package app.portland.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.portland.data.Reminders

/** App settings sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val snap by Reminders.state(context).collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(Reminders.hasNotificationPermission(context)) }
    var systemEnabled by remember { mutableStateOf(Reminders.notificationsEnabledInSystem(context)) }
    var askSettings by remember { mutableStateOf(false) }

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

    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        systemEnabled = Reminders.notificationsEnabledInSystem(context)
        Reminders.onPermissionResult(context, granted)
        if (granted) {
            askSettings = false
            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
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
            haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
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
        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
        Reminders.setEnabled(context, true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = frostedSheet(),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = Corners.sheetRadius, topEnd = Corners.sheetRadius),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 36.dp, height = 5.dp, color = p.ink3.copy(alpha = 0.5f)) },
    ) {
        FrostBehind()
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(
                "Settings",
                style = Type.headline,
                color = p.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .glass(null, Corners.card)
                    .clip(Corners.card)
                    .clickable(role = Role.Switch, onClickLabel = "Notifications", onClick = ::toggleNotifications)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Notifications",
                    style = Type.body.copy(fontWeight = FontWeight.Medium),
                    color = p.ink,
                    modifier = Modifier.weight(1f),
                )
                PillSwitch(checked = notificationsActive)
            }

            if (!hasPermission || !systemEnabled) {
                Text(
                    "Off in Android settings · Open",
                    style = Type.footnote,
                    color = p.ink3,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 12.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = { openNotificationSettings(context) },
                        ),
                )
            }
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
                .offset(x = offset)
                .size(22.dp)
                .shadow(2.dp, CircleShape, ambientColor = Color.Black.copy(0.12f), spotColor = Color.Black.copy(0.18f))
                .clip(CircleShape)
                .background(thumb),
        )
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
