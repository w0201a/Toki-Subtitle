package io.github.meiyongai.toki.subtitle.ui

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Stable Toki palette; wallpaper colors cannot split the manager and injected dialogs. */
@Suppress("UNUSED_PARAMETER")
internal fun tokiColorScheme(context: Context, dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(TokiPalette.ACCENT_DARK), onPrimary = Color(0xFF390512),
    primaryContainer = Color(0xFF39232C), onPrimaryContainer = Color(0xFFFFB1C0),
    secondary = Color(0xFF69CABE), secondaryContainer = Color(0xFF203834),
    background = Color(TokiPalette.CANVAS_DARK), onBackground = Color(TokiPalette.INK_DARK),
    surface = Color(TokiPalette.SURFACE_DARK), onSurface = Color(TokiPalette.INK_DARK),
    surfaceVariant = Color(0xFF303037), onSurfaceVariant = Color(TokiPalette.SECONDARY_DARK),
    surfaceContainer = Color(0xFF232328), surfaceContainerLow = Color(TokiPalette.SURFACE_DARK),
    surfaceContainerHigh = Color(0xFF2B2B31), surfaceContainerHighest = Color(0xFF303037),
    surfaceContainerLowest = Color(TokiPalette.CANVAS_DARK),
    outline = Color(0xFF74747F), outlineVariant = Color(0xFF393940), surfaceTint = Color.Transparent
) else lightColorScheme(
    primary = Color(TokiPalette.ACCENT), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF0F3), onPrimaryContainer = Color(0xFFAB2140),
    secondary = Color(0xFF238B80), secondaryContainer = Color(0xFFE9F7F4),
    background = Color(TokiPalette.CANVAS), onBackground = Color(TokiPalette.INK),
    surface = Color(TokiPalette.SURFACE), onSurface = Color(TokiPalette.INK),
    surfaceVariant = Color(0xFFF0F0F3), onSurfaceVariant = Color(TokiPalette.SECONDARY),
    surfaceContainer = Color.White, surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFF0F0F3), surfaceContainerHighest = Color(0xFFE9E9EF),
    surfaceContainerLowest = Color.White, outline = Color(0xFF96969F),
    outlineVariant = Color(0xFFE9E9EF), surfaceTint = Color.Transparent
)
