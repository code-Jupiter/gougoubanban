package com.littlekitty.xiaokele.chat

import com.littlekitty.xiaokele.core.common.json.JsonCodec

import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.network.external.WeatherFetchResult
import com.littlekitty.xiaokele.core.network.external.WeatherLocation
import com.littlekitty.xiaokele.core.network.external.WeatherQueryProfile
import com.littlekitty.xiaokele.core.network.external.WeatherService
import com.littlekitty.xiaokele.discovery.CurrentLocationResolver
import com.littlekitty.xiaokele.external.ExternalContextResolver
import com.littlekitty.xiaokele.external.ExternalSyncOutcomes
import com.littlekitty.xiaokele.external.LocationSnapshotRefresher
import com.littlekitty.xiaokele.creative.CreativeLibraryAccess
import com.littlekitty.xiaokele.creative.SongLyricSearchResult
import com.littlekitty.xiaokele.creative.SongLyricSearchService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Executes only the small, explicitly allowlisted tool set exposed to front chat. */
class DefaultLocalChatToolExecutor(
    private val weatherService: WeatherService,
    private val contextResolver: ExternalContextResolver,
    private val timeProvider: TimeProvider,
    private val currentLocationResolver: CurrentLocationResolver,
    private val creativeLibraryAccess: CreativeLibraryAccess? = null,
    private val songLyricSearchService: SongLyricSearchService? = null,
    private val locationSnapshotRefresher: LocationSnapshotRefresher? = null,
    /** A-model evidence collection must not write durable location state. */
    private val allowDurableWrites: Boolean = true,
) : LocalChatToolExecutor {

    override suspend fun execute(call: FrontChatToolCall): JsonObject {
        val result = runCatching {
            when (call.name) {
                TOOL_QUERY_WEATHER -> queryWeather(call.arguments)
                TOOL_QUERY_LOCATION -> queryLocation()
                TOOL_SEARCH_SONG_LYRICS -> searchSongLyrics(call.arguments)
                TOOL_LIST_CREATIVE_TOPICS -> listCreativeTopics(call.arguments)
                TOOL_READ_CREATIVE_CANDIDATE -> readCreativeCandidate(call.arguments)
                TOOL_READ_CREATIVE_WORK -> readCreativeWork(call.arguments)
                TOOL_READ_CREATIVE_SESSION -> readCreativeSession(call.arguments)
                TOOL_MARK_CREATIVE_TOPIC_DISCUSSED -> markCreativeTopicDiscussed(call.arguments)
                TOOL_SAVE_CREATIVE_SESSION_PROGRESS -> saveCreativeSessionProgress(call.arguments)
                else -> errorResult("unknown_tool", "不支持的工具：${call.name}")
            }
        }.getOrElse { throwable ->
            errorResult("tool_execution_failed", throwable.message ?: "工具执行失败")
        }
        return buildJsonObject {
            put("type", "tool_result")
            put("tool_name", call.name)
            put("arguments", call.arguments)
            put("result", result)
        }
    }

    private suspend fun queryWeather(arguments: JsonObject): JsonObject {
        val now = timeProvider.now().toEpochMilli()
        val current = currentLocationResolver.resolveCurrent()
            ?: return errorResult(
                "location_unavailable",
                "无法获取手机当前定位，请确认定位服务已开启并授予定位权限",
                source = "DEVICE_CURRENT_LOCATION",
            )
        val locationReport = if (allowDurableWrites) {
            val refresher = locationSnapshotRefresher
                ?: return errorResult("location_refresh_unavailable", "最新定位工作流暂不可用，暂时不能查询天气")
            refresher.persistFreshLocation(current.fix)
        } else {
            null
        }
        if (locationReport?.outcome == ExternalSyncOutcomes.FAILED) {
            return errorResult(
                "location_refresh_failed",
                "无法保存本次最新定位，暂时不能查询天气",
                source = "DEVICE_CURRENT_LOCATION_FRESH",
                locationRefreshOutcome = locationReport.outcome,
            )
        }
        if (locationReport?.outcome in setOf(
                ExternalSyncOutcomes.SKIPPED_LOCATION_UNUSABLE,
                ExternalSyncOutcomes.SKIPPED_NO_LOCATION,
                ExternalSyncOutcomes.IGNORED_STALE_CACHE,
            )
        ) {
            return errorResult(
                "location_unavailable",
                "本次定位精度不足，暂时不能查询天气",
                source = "DEVICE_CURRENT_LOCATION_FRESH",
                locationRefreshOutcome = locationReport?.outcome,
            )
        }
        // Read-only model evidence must use the fix just obtained rather than
        // a possibly stale persisted snapshot.
        val currentLocation = if (allowDurableWrites) contextResolver.resolveLocationSnapshot(now) else null
        val latitude = currentLocation?.latitude ?: current.fix.latitude
        val longitude = currentLocation?.longitude ?: current.fix.longitude
        val placeType = currentLocation?.placeType ?: "UNKNOWN"
        val city = current.city
        val profile = when (arguments.string("profile")?.uppercase()) {
            WeatherQueryProfile.SHIFT_COMMUTE.name -> WeatherQueryProfile.SHIFT_COMMUTE
            WeatherQueryProfile.DAILY_BRIEF.name -> WeatherQueryProfile.DAILY_BRIEF
            WeatherQueryProfile.FULL.name -> WeatherQueryProfile.FULL
            else -> WeatherQueryProfile.CORE
        }
        return when (val response = weatherService.fetch(
            WeatherLocation(placeType, latitude, longitude, city),
            profile,
        )) {
            is WeatherFetchResult.Success -> buildJsonObject {
                put("ok", true)
                put("source", if (allowDurableWrites) "DEVICE_CURRENT_LOCATION_FRESH" else "DEVICE_CURRENT_LOCATION_READ_ONLY")
                locationReport?.outcome?.let { put("location_refresh_outcome", it) }
                put("data", JsonCodec.json.encodeToJsonElement(response.snapshot))
            }

            WeatherFetchResult.Empty -> errorResult(
                "weather_empty",
                "没有返回天气数据",
                source = if (allowDurableWrites) "DEVICE_CURRENT_LOCATION_FRESH" else "DEVICE_CURRENT_LOCATION_READ_ONLY",
                locationRefreshOutcome = locationReport?.outcome,
            )
            is WeatherFetchResult.Failure -> errorResult(
                "weather_unavailable",
                // Name the actual cause. "Service temporarily unavailable" invites
                // the user to retry forever when the real problem is that no
                // QWeather credential is configured, which retrying cannot fix.
                when (response.error.code) {
                    com.littlekitty.xiaokele.core.common.error.ErrorCode.WEATHER_NOT_CONFIGURED ->
                        "还没有配置天气服务（和风天气 Key），暂时不能查询天气、也没有可用的天气信息"

                    else -> "天气服务暂时不可用"
                },
                source = if (allowDurableWrites) "DEVICE_CURRENT_LOCATION_FRESH" else "DEVICE_CURRENT_LOCATION_READ_ONLY",
                locationRefreshOutcome = locationReport?.outcome,
            )
        }
    }

    private suspend fun queryLocation(): JsonObject {
        val current = currentLocationResolver.resolveCurrent()
            ?: return errorResult(
                "location_unavailable",
                "无法获取手机当前定位，请确认定位服务已开启并授予定位权限",
            )
        val fix = current.fix
        return buildJsonObject {
            put("ok", true)
            put("source", "DEVICE_CURRENT_LOCATION")
            put("data", buildJsonObject {
                put("latitude", fix.latitude)
                put("longitude", fix.longitude)
                put("accuracy_meters", fix.accuracyMeters)
                current.province?.let { put("province", it) }
                current.city?.let { put("city", it) }
                current.district?.let { put("district", it) }
                current.searchArea?.let { put("search_area", it) }
                if (current.city == null && current.district == null) {
                    put(
                        "note",
                        "本次反向地理编码不可用，结果只有坐标，没有城市/区县数据。" +
                            "回复时必须明确城市和区县均无法确认，不得根据坐标猜测城市；" +
                            "不要向用户复述精确经纬度。",
                    )
                }
                put("fresh", true)
                put("observed_at_epoch_millis", timeProvider.now().toEpochMilli())
            })
        }
    }

    private suspend fun searchSongLyrics(arguments: JsonObject): JsonObject {
        val service = songLyricSearchService ?: return errorResult("song_search_unavailable", "歌曲检索暂不可用")
        val query = arguments.string("query") ?: return errorResult("invalid_song_query", "缺少歌曲或歌词意境")
        return when (val result = service.search(query, arguments.string("artist_hint"))) {
            is SongLyricSearchResult.Success -> buildJsonObject {
                put("ok", true)
                put("source_kind", "SONG_LYRIC_MATCH")
                put("song_title", result.match.songTitle)
                put("artist", result.match.artist)
                put("lyric_excerpt", result.match.lyricExcerpt)
                put("match_reason", result.match.matchReason)
                put("confidence", result.match.confidence)
                put("no_playback_link", true)
                put("no_complete_lyrics", true)
            }
            is SongLyricSearchResult.Unavailable -> errorResult("song_search_unavailable", result.reason)
        }
    }

    private suspend fun listCreativeTopics(arguments: JsonObject): JsonObject {
        val access = creativeLibraryAccess ?: return errorResult("creative_unavailable", "创作素材库暂不可用")
        val limit = arguments.number("limit")?.toInt()?.coerceIn(1, 3) ?: 3
        return buildJsonObject {
            put("ok", true)
            put("source_kind", "CREATIVE_TOPIC_SUMMARIES")
            put("topics", kotlinx.serialization.json.buildJsonArray { access.listAvailableTopics(limit).forEach(::add) })
        }
    }

    private suspend fun readCreativeWork(arguments: JsonObject): JsonObject {
        val access = creativeLibraryAccess ?: return errorResult("creative_unavailable", "创作素材库暂不可用")
        val workId = arguments.string("work_id") ?: return errorResult("invalid_work_id", "缺少 work_id")
        return access.readWork(workId) ?: errorResult("creative_work_unavailable", "作品不存在、已失效或不可读取")
    }

    private suspend fun readCreativeCandidate(arguments: JsonObject): JsonObject {
        val access = creativeLibraryAccess ?: return errorResult("creative_unavailable", "创作素材库暂不可用")
        val candidateId = arguments.string("candidate_id") ?: return errorResult("invalid_candidate_id", "缺少 candidate_id")
        return access.readCandidate(candidateId) ?: errorResult("creative_candidate_unavailable", "候选不存在、已失效或不可读取")
    }

    private suspend fun readCreativeSession(arguments: JsonObject): JsonObject {
        val access = creativeLibraryAccess ?: return errorResult("creative_unavailable", "创作素材库暂不可用")
        val sessionId = arguments.string("session_id") ?: return errorResult("invalid_session_id", "缺少 session_id")
        return access.readSession(sessionId) ?: errorResult("creative_session_unavailable", "创作会话不存在或已结束")
    }

    private suspend fun markCreativeTopicDiscussed(arguments: JsonObject): JsonObject {
        val access = creativeLibraryAccess ?: return errorResult("creative_unavailable", "创作素材库暂不可用")
        val topicId = arguments.string("topic_id") ?: return errorResult("invalid_topic_id", "缺少 topic_id")
        return buildJsonObject { put("ok", access.markTopicDiscussed(topicId)) }
    }

    private suspend fun saveCreativeSessionProgress(arguments: JsonObject): JsonObject {
        val access = creativeLibraryAccess ?: return errorResult("creative_unavailable", "创作素材库暂不可用")
        val sessionId = arguments.string("session_id") ?: return errorResult("invalid_session_id", "缺少 session_id")
        val saved = access.saveSessionProgress(
            sessionId = sessionId,
            stage = arguments.string("stage") ?: "ACTIVE",
            outline = arguments.string("outline"),
            direction = arguments.string("direction"),
            lastMessageId = arguments.string("last_message_id"),
        )
        return buildJsonObject { put("ok", saved) }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Double? =
        this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

    private fun errorResult(
        code: String,
        message: String,
        source: String? = null,
        locationRefreshOutcome: String? = null,
    ): JsonObject = buildJsonObject {
        put("ok", false)
        put("error_code", code)
        put("message", message)
        source?.let { put("source", it) }
        locationRefreshOutcome?.let { put("location_refresh_outcome", it) }
    }

    companion object {
        const val TOOL_QUERY_WEATHER = "query_weather"
        const val TOOL_QUERY_LOCATION = "query_location"
        const val TOOL_SEARCH_SONG_LYRICS = "search_song_lyrics"
        const val TOOL_LIST_CREATIVE_TOPICS = "list_available_creative_topics"
        const val TOOL_READ_CREATIVE_CANDIDATE = "read_creative_candidate"
        const val TOOL_READ_CREATIVE_WORK = "read_creative_work"
        const val TOOL_READ_CREATIVE_SESSION = "read_creative_session"
        const val TOOL_MARK_CREATIVE_TOPIC_DISCUSSED = "mark_topic_discussed"
        const val TOOL_SAVE_CREATIVE_SESSION_PROGRESS = "save_creative_session_progress"
    }
}
