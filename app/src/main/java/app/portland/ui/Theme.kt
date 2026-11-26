package app.portland.ui

import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.portland.R
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.runtimeShaderEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedRectangularShape

/** Neutral ink ladder: alpha black on white (alpha white on pure black in dark mode), plus Apple-style fills and glass. */
@Immutable
data class Palette(
    val canvas: Color,
    val ink: Color,   // primary text, icons
    val ink2: Color,  // supporting text
    val ink3: Color,  // metadata, placeholders
    val line: Color,  // separators, glass fallback hairline
    val fill: Color,  // image placeholders, quiet surfaces (Apple tertiarySystemFill)
    val sheet: Color, // dialogs, menus, bottom sheets (Apple elevated background)
    val glass: Color, // the faint film on clear glass floating over content
    val glassFlat: Color, // glass sitting on the plain canvas (search field, cells, segmented track)
    val glassRegular: Color, // the film on regular (frosted) glass: panels that carry text
    val glassSolid: Color, // glass stand-in where backdrop effects are unavailable (Android 11)
    val thumb: Color,  // selected segment of a segmented control
    val danger: Color, // destructive actions (Apple systemRed)
    val dark: Boolean,
    val shimmer: Color, // the light that sweeps over a loading placeholder
    val scrim: Color, // dims content behind a panel, shelf or sheet: one strength everywhere
)

private val Light = Palette(
    canvas = Color.White, ink = Color.Black, ink2 = Color.Black.copy(0.64f),
    ink3 = Color.Black.copy(0.56f), line = Color.Black.copy(0.10f), fill = Color(0x1F767680),
    sheet = Color(0xFFF9F9F9), glass = Color.White.copy(0.14f), glassFlat = Color(0x1A767680),
    glassRegular = Color(0xFFF9F9F9).copy(0.62f),
    glassSolid = Color(0xF2F6F6F8), thumb = Color.White, danger = Color(0xFFFF3B30), dark = false,
    shimmer = Color.White.copy(0.7f), scrim = Color.Black.copy(0.18f),
)
private val Dark = Palette(
    canvas = Color.Black, ink = Color.White, ink2 = Color.White.copy(0.60f),
    ink3 = Color.White.copy(0.48f), line = Color.White.copy(0.14f), fill = Color(0x3D767680),
    sheet = Color(0xFF1C1C1E), glass = Color.Black.copy(0.10f), glassFlat = Color.White.copy(0.09f),
    glassRegular = Color(0xFF1C1C1E).copy(0.58f),
    glassSolid = Color(0xF21C1C1E), thumb = Color.White.copy(0.20f), danger = Color(0xFFFF453A), dark = true,
    shimmer = Color.White.copy(0.07f), scrim = Color.Black.copy(0.40f),
)

private fun inter(weight: Int, opsz: Float) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opsz)),
)

/** Inter's text cut (optical size 14): open spacing that stays legible at UI sizes. */
val InterText = FontFamily(inter(400, 14f), inter(500, 14f), inter(600, 14f), inter(700, 14f))

/** Inter's display cut (optical size 32): tighter, finer letterforms for large titles. */
val InterDisplay = FontFamily(inter(600, 32f), inter(700, 32f))

/**
 * Apple's type roles, set in Inter (SF Pro may only ship on Apple platforms). Inter has a taller x-height than
 * SF, so the body sizes sit 1sp under Apple's (16 for 17) to read at the same size. Tracking follows Inter's own
 * dynamic-metrics formula (−0.0223 + 0.185·e^(−0.1745·size) em): tight at titles, slightly open at labels.
 * Pick a role, not a size; vary weight and ink within a role.
 */
object Type {
    /** Screen titles that scroll away: "Portland", a board's name. */
    val largeTitle = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-0.022).em)
    /** Section and sheet titles. */
    val title = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.018).em)
    /** Emphasised body: dialog titles, the viewer's date, "3 selected". */
    val headline = TextStyle(fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.011).em)
    /** Running text, fields, rows. */
    val body = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.011).em)
    /** Secondary rows: board names under covers, menu items. */
    val subhead = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.009).em)
    /** Metadata, counts, captions, explanations. */
    val footnote = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.003).em)
    /** One word under an icon, badges. */
    val label = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.005.em)
}

