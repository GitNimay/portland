package app.portland.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.portland.BuildConfig
import app.portland.R
import app.portland.data.Prefs
import app.portland.data.Reminders
import app.portland.data.ThemeMode
import coil3.SingletonImageLoader
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App settings, laid out like iOS Settings: inset grouped sections on a grouped background, rows with coloured
 * icon tiles, section headers above and explanations below. Every change applies at once; there is no Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    val prefs by remember(context) { Prefs.state(context) }.collectAsStateWithLifecycle()
    val snap by remember(context) { Reminders.state(context) }.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(Reminders.hasNotificationPermission(context)) }
    var systemEnabled by remember { mutableStateOf(Reminders.notificationsEnabledInSystem(context)) }
    var askSettings by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = Reminders.hasNotificationPermission(context)
                systemEnabled = Reminders.notificationsEnabledInSystem(context)
                if (hasPermission && systemEnabled) {
                    askSettings = false
                    Reminders.ensureScheduled(context)
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { cacheBytes = imageCacheBytes(context) }

    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        systemEnabled = Reminders.notificationsEnabledInSystem(context)
        Reminders.onPermissionResult(context, granted)
        if (granted) {
            askSettings = false
            haptics.play(Fx.ToggleOn)
        } else {
            val activity = context as? Activity
            askSettings = activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    val notificationsActive = snap.enabled && hasPermission && systemEnabled

    fun toggleNotifications() {
        if (notificationsActive) {
            haptics.play(Fx.ToggleOff)
            Reminders.setEnabled(context, false)
            return
        }
        if (!hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (askSettings) openNotificationSettings(context) else requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!systemEnabled) {
            openNotificationSettings(context)
            return
        }
        haptics.play(Fx.ToggleOn)
        Reminders.setEnabled(context, true)
    }

    fun toggleHaptics() {
        val on = !prefs.haptics
        // Felt either way: the switch previews what it turns on, and says goodbye as it turns off.
        haptics.play(if (on) Fx.ToggleOn else Fx.ToggleOff, force = true)
        Prefs.setHaptics(context, on)
    }

    fun toggleReduceTransparency() {
        haptics.play(if (prefs.reduceTransparency) Fx.ToggleOff else Fx.ToggleOn)
        Prefs.setReduceTransparency(context, !prefs.reduceTransparency)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = frostedSheet(p.grouped),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = Corners.sheetRadius, topEnd = Corners.sheetRadius),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 36.dp, height = 5.dp, color = p.ink3.copy(alpha = 0.5f)) },
    ) {
        Column(
            Modifier
                .frostBehindSheet()
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                "Settings",
                style = Type.headline,
                color = p.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )

            SettingsGroup(
                header = "Appearance",
                footer = "Solid surfaces instead of glass and blur: easier to read, and lighter on older phones.",
            ) {
                ThemePicker(
                    selected = prefs.theme,
                    onSelect = { mode ->
                        if (mode != prefs.theme) {
                            haptics.play(Fx.Tick)
                            Prefs.setTheme(context, mode)
                        }
                    },
                )
                RowDivider(inset = 0.dp)
                SettingsRow(
                    icon = R.drawable.ic_transparency,
                    tile = if (p.dark) Color(0xFF0A84FF) else Color(0xFF007AFF),
                    title = "Reduce transparency",
                    role = Role.Switch,
                    onClick = ::toggleReduceTransparency,
                ) { PillSwitch(checked = prefs.reduceTransparency) }
            }

            SettingsGroup(
                footer = if (hasPermission && systemEnabled) "A pin you saved comes back now and then: twice a day at most, never at night." else null,
            ) {
                SettingsRow(
                    icon = R.drawable.ic_bell,
                    tile = if (p.dark) Color(0xFFFF453A) else Color(0xFFFF3B30),
                    title = "Notifications",
                    role = Role.Switch,
                    onClick = ::toggleNotifications,
                ) { PillSwitch(checked = notificationsActive) }
                RowDivider()
                SettingsRow(
                    icon = R.drawable.ic_vibrate,
                    tile = if (p.dark) Color(0xFFFF375F) else Color(0xFFFF2D55),
                    title = "Haptics",
                    role = Role.Switch,
                    onClick = ::toggleHaptics,
                ) { PillSwitch(checked = prefs.haptics) }
            }
            if (!hasPermission || !systemEnabled) {
                Text(
                    "Notifications are off in Android settings · Open",
                    style = Type.footnote,
                    color = p.ink3,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = { openNotificationSettings(context) },
                        ),
                )
            }

            SettingsGroup(
                header = "Storage",
                footer = "Images saved on this phone and in your backup are kept; only downloaded copies are removed.",
            ) {
                SettingsRow(
                    icon = R.drawable.ic_storage,
                    tile = Color(0xFF8E8E93),
                    title = "Image cache",
                    role = Role.Button,
                    enabled = (cacheBytes ?: 0L) > 0L,
                    onClick = { confirmClear = true },
                ) {
                    Text(
                        cacheBytes?.let { Formatter.formatShortFileSize(context, it) }.orEmpty(),
                        style = Type.body.digits(),
                        color = p.ink3,
                    )
                }
            }

            Text(
                "Portland ${BuildConfig.VERSION_NAME}",
                style = Type.footnote.digits(),
                color = p.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
            )
        }
    }

    if (confirmClear) {
        PortlandDialog(
            title = "Clear image cache?",
            body = "Your pins stay. Their images download again from your backup when you next see them.",
            confirmLabel = "Clear",
            onConfirm = {
                confirmClear = false
                scope.launch {
                    clearImageCache(context)
                    cacheBytes = imageCacheBytes(context)
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/** A titled, inset-grouped card of rows, as in iOS Settings. */
@Composable
private fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    if (header != null) {
        Text(
            header.uppercase(),
            style = Type.footnote.copy(letterSpacing = 0.02.em),
            color = p.ink3,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 7.dp),
        )
    } else {
        Spacer(Modifier.height(28.dp))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Corners.card)
            .background(p.cell),
        content = content,
    )
    if (footer != null) {
        Text(
            footer,
            style = Type.footnote,
            color = p.ink3,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
        )
    }
}

/**
 * One row: a coloured icon tile, a title, and [trailing] (a switch, a value). The whole row is the touch target
 * and greys under the finger, as iOS rows do.
 */
@Composable
private fun SettingsRow(
    @DrawableRes icon: Int,
    tile: Color,
    title: String,
    role: Role,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val wash by animateFloatAsState(if (pressed) 1f else 0f, Motion.press(), label = "rowPress")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .drawBehind {
                val w = wash.coerceIn(0f, 1f)
                if (w > 0f) drawRect(p.fill, alpha = w)
            }
            .clickable(interactionSource = press, indication = null, enabled = enabled, role = role, onClickLabel = title, onClick = onClick)
            .padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RowTile)
                .background(tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(
            title,
            style = Type.body,
            color = p.ink,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 12.dp),
        )
        trailing()
    }
}

