package com.lanqi.weiassist.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** A calm blue: used where the system offers no wallpaper colours (before Android 12). */
private val Light = lightColorScheme(
    primary = Color(0xFF3D5BA9), onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2FF), onPrimaryContainer = Color(0xFF001848),
    secondary = Color(0xFF585E71), secondaryContainer = Color(0xFFDDE2F9), onSecondaryContainer = Color(0xFF151B2C),
    tertiary = Color(0xFF735572), tertiaryContainer = Color(0xFFFED7F9), onTertiaryContainer = Color(0xFF2A132C),
    background = Color(0xFFFAF8FF), surface = Color(0xFFFAF8FF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F3FA), surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF), surfaceContainerHighest = Color(0xFFE3E2E9),
    outlineVariant = Color(0xFFC5C6D0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB2C5FF), onPrimary = Color(0xFF002B74),
    primaryContainer = Color(0xFF23438F), onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFFC1C6DD), secondaryContainer = Color(0xFF414659), onSecondaryContainer = Color(0xFFDDE2F9),
    tertiary = Color(0xFFE1BBDD), tertiaryContainer = Color(0xFF5A3D5A), onTertiaryContainer = Color(0xFFFED7F9),
    background = Color(0xFF121318), surface = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1A1B21), surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F), surfaceContainerHighest = Color(0xFF34343A),
    outlineVariant = Color(0xFF44464F),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

/** The app's look: Material 3, wallpaper colours on Android 12+, light or dark as the system is. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}
