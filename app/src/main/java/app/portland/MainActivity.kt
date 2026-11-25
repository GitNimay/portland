package app.portland

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.portland.data.Links
import app.portland.data.Updater
import app.portland.ui.AppRoot
import app.portland.ui.PortlandTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) saveShared(intent) // a recreated activity re-delivers the same intent
        setContent { PortlandTheme { AppRoot() } }
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
        if (Updater.awaitingPermission && packageManager.canRequestPackageInstalls()) {
            Updater.awaitingPermission = false
            store.launch { Updater.install(this@MainActivity) }
        }
    }

    override fun onStop() {
        super.onStop()
        Updater.foreground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveShared(intent)
    }

    /** "Share to Portland" from any app. */
    private fun saveShared(intent: Intent) {
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
