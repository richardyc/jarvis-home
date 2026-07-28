package com.avera.jarvis

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The idle screen's weather: refreshed every ~30 minutes for wherever the panel actually is.
 * No GPS on this board — IP geolocation (city-level is plenty for weather), config home_city
 * as the fallback when the lookup fails.
 */
object Ambient {
    var weather by mutableStateOf<WeatherData?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cachedCity: String? = null

    /** Start the refresh loop; safe to call once from onCreate. */
    fun start(http: OkHttpClient, fallbackCity: String) {
        Thread {
            while (true) {
                runCatching {
                    val city = cachedCity ?: ipCity(http)?.also { cachedCity = it } ?: fallbackCity
                    val d = Weather.fetch(http, city)
                    if (d != null) main.post { weather = d }
                }
                try { Thread.sleep(30 * 60_000L) } catch (_: Exception) { return@Thread }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** "San Francisco, California" from the panel's public IP (ipapi.co — https, free tier). */
    private fun ipCity(http: OkHttpClient): String? = runCatching {
        val body = http.newCall(
            Request.Builder().url("https://ipapi.co/json/")
                .header("User-Agent", "JarvisPanel/1.0").build()
        ).execute().use { it.body?.string().orEmpty() }
        val o = JSONObject(body)
        val city = o.optString("city")
        val region = o.optString("region")
        if (city.isEmpty()) null
        else if (region.isEmpty()) city else "$city, $region"
    }.getOrNull()
}
