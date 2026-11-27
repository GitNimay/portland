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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
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
import app.portland.data.Cloud
import app.portland.data.Prefs
import app.portland.data.Reminders
import app.portland.data.ThemeMode
import app.portland.store
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
    val cloud by Cloud.state.collectAsStateWithLifecycle()
    var setupOpen by remember { mutableStateOf(false) }
    var showBucket by remember { mutableStateOf(false) }
    var leaveCloud by remember { mutableStateOf<Int?>(null) } // pins to download before leaving R2, while asking
    var moveFailed by remember { mutableStateOf<Int?>(null) }

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
        SyncWindowBars() // the theme can change while this sheet is open
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

            SettingsGroup(header = "Appearance") {
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
                    checked = prefs.reduceTransparency,
                    onClick = ::toggleReduceTransparency,
                ) { PillSwitch(checked = prefs.reduceTransparency) }
            }

            SettingsGroup {
                SettingsRow(
                    icon = R.drawable.ic_bell,
                    tile = if (p.dark) Color(0xFFFF453A) else Color(0xFFFF3B30),
                    title = "Notifications",
                    checked = notificationsActive,
                    onClick = ::toggleNotifications,
                ) { PillSwitch(checked = notificationsActive) }
                RowDivider()
                SettingsRow(
                    icon = R.drawable.ic_vibrate,
                    tile = if (p.dark) Color(0xFFFF375F) else Color(0xFFFF2D55),
                    title = "Haptics",
                    checked = prefs.haptics,
                    onClick = ::toggleHaptics,
                ) { PillSwitch(checked = prefs.haptics) }
            }
            if (!hasPermission || !systemEnabled) {
                Text(
                    "Off in Android settings · Open",
                    style = Type.footnote,
                    color = p.ink3,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = { openNotificationSettings(context) },
                        )
                        // A full-height touch target around one line of footnote.
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }

            SettingsGroup(header = "Storage") {
                val green = settingsGreen()
                SettingsRow(
                    icon = R.drawable.ic_phone,
                    tile = if (p.dark) Color(0xFF0A84FF) else Color(0xFF007AFF),
                    title = "This phone",
                    selected = !cloud.connected,
                    onClick = {
                        if (cloud.connected && cloud.moving == null) {
                            haptics.play(Fx.Tick)
                            scope.launch { leaveCloud = Cloud.remoteOnly(context).size }
                        }
                    },
                ) {
                    val moving = cloud.moving
                    if (moving != null) {
                        Text("${moving.done} of ${moving.total}", style = Type.body.digits(), color = p.ink3)
                    } else {
                        CheckCircle(!cloud.connected, green)
                    }
                }
                RowDivider()
                SettingsRow(
                    icon = R.drawable.ic_cloud,
                    tile = CloudflareOrange,
                    title = "Cloudflare R2",
                    selected = cloud.connected,
                    onClick = {
                        haptics.play(Fx.Tick)
                        if (cloud.connected) showBucket = true else setupOpen = true
                    },
                ) { CheckCircle(cloud.connected, green) }
                if (cloud.connected) {
                    RowDivider()
                    SettingsRow(
                        icon = R.drawable.ic_download,
                        tile = settingsGreen(),
                        title = "Keep copies on this phone",
                        checked = cloud.keepLocal,
                        onClick = {
                            haptics.play(if (cloud.keepLocal) Fx.ToggleOff else Fx.ToggleOn)
                            Cloud.setKeepLocal(context, !cloud.keepLocal)
                        },
                    ) { PillSwitch(checked = cloud.keepLocal) }
                }
                RowDivider()
                SettingsRow(
                    icon = R.drawable.ic_storage,
                    tile = Color(0xFF8E8E93),
                    title = "Image cache",
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
            body = "Images download again when needed.",
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

    if (setupOpen) CloudSetupSheet(onDismiss = { setupOpen = false })

    leaveCloud?.let { count ->
        PortlandDialog(
            title = "Use this phone only?",
            body = if (count > 0) "$count ${if (count == 1) "pin downloads" else "pins download"} to this phone first. Your bucket stays as it is."
            else "Your bucket stays as it is.",
            confirmLabel = "Switch",
            onConfirm = {
                leaveCloud = null
                // On the store's scope: the download carries on if Settings closes.
                context.store.launch {
                    val failed = Cloud.useThisPhoneOnly(context)
                    if (failed > 0) moveFailed = failed
                }
            },
            onDismiss = { leaveCloud = null },
        )
    }

    moveFailed?.let { failed ->
        PortlandDialog(
            title = "$failed ${if (failed == 1) "pin" else "pins"} didn't download",
            body = "Try again when you're online, or switch anyway and leave them in R2.",
            confirmLabel = "Switch anyway",
            destructive = true,
            onConfirm = {
                moveFailed = null
                context.store.launch { Cloud.useThisPhoneOnly(context, force = true) }
            },
            onDismiss = { moveFailed = null },
        )
    }

    if (showBucket) {
        val config = cloud.config
        PortlandDialog(
            title = "Cloudflare R2",
            body = config?.let { "Bucket “${it.bucket}” in account …${it.accountId.takeLast(6)}." },
            confirmLabel = "Change",
            dismissLabel = "Close",
            onConfirm = {
                showBucket = false
                setupOpen = true
            },
            onDismiss = { showBucket = false },
        )
    }
}

/** Apple's system green: switches that are on, the chosen option. */
@Composable
internal fun settingsGreen(): Color = if (LocalPalette.current.dark) Color(0xFF30D158) else Color(0xFF34C759)

/** A titled, inset-grouped card of rows, as in iOS Settings. */
@Composable
internal fun SettingsGroup(
    header: String? = null,
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
}

/**
 * One row: a coloured icon tile, a title, and [trailing] (a switch, a value). The whole row is the touch target
 * and greys under the finger, as iOS rows do. With [checked] it is a switch: screen readers hear "on" or "off".
 * With [selected] it is one of a set of options (a radio button).
 */
@Composable
internal fun SettingsRow(
    @DrawableRes icon: Int,
    tile: Color,
    title: String,
    onClick: () -> Unit,
    checked: Boolean? = null,
    selected: Boolean? = null,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val wash by animateFloatAsState(if (pressed) 1f else 0f, Motion.press(), label = "rowPress")
    val action = if (checked != null) {
        Modifier.toggleable(value = checked, interactionSource = press, indication = null, enabled = enabled, role = Role.Switch) { onClick() }
    } else if (selected != null) {
        Modifier.selectable(selected = selected, interactionSource = press, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.clickable(interactionSource = press, indication = null, enabled = enabled, role = Role.Button, onClickLabel = title, onClick = onClick)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .drawBehind {
                val w = wash.coerceIn(0f, 1f)
                if (w > 0f) drawRect(p.fill, alpha = w)
            }
            .then(action)
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
internal fun RowDivider(inset: Dp = 58.dp) {
    val p = LocalPalette.current
    Box(
        Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(p.line),
    )
}

/** System / Light / Dark: a small Android phone showing Portland in each look, a label and a check under each. */
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
    val green = settingsGreen()
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, Motion.press(), label = "themePress")
    val ring by animateFloatAsState(if (selected) 1f else 0f, Motion.snappy(), label = "themeRing")
    Column(
        Modifier
            .widthIn(max = 88.dp)
            .selectable(selected = selected, interactionSource = press, indication = null, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .width(70.dp)
                .aspectRatio(0.5f)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        ) {
            val inset = 4.dp.toPx() // room for the selection ring around the phone
            val r = ring.coerceIn(0f, 1f)
            if (r > 0f) {
                val stroke = 2.dp.toPx()
                drawRoundRect(
                    green.copy(alpha = r),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(stroke),
                )
            }
            translate(inset, inset) {
                val phone = Size(size.width - inset * 2, size.height - inset * 2)
                when (mode) {
                    ThemeMode.Light -> drawPhone(phone, dark = false)
                    ThemeMode.Dark -> drawPhone(phone, dark = true)
                    ThemeMode.System -> {
                        clipRect(right = phone.width / 2f) { drawPhone(phone, dark = false) }
                        clipRect(left = phone.width / 2f) { drawPhone(phone, dark = true) }
                    }
                }
            }
        }
        Text(label, style = Type.footnote, color = p.ink, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        CheckCircle(selected, green)
    }
}

// Soft photo colours for the preview's pins, per look.
private val LightPhotos = listOf(Color(0xFFBFD7F2), Color(0xFFF4CDBD), Color(0xFFCFE4CC), Color(0xFFE2D7F4), Color(0xFFF5E2AE), Color(0xFFC6E2E6))
// Photo heights per column, in layout units: a staggered masonry like the real feed.
private val PhotoHeights = arrayOf(floatArrayOf(8f, 6f, 9f, 7f), floatArrayOf(6f, 9f, 6.5f, 8f))
private val DarkPhotos = listOf(Color(0xFF2B4361), Color(0xFF5A3A2E), Color(0xFF2E4A33), Color(0xFF41365C), Color(0xFF5A4C26), Color(0xFF244A50))

/**
 * A small Android phone: a dark frame, a punch-hole camera over the status bar, the Portland title, a two-column
 * masonry of photos and the gesture bar, in the canvas colours of each look.
 */
private fun DrawScope.drawPhone(phone: Size, dark: Boolean) {
    val w = phone.width
    val h = phone.height
    // Frame
    drawRoundRect(if (dark) Color(0xFF2A2A2C) else Color(0xFF1C1C1E), size = phone, cornerRadius = CornerRadius(w * 0.2f))
    // Screen
    val bezel = w * 0.045f
    val sw = w - bezel * 2
    val sh = h - bezel * 2
    translate(bezel, bezel) {
        drawRoundRect(if (dark) Color.Black else Color(0xFFF7F7F4), size = Size(sw, sh), cornerRadius = CornerRadius(sw * 0.17f))
        val ink = if (dark) Color.White else Color.Black
        val u = sw / 20f // layout unit
        // Status bar: time on the left, signal and battery on the right, the camera hole in the middle.
        val statusY = u * 1.6f
        drawRoundRect(ink.copy(alpha = 0.7f), Offset(u * 2f, statusY - u * 0.35f), Size(u * 2.6f, u * 0.7f), CornerRadius(u * 0.35f))
        drawRoundRect(ink.copy(alpha = 0.7f), Offset(sw - u * 4.6f, statusY - u * 0.35f), Size(u * 2.6f, u * 0.7f), CornerRadius(u * 0.35f))
        drawCircle(Color.Black, radius = u * 0.75f, center = Offset(sw / 2f, statusY))
        if (dark) drawCircle(Color(0xFF2A2A2C), radius = u * 0.75f, center = Offset(sw / 2f, statusY), style = Stroke(u * 0.2f))
        // "Portland"
        drawRoundRect(ink.copy(alpha = 0.85f), Offset(u * 2f, u * 3.6f), Size(u * 8.5f, u * 1.3f), CornerRadius(u * 0.65f))
        // Masonry of photos
        val photos = if (dark) DarkPhotos else LightPhotos
        val gap = u * 0.9f
        val colW = (sw - u * 4f - gap) / 2f
        val heights = PhotoHeights
        val bottom = sh - u * 3f
        for (col in 0..1) {
            var y = u * 6.2f
            for (i in heights[col].indices) {
                if (y >= bottom - u) break // the feed runs on under the gesture bar, like a real one
                val ph = minOf(u * heights[col][i], bottom - y)
                drawRoundRect(photos[(col * 3 + i) % photos.size], Offset(u * 2f + col * (colW + gap), y), Size(colW, ph), CornerRadius(u * 1.1f))
                y += ph + gap
            }
        }
        // Gesture navigation bar
        drawRoundRect(ink.copy(alpha = 0.6f), Offset(sw / 2f - u * 3.5f, sh - u * 1.6f), Size(u * 7f, u * 0.5f), CornerRadius(u * 0.25f))
    }
}

@Composable
internal fun CheckCircle(checked: Boolean, green: Color) {
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
                .background(green),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

/** iOS-style switch: green when on, a white thumb either way. */
@Composable
internal fun PillSwitch(checked: Boolean) {
    val p = LocalPalette.current
    val off = if (p.dark) Color(0xFF39393D) else Color(0xFFE9E9EB)
    val track by animateColorAsState(if (checked) settingsGreen() else off, Motion.snappy(), label = "track")
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
                .background(Color.White),
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
