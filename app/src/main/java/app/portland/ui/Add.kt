package app.portland.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.portland.R
import app.portland.data.Links
import app.portland.store
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val FabSize = 56.dp
// Half the button: a circle when closed, a sheet-like squircle when open.
private val MorphShape = RoundedRectangle(28.dp)

/**
 * The one primary action. A clear glass "+" that, when tapped, morphs into a glass panel: the button's own
 * glass stretches out from its corner (a bouncy spring, like an iOS 26 Liquid Glass menu), the plus turns away,
 * and the choices materialise out of a blur. Offers the photo picker or a pasted link, which is downloaded in
 * the background into [boardId] (null = no board). Steps aside while pins are selected ([visible] false).
 * Compose it late inside the screen's Box so it floats over the grid.
 */
@Composable
fun BoxScope.AddMenu(backdrop: Backdrop, boardId: String?, visible: Boolean = true) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val store = context.store
    val haptics = LocalHapticFeedback.current
    var open by rememberSaveable { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris -> store.launch { add(uris, boardId) } }
    if (!visible) open = false

    // 0 = the button, 1 = the panel. Opening overshoots a touch (the glass "jelly"); closing settles without bounce.
    val t by animateFloatAsState(
        if (open) 1f else 0f,
        if (open) spring(dampingRatio = 0.72f, stiffness = 260f) else spring(dampingRatio = 1f, stiffness = 380f),
        label = "morph",
    )
    BackHandler(open) { open = false }

    // The scrim dims the feed and closes the panel on a tap outside.
    if (t > 0.01f) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = t.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = if (p.dark) 0.36f else 0.16f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close") { open = false },
        )
    }

    AnimatedVisibility(
        visible,
        Modifier.align(Alignment.BottomEnd),
        enter = scaleIn(Motion.bouncy(), initialScale = 0.6f) + fadeIn(),
        exit = scaleOut(tween(160), targetScale = 0.6f) + fadeOut(tween(160)),
    ) {
        BoxWithConstraints(
            Modifier
                // Rises above the keyboard only for its own link field, not for the search field.
                .windowInsetsPadding(if (open) WindowInsets.ime.union(WindowInsets.navigationBars) else WindowInsets.navigationBars)
                .padding(20.dp),
        ) {
            val panelWidth = minOf(maxWidth, 380.dp)
            val press = remember { MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed && !open) 1.1f else 1f, Motion.press(), label = "press")
            Layout(
                content = {
                    AddPanel(
                        boardId = boardId,
                        open = open,
                        onPick = {
                            open = false
                            pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
                        },
                        onClose = { open = false },
                    )
                    Box(Modifier.size(FabSize), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = p.ink, modifier = Modifier.size(24.dp))
                    }
                },
                modifier = Modifier
                    // Clear glass as a button; as it opens, a frosted film fades in so the text reads over any feed.
                    .glass(backdrop, MorphShape, tint = lerpColor(p.glass, p.sheet.copy(alpha = if (p.dark) 0.78f else 0.82f), t), scale = { scale })
                    .clip(MorphShape)
                    // Only the closed button is a button; open, the panel's own controls take over for screen readers.
                    .then(
                        if (open) {
                            Modifier
                        } else {
                            Modifier
                                .clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = "Add images, videos or a link") {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    open = true
                                }
                                .semantics { contentDescription = "Add" }
                        },
                    ),
            ) { measurables, _ ->
                val fab = FabSize.roundToPx()
                val panel = measurables[0].measure(Constraints.fixedWidth(panelWidth.roundToPx()))
                val icon = measurables[1].measure(Constraints.fixed(fab, fab))
                // Size follows the spring past 1 (the overshoot) but never shrinks below the button.
                val grow = t.coerceAtLeast(0f)
                val w = lerp(fab.toFloat(), panel.width.toFloat(), grow).roundToInt().coerceAtLeast(fab)
                val h = lerp(fab.toFloat(), panel.height.toFloat(), grow).roundToInt().coerceAtLeast(fab)
                layout(w, h) {
                    // Content is pinned to the bottom-right corner, where the button was, so it unfolds from there.
                    // Folded away it is not placed at all, so its invisible controls cannot catch the button's taps.
                    if (t > 0.001f) panel.placeWithLayer(w - panel.width, h - panel.height) {
                        val c = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
                        alpha = c
                        scaleX = lerp(0.92f, 1f, c)
                        scaleY = scaleX
                        transformOrigin = TransformOrigin(1f, 1f)
                        val blur = (1f - c) * 16.dp.toPx()
                        renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0.5f) BlurEffect(blur, blur) else null
                    }
                    icon.placeWithLayer(w - fab, h - fab) {
                        rotationZ = 135f * t.coerceIn(0f, 1f)
                        alpha = (1f - t * 2.5f).coerceIn(0f, 1f)
                    }
                }
            }
        }
    }
}

