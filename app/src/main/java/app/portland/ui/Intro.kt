package app.portland.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.portland.R
import app.portland.store
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The launch intro, after the user's wireframe, on one clock (ms) so no beat can drift:
 *   0–700     the splash logo settles while 8 of your newest pins burst out of it into an orbit ring
 *   0–1300    the ring spins, faster as it tightens (angular momentum), the far side smaller and dimmer
 *   700–1350  the ring spirals in to a loose, tilted stack over the logo
 *   1300–1600 the cards are swallowed one by one; the logo pulses with a soft halo and a haptic tick
 *   1680–2120 the logo lifts away and the feed rises in underneath
 * Every transform is read inside graphicsLayer {}, so the intro never recomposes while it runs.
 */
private const val CARDS = 8
private const val ENTER_STAGGER = 45f
private const val ENTER = 420f
private const val CONVERGE_AT = 700f
private const val CONVERGE = 650f
private const val ABSORB_AT = 1300f
private const val ABSORB_STAGGER = 18f
private const val ABSORB = 280f
private const val PULSE_AT = 1360f
private const val PULSE = 380f
private const val HANDOFF_AT = 1680f
private const val HANDOFF = 440f
private const val TOTAL = HANDOFF_AT + HANDOFF

// With an icon background (themes.xml forces white) the platform splash draws the adaptive icon as a 160dp
// disc cut from a 240dp canvas, centred in the window. The intro's first frame draws exactly that, so the
// hand-off from the system splash is invisible.
private val SplashDisc = 160.dp
private val SplashCanvas = 240.dp
private const val LOGO_REST = 0.72f // settled logo: ~115dp, so the ring fits a phone's width

private val Ring = 136.dp
private const val ELLIPSE = 0.86f // a slightly tilted ring reads as 3D
private val CardW = 70.dp
private val CardH = 54.dp

private val EaseOut = CubicBezierEasing(0.32f, 0.72f, 0f, 1f) // Apple's default ease-out
private val EaseInOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val EaseIn = CubicBezierEasing(0.5f, 0f, 0.9f, 0.4f)

private fun seg(t: Float, start: Float, dur: Float) = ((t - start) / dur).coerceIn(0f, 1f)

/** Ease-out with a small overshoot (a spring that settles once), for things that land. */
private fun backOut(x: Float, s: Float = 1.4f): Float {
    val y = x - 1f
    return 1f + y * y * ((s + 1f) * y + s)
}

/** Orbit angle in degrees after [t] ms: starts slow and speeds up as the ring tightens. */
private fun spin(t: Float) = 0.085f * t + 0.00011f * t * t

// Fixed per-card variety (a seeded look: the same intro every launch).
private val ClusterX = floatArrayOf(-9f, 8f, -4f, 11f, -12f, 3f, 7f, -6f)
private val ClusterY = floatArrayOf(-7f, -10f, 9f, 3f, 5f, -2f, 10f, 1f)
private val ClusterTilt = floatArrayOf(-8f, 6f, -3f, 9f, -10f, 4f, -6f, 7f)
private val OrbitTilt = floatArrayOf(-4f, 3f, -2f, 5f, -3f, 4f, -5f, 2f)

/**
 * Plays the intro over [content] (the app, composed underneath from the first frame so it is ready when the
 * intro hands over). [play] false shows [content] straight away. A tap skips to the hand-off.
 */
@Composable
fun IntroHost(play: Boolean, content: @Composable () -> Unit) {
    val motion = motionEnabled()
    var done by rememberSaveable { mutableStateOf(!play || !motion) }
    val clock = remember { Animatable(if (done) TOTAL else 0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    // Flips once, so the feed's draw is skipped during the intro without redrawing it every frame. It starts
    // drawing (still hidden under the opaque intro) while the cards are being swallowed, so its first, costly
    // frame never lands on the hand-off.
    val showFeed by remember { derivedStateOf { clock.value >= ABSORB_AT + 150f } }

    if (!done) LaunchedEffect(Unit) {
        // Start only once frames flow steadily: the first frames of a cold start (composing the feed, the splash
        // leaving) can take long, and a clock started under them would play the intro behind a frozen screen.
        var last = withFrameNanos { it }
        val giveUp = last + 1_500_000_000L
        var steady = 0
        while (steady < 3 && last < giveUp) {
            val now = withFrameNanos { it }
            steady = if (now - last < 34_000_000L) steady + 1 else 0
            last = now
        }
        launch { clock.animateTo(TOTAL, tween((TOTAL - clock.value).toInt(), easing = LinearEasing)) }
        launch {
            snapshotFlow { clock.value >= PULSE_AT }.first { it }
            if (clock.value < HANDOFF_AT) haptics.play(Fx.Tick)
        }
        snapshotFlow { clock.value >= TOTAL }.first { it }
        done = true
    }

    Box(Modifier.fillMaxSize()) {
        // The feed is composed (and laid out) from the first frame but drawn only from the hand-off, when it rises
        // from slightly smaller as the intro lifts away. It keeps this place in the tree, so finishing never rebuilds it.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = lerp(0.94f, 1f, EaseOut.transform(seg(clock.value, HANDOFF_AT, HANDOFF)))
                    scaleX = s
                    scaleY = s
                }
                .drawWithContent { if (showFeed) drawContent() },
        ) { content() }
        if (!done) Intro(
            clock = { clock.value },
            onSkip = {
                if (clock.value < HANDOFF_AT) scope.launch {
                    clock.snapTo(HANDOFF_AT)
                    clock.animateTo(TOTAL, tween(HANDOFF.toInt(), easing = LinearEasing))
                }
            },
        )
    }
}

