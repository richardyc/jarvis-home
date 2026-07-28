package com.avera.jarvis.tools

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.avera.jarvis.Sfx
import com.avera.jarvis.Tool
import com.avera.jarvis.ToolHost
import com.avera.jarvis.Tools
import org.json.JSONObject

/**
 * App-level timer state, observable by Compose. Lives outside the session — a timer set in one
 * conversation must keep counting and ring after the conversation is long gone (the app is the
 * HOME activity, the process is always alive).
 */
object TimerManager {
    /** elapsedRealtime when the timer fires; 0 = no timer. Immune to wall-clock changes. */
    var endsAt by mutableStateOf(0L)
        private set
    var totalMs by mutableStateOf(0L)
        private set
    var label by mutableStateOf("")
        private set
    var ringing by mutableStateOf(false)
        private set

    /** How loud the chime is — wired to the panel volume by MainActivity. */
    @Volatile var gainProvider: () -> Float = { 0.7f }
    /**
     * Ring started/stopped — MainActivity pauses the wake detector for the duration: the melodic
     * chime reliably false-triggered openWakeWord, and the resulting startConversation() silently
     * dismissed the ring seconds after it fired (a self-dismissing alarm, found the hard way).
     */
    var onRingChange: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    /** Mirror of [ringing] for background threads — Compose state isn't a cross-thread flag. */
    @Volatile private var ringingV = false
    @Volatile private var lastChimeAt = 0L
    private val fire = Runnable {
        android.util.Log.i("Jarvis", "timer FIRED (label='$label')")
        ringing = true; ringingV = true
        onRingChange?.invoke(true)
        chimeLoop(0)
        startStopListener()
    }

    fun start(seconds: Int, label: String) = main.post {
        // main thread only: Compose state + the Handler bookkeeping stay coherent
        cancelInternal("restart")
        this.label = label
        totalMs = seconds * 1000L
        endsAt = SystemClock.elapsedRealtime() + totalMs
        main.postDelayed(fire, totalMs)
        android.util.Log.i("Jarvis", "timer START ${seconds}s (label='$label') endsAt=$endsAt")
    }

    fun cancel() { main.post { cancelInternal("cancel") } }

    private fun cancelInternal(why: String) {
        val wasRinging = ringing
        if (endsAt != 0L) android.util.Log.i("Jarvis", "timer CANCELLED ($why, wasRinging=$wasRinging)")
        main.removeCallbacks(fire)   // ONLY our fire — removeCallbacksAndMessages(null) would also
                                     // kill a just-posted start{} block sharing this handler
        endsAt = 0L; totalMs = 0L; label = ""; ringing = false; ringingV = false
        if (wasRinging) onRingChange?.invoke(false)
    }

    /**
     * Hands-free dismissal: while ringing, listen in the QUIET GAPS between chimes (we play the
     * chime, so we know exactly when it sounds) and any loud utterance — "stop", a shout, a clap —
     * ends the ring. No wake-word model here: the chime itself false-triggers "Hey Jarvis", and
     * making someone walk over and tap an alarm defeats a voice device.
     */
    private fun startStopListener() {
        Thread {
            // frames come from MicHub — no AudioRecord of our own, so this listener can never
            // lose the capture slot to (or steal it from) the wake detector or a session
            val q = java.util.concurrent.ArrayBlockingQueue<ShortArray>(32)
            val sink: (ShortArray) -> Unit = { f -> q.offer(f.copyOf()) }
            com.avera.jarvis.MicHub.addSink(sink)
            var hot = 0
            while (ringingV) {
                val f = q.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val sinceChime = SystemClock.elapsedRealtime() - lastChimeAt
                if (sinceChime < 1150 || sinceChime > 2900) { hot = 0; continue }   // chime (or its room echo) is sounding
                var amp = 0
                for (i in f.indices) { val a = Math.abs(f[i].toInt()); if (a > amp) amp = a }
                if (amp > STOP_AMP) {
                    if (++hot >= 20) {   // 200ms sustained (10ms hub frames)
                        android.util.Log.i("Jarvis", "ring dismissed by voice (amp=$amp)")
                        main.post { dismissRing() }
                        break
                    }
                } else hot = 0
            }
            com.avera.jarvis.MicHub.removeSink(sink)
        }.also { it.isDaemon = true; it.start() }
    }

    /** Loud-enough-to-mean-it: a raised voice or clap near the panel, not the room's hum.
     *  Scale is the COMM route's (hub) — ~8x quieter than the old raw route, hence 400 not 3000. */
    private const val STOP_AMP = 400

    /** Tap / new conversation silences a ringing timer (and only a ringing one). */
    fun dismissRing() { if (ringing) cancel() }

    fun remainingMs(): Long = if (endsAt == 0L) 0L else (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    /**
     * Ring politely: a chime every 3s — the long quiet gap is deliberate, it's the window where
     * a spoken "stop" can actually be heard (the echo suppressor clamps the mic DURING the beep).
     * Give up after ~90s unattended.
     */
    private fun chimeLoop(n: Int) {
        if (!ringing) return
        if (n >= 30) { cancel(); return }
        lastChimeAt = SystemClock.elapsedRealtime()
        Sfx.chime(gainProvider())
        main.postDelayed({ chimeLoop(n + 1) }, 3000)
    }
}

object TimerTool : Tool {
    override val name = "timer"
    override val description =
        "Set, cancel, or check a countdown timer with an audible chime. Call for 'set a timer for " +
        "10 minutes', 'cancel the timer', 'how long is left'. Only one timer at a time."
    override val parameters = Tools.objectOf(
        "action" to Tools.string("what to do", listOf("start", "cancel", "status")),
        "seconds" to Tools.integer("timer length in seconds (for start)"),
        "label" to Tools.string("optional short label, e.g. 'tea'"),
        required = listOf("action")
    )
    override val fast = true

    override fun run(args: JSONObject, host: ToolHost): String = when (args.optString("action")) {
        "start" -> {
            val secs = args.optInt("seconds", 0)
            if (secs <= 0) "No duration given — ask the user how long."
            else {
                TimerManager.start(secs, args.optString("label"))
                "Timer set for ${spoken(secs * 1000L)}; a countdown is on the display. Briefly confirm."
            }
        }
        "cancel" -> {
            if (TimerManager.endsAt == 0L) "There is no timer running."
            else { TimerManager.cancel(); "Timer cancelled. Briefly confirm." }
        }
        else -> {
            if (TimerManager.endsAt == 0L) "There is no timer running."
            else "${spoken(TimerManager.remainingMs())} left on the timer. Tell the user naturally."
        }
    }

    private fun spoken(ms: Long): String {
        val s = (ms / 1000).toInt()
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return buildString {
            if (h > 0) append("$h hour${if (h > 1) "s" else ""} ")
            if (m > 0) append("$m minute${if (m > 1) "s" else ""} ")
            if (sec > 0 && h == 0) append("$sec second${if (sec > 1) "s" else ""}")
        }.trim().ifEmpty { "0 seconds" }
    }
}
