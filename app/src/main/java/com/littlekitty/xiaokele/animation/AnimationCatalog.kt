package com.littlekitty.xiaokele.animation

import androidx.annotation.DrawableRes
import com.littlekitty.xiaokele.R

/** Shared semantic directory for the transparent Animated WebP pet resources. */
object AnimationCatalog {

    const val ID_DEFAULT_IDLE = "default_idle"
    const val ID_WAITING = "dog_waiting"
    const val ID_SLEEPING = "dog_sleeping"
    const val ID_CURLED_UP = "dog_curled_up"
    const val ID_EXPECTING = "dog_expecting"
    const val ID_CARING = "dog_caring"
    const val ID_WORK_GUARD = "dog_work_guard"
    const val ID_WELCOME_BACK = "dog_welcome_back"

    val knownIds: Set<String> = setOf(
        ID_DEFAULT_IDLE,
        ID_WAITING,
        ID_SLEEPING,
        ID_CURLED_UP,
        ID_EXPECTING,
        ID_CARING,
        ID_WORK_GUARD,
        ID_WELCOME_BACK,
    )

    fun resolveOrNull(animationId: String?): String? =
        animationId?.takeIf { it in knownIds }

    @DrawableRes
    fun resourceFor(animationId: String?): Int = when (animationId) {
        ID_WAITING -> R.drawable.dog_waiting
        ID_SLEEPING -> R.drawable.dog_sleeping
        ID_CURLED_UP -> R.drawable.dog_curled_up
        ID_EXPECTING -> R.drawable.dog_expecting
        ID_CARING -> R.drawable.dog_caring
        ID_WORK_GUARD -> R.drawable.dog_work_guard
        ID_WELCOME_BACK -> R.drawable.dog_welcome_back
        null, ID_DEFAULT_IDLE -> R.drawable.dog_default_idle
        else -> R.drawable.dog_default_idle
    }

    fun durationMillisFor(animationId: String?): Long = when (animationId) {
        ID_EXPECTING, ID_WELCOME_BACK -> 6_000L
        ID_DEFAULT_IDLE, null -> 5_917L
        else -> 5_917L
    }
}
