package com.littlekitty.xiaokele.scene

import com.littlekitty.xiaokele.core.common.scene.SceneCooldown
import com.littlekitty.xiaokele.core.common.protocol.BehaviorTendencyKeyNames
import com.littlekitty.xiaokele.core.common.scene.SceneThresholdProvider
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.repository.BehaviorTendencyRepository
import com.littlekitty.xiaokele.external.ExternalContextResolver
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic scene precondition gate.
 *
 * It runs before PolicyResolver/model output and only decides ALLOW or
 * NO_ACTION. It never writes user-visible state and never generates copy.
 * The gate owns the frozen cross-cutting rules:
 * - ordinary weather is silent; non-HIGH alerts and insignificant changes are silent;
 * - nearby candidates need a distance threshold;
 * - one delivered scene outreach cools the same scene group across channels.
 */
class ScenePreconditionGate(
    private val db: AppDatabase,
    private val contextResolver: ExternalContextResolver,
    private val thresholdProvider: SceneThresholdProvider,
    private val timeProvider: TimeProvider,
    private val behaviorTendencyRepository: BehaviorTendencyRepository? = null,
) {
    private val thresholds = { thresholdProvider.current() }

    private val evaluators: List<SceneEvaluator> = listOf(
        InteractionSceneEvaluator(thresholds),
        DailyWeatherSceneEvaluator(),
        WeatherSceneEvaluator(thresholds),
        NearbySceneEvaluator(thresholds),
    )

    suspend fun evaluate(event: EventEntity, sceneCooldownMillisOverride: Long? = null): SceneGateResult {
        val now = timeProvider.now().toEpochMilli()
        val context = SceneContextSnapshot(
            nowEpochMillis = now,
            locationSnapshot = contextResolver.resolveLocationSnapshot(now),
            petState = db.petStateDao().get(),
            lastInteractionAtEpochMillis = db.messageDao().latestInteractionAtEpochMillis(),
        )

        val evaluation = evaluators.firstNotNullOfOrNull { it.evaluate(event, context) }
            ?: SceneEvaluation.allow(
                reasonCode = "no_scene_precondition_for_event_type",
                sceneGroup = SceneCooldown.forEventType(event.type),
                summary = "无专门前置规则，按既有 Scene Policy 处理。",
            )
        if (evaluation.decision == SceneDecision.NO_ACTION) {
            return SceneGateResult.ForceNoAction(evaluation)
        }

        val cooldownBlock = cooldownBlock(evaluation, now, sceneCooldownMillisOverride)
        if (cooldownBlock != null) {
            return SceneGateResult.ForceNoAction(cooldownBlock)
        }
        // The daily proactive budget is enforced at channel execution
        // (ChannelExecutionCoordinator), not here: this gate must not block
        // internal scenes (PetState/widget) that never start a conversation.
        return SceneGateResult.Allow(evaluation)
    }

    private suspend fun cooldownBlock(
        evaluation: SceneEvaluation,
        now: Long,
        sceneCooldownMillisOverride: Long?,
    ): SceneEvaluation? {
        val group = evaluation.sceneGroup ?: return null
        val baseCooldownMillis = sceneCooldownMillisOverride ?: thresholds().sceneCooldownMillis
        val proactiveLevel = behaviorTendencyRepository?.activeTendencies()
            ?.firstOrNull { it.key == BehaviorTendencyKeyNames.PROACTIVE_CONTACT_LEVEL }
            ?.currentValue
            .let(ProactiveTendencyPolicy::normalizedLevel)
        val cooldownMillis = if (sceneCooldownMillisOverride != null) {
            baseCooldownMillis
        } else {
            ProactiveTendencyPolicy.cooldownMillis(baseCooldownMillis, proactiveLevel)
        }
        val latest = db.deliveryHistoryDao().latestDeliveredForCooldownGroupSince(
            cooldownGroup = group,
            after = now - cooldownMillis,
        ) ?: return null
        return SceneEvaluation.noAction(
            reasonCode = "scene_cooldown_active",
            sceneGroup = group,
            summary = "同一场景跨渠道冷却中。",
            derivedFields = mapOf(
                "cooldown_group" to JsonPrimitive(group),
                "last_delivered_at" to JsonPrimitive(latest.deliveredAt),
                "cooldown_millis" to JsonPrimitive(cooldownMillis),
            ),
        )
    }
}
