package com.littlekitty.xiaokele.discovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CancellationException
import com.littlekitty.xiaokele.event.EventDispatchResult
import com.littlekitty.xiaokele.external.ExternalEventContract
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.koin.java.KoinJavaComponent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class DiscoverySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val service = KoinJavaComponent.getOrNull<DiscoveryFeedService>(DiscoveryFeedService::class.java)
            ?: return Result.failure()
        val store = KoinJavaComponent.getOrNull<ByokSettingsStore>(ByokSettingsStore::class.java)
            ?: return Result.failure()
        val locationResolver = KoinJavaComponent.getOrNull<DiscoveryLocationResolver>(DiscoveryLocationResolver::class.java)
            ?: return Result.failure()
        val diagnostics = KoinJavaComponent.getOrNull<DiagnosticsLogger>(DiagnosticsLogger::class.java)
        diagnostics?.taskStarted("discovery_sync", UNIQUE_PERIODIC)
        val cache = DiscoveryCacheStore(applicationContext)
        val previousKeys = cache.load().map { discoveryKey(it) }.toSet()
        val location = locationResolver.resolve()
        if (location == null) {
            diagnostics?.taskFinished("discovery_sync", DiagnosticLogStatuses.SUCCESS, "no_location")
            return Result.success()
        }
        val result = service.fetchDaily(
            areas = setOf(location.area),
            categories = store.selectedCategories().ifEmpty { setOf("events", "food") },
        )
        return try {
            when (result) {
            is DiscoveryFeedResult.Success -> {
                cache.save(result.items, result.generatedAt)
                if (store.notificationsEnabled()) {
                    val newKeys: Set<String> = result.items
                        .map { discoveryKey(it) }
                        .filterNot { previousKeys.contains(it) }
                        .toSet()
                    val mentionStore = DiscoveryMentionStore(applicationContext)
                    // Candidate pool is the whole current cache minus the
                    // already-mentioned registry, so items the model once
                    // declined stay eligible instead of being lost to the
                    // daily cache diff; brand-new entries sort first.
                    val candidates = cache.load()
                        .filterNot { mentionStore.isMentioned(it) }
                        .sortedWith(
                            compareByDescending<DiscoveryItem> { newKeys.contains(discoveryKey(it)) }
                                .thenByDescending { it.score },
                        )
                        .take(ExternalEventContract.DISCOVERY_NUDGE_MAX_ITEMS)
                    if (candidates.isNotEmpty()) {
                        val dispatcher = KoinJavaComponent.getOrNull<com.littlekitty.xiaokele.event.LocalBackgroundDispatcher>(
                            com.littlekitty.xiaokele.event.LocalBackgroundDispatcher::class.java,
                        )
                        val timeProvider = KoinJavaComponent.getOrNull<TimeProvider>(TimeProvider::class.java)
                        if (dispatcher != null && timeProvider != null) {
                            val outcome = DiscoveryNudgeDispatcher.maybeDispatchOncePerDay(
                                dispatcher = dispatcher,
                                timeProvider = timeProvider,
                                items = candidates,
                            )
                            markMentionedAfterRun(mentionStore, outcome, candidates)
                        }
                    }
                }
                Result.success().also { diagnostics?.taskFinished("discovery_sync", DiagnosticLogStatuses.SUCCESS) }
            }

            is DiscoveryFeedResult.Empty -> {
                cache.save(emptyList(), null)
                Result.success().also { diagnostics?.taskFinished("discovery_sync", DiagnosticLogStatuses.SUCCESS, "EMPTY") }
            }

            is DiscoveryFeedResult.Failure -> if (
                result.error.technicalDetail == "persistent_generation_quota_exhausted"
            ) {
                // Durable quota denials are terminal for this fetch. Retrying
                // the Worker would only spin while the reservation remains
                // denied and would look like an implicit budget top-up.
                Result.success().also {
                    diagnostics?.taskFinished("discovery_sync", DiagnosticLogStatuses.NO_ACTION, "generation_quota_exhausted")
                }
            } else {
                Result.retry().also {
                    diagnostics?.taskFinished("discovery_sync", DiagnosticLogStatuses.RETRY, "provider_failure")
                }
            }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            diagnostics?.taskFinished("discovery_sync", DiagnosticLogStatuses.FAILED, error.message)
            Result.retry()
        }
    }

    /** Registers only an exact item represented by a chat message. */
    private suspend fun markMentionedAfterRun(
        mentionStore: DiscoveryMentionStore,
        outcome: EventDispatchResult,
        candidates: List<DiscoveryItem>,
    ) {
        val processed = outcome as? EventDispatchResult.Processed ?: return
        val planExecuted = processed.processing as?
            com.littlekitty.xiaokele.event.BackgroundProcessingResult.PlanExecuted ?: return
        val proactiveWasAttempted = planExecuted.channelReport.results.any {
            it.actionType == com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry.SEND_PROACTIVE_MESSAGE &&
                it.status in setOf(ActionExecutionStatuses.COMPLETED, ActionExecutionStatuses.FAILED)
        }
        if (!proactiveWasAttempted) return
        val db = KoinJavaComponent.getOrNull<com.littlekitty.xiaokele.core.database.AppDatabase>(
            com.littlekitty.xiaokele.core.database.AppDatabase::class.java,
        )
        val messageReachedChat = db?.messageDao()?.assistantForEvent(processed.eventId) != null
        val toMark = selectDiscoveryItemsToMark(
            messageReachedChat = messageReachedChat,
            candidates = candidates,
            todos = db?.todoDao()?.bySourceEventId(processed.eventId).orEmpty(),
        )
        if (toMark.isEmpty()) return
        mentionStore.markMentioned(toMark)
    }

    companion object {
        const val UNIQUE_PERIODIC = "discovery_sync_periodic_v2"
        const val UNIQUE_PERIODIC_LEGACY = "discovery_sync_periodic_v1"
        const val UNIQUE_REFRESH = "discovery_refresh_foreground_v1"
        const val PERIODIC_INTERVAL_HOURS = 24L

        /** Cache/registry key covering both identifiers of an item. */
        fun discoveryKey(item: DiscoveryItem): String = item.id + "|" + item.sourceUrl

        fun enqueuePeriodic(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_LEGACY)
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DiscoverySyncWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(networkConstraints())
                    .build(),
            )
        }

        fun enqueueRefresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_REFRESH,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DiscoverySyncWorker>()
                    .setConstraints(networkConstraints())
                    .build(),
            )
        }

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}