// Apple's icon tiles: a 30pt continuous-corner square.
private val RowTile = RoundedRectangle(8.dp)

/** Hairline between rows, starting under the title as in iOS (past the icon tile) unless [inset] says otherwise. */
@Composable
private fun RowDivider(inset: Dp = 58.dp) {
    val p = LocalPalette.current
    Box(
        Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(p.line),
    )
}

/**
 * Light / Dark / System, as in iOS's Display & Brightness: a small picture of the app in each look, a label and
 * a check circle under each. System shows both halves.
 */
@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (mode in ThemeMode.entries) {
            ThemeOption(mode, mode == selected) { onSelect(mode) }
        }
    }
}

@Composable
private fun ThemeOption(mode: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val label = when (mode) {
        ThemeMode.System -> "System"
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
    }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, Motion.press(), label = "themePress")
    val ring by animateColorAsState(if (selected) p.ink else p.line, Motion.snappy(), label = "themeRing")
    Column(
        Modifier
            .widthIn(max = 84.dp)
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .clickable(interactionSource = press, indication = null, role = Role.RadioButton, onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .width(64.dp)
                .aspectRatio(0.56f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(if (selected) 2.dp else 1.dp, ring, PreviewShape)
                .padding(4.dp)
                .clip(PreviewInnerShape),
        ) {
            when (mode) {
                ThemeMode.Light -> drawAppPreview(dark = false)
                ThemeMode.Dark -> drawAppPreview(dark = true)
                ThemeMode.System -> {
                    clipRect(right = size.width / 2f) { drawAppPreview(dark = false) }
                    clipRect(left = size.width / 2f) { drawAppPreview(dark = true) }
                }
            }
        }
        Text(label, style = Type.footnote, color = p.ink, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        CheckCircle(selected)
    }
}

private val PreviewShape = RoundedRectangle(14.dp)
private val PreviewInnerShape = RoundedRectangle(10.dp)

/** A tiny Portland: a title bar and a two-column masonry of cards on the canvas of each look. */
private fun DrawScope.drawAppPreview(dark: Boolean) {
    val canvas = if (dark) Color.Black else Color(0xFFF7F7F4)
    val card = if (dark) Color(0xFF2C2C2E) else Color(0xFFE4E3DE)
    val ink = if (dark) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.8f)
    drawRect(canvas)
    val pad = size.width * 0.12f
    val gap = size.width * 0.06f
    val r = CornerRadius(size.width * 0.06f)
    // "Portland"
    drawRoundRect(ink, Offset(pad, size.height * 0.08f), Size(size.width * 0.46f, size.height * 0.045f), CornerRadius(size.height * 0.02f))
    val colW = (size.width - pad * 2 - gap) / 2f
    val top = size.height * 0.2f
    val heights = floatArrayOf(0.26f, 0.18f, 0.2f, 0.28f, 0.22f, 0.2f)
    for (col in 0..1) {
        var y = top
        for (i in 0 until 3) {
            val h = size.height * heights[col * 3 + i]
            drawRoundRect(card, Offset(pad + col * (colW + gap), y), Size(colW, h), r)
            y += h + gap
        }
    }
}

