package com.avera.jarvis.tools

import com.avera.jarvis.Memory
import com.avera.jarvis.Tool
import com.avera.jarvis.ToolHost
import com.avera.jarvis.Tools
import org.json.JSONObject

object MemoryTool : Tool {
    override val name = "remember"
    override val description =
        "Save something to long-term memory. Call when the user says 'remember …', states a " +
        "lasting preference or fact about themselves or the home, or corrects something you had " +
        "wrong. One short sentence per fact. If the fact should change your future behavior, " +
        "include when it applies (e.g. 'prefers Celsius when asked about weather'). Use scope " +
        "'today' for things that only matter today (e.g. 'guests coming at 7')."
    override val parameters = Tools.objectOf(
        "fact" to Tools.string("the fact, one short sentence"),
        "scope" to Tools.string("how long it matters", listOf("durable", "today")),
        required = listOf("fact")
    )
    override val fast = true

    override fun run(args: JSONObject, host: ToolHost): String {
        val fact = args.optString("fact").trim()
        if (fact.isEmpty()) return "Nothing to remember — the fact was empty."
        if (args.optString("scope") == "today") Memory.noteToday(fact)
        else Memory.rememberDurable(fact)
        return "Remembered. Confirm in a few words."
    }
}