@Composable
private fun Intro(clock: () -> Float, onSkip: () -> Unit) {
    val p = LocalPalette.current
    val store = LocalContext.current.store
    // The newest pins; an empty library still gets a ring of quiet placeholder tiles.
    val thumbs = remember { store.library.value.pins.take(CARDS).map(store::thumb) }
    // The window colour, not the palette's: it is what the system splash showed a frame ago.
    val canvas = colorResource(R.color.canvas)

    Box(
        Modifier
            .fillMaxSize()
            .preferredFrameRate(FrameRateCategory.High)
            .drawBehind { drawRect(canvas, alpha = 1f - EaseInOut.transform(seg(clock(), HANDOFF_AT, HANDOFF * 0.8f))) }
            .pointerInput(Unit) { detectTapGestures { onSkip() } },
        contentAlignment = Alignment.Center,
    ) {
        // A soft halo that blooms with the pulse, so the logo seems to take the light of what it absorbed.
        Box(
            Modifier
                .size(SplashDisc)
                .graphicsLayer {
                    val t = clock()
                    alpha = 0.55f * sin(PI.toFloat() * seg(t, PULSE_AT - 60f, PULSE + 120f))
                    val s = LOGO_REST * lerp(1.1f, 1.9f, EaseOut.transform(seg(t, PULSE_AT - 60f, PULSE + 120f)))
                    scaleX = s
                    scaleY = s
                }
                .background(Brush.radialGradient(listOf(p.ink.copy(alpha = 0.35f), Color.Transparent)), CircleShape),
        )
        for (i in 0 until CARDS) {
            val model = thumbs.getOrNull(i)
            Box(
                Modifier
                    .size(CardW, CardH)
                    .graphicsLayer { card(i, clock()) }
                    .background(p.fill)
                    .border(0.5.dp, p.line, Corners.small),
            ) {
                if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        // The logo, drawn exactly like the system splash icon on frame one.
        Box(
            Modifier
                .size(SplashDisc)
                .graphicsLayer { logo(clock()) }
                .clip(CircleShape)
                // Dark mode inverts it (dark disc, light mark), matching values-night-v31/themes.xml.
                .background(if (p.dark) colorResource(R.color.logo_disc_night) else Color.White)
                .semantics { contentDescription = "Portland" },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.mipmap.ic_launcher_foreground),
                null,
                Modifier.requiredSize(SplashCanvas),
                colorFilter = if (p.dark) ColorFilter.tint(colorResource(R.color.logo_mark_night)) else null,
            )
        }
    }
}

private fun GraphicsLayerScope.logo(t: Float) {
    val settle = lerp(1f, LOGO_REST, backOut(EaseOut.transform(seg(t, 60f, 560f)), 0.9f))
    // Each card that lands nudges it; the pulse peaks just after the last one.
    val pulse = 1f + 0.09f * sin(PI.toFloat() * backOut(seg(t, PULSE_AT, PULSE), 0.6f).coerceIn(0f, 1f))
    val h = seg(t, HANDOFF_AT, HANDOFF)
    val lift = lerp(1f, 1.45f, EaseIn.transform(h))
    val s = settle * pulse * lift
    scaleX = s
    scaleY = s
    alpha = 1f - EaseInOut.transform(seg(t, HANDOFF_AT + 40f, HANDOFF - 140f))
    blur(16.dp.toPx() * EaseIn.transform(h))
}

private fun GraphicsLayerScope.card(i: Int, t: Float) {
    // Entrance: out of the logo, staggered around the ring, landing with a slight overshoot and out of a blur.
    val e = seg(t, i * ENTER_STAGGER, ENTER)
    val enter = EaseOut.transform(e)
    // Converge: the ring spirals into a loose stack.
    val c = EaseInOut.transform(seg(t, CONVERGE_AT, CONVERGE))
    // Absorb: pulled into the logo, one after another.
    val a = EaseIn.transform(seg(t, ABSORB_AT + i * ABSORB_STAGGER, ABSORB))

    val theta = (i * 360f / CARDS - 90f + spin(t)) * (PI.toFloat() / 180f)
    val r = Ring.toPx() * lerp(0.3f, 1f, enter) * (1f - c)
    val depth = (sin(theta) + 1f) / 2f * (1f - c) + c // far side of the ring is smaller and dimmer
    val x = cos(theta) * r + ClusterX[i].dp.toPx() * c
    val y = sin(theta) * r * ELLIPSE + ClusterY[i].dp.toPx() * c

    translationX = x * (1f - a)
    translationY = y * (1f - a)
    rotationZ = lerp(OrbitTilt[i] + 5f * cos(theta), ClusterTilt[i], c) * (1f - a)
    val s = lerp(0.5f, 1f, backOut(e, 1.2f)) * lerp(0.88f, 1f, depth) * lerp(1f, 0.84f, c) * (1f - a)
    scaleX = s
    scaleY = s
    alpha = enter * lerp(0.7f, 1f, depth) * (1f - seg(a, 0.55f, 0.45f))
    shape = Corners.small
    clip = true
    blur(10.dp.toPx() * (1f - enter) + 6.dp.toPx() * a)
}

private fun GraphicsLayerScope.blur(px: Float) {
    renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && px > 0.5f) BlurEffect(px, px) else null
}

/** False when the user turned animations off (developer options or accessibility "Remove animations"). */
@Composable
private fun motionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}
