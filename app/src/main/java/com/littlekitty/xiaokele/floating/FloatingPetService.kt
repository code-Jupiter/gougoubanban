package com.littlekitty.xiaokele.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.littlekitty.xiaokele.R
import com.littlekitty.xiaokele.MainActivity
import com.littlekitty.xiaokele.animation.AnimationCatalog
import kotlin.math.roundToInt

class FloatingPetService : Service() {

    private var overlay: FloatingPetOverlay? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!Settings.canDrawOverlays(this) || !FloatingWindowSettingsStore(this).isEnabled()) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this) || !FloatingWindowSettingsStore(this).isEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_HIDE_OVERLAY -> hideOverlay()
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_PLAY_ANIMATION -> {
                showOverlay()
                intent.getStringExtra(EXTRA_ANIMATION_ID)?.let { overlay?.playAnimation(it) }
            }
            ACTION_SHOW_BUBBLE -> {
                // A bubble request can be the first command after the service
                // is created. Always create the overlay before addressing it;
                // otherwise `overlay?.showBubble(...)` silently drops the
                // message.
                showOverlay()
                    intent.getStringExtra(EXTRA_TEXT)?.let {
                        overlay?.showBubble(
                            it,
                            force = intent.getBooleanExtra(EXTRA_FORCE_BUBBLE, false),
                        )
                        intent.getStringExtra(EXTRA_ANIMATION_ID)?.let { animationId -> overlay?.playAnimation(animationId) }
                }
            }
            ACTION_SHOW_PENDING_BUBBLE -> {
                showOverlay()
                PendingFloatingBubbleStore(this).consumeIfValid()?.let { pending ->
                    overlay?.showBubble(
                        pending.text + unreadSuffix(intent.getIntExtra(EXTRA_UNREAD_COUNT, 0)),
                        force = intent.getBooleanExtra(EXTRA_FORCE_BUBBLE, false),
                    )
                }
            }
            null -> showOverlay()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlay?.detach()
        overlay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun unreadSuffix(unreadCount: Int): String =
        if (unreadCount > 1) "\n（还有 ${unreadCount - 1} 条未读）" else ""

    private fun showOverlay() {
        if (overlay != null) return
        overlay = FloatingPetOverlay(this, onTripleTap = ::disableFromTripleTap).also { it.attach() }
    }

    private fun hideOverlay() {
        overlay?.detach()
        overlay = null
    }

    private fun disableFromTripleTap() {
        FloatingWindowSettingsStore(this).setEnabled(false)
        hideOverlay()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_window_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.floating_window_notification_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_dog_placeholder)
        .setContentTitle(getString(R.string.floating_window_notification_title))
        .setContentText(getString(R.string.floating_window_notification_text))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setSilent(true)
        .build()

    private class FloatingPetOverlay(
        private val context: Context,
        private val onTripleTap: () -> Unit,
    ) {
        private val windowManager = context.getSystemService(WindowManager::class.java)
        private val settings = FloatingWindowSettingsStore(context)
        private val handler = Handler(Looper.getMainLooper())
        private val density = context.resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val root = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }
        private val petImage = ImageView(context).apply {
            contentDescription = context.getString(R.string.floating_window_pet_description)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.dog_default_idle)
        }
        private val bellImage = ImageView(context).apply {
            contentDescription = context.getString(R.string.floating_window_bell_description)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.ic_floating_bell)
            visibility = View.GONE
        }
        private val bubble = TextView(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.argb(35, 109, 76, 65))
            }
            setTextColor(Color.rgb(82, 58, 48))
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14), dp(9), dp(14), dp(9))
            visibility = View.GONE
        }
        private val collapseRunnable = Runnable { collapse() }
        private val hideBubbleRunnable = Runnable { hideBubble() }
        private val restoreIdleRunnable = Runnable { setAnimation(AnimationCatalog.ID_DEFAULT_IDLE) }
        private val singleTapRunnable = Runnable {
            tapCount = 0
            lastTapAt = 0L
            if (collapsed) expandFromBell() else openApp()
        }
        private val petLayoutParams = FrameLayout.LayoutParams(
            dp(184),
            dp(PET_HEIGHT_DP),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        )
        private val bubbleLayoutParams = FrameLayout.LayoutParams(
            dp(BUBBLE_WIDTH_DP),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        )
        private val layoutParams = WindowManager.LayoutParams().apply {
            width = dp(224)
            height = dp(218)
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.TOP or Gravity.END
            x = settings.xOffsetOrNull() ?: dp(10)
            y = settings.yOffsetOrNull() ?: dp(150)
            title = "陪伴悬浮窗"
        }
        private var attached = false
        private var collapsed = false
        private var lastBubbleShownAt = 0L
        private var dragging = false
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var tapCount = 0
        private var lastTapAt = 0L

        init {
            root.addView(petImage, petLayoutParams)
            root.addView(
                bellImage,
                FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER),
            )
            root.addView(bubble, bubbleLayoutParams)
            root.setOnTouchListener { _, event -> handleTouch(event) }
            bubble.setOnClickListener { openApp() }
        }

        fun attach() {
            if (attached) return
            clampPosition()
            windowManager.addView(root, layoutParams)
            attached = true
            startAnimation()
            keepAwake()
        }

        fun detach() {
            if (!attached) return
            handler.removeCallbacks(collapseRunnable)
            handler.removeCallbacks(hideBubbleRunnable)
            handler.removeCallbacks(restoreIdleRunnable)
            handler.removeCallbacks(singleTapRunnable)
            tapCount = 0
            lastTapAt = 0L
            (petImage.drawable as? Animatable)?.stop()
            runCatching { windowManager.removeView(root) }
            attached = false
        }

        fun showBubble(rawText: String, force: Boolean = false) {
            val text = rawText.trim().replace(Regex("\\s+"), " ")
            if (text.isBlank()) return
            val now = SystemClock.uptimeMillis()
            if (!force && now - lastBubbleShownAt < BUBBLE_COOLDOWN_MS) return
            lastBubbleShownAt = now
            if (collapsed) expand()
            val displayText = text.take(MAX_BUBBLE_CHARS).let { visibleText ->
                if (visibleText.length < text.length) "$visibleText…" else visibleText
            }
            bubble.text = displayText
            bubble.visibility = View.VISIBLE
            layoutBubbleAroundPet()
            handler.removeCallbacks(hideBubbleRunnable)
            if (force) {
                // Debug previews must remain observable long enough for a
                // human or a screenshot tool to verify the actual bubble.
                handler.removeCallbacks(collapseRunnable)
                handler.postDelayed(hideBubbleRunnable, DEBUG_BUBBLE_DURATION_MS)
                handler.postDelayed(collapseRunnable, DEBUG_COLLAPSE_AFTER_MS)
            } else {
                handler.postDelayed(hideBubbleRunnable, BUBBLE_DURATION_MS)
                keepAwake()
            }
        }

        fun playAnimation(animationId: String) {
            if (AnimationCatalog.resolveOrNull(animationId) == null) return
            if (collapsed) expand()
            setAnimation(animationId)
            handler.removeCallbacks(restoreIdleRunnable)
            handler.postDelayed(restoreIdleRunnable, AnimationCatalog.durationMillisFor(animationId))
            keepAwake()
        }

        private fun setAnimation(animationId: String) {
            petImage.setImageResource(AnimationCatalog.resourceFor(animationId))
            (petImage.drawable as? Animatable)?.start()
        }

        private fun keepAwake() {
            if (collapsed) return
            handler.removeCallbacks(collapseRunnable)
            handler.postDelayed(collapseRunnable, COLLAPSE_AFTER_MS)
        }

        private fun collapse() {
            if (!attached || collapsed) return
            val petTop = currentPetTopScreen()
            collapsed = true
            bubble.visibility = View.GONE
            petImage.visibility = View.GONE
            bellImage.visibility = View.VISIBLE
            layoutParams.width = dp(76)
            layoutParams.height = dp(76)
            layoutParams.y = petTop + (dp(PET_HEIGHT_DP) - dp(76)) / 2
            clampPosition()
            updateWindowLayout()
        }

        private fun expandFromBell() {
            if (!collapsed) {
                keepAwake()
                return
            }
            bellImage.animate()
                .rotationBy(16f)
                .setDuration(90L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    bellImage.rotation = 0f
                    expand()
                }
                .start()
        }

        private fun expand() {
            val petTop = if (collapsed) {
                layoutParams.y + (dp(76) - dp(PET_HEIGHT_DP)) / 2
            } else {
                currentPetTopScreen()
            }
            collapsed = false
            bellImage.visibility = View.GONE
            petImage.visibility = View.VISIBLE
            layoutPetOnly(petTop)
            keepAwake()
        }

        private fun hideBubble() {
            bubble.visibility = View.GONE
            if (!collapsed) layoutPetOnly(currentPetTopScreen())
        }

        private fun layoutPetOnly(petTopScreen: Int) {
            layoutParams.width = dp(OVERLAY_WIDTH_DP)
            layoutParams.height = dp(PET_HEIGHT_DP)
            petLayoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            petLayoutParams.topMargin = 0
            petLayoutParams.bottomMargin = 0
            bubbleLayoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            bubbleLayoutParams.topMargin = 0
            layoutParams.y = petTopScreen
            clampPosition()
            updateWindowLayout()
        }

        private fun layoutBubbleAroundPet() {
            bubble.measure(
                View.MeasureSpec.makeMeasureSpec(dp(BUBBLE_WIDTH_DP), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(BUBBLE_MAX_HEIGHT_DP), View.MeasureSpec.AT_MOST),
            )
            val bubbleHeight = bubble.measuredHeight.coerceAtLeast(dp(1))
            val petTopScreen = currentPetTopScreen()
            val gap = dp(BUBBLE_GAP_DP)
            val displayHeight = context.resources.displayMetrics.heightPixels
            val expandedHeight = dp(PET_HEIGHT_DP) + gap + bubbleHeight
            val fitsAbove = petTopScreen - gap - bubbleHeight >= 0
            val fitsBelow = petTopScreen + dp(PET_HEIGHT_DP) + gap + bubbleHeight <= displayHeight
            val bubbleAbove = fitsAbove || !fitsBelow
            val rootTop = if (bubbleAbove) {
                petTopScreen - gap - bubbleHeight
            } else {
                petTopScreen
            }

            layoutParams.width = dp(OVERLAY_WIDTH_DP)
            layoutParams.height = expandedHeight
            petLayoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            petLayoutParams.topMargin = if (bubbleAbove) bubbleHeight + gap else 0
            petLayoutParams.bottomMargin = 0
            bubbleLayoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            bubbleLayoutParams.topMargin = if (bubbleAbove) 0 else dp(PET_HEIGHT_DP) + gap
            layoutParams.y = rootTop
            clampPosition()
            updateWindowLayout()
        }

        private fun currentPetTopScreen(): Int {
            if (collapsed) return layoutParams.y + (dp(76) - dp(PET_HEIGHT_DP)) / 2
            return layoutParams.y + petLayoutParams.topMargin
        }

        private fun handleTouch(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    handler.removeCallbacks(singleTapRunnable)
                    if (!collapsed) handler.removeCallbacks(collapseRunnable)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.rawX - downRawX
                    val totalDy = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(totalDx) > touchSlop || kotlin.math.abs(totalDy) > touchSlop)) {
                        dragging = true
                        tapCount = 0
                        lastTapAt = 0L
                        handler.removeCallbacks(singleTapRunnable)
                    }
                    if (dragging) {
                        layoutParams.x -= (event.rawX - lastRawX).roundToInt()
                        layoutParams.y += (event.rawY - lastRawY).roundToInt()
                        clampPosition()
                        updateWindowLayout()
                    }
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        registerTap()
                    } else {
                        settings.setPosition(layoutParams.x, layoutParams.y)
                        keepAwake()
                    }
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    tapCount = 0
                    lastTapAt = 0L
                    handler.removeCallbacks(singleTapRunnable)
                    keepAwake()
                    return true
                }
            }
            return true
        }

        private fun registerTap() {
            val now = SystemClock.uptimeMillis()
            if (now - lastTapAt > TRIPLE_TAP_TIMEOUT_MS) {
                tapCount = 0
            }
            tapCount += 1
            lastTapAt = now
            if (tapCount >= TRIPLE_TAP_COUNT) {
                handler.removeCallbacks(singleTapRunnable)
                tapCount = 0
                lastTapAt = 0L
                onTripleTap()
                return
            }
            handler.removeCallbacks(singleTapRunnable)
            handler.postDelayed(singleTapRunnable, TRIPLE_TAP_TIMEOUT_MS)
        }

        private fun openApp() {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
            context.startActivity(intent)
        }

        private fun clampPosition() {
            val displayMetrics = context.resources.displayMetrics
            val maxX = (displayMetrics.widthPixels - layoutParams.width).coerceAtLeast(0)
            val maxY = (displayMetrics.heightPixels - layoutParams.height).coerceAtLeast(0)
            layoutParams.x = layoutParams.x.coerceIn(0, maxX)
            layoutParams.y = layoutParams.y.coerceIn(0, maxY)
        }

        private fun updateWindowLayout() {
            if (attached) runCatching { windowManager.updateViewLayout(root, layoutParams) }
        }

        private fun startAnimation() {
            (petImage.drawable as? Animatable)?.start()
        }

        private fun dp(value: Int): Int = (value * density).roundToInt()

        private companion object {
            const val OVERLAY_WIDTH_DP = 224
            const val PET_HEIGHT_DP = 218
            const val BUBBLE_WIDTH_DP = 208
            const val BUBBLE_MAX_HEIGHT_DP = 116
            const val BUBBLE_GAP_DP = 8
            const val COLLAPSE_AFTER_MS = 5_000L
            const val BUBBLE_DURATION_MS = 4_500L
            const val DEBUG_COLLAPSE_AFTER_MS = 30_000L
            const val DEBUG_BUBBLE_DURATION_MS = 30_000L
            const val BUBBLE_COOLDOWN_MS = 45_000L
            const val MAX_BUBBLE_CHARS = 96
            const val TRIPLE_TAP_COUNT = 3
            const val TRIPLE_TAP_TIMEOUT_MS = 420L
        }
    }

    companion object {
        private const val CHANNEL_ID = "little_kitty_floating_window"
        private const val NOTIFICATION_ID = 2101
        const val ACTION_SHOW_BUBBLE = "com.littlekitty.xiaokele.floating.SHOW_BUBBLE"
        const val ACTION_SHOW_PENDING_BUBBLE = "com.littlekitty.xiaokele.floating.SHOW_PENDING_BUBBLE"
        const val ACTION_PLAY_ANIMATION = "com.littlekitty.xiaokele.floating.PLAY_ANIMATION"
        const val ACTION_HIDE_OVERLAY = "com.littlekitty.xiaokele.floating.HIDE_OVERLAY"
        const val ACTION_SHOW_OVERLAY = "com.littlekitty.xiaokele.floating.SHOW_OVERLAY"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_ANIMATION_ID = "extra_animation_id"
        const val EXTRA_FORCE_BUBBLE = "extra_force_bubble"
        const val EXTRA_UNREAD_COUNT = "extra_unread_count"

        fun startHidden(context: Context) {
            startWithAction(context, ACTION_HIDE_OVERLAY)
        }

        fun show(context: Context) {
            startWithAction(context, ACTION_SHOW_OVERLAY)
        }

        private fun startWithAction(context: Context, action: String) {
            if (Settings.canDrawOverlays(context)) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FloatingPetService::class.java).setAction(action),
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPetService::class.java))
        }

        fun showBubble(
            context: Context,
            text: String,
            allowWhenForeground: Boolean = false,
            forceBubble: Boolean = false,
            animationId: String? = null,
        ): Boolean {
            if (text.isBlank() || !Settings.canDrawOverlays(context)) return false
            if (!FloatingWindowSettingsStore(context).isEnabled()) return false
            if (!allowWhenForeground && AppVisibilityState.isForeground) return false
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FloatingPetService::class.java)
                        .setAction(ACTION_SHOW_BUBBLE)
                        .putExtra(EXTRA_TEXT, text)
                        .putExtra(EXTRA_FORCE_BUBBLE, forceBubble)
                        .putExtra(EXTRA_ANIMATION_ID, animationId),
                )
            }.onFailure { return false }
            return true
        }

        fun playAnimation(context: Context, animationId: String) {
            if (AnimationCatalog.resolveOrNull(animationId) == null) return
            if (!Settings.canDrawOverlays(context)) return
            if (!FloatingWindowSettingsStore(context).isEnabled()) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingPetService::class.java)
                    .setAction(ACTION_PLAY_ANIMATION)
                    .putExtra(EXTRA_ANIMATION_ID, animationId),
            )
        }

        fun enqueuePendingBubble(
            context: Context,
            text: String,
            eventId: String?,
            priority: Int,
            expiresAt: Long,
        ) {
            if (text.isBlank()) return
            if (!FloatingWindowSettingsStore(context).isEnabled()) return
            PendingFloatingBubbleStore(context).enqueue(
                PendingFloatingBubble(
                    text = text,
                    eventId = eventId,
                    priority = priority,
                    expiresAt = expiresAt,
                ),
            )
        }

        /**
         * Debug-only preview handoff. The pending text is consumed here and
         * sent directly to the service, so a newly-created service cannot
         * consume an empty/late queue while the debug page reports success.
         */
        fun showPendingDebug(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            if (!FloatingWindowSettingsStore(context).isEnabled()) return false
            val pending = PendingFloatingBubbleStore(context).consumeIfValid() ?: return false
            val accepted = showBubble(
                context = context,
                text = pending.text,
                allowWhenForeground = true,
                forceBubble = true,
            )
            if (!accepted) {
                PendingFloatingBubbleStore(context).enqueue(pending)
            }
            return accepted
        }

        /**
         * Requests delivery of the durable pending bubble. Production callers
         * keep the foreground guard; debug preview explicitly opts in so a
         * test button can validate the real overlay path without requiring a
         * second manual Home press.
         */
        fun showPending(
            context: Context,
            allowWhenForeground: Boolean = false,
            forceBubble: Boolean = false,
            unreadCount: Int = 0,
        ): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            if (!FloatingWindowSettingsStore(context).isEnabled()) return false
            if (!allowWhenForeground && AppVisibilityState.isForeground) return false
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingPetService::class.java)
                    .setAction(ACTION_SHOW_PENDING_BUBBLE)
                    .putExtra(EXTRA_FORCE_BUBBLE, forceBubble)
                    .putExtra(EXTRA_UNREAD_COUNT, unreadCount),
            )
            return true
        }
    }
}
