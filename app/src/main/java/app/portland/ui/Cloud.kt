package app.portland.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.portland.R
import app.portland.data.Cloud
import app.portland.data.CloudflareAccount
import app.portland.data.CloudflareApi
import app.portland.data.DEFAULT_BUCKET
import app.portland.data.R2
import app.portland.data.R2Setup
import app.portland.data.R2_DASHBOARD_URL
import app.portland.data.SetupProblem
import app.portland.data.SetupResult
import app.portland.data.TOKEN_TEMPLATE_URL
import app.portland.store
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch

/** Cloudflare's orange, for the R2 tile: the one place the brand colour shows. */
internal val CloudflareOrange = Color(0xFFF38020)

/**
 * Connect R2 in three taps: open Cloudflare's token page (permissions already filled in), copy the token, paste
 * it here. Portland finds the account, makes the bucket and proves it can write there before it says yes. People
 * who already have R2 access keys can use those instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSetupSheet(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val setup = remember { R2Setup(CloudflareApi(R2.base), R2.base) }

    var token by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf<List<CloudflareAccount>>(emptyList()) }
    var needAccountId by remember { mutableStateOf(false) }
    var useKeys by remember { mutableStateOf(false) }
    var bucket by remember { mutableStateOf(DEFAULT_BUCKET) }
    var keyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var keepLocal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<SetupProblem?>(null) }

    val ready = !busy && if (useKeys) {
        R2Setup.accountIdIn(accountId) != null && bucket.isNotBlank() && keyId.isNotBlank() && secret.isNotBlank()
    } else {
        token.isNotBlank() && (!needAccountId || R2Setup.accountIdIn(accountId) != null)
    }

    fun connect(pickedAccount: String? = null) {
        if (busy) return
        busy = true
        problem = null
        haptics.play(Fx.Tick)
        scope.launch {
            val account = pickedAccount ?: R2Setup.accountIdIn(accountId)
            val result = if (useKeys) {
                setup.withKeys(account.orEmpty(), bucket, keyId, secret)
            } else {
                setup.withToken(token, account.takeIf { pickedAccount != null || needAccountId })
            }
            when (result) {
                is SetupResult.Connected -> {
                    // Saved on the store's scope: closing the sheet must not cut the save short.
                    context.store.launch { Cloud.connect(context, result.config, keepLocal) }
                    haptics.play(Fx.Confirm)
                    onDismiss()
                }
                is SetupResult.PickAccount -> accounts = result.accounts
                SetupResult.NeedAccountId -> needAccountId = true
                is SetupResult.Failed -> {
                    problem = result.problem
                    haptics.play(Fx.Reject)
                }
            }
            busy = false
        }
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
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                .animateContentSize(Motion.smooth()),
        ) {
            Text("Cloudflare R2", style = Type.headline, color = p.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(
                "Your own bucket. Free up to 10 GB.",
                style = Type.footnote,
                color = p.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )

            if (!useKeys) {
                SettingsGroup(header = "Token") {
                    SettingsRow(
                        icon = R.drawable.ic_cloud,
                        tile = CloudflareOrange,
                        title = "Create token on Cloudflare",
                        onClick = { openUrl(context, TOKEN_TEMPLATE_URL) },
                    ) { Chevron() }
                    RowDivider()
                    Field(
                        value = token,
                        onValue = { token = it; problem = null; accounts = emptyList() },
                        placeholder = "Paste token",
                        secret = true,
                        trailing = {
                            SmallButton("Paste") {
                                scope.launch {
                                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
                                    if (text.isNotEmpty()) { token = text; problem = null; accounts = emptyList() }
                                }
                            }
                        },
                    )
                }
            } else {
                SettingsGroup(header = "Access keys") {
                    Field(bucket, { bucket = it.trim().lowercase(); problem = null }, "Bucket")
                    RowDivider(inset = 16.dp)
                    Field(keyId, { keyId = it.trim(); problem = null }, "Access key ID")
                    RowDivider(inset = 16.dp)
                    Field(secret, { secret = it.trim(); problem = null }, "Secret access key", secret = true)
                }
            }

            if (useKeys || needAccountId) {
                SettingsGroup(header = "Account ID") {
                    Field(accountId, { accountId = it; problem = null }, "Account ID or dashboard link")
                    RowDivider()
                    SettingsRow(
                        icon = R.drawable.ic_info,
                        tile = Color(0xFF8E8E93),
                        title = "Find it on Cloudflare",
                        onClick = { openUrl(context, R2_DASHBOARD_URL) },
                    ) { Chevron() }
                }
            }

            if (accounts.isNotEmpty()) {
                SettingsGroup(header = "Account") {
                    accounts.forEachIndexed { i, account ->
                        if (i > 0) RowDivider()
                        SettingsRow(
                            icon = R.drawable.ic_cloud,
                            tile = CloudflareOrange,
                            title = account.name.ifBlank { account.id },
                            onClick = { connect(account.id) },
                        ) { Chevron() }
                    }
                }
            }

            SettingsGroup {
                SettingsRow(
                    icon = R.drawable.ic_phone,
                    tile = if (p.dark) Color(0xFF0A84FF) else Color(0xFF007AFF),
                    title = "Keep copies on this phone",
                    checked = keepLocal,
                    onClick = {
                        haptics.play(if (keepLocal) Fx.ToggleOff else Fx.ToggleOn)
                        keepLocal = !keepLocal
                    },
                ) { PillSwitch(checked = keepLocal) }
            }

            problem?.let { pr ->
                Text(
                    pr.message,
                    style = Type.footnote,
                    color = p.danger,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                if (pr == SetupProblem.R2Off) {
                    TextLink("Turn on R2", Modifier.padding(start = 16.dp)) { openUrl(context, R2_DASHBOARD_URL) }
                }
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                label = if (busy) "Connecting…" else "Connect",
                enabled = ready,
                busy = busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { connect() },
            )
            TextLink(
                if (useKeys) "Use a token instead" else "Use access keys instead",
                Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
            ) {
                useKeys = !useKeys
                problem = null
            }
        }
    }
}

/**
 * The small "back up to R2" card. It takes the add button's place at the bottom (the button lifts above it by
 * the height [onHeight] reports) until the user connects, snoozes it a week (Remind me) or turns it off for good
 * (Never). Frosted glass, like the add panel, so it reads over any feed.
 */
