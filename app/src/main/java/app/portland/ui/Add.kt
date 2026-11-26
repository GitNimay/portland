package app.portland.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import app.portland.R
import app.portland.data.Links
import app.portland.store
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val FabSize = 56.dp
// Half the button's height: a circle when closed, a capsule as it stretches.
private val MorphShape = RoundedRectangle(28.dp)
private val Slot = 48.dp
private val Inset = 4.dp
// The icon row: three slots, then the button itself (now a close button) at the end.
private val IconsWidth = Inset + Slot * 3 + FabSize

/** Closed: the "+" alone. Icons: a row of what can be added. Link: the same glass stretched into a field. */
private enum class Stage { Closed, Icons, Link }

/**
 * The one primary action, icon only. The clear glass "+" stretches sideways into a capsule of icons (photo
 * library, paste a copied link, type a link) while the plus turns into a close mark; the link icon stretches
 * the same glass again, across the screen, into a text field. Every change is the glass growing or shrinking
 * from the button's corner on a spring, with the contents coming into focus out of a blur. Links download in
 * the background into [boardId] (null = no board). Steps aside while pins are selected ([visible] false).
 * Compose it late inside the screen's Box so it floats over the grid.
 */
@Composable
fun BoxScope.AddMenu(backdrop: Backdrop, boardId: String?, visible: Boolean = true) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val store = context.store
    val haptics = LocalHaptics.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var stage by rememberSaveable { mutableStateOf(Stage.Closed) }
    var link by rememberSaveable { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    val pick = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris -> store.launch { add(uris, boardId) } }
    if (!visible) stage = Stage.Closed
    val open = stage != Stage.Closed

    // 0 = the button, 1 = open: drives the frost, the scrim and the plus turning into a close mark.
    val t by animateFloatAsState(if (open) 1f else 0f, if (open) Motion.morphOpen() else Motion.morphClose(), label = "open")
    BackHandler(open) { stage = if (stage == Stage.Link) Stage.Icons else Stage.Closed }

    fun close() {
        stage = Stage.Closed
        link = ""
    }

    // A sideways shake, as iOS does for a wrong passcode: a spring kicked with velocity, settling at rest.
    fun reject() {
        haptics.play(Fx.Reject)
        scope.launch { shake.animateTo(0f, spring(dampingRatio = 0.2f, stiffness = 900f), initialVelocity = 900f) }
    }

    fun save(text: String) {
        val url = Links.find(text) ?: return reject()
        haptics.play(Fx.Confirm)
        Links.enqueue(context, url, boardId)
        close()
    }

    suspend fun clip(): String =
        clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()

    // The scrim dims the feed and closes everything on a tap outside.
    val scrimOn by remember { derivedStateOf { t > 0.01f } }
    if (scrimOn) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = t.coerceIn(0f, 1f) }
                .background(p.scrim)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close") { close() },
        )
    }

    AnimatedVisibility(
        visible,
        Modifier.align(Alignment.BottomEnd),
        enter = scaleIn(Motion.bouncy(), initialScale = 0.6f) + fadeIn(),
        exit = scaleOut(Motion.dismiss(), targetScale = 0.6f) + fadeOut(tween(160)),
    ) {
        BoxWithConstraints(
            Modifier
                // Rises above the keyboard only for its own link field, not for the search field.
                .windowInsetsPadding(if (stage == Stage.Link) WindowInsets.ime.union(WindowInsets.navigationBars) else WindowInsets.navigationBars)
                .padding(20.dp),
        ) {
            val full = maxWidth
            val target = when (stage) {
                Stage.Closed -> FabSize
                Stage.Icons -> IconsWidth
                Stage.Link -> full
            }
            // Growing overshoots a touch (the glass "jelly"); folding back settles without a bounce.
            val width by animateDpAsState(target, if (open) Motion.morphOpen() else Motion.morphClose(), label = "width")
            val press = remember { MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed && !open) 1.1f else 1f, Motion.press(), label = "press")

            Row(
                Modifier
                    .offset { IntOffset(shake.value.roundToInt(), 0) }
                    // Width is read in layout, so the morph never recomposes the contents.
                    .layout { m, c ->
                        val w = c.constrainWidth(width.coerceAtLeast(FabSize).roundToPx())
                        val h = c.constrainHeight(FabSize.roundToPx())
                        val pl = m.measure(Constraints.fixed(w, h))
                        layout(w, h) { pl.place(0, 0) }
                    }
                    // Clear glass as a button; frosted as it opens, so icons and text read over any feed.
                    .glass(backdrop, MorphShape, scale = { scale }, frostOf = { t })
                    .clip(MorphShape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Contents keep their full width and are pinned to the button's side, so the growing glass
                // uncovers them from the corner instead of squeezing them.
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                    AnimatedContent(
                        targetState = stage,
                        transitionSpec = {
                            val from = TransformOrigin(1f, 0.5f)
                            (fadeIn(tween(200, delayMillis = 60)) + scaleIn(Motion.smooth(), initialScale = 0.8f, transformOrigin = from)) togetherWith
                                (fadeOut(tween(90)) + scaleOut(Motion.dismiss(), targetScale = 0.8f, transformOrigin = from)) using null
                        },
                        contentAlignment = Alignment.CenterEnd,
                        modifier = Modifier.wrapContentWidth(Alignment.End, unbounded = true),
                        label = "stage",
                    ) { s ->
                        when (s) {
                            Stage.Closed -> Spacer(Modifier.size(0.dp))
                            Stage.Icons -> Row(materialize().padding(start = Inset), verticalAlignment = Alignment.CenterVertically) {
                                GlassIcon(R.drawable.ic_photo, "Photos and videos") {
                                    close()
                                    pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
                                }
                                GlassIcon(R.drawable.ic_clipboard, "Paste a copied link") {
                                    scope.launch {
                                        val copied = clip()
                                        if (Links.find(copied) != null) {
                                            save(copied)
                                        } else {
                                            // Nothing usable copied: open the field to type one instead.
                                            link = copied.trim()
                                            stage = Stage.Link
                                            reject()
                                        }
                                    }
                                }
                                GlassIcon(R.drawable.ic_link, "Type a link") { stage = Stage.Link }
                            }
                            Stage.Link -> LinkField(
                                link = link,
                                onLink = { link = it },
                                onPaste = { scope.launch { link = clip().let { c -> Links.find(c) ?: c.trim() } } },
                                onSave = { save(link) },
                                modifier = materialize().requiredWidth(full - FabSize),
                            )
                        }
                    }
                }
                // The button itself: "+" closed, a close mark open (the plus turns an eighth).
                Box(
                    Modifier
                        .size(FabSize)
                        .clickable(
                            interactionSource = press,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = if (open) "Close" else "Add images, videos or a link",
                        ) {
                            if (open) {
                                close()
                            } else {
                                haptics.play(Fx.Tick)
                                stage = Stage.Icons
                            }
                        }
                        .semantics { contentDescription = if (open) "Close" else "Add" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_plus),
                        contentDescription = null,
                        tint = p.ink,
                        modifier = Modifier.size(IconSize.large).graphicsLayer { rotationZ = 45f * t },
                    )
                }
            }
        }
    }
}

