package app.portland

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.portland.data.Links
import app.portland.data.Reminders
import app.portland.data.Updater
import app.portland.ui.AppRoot
import app.portland.ui.IntroHost
import app.portland.ui.PortlandTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class MainActivity : ComponentActivity() {
    private val pendingPinId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            saveShared(intent) // a recreated activity re-delivers the same intent
            handleReminderIntent(intent)
        }
        // The intro plays once per process, on a launch from the launcher; not for shares, notifications, rotations or warm returns.
        val play = savedInstanceState == null && !introPlayed && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        introPlayed = true
        // Cut the system splash instead of letting it fade: the intro's first frame is the same picture.
        if (play && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setOnExitAnimationListener { it.remove() }
        setContent {
            PortlandTheme {
                IntroHost(play) {
                    AppRoot(
                        openPinId = pendingPinId.value,
                        onPinOpened = { pendingPinId.value = null },
                    )
                }
            }
        }
        // Look for a newer CI build on every return to the app, and every 15 minutes while it stays open.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    Updater.check(applicationContext)
                    delay(15.minutes)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Updater.foreground = true
        Reminders.recordAppActive(applicationContext)
        if (Updater.awaitingPermission && packageManager.canRequestPackageInstalls()) {
            Updater.awaitingPermission = false
            store.launch { Updater.install(this@MainActivity) }
        }
    }

    override fun onStop() {
        super.onStop()
        Updater.foreground = false
        // The quiet hour after a visit counts from when the user left, not when they arrived (throttled, off-main).
        Reminders.recordAppActive(applicationContext, force = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveShared(intent)
        handleReminderIntent(intent)
    }

    private fun handleReminderIntent(intent: Intent) {
        if (intent.action != Reminders.ACTION_OPEN_PIN) return
        val pinId = intent.getStringExtra(Reminders.EXTRA_PIN_ID) ?: return
        intent.removeExtra(Reminders.EXTRA_PIN_ID)
        Reminders.recordOpened(applicationContext, pinId)
        pendingPinId.value = pinId
    }

    /** "Share to Portland" from any app. */
    private fun saveShared(intent: Intent) {
        try {
            handleShared(intent)
        } catch (e: RuntimeException) { // a malformed share from another app (bad parcelable, revoked URI) must not kill us
            Log.w("Main", "share ignored", e)
        }
    }

    private fun handleShared(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val url = intent.getStringExtra(Intent.EXTRA_TEXT)?.let(Links::find)
            if (url != null) Links.enqueue(this, url, null)
            else Toast.makeText(this, "No link to save in what was shared", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        store.launch { add(uris, null) }
    }
}

/** Survives activity recreation but not the process: the intro is a cold-start treat, not an every-return toll. */
private var introPlayed = false
