package app.portland

import android.app.Application
import android.content.Context
import app.portland.data.Captions
import app.portland.data.Prefs
import app.portland.data.R2
import app.portland.data.Reminders
import app.portland.data.Store
import app.portland.data.Sync
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.request.maxBitmapSize
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

class App : Application(), SingletonImageLoader.Factory {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        Reminders.warm(this) // reads the reminder prefs on a background thread before the UI asks for them
        // Warm ImageLoader / DiskCache / OkHttp off the main thread, and defer housekeeping until first frames settle.
        store.launch {
            withContext(Dispatchers.IO) {
                Prefs.warm(this@App) // theme and haptics: read before the first frame asks for them
                SingletonImageLoader.get(this@App)
            }
            delay(STARTUP_GRACE_MS)
            Sync.schedule(this@App) // pulls anything added from another install
            Captions.schedule(this@App) // finishes descriptions interrupted by a process death
            Reminders.ensureScheduled(this@App) // arms the randomized daytime reminder chain
            indexColors() // colour-tags pins saved before colour search existed
        }
    }

    // One loader for the masonry grid and the full-screen pager.
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { R2.http })) }
        // Bounded decoded-bitmap cache: a share of the app's heap class, not of the whole device.
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        // Explicit, so R2 downloads survive restarts without growing forever (the OS may still trim cacheDir).
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("image_cache").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
        // Cap any single decode (default 4096²≈64 MB), well under Canvas's "too large bitmap" limit. Zoom uses tiles.
        .maxBitmapSize(Size(3072, 3072))
        .crossfade(160)
        .build()

    private companion object {
        const val STARTUP_GRACE_MS = 1500L
    }
}

val Context.store: Store get() = (applicationContext as App).store