@Composable
fun BoxScope.CloudNudge(
    visible: Boolean,
    backdrop: Backdrop,
    onSetUp: () -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit,
    onHeight: (Int) -> Unit,
) {
    val p = LocalPalette.current
    AnimatedVisibility(
        visible,
        Modifier.align(Alignment.BottomCenter),
        // Rises from below the screen edge into the add button's place (the button lifts over it on the same
        // spring), and sinks back out of sight when dismissed while the button settles home.
        enter = slideInVertically(Motion.smooth()) { it * 2 } + fadeIn(),
        exit = slideOutVertically(Motion.smooth()) { it * 2 } + fadeOut(),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp) // the add button's own margin
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .onSizeChanged { onHeight(it.height) }
                .glass(backdrop, NudgeShape, frost = 1f)
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(NudgeTile).background(CloudflareOrange), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_cloud), contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("Back up to Cloudflare R2", style = Type.footnote.copy(fontWeight = FontWeight.SemiBold), color = p.ink)
                    Text("Keep your pins safe and open them anywhere.", style = Type.footnote, color = p.ink2)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallButton("Never", quiet = true, onClick = onNever)
                SmallButton("Remind me", quiet = true, onClick = onLater)
                SmallButton("Set up", strong = true, onClick = onSetUp)
            }
        }
    }
}

private val NudgeShape = RoundedRectangle(24.dp)
private val NudgeTile = RoundedRectangle(8.dp)

/** A single-line input as a settings row: no box of its own, the group card is the field. */
@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = Type.body.copy(color = p.ink),
        cursorBrush = SolidColor(p.ink),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Row(Modifier.height(52.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = Type.body, color = p.ink3)
                    field()
                }
                trailing?.invoke()
            }
        },
    )
}

@Composable
private fun Chevron() {
    Icon(
        painterResource(R.drawable.ic_chevron_down),
        contentDescription = null,
        tint = LocalPalette.current.ink3,
        modifier = Modifier.size(IconSize.small).graphicsLayer { rotationZ = -90f },
    )
}

/** A compact pill: [strong] is filled ink (the action), [quiet] is plain text, otherwise a faint fill. */
@Composable
private fun SmallButton(label: String, strong: Boolean = false, quiet: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, Motion.press(), label = "press")
    Box(
        Modifier
            .height(34.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Corners.pill)
            .background(if (strong) p.ink else if (quiet) Color.Transparent else p.fill)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Type.footnote.copy(fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium),
            color = if (strong) p.canvas else if (quiet) p.ink2 else p.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, busy: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.press(), label = "press")
    val dim by animateFloatAsState(if (enabled || busy) 1f else 0.38f, Motion.smooth(), label = "enabled")
    Row(
        modifier
            .height(50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = dim }
            .clip(Corners.pill)
            .background(p.ink)
            .clickable(interactionSource = press, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                color = p.canvas,
                trackColor = Color.Transparent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(IconSize.small).semantics { contentDescription = "Working" },
            )
            Spacer(Modifier.size(10.dp))
        }
        Text(label, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.canvas)
    }
}

@Composable
private fun TextLink(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = Type.footnote.copy(fontWeight = FontWeight.Medium),
        color = LocalPalette.current.ink2,
        modifier = modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            // A full-height touch target around one line of footnote.
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

internal fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
