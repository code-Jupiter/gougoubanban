package com.littlekitty.xiaokele.scene

import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.external.LocationSnapshot

/**
 * Public-edition scene context.
 *
 * It carries only what the companion can actually observe: the current time,
 * the user's current foreground location snapshot (if any), the pet state and
 * the last interaction time. Shift phase, fixed place and meal windows were
 * removed with the shift feature.
 */
data class SceneContextSnapshot(
    val nowEpochMillis: Long,
    val locationSnapshot: LocationSnapshot?,
    val petState: PetStateEntity?,
    val lastInteractionAtEpochMillis: Long,
)
