package com.avera.jarvis

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Web search for the voice assistant.
 *
 * The Realtime API has no built-in web search — declaring {"type": "web_search"} in session.update
 * is silently accepted and then hangs forever. The supported route is a normal function tool that
 * the client services itself: when Jarvis calls web_search we run the query through OpenRouter's
 * Perplexity Sonar Pro Search (a purpose-built search model with live web access) and hand the
 * answer back as the function result. We ask for a couple of spoken-style sentences, because
 * whatever comes back gets read aloud and also lands in the realtime session's context window.
 */
object Search {
    private const val MODEL = "perplexity/sonar-pro-search"
    private val JSON = "application/json".toMediaType()

    /**
     * Date-anchored: without "today is …" the search model has no idea what "next", "latest",
     * or "upcoming" mean and happily serves last week (it returned an already-played World Cup
     * match for "when is Argentina's next game").
     */
    private fun prompt(query: String): String {
        val today = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.US)
            .format(java.util.Date())
        return "Today is $today. Search the web and answer in 2-3 short sentences, written to be " +
            "read aloud (no markdown, no lists, no URLs, no citations). Prefer the most recent " +
            "information; for questions about a next or upcoming event, answer with what is " +
            "upcoming relative to today, not something already finished: $query"
    }

    fun run(client: OkHttpClient, query: String): String {
        if (query.isBlank()) return "No search query was given."
        // Sonar Pro Search first (better live results); if OpenRouter can't serve — out of
        // credits, outage, whatever — fall back to OpenAI's hosted web_search on the key that
        // already runs every conversation. Search should never be down because one vendor is.
        sonar(client, query)?.let { return it }
        Log.w("Jarvis", "sonar unavailable → falling back to OpenAI web search")
        openAi(client, query)?.let { return it }
        return "The search failed. Tell the user you couldn't reach the web."
    }

    /** OpenRouter → Perplexity Sonar Pro Search. Null = unavailable, try the fallback. */
    private fun sonar(client: OkHttpClient, query: String): String? = try {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 2000)
            .put("messages", org.json.JSONArray().put(JSONObject()
                .put("role", "user").put("content", prompt(query))))
            .toString()
        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${Env["OPENROUTER_API_KEY"]}")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e("Jarvis", "search HTTP ${resp.code}: ${text.take(200)}")
                null
            } else {
                val msg = runCatching { JSONObject(text) }.getOrNull()
                    ?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val answer = speakable(msg?.optString("content").orEmpty())
                Log.i("Jarvis", "search ok (sonar): ${answer.take(120)}")
                answer.ifBlank { null }
            }
        }
    } catch (e: Exception) {
        Log.e("Jarvis", "sonar search failed", e); null
    }

    /** OpenAI Responses API with its hosted web_search tool — the reliable second opinion. */
    private fun openAi(client: OkHttpClient, query: String): String? = try {
        val body = JSONObject()
            .put("model", "gpt-4.1-mini")
            .put("tools", org.json.JSONArray().put(JSONObject().put("type", "web_search")))
            .put("input", prompt(query))
            .toString()
        val req = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer ${Env["OPENAI_API_KEY"]}")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e("Jarvis", "openai search HTTP ${resp.code}: ${text.take(200)}")
                null
            } else {
                val answer = extractResponsesText(text)
                Log.i("Jarvis", "search ok (openai): ${answer.take(120)}")
                answer.ifBlank { null }
            }
        }
    } catch (e: Exception) {
        Log.e("Jarvis", "openai search failed", e); null
    }

    /** Responses payload: output[] → message → content[].text */
    private fun extractResponsesText(json: String): String {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return ""
        val out = root.optJSONArray("output") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until out.length()) {
            val item = out.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (c in 0 until content.length()) {
                val part = content.optJSONObject(c) ?: continue
                val t = part.optString("text")
                if (t.isNotEmpty()) sb.append(t).append(' ')
            }
        }
        return speakable(sb.toString())
    }

    /**
     * The search model cites its sources as markdown links, which is right for a screen and wrong
     * for a speaker — read aloud, "open paren bracket formula1.com bracket..." is nonsense.
     */
    private fun speakable(text: String): String = text
        .replace(Regex("""\(\[[^\]]*]\([^)]*\)\)"""), "")   // ([label](url)) — the citation form
        .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")   // [label](url) → label
        .replace(Regex("""https?://\S+"""), "")             // any bare URL
        .replace(Regex("""\s{2,}"""), " ")
        .replace(Regex("""\s+([.,!?])"""), "$1")
        .trim()
}
