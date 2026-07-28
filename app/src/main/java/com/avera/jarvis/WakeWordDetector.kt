package com.avera.jarvis

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * "Hey Jarvis" wake word via openWakeWord (3 ONNX models) on ONNX Runtime.
 * Pipeline (verified in Python: hey=0.998, negative=0.001):
 *   16kHz audio → melspectrogram(1760 samples → 8 frames, /10+2) → rolling mel buffer
 *   → embedding(last 76 mel frames → 96) each 80ms → rolling 16-embedding buffer
 *   → wakeword(16×96 → score). Fires onWake() when score ≥ threshold.
 */
class WakeWordDetector(
    private val context: Context,
    private val threshold: Float,
    private val onWake: () -> Unit
) {
    private val SR = 16000
    private val CHUNK = 1280      // 80ms
    private val CONTEXT = 480     // mel overlap context (3 hops)
    private val MEL_BINS = 32
    private val EMB_WIN = 76      // mel frames per embedding
    private val WW_WIN = 16       // embeddings per score

    private val env = OrtEnvironment.getEnvironment()
    private var melS: OrtSession? = null
    private var embS: OrtSession? = null
    private var wwS: OrtSession? = null

    @Volatile private var running = false
    private var thread: Thread? = null

    private val prevTail = ShortArray(CONTEXT)
    private var haveTail = false
    companion object { private var selfTested = false }
    private val melBuf = ArrayDeque<FloatArray>()
    private val embBuf = ArrayDeque<FloatArray>()
    private var cooldown = 0

    // energy gate — see loop()
    private var noiseFloor = 30f
    private var lastSoundMs = 0L
    private val MIN_GATE_RMS = 12f      // measured: silent room ≈ 2–8 RMS, speech ≈ 90–150
    private val HANGOVER_MS = 2500L     // keep inferring this long after sound stops

    // Bridge audio for the mic handoff (one capture slot: this record must die before the
    // session's can start). Two pieces: a small pre-trigger ring (the wake model fires a few
    // hundred ms after "Jarvis" ends, so the ring holds the start of a continuous question),
    // and EVERYTHING after the trigger — on wake the loop drops inference and keeps recording
    // until stop(), so the session's setup time (mode IPC, track creation…) costs no audio.
    private val RING_CHUNKS = 12        // 12 × 80ms = 960ms
    private val ring = ArrayDeque<ShortArray>()
    @Volatile private var captureOnly = false
    private var preTail: ShortArray? = null
    private val postTrigger = ArrayList<ShortArray>()

    /** MicHub frame counter at the moment the wake word fired — the session cuts its seed from
     *  the hub's ring using this timestamp (the detector's own buffers are no longer spliced). */
    @Volatile var lastFireFrame = -1L
        private set

    /** The bridge: ~320ms pre-trigger + everything captured until stop(). Call after stop(). */
    fun tail(): ShortArray? {
        val pre = preTail ?: return null
        preTail = null
        val post = postTrigger
        Log.i("Jarvis", "wake tail: pre=${pre.size * 1000 / 16000}ms post=${post.size * 80}ms (${post.size} chunks)")
        val flat = ShortArray(pre.size + post.sumOf { it.size })
        System.arraycopy(pre, 0, flat, 0, pre.size)
        var o = pre.size
        for (c in post) { System.arraycopy(c, 0, flat, o, c.size); o += c.size }
        post.clear()
        return flat
    }

    /**
     * These models are tiny; ONNX Runtime's default thread pool spins up a worker per core and the
     * oversubscription costs far more than it saves — it was burning ~45% of the CPU on this quad
     * A35 to run 80 ms of audio every 80 ms. One thread each is plenty and runs cool.
     */
    private fun opts() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }

    /**
     * Feed mode: load the models but run no mic loop — audio is pushed in from elsewhere.
     * Used for barge-in, where the live session already owns the microphone.
     */
    fun openForFeeding() {
        if (melS != null) return
        melS = env.createSession(context.assets.open("oww/melspectrogram.onnx").readBytes(), opts())
        embS = env.createSession(context.assets.open("oww/embedding_model.onnx").readBytes(), opts())
        wwS = env.createSession(context.assets.open("oww/hey_jarvis_v0.1.onnx").readBytes(), opts())
        haveTail = false; melBuf.clear(); embBuf.clear()
    }

    /** Push one 80ms / 1280-sample 16kHz mono chunk. Returns true if the wake word fired. */
    fun feed(chunk: ShortArray): Boolean {
        if (melS == null) return false
        val score = try { processChunk(chunk, listening = true) } catch (e: Exception) {
            Log.e("Jarvis", "barge-in inference failed: $e"); return false
        }
        return score >= threshold
    }

    fun reset() { haveTail = false; melBuf.clear(); embBuf.clear() }

    fun start() {
        if (running) return
        melS = env.createSession(context.assets.open("oww/melspectrogram.onnx").readBytes(), opts())
        embS = env.createSession(context.assets.open("oww/embedding_model.onnx").readBytes(), opts())
        wwS = env.createSession(context.assets.open("oww/hey_jarvis_v0.1.onnx").readBytes(), opts())
        haveTail = false; melBuf.clear(); embBuf.clear(); cooldown = 0
        if (!selfTested) { selfTest(); selfTested = true }  // once: verify pipeline vs bundled hey.wav
        haveTail = false; melBuf.clear(); embBuf.clear(); cooldown = 0
        captureOnly = false; postTrigger.clear(); preTail = null
        hubQ.clear(); hubFill = 0
        running = true
        MicHub.addSink(hubSink)
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    /** Feed the bundled, known-good hey.wav through the pipeline to verify the Kotlin port. */
    private fun selfTest() {
        val bytes = context.assets.open("oww/hey_test.wav").readBytes()
        val n = (bytes.size - 44) / 2
        val pad = 16000
        val full = ShortArray(pad + n + pad)
        var j = 44
        for (i in 0 until n) {
            full[pad + i] = ((bytes[j].toInt() and 0xff) or (bytes[j + 1].toInt() shl 8)).toShort(); j += 2
        }
        // Run it through the SAME energy gate the live loop uses — the clip is padded with a second
        // of silence at each end, so this proves the gate opens in time and doesn't eat the wake word.
        var maxScore = 0f
        var off = 0
        var gateOpenChunks = 0
        val chunk = ShortArray(CHUNK)
        var floor = 30f
        var lastSound = -HANGOVER_MS
        var tMs = 0L
        while (off + CHUNK <= full.size) {
            System.arraycopy(full, off, chunk, 0, CHUNK)
            var sum = 0.0
            for (k in 0 until CHUNK) { val v = chunk[k].toDouble(); sum += v * v }
            val rms = Math.sqrt(sum / CHUNK).toFloat()
            floor = if (rms < floor) floor * 0.9f + rms * 0.1f else floor * 0.999f + rms * 0.001f
            if (rms > maxOf(MIN_GATE_RMS, floor * 3f)) lastSound = tMs
            val listening = tMs - lastSound < HANGOVER_MS
            if (listening) gateOpenChunks++
            val s = processChunk(chunk, listening)
            if (s > maxScore) maxScore = s
            off += CHUNK
            tMs += 80
        }
        Log.i("Jarvis", "WAKE SELF-TEST gate: inference ran on $gateOpenChunks/${full.size / CHUNK} chunks")
        Log.i("Jarvis", "WAKE SELF-TEST (bundled hey.wav) max score = $maxScore  (expect ~0.99)")
    }

    fun stop() {
        MicHub.removeSink(hubSink)
        running = false
        // Wait for the loop to leave OrtSession.run() before closing the sessions — closing a
        // session mid-inference frees its native handle under the running thread (SIGSEGV).
        // Worst case here is one 80ms chunk read + one inference, so the join is short.
        thread?.takeIf { it !== Thread.currentThread() }?.let { runCatching { it.join(1500) } }
        thread = null
        runCatching { melS?.close(); embS?.close(); wwS?.close() }
        melS = null; embS = null; wwS = null
    }

    // Frames arrive from MicHub (the app's one always-open, echo-cancelled capture) — no
    // AudioRecord of our own, so starting/stopping the detector never touches the capture slot
    // and never pays the DSP's ~2.25s soft-start.
    private val hubQ = java.util.concurrent.ArrayBlockingQueue<ShortArray>(32)
    private val hubAcc = ShortArray(CHUNK)
    private var hubFill = 0
    private val hubSink: (ShortArray) -> Unit = { f ->
        // Make-up gain to recognition-route levels: the comm-route hub runs ~10x quieter, and
        // both the wake model's mel features and the bridged seed expect normal speech amplitude
        // (measured: live "Hey Jarvis" scored 0.01 ungained, 0.99 at proper level).
        for (i in f.indices) {   // hub FRAME (160) divides CHUNK (1280)
            val v = f[i] * 10
            hubAcc[hubFill + i] = when {   // soft knee, not hard clip — this audio is also the seed
                v > 30000 -> minOf(32700, 30000 + (v - 30000) / 8)
                v < -30000 -> maxOf(-32700, -30000 + (v + 30000) / 8)
                else -> v
            }.toShort()
        }
        hubFill += f.size
        if (hubFill >= CHUNK) { hubFill = 0; hubQ.offer(hubAcc.copyOf()) }
    }

    private fun loop() {
        Log.i("Jarvis", "wake-word: listening for 'Hey Jarvis' (thr=$threshold) [hub-fed]")
        var maxSeen = 0f
        var frames = 0L
        while (running) {
            val chunk = hubQ.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue

            if (captureOnly) {
                // wake already fired — no inference, just keep the room's audio flowing into the
                // bridge while the session sets itself up. stop() ends this; the 4s cap is a
                // safety net in case the handoff never comes.
                postTrigger.add(chunk.copyOf())
                if (postTrigger.size > 50) break
                continue
            }

            ring.addLast(chunk.copyOf())
            while (ring.size > RING_CHUNKS) ring.removeFirst()

            // Energy gate. Running the embedding CNN on every 80ms chunk, forever, cost ~97% of a
            // core — in a bedroom that is almost always spent on silence. The cheap mel stage keeps
            // running (the first embedding needs 760ms of mel context), but the expensive stages
            // only run when the room is actually making noise, plus a hangover so a wake word that
            // starts the moment sound appears is still fully covered.
            var sum = 0.0
            for (k in 0 until CHUNK) { val v = chunk[k].toDouble(); sum += v * v }
            val rms = Math.sqrt(sum / CHUNK).toFloat()
            // track the room's noise floor: fall fast, rise slowly
            noiseFloor = if (rms < noiseFloor) noiseFloor * 0.9f + rms * 0.1f
                         else noiseFloor * 0.999f + rms * 0.001f
            val now = android.os.SystemClock.elapsedRealtime()
            if (rms > maxOf(MIN_GATE_RMS, noiseFloor * 3f)) lastSoundMs = now
            val listening = now - lastSoundMs < HANGOVER_MS

            // an uncaught exception here would kill the whole process — fail closed instead
            val score = try { processChunk(chunk, listening) } catch (e: Exception) {
                Log.e("Jarvis", "wake inference failed, stopping detector: $e"); break
            }
            if (score > maxSeen) maxSeen = score
            if (++frames % 25 == 0L) {
                Log.i("Jarvis", "wake-word peak(2s)=$maxSeen rms=$rms floor=$noiseFloor gate=${if (listening) "OPEN" else "closed"}")
                maxSeen = 0f
            }
            if (score >= threshold && cooldown == 0) {
                lastFireFrame = MicHub.frameCount
                Log.i("Jarvis", "*** WAKE WORD 'Hey Jarvis' detected! score=$score (hubFrame=$lastFireFrame) ***")
                // Keep only the DETECTION-LAG window (~320ms) of the past: it holds the start of
                // a continuously-spoken question but NOT the wake phrase itself — "Hey Jarvis" in
                // the session's audio + a breath reads as a complete turn and he'd answer the
                // wake word mid-sentence. Then switch to capture-only: the mic keeps rolling
                // through the whole session setup and releases at the last moment (see startMic's
                // onMicAboutToOpen), so the handoff hole shrinks to the record-creation time.
                val keep = minOf(4, ring.size)
                val flat = ShortArray(keep * CHUNK)
                var o = 0
                for (c in ring.drop(ring.size - keep)) { System.arraycopy(c, 0, flat, o, c.size); o += c.size }
                preTail = flat
                postTrigger.clear()
                captureOnly = true
                onWake()
                // no break: the loop continues capture-only until stop()
            }
            if (cooldown > 0) cooldown--
        }
    }

    /**
     * Returns the wakeword score for this chunk (0 if not enough context yet).
     * When [listening] is false the room is silent: keep the mel buffer warm (cheap) but skip the
     * embedding + wakeword models (expensive). The embedding history is dropped, so the first
     * ~1.3s after sound returns rebuilds it — which is exactly the span the wake word occupies.
     */
    private fun processChunk(chunk: ShortArray, listening: Boolean = true): Float {
        val melIn = FloatArray(CONTEXT + CHUNK)
        if (haveTail) for (i in 0 until CONTEXT) melIn[i] = prevTail[i].toFloat()
        for (i in 0 until CHUNK) melIn[CONTEXT + i] = chunk[i].toFloat()
        System.arraycopy(chunk, CHUNK - CONTEXT, prevTail, 0, CONTEXT); haveTail = true

        for (f in runMel(melIn)) { melBuf.addLast(f); while (melBuf.size > EMB_WIN) melBuf.removeFirst() }
        if (melBuf.size < EMB_WIN) return 0f

        if (!listening) { embBuf.clear(); return 0f }

        embBuf.addLast(runEmb(melBuf)); while (embBuf.size > WW_WIN) embBuf.removeFirst()
        if (embBuf.size < WW_WIN) return 0f

        return runWw(embBuf)
    }

    private fun runMel(audio: FloatArray): List<FloatArray> {
        val s = melS ?: return emptyList()
        val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong()))
        val r = s.run(mapOf("input" to t))
        val out = r[0] as OnnxTensor
        val T = out.info.shape[2].toInt()   // melspec output is [1, 1, T, 32] — time is dim 2, not 0
        val fb = out.floatBuffer
        val frames = ArrayList<FloatArray>(T)
        for (ti in 0 until T) {
            val fr = FloatArray(MEL_BINS)
            for (b in 0 until MEL_BINS) fr[b] = fb.get(ti * MEL_BINS + b) / 10f + 2f
            frames.add(fr)
        }
        r.close(); t.close()
        return frames
    }

    private fun runEmb(mel: ArrayDeque<FloatArray>): FloatArray {
        val data = FloatArray(EMB_WIN * MEL_BINS)
        var idx = 0
        for (f in mel) for (b in 0 until MEL_BINS) data[idx++] = f[b]
        val s = embS ?: return FloatArray(96)
        val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, EMB_WIN.toLong(), MEL_BINS.toLong(), 1))
        val r = s.run(mapOf("input_1" to t))
        val fb = (r[0] as OnnxTensor).floatBuffer
        val emb = FloatArray(96); for (i in 0 until 96) emb[i] = fb.get(i)
        r.close(); t.close()
        return emb
    }

    private fun runWw(embs: ArrayDeque<FloatArray>): Float {
        val data = FloatArray(WW_WIN * 96)
        var idx = 0
        for (e in embs) for (i in 0 until 96) data[idx++] = e[i]
        val s = wwS ?: return 0f
        val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, WW_WIN.toLong(), 96))
        val r = s.run(mapOf("x.1" to t))
        val score = (r[0] as OnnxTensor).floatBuffer.get(0)
        r.close(); t.close()
        return score
    }
}
