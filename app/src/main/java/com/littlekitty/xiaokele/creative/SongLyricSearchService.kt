package com.littlekitty.xiaokele.creative

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

data class SongLyricMatch(
    val songTitle: String,
    val artist: String,
    val lyricExcerpt: String,
    val matchReason: String,
    val confidence: Double,
    /** Kept for diagnostics only; never returned to the chat model. */
    val internalSourceReference: String,
)

sealed interface SongLyricSearchResult {
    data class Success(val match: SongLyricMatch) : SongLyricSearchResult
    data class Unavailable(val reason: String) : SongLyricSearchResult
}

interface SongLyricSearchService {
    suspend fun search(query: String, artistHint: String? = null): SongLyricSearchResult
}

/**
 * Consent-gated, fail-closed song lookup. iTunes disambiguates the title and
 * artist; lyrics.ovh is used only to verify a short excerpt. No playback URL
 * or complete lyrics are returned to the model or the user.
 */
class PublicSongLyricSearchService(
    private val transport: HttpTransport,
) : SongLyricSearchService {

    override suspend fun search(query: String, artistHint: String?): SongLyricSearchResult {
        val normalizedQuery = query.trim().take(MAX_QUERY_LENGTH)
        if (normalizedQuery.isBlank()) return SongLyricSearchResult.Unavailable("空查询")
        val searchUrl = "https://itunes.apple.com/search?term=${urlEncode(normalizedQuery)}&entity=song&limit=5&country=CN"
        val searchResponse = transport.execute(HttpRequest(HttpMethod.GET, searchUrl))
        val tracks = when (searchResponse) {
            is NetworkResult.Failure -> return SongLyricSearchResult.Unavailable("歌曲检索网络不可用")
            is NetworkResult.Success -> if (searchResponse.statusCode in 200..299) {
                parseTracks(searchResponse.body)
            } else {
                emptyList()
            }
        }
        if (tracks.isEmpty()) return SongLyricSearchResult.Unavailable("没有可靠的歌曲匹配")
        val filtered = artistHint?.trim()?.takeIf { it.isNotBlank() }?.let { hint ->
            tracks.filter { it.second.contains(hint, ignoreCase = true) }.ifEmpty { tracks }
        } ?: tracks
        for ((title, artist, previewUrl) in filtered.take(MAX_TRACKS_TO_VERIFY)) {
            val lyricsUrl = "https://api.lyrics.ovh/v1/${urlEncode(artist)}/${urlEncode(title)}"
            val lyricsResponse = transport.execute(HttpRequest(HttpMethod.GET, lyricsUrl))
            val lyrics = (lyricsResponse as? NetworkResult.Success)
                ?.takeIf { it.statusCode in 200..299 }
                ?.let { response ->
                    runCatching {
                        JsonCodec.json.parseToJsonElement(response.body).jsonObject["lyrics"]
                            ?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: continue
            return SongLyricSearchResult.Success(
                SongLyricMatch(
                    songTitle = title,
                    artist = artist,
                    lyricExcerpt = shortExcerpt(lyrics),
                    matchReason = "歌名、歌手与歌词片段都完成了独立匹配。",
                    confidence = if (artistHint != null && artist.contains(artistHint, ignoreCase = true)) 0.90 else 0.78,
                    internalSourceReference = previewUrl,
                ),
            )
        }
        return SongLyricSearchResult.Unavailable("无法可靠核对歌词片段")
    }

    private fun parseTracks(body: String): List<Triple<String, String, String>> = runCatching {
        JsonCodec.json.parseToJsonElement(body).jsonObject["results"]!!.jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            val title = item["trackName"]?.jsonPrimitive?.contentOrNull?.trim()
            val artist = item["artistName"]?.jsonPrimitive?.contentOrNull?.trim()
            val preview = item["trackViewUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (title.isNullOrBlank() || artist.isNullOrBlank()) null else Triple(title, artist, preview)
        }
    }.getOrDefault(emptyList())

    private fun shortExcerpt(lyrics: String): String {
        val line = lyrics.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }.orEmpty()
        return if (line.any { it in '\u4e00'..'\u9fff' }) {
            line.take(MAX_CJK_EXCERPT_CHARS)
        } else {
            line.split(Regex("\\s+")).take(MAX_ENGLISH_EXCERPT_WORDS).joinToString(" ").take(MAX_ENGLISH_EXCERPT_CHARS)
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val MAX_QUERY_LENGTH = 120
        const val MAX_TRACKS_TO_VERIFY = 3
        const val MAX_CJK_EXCERPT_CHARS = 20
        const val MAX_ENGLISH_EXCERPT_WORDS = 10
        const val MAX_ENGLISH_EXCERPT_CHARS = 72
    }
}
