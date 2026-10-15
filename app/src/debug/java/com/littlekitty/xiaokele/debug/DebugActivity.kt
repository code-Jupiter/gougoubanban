package com.littlekitty.xiaokele.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import com.littlekitty.xiaokele.MainActivity
import com.littlekitty.xiaokele.floating.FloatingPetService
import com.littlekitty.xiaokele.floating.FloatingWindowSettingsStore
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import com.littlekitty.xiaokele.ui.theme.XiaoKeLeTheme
import org.koin.android.ext.android.inject

/** Debug-only navigation shell; business operations live in screen/services. */
class DebugActivity : ComponentActivity() {

    private val dataMaintenance: DebugDataMaintenanceService by inject()
    private val taskInspector: BackgroundTaskInspector by inject()
    private val byokSettingsStore: ByokSettingsStore by inject()
    private val realAiAcceptance: RealAiAcceptanceService by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaoKeLeTheme {
                BackHandler(onBack = ::returnToChat)
                DebugCenterScreen(
                    dataMaintenance = dataMaintenance,
                    taskInspector = taskInspector,
                    byokSettingsStore = byokSettingsStore,
                    realAiAcceptance = realAiAcceptance,
                    onShowPreview = ::showPreviewBubble,
                    onClosePreview = ::closePreviewBubble,
                )
            }
        }
    }

    /**
     * The debug activity can be launched without a visible parent during
     * acceptance work. Always return to the chat shell instead of leaving the
     * user at the launcher in that case.
     */
    private fun returnToChat() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    private fun showPreviewBubble(text: String, animationId: String?): String {
        val context = applicationContext
        if (!android.provider.Settings.canDrawOverlays(context)) {
            return "浮窗预览失败：未授予“显示在其他应用上层”权限"
        }
        if (!FloatingWindowSettingsStore(context).isEnabled()) {
            return "浮窗预览失败：设置中的“悬浮窗陪伴”未开启"
        }
        if (text.isBlank()) {
            val validAnimation = animationId?.takeIf { com.littlekitty.xiaokele.animation.AnimationCatalog.resolveOrNull(it) != null }
                ?: com.littlekitty.xiaokele.animation.AnimationCatalog.ID_DEFAULT_IDLE
            FloatingPetService.playAnimation(context, validAnimation)
            return "动画预览请求已发送：$validAnimation。"
        }
        return if (FloatingPetService.showBubble(context, text, allowWhenForeground = true, forceBubble = true, animationId = animationId)) {
            "Debug 浮窗预览请求已发送；会立即显示，约 15 秒后结束。"
        } else {
            "浮窗预览失败：Android 未接受悬浮窗服务请求"
        }
    }

    private fun closePreviewBubble(): String {
        FloatingPetService.stop(applicationContext)
        return "Debug 浮窗预览已关闭。"
    }
}
