package app.portland

import android.app.Application
import android.content.Context
import app.portland.data.Captions
import app.portland.data.R2
import app.portland.data.Store
import app.portland.data.Sync
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class App : Application(), SingletonImageLoader.Factory {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        Sync.schedule(this) // pulls anything added from another install
        Captions.schedule(this) // finishes descriptions interrupted by a process death
    }

    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { R2.http })) }
        .crossfade(160)
        .build()
}

val Context.store: Store get() = (applicationContext as App).store
