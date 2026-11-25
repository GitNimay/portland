package app.portland.data

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import app.portland.BuildConfig
import app.portland.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A build CI published to R2 (releases/latest.json). */
data class Release(val versionCode: Int, val versionName: String, val apk: String, val sha256: String) {
    companion object {
        fun parse(json: String) = JSONObject(json).run {
            Release(getInt("versionCode"), getString("versionName"), getString("apk"), getString("sha256").lowercase())
        }
    }
}

/**
 * Self-updates from CI builds in R2: download in the background, verify, then install silently
 * (Android 12+ lets an app update itself without a prompt once "Install unknown apps" is allowed).
 */
object Updater {
    // Debug builds are signed with a different key, so a CI build could never replace them.
    private val enabled = !BuildConfig.DEBUG && R2.enabled
    private val lock = Mutex()
    private val _ready = MutableStateFlow<Release?>(null)

    /** A newer build that is downloaded, verified and waiting to install. */
    val ready: StateFlow<Release?> = _ready

    @Volatile var foreground = false

    /** The user asked to update but first had to allow "Install unknown apps"; resume when they return. */
    @Volatile var awaitingPermission = false

    suspend fun check(context: Context) {
        if (!enabled) return
        lock.withLock {
            try {
                val release = R2.get("releases/latest.json")?.let { Release.parse(it.decodeToString()) } ?: return
                if (release.versionCode <= BuildConfig.VERSION_CODE || _ready.value == release) return
                val apk = apkFile(context)
                withContext(Dispatchers.IO) {
                    if (!(apk.exists() && sha256(apk) == release.sha256)) download(release, apk)
                }
                _ready.value = release
            } catch (e: Exception) {
                Log.w("Updater", "update check failed", e)
            }
        }
    }

    /**
     * Installs the ready build. [interactive] = the user asked for it, so we may open the one-time
     * "Install unknown apps" setting; otherwise (app in background) we only install if it can be silent.
     */
    suspend fun install(context: Context, interactive: Boolean) {
        val release = _ready.value ?: return
        relaunchMarker(context).apply { if (interactive) createNewFile() else delete() }
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            if (interactive) {
                awaitingPermission = true
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                // From an Activity, Back returns to Portland instead of the Settings app.
                if (context !is Activity) settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settings)
            }
            return
        }
        withContext(Dispatchers.IO) {
            val apk = apkFile(context)
            if (sha256(apk) != release.sha256) throw IOException("update apk changed on disk")
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("portland.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val status = PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, InstallStatusReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the installer fills in the status extras
                )
                session.commit(status.intentSender)
            }
        }
    }

    private fun apkFile(context: Context) = File(context.cacheDir, "update.apk")
    internal fun relaunchMarker(context: Context) = File(context.filesDir, "relaunch-after-update")

    private fun download(release: Release, into: File) {
        val tmp = File(into.path + ".part")
        R2.http.newCall(Request.Builder().url(R2.url(release.apk)).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("download ${r.code}")
            r.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        }
        if (sha256(tmp) != release.sha256) {
            tmp.delete()
            throw IOException("checksum mismatch for ${release.versionName}")
        }
        if (!tmp.renameTo(into)) throw IOException("could not move update into place")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Receives the install result; also handles the first update, which Android may still want confirmed. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null && Updater.foreground) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // this process is replaced by the new version
            else -> Log.w("Updater", "install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

/** Brings Portland back after it replaced itself, so updating while in use feels like a quick refresh. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only when the user tapped Restart; a silent update while they are elsewhere must not pop the app up.
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || !Updater.relaunchMarker(context).delete()) return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.i("Updater", "relaunch not allowed from background", it) }
    }
}
