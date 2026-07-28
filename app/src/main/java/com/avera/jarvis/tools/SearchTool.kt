package com.avera.jarvis.tools

import com.avera.jarvis.Search
import com.avera.jarvis.Tool
import com.avera.jarvis.ToolHost
import com.avera.jarvis.Tools
import org.json.JSONObject

/**
 * The Realtime API has NO built-in web search (declaring {"type":"web_search"} there silently
 * hangs). The supported pattern is a plain function tool that we service ourselves — Search.kt
 * asks the Responses API to do the actual searching.
 */
object SearchTool : Tool {
    override val name = "web_search"
    override val description =
        "Search the web for current information: news, prices, opening hours, facts you don't " +
        "know or that may have changed. Say a short phrase like 'One moment.' in the same turn " +
        "you call this."
    override val parameters = Tools.objectOf(
        "query" to Tools.string("What to search for"),
        required = listOf("query")
    )

    override fun run(args: JSONObject, host: ToolHost): String {
        val q = args.optString("query")
        host.status("Searching…")
        val answer = Search.run(host.http, q)
        host.status("Jarvis is speaking…")
        return answer
    }
}
