package dev.motonav.simulator.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic tokens; components never use raw hex values. */
@Immutable
data class MotoColors(
    val background: Color = Color(0xFF0B0D10),
    val surface: Color = Color(0xFF14171C),
    val surfaceRaised: Color = Color(0xFF1B1F26),
    val surfaceSunken: Color = Color(0xFF07080A),
    val outline: Color = Color(0xFF262B33),
    val outlineStrong: Color = Color(0xFF3A414C),
    val text: Color = Color(0xFFF3F5F8),
    val textMuted: Color = Color(0xFFA0A8B4),
    val textFaint: Color = Color(0xFF6B7380),
    val accent: Color = Color(0xFFFF7A1A),
    val onAccent: Color = Color(0xFF1C0D00),
    val accentSoft: Color = Color(0x26FF7A1A),
    val success: Color = Color(0xFF34D399),
    val successSoft: Color = Color(0x2234D399),
    val warning: Color = Color(0xFFFBBF24),
    val warningSoft: Color = Color(0x22FBBF24),
    val danger: Color = Color(0xFFF87171),
    val dangerSoft: Color = Color(0x22F87171),
    val info: Color = Color(0xFF60A5FA),
    val infoSoft: Color = Color(0x2260A5FA),
)

val LocalMotoColors = staticCompositionLocalOf { MotoColors() }

object Moto {
    val colors: MotoColors @Composable get() = LocalMotoColors.current
}

private val Sans = FontFamily.SansSerif
private val Mono = FontFamily.Monospace

private val MotoTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.1.sp),
)

val NumericStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun MotoTheme(content: @Composable () -> Unit) {
    val colors = MotoColors()
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentSoft,
        onPrimaryContainer = colors.accent,
        secondary = colors.info,
        background = colors.background,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.surfaceRaised,
        onSurfaceVariant = colors.textMuted,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surfaceRaised,
        surfaceContainerHighest = colors.surfaceRaised,
        surfaceContainerLow = colors.surface,
        outline = colors.outlineStrong,
        outlineVariant = colors.outline,
        error = colors.danger,
        onError = Color.Black,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalMotoColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MotoTypography,
            shapes = Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(22.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