/** Tabular figures, so times and counts don't jitter as their digits change. */
fun TextStyle.digits(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * Continuous-curvature ("squircle") corners, as on iOS. Use these instead of RoundedCornerShape.
 * They are [RoundedRectangularShape]s, which the glass lens needs. Nest them concentrically: a shape inset by
 * `p` inside one of radius `r` takes radius `r − p` (a 16dp-padded row in a 34dp sheet is [tile]-ish, 18dp).
 */
object Corners {
    val pill: RoundedRectangularShape = Capsule()
    val bar: RoundedRectangularShape = RoundedRectangle(5.dp)     // skeleton text bars
    val frame: RoundedRectangularShape = RoundedRectangle(7.dp)   // the viewer's filmstrip frames
    val small: RoundedRectangularShape = RoundedRectangle(12.dp)  // thumbnails, inputs, small badges
    val tile: RoundedRectangularShape = RoundedRectangle(16.dp)   // icon tiles and rows inside panels
    val menu: RoundedRectangularShape = RoundedRectangle(16.dp)   // dropdown menus
    val card: RoundedRectangularShape = RoundedRectangle(18.dp)   // pins and board covers in grids
    val media: RoundedRectangularShape = RoundedRectangle(26.dp)  // the viewer's media
    val sheet: RoundedRectangularShape = RoundedRectangle(30.dp)  // dialogs and sheets
    val sheetRadius = 30.dp // for Material sheets, which take a plain RoundedCornerShape
}

/**
 * Icon sizes, after SF Symbols' scales: pick one by where the icon sits, never a one-off. Phosphor's glyphs fill
 * about 80% of their box. Keep the touch target at least 48dp around any of them.
 */
object IconSize {
    /** A glyph inside a line of footnote text: the play mark on a time pill. */
    val inline = 10.dp
    /** Inside fields and small circles: the search field, a close button on a sheet, a selection check. */
    val small = 16.dp
    /** The default: glass buttons, toolbars, menu and list rows. */
    val medium = 20.dp
    /** Primary or standalone: the add button, tiles. */
    val large = 24.dp
}

/**
 * Apple's springs. SwiftUI's `.smooth`, `.snappy` and `.bouncy` are 0.5s springs with 0, 0.15 and 0.3 bounce;
 * in Compose that is stiffness (2π / 0.5)² ≈ 158 and dampingRatio = 1 − bounce.
 * Use these for every animation; avoid tween() except for pure opacity fades.
 */
object Motion {
    fun <T> smooth(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 158f)
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 158f)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.7f, stiffness = 158f)
    /** Touch feedback: quick in, a small settle on release. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 700f)
    /** Dialogs rising from the centre: Material 3 Expressive's default spatial spring, a quick settle. */
    fun <T> dialog(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    /** Glass growing out of the control that opened it (a button into a panel): overshoots a touch, the "jelly". */
    fun <T> morphOpen(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.72f, stiffness = 260f)
    /** The same glass folding back into its control: no overshoot. */
    fun <T> morphClose(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 380f)
    /** Anything leaving: sheets, bars, marks. Quick and critically damped, so nothing bounces on its way out. */
    fun <T> dismiss(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 420f)
}

/**
 * Clear Liquid Glass, after Apple's "clear" variant: the [backdrop] behind this element shows through almost
 * untouched (saturated, a hair of blur), bent by a deep lens at the rim, under a faint film ([tint], default
 * [Palette.glass]) and a specular edge highlight.
 *
 * [backdrop] must record content drawn *behind* this element (a sibling with Modifier.layerBackdrop),
 * never content that contains it. Pass null where only the plain canvas sits behind (e.g. inside a scrolling
 * list): you get the same rim and film over [Palette.glassFlat], without refraction. [scale] is read at draw
 * time; drive press feedback through it instead of Modifier.graphicsLayer, which would misplace the
 * refraction. Apply before size/clickable.
 *
 * [frost] moves it toward Apple's "regular" variant (0 clear .. 1 regular): the backdrop is frosted (blurred)
 * and the film thickens to a translucent sheet, so text on the glass reads over any content. Use regular for
 * panels that carry words (menus, the add panel, the send dock), clear for controls over media. A morph from a
 * button into a panel animates it from 0 to 1.
 */
@Composable
fun Modifier.glass(
    backdrop: Backdrop?,
    shape: RoundedRectangularShape = Corners.pill,
    tint: Color = Color.Unspecified,
    scale: () -> Float = { 1f },
    frost: Float = 0f,
): Modifier {
    val p = LocalPalette.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        val solid = if (tint == Color.Unspecified) p.glassSolid else tint.copy(alpha = 1f)
        return this
            .graphicsLayer { val s = scale(); scaleX = s; scaleY = s }
            .clip(shape)
            .background(solid)
            .border(0.5.dp, p.line, shape)
    }
    val f = frost.coerceIn(0f, 1f)
    val clear = when {
        tint != Color.Unspecified -> tint
        backdrop == null -> p.glassFlat
        else -> p.glass
    }
    val surface = if (f > 0f) androidx.compose.ui.graphics.lerp(clear, p.glassRegular, f) else clear
    val dark = p.dark
    return this.drawBackdrop(
        backdrop = backdrop ?: emptyBackdrop(),
        shape = { shape },
        effects = {
            if (backdrop != null) {
                colorControls(brightness = if (dark) 0f else 0.06f, saturation = 1.6f)
                blur((1.5f + (RegularBlur - 1.5f) * f).dp.toPx())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val radius = size.minDimension / 2f
                    lens(
                        refractionHeight = minOf(radius * 0.9f, 20.dp.toPx()),
                        refractionAmount = minOf(size.minDimension * 0.8f, 40.dp.toPx()),
                        depthEffect = true,
                    )
                }
            }
        },
        highlight = { Highlight.Default },
        shadow = { if (backdrop != null) Shadow.Default else null },
        layerBlock = { val s = scale(); scaleX = s; scaleY = s },
        onDrawSurface = { drawRect(surface) },
    )
}

