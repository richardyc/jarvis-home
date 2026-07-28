package com.avera.jarvis.tools

import android.util.Log
import com.avera.jarvis.Card
import com.avera.jarvis.Tool
import com.avera.jarvis.ToolHost
import com.avera.jarvis.Tools
import okhttp3.Request
import org.json.JSONObject

data class Game(
    val away: String, val home: String,          // team abbreviations, away @ home
    val awayScore: String, val homeScore: String,
    val awayLogo: String, val homeLogo: String,
    val live: Boolean,
    val pre: Boolean,                             // scheduled, not yet played → render "vs", no 0·0
    val detail: String                            // "Final", "Q3 4:12", "Wed 12:00 PM"
)

data class ScoresCard(val title: String, val games: List<Game>) : Card

/**
 * Scores, results, and upcoming fixtures via ESPN's public scoreboard JSON (site.api.espn.com,
 * no key). Not an enum of leagues: the endpoint shape is uniform for EVERY sport ESPN carries,
 * so the model passes the league path itself and anything ESPN covers just works.
 */
object ScoresTool : Tool {
    override val name = "get_scores"
    override val description =
        "Sports scores, results, AND upcoming fixtures — renders a scoreboard panel with team " +
        "logos on the display. Use it for schedules ('when do X play next') as well as scores. " +
        "league = an ESPN league path: basketball/nba, football/nfl, baseball/mlb, hockey/nhl, " +
        "soccer/eng.1 (Premier League), soccer/uefa.champions, soccer/fifa.world (World Cup), " +
        "soccer/usa.1 (MLS), basketball/wnba, football/college-football, or any other ESPN league. " +
        "For upcoming games pass dates (you know today's date); omit dates for today/live."
    override val parameters = Tools.objectOf(
        "league" to Tools.string("ESPN league path, e.g. 'soccer/fifa.world' or 'basketball/nba'"),
        "dates" to Tools.string("YYYYMMDD, or a range YYYYMMDD-YYYYMMDD, for schedule/upcoming lookups"),
        "team" to Tools.string("optional team name/city to filter to, e.g. 'Argentina' or 'Warriors'"),
        required = listOf("league")
    )

    override fun run(args: JSONObject, host: ToolHost): String {
        val path = args.optString("league").trim('/').lowercase()
        if (!path.matches(Regex("[a-z0-9.-]+/[a-z0-9.-]+"))) return "Unknown league path."
        val team = args.optString("team").trim().lowercase()
        // The model forgets `dates` on some runs; a bare scoreboard is TODAY only, which answers
        // "when do they play next" with "no games". Default to a today→+5d window — today's live
        // games still list first, and near fixtures are in reach without the model's help.
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val dates = args.optString("dates").replace(Regex("[^0-9-]"), "").ifEmpty {
            fmt.format(java.util.Date()) + "-" +
                fmt.format(java.util.Date(System.currentTimeMillis() + 5 * 86_400_000L))
        }
        host.status("Checking scores…")
        return try {
            val url = "https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard?dates=$dates"
            val body = host.http.newCall(Request.Builder().url(url).build())
                .execute().use { it.body?.string().orEmpty() }
            val title = JSONObject(body).optJSONArray("leagues")?.optJSONObject(0)
                ?.optString("abbreviation")?.ifEmpty { null }
                ?: JSONObject(body).optJSONArray("leagues")?.optJSONObject(0)?.optString("name")
                ?: path.substringAfter('/').uppercase()
            val events = JSONObject(body).optJSONArray("events")
            val games = ArrayList<Game>()
            for (i in 0 until (events?.length() ?: 0)) {
                val ev = events!!.optJSONObject(i) ?: continue
                val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                val st = comp.optJSONObject("status")?.optJSONObject("type")
                var away: JSONObject? = null; var home: JSONObject? = null
                val cs = comp.optJSONArray("competitors")
                for (c in 0 until (cs?.length() ?: 0)) {
                    val comp2 = cs!!.optJSONObject(c) ?: continue
                    if (comp2.optString("homeAway") == "home") home = comp2 else away = comp2
                }
                if (home == null || away == null) continue
                fun name(c: JSONObject) = c.optJSONObject("team")?.optString("abbreviation").orEmpty()
                fun full(c: JSONObject) = c.optJSONObject("team")?.optString("displayName").orEmpty()
                fun logo(c: JSONObject) = c.optJSONObject("team")?.optString("logo").orEmpty()
                if (team.isNotEmpty() &&
                    !full(home).lowercase().contains(team) && !full(away).lowercase().contains(team)) continue
                val pre = st?.optString("state") == "pre"
                // Fixtures: kickoff from event.date in the PANEL's timezone. Field trap: ESPN's
                // shortDetail says just "Scheduled" for pre games — the kickoff string lives in
                // `detail` ("Wed, July 15th at 3:00 PM EDT"), kept here as the fallback.
                val detail = if (pre) kickoff(ev.optString("date"))
                    ?: st?.optString("detail").orEmpty()
                else st?.optString("shortDetail").orEmpty()
                games.add(Game(
                    away = name(away), home = name(home),
                    awayScore = away.optString("score"), homeScore = home.optString("score"),
                    awayLogo = logo(away), homeLogo = logo(home),
                    live = st?.optString("state") == "in",
                    pre = pre,
                    detail = detail
                ))
                if (games.size == 6) break
            }
            if (games.isEmpty())
                return "No ${if (team.isEmpty()) title else "$team ($title)"} games in the window " +
                    "$dates. You may call again with a wider dates range (YYYYMMDD-YYYYMMDD)."
            host.showCard(ScoresCard(title, games))
            host.status("Jarvis is speaking…")
            val summary = games.joinToString("; ") {
                if (it.pre) "${it.away} vs ${it.home}, ${it.detail}"
                else "${it.away} ${it.awayScore}–${it.homeScore} ${it.home} (${it.detail})"
            }
            "Scoreboard is on the display: $summary. Give a short natural spoken summary of " +
                (if (team.isEmpty()) "the most notable games" else "the $team game") + ", not a list."
        } catch (e: Exception) {
            Log.e("Jarvis", "scores fetch failed", e)
            "The scores lookup failed. Tell the user, briefly."
        }
    }

    /** "2026-07-15T16:00Z" → "Wed, Jul 15, 12:00 PM PDT" in the panel's timezone. */
    private fun kickoff(iso: String): String? = runCatching {
        val parse = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val d = parse.parse(iso) ?: return null
        // deliberate two-line break: date on top, time+zone underneath — never a mid-time wrap
        java.text.SimpleDateFormat("EEE, MMM d\nh:mm a zzz", java.util.Locale.US).format(d)
    }.getOrNull()
}