/** The link stage: a link mark, the field, and one round action: paste while empty, save once there's text. */
@Composable
private fun LinkField(link: String, onLink: (String) -> Unit, onPaste: () -> Unit, onSave: () -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Leaving the stage drops focus, so the keyboard never stays up behind the button.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { focusManager.clearFocus() } }
    val empty = link.isBlank()
    val fill by animateFloatAsState(if (empty) 0f else 1f, Motion.snappy(), label = "send")
    Row(modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_link), contentDescription = null, tint = p.ink3, modifier = Modifier.size(IconSize.medium))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = link,
            onValueChange = onLink,
            singleLine = true,
            textStyle = Type.body.copy(color = p.ink),
            cursorBrush = SolidColor(p.ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Link to an image or video" },
            decorationBox = { field ->
                Box {
                    if (link.isEmpty()) Text("Paste a link", style = Type.body, color = p.ink3)
                    field()
                }
            },
        )
        GlassIcon(
            icon = if (empty) R.drawable.ic_clipboard else R.drawable.ic_arrow_up,
            label = if (empty) "Paste" else "Save link",
            size = 40.dp,
            tint = lerp(p.ink, p.canvas, fill),
            fill = lerp(p.fill.copy(alpha = 0f), p.ink, fill),
            onClick = { if (empty) onPaste() else onSave() },
        )
    }
}
