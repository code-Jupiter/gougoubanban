package com.littlekitty.xiaokele.scene

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.channel.ChannelExecutionCoordinator
import com.littlekitty.xiaokele.chat.CharacterCoreBootstrap
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.common.scene.SceneCandidateThresholds
import com.littlekitty.xiaokele.core.common.scene.SceneThresholdProvider
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.MutableTimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.policy.PolicyResolver
import com.littlekitty.xiaokele.core.database.policy.ProductScenePolicyCatalog
import com.littlekitty.xiaokele.core.database.repository.BackgroundMutationExecutor
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheEmissionGuard
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheRepository
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.network.model.ModelService
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.event.BackgroundCompanionPipeline
import com.littlekitty.xiaokele.event.BackgroundContextBuilder
import com.littlekitty.xiaokele.event.EventNormalizer
import com.littlekitty.xiaokele.event.LocalBackgroundDispatcher
import com.littlekitty.xiaokele.external.ExternalContextResolver
import com.littlekitty.xiaokele.external.LocationSnapshot
import com.littlekitty.xiaokele.external.ExternalEventContract
import com.littlekitty.xiaokele.notification.NotificationAdapter
import com.littlekitty.xiaokele.notification.NotificationSendRequest
import com.littlekitty.xiaokele.notification.NotificationSendResult
import com.littlekitty.xiaokele.widget.WidgetRefreshResult
import com.littlekitty.xiaokele.widget.WidgetStateAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.time.Instant

class MutableSceneThresholds(initial: SceneCandidateThresholds = SceneCandidateThresholds()) :
    SceneThresholdProvider {
    @Volatile
    var currentThresholds: SceneCandidateThresholds = initial
    override fun current(): SceneCandidateThresholds = currentThresholds
}

class TestNotificationRecorder(
    var result: NotificationSendResult = NotificationSendResult.Delivered,
) : NotificationAdapter {
    val sent = mutableListOf<NotificationSendRequest>()
    override suspend fun send(request: NotificationSendRequest): NotificationSendResult {
        sent += request
        return result
    }
}

class TestWidgetRecorder : WidgetStateAdapter {
    var refreshCount = 0
    override suspend fun refreshCurrentPetState(): WidgetRefreshResult {
        refreshCount++
        return WidgetRefreshResult.Refreshed(emptyList())
    }
}

class RawDecisionModelService(
    private val rawJson: String,
) : ModelService {
    override val serviceName: String = "test_raw_decision"
    var calls = 0
    override suspend fun generate(context: ContextPack): ModelServiceResult {
        calls++
        return ModelServiceResult.Success(rawJson)
    }
}

class NoActionRecordingModel : ModelService {
    override val serviceName: String = "test_no_action_recording"
    val processedEventIds = mutableListOf<String>()
    override suspend fun generate(context: ContextPack): ModelServiceResult {
        val eventId = context.event?.get("id")?.toString()?.trim('"').orEmpty()
        processedEventIds += eventId
        return ModelServiceResult.Success(
            com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(
                AgentDecision(decision = DecisionNames.NO_ACTION, decisionSummary = "test no action"),
            ),
        )
    }
}