// Frost radius of regular glass: enough to turn busy content behind it into colour, not detail.
private const val RegularBlur = 14f

/** Container colour for a dialog or sheet: see-through when [FrostBehind] can blur the app behind it. */
@Composable
fun frostedSheet(): Color {
    val p = LocalPalette.current
    val context = LocalContext.current
    val blur = remember(context) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
    }
    return if (blur) p.sheet.copy(alpha = if (p.dark) 0.68f else 0.74f) else p.sheet
}

/**
 * Frosts the app behind the current dialog or bottom-sheet window, so a [frostedSheet] container reads as glass.
 * Call it from inside the dialog's content. No-op before Android 12 or where the device disables window blur.
 */
@Composable
fun FrostBehind(radius: Dp = 28.dp) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    val px = with(LocalDensity.current) { radius.roundToPx() }
    DisposableEffect(view, px) {
        val window = ((view.parent as? DialogWindowProvider) ?: (view as? DialogWindowProvider))?.window
        if (window != null && window.windowManager.isCrossWindowBlurEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = px }
            window.setDimAmount(0.18f)
        }
        onDispose {}
    }
}

private const val ScrollEdgeShader = """
uniform shader content;
uniform float2 size;
layout(color) uniform half4 tint;
uniform float tintIntensity;

half4 main(float2 coord) {
    float mask = smoothstep(size.y, size.y * 0.35, coord.y);
    return mix(content.eval(coord) * mask, tint * mask, tintIntensity);
}"""

/**
 * iOS 26 scroll-edge effect for a pinned header: content scrolling under it is blurred and washed with the
 * canvas colour, strongest at the top and dissolving toward the bottom edge. [visible] (0..1, read at draw time)
 * fades it in as content arrives underneath. Put it on a Box sized like the header, drawn above the
 * [backdrop]'s content and below the header itself.
 */
@Composable
fun Modifier.scrollEdge(backdrop: Backdrop, visible: () -> Float): Modifier {
    val p = LocalPalette.current
    val canvas = p.canvas
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return this
            .graphicsLayer { alpha = visible() }
            .background(Brush.verticalGradient(0f to canvas.copy(alpha = 0.96f), 0.65f to canvas.copy(alpha = 0.8f), 1f to canvas.copy(alpha = 0f)))
    }
    val intensity = if (p.dark) 0.72f else 0.78f
    return this.drawPlainBackdrop(
        backdrop = backdrop,
        shape = { RectangleShape },
        effects = {
            blur(10.dp.toPx())
            runtimeShaderEffect("PortlandScrollEdge", ScrollEdgeShader, "content") {
                setFloatUniform("size", size.width, size.height)
                setColorUniform("tint", canvas)
                setFloatUniform("tintIntensity", intensity)
            }
        },
        layerBlock = { alpha = visible() },
    )
}

val LocalPalette = staticCompositionLocalOf { Light }

@Composable
fun PortlandTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) Dark else Light
    val sheet = p.sheet
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.ink, onPrimary = p.canvas, secondary = p.ink, onSecondary = p.canvas,
        background = p.canvas, onBackground = p.ink, surface = p.canvas, onSurface = p.ink,
        onSurfaceVariant = p.ink2, outline = p.line, outlineVariant = p.line,
        surfaceContainerLowest = sheet, surfaceContainerLow = sheet, surfaceContainer = sheet,
        surfaceContainerHigh = sheet, surfaceContainerHighest = sheet, surfaceTint = Color.Transparent,
        scrim = p.scrim,
    )
    // Every Material component maps onto the same roles.
    val type = Typography(
        displayLarge = Type.largeTitle, displayMedium = Type.largeTitle, displaySmall = Type.largeTitle,
        headlineLarge = Type.largeTitle, headlineMedium = Type.title, headlineSmall = Type.title,
        titleLarge = Type.title, titleMedium = Type.headline, titleSmall = Type.headline,
        bodyLarge = Type.body, bodyMedium = Type.body, bodySmall = Type.footnote,
        labelLarge = Type.body.copy(fontWeight = FontWeight.Medium), labelMedium = Type.footnote, labelSmall = Type.label,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
