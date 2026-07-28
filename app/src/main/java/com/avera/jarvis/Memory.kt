package com.avera.jarvis

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-term memory as plain Markdown on disk — no hidden state, everything inspectable:
 *
 *   files/memory/MEMORY.md      durable facts & preferences, injected into EVERY system prompt
 *   files/memory/YYYY-MM-DD.md  daily notes; today + yesterday ride along for recent context
 *
 * The model writes through the `remember` tool; the user wipes it from Settings. Facts that
 * change future behavior should carry their trigger ("…when asked for music"), not just the fact.
 */
object Memory {
    private lateinit var dir: File
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    fun init(ctx: android.content.Context) {
        dir = File(ctx.filesDir, "memory")
        dir.mkdirs()
    }

    private val main get() = File(dir, "MEMORY.md")
    private fun dailyFile(d: Date = Date()) = File(dir, day.format(d) + ".md")

    @Synchronized
    fun rememberDurable(fact: String) {
        main.appendText("- ${fact.trim()}\n")
        android.util.Log.i("Jarvis", "memory += \"${fact.trim()}\"")
    }

    @Synchronized
    fun noteToday(note: String) {
        dailyFile().appendText("- ${clock.format(Date())} ${note.trim()}\n")
    }

    /** Durable facts for the prompt, newest kept if the file has grown past the budget. */
    fun durable(maxChars: Int = 4000): String {
        val t = runCatching { main.readText() }.getOrDefault("").trim()
        return if (t.length <= maxChars) t else t.takeLast(maxChars).substringAfter("\n")
    }

    /** Today's + yesterday's notes (capped) — enough recency to feel continuous. */
    fun recentNotes(maxChars: Int = 1500): String {
        val y = Date(System.currentTimeMillis() - 86_400_000L)
        val t = listOf(dailyFile(y), dailyFile())
            .filter { it.exists() }
            .joinToString("\n") { it.readText().trim() }
            .trim()
        return if (t.length <= maxChars) t else t.takeLast(maxChars).substringAfter("\n")
    }

    fun factCount(): Int =
        runCatching { main.readLines().count { it.startsWith("- ") } }.getOrDefault(0)

    @Synchronized
    fun wipe() {
        dir.listFiles()?.forEach { it.delete() }
        android.util.Log.i("Jarvis", "memory wiped")
    }
}
