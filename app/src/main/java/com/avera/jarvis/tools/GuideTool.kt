package com.avera.jarvis.tools

import android.util.Log
import com.avera.jarvis.Card
import com.avera.jarvis.Tool
import com.avera.jarvis.ToolHost
import com.avera.jarvis.Tools
import okhttp3.Request
import org.json.JSONObject

data class GuideStep(val heading: String, val body: String)
data class GuideCard(val title: String, val steps: List<GuideStep>, val heroUrl: String?) : Card

/**
 * The centerpiece widget: "how do I make avocado toast", "two-day Tokyo plan" → the model writes
 * concise steps, we render them as a magazine-style card with a hero photo, and he speaks only a
 * one-line summary instead of reading a list out loud.
 */
object GuideTool : Tool {
    override val name = "show_guide"
    override val description =
        "Render a step-by-step guide on the display: recipes, how-tos, itineraries, plans, " +
        "checklists — any multi-step answer. Provide a short title and 3-8 concise steps. " +
        "The display is a visual companion — after calling, still walk through the steps by " +
        "voice, naturally and unhurried (this is a hands-free device; the user may be cooking)."
    override val parameters = Tools.objectOf(
        "title" to Tools.string("short title, e.g. 'Avocado Toast' or 'Tokyo in 2 Days'"),
        "image_query" to Tools.string("what a fitting cover photo shows, e.g. 'avocado toast'"),
        "steps" to Tools.arrayOf(
            Tools.objectOf(
                "heading" to Tools.string("3-6 word step name"),
                "body" to Tools.string("1-2 sentence detail"),
                required = listOf("heading", "body")
            ),
            "the steps, in order"
        ),
        required = listOf("title", "steps")
    )

    override fun run(args: JSONObject, host: ToolHost): String {
        val title = args.optString("title")
        val arr = args.optJSONArray("steps")
        val steps = ArrayList<GuideStep>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val s = arr!!.optJSONObject(i) ?: continue
            val h = s.optString("heading"); val b = s.optString("body")
            if (h.isNotEmpty() || b.isNotEmpty()) steps.add(GuideStep(h, b))
        }
        if (title.isEmpty() || steps.isEmpty()) return "The guide needs a title and steps — try again."
        // One good cover photo; the guide is fine without it if both searches come up dry.
        // Wikipedia's article lead image first (curated, high quality for dishes/places), then
        // Openverse as the anything-goes fallback.
        val q = args.optString("image_query").ifEmpty { title }
        val hero = try {
            wikipediaImage(host, q) ?: Openverse.search(host, q, 1).firstOrNull()
        } catch (e: Exception) {
            Log.w("Jarvis", "guide hero photo failed (${e.message})"); null
        }
        host.showCard(GuideCard(title, steps, hero))
        host.status("Jarvis is speaking…")
        return "The \"$title\" guide (${steps.size} steps) is now on the display as a visual aid. " +
            "Now walk the user through it by voice: conversational and unhurried, step by step — " +
            "they may be busy with their hands. They'll say stop when they've heard enough."
    }

    /** Lead image of the best-matching Wikipedia article — one call via generator=search. */
    private fun wikipediaImage(host: ToolHost, q: String): String? {
        val url = "https://en.wikipedia.org/w/api.php?action=query&generator=search" +
            "&gsrlimit=1&prop=pageimages&piprop=thumbnail&pithumbsize=900&format=json" +
            "&gsrsearch=" + java.net.URLEncoder.encode(q, "UTF-8")
        val body = host.http.newCall(
            okhttp3.Request.Builder().url(url).header("User-Agent", "JarvisPanel/1.0").build()
        ).execute().use { it.body?.string().orEmpty() }
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val first = pages.keys().asSequence().firstOrNull() ?: return null
        return pages.optJSONObject(first)?.optJSONObject("thumbnail")?.optString("source")
            ?.takeIf { it.startsWith("http") }
    }
}

/** Photo search via Openverse (openverse.org — free, no API key). Feeds the guide's cover photo. */
object Openverse {
    fun search(host: ToolHost, q: String, n: Int): List<String> {
        val url = "https://api.openverse.org/v1/images/?page_size=${n * 2}&mature=false&q=" +
            java.net.URLEncoder.encode(q, "UTF-8")
        val body = host.http.newCall(
            Request.Builder().url(url).header("User-Agent", "JarvisPanel/1.0").build()
        ).execute().use { it.body?.string().orEmpty() }
        val results = JSONObject(body).optJSONArray("results")
        val urls = ArrayList<String>()
        for (i in 0 until (results?.length() ?: 0)) {
            val r = results!!.optJSONObject(i) ?: continue
            // thumbnail = Openverse-scaled ~600px, right for a card; fall back to the original
            val u = r.optString("thumbnail").ifEmpty { r.optString("url") }
            if (u.startsWith("http")) urls.add(u)
            if (urls.size == n) break
        }
        return urls
    }
}
