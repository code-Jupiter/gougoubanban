package com.littlekitty.xiaokele.scene

/**
 * Turns the learned proactive-contact tendency into deterministic timing.
 * The neutral value (0.5) preserves product defaults; the two ends halve or
 * double the interval without letting model output bypass local scene gates.
 */
object ProactiveTendencyPolicy {
    const val DEFAULT_LEVEL = 0.5

    fun normalizedLevel(raw: String?): Double =
        raw?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: DEFAULT_LEVEL

    fun interactionGapMillis(baseMillis: Long, level: Double): Long =
        scaleInterval(baseMillis, level)

    fun cooldownMillis(baseMillis: Long, level: Double): Long =
        scaleInterval(baseMillis, level)

    private fun scaleInterval(baseMillis: Long, level: Double): Long {
        val normalized = level.coerceIn(0.0, 1.0)
        val factor = if (normalized <= DEFAULT_LEVEL) {
            2.0 - (2.0 * normalized)
        } else {
            1.5 - normalized
        }
        return (baseMillis * factor).toLong().coerceAtLeast(1L)
    }
}