/**
 * Conservative business rule: no chat message means the user had no chance to
 * see the recommendation, and a missing/unknown item_id never marks the whole
 * candidate batch by guesswork.
 */
internal fun selectDiscoveryItemsToMark(
    messageReachedChat: Boolean,
    candidates: List<DiscoveryItem>,
    todos: List<TodoEntity>,
): List<DiscoveryItem> {
    if (!messageReachedChat) return emptyList()
    val mentionedIds = todos.mapNotNull { todo ->
        runCatching {
            JsonCodec.decode<kotlinx.serialization.json.JsonObject>(todo.payloadJson ?: "{}")
        }.getOrNull()?.get("item_id")?.jsonPrimitive?.contentOrNull
    }.toSet()
    if (mentionedIds.isEmpty()) return emptyList()
    return candidates.filter { it.id in mentionedIds }
}

/** Runs one independent search per enabled category so both knowledge lanes are refreshed daily. */
suspend fun DiscoveryFeedService.fetchDaily(
    areas: Set<String>,
    categories: Set<String>,
): DiscoveryFeedResult {
    val requestedCategories = listOf("food", "events").filter(categories::contains)
    if (requestedCategories.isEmpty()) return DiscoveryFeedResult.Empty

    val results = requestedCategories.map { category ->
        fetch(areas = areas, categories = setOf(category))
    }
    val failure = results.filterIsInstance<DiscoveryFeedResult.Failure>().firstOrNull()
    if (failure != null) return failure

    val items = results
        .filterIsInstance<DiscoveryFeedResult.Success>()
        .flatMap { it.items }
        .distinctBy { it.sourceUrl }
        .sortedWith(compareBy<DiscoveryItem> { it.category }.thenByDescending { it.score })
    if (items.isEmpty()) return DiscoveryFeedResult.Empty

    return DiscoveryFeedResult.Success(
        items = items,
        generatedAt = results.filterIsInstance<DiscoveryFeedResult.Success>()
            .mapNotNull { it.generatedAt }
            .firstOrNull(),
    )
}

/**
 * Turns fresh discovery items into a once-per-day DISCOVERY_TOPIC_NUDGE
 * candidate. The companion pipeline (scene gate + model) decides whether and
 * how to mention one item lightly; details live in the follow-up todo.
 */
object DiscoveryNudgeDispatcher {

    private val ZONE = TimeProvider.DISPLAY_ZONE

    suspend fun maybeDispatchOncePerDay(
        dispatcher: com.littlekitty.xiaokele.event.LocalBackgroundDispatcher,
        timeProvider: TimeProvider,
        items: List<DiscoveryItem>,
    ): EventDispatchResult {
        if (items.isEmpty()) return EventDispatchResult.Expired("no_items")
        val now = timeProvider.now()
        val nowEpoch = now.toEpochMilli()
        val localDate = DateTimeFormatter.BASIC_ISO_DATE.format(now.atZone(ZONE))
        val eventKey = ExternalEventContract.discoveryNudgeEventKey(localDate)
        val expiresAt = nowEpoch + NUDGE_EVENT_TTL_MILLIS
        val event = NormalizedEvent(
            eventKey = eventKey,
            type = ExternalEventContract.EVENT_TYPE_DISCOVERY_TOPIC_NUDGE,
            source = ExternalEventContract.DISCOVERY_NUDGE_EVENT_SOURCE,
            occurredAt = InstantJson.formatUtc(now),
            receivedAt = InstantJson.formatUtc(now),
            expiresAt = InstantJson.formatUtc(Instant.ofEpochMilli(expiresAt)),
            priority = ScenePriorities.LOCAL_CANDIDATE_DEFAULT,
            payload = buildJsonObject {
                put("local_date", localDate)
                putJsonArray("items") {
                    items.forEach { item ->
                        add(buildJsonObject {
                            put("item_id", item.id)
                            put("title", item.title)
                            put("category", item.category)
                            put("summary", item.summary.take(MAX_SUMMARY_CHARS))
                            put("source_url", item.sourceUrl)
                            item.eventStart?.let { put("event_start", it) }
                        })
                    }
                }
            },
        )
        return dispatcher.dispatch(event)
    }

    private const val NUDGE_EVENT_TTL_MILLIS = 12L * 60L * 60L * 1000L
    private const val MAX_SUMMARY_CHARS = 200
}