/** What the panel offers. Laid out at full size; [AddMenu] reveals it as the glass grows. */
@Composable
private fun AddPanel(boardId: String?, open: Boolean, onPick: () -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboard.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }

    // A closed panel holds no focus, so the keyboard never stays up behind the button.
    LaunchedEffect(open) {
        if (!open) {
            focus.clearFocus()
            link = ""
            error = false
        }
    }

    fun save() {
        val url = Links.find(link)
        if (url == null) {
            error = true
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            // A sideways shake, as iOS does for a wrong passcode: a spring kicked with velocity, settling at rest.
            scope.launch { shake.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 900f), initialVelocity = 900f) }
            return
        }
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        Links.enqueue(context, url, boardId)
        onClose()
    }

    // While folded away the panel is invisible, so screen readers must not find it either.
    Column(
        Modifier
            .then(if (open) Modifier else Modifier.clearAndSetSemantics {})
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Add to Portland",
                style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                color = p.ink,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(p.fill)
                    .clickable(enabled = open, role = Role.Button, onClickLabel = "Close", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Close", tint = p.ink2, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Option(R.drawable.ic_photo, "Photos & videos", "Choose from your library", enabled = open, onClick = onPick)
        Spacer(Modifier.height(10.dp))
        val linkFocus = remember { FocusRequester() }
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(shake.value.roundToInt(), 0) }
                .height(52.dp)
                .glass(null, Corners.small)
                .clip(Corners.small)
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_link), contentDescription = null, tint = p.ink2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = link,
                onValueChange = { link = it; error = false },
                enabled = open,
                singleLine = true,
                textStyle = Type.body.copy(color = p.ink),
                cursorBrush = SolidColor(p.ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.weight(1f).focusRequester(linkFocus).semantics { contentDescription = "Link to an image or video" },
                decorationBox = { field ->
                    Box {
                        if (link.isEmpty()) Text("Paste a link", style = Type.body, color = p.ink3)
                        field()
                    }
                },
            )
            val empty = link.isBlank()
            val fill by animateFloatAsState(if (empty) 0f else 1f, Motion.snappy(), label = "send")
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .height(40.dp)
                    .clip(Corners.pill)
                    .background(lerpColor(p.fill, p.ink, fill))
                    .clickable(enabled = open, role = Role.Button, onClickLabel = if (empty) "Paste link" else "Save link") {
                        if (empty) {
                            scope.launch {
                                val pasted = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                link = Links.find(pasted) ?: pasted.trim()
                                error = false
                                linkFocus.requestFocus()
                            }
                        } else {
                            save()
                        }
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (empty) {
                    Text("Paste", style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = p.ink)
                } else {
                    Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = null, tint = p.canvas, modifier = Modifier.size(20.dp))
                }
            }
        }
        RevealText(if (error) "That doesn't look like a link" else "") {
            Text(
                it,
                style = Type.caption,
                color = p.danger,
                modifier = Modifier.padding(start = 4.dp, top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
internal fun Option(@DrawableRes icon: Int, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.press(), label = "press")
    Row(
        Modifier
            .fillMaxWidth()
            .glass(null, Corners.small, scale = { scale })
            .clip(Corners.small)
            .clickable(interactionSource = press, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = p.ink, modifier = Modifier.size(22.dp))
        Column {
            Text(title, style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.ink)
            Text(subtitle, style = Type.caption, color = p.ink3)
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))
