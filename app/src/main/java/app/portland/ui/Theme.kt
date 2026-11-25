package app.portland.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.portland.R

/** Neutral ink ladder: alpha black on white (alpha white on near-black in dark mode). */
@Immutable
data class Palette(
    val canvas: Color,
    val ink: Color,   // primary text, icons
    val ink2: Color,  // supporting text
    val ink3: Color,  // metadata, placeholders
    val line: Color,  // separators
    val fill: Color,  // image placeholders, quiet surfaces
    val shimmer: Color, // the light that sweeps over a loading placeholder
)

private val Light = Palette(
    canvas = Color.White, ink = Color.Black, ink2 = Color.Black.copy(0.64f),
    ink3 = Color.Black.copy(0.56f), line = Color.Black.copy(0.10f), fill = Color.Black.copy(0.05f),
    shimmer = Color.White.copy(0.7f),
)
private val Dark = Palette(
    canvas = Color(0xFF0A0A0A), ink = Color.White, ink2 = Color.White.copy(0.56f),
    ink3 = Color.White.copy(0.46f), line = Color.White.copy(0.12f), fill = Color.White.copy(0.08f),
    shimmer = Color.White.copy(0.07f),
)

private fun geist(weight: Int) =
    Font(R.font.geist, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Geist = FontFamily(geist(400), geist(500), geist(600))

/** The only three type sizes in the app. Vary weight and ink, never size. */
object Type {
    val display = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.7).sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp)
    val caption = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
}

val LocalPalette = staticCompositionLocalOf { Light }

@Composable
fun PortlandTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) Dark else Light
    val sheet = if (dark) Color(0xFF161616) else Color.White
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.ink, onPrimary = p.canvas, secondary = p.ink, onSecondary = p.canvas,
        background = p.canvas, onBackground = p.ink, surface = p.canvas, onSurface = p.ink,
        onSurfaceVariant = p.ink2, outline = p.line, outlineVariant = p.line,
        surfaceContainerLowest = sheet, surfaceContainerLow = sheet, surfaceContainer = sheet,
        surfaceContainerHigh = sheet, surfaceContainerHighest = sheet, surfaceTint = Color.Transparent,
    )
    // Every Material component maps onto the same three sizes.
    val type = Typography(
        displayLarge = Type.display, displayMedium = Type.display, displaySmall = Type.display,
        headlineLarge = Type.display, headlineMedium = Type.display, headlineSmall = Type.display,
        titleLarge = Type.body.copy(fontWeight = FontWeight.Medium), titleMedium = Type.body.copy(fontWeight = FontWeight.Medium),
        titleSmall = Type.body.copy(fontWeight = FontWeight.Medium),
        bodyLarge = Type.body, bodyMedium = Type.body, bodySmall = Type.caption,
        labelLarge = Type.body.copy(fontWeight = FontWeight.Medium), labelMedium = Type.caption, labelSmall = Type.caption,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
