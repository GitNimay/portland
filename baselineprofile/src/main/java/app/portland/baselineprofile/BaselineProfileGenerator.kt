package app.portland.baselineprofile

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the paths a person actually takes: cold start (with the intro), the feed scrolling, a pin opening and
 * paging in the viewer, the Settings sheet, and the Boards tab. Run against the local-only profiling install
 * (`app.portland.bp`, see app/build.gradle.kts):
 *
 *   ./gradlew :app:generateReleaseBaselineProfile -Pabis=x86_64
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val targetApp: String =
        InstrumentationRegistry.getArguments().getString("targetAppId") ?: "app.portland.bp" // the profiling install (app/build.gradle.kts)

    @Test
    fun generate() {
        seedPins()
        rule.collect(packageName = targetApp, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.desc("Settings")), 10_000)
            device.waitForIdle()

            // The feed: fling down and back, as when browsing.
            device.findObject(By.scrollable(true))?.let { grid ->
                grid.setGestureMargin(device.displayWidth / 5)
                grid.fling(Direction.DOWN)
                device.waitForIdle()
                grid.fling(Direction.UP)
                device.waitForIdle()
            }

            // Open a pin, page through a few, and go back.
            device.click(device.displayWidth / 4, device.displayHeight * 2 / 5)
            device.waitForIdle()
            Thread.sleep(800)
            repeat(3) {
                device.swipe(device.displayWidth * 4 / 5, device.displayHeight / 2, device.displayWidth / 5, device.displayHeight / 2, 12)
                device.waitForIdle()
            }
            device.pressBack()
            device.waitForIdle()
            Thread.sleep(600)

            // Settings: the frosted sheet and its rows.
            device.findObject(By.desc("Settings"))?.click()
            device.waitForIdle()
            Thread.sleep(800)
            device.pressBack()
            device.waitForIdle()

            // Boards.
            device.findObject(By.desc("Boards"))?.click()
            device.waitForIdle()
            Thread.sleep(500)
        }
    }

    /**
     * Gives the empty profiling install something to show: a dozen generated pictures of varied shapes, shared to
     * it as a user would share from the gallery. Idempotent enough: a re-run only adds more pins to a throwaway app.
     */
    private fun seedPins() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val resolver = context.contentResolver
        val uris = ArrayList<Uri>()
        val sizes = listOf(800 to 1000, 900 to 600, 700 to 1200, 1000 to 1000, 800 to 600, 600 to 900)
        repeat(12) { i ->
            val (w, h) = sizes[i % sizes.size]
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val hue = (i * 31f) % 360f
            val paint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.95f)),
                    android.graphics.Color.HSVToColor(floatArrayOf((hue + 60f) % 360f, 0.6f, 0.6f)),
                    Shader.TileMode.CLAMP,
                )
            }
            Canvas(bitmap).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "portland-profile-$i.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PortlandProfile")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@repeat
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            uris += uri
        }
        if (uris.isEmpty()) return
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            setPackage(targetApp)
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(share)
        Thread.sleep(8_000) // imports and thumbnails run in the background
    }
}
