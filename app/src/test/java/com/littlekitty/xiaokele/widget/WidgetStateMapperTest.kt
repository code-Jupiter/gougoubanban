package com.littlekitty.xiaokele.widget

import android.app.Application
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.state.PetStateIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WidgetStateMapperTest {

    @Test
    fun emptyShortTextHidesBubbleAndNonEmptyShortTextIsKept() {
        val adapter = CatalogAnimationAdapter()
        val empty = WidgetStateMapper.fromPetState(
            PetStateEntity(
                stateId = PetStateIds.IDLE,
                animationId = null,
                shortText = "   ",
                startsAt = 1L,
                updatedAt = 1L,
            ),
            adapter,
        )
        assertFalse(empty.showShortTextBubble)
        assertNull(empty.shortText)

        val visible = WidgetStateMapper.fromPetState(
            PetStateEntity(
                stateId = PetStateIds.WAITING,
                animationId = null,
                shortText = "测试用户，我在",
                startsAt = 1L,
                updatedAt = 1L,
            ),
            adapter,
        )
        assertTrue(visible.showShortTextBubble)
        assertEquals("测试用户，我在", visible.shortText)
    }

    @Test
    fun unknownAndMissingAnimationBothNormalizeToDefaultIdle() {
        val adapter = CatalogAnimationAdapter()
        val unknown = WidgetStateMapper.fromPetState(
            PetStateEntity(
                stateId = PetStateIds.IDLE,
                animationId = "dog_not_imported",
                startsAt = 1L,
                updatedAt = 1L,
            ),
            adapter,
        )
        val missing = WidgetStateMapper.fromPetState(
            PetStateEntity(
                stateId = PetStateIds.IDLE,
                animationId = null,
                startsAt = 1L,
                updatedAt = 1L,
            ),
            adapter,
        )
        assertEquals("default_idle", unknown.animationId)
        assertEquals("default_idle", missing.animationId)
    }

    @Test
    fun widgetClickTargetExtractsOnlyValidEntityIds() {
        assertEquals(null, WidgetClickTarget.todoIdOrNull(null))
        assertEquals(null, WidgetClickTarget.todoIdOrNull("not-json"))
        assertEquals(
            "todo_00000000000000000000000000000001",
            WidgetClickTarget.todoIdOrNull("""{"todo_id":"todo_00000000000000000000000000000001"}"""),
        )
        assertEquals(null, WidgetClickTarget.todoIdOrNull("""{"todo_id":"bad"}"""))
    }
}
