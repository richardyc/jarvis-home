package com.avera.jarvis.tools

import com.avera.jarvis.Tool
import com.avera.jarvis.ToolHost
import com.avera.jarvis.Tools
import org.json.JSONObject

object VolumeTool : Tool {
    override val name = "set_volume"
    override val description =
        "Change the speaker volume. Use 'direction' for relative changes ('up'/'down'/'mute'/'max') " +
        "or 'level' (0-100) for an absolute level."
    override val parameters = Tools.objectOf(
        "direction" to Tools.string("relative volume change", listOf("up", "down", "mute", "max")),
        "level" to Tools.integer("absolute volume 0-100"),
    )
    override val fast = true

    override fun run(args: JSONObject, host: ToolHost): String {
        val cur = host.volume()
        val target = when {
            args.has("level") -> args.optInt("level").coerceIn(0, 100)
            args.optString("direction") == "max" -> 100
            args.optString("direction") == "mute" -> 0
            args.optString("direction") == "down" -> cur - 20
            else /* up */ -> cur + 20
        }.coerceIn(0, 100)
        host.setVolume(target)
        return "Volume set to $target%. Briefly confirm."
    }
}

object EndTool : Tool {
    override val name = "end_conversation"
    override val description =
        "End the conversation and stop listening. Call ONLY when the user is clearly finished: " +
        "'that's all', 'goodbye', 'thanks, bye', 'never mind', '没事了'. A bare 'stop' is an " +
        "INTERRUPTION (they have more to say) — never end the conversation on 'stop'."
    override val fast = true

    override fun run(args: JSONObject, host: ToolHost): String? {
        TimerManager.dismissRing()   // "stop" with a ringing timer means the timer too
        host.endSession()
        return null   // the session is closing; there is no one to answer
    }
}
