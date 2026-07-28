package com.avera.jarvis

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class DayForecast(val day: String, val code: Int, val hi: Int, val lo: Int)
data class WeatherData(
    val location: String,
    val currentTemp: Int,
    val icon: String,
    val description: String,
    val days: List<DayForecast>,
    val curve: List<Pair<String, Int>>   // (hour label, temp)
)

/** Free weather via Open-Meteo — no API key, no signup. Runs on-device. */
object Weather {
    fun fetch(client: OkHttpClient, location: String): WeatherData? { return try {
        // 1) geocode name -> lat/lon, disambiguating: match a region hint after a comma
        //    ("San Francisco, California"), else prefer a US match (this is a US home device).
        val cityQuery = location.substringBefore(",").trim().ifEmpty { location }
        val hint = location.substringAfter(",", "").trim().lowercase()
        val geo = JSONObject(
            get(client, "https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&name=" +
                java.net.URLEncoder.encode(cityQuery, "UTF-8").replace("+", "%20"))   // %20 not '+' for Open-Meteo
        )
        val results = geo.optJSONArray("results")
        if (results == null || results.length() == 0) return null
        // Score each candidate: exact name match, region/country hint, else US, then break ties by
        // population. This avoids picking a small same-region town over the city the user meant.
        var place = results.getJSONObject(0); var bestScore = -1.0
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            var score = 0.0
            if (r.optString("name").equals(cityQuery, ignoreCase = true)) score += 1000.0
            if (hint.isNotEmpty() && (
                    r.optString("admin1").lowercase().contains(hint) ||
                    r.optString("country").lowercase().contains(hint) ||
                    r.optString("country_code").equals(hint, ignoreCase = true))) score += 500.0
            else if (hint.isEmpty() && r.optString("country_code") == "US") score += 200.0
            score += r.optDouble("population", 0.0) / 1_000_000.0   // tiebreak by size
            if (score > bestScore) { bestScore = score; place = r }
        }
        val lat = place.getDouble("latitude"); val lon = place.getDouble("longitude")
        val name = place.optString("name") +
            (place.optString("admin1").takeIf { it.isNotEmpty() }?.let { ", $it" } ?: "")

        // 2) forecast
        val fc = JSONObject(get(client,
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&hourly=temperature_2m&temperature_unit=fahrenheit&timezone=auto&forecast_days=7"))

        val cur = fc.getJSONObject("current")
        // clamp: guards against a corrupted/truncated response (seen once as a 9-digit temp)
        val curTemp = cur.getDouble("temperature_2m").toInt().coerceIn(-100, 150)
        val curCode = cur.getInt("weather_code")

        val daily = fc.getJSONObject("daily")
        val dts = daily.getJSONArray("time")
        val dcode = daily.getJSONArray("weather_code")
        val dmax = daily.getJSONArray("temperature_2m_max")
        val dmin = daily.getJSONArray("temperature_2m_min")
        val dayFmt = SimpleDateFormat("EEE", Locale.US)
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val days = ArrayList<DayForecast>()
        for (i in 0 until minOf(7, dts.length())) {
            val label = if (i == 0) "Today" else dayFmt.format(parseFmt.parse(dts.getString(i))!!)
            days.add(DayForecast(label, dcode.getInt(i), dmax.getDouble(i).toInt(), dmin.getDouble(i).toInt()))
        }

        // 3) hourly curve: 8 points, every 3h from the current hour
        val htemp = fc.getJSONObject("hourly").getJSONArray("temperature_2m")
        val start = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val curve = ArrayList<Pair<String, Int>>()
        var h = start; var n = 0
        while (n < 8 && h < htemp.length()) {
            val hod = h % 24
            val label = when { hod == 0 -> "12a"; hod < 12 -> "${hod}a"; hod == 12 -> "12p"; else -> "${hod - 12}p" }
            curve.add(label to htemp.getDouble(h).toInt())
            h += 3; n++
        }
        WeatherData(name, curTemp, icon(curCode), desc(curCode), days, curve)
    } catch (e: Exception) { null } }

    private fun get(client: OkHttpClient, url: String): String {
        client.newCall(Request.Builder().url(url).build()).execute().use {
            return it.body?.string() ?: throw Exception("empty body")
        }
    }

    fun icon(c: Int): String = when (c) {
        0 -> "☀️"; 1, 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"
        in 51..57 -> "🌦️"; in 61..67 -> "🌧️"; in 71..77 -> "🌨️"
        in 80..82 -> "🌧️"; 85, 86 -> "🌨️"; in 95..99 -> "⛈️"; else -> "🌡️"
    }
    fun desc(c: Int): String = when (c) {
        0 -> "clear"; 1 -> "mainly clear"; 2 -> "partly cloudy"; 3 -> "overcast"
        45, 48 -> "foggy"; in 51..57 -> "drizzly"; in 61..67 -> "rainy"; in 71..77 -> "snowy"
        in 80..82 -> "showers"; 85, 86 -> "snow showers"; in 95..99 -> "thunderstorms"; else -> "mild"
    }
}
