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
    val glassSolid: Color, // glass stand-in where backdrop effects are unavailable (Android 11)
    val thumb: Color,  // selected segment of a segmented control
    val danger: Color, // destructive actions (Apple systemRed)
    val dark: Boolean,
    val shimmer: Color, // the light that sweeps over a loading placeholder
)

private val Light = Palette(
    canvas = Color.White, ink = Color.Black, ink2 = Color.Black.copy(0.64f),
    ink3 = Color.Black.copy(0.56f), line = Color.Black.copy(0.10f), fill = Color(0x1F767680),
    sheet = Color(0xFFF9F9F9), glass = Color.White.copy(0.14f), glassFlat = Color(0x1A767680),
    glassSolid = Color(0xF2F6F6F8), thumb = Color.White, danger = Color(0xFFFF3B30), dark = false,
    shimmer = Color.White.copy(0.7f),
)
private val Dark = Palette(
    canvas = Color.Black, ink = Color.White, ink2 = Color.White.copy(0.60f),
    ink3 = Color.White.copy(0.48f), line = Color.White.copy(0.14f), fill = Color(0x3D767680),
    sheet = Color(0xFF1C1C1E), glass = Color.Black.copy(0.10f), glassFlat = Color.White.copy(0.09f),
    glassSolid = Color(0xF21C1C1E), thumb = Color.White.copy(0.20f), danger = Color(0xFFFF453A), dark = true,
    shimmer = Color.White.copy(0.07f),
)

private fun geist(weight: Int) =
    Font(R.font.geist, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Geist = FontFamily(geist(400), geist(500), geist(600))

/** The only three type sizes in the app. Vary weight and ink, never size. */
object Type {
    val display = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp)
    val caption = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.05).sp)
}

/**
 * Continuous-curvature ("squircle") corners, as on iOS. Use these instead of RoundedCornerShape.
 * They are [RoundedRectangularShape]s, which the glass lens needs.
 */
object Corners {
    val pill: RoundedRectangularShape = Capsule()
    val small: RoundedRectangularShape = RoundedRectangle(12.dp)  // thumbnails, inputs, small badges
    val card: RoundedRectangularShape = RoundedRectangle(18.dp)   // pins and board covers in grids
    val media: RoundedRectangularShape = RoundedRectangle(26.dp)  // the viewer's media
    val sheet: RoundedRectangularShape = RoundedRectangle(30.dp)  // dialogs and sheets
    val menu: RoundedRectangularShape = RoundedRectangle(16.dp)   // dropdown menus
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
 */
@Composable
fun Modifier.glass(
    backdrop: Backdrop?,
    shape: RoundedRectangularShape = Corners.pill,
    tint: Color = Color.Unspecified,
    scale: () -> Float = { 1f },
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
    val surface = when {
        tint != Color.Unspecified -> tint
        backdrop == null -> p.glassFlat
        else -> p.glass
    }
    val dark = p.dark
    return this.drawBackdrop(
        backdrop = backdrop ?: emptyBackdrop(),
        shape = { shape },
        effects = {
            if (backdrop != null) {
                colorControls(brightness = if (dark) 0f else 0.06f, saturation = 1.6f)
                blur(1.5.dp.toPx())
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
        scrim = Color.Black.copy(alpha = 0.32f),
    )
    // Every Material component maps onto the same three sizes.
    val type = Typography(
        displayLarge = Type.display, displayMedium = Type.display, displaySmall = Type.display,
        headlineLarge = Type.display, headlineMedium = Type.display, headlineSmall = Type.display,
        titleLarge = Type.body.copy(fontWeight = FontWeight.SemiBold), titleMedium = Type.body.copy(fontWeight = FontWeight.Medium),
        titleSmall = Type.body.copy(fontWeight = FontWeight.Medium),
        bodyLarge = Type.body, bodyMedium = Type.body, bodySmall = Type.caption,
        labelLarge = Type.body.copy(fontWeight = FontWeight.Medium), labelMedium = Type.caption, labelSmall = Type.caption,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
