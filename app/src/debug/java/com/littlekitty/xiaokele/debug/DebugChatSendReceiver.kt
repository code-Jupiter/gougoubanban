package com.littlekitty.xiaokele.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.littlekitty.xiaokele.chat.ChatSendOutcome
import com.littlekitty.xiaokele.chat.FrontChatPipeline
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogCategories
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.provider.MiniMaxConnectionTester
import com.littlekitty.xiaokele.provider.OfficialModelTestResult
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import java.security.MessageDigest
import java.util.UUID

/**
 * Debug-only acceptance entry: `adb shell am broadcast -a
 * com.littlekitty.xiaokele.debug.SEND_CHAT -e text "..."` sends the text as a
 * real user chat turn through the production FrontChatPipeline (context ->
 * MiniMax -> tools -> validation -> persistence -> animation). The chat UI
 * shows the persisted result like any other turn. Not compiled into release.
 */
class DebugChatSendReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TEST_OFFICIAL_PROFILE) {
            testOfficialProfile()
            return
        }
        if (intent.action != ACTION) return
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
        val pending = goAsync()
        if (text.isEmpty()) {
            pending.finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val correlationId = "debug_send_${UUID.randomUUID()}"
            try {
                val koin = GlobalContext.get()
                val pipeline = koin.get<FrontChatPipeline>()
                val diagnostics = runCatching { koin.get<DiagnosticsLogger>() }.getOrNull()
                val outcome = pipeline.send(text)
                val detail = when (outcome) {
                    is ChatSendOutcome.Success ->
                        "sent=true assistant=${outcome.assistantMessageId} animation=${outcome.animationId}"
                    is ChatSendOutcome.Failure ->
                        "sent=false error=${outcome.error.code.name} detail=${outcome.error.technicalDetail.orEmpty()}"
                    else -> "sent=unknown"
                }
                diagnostics?.record(
                    category = DiagnosticLogCategories.SYSTEM,
                    name = "debug_chat_send",
                    status = if (outcome is ChatSendOutcome.Success) {
                        DiagnosticLogStatuses.SUCCESS
                    } else {
                        DiagnosticLogStatuses.FAILED
                    },
                    subjectId = correlationId,
                    summary = "Debug 广播已通过真实前台链路发送聊天消息",
                    payloadJson = kotlinx.serialization.json.buildJsonObject {
                        put("correlation_id", correlationId)
                        put("text_length", text.length)
                        put("text_sha256", sha256(text))
                        put("outcome", if (outcome is ChatSendOutcome.Success) "success" else "failure")
                        put("animation_id", (outcome as? ChatSendOutcome.Success)?.animationId)
                        put("error_code", (outcome as? ChatSendOutcome.Failure)?.error?.code?.name)
                    }.toString(),
                )
                android.util.Log.i("DebugChatSend", detail)
            } catch (t: Throwable) {
                android.util.Log.e("DebugChatSend", "send failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /**
     * Debug-only counterpart of the settings-screen test button.  It exists
     * so acceptance automation can verify both MiniMax contracts without
     * requiring a synthetic tap inside the embedded emulator window.
     */
    private fun testOfficialProfile() {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val correlationId = "debug_official_profile_${UUID.randomUUID()}"
            try {
                val koin = GlobalContext.get()
                val store = koin.get<ByokSettingsStore>()
                val tester = koin.get<MiniMaxConnectionTester>()
                val diagnostics = runCatching { koin.get<DiagnosticsLogger>() }.getOrNull()
                val key = store.loadKeyOrNull().orEmpty()
                val outcome = tester.testOfficialProfile(key, store.region(), store.endpoint())
                val bound = (outcome as? OfficialModelTestResult.Success)?.let { result ->
                    store.markOfficialValidation(
                        keyFingerprint = result.keyFingerprint,
                        region = store.region(),
                        endpoint = store.endpoint(),
                        m2Model = result.m2Model,
                        herModel = result.herModel,
                    )
                } ?: false
                val success = outcome is OfficialModelTestResult.Success && bound
                diagnostics?.record(
                    category = DiagnosticLogCategories.SYSTEM,
                    name = "debug_official_profile_test",
                    status = if (success) DiagnosticLogStatuses.SUCCESS else DiagnosticLogStatuses.FAILED,
                    subjectId = correlationId,
                    summary = "Debug 广播已完成 MiniMax M2/M2-her 双模型能力测试",
                    payloadJson = kotlinx.serialization.json.buildJsonObject {
                        put("correlation_id", correlationId)
                        put("outcome", if (success) "success" else "failure")
                        put("configuration_bound", bound)
                    }.toString(),
                )
                android.util.Log.i(
                    "DebugChatSend",
                    if (success) "official_profile=success" else "official_profile=failure",
                )
            } catch (t: Throwable) {
                android.util.Log.e("DebugChatSend", "official profile test failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.littlekitty.xiaokele.debug.SEND_CHAT"
        const val ACTION_TEST_OFFICIAL_PROFILE = "com.littlekitty.xiaokele.debug.TEST_OFFICIAL_PROFILE"
        const val EXTRA_TEXT = "text"
    }
}
