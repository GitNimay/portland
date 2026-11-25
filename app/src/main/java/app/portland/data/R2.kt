package app.portland.data

import okhttp3.OkHttpClient
import java.io.File

// STUB: replaced by the real SigV4 client.
object R2 {
    val enabled: Boolean = false
    val http: OkHttpClient by lazy { OkHttpClient() }
    fun url(key: String): String = "https://r2.invalid/$key"
    suspend fun put(key: String, file: File, contentType: String) {}
    suspend fun putBytes(key: String, bytes: ByteArray, contentType: String) {}
    suspend fun get(key: String): ByteArray? = null
    suspend fun delete(key: String) {}
}
