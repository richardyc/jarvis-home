package com.avera.jarvis

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log

/**
 * The ONE microphone stream for the whole app, opened at boot and never closed.
 *
 * Why it exists: this HAL has a single capture slot, and the wake→session handoff used to close
 * one AudioRecord and open another. EchoLab measured what that costs — a ~160ms dead gap, plus
 * the killer: the Fluence DSP suppresses the first ~2.25 SECONDS of every freshly-opened
 * comm-route capture (~40dB) while it converges. A question spoken right after "Hey Jarvis"
 * landed exactly inside that window, which is why its first words arrived crushed or missing.
 * With one stream that never closes, the convergence cost is paid once at boot and never again.
 *
 * Config: VOICE_COMMUNICATION + AcousticEchoCanceler — the only capture source where this ROM
 * enables the Fluence DSP echo canceller (see thinksmart memory / EchoLab measurements). All
 * consumers therefore hear an echo-cancelled room.
 *
 * Consumers subscribe as sinks and each receives every 10ms/160-sample mono 16kHz frame on the
 * hub thread. Sinks must be fast (copy/enqueue, no I/O) and must tolerate frames they don't
 * want (check your own state). The frame array is reused — COPY it if you keep it.
 */
object MicHub {
    const val RATE = 16000
    const val FRAME = 160   // 10ms

    private val sinks = java.util.concurrent.CopyOnWriteArrayList<(ShortArray) -> Unit>()
    @Volatile private var running = false
    private var thread: Thread? = null

    // Rolling history of the last RING_FRAMES frames (~4s), indexed by a monotonic frame counter.
    // This is how the wake→session seed is cut: by FRAME NUMBER on one continuous timeline, so
    // the splice can neither drop nor duplicate audio (the old detector-owned bridge lost ~500ms
    // between "wake stops listening" and "session sink attaches").
    private const val RING_FRAMES = 400
    private val ring = ShortArray(RING_FRAMES * FRAME)
    private val ringLock = Any()
    @Volatile var frameCount = 0L
        private set

    fun addSink(s: (ShortArray) -> Unit) { sinks.addIfAbsent(s) }
    fun removeSink(s: (ShortArray) -> Unit) { sinks.remove(s) }

    /** Frames [fromFrame, toFrame) from the rolling history, clamped to what's still buffered. */
    fun snapshotRange(fromFrame: Long, toFrame: Long): ShortArray {
        synchronized(ringLock) {
            val newest = frameCount
            val oldest = maxOf(0L, newest - RING_FRAMES)
            val a = maxOf(fromFrame, oldest)
            val b = minOf(toFrame, newest)
            if (b <= a) return ShortArray(0)
            val out = ShortArray(((b - a) * FRAME).toInt())
            var o = 0
            for (f in a until b) {
                val idx = ((f % RING_FRAMES) * FRAME).toInt()
                System.arraycopy(ring, idx, out, o, FRAME)
                o += FRAME
            }
            return out
        }
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.also { it.isDaemon = true; it.name = "mic-hub"; it.start() }
    }

    /**
     * Routing anchor. Measured on-device: a VOICE_COMMUNICATION capture with NO output stream
     * open reads digital silence (rms ~1.6 — the HAL parks the input path when there is no RX
     * to reference). A permanently-open track fed silence keeps the route alive; it costs
     * nothing audible and the session's real playback simply mixes on top.
     */
    private var anchor: AudioTrack? = null
    private val anchorZeros = ShortArray(480)   // 10ms @48k — one hub frame of silence

    private fun openAnchor() {
        if (anchor != null) return
        anchor = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(19200)   // 200ms cushion for mic/speaker clock drift
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        } catch (e: Exception) { Log.e("Jarvis", "MicHub anchor failed: $e"); null }
    }

    private fun open(): Pair<AudioRecord, AcousticEchoCanceler?>? {
        openAnchor()   // BEFORE the record starts, so the input path routes against a live RX
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (attempt in 0 until 40) {
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, RATE))   // ≥0.5s so slow sinks can never underrun the HAL
            } catch (e: Exception) { null }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                val aec = try {
                    AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
                } catch (e: Exception) { null }
                rec.startRecording()
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    Log.i("Jarvis", "MicHub open: Fluence AEC ${if (aec?.enabled == true) "ON" else "MISSING"} (attempt ${attempt + 1})")
                    return rec to aec
                }
                runCatching { aec?.release() }
            }
            runCatching { rec?.release() }
            try { Thread.sleep(150) } catch (_: Exception) { return null }
        }
        Log.e("Jarvis", "MicHub: capture slot never became available")
        return null
    }

    private fun loop() {
        while (running) {
            val (rec, aec) = open() ?: return
            val frame = ShortArray(FRAME)
            var dead = 0
            while (running) {
                var got = 0
                while (got < FRAME && running) {
                    val n = rec.read(frame, got, FRAME - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME) {
                    // read() failing repeatedly = the stream died under us (audioserver crash,
                    // policy change). Reopen — costs one soft-start, but silence costs the app.
                    if (++dead > 50) { Log.w("Jarvis", "MicHub stream died — reopening"); break }
                    try { Thread.sleep(20) } catch (_: Exception) {}
                    continue
                }
                dead = 0
                // one frame of silence out per frame in: keeps the anchor stream (and with it
                // the capture route) out of standby forever, paced by the mic clock
                anchor?.let { runCatching { it.write(anchorZeros, 0, anchorZeros.size) } }
                synchronized(ringLock) {
                    System.arraycopy(frame, 0, ring, ((frameCount % RING_FRAMES) * FRAME).toInt(), FRAME)
                    frameCount++
                }
                for (s in sinks) try { s(frame) } catch (e: Exception) {
                    Log.e("Jarvis", "MicHub sink crashed — removing it", e)
                    sinks.remove(s)
                }
            }
            runCatching { aec?.release() }
            runCatching { rec.stop(); rec.release() }
        }
    }
}
