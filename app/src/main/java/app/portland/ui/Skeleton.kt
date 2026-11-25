package app.portland.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// One sweep: the band crosses the screen, then rests, so the grid breathes instead of strobing.
private const val SWEEP_MS = 1100L
private const val REST_MS = 500L

/**
 * A soft highlight that sweeps across a placeholder. Draws behind the content, so apply it after
 * clip/background and drop it (or let the image cover it) once the real thing has loaded.
 *
 * Cheap by construction: it never recomposes or relayouts. Each frame only invalidates this
 * node's draw, the gradient shader is cached (constant size, moved by translation), and every
 * placeholder reads the same frame clock in root coordinates, so a grid of them shows one light
 * passing over the screen instead of many out-of-step shimmers. The frame loop dies with the node
 * (scrolled away, image loaded) and never starts when system animations are turned off.
 */
fun Modifier.shimmer(highlight: Color): Modifier = this then ShimmerElement(highlight)

private data class ShimmerElement(val highlight: Color) : ModifierNodeElement<ShimmerNode>() {
    override fun create() = ShimmerNode(highlight)
    override fun update(node: ShimmerNode) = node.setHighlight(highlight)
    override fun InspectorInfo.inspectableProperties() {
        name = "shimmer"
    }
}

private class ShimmerNode(private var highlight: Color) : Modifier.Node(), DrawModifierNode {
    private var frameMs = 0L
    private var loop: Job? = null
    private var brush: Brush? = null
    private var brushW = 0f

    override val shouldAutoInvalidate get() = false

    fun setHighlight(c: Color) {
        if (c == highlight) return
        highlight = c
        brush = null
        invalidateDraw()
    }

    override fun onAttach() {
        loop = coroutineScope.launch {
            // "Remove animations" (animator scale 0): a still placeholder, no frame callbacks at all.
            if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return@launch
            while (isActive) {
                withFrameMillis { frameMs = it }
                invalidateDraw()
            }
        }
    }

    override fun onDetach() {
        loop?.cancel()
        loop = null
    }

    override fun ContentDrawScope.draw() {
        if (frameMs != 0L) {
            val t = (frameMs % (SWEEP_MS + REST_MS)).toFloat() / SWEEP_MS
            if (t <= 1f) {
                val coords = requireLayoutCoordinates()
                val screenW = coords.root().size.width.toFloat()
                val bandW = screenW * 0.6f
                // Smoothstep, so the light glides in and out instead of sliding at constant speed.
                val e = t * t * (3 - 2 * t)
                val bandX = -bandW + e * (screenW + bandW) - coords.positionInRoot().x
                if (bandX < size.width && bandX + bandW > 0f) {
                    val b = brush?.takeIf { brushW == bandW }
                        ?: Brush.horizontalGradient(listOf(Color.Transparent, highlight, Color.Transparent), endX = bandW)
                            .also { brush = it; brushW = bandW }
                    clipRect {
                        translate(left = bandX) { drawRect(b, size = Size(bandW, size.height)) }
                    }
                }
            }
        }
        drawContent()
    }
}

private fun LayoutCoordinates.root(): LayoutCoordinates {
    var c = this
    while (true) c = c.parentLayoutCoordinates ?: return c
}

/** Placeholder for a pin that is still being copied in: same shape and rhythm as a real card. */
@Composable
fun SkeletonCard(ratio: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(CardShape).background(p.fill).shimmer(p.shimmer))
        SkeletonLine(0.62f)
    }
}

/** A one-line text placeholder exactly as tall as a caption line, so swapping in the text doesn't jump. */
@Composable
fun SkeletonLine(fraction: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp).height(18.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth(fraction).height(9.dp).clip(RoundedCornerShape(5.dp)).background(p.fill).shimmer(p.shimmer))
    }
}