@Composable
private fun CheckCircle(checked: Boolean) {
    val p = LocalPalette.current
    val fill by animateFloatAsState(if (checked) 1f else 0f, Motion.snappy(), label = "check")
    Box(
        Modifier
            .size(22.dp)
            .border(1.5.dp, p.ink3.copy(alpha = 0.5f * (1f - fill.coerceIn(0f, 1f))), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .graphicsLayer {
                    val f = fill.coerceIn(0f, 1.2f)
                    alpha = fill.coerceIn(0f, 1f)
                    scaleX = 0.6f + 0.4f * f
                    scaleY = 0.6f + 0.4f * f
                }
                .clip(CircleShape)
                .background(p.ink),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = p.canvas, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun PillSwitch(checked: Boolean) {
    val p = LocalPalette.current
    val track by animateColorAsState(if (checked) p.ink else p.fill, Motion.snappy(), label = "track")
    val thumb by animateColorAsState(if (checked) p.canvas else Color.White, Motion.snappy(), label = "thumb")
    val offset by animateDpAsState(if (checked) 18.dp else 0.dp, Motion.snappy(), label = "thumbX")
    Box(
        Modifier
            .width(46.dp)
            .height(28.dp)
            .clip(Corners.pill)
            .background(track)
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .size(22.dp)
                .shadow(2.dp, CircleShape, ambientColor = Color.Black.copy(0.12f), spotColor = Color.Black.copy(0.18f))
                .clip(CircleShape)
                .background(thumb),
        )
    }
}

/** Coil's downloaded-image cache on disk (R2 copies). Pins' own files live elsewhere and are never counted. */
private suspend fun imageCacheBytes(context: Context): Long = withContext(Dispatchers.IO) {
    SingletonImageLoader.get(context).diskCache?.size ?: 0L
}

private suspend fun clearImageCache(context: Context) = withContext(Dispatchers.IO) {
    val loader = SingletonImageLoader.get(context)
    loader.diskCache?.clear()
    withContext(Dispatchers.Main) { loader.memoryCache?.clear() }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
