package com.avera.jarvis

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One chat bubble. [text] is observable so the assistant's reply streams into it live. */
class ChatEntry(val mine: Boolean, initial: String) {
    var text by mutableStateOf(initial)
}

/**
 * The live conversation.
 *
 *   mic 16k → (hardware echo cancel) → WebSocket → Realtime API → 24k PCM → speaker
 *
 * Half-duplex: the mic isn't streamed while Jarvis speaks, so the server never hears him. Interrupt
 * with "Hey Jarvis" (detected on-device) or a tap.
 */
class RealtimeSession(private val cfg: Config) {

    // observable UI state
    var status by mutableStateOf("Say “Hey Jarvis”")
        private set
    var userText by mutableStateOf("")
        private set
    var assistantText by mutableStateOf("")
        private set
    var active by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var card by mutableStateOf<Card?>(null)             // widget card on the display, if any
        private set
    var level by mutableStateOf(0f)                     // 0..1 audio level → drives the orb waves
        private set

    /** The whole conversation so far, oldest first — the scrollable chat log. Cleared when it ends. */
    val messages = androidx.compose.runtime.mutableStateListOf<ChatEntry>()
    private var curAssistant: ChatEntry? = null         // the assistant bubble currently streaming

    /** invoked (on main) when a session fully ends → coordinator restarts the wake word */
    var onClosed: (() -> Unit)? = null
    var audioManager: android.media.AudioManager? = null   // for voice volume control (buttons are dead)

    /** demo: real Open-Meteo fetch for a location, render the card (proves the live fetch→card path) */
    fun showDemoWeather(location: String) {
        Thread {
            val d = Weather.fetch(client, location)
            if (d != null) set {
                card = WeatherCard(d)
                status = "Jarvis is speaking…"
                assistantText = "It's ${d.currentTemp}° and ${d.description} in ${d.location} — " +
                    "today ${d.days.firstOrNull()?.hi}/${d.days.firstOrNull()?.lo}."
            } else set { status = "weather fetch failed for '$location'" }
        }.also { it.isDaemon = true; it.start() }
    }

