package com.littlekitty.xiaokele.animation

/**
 * App-side Animation Adapter contract (architecture section 25, FR-PET-05/06).
 * App chat and home-screen Widget share this semantic directory. Unknown/null
 * animation ids are normalized to [AnimationCatalog.ID_DEFAULT_IDLE]; text and
 * widget rendering never fail because of a missing animation asset.
 */
interface AnimationAdapter {
    /**
     * Returns the playable semantic animation id. Unknown ids are normalized to
     * default_idle instead of failing the message or widget (FR-PET-06).
     */
    fun normalize(animationId: String?): String
}

class CatalogAnimationAdapter : AnimationAdapter {
    override fun normalize(animationId: String?): String =
        AnimationCatalog.resolveOrNull(animationId) ?: AnimationCatalog.ID_DEFAULT_IDLE
}
