package com.littlekitty.xiaokele.animation

import android.graphics.drawable.Animatable
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Plays one of the transparent Animated WebP resources on Android's native
 * ImageView. Action clips return to the idle clip after their source duration.
 */
@Composable
fun AnimatedPetImage(
    animationId: String?,
    animationKey: String? = null,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val normalizedId = AnimationCatalog.resolveOrNull(animationId)
        ?: AnimationCatalog.ID_DEFAULT_IDLE
    val replayKey = animationKey ?: normalizedId
    var displayedId by remember { mutableStateOf(normalizedId) }

    LaunchedEffect(normalizedId, replayKey) {
        displayedId = normalizedId
        if (normalizedId != AnimationCatalog.ID_DEFAULT_IDLE) {
            delay(AnimationCatalog.durationMillisFor(normalizedId))
            if (displayedId == normalizedId) {
                displayedId = AnimationCatalog.ID_DEFAULT_IDLE
            }
        }
    }

    val resourceId = AnimationCatalog.resourceFor(displayedId)
    val renderTag = "$replayKey:$resourceId"
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isFocusable = false
            }
        },
        update = { imageView ->
            imageView.contentDescription = contentDescription
            if (imageView.tag != renderTag) {
                (imageView.drawable as? Animatable)?.stop()
                imageView.setImageResource(resourceId)
                imageView.tag = renderTag
            }
            (imageView.drawable as? Animatable)?.let { drawable ->
                if (!drawable.isRunning) drawable.start()
            }
        },
    )
}
