package com.littlekitty.xiaokele.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ChatUiSemanticsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun placeholderAddressesUserAndCompanion() {
        val placeholder = context.getString(R.string.chat_placeholder, "可乐")
        assertEquals("你想对可乐说什么…", placeholder)
        assertFalse(placeholder.contains(context.getString(R.string.app_name)))
    }

    @Test
    fun persistedRoleStringsMapToVisibleAuthors() {
        assertEquals(ChatMessageAuthor.USER, ChatUiSemantics.authorForRole("USER"))
        assertEquals(ChatMessageAuthor.COMPANION, ChatUiSemantics.authorForRole("ASSISTANT"))
        assertEquals(ChatMessageAuthor.UNKNOWN, ChatUiSemantics.authorForRole("SYSTEM"))
        assertTrue(ChatUiSemantics.isUserMessage("USER"))
        assertFalse(ChatUiSemantics.isUserMessage("ASSISTANT"))
    }

    @Test
    fun visibleSenderLabelsMatchPublicRoleSemantics() {
        assertEquals("你", context.getString(R.string.chat_sender_user))
        assertEquals("可乐", context.getString(R.string.chat_sender_companion, "可乐"))
        assertEquals("你发送的消息", context.getString(R.string.chat_user_bubble_description))
        assertEquals("可乐回复的消息", context.getString(R.string.chat_assistant_bubble_description, "可乐"))
        assertEquals("你给可乐发消息的输入框", context.getString(R.string.chat_input_content_description, "可乐"))
        assertEquals("可乐的陪伴舞台", context.getString(R.string.role_stage_content_description, "可乐"))
        assertEquals("还没有消息，你先和可乐打个招呼吧", context.getString(R.string.chat_empty_state, "可乐"))
    }

    @Test
    fun sendButtonEnablementIsDeterministicAndNeverSendsBlankText() {
        assertTrue(ChatUiSemantics.canSend("你好", isSending = false))
        assertTrue(ChatUiSemantics.canSend(" 你好 ", isSending = false))
        assertFalse(ChatUiSemantics.canSend("", isSending = false))
        assertFalse(ChatUiSemantics.canSend("   ", isSending = false))
        assertTrue(ChatUiSemantics.canSend("你好", isSending = true))
    }

    @Test
    fun pendingReplyIsPresentedAsCompanionActivity() {
        assertTrue(ChatUiSemantics.shouldShowCompanionReplyingIndicator(isSending = true))
        assertFalse(ChatUiSemantics.shouldShowCompanionReplyingIndicator(isSending = false))
        assertEquals("可乐正在思考…", context.getString(R.string.chat_message_sending, "可乐"))
    }

    @Test
    fun timestampsAreGroupedAndUseReadableChineseDateFormats() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-08-30T04:34:00Z").toEpochMilli()
        assertTrue(ChatUiSemantics.shouldShowTimestamp(null, now))
        assertFalse(
            ChatUiSemantics.shouldShowTimestamp(
                previousCreatedAt = now,
                currentCreatedAt = now + 4 * 60 * 1000L + 59_999L,
            ),
        )
        assertTrue(
            ChatUiSemantics.shouldShowTimestamp(
                previousCreatedAt = now,
                currentCreatedAt = now + ChatUiSemantics.TIMESTAMP_GAP_MILLIS,
            ),
        )
        assertEquals("12:34", ChatUiSemantics.formatTimestamp(now, now, zone))
        assertEquals(
            "昨天 12:34",
            ChatUiSemantics.formatTimestamp(now - 24 * 60 * 60 * 1000L, now, zone),
        )
        assertEquals(
            "8月28日 12:34",
            ChatUiSemantics.formatTimestamp(now - 2 * 24 * 60 * 60 * 1000L, now, zone),
        )
        assertEquals(
            "2025年8月30日 12:34",
            ChatUiSemantics.formatTimestamp(now - 365 * 24 * 60 * 60 * 1000L, now, zone),
        )
    }

    @Test
    fun mainActivitySourceContractUsesAdjustResizeForIme() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").exists() }
        val manifest = java.io.File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".MainActivity\""))
        assertTrue(manifest.contains("android:windowSoftInputMode=\"adjustResize\""))
    }
}
