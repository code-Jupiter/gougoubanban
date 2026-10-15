package com.littlekitty.xiaokele.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationCommandCatalogTest {

    @Test
    fun chineseCommandResolvesToWaitingAnimation() {
        val result = AnimationCommandCatalog.parse("/发呆")

        assertTrue(result is AnimationCommandCatalog.ParseResult.Action)
        assertEquals(
            AnimationCatalog.ID_WAITING,
            (result as AnimationCommandCatalog.ParseResult.Action).entry.animationId,
        )
    }

    @Test
    fun englishAnimationIdAliasAlsoResolves() {
        val result = AnimationCommandCatalog.parse(" /dog_sleeping ")

        assertEquals(
            AnimationCatalog.ID_SLEEPING,
            (result as AnimationCommandCatalog.ParseResult.Action).entry.animationId,
        )
    }

    @Test
    fun helpAndUnknownCommandsAreLocalResults() {
        assertTrue(AnimationCommandCatalog.parse("/帮助") is AnimationCommandCatalog.ParseResult.Help)
        assertTrue(AnimationCommandCatalog.parse("/不存在") is AnimationCommandCatalog.ParseResult.Unknown)
        assertNull(AnimationCommandCatalog.parse("普通聊天"))
    }
}