    private val main = Handler(Looper.getMainLooper())
    // This panel's network has broken IPv6 (SLAAC addresses but no v6 default route), so
    // OkHttp/Java otherwise tries an IPv6 socket first and dies with ENETUNREACH. Force IPv4.
    private val ipv4Only = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filterIsInstance<Inet4Address>()
            return if (v4.isNotEmpty()) v4 else all
        }
    }
    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4Only)
        // The WiFi stack takes the radio off-channel for a couple of seconds when it scans; a tight
        // ping deadline turns that into a dropped conversation. Be patient — a genuinely dead socket
        // still gets caught, just a little later, and the session reconnects underneath the user.
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    init { ImageLoader.http = client }   // card images ride the same IPv4-pinned client
    private var audioChunks = 0
    private var transcriptChunks = 0
    private val pendingFn = HashMap<String, String>()   // call_id → function name
    @Volatile private var micGated = false   // "speaker may be audible": turns committed now are
                                              // talk-over — stop-scanned, never auto-answered.
                                              // (Mic STREAMS regardless; the DSP cancels the echo.)
    @Volatile private var startedGated = false // micGated at the last speech ONSET — a turn that
                                               // began over the speaker stays a talk-over turn
                                               // even if it commits just after the gate clears
                                               // (his "Stop." straddled the gate and got ANSWERED)
    @Volatile private var framesWritten = 0L // frames pushed to AudioTrack, to know when playback drains
    @Volatile private var ungateGen = 0      // invalidates a pending drain-watch when a new turn/interrupt happens
    private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var recordThread: Thread? = null
    private var reconnects = 0
    // A real WiFi drop takes 5–30s to heal; spread the retries across ~30s so a session can
    // ride one out instead of giving up in 3 seconds like the first version of this did.
    private val RETRY_DELAYS_MS = longArrayOf(1000, 2000, 4000, 8000, 15000)
    // Only these cut Jarvis off mid-answer (matched against the live mic transcription). The whole
    // utterance must BE a stop command — anchored, so TV dialogue that merely contains the word
    // "stop" in a sentence doesn't kill the response. Chinese matches anywhere (short phrases).
    private val STOP_PHRASE = Regex(
        "^\\W*(ok(ay)?\\W+)?(jarvis\\W+)?((stop|shut\\s*up|be\\s*quiet|quiet|enough|shush)\\W*)+((it|now|please|jarvis)\\W*)*$",
        RegexOption.IGNORE_CASE)
    private val STOP_PHRASE_ZH = Regex("别说了|別說了|闭嘴|閉嘴|安静|安靜|停下|够了|夠了|等等|等一下|慢着|慢著|停")
    // Mid-speech the transcript is mostly Jarvis's echo; to cut him off we accept a stop word appearing
    // anywhere (the user speaking over him), not just as a whole anchored utterance.
    // Explicit stop commands ONLY. Mid-playback transcripts are a MIX (Jarvis's leaked speech +
    // the user), so common words like wait/hold-on/quiet would self-interrupt when Jarvis says
    // them; these unambiguous ones never appear in his answers.
    private val STOP_LOOSE = Regex("\\b(stop|shut\\s*up|shush)\\b", RegexOption.IGNORE_CASE)
    // item_id → was Jarvis speaking when this turn was captured (true = his echo, not a real user turn).
    /** Set while the physical mic-mute switch is engaged — never stream audio then. */
    @Volatile var hardMuted = false
    /** Debug only: run the session but never stream the mic upstream. */
    @Volatile var debugNoUpstream = false
    /** Invoked on socket failure so the owner can kick the WiFi watchdog immediately. */
    var onNetworkTrouble: (() -> Unit)? = null

    /**
     * Barge-in: push a 16kHz chunk captured while Jarvis is talking; returns true if the user said
     * the wake word over him. Energy alone can't do this — the panel has no echo canceller, so the
     * mic mostly hears Jarvis's own voice out of the speaker. The wake model is discriminative, so
     * it ignores that and only fires on an actual "Hey Jarvis".
     */
    var bargeCheck: ((ShortArray) -> Boolean)? = null
    private var track: AudioTrack? = null
    private val playExec = Executors.newSingleThreadExecutor()
    private val bargeExec = Executors.newSingleThreadExecutor()
    @Volatile private var bargeBusy = false   // drop barge chunks while one inference is in flight

    private val micRate = Aec.RATE   // 16kHz: AEC3's native rate, and what we tell the API to expect
    // ONE fixed make-up gain — measured end-to-end in the VAD harness against the real API:
    // raw comm-route speech peaks ~4000 even loud and close, so x6 can never clip (peak 25k),
    // fires the server VAD from the first word at threshold 0.6, and needs no limiter, no
    // envelope tracking, no attack/release. (An adaptive gain was tried; its level movement
    // was itself the audible artifact.)
    private val micGain = cfg.micGain
    private val outRate = 24000      // Realtime API speaks back at 24kHz
    /** Speaker→mic flight time plus capture latency. AEC3 refines this itself; it's a starting hint. */
    private val ECHO_DELAY_MS = 60
    /** Coalesce 10ms AEC frames into ~100ms websocket messages. */
    private val SEND_CHUNKS = 10
    /** A 10ms frame at the API's rate — what each cancelled 160-sample frame becomes upsampled. */
    private val UP_FRAME = outRate / 100

    /** Reference signal for AEC3: what the speaker is emitting, paced to the playback head. */
    private val farEnd = FarEnd()

    // Mic audio captured before the socket is ready to receive it — during the initial connect, and
    // during a mid-conversation reconnect. Flushed the instant the session opens, so the first words
    // of a question (spoken while the WebSocket is still connecting) are never lost. Without this the
    // mic only started on onOpen, dropping ~2-3s of speech and garbling the transcription.
    @Volatile private var wsReady = false
    private val prerollLock = Any()
    private val preroll = java.io.ByteArrayOutputStream()
    private val PREROLL_CAP = outRate * 2 * 10   // ~10s of 24kHz PCM — plenty for any connect

    // Output volume as a linear gain on the playback track (0..1). Deterministic: on this HAL the
    // system stream-volume plumbing doesn't reliably re-scale an already-playing track, which is why
    // the volume buttons looked dead. A per-track gain always works.
    @Volatile private var outGain = 1f
    /** Model/voice, re-read at every session start — Settings can change them between sessions. */
    var modelProvider: () -> String = { cfg.model }
    var voiceProvider: () -> String = { cfg.voice }

    /** Notified when a tool changes the volume so the UI HUD can reflect it. pct = 0..100. */
    var onVolumeChange: ((Int) -> Unit)? = null
    fun setOutputGain(g: Float) { outGain = g.coerceIn(0f, 1f) }   // applied by scaling the PCM (see audio delta)

    /** What tools may touch (see Tools.kt) — the only door from a plugin into the session. */
    private val toolHost = object : ToolHost {
        override val http get() = client
        override val homeCity get() = cfg.homeCity
        override fun status(text: String) = set { if (running) status = text }
        override fun showCard(card: Card?) = set { this@RealtimeSession.card = card }
        override fun volume() = (outGain * 100).toInt()
        override fun setVolume(pct: Int) {
            setOutputGain(pct / 100f)
            onVolumeChange?.invoke(pct)
        }
        override fun endSession() {
            Log.i("Jarvis", "tool end_conversation → stopping session")
            set { status = "Okay — stopping." }
            main.postDelayed({ stop() }, 250)
        }
    }

    /** playbackHeadPosition, but safe: a torn-down track (stop/interrupt on another thread) throws. */
    private fun headFrames(): Long =
        try { track?.playbackHeadPosition?.toLong() ?: 0L } catch (e: IllegalStateException) { 0L }


    /** Duck-on-voice (see mic loop): speaker goes silent until this deadline passes. */
    @Volatile private var duckUntil = 0L
    private var duckHot = 0                 // mic-thread only: consecutive hot 10ms frames
    // True from a gated speech_started until that turn commits/stops: the utterance is mid-air
    // and the duck must hold, or its tail gets re-clamped when a fixed window expires (measured:
    // "tell me what time it is right now" → "Right. Right.").
    @Volatile private var speechInFlight = false
    // Duck trigger, evaluated only while micGated and not already ducked — gain is x1 then, so
    // RAW scale. EchoLab overlap test: a normal-volume interruption rides at peaks 670-1830 for
    // ~1s SUSTAINED, while echo-residual bursts are short (~100-600ms). So: 300ms of sustained
    // energy above 450 = someone is talking over Jarvis → silence the speaker, which releases
    // the DSP's doubletalk suppressor so the REST of their sentence reaches the server at full
    // strength (the duck window also switches the upstream gain on — see gainNow). A false fire
    // costs a 1.8s dip; rate-limited to 2 per response.
    // Tuned OFFLINE against recorded captures (EchoLab + live taps): fires 410ms into a real
    // question spoken over the poem, zero false fires on 20s+ of playback-only audio.
    private val DUCK_AMP = 300
    private var duckCount = 0               // ducks fired for the current response


    /**
     * Frames actually played, by the most honest witness available. This HAL's head counter
     * randomly wedges at 0 system-wide (only an audioserver restart revives it) — when that
     * happens the wall clock takes over: the DAC eats exactly outRate frames per second, so
     * elapsed time bounds consumption within a hair. Everything that used to trust the head
     * (write pacing, the drain watcher) rides this instead.
     */
    /** One truth for "what has the speaker played": drain-watch and pacing use the same clock
     *  as AEC3's reference — the old separate wall-clock arm saturated at framesWritten and told
     *  the pacing the queue was always empty. */
    private fun playedFrames(): Long = speakerFrames()

    /** Playback-stream epoch: where and when continuous output (re)started after an empty queue. */
    @Volatile private var epochFrames = 0L
    @Volatile private var epochTime = 0L
    /** elapsedRealtime of the last actual track.write — the physics behind the mic gate. */
    @Volatile private var lastWriteAt = 0L

    /**
     * Where the SPEAKER truly is — the clock AEC3's reference, the write pacing, and the drain
     * watcher all live by. MODELED ONLY: from the moment a stream (re)starts, the DAC consumes
     * exactly outRate frames per second, so epoch + elapsed is accurate to ~20ms and can never
     * freeze. The hardware playbackHeadPosition is NEVER consulted — on this HAL it wedges at 0
     * after certain track operations AND freezes at arbitrary nonzero values mid-playback
     * (observed stuck at 79200 for 10s); one frozen read poisons the echo canceller. Physics
     * doesn't freeze.
     */
    private fun speakerFrames(): Long {
        if (epochTime == 0L) return 0L
        val modeled = epochFrames +
            (android.os.SystemClock.elapsedRealtime() - epochTime) * outRate / 1000
        return modeled.coerceAtMost(framesWritten)
    }

    private fun flushPreroll(webSocket: WebSocket) {
        val data = synchronized(prerollLock) { val b = preroll.toByteArray(); preroll.reset(); b }
        if (data.isEmpty()) return
        var off = 0
        val chunk = outRate           // ~0.5s of 24k PCM per message
        while (off < data.size) {
            val n = minOf(chunk, data.size - off)
            webSocket.send(JSONObject().put("type", "input_audio_buffer.append")
                .put("audio", Base64.encodeToString(data, off, n, Base64.NO_WRAP)).toString())
            off += n
        }
        tap(data, data.size)
        Log.i("Jarvis", "preroll flushed: ${data.size} bytes (~${data.size / 2 * 1000 / outRate}ms of speech)")
    }

    private val hardStop = Runnable { set { status = "Session ended · 10-min cap" }; stop() }
    private val idleStop = Runnable { set { status = "Idle — tap to talk" }; stop() }
    // safety: never leave the mic gated forever if a response stalls
    private val ungateSafety = Runnable { micGated = false; Log.i("Jarvis", "mic UNGATED (safety timeout)") }

    fun toggle() { if (running) stop() else start() }

    fun start() {
        if (running) return
        if (hardMuted) {                       // physical switch wins — never listen while muted
            set { status = "Microphone is muted" }
            return
        }
        running = true
        micGated = false
        startedGated = false
        wsReady = false
        reconnects = 0
        stillborns = 0
        synchronized(prerollLock) { preroll.reset() }
        tapOpen()             // mirror this session's upstream audio for ear-debugging
        // FINAL synthesis of the audio saga. Capture = RAW route (no comm mode, no fluence):
        // the DSP path's AGC pumped his voice 300→20000 within a sentence and the suppressor
        // muffled utterance onsets — "not hearing me clearly". The raw route records naturally.
        // Echo protection doesn't need the chip anymore because the PROVEN POLICY carries it:
        // questions are captured while Jarvis is SILENT (no echo exists), nothing auto-responds,
        // and mid-playback only short stop-bursts act. AEC3 + duck are best-effort cleanup.
        audioManager?.let { am -> runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        } }
        set { status = "Listening…"; active = true; userText = ""; assistantText = ""; messages.clear(); curAssistant = null }
        // HARD safety cap: force-close after maxSessionMs no matter what
        main.postDelayed(hardStop, cfg.maxSessionMs)
        resetIdle()
        // Playback BEFORE capture: the HAL picks the mic's snd_device once, at open, and only routes
        // it through the echo-cancelled path if a voice-comm output is already live on the speaker.
        startPlayback()
        // Start capturing NOW, before the socket connects. Audio is buffered (preroll) and flushed on
        // open, so the first words — spoken while connecting — are never lost.
        if (cfg.micEnabled) startMic()
        reconnect()
    }

    fun stop() {
        val wasActive = running
        running = false
        micGated = false
        wsReady = false
        synchronized(prerollLock) { preroll.reset() }
        main.removeCallbacks(hardStop)
        main.removeCallbacks(idleStop)
        main.removeCallbacks(ungateSafety)
        // Return to the resting orb — don't leave the last exchange (or a stray "stop") on screen.
        set { status = "Say “Hey Jarvis”"; active = false; speaking = false; level = 0f
            userText = ""; assistantText = ""; card = null; messages.clear(); curAssistant = null }
        recordThread = null
        ws?.close(1000, "bye"); ws = null
        flushGen++   // orphan any queued writes/flushes before the teardown lands
        playExec.execute { track?.let { runCatching { it.stop(); it.release() } }; track = null }
        if (wasActive) main.post { onClosed?.invoke() }
    }

    /**
     * Cut Jarvis off mid-answer and go back to listening — without ending the conversation.
     * Playback is flushed (there can be seconds of speech buffered ahead) and the server is told
     * to stop generating, so he doesn't resume talking over the user.
     */
    fun interrupt() {
        if (!running || !micGated) return
        Log.i("Jarvis", "BARGE-IN — cancelling response, back to listening")
        silenceNow()
    }

    /**
     * interrupt() without the micGated precondition: a transcribed stop command can land a beat
     * AFTER the gate cleared (transcripts lag speech by 1-2s) and must still cut the speech.
     * Main thread only.
     */
    private fun silenceNow() {
        if (!running) return
        micGated = false
        startedGated = false
        ungateGen++                        // cancel any pending drain-watch from the response we're killing
        main.removeCallbacks(ungateSafety)
        flushPlayback()   // orphans the queue; the ≤300ms already in the track plays out
        // only cancel a response that's actually alive — transcripts lag speech by 1-2s, so an
        // interrupt often lands after response.done and a blind cancel is an API error
        if (respActive) ws?.send(JSONObject().put("type", "response.cancel").toString())
        resetIdle()
        set { speaking = false; level = 0f; status = "Listening…"; assistantText = "" }
    }

    /** True from response.created to response.done — what response.cancel/create must respect. */
    @Volatile private var respActive = false
    /** Consecutive responses killed at birth — two in a row = room chatter, not conversation. */
    private var stillborns = 0
    /** item_id → was Jarvis speaking when this turn was captured (true = don't answer it). */
    private val turnEcho = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Talk-over fragments collected while the speaker plays — evaluated as one utterance. Main thread only. */
    private var gatedFrags = ""
    private val gatedFragEval = Runnable {
        val text = gatedFrags.trim()
        gatedFrags = ""
        if (text.isEmpty() || !running) return@Runnable
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        if (words >= 3 || cjk >= 4) {
            Log.i("Jarvis", "talk-over (merged \"$text\") → interrupting and answering")
            set {
                userText = text
                messages.add(ChatEntry(mine = true, initial = text))
            }
            val wasActive = respActive
            silenceNow()
            if (wasActive)   // let the cancel land before asking for the answer
                main.postDelayed({ if (running) ws?.send(JSONObject().put("type", "response.create").toString()) }, 250)
            else ws?.send(JSONObject().put("type", "response.create").toString())
        } else if (micGated) {
            // Short unreadable fragment while the speaker is STILL playing: the duck fired and
            // the VAD committed — someone demonstrably shouted over Jarvis, and a 1-2 word bark
            // over playback is almost always "stop" with its onset eaten by the suppressor
            // (his "stop" transcribed as "Good stuff." and got dropped). Err on obedience:
            // shut up and listen. Worst case a noise burst cost the rest of one answer.
            Log.i("Jarvis", "short talk-over (\"$text\") while speaking → treating as stop")
            silenceNow()
        } else Log.i("Jarvis", "talk-over fragments dropped (\"$text\")")
    }

    /**
     * NATIVE turn-taking, MEANING-aware end-of-turn. Two generations of client machinery died
     * here: echo classification (obsoleted by the hardware canceller — 0 false triggers over 45s
     * of his own speech) and fixed-silence turn detection (600ms of thinking pause chopped one
     * thought into three turns; the model answered fragments and felt "dumb"). semantic_vad
     * keeps an unfinished sentence's turn open through a pause and closes a finished one fast;
     * the server sequences interruptions and responses atomically.
     */
    private fun turnDetection(): JSONObject = JSONObject()
        // THE PROVEN POLICY (restored after the full-duplex campaign): the server never
        // auto-responds and never auto-truncates. A bumped laptop, a phone call near the panel,
        // an echo transient — none of it can spawn or kill a response, because WE create
        // responses only for deliberate turns (see committed handler) and interruption is only
        // explicit: "Hey Jarvis", a stop phrase, or a tap. Costs true talk-over dialogue;
        // buys a device that never converses with furniture. User's call, and the right one.
        .put("type", "server_vad")
        // 0.6: harness-verified against the real API with x6 comm-route audio — catches a
        // sentence from its first word; echo residual (x1 while gated) stays silent even at 0.3
        .put("threshold", 0.6)
        // 500ms of pre-speech audio kept: the DSP's AGC attenuates each utterance's first
        // ~300-500ms while it opens up — wide padding keeps those soft syllables in the turn
        .put("prefix_padding_ms", 500)
        .put("silence_duration_ms", maxOf(cfg.vadSilenceMs, 900))
        .put("interrupt_response", false)
        .put("create_response", false)

    /** Debug: put words in the user's mouth, so a spoken answer can be driven without a voice. */
    @Volatile var pendingAsk: String? = null

    /** Swipe-to-dismiss on a widget card — back to whatever is underneath. */
    fun clearCard() { set { card = null } }

    /**
     * Is a transcript captured during playback the USER, or a residue of Jarvis's own voice?
     * The canceller leaves his voice at whisper-noise level, so echo transcripts are rare and
     * short — and when they do appear they quote him. Substantive + not a quote = a real person.
     */

    /**
     * Debug tap: every byte this session uploads to the API is mirrored to files/upstream.pcm
     * (24kHz mono 16-bit LE, ~60s cap, truncated each session). When "why can't it hear me"
     * strikes, pull the file and LISTEN to what the server heard — ears beat logs.
     */
    @Volatile var tapDir: java.io.File? = null
    private var tapStream: java.io.FileOutputStream? = null
    private var tapBytes = 0
    private val TAP_CAP = 3_000_000

    private fun tapOpen() {
        runCatching { tapStream?.close() }
        tapBytes = 0
        tapStream = tapDir?.let {
            runCatching { java.io.FileOutputStream(java.io.File(it, "upstream.pcm")) }.getOrNull()
        }
    }

    private fun tap(b: ByteArray, len: Int) {
        val s = tapStream ?: return
        if (tapBytes >= TAP_CAP) return
        runCatching { s.write(b, 0, len); tapBytes += len }
    }

    /**
     * The last thing before the session mic opens: release whatever else holds the capture slot
     * and collect its bridge audio. Runs ON the mic thread so the wake detector records through
     * the ENTIRE session setup and dies at the final instant — the handoff hole shrinks to just
     * the AudioRecord spin-up.
     */
    var onMicAboutToOpen: (() -> Unit)? = null

    /** Splice bridge audio (16k) into the upstream, level-matched. Call before the mic captures. */
    fun seedPreroll(pcm16k: ShortArray) { if (pcm16k.isNotEmpty()) writeSeed(pcm16k, 1f) }

    /** MicHub frame counter at the last wake fire — set by the owner; -1 when unknown. */
    var wakeFireFrame: (() -> Long)? = null

    private fun writeSeed(pcmIn0: ShortArray, gain: Float) {
        // The hub ring is raw (x1); bring the seed to the same level as the live gained stream.
        val pcmIn: ShortArray
        if (gain > 1.001f) {
            pcmIn = ShortArray(pcmIn0.size)
            for (i in pcmIn0.indices) {
                val v = (pcmIn0[i] * gain).toInt()
                pcmIn[i] = when {
                    v > 30000 -> minOf(32700, 30000 + (v - 30000) / 8)
                    v < -30000 -> maxOf(-32700, -30000 + (v + 30000) / 8)
                    else -> v
                }.toShort()
            }
        } else pcmIn = pcmIn0
        // Trim dead air off both ends first. The comm-mode switch MUTES the still-running wake
        // record mid-setup, leaving ~0.7s of silence at the bridge's tail — spliced as-is, that
        // silence splits the user's sentence into two turns and the model answers the fragment.
        var end = pcmIn.size
        outer@ while (end > 320) {
            for (i in end - 320 until end) if (Math.abs(pcmIn[i].toInt()) > 250) break@outer
            end -= 320
        }
        var startIdx = 0
        outer2@ while (startIdx < end - 320) {
            for (i in startIdx until startIdx + 320) if (Math.abs(pcmIn[i].toInt()) > 250) break@outer2
            startIdx += 320
        }
        if (end - startIdx < 800) return   // nothing but silence — a seed adds nothing
        val pcm = pcmIn.copyOfRange(maxOf(0, startIdx - 320), end)
        // fade the last ~8ms to zero so the splice seam can't produce a step-discontinuity pop
        // (v2 recording had a full-scale 32767 click exactly at the seam)
        val fade = minOf(128, pcm.size)
        for (i in 0 until fade) {
            val idx = pcm.size - fade + i
            pcm[idx] = (pcm[idx] * (fade - 1 - i) / fade).toShort()
        }
        // No level-matching: bridge (wake detector) and session mic are the SAME raw route.
        // same 16k→24k linear interpolation the mic loop uses, over an arbitrary-length buffer
        val outLen = pcm.size * 3 / 2
        val out = ByteArray(outLen * 2)
        var b = 0
        for (j in 0 until outLen) {
            val pos = j * 2f / 3f
            val idx = pos.toInt()
            if (idx >= pcm.size) break
            val frac = pos - idx
            val a = pcm[idx].toInt()
            val next = if (idx + 1 < pcm.size) pcm[idx + 1].toInt() else a
            val v = (a + frac * (next - a)).toInt().coerceIn(-32768, 32767)
            out[b++] = (v and 0xff).toByte()
            out[b++] = ((v shr 8) and 0xff).toByte()
        }
        synchronized(prerollLock) { if (preroll.size() < PREROLL_CAP) preroll.write(out, 0, b) }
        Log.i("Jarvis", "preroll seeded: ${pcm.size * 1000 / 16000}ms bridged from the wake detector")
    }

    /** Inject a typed user turn: immediately if the session is live, else queued for the next open. */
    fun ask(text: String) {
        if (running && wsReady) main.post { sendAsk(text) }
        else pendingAsk = text
    }

    private fun sendAsk(text: String) {
        Log.i("Jarvis", "ask: \"$text\"")
        set { messages.add(ChatEntry(mine = true, initial = text)) }
        ws?.send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
            .put("type", "message").put("role", "user")
            .put("content", org.json.JSONArray().put(
                JSONObject().put("type", "input_text").put("text", text)))).toString())
        ws?.send(JSONObject().put("type", "response.create").toString())
    }

    /** Restart the inactivity timer; called on any speech/response activity. */
    private fun resetIdle() {
        main.removeCallbacks(idleStop)
        if (running) main.postDelayed(idleStop, cfg.idleTimeoutMs)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnects = 0
            micGated = false          // a gate from before a reconnect must not survive the new socket
            startedGated = false
            sendSessionUpdate(webSocket)
            startPlayback()
            // The mic thread has been capturing since start(); release its buffered audio, then let it
            // stream live. On the first connect this is the user's opening words; on a reconnect it's
            // whatever was said during the blip.
            flushPreroll(webSocket)
            wsReady = true
            // no greeting — just listen immediately (better UX + no overlap with the user's first ask)
            set { status = "Listening…" }
            pendingAsk?.let { q -> pendingAsk = null; main.postDelayed({ sendAsk(q) }, 600) }
        }
        override fun onMessage(webSocket: WebSocket, text: String) = handleEvent(text)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("Jarvis", "ws failure: ${t.javaClass.simpleName}: ${t.message} code=${response?.code}")
            if (!running) return
            // The panel's link blips (the WiFi stack does periodic off-channel scans), which kills
            // a long-lived TLS stream mid-answer. Don't end the conversation over that — silently
            // rebuild the socket and keep going. Only give up after several failures in a row.
            if (webSocket !== ws) return          // stale socket from an earlier attempt
            onNetworkTrouble?.invoke()            // get the watchdog probing right now
            if (reconnects < RETRY_DELAYS_MS.size) {
                val delay = RETRY_DELAYS_MS[reconnects]
                reconnects++
                Log.i("Jarvis", "ws reconnecting (attempt $reconnects/${RETRY_DELAYS_MS.size} in ${delay}ms)")
                set { status = "Reconnecting…" }
                // The mic thread keeps running across reconnects now (it buffers into preroll while
                // wsReady is false), so don't kill it — just stop streaming until the new socket opens.
                wsReady = false
                flushGen++
                playExec.execute { track?.let { runCatching { it.stop(); it.release() } }; track = null }
                main.postDelayed({ if (running) reconnect() }, delay)
            } else {
                set { status = "Connection lost — say “Hey Jarvis”" }
                stop()
            }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (running) set { status = "Closed: $reason" }
        }
    }

    private fun reconnect() {
        wsReady = false
        val req = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=${modelProvider()}")
            .header("Authorization", "Bearer ${Env["OPENAI_API_KEY"]}")
            .build()
        ws = client.newWebSocket(req, listener)
    }

    private fun sendSessionUpdate(webSocket: WebSocket) {
        // GA schema: audio nested under input/output, session.type = realtime
        val input = JSONObject()
            // The API's minimum input rate is 24kHz. AEC3 runs at its native 16kHz — that's where it
            // cancels best — so the cleaned mic is upsampled to 24kHz on the way out.
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", outRate))
            .put("turn_detection", turnDetection())
            .put("transcription", JSONObject().put("model", "gpt-4o-transcribe"))
            // NO server-side noise_reduction. far_field/near_field DESTROY non-English speech on
            // this device: our upstream is already Fluence-AEC + on-device NS cleaned and x6-gained,
            // so OpenAI's NR double-processes it into garbage. Measured 2026-07-15 with the exact
            // "明天有世界杯吗" capture: far_field → whisper heard "You can't restrict them all" and the
            // model hallucinated get_weather(San Francisco); NR OFF → both mini and full transcribe
            // "明天有谁比赛吗?" perfectly and full web_searches it. whisper-1 → gpt-4o-transcribe for
            // far better Mandarin (client-side stop-word matching rides on this transcript).
        val output = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", outRate))
            .put("voice", voiceProvider())
        val session = JSONObject()
            .put("type", "realtime")
            .put("output_modalities", org.json.JSONArray().put("audio"))
            // rebuilt fresh each session so "today" is actually today
            .put("instructions", Prompt.build(cfg))
            .put("audio", JSONObject().put("input", input).put("output", output))
            .put("tools", Tools.schema())
        webSocket.send(JSONObject().put("type", "session.update").put("session", session).toString())
    }

    private fun handleEvent(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = o.optString("type")
        if (!type.endsWith(".delta")) {
            // log full JSON for non-delta events (may carry error/status detail)
            Log.i("Jarvis", "evt $type ${if (type.contains("error") || type.contains("failed")) text.take(300) else ""}")
        }
        when (type) {
            "response.created" -> {
                respActive = true
                duckCount = 0
                // Jarvis is about to speak → gate the mic so it can't hear itself
                micGated = true
                // No fixed "ungate safety" here: a long answer legitimately stays gated for its
                // whole playback (an 8s cap once ungated mid-poem and misrouted an interruption).
                // The starve watcher ends the gate, and its 60s hard cap covers true stalls.
                Log.i("Jarvis", "mic GATED (Jarvis responding)")
                // the card caption shows THIS reply, not the whole session's transcript
                set { assistantText = "" }
            }
            "input_audio_buffer.speech_started" -> {
                startedGated = micGated   // classify the turn by its ONSET, not its commit moment
                // The server's VAD heard speech while our speaker is (or may still be) audible.
                // Echo residual rides at x1 and cannot trip the VAD — this is a REAL voice, so
                // duck immediately: the speaker goes silent (releasing the DSP's doubletalk
                // suppressor) and the upstream gain switches on, so the REST of the utterance
                // arrives clean and level-continuous instead of chopped at the gate seam.
                if (micGated && !hardMuted) {
                    speechInFlight = true   // mic loop keeps extending the duck until the turn ends
                    duckUntil = android.os.SystemClock.elapsedRealtime() + 2000
                    Log.i("Jarvis", "VAD speech over playback → ducking speaker until the turn ends")
                }
                // A ringing timer dies at the first sound of a voice — nobody addresses a ringing
                // alarm except to silence it, and waiting for a transcribed "stop" loses the race
                // against the next chime burst (the DSP's echo suppressor clamps the mic during it).
                if (com.avera.jarvis.tools.TimerManager.ringing) {
                    Log.i("Jarvis", "voice heard while ringing → dismissing timer")
                    main.post { com.avera.jarvis.tools.TimerManager.dismissRing() }
                }
                // NOTE: no resetIdle here. Raw speech onset must NOT keep the session alive —
                // an open mic in a lived-in room hears plenty of speech that isn't addressed to
                // Jarvis (the user on the phone, people chatting), and resetting the idle timer
                // on every sound made sessions immortal: he answered room chatter forever.
                // Only COMMITTED turns and responses extend the conversation.
                // While Jarvis SPEAKS, sound does NOTHING here — no truncation, no flush. A
                // bumped laptop or room chatter cannot cut him off; only "Hey Jarvis", a stop
                // phrase in the transcript, or a tap interrupts (the design that survives a
                // real room). When he's quietly listening, speech just clears the screen.
                if (!micGated) {
                    if (framesWritten > playedFrames()) flushPlayback()
                    set { speaking = false; status = "Listening…"; card = null }
                }
            }
            "response.output_item.added" -> {
                // remember the function name for this call_id (the .done event may omit it)
                val item = o.optJSONObject("item")
                if (item?.optString("type") == "function_call")
                    pendingFn[item.optString("call_id")] = item.optString("name")
            }
            "response.function_call_arguments.done" -> {
                val callId = o.optString("call_id")
                val fname = o.optString("name").ifEmpty { pendingFn.remove(callId).orEmpty() }
                val tool = Tools.byName(fname)
                if (tool == null) {
                    Log.w("Jarvis", "model called unknown tool '$fname'")
                    sendFunctionResult(callId, "That tool doesn't exist.")
                    return
                }
                val args = runCatching { JSONObject(o.optString("arguments")) }.getOrElse { JSONObject() }
                Log.i("Jarvis", "tool $fname(${args.toString().take(140)})")
                if (tool.fast) {
                    tool.run(args, toolHost)?.let { sendFunctionResult(callId, it) }
                    return
                }
                Thread {
                    val out = try { tool.run(args, toolHost) } catch (e: Exception) {
                        Log.e("Jarvis", "tool $fname crashed", e)
                        "The $fname tool failed. Tell the user, briefly."
                    }
                    out?.let { sendFunctionResult(callId, it) }
                }.also { it.isDaemon = true; it.start() }
            }
            "input_audio_buffer.speech_stopped" -> { speechInFlight = false }
            "input_audio_buffer.committed" -> {
                speechInFlight = false
                resetIdle()
                // We drive responses: only a turn that BEGAN while Jarvis was quiet gets answered
                // here. Turns that began over the speaker (even if they commit a beat after the
                // gate clears — his "Stop." did exactly that) are routed by their transcript
                // below: bare stop = silence, real question = late answer, junk = ignored.
                val itemId = o.optString("item_id")
                val gated = micGated || startedGated
                turnEcho[itemId] = gated
                if (!gated) ws?.send(JSONObject().put("type", "response.create").toString())
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = o.optString("transcript").trim()
                Log.i("Jarvis", "USER heard: \"$transcript\"  (gated=${turnEcho[o.optString("item_id")] ?: micGated})")
                // A ringing timer while the session is live: the session owns the one capture
                // slot, so the timer's own gap-listener can't run — "stop" arrives HERE instead.
                if (com.avera.jarvis.tools.TimerManager.ringing &&
                    (STOP_LOOSE.containsMatchIn(transcript) || STOP_PHRASE_ZH.containsMatchIn(transcript))) {
                    Log.i("Jarvis", "ring dismissed by voice via session (\"$transcript\")")
                    main.post { com.avera.jarvis.tools.TimerManager.dismissRing() }
                }
                val itemId2 = o.optString("item_id")
                val gated = turnEcho.remove(itemId2) ?: micGated
                // A bare stop command silences Jarvis NO MATTER when it was captured. The model
                // must never get to answer one ("Stop, stop." was drawing a spoken reply): kill
                // the playback and any in-flight response right here, client-side.
                if (STOP_PHRASE.matches(transcript) || STOP_PHRASE_ZH.matches(transcript)) {
                    Log.i("Jarvis", "bare stop (\"$transcript\") → silence")
                    resetIdle()
                    main.post { silenceNow() }
                } else if (gated) {
                    if (STOP_LOOSE.containsMatchIn(transcript) || STOP_PHRASE_ZH.containsMatchIn(transcript)) {
                        // stop word riding inside a longer talk-over ("stop, 我问你的是...")
                        Log.i("Jarvis", "stop heard mid-speech (\"$transcript\") → interrupting")
                        main.post { gatedFrags = ""; main.removeCallbacks(gatedFragEval); silenceNow() }
                    } else if (transcript.isNotBlank()) main.post {
                        // A REAL utterance that began over the speaker (echo can't commit turns —
                        // the DSP cancels it). The VAD chops talk-over speech into fragments
                        // ("What time?" / "California."), so single pieces can't be judged alone:
                        // collect fragments for 1.2s after the last one, then evaluate the WHOLE.
                        // The items are already in the conversation, so answering is just one
                        // response.create — the model reads all of them together.
                        gatedFrags = "$gatedFrags $transcript".trim()
                        main.removeCallbacks(gatedFragEval)
                        main.postDelayed(gatedFragEval, 1200)
                    }
                } else set {
                    userText = transcript
                    if (transcript.isNotBlank()) messages.add(ChatEntry(mine = true, initial = transcript))
                }
            }
            "response.output_audio_transcript.delta" -> {
                transcriptChunks++
                val d = o.optString("delta")
                set {
                    assistantText += d
                    val a = curAssistant ?: ChatEntry(mine = false, initial = "")
                        .also { curAssistant = it; messages.add(it) }
                    a.text += d
                }
            }
            "response.output_audio.delta" -> {
                audioChunks++
                Log.i("Jarvis", "AUDIO recv #$audioChunks (arrival)")
                set { speaking = true; status = "Jarvis is speaking…" }
                val b64 = o.optString("delta")
                if (b64.isNotEmpty()) {
                    val pcm = Base64.decode(b64, Base64.NO_WRAP)
                    val gen = flushGen   // captured at enqueue: a flush orphans this chunk
                    playExec.execute {
                        if (gen != flushGen) return@execute   // audio from before an interruption
                        // Pace the writes: the whole answer arrives in a ~2s burst, and volume is
                        // baked into the samples — an unbounded track queue meant a rocker press
                        // couldn't be heard for seconds. Cap what's in the track at ~300ms; the
                        // rest waits here, so gain is applied moments before it reaches the ear.
                        var waited = 0
                        while (running && gen == flushGen) {
                            if (framesWritten - playedFrames() < outRate * 3 / 10) break
                            if (waited >= 4000) {   // belt-and-suspenders; the clock makes this unreachable
                                Log.w("Jarvis", "pacing wait stuck — writing anyway")
                                break
                            }
                            try { Thread.sleep(40); waited += 40 } catch (_: Exception) { break }
                        }
                        if (gen != flushGen) return@execute
                        // Software volume: scale the PCM in place. The device's amp ignores
                        // stream/track volume (mixer shows the attenuation but the speaker stays
                        // loud), so gain goes into the samples themselves. Duck-on-voice folds in
                        // here: full SILENCE while someone talks over him — anything quieter only
                        // partly releases the DSP suppressor and their voice stays half-buried.
                        val g = outGain *
                            (if (android.os.SystemClock.elapsedRealtime() < duckUntil) 0f else 1f)
                        if (g < 0.995f) {
                            var i = 0
                            while (i + 1 < pcm.size) {
                                var s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
                                if (s > 32767) s -= 65536
                                val v = (s * g).toInt().coerceIn(-32768, 32767)
                                pcm[i] = (v and 0xff).toByte()
                                pcm[i + 1] = ((v shr 8) and 0xff).toByte()
                                i += 2
                            }
                        }
                        // stream restarting after a drain? mark the modeled playhead's epoch
                        if (speakerFrames() >= framesWritten) {
                            epochFrames = framesWritten
                            epochTime = android.os.SystemClock.elapsedRealtime()
                        }
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        runCatching { track?.let {
                            it.write(pcm, 0, pcm.size); framesWritten += pcm.size / 2
                            lastWriteAt = android.os.SystemClock.elapsedRealtime()
                        } }
                        val wrote = android.os.SystemClock.elapsedRealtime() - t0
                        val head = playedFrames()
                        val behindMs = ((framesWritten - head) * 1000 / outRate)
                        if (audioChunks <= 3 || audioChunks % 25 == 0)
                            Log.i("Jarvis", "audio: chunk=$audioChunks write=${wrote}ms head=$head " +
                                    "queued=${behindMs}ms")
                        // sample the output chunk's peak → drive the orb waves while Jarvis speaks
                        var m = 0; var k = 0
                        while (k + 1 < pcm.size) {
                            val s = (pcm[k].toInt() and 0xff) or (pcm[k + 1].toInt() shl 8)
                            val a = if (s < 0) -s else s
                            if (a > m) m = a
                            k += 32
                        }
                        val norm = (m / 6000f).coerceIn(0f, 1f)
                        main.post { level = maxOf(norm, level * 0.82f) }
                    }
                }
            }
            "response.done" -> {
                respActive = false
                // Chatter escape: a stillborn response (0 audio) means something spoke over its
                // birth. Once = a real interruption. Twice in a row = the room is having a
                // conversation that isn't with Jarvis — bow out instead of fighting for a word
                // (an open mic near a phone call otherwise "loops" forever).
                if (audioChunks == 0) {
                    if (++stillborns >= 2) {
                        Log.i("Jarvis", "two stillborn responses — room chatter, closing session")
                        set { status = "…I'll leave you to it." }
                        main.postDelayed({ stop() }, 400)
                        return
                    }
                } else stillborns = 0
                Log.i("Jarvis", "RESPONSE DONE ✓  audioChunks=$audioChunks transcriptChunks=$transcriptChunks  JARVIS said: \"${assistantText.trim()}\"")
                audioChunks = 0; transcriptChunks = 0
                resetIdle()
                main.removeCallbacks(ungateSafety)
                // Re-open the mic only once the speaker has TRULY finished. Poll the playback head
                // instead of estimating once — a short estimate opens the mic while the tail is still
                // playing, and in half-duplex that tail is exactly what self-triggers a new turn.
                val gateGen = ++ungateGen
                Thread {
                    // PHYSICS-BASED gate clear — no clocks, no counters, no models. The speaker
                    // can only make sound if we are feeding it bytes; every clock-based version
                    // of this gate dropped mid-sentence when a network stall desynced the model
                    // from reality (his voice then poured into an "ungated" turn and the policy
                    // answered it — the self-talk loop). The gate clears only after the track has
                    // been STARVED OF WRITES for 1.3s: provably ≥1s of speaker silence.
                    var guard = 0
                    while (running && gateGen == ungateGen) {
                        val quiet = android.os.SystemClock.elapsedRealtime() - lastWriteAt
                        if (quiet > 1300 && !respActive) break
                        try { Thread.sleep(100) } catch (_: Exception) { break }
                        if (++guard > 600) break            // 60s hard cap
                    }
                    if (running && gateGen == ungateGen) main.post {
                        micGated = false
                        Log.i("Jarvis", "speaker starved 1.3s — mic no longer gated as echo")
                        if (running) { speaking = false; status = "Listening… (follow up any time)" }
                    }
                }.also { it.isDaemon = true; it.start() }
                set { curAssistant = null }
            }
            "error" -> {
                val code = o.optJSONObject("error")?.optString("code")
                if (code == "response_cancel_not_active" ||
                    code == "conversation_already_has_active_response")
                    // benign timing races around barge-in — never worth alarming the user
                    Log.w("Jarvis", "benign api race: $code")
                else
                    set { status = "API error: ${o.optJSONObject("error")?.optString("message")}" }
            }
        }
    }

    private fun sendFunctionResult(callId: String, output: String) {
        ws?.send(JSONObject().put("type", "conversation.item.create")
            .put("item", JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", output))
            .toString())
        ws?.send(JSONObject().put("type", "response.create").toString())
    }

    private fun startPlayback() {
        if (track != null) return          // reconnects reuse the session's track
        val minOut = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)   // plain media path; no routing dominoes
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(outRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            // ~3s jitter buffer. The model emits speech faster than real time, so this fills ahead
            // and playback keeps flowing through the multi-second stalls the WiFi stack causes when
            // it takes the radio off-channel to scan. At 0.5s it underran and the voice stuttered.
            .setBufferSizeInBytes(maxOf(minOut, outRate * 2 * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
        // Prime with ~100ms of silence so the output is truly ACTIVE before audio arrives.
        // Counted in framesWritten so the drain-poll's head-position comparison stays exact.
        track?.write(ShortArray(outRate / 10), 0, outRate / 10)
        framesWritten = (outRate / 10).toLong()
        // EVERYTHING that tells time about this track restarts with it. A stale epoch from the
        // previous track/session made the modeled playhead saturate → pacing thought the queue
        // was empty (2s piled up) and the AEC3 reference ran at write-position → cancellation
        // gutted in EVERY SESSION AFTER THE FIRST (fresh-process regressions passed; the user's
        // real second session always looped).
        epochFrames = 0L
        epochTime = android.os.SystemClock.elapsedRealtime()
        farEnd.reset()
    }

    /** Bumped on every flush/teardown; queued write tasks compare and bail. */
    @Volatile private var flushGen = 0

    private fun flushPlayback() {
        // "Flush" = ABANDON, never destroy. Orphan every queued chunk (gen bump) — the ≤300ms
        // already inside the track plays out and the speaker goes quiet. The track is never
        // paused/flushed/released mid-session, because each of those roads led somewhere bad:
        // pause/flush/play wedges this HAL's head counter at 0 forever; release+recreate resets
        // AEC3's far-end timeline, and an echo canceller needs 1-2s to reconverge — it leaked
        // full-level echo meanwhile, the leak triggered the next truncation, and the session
        // spiraled into answering its own ghost ("really really bad echo", 8 responses/40s).
        // Teams/Zoom survive on this hardware the same way: ONE continuous playback stream and
        // ONE continuously-converged software canceller for the whole call.
        ++flushGen
    }

    /**
     * Microphone → Qualcomm Fluence DSP echo canceller → API.
     *
     * Capture is 16kHz mono from VOICE_COMMUNICATION with the AEC effect attached — that exact
     * pair flips `enable_aec` in the HAL, which selects the _AEC ACDB tuning and wires the EC_REF
     * loopback from the speaker into the ADSP canceller (EchoLab-measured: echo lands 16-19dB
     * BELOW the room's noise floor; the old VOICE_RECOGNITION source is the one path where the
     * ROM disables Fluence, and it heard the speaker at +18dB). NoiseSuppressor is deliberately
     * NOT attached: Fluence NS mangles far-field speech ("brown fox" → "round clock" in Whisper)
     * and OpenAI runs its own far-field noise reduction server-side.
     *
     * With the DSP canceller in-path the loop is FULL DUPLEX: the mic streams even while Jarvis
     * speaks. His own voice can't reach the server (cancelled at the DSP), so it can never be
     * transcribed or answered — the anti-loop guarantee is in silicon, not in gate code.
     */
    private fun startMic() {
        recordThread = Thread {
            val hubQ = java.util.concurrent.ArrayBlockingQueue<ShortArray>(32)
            val sinkFirstFrame = java.util.concurrent.atomic.AtomicLong(-1L)
            val hubSink: (ShortArray) -> Unit = { f ->
                sinkFirstFrame.compareAndSet(-1L, MicHub.frameCount)
                hubQ.offer(f.copyOf())
            }
            // Sink attaches FIRST — live capture is secured before anything else happens, so
            // the handoff can never drop audio again (measured: the old stop-wake-then-attach
            // order left a ~500ms unowned hole that ate "what is the" from a wake question).
            MicHub.addSink(hubSink)
            onMicAboutToOpen?.invoke()   // now stop the wake detector's consumption
            // Seed: from 320ms before the wake fire up to our sink's first frame, cut from the
            // hub's rolling ring on ONE frame timeline — gapless and duplication-free.
            val fire = wakeFireFrame?.invoke() ?: -1L
            if (fire >= 0 && MicHub.frameCount - fire < 500) {   // a RECENT fire, not a reconnect
                var waited = 0
                while (sinkFirstFrame.get() < 0 && waited < 40) {
                    try { Thread.sleep(5) } catch (_: Exception) {}; waited += 5
                }
                val until = sinkFirstFrame.get().let { if (it >= 0) it else MicHub.frameCount }
                val seed = MicHub.snapshotRange(fire - 32, until)
                if (seed.isNotEmpty()) writeSeed(seed, micGain)
            }
            Log.i("Jarvis", "mic sink attached (hub: comm route, Fluence DSP AEC, always-warm)")

            val chunkFrames = Aec.FRAME                       // 10ms frames
            val pending = ByteArray(UP_FRAME * 2 * SEND_CHUNKS)
            var pendingLen = 0
            val bargeChunk = ShortArray(1280)                 // 80ms, what the wake model wants
            var bargeFill = 0
            var frames = 0L
            var levelPeak = 0

            // identity check: a reconnect spawns a fresh mic thread; this one must unsubscribe
            // as soon as it is no longer the current one, so frames flow to the new sink only
            while (running && Thread.currentThread() === recordThread) {
                val mono = hubQ.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                // Echo cancellation happens in the DSP before samples ever reach this loop —
                // no software AEC here (AEC3's software doubletalk suppressor once ate the
                // user's "stop"; the Fluence hardware path replaced it wholesale).

                // Fixed x6 make-up gain while the speaker is quiet (or duck-silenced); x1 while
                // it plays. The comm path records ~11x quieter than the old raw route (phone-at-
                // mouth TX calibration); x6 restores VAD-visible levels without ever clipping.
                // Gated audio rides at x1 because boosted echo residual trips the server VAD
                // (harness-measured: hallucinated turns at x6, dead silent at x1).
                val gainNow = if (!micGated ||
                    android.os.SystemClock.elapsedRealtime() < duckUntil) micGain else 1f
                if (gainNow > 1.001f) {
                    for (i in 0 until chunkFrames) {
                        mono[i] = (mono[i] * gainNow).toInt().coerceIn(-32700, 32700).toShort()
                    }
                }

                var maxAmp = 0
                for (v in mono) {
                    val a = Math.abs(v.toInt())
                    if (a > maxAmp) maxAmp = a
                }

                // Duck-on-voice: someone is talking over Jarvis. The DSP's echo suppressor clamps
                // the mic hardest exactly when the speaker is loud (that's why interrupting only
                // worked "sometimes" — it depended on landing in his pauses). Energy alone can't
                // CONFIRM speech (a TV would false-interrupt), but it's a safe hint to duck the
                // speaker: less far-end → the suppressor opens → their voice reaches the server at
                // normal volume and the real stop/VAD path finishes the job. Wrong hint = he's
                // briefly quieter, nothing else. His own cancelled echo idles at maxAmp ~8-22;
                // 350 is far above it.
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (speechInFlight) duckUntil = maxOf(duckUntil, nowMs + 400)
                if (micGated && !hardMuted && nowMs >= duckUntil) {
                    // Leaky integrator, not consecutive-frames: real speech has syllable gaps
                    // that reset a strict counter (a full question over the poem never fired
                    // it). Hot frames count +2, quiet ones -1 — sustained talking accumulates,
                    // isolated echo-residual bursts drain away.
                    duckHot = if (maxAmp > DUCK_AMP) minOf(duckHot + 3, 60) else maxOf(duckHot - 1, 0)
                    if (duckHot >= 30 && duckCount < 2) {
                        duckCount++
                        duckHot = 0
                        duckUntil = nowMs + 1800
                        Log.i("Jarvis", "sustained voice over playback (amp=$maxAmp) → ducking speaker ($duckCount/2)")
                    }
                } else if (nowMs >= duckUntil) duckHot = 0
                // (no software residual gate needed: the DSP suppressor crushes everything
                // near-end while the speaker plays — it IS the gate)

                // "Hey Jarvis" mid-answer = instant local interrupt (~200ms), the fast path next to
                // the stop-phrase transcription path (~1-2s). Runs ONLY while he's speaking, and the
                // inference happens off-thread with chunk-dropping — inline inference here once
                // starved the socket reader enough to miss pongs and drop the session mid-answer.
                if (micGated && !hardMuted && bargeCheck != null) {
                    // normalize to the wake model's expected level: gated frames ride at x1
                    val bargeBoost = if (gainNow > 1.001f) 1 else 10
                    for (v in mono) {
                        bargeChunk[bargeFill++] = (v * bargeBoost).coerceIn(-32700, 32700).toShort()
                        if (bargeFill == bargeChunk.size) {
                            bargeFill = 0
                            if (!bargeBusy) {
                                bargeBusy = true
                                val copy = bargeChunk.copyOf()
                                bargeExec.execute {
                                    val hit = runCatching { bargeCheck?.invoke(copy) == true }.getOrDefault(false)
                                    bargeBusy = false
                                    if (hit) main.post { interrupt() }
                                }
                            }
                        }
                    }
                } else bargeFill = 0

                // FULL DUPLEX: the mic streams even while Jarvis speaks. The DSP canceller
                // removes his voice before it reaches this loop (measured 16-19dB below the
                // room's noise floor — the server hears silence where the speaker is), so echo
                // can never be committed as a turn. The turn policy stays as a second wall:
                // turns committed while the speaker plays are only scanned for stop phrases,
                // never auto-answered.
                val mayStream = !hardMuted && !debugNoUpstream
                if (mayStream) {
                    // 16k → 24k (the API's minimum rate): 2 samples in, 3 out, linear interpolation.
                    var b = pendingLen
                    for (j in 0 until UP_FRAME) {
                        val pos = j * 2f / 3f
                        val idx = pos.toInt()
                        val frac = pos - idx
                        val a = mono[idx].toInt()
                        val next = if (idx + 1 < mono.size) mono[idx + 1].toInt() else a
                        val v = (a + frac * (next - a)).toInt().coerceIn(-32768, 32767)
                        pending[b++] = (v and 0xff).toByte()
                        pending[b++] = ((v shr 8) and 0xff).toByte()
                    }
                    pendingLen = b
                    if (pendingLen >= pending.size) {        // ~100ms per websocket message
                        if (wsReady) {
                            ws?.send(JSONObject().put("type", "input_audio_buffer.append").put("audio",
                                Base64.encodeToString(pending, 0, pendingLen, Base64.NO_WRAP)).toString())
                            tap(pending, pendingLen)
                        } else {
                            // Socket not ready (connecting or reconnecting): buffer instead of dropping.
                            // Flushed to the API the instant it opens (flushPreroll), so no words are lost.
                            synchronized(prerollLock) {
                                if (preroll.size() < PREROLL_CAP) preroll.write(pending, 0, pendingLen)
                            }
                        }
                        pendingLen = 0
                    }
                } else pendingLen = 0

                // The loop now ticks every 10ms (AEC3's frame). Driving the orb from every tick meant
                // 100 recompositions a second and half a core on the main thread — throttle to 10Hz,
                // which is well past what the eye resolves on a breathing orb.
                if (maxAmp > levelPeak) levelPeak = maxAmp
                frames++
                if (frames % 10 == 0L) {
                    val micNorm = (levelPeak / 6000f).coerceIn(0f, 1f)
                    levelPeak = 0
                    main.post {
                        level = if (micGated) level * 0.9f else maxOf(micNorm, level * 0.8f)
                    }
                }
                if (frames % 500 == 0L)
                    Log.i("Jarvis", "mic frames=$frames maxAmp=$maxAmp speaking=$micGated " +
                        "head=${headFrames()} spk=${speakerFrames()} written=$framesWritten")
            }
            MicHub.removeSink(hubSink)
        }.also { it.isDaemon = true; it.start() }
    }

    private inline fun set(crossinline block: () -> Unit) { main.post { block() } }
}
