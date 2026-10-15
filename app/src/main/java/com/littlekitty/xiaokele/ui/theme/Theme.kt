package com.littlekitty.xiaokele.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF6D4C41),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF2B160B),
    secondary = Color(0xFF8D6E63),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF4DED1),
    onSecondaryContainer = Color(0xFF35211A),
    background = Color(0xFFFFF6F0),
    surface = Color(0xFFFFFBF8),
    surfaceVariant = Color(0xFFF4E6DE),
    onSurface = Color(0xFF221A16),
    onSurfaceVariant = Color(0xFF53433C),
    outline = Color(0xFF85736A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF1BC9D),
    onPrimary = Color(0xFF492A1A),
    primaryContainer = Color(0xFF62402E),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE7BDA8),
    onSecondary = Color(0xFF442A1F),
    secondaryContainer = Color(0xFF5D4034),
    onSecondaryContainer = Color(0xFFFFDCC2),
    background = Color(0xFF160F0C),
    surface = Color(0xFF1A130F),
    surfaceVariant = Color(0xFF52443D),
    onSurface = Color(0xFFEDE0DA),
    onSurfaceVariant = Color(0xFFD7C3B9),
    outline = Color(0xFFA08C82),
)

@Composable
fun XiaoKeLeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
