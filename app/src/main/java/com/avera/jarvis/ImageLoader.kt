package com.avera.jarvis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Tiny image pipeline: OkHttp fetch → downsampled Bitmap → LruCache. No Coil/Glide — neither is
 * in the offline gradle cache, and a panel showing a handful of card images doesn't need them.
 */
object ImageLoader {
    private val exec = Executors.newFixedThreadPool(3)
    // ~1/8 of the app heap; entries are counted in KB
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    @Volatile var http: OkHttpClient = OkHttpClient()

    /** Fetch (or serve cached), downsampled so the longest side ≤ [maxDim] px. */
    fun load(url: String, maxDim: Int = 800, done: (Bitmap?) -> Unit) {
        cache.get(url)?.let { done(it); return }
        exec.execute {
            val bmp = try {
                val bytes = http.newCall(
                    Request.Builder().url(url).header("User-Agent", "JarvisPanel/1.0").build()
                ).execute().use { it.body?.bytes() } ?: ByteArray(0)
                // two-pass decode: bounds first, then sampled — a 2GB-RAM panel, not a phone
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxDim) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Exception) {
                Log.w("Jarvis", "image load failed: $url (${e.message})"); null
            }
            bmp?.let { cache.put(url, it) }
            done(bmp)
        }
    }
}

/** Compose hook: null while loading (render a placeholder), then the bitmap. */
@Composable
fun rememberUrlImage(url: String, maxDim: Int = 800): ImageBitmap? {
    val state by produceState<ImageBitmap?>(initialValue = null, url) {
        ImageLoader.load(url, maxDim) { value = it?.asImageBitmap() }
    }
    return state
}
