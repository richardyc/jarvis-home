package com.avera.jarvis

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * The plugin system. One [Tool] object per capability, self-contained in its own file under
 * tools/ — schema (what the model sees), execution (what happens), and any card it puts on
 * screen. Adding a capability = write one file, list it in [Tools.all]. Nothing else changes.
 */
interface Tool {
    /** Function name the model calls. */
    val name: String
    /** When the model should call it — this is a prompt, write it like one. */
    val description: String
    /** JSON-Schema parameters object, or null for no-arg tools. */
    val parameters: JSONObject? get() = null
    /** true → runs inline on the socket thread. Only for instant, no-I/O tools. */
    val fast: Boolean get() = false

    /**
     * Execute (off the main thread unless [fast]) and return the function_call output the model
     * speaks from — write it as instructions ("Card is on screen. Give a short summary.").
     * Return null to send nothing (e.g. the session is ending).
     */
    fun run(args: JSONObject, host: ToolHost): String?
}

/** Everything a tool may touch — tools never see RealtimeSession internals. */
interface ToolHost {
    val http: OkHttpClient
    val homeCity: String
    /** Status line under the orb ("Searching…"). */
    fun status(text: String)
    /** Put a widget card on the display (null clears). */
    fun showCard(card: Card?)
    fun volume(): Int
    fun setVolume(pct: Int)
    /** Wind down the conversation and return to the wake word. */
    fun endSession()
}

/**
 * A widget on the display. Implementations live next to the tool that produces them; PanelUi
 * owns one renderer per type. Not sealed so tool files (tools/ package) can add their own.
 */
interface Card
data class WeatherCard(val data: WeatherData) : Card

object Tools {
    /** Every capability Jarvis has. Order = order in the model's tool list. */
    val all: List<Tool> = listOf(
        com.avera.jarvis.tools.WeatherTool,
        com.avera.jarvis.tools.SearchTool,
        com.avera.jarvis.tools.GuideTool,
        com.avera.jarvis.tools.ScoresTool,
        com.avera.jarvis.tools.TimerTool,
        com.avera.jarvis.tools.MemoryTool,
        com.avera.jarvis.tools.VolumeTool,
        com.avera.jarvis.tools.EndTool,
    )

    fun byName(name: String): Tool? = all.firstOrNull { it.name == name }

    /** The session.update "tools" array. */
    fun schema(): JSONArray = JSONArray().apply {
        for (t in all) put(JSONObject().apply {
            put("type", "function")
            put("name", t.name)
            put("description", t.description)
            t.parameters?.let { put("parameters", it) }
        })
    }

    /* -- tiny JSON-Schema builders so tool files stay readable -- */

    fun objectOf(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { for ((k, v) in props) put(k, v) })
            if (required.isNotEmpty()) put("required", JSONArray().apply { required.forEach { put(it) } })
        }

    fun string(desc: String, enum: List<String>? = null) = JSONObject().apply {
        put("type", "string"); put("description", desc)
        enum?.let { put("enum", JSONArray().apply { it.forEach { v -> put(v) } }) }
    }

    fun integer(desc: String) = JSONObject().apply { put("type", "integer"); put("description", desc) }

    fun arrayOf(items: JSONObject, desc: String) = JSONObject().apply {
        put("type", "array"); put("description", desc); put("items", items)
    }
}
