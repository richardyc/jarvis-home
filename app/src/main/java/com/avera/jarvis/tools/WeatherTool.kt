package com.avera.jarvis.tools

import com.avera.jarvis.Tool
import com.avera.jarvis.ToolHost
import com.avera.jarvis.Tools
import com.avera.jarvis.Weather
import com.avera.jarvis.WeatherCard
import org.json.JSONObject

object WeatherTool : Tool {
    override val name = "get_weather"
    override val description =
        "Get current weather + forecast for a place AND show it on the display. " +
        "Call whenever the user asks about weather."
    override val parameters = Tools.objectOf(
        "location" to Tools.string(
            "City, with state or country to disambiguate when helpful, e.g. 'San Francisco, California'"),
        required = listOf("location")
    )

    override fun run(args: JSONObject, host: ToolHost): String {
        val loc = args.optString("location").ifEmpty { host.homeCity }
        val d = Weather.fetch(host.http, loc)
            ?: return "Couldn't find weather for '$loc'. Ask the user to clarify the place."
        host.showCard(WeatherCard(d))
        host.status("Jarvis is speaking…")
        return "Weather card is now on screen for ${d.location}: ${d.currentTemp}°F, ${d.description}, " +
            "today ${d.days.firstOrNull()?.hi}/${d.days.firstOrNull()?.lo}. Give a short, natural spoken summary."
    }
}