class Phase7TestContext(
    val db: AppDatabase,
    val time: MutableTimeProvider,
    val thresholds: MutableSceneThresholds,
    val contextResolver: ExternalContextResolver,
    val notification: TestNotificationRecorder,
    val widget: TestWidgetRecorder,
) {

    fun buildCoordinator(): ChannelExecutionCoordinator = ChannelExecutionCoordinator(
        db = db,
        deliveryOutbox = DeliveryOutbox(db, db.deliveryHistoryDao()),
        notificationAdapter = notification,
        widgetStateAdapter = widget,
        animationAdapter = CatalogAnimationAdapter(),
        timeProvider = time,
        appContext = ApplicationProvider.getApplicationContext(),
    )

    fun buildPipeline(model: ModelService): BackgroundCompanionPipeline = BackgroundCompanionPipeline(
        db = db,
        modelService = model,
        policyResolver = PolicyResolver(db),
        contextBuilder = BackgroundContextBuilder(
            db = db,
            timeProvider = time,
            externalContextResolver = contextResolver,
        ),
        mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
        animationAdapter = CatalogAnimationAdapter(),
        timeProvider = time,
        channelCoordinator = buildCoordinator(),
        sceneGate = ScenePreconditionGate(db, contextResolver, thresholds, time),
    )

    fun buildDispatcher(model: ModelService): LocalBackgroundDispatcher = LocalBackgroundDispatcher(
        normalizer = EventNormalizer(db.eventDao(), time),
        pipeline = buildPipeline(model),
    )

    fun buildScheduler(pipeline: BackgroundCompanionPipeline): SceneCheckScheduler = SceneCheckScheduler(
        db = db,
        pipeline = pipeline,
        dispatcher = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), time), pipeline),
        contextResolver = contextResolver,
        thresholdProvider = thresholds,
        timeProvider = time,
    )

    /** Mirrors one cached foreground fix; the public edition has a single place. */
    suspend fun cacheLocation(observedAt: Instant = time.now()) {
        db.externalCacheDao().upsert(
            com.littlekitty.xiaokele.core.database.entity.ExternalCacheEntity(
                cacheKey = ExternalEventContract.LOCATION_CACHE_KEY,
                source = ExternalEventContract.LOCATION_CACHE_SOURCE,
                payloadJson = com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(
                    LocationSnapshot(
                        latitude = 31.2304,
                        longitude = 121.4737,
                        accuracyMeters = 20.0,
                        observedAtEpochMillis = observedAt.toEpochMilli(),
                    ),
                ),
                fetchedAt = observedAt.toEpochMilli(),
                expiresAt = observedAt.toEpochMilli() + ExternalEventContract.LOCATION_CACHE_TTL_MILLIS,
            ),
        )
    }

    suspend fun insertEvent(
        type: String,
        key: String,
        payload: JsonObject,
        priority: Int,
        source: String = "TEST_SCENE",
        receivedAt: Instant = time.now(),
        expiresAt: Instant = time.now().plusSeconds(3600),
        status: String = EventStatuses.RECEIVED,
    ): EventEntity {
        val entity = EventEntity(
            id = com.littlekitty.xiaokele.core.common.id.EntityId.generate("evt").value,
            eventKey = key,
            type = type,
            source = source,
            payloadJson = payload.toString(),
            occurredAt = receivedAt.toEpochMilli(),
            receivedAt = receivedAt.toEpochMilli(),
            expiresAt = expiresAt.toEpochMilli(),
            priority = priority,
            status = status,
        )
        db.eventDao().upsert(entity)
        return entity
    }

    suspend fun insertUserMessage(content: String = "hi", at: Instant = time.now()) {
        db.messageDao().upsert(
            MessageEntity(
                id = com.littlekitty.xiaokele.core.common.id.EntityId.generate("msg").value,
                role = "USER",
                content = content,
                createdAt = at.toEpochMilli(),
                status = MessageStatuses.COMPLETED,
            ),
        )
    }

    fun close() {
        db.close()
    }
}

fun buildPhase7TestContext(now: Instant = Instant.parse("2026-08-16T10:00:00Z")): Phase7TestContext {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val time = MutableTimeProvider(now)
    val thresholds = MutableSceneThresholds()
    val guard = ExternalCacheEmissionGuard(db.externalCacheDao())
    val externalRepo = ExternalCacheRepository(db, db.externalCacheDao(), guard)
    val contextResolver = ExternalContextResolver(db, time)
    runBlocking {
        CharacterCoreBootstrap.ensureSeeded(db, now.toEpochMilli())
        ProductScenePolicyCatalog.ensureSeeded(db)
    }
    return Phase7TestContext(
        db = db,
        time = time,
        thresholds = thresholds,
        contextResolver = contextResolver,
        notification = TestNotificationRecorder(),
        widget = TestWidgetRecorder(),
    )
}
