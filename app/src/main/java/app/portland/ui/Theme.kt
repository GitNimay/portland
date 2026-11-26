package app.portland.ui

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.portland.R
import app.portland.data.Prefs
import app.portland.data.ThemeMode
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedRectangularShape
import java.util.function.Consumer
import kotlin.math.roundToInt

/** Neutral ink ladder: alpha black on warm paper white (alpha white on OLED black in dark mode), plus Apple-style fills and glass. */
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
    val grouped: Color, // background of a grouped list sheet (Apple systemGroupedBackground, elevated)
    val cell: Color, // rows of a grouped list, one step above [grouped]
    val behind: Color, // what shows around a screen shrinking under predictive back
)

// Light canvas: a warm paper white (as on cursor.com) instead of stark #FFF; it is easier on the eyes and lets
// white cells and sheets read as raised. Dark is true OLED black: unlit pixels, with raised surfaces in Apple's greys.
private val Paper = Color(0xFFF7F7F4)
private val PaperRaised = Color(0xFFFCFCFA)

private val Light = Palette(
    canvas = Paper, ink = Color.Black, ink2 = Color.Black.copy(0.64f),
    ink3 = Color.Black.copy(0.56f), line = Color.Black.copy(0.10f), fill = Color(0x1F767680),
    sheet = PaperRaised, glass = Color.White.copy(0.14f), glassFlat = Color(0x1A767680),
    glassRegular = PaperRaised.copy(0.62f),
    glassSolid = Color(0xF2FAFAF7), thumb = Color.White, danger = Color(0xFFFF3B30), dark = false,
    shimmer = Color.White.copy(0.7f), scrim = Color.Black.copy(0.18f),
    grouped = Color(0xFFF2F1ED), cell = Color.White, behind = Color(0xFFE6E5E0),
)
private val Dark = Palette(
    canvas = Color.Black, ink = Color.White, ink2 = Color.White.copy(0.60f),
    ink3 = Color.White.copy(0.48f), line = Color.White.copy(0.14f), fill = Color(0x3D767680),
    sheet = Color(0xFF1C1C1E), glass = Color.Black.copy(0.10f), glassFlat = Color.White.copy(0.09f),
    glassRegular = Color(0xFF1C1C1E).copy(0.58f),
    glassSolid = Color(0xF21C1C1E), thumb = Color.White.copy(0.20f), danger = Color(0xFFFF453A), dark = true,
    shimmer = Color.White.copy(0.07f), scrim = Color.Black.copy(0.40f),
    grouped = Color(0xFF1C1C1E), cell = Color(0xFF2C2C2E), behind = Color(0xFF1C1C1E),
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
    frostOf: (() -> Float)? = null, // draw-time frost (overrides [frost]): animate it without recomposing
): Modifier {
    val p = LocalPalette.current
    // Android 11, or "Reduce transparency" in Settings: one solid fill, no backdrop recording or effects at all.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || LocalReduceTransparency.current) {
        val solid = if (tint == Color.Unspecified) p.glassSolid else tint.copy(alpha = 1f)
        return this
            .graphicsLayer { val s = scale(); scaleX = s; scaleY = s }
            .clip(shape)
            .background(solid)
            .border(0.5.dp, p.line, shape)
    }
    val clear = when {
        tint != Color.Unspecified -> tint
        backdrop == null -> p.glassFlat
        else -> p.glass
    }
    val regular = p.glassRegular
    val dark = p.dark
    return this.drawBackdrop(
        backdrop = backdrop ?: emptyBackdrop(),
        shape = { shape },
        effects = {
            if (backdrop != null) {
                colorControls(brightness = if (dark) 0f else 0.06f, saturation = 1.6f)
                val f = (frostOf?.invoke() ?: frost).coerceIn(0f, 1f)
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
        onDrawSurface = {
            val f = (frostOf?.invoke() ?: frost).coerceIn(0f, 1f)
            drawRect(if (f > 0f) androidx.compose.ui.graphics.lerp(clear, regular, f) else clear)
        },
    )
}

// Frost radius of regular glass: enough to turn busy content behind it into colour, not detail.
private const val RegularBlur = 14f

/**
 * Whether the system will blur behind a window right now. It can change while a sheet is open (battery saver,
 * the developer option, a TV-style tunnelled video), so it is state fed by the system listener, not a one-off read.
 * Always false before Android 12 and under "Reduce transparency".
 */
@Composable
fun rememberWindowBlurEnabled(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || LocalReduceTransparency.current) return false
    val context = LocalContext.current
    val wm = remember(context) { context.getSystemService(WindowManager::class.java) } ?: return false
    var enabled by remember(wm) { mutableStateOf(wm.isCrossWindowBlurEnabled) }
    DisposableEffect(wm) {
        val listener = Consumer<Boolean> { enabled = it } // also called once at registration with the current value
        wm.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        onDispose { wm.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled
}

/**
 * Container colour for a dialog or sheet: see-through when [FrostBehind] is blurring the app behind it, solid
 * otherwise (so a sheet never turns into plain see-through film when blur switches off). [base] defaults to
 * [Palette.sheet]; grouped lists pass [Palette.grouped].
 */
@Composable
fun frostedSheet(base: Color = LocalPalette.current.sheet): Color {
    val dark = LocalPalette.current.dark
    return if (rememberWindowBlurEnabled()) base.copy(alpha = if (dark) 0.68f else 0.74f) else base
}

/**
 * Frosts the app behind the current dialog or bottom-sheet window, so a [frostedSheet] container reads as glass.
 * Call it from inside the dialog's content. The blur and the dim follow [progress] (0 closed .. 1 open, read
 * outside composition), so they ease in and out with the panel instead of snapping on at the first frame and off
 * after it has gone. The window is only touched when the whole-pixel radius changes. No-op before Android 12;
 * where blur is off, the dim is a little stronger so the panel still stands apart.
 */
@Composable
fun FrostBehind(progress: () -> Float = { 1f }, radius: Dp = 28.dp) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val blur = rememberWindowBlurEnabled()
    val view = LocalView.current
    val maxPx = with(LocalDensity.current) { radius.toPx() }
    val read by rememberUpdatedState(progress)
    val window = remember(view) { ((view.parent as? DialogWindowProvider) ?: (view as? DialogWindowProvider))?.window }
    // A dialog dims its own window; a bottom sheet draws its scrim in Compose and has no dim layer. Read once,
    // before this function adds the flag itself.
    val windowDims = remember(window) { window != null && window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0 }
    // Start from nothing before the window's first frame (effects apply before it draws), so a dialog never
    // flashes its theme's default dim before the eased one below takes over.
    DisposableEffect(window) {
        window?.attributes = window?.attributes?.apply { dimAmount = 0f; blurBehindRadius = 0 }
        onDispose {}
    }
    LaunchedEffect(window, blur, maxPx) {
        window ?: return@LaunchedEffect
        if (blur) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        // The system draws blur-behind on the window's dim layer, and skips a dim layer with zero alpha, so a
        // sheet gets a dim layer too: just enough alpha to carry the blur, invisible on top of its own scrim.
        if (blur && !windowDims) window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val dim = when {
            !windowDims -> CarrierDim
            blur -> BlurDim
            else -> SolidDim
        }
        // Whole pixels, and a dim quantised to the same steps: at most ~radius updates per open or close.
        snapshotFlow { (read().coerceIn(0f, 1f) * maxPx).roundToInt() }.collect { px ->
            window.attributes = window.attributes.apply {
                if (blur) blurBehindRadius = px
                dimAmount = dim * (px / maxPx).coerceIn(0f, 1f)
            }
        }
    }
}

/**
 * Keeps a dialog or sheet window's status and navigation bar icons readable when the theme changes while it is
 * open (a sheet window picks its icon colours when it is created). Call from inside the window's content.
 */
@Composable
fun SyncWindowBars() {
    val view = LocalView.current
    val dark = LocalPalette.current.dark
    LaunchedEffect(view, dark) {
        val window = ((view.parent as? DialogWindowProvider) ?: (view as? DialogWindowProvider))?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private const val BlurDim = 0.18f
private const val SolidDim = 0.32f
private const val CarrierDim = 0.01f

/**
 * [FrostBehind] for a bottom sheet's content: the frost follows how far the sheet has risen into the window,
 * so it tracks the open and close animations and a finger dragging the sheet down. Apply it to the sheet's
 * outermost content (the full-width column below the drag handle).
 */
@Composable
fun Modifier.frostBehindSheet(radius: Dp = 28.dp): Modifier {
    val risen = remember { mutableFloatStateOf(0f) }
    FrostBehind({ risen.floatValue }, radius)
    return onGloballyPositioned { c ->
        val h = c.size.height
        if (h <= 0) return@onGloballyPositioned
        val windowH = c.findRootCoordinates().size.height
        risen.floatValue = ((windowH - c.positionInRoot().y) / minOf(h, windowH)).coerceIn(0f, 1f)
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
    // Nothing under the header yet: skip the blur and shader passes entirely (recomposes only when this flips).
    val active by remember(visible) { derivedStateOf { visible() > 0.001f } }
    if (!active) return this
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || LocalReduceTransparency.current) {
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

/** "Reduce transparency" (Settings): glass, sheets and blurs draw as solid surfaces. */
val LocalReduceTransparency = staticCompositionLocalOf { false }

/** Stands in for the system haptics when the user turns Haptics off in Settings. */
private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

// Every Material component maps onto the same type roles. Built once: it never changes.
private val PortlandTypography = Typography(
    displayLarge = Type.largeTitle, displayMedium = Type.largeTitle, displaySmall = Type.largeTitle,
    headlineLarge = Type.largeTitle, headlineMedium = Type.title, headlineSmall = Type.title,
    titleLarge = Type.title, titleMedium = Type.headline, titleSmall = Type.headline,
    bodyLarge = Type.body, bodyMedium = Type.body, bodySmall = Type.footnote,
    labelLarge = Type.body.copy(fontWeight = FontWeight.Medium), labelMedium = Type.footnote, labelSmall = Type.label,
)

private fun schemeFor(p: Palette): ColorScheme {
    val sheet = p.sheet
    return (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.ink, onPrimary = p.canvas, secondary = p.ink, onSecondary = p.canvas,
        background = p.canvas, onBackground = p.ink, surface = p.canvas, onSurface = p.ink,
        onSurfaceVariant = p.ink2, outline = p.line, outlineVariant = p.line,
        surfaceContainerLowest = sheet, surfaceContainerLow = sheet, surfaceContainer = sheet,
        surfaceContainerHigh = sheet, surfaceContainerHighest = sheet, surfaceTint = Color.Transparent,
        scrim = p.scrim,
    )
}
private val LightScheme by lazy(LazyThreadSafetyMode.NONE) { schemeFor(Light) }
private val DarkScheme by lazy(LazyThreadSafetyMode.NONE) { schemeFor(Dark) }

/** Resolves Settings' theme choice against the system, for code outside [PortlandTheme]. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun PortlandTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs by remember(context) { Prefs.state(context) }.collectAsState()
    val dark = prefs.theme.isDark()
    val p = if (dark) Dark else Light
    // The activity handles uiMode changes itself (no recreation), so the window and system bars follow here.
    val activity = context as? ComponentActivity
    if (activity != null) {
        LaunchedEffect(activity, dark) {
            activity.window.setBackgroundDrawable(ColorDrawable(p.canvas.toArgb()))
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(NavScrimLight, NavScrimDark) { dark },
            )
        }
    }
    val systemHaptics = LocalHapticFeedback.current
    val haptics = rememberHaptics(prefs.haptics)
    CompositionLocalProvider(
        LocalPalette provides p,
        LocalReduceTransparency provides prefs.reduceTransparency,
        LocalHaptics provides haptics,
        // Material components' own feedback obeys the same switch.
        LocalHapticFeedback provides if (prefs.haptics) systemHaptics else NoHaptics,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = PortlandTypography, content = content)
    }
}

// enableEdgeToEdge()'s own defaults for the 3-button navigation bar scrim.
private val NavScrimLight = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NavScrimDark = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)
