package com.avera.echolab

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * EchoLab — isolated echo-cancellation test bench. No network, no assistant logic.
 *
 * Plays a bundled speech clip out the speaker while recording the mic, then reports how
 * much of the clip leaked into the capture. Phases: 2s ambient -> clip -> 2s tail.
 * In a quiet room, anything in the clip window above the ambient floor is echo.
 *
 * Drive it over adb:
 *   am start -n com.avera.echolab/.MainActivity --es mode raw|comm|commnofx|fxonly
 *
 *   raw      VOICE_RECOGNITION source, MODE_NORMAL, USAGE_MEDIA        (Jarvis today; control)
 *   comm     VOICE_COMMUNICATION + MODE_IN_COMMUNICATION + speakerphone
 *            + USAGE_VOICE_COMMUNICATION + AEC/NS effects              (full Teams recipe)
 *   commnofx same as comm but without creating the AEC/NS effect objects
 *   fxonly   VOICE_COMMUNICATION source + AEC/NS effects, but MODE_NORMAL + USAGE_MEDIA
 *
 * Results land in getExternalFilesDir(): <mode>_mic.wav + <mode>_report.txt, and logcat tag EchoLab.
 * Audio mode/speakerphone state is always restored in a finally block so a failed run
 * cannot leave the device wedged in communication mode.
 */
class MainActivity : Activity() {

    private val capturing = AtomicBoolean(false)
    private val capturedSamples = AtomicLong(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent?.getStringExtra("mode") ?: "raw"
        val reqRate = intent?.getIntExtra("rate", 16000) ?: 16000
        val ambientMs = intent?.getIntExtra("ambient", 2000) ?: 2000
        val wantNs = (intent?.getIntExtra("ns", 1) ?: 1) != 0
        Log.i(TAG, "=== EchoLab start, mode=$mode rate=$reqRate ambient=$ambientMs ===")
        thread(name = "echolab") {
            try {
                if (mode == "handoff") runHandoff() else runTest(mode, reqRate, ambientMs, wantNs)
            } catch (t: Throwable) {
                Log.e(TAG, "test failed", t)
                report(mode, "FAILED: $t")
            } finally {
                runOnUiThread { finishAndRemoveTask() }
            }
        }
    }

    private fun runTest(mode: String, reqRate: Int, ambientMs: Int, wantNs: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val lines = StringBuilder()
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        val savedMode = am.mode
        val savedSpeaker = am.isSpeakerphoneOn

        try {
            // Pin volumes so runs are comparable across modes.
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 8 / 10, 0)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) * 8 / 10, 0)

            // suffix match so orchestrated runs can prefix a label, e.g. "dt_fxonly"
            val commMode = mode.endsWith("comm") || mode.endsWith("commnofx")
            val commSource = commMode || mode.endsWith("fxonly")
            val recAec = mode.endsWith("recaec")   // VOICE_RECOGNITION + AEC effect: the HAL
                                                   // qualifies this source for enable_aec too
            val wantFx = mode.endsWith("comm") || mode.endsWith("fxonly") || recAec

            if (commMode) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
            } else {
                am.mode = AudioManager.MODE_NORMAL
            }
            lines.appendLine("mode=$mode commMode=$commMode source=${if (commSource) "VOICE_COMMUNICATION" else "VOICE_RECOGNITION"} fx=$wantFx")
            lines.appendLine("musicVol=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} " +
                "callVol=${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}")

            // --- capture ---
            val source = if (commSource) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                         else MediaRecorder.AudioSource.VOICE_RECOGNITION
            var rate = reqRate
            record = buildRecord(source, rate)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release(); rate = if (reqRate == 16000) 48000 else 16000
                record = buildRecord(source, rate)
                if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord init failed at $reqRate and $rate")
            }
            lines.appendLine("captureRate=$rate session=${record.audioSessionId}")

            if (wantFx) {
                lines.appendLine("aecAvailable=${AcousticEchoCanceler.isAvailable()} nsAvailable=${NoiseSuppressor.isAvailable()}")
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.audioSessionId)
                    aec?.enabled = true
                    lines.appendLine("aec=${aec?.enabled ?: "create-failed"} descriptor=${aec?.descriptor?.implementor ?: "-"} ${aec?.descriptor?.name ?: ""}")
                }
                if (wantNs && NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(record.audioSessionId)
                    ns?.enabled = true
                    lines.appendLine("ns=${ns?.enabled ?: "create-failed"}")
                }
            }
            if ((intent?.getIntExtra("agcoff", 0) ?: 0) != 0) {
                // create-then-disable: on Qualcomm HALs this is the hook to switch OFF the
                // default tx AGC that otherwise pumps the capture level
                val agc = try {
                    android.media.audiofx.AutomaticGainControl.create(record.audioSessionId)
                        ?.apply { enabled = false }
                } catch (e: Exception) { null }
                lines.appendLine("agcAvailable=${android.media.audiofx.AutomaticGainControl.isAvailable()} agcForcedOff=${agc?.let { !it.enabled } ?: "create-failed"}")
            }

            // --- far end ---
            val far = assets.open("farend_48k.pcm").readBytes()
            val usage = if (commMode) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(maxOf(
                    AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2,
                    96000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // --- run ---
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                throw IllegalStateException("startRecording did not stick (recordingState=${record.recordingState})")

            val chunks = ArrayList<ShortArray>()
            capturing.set(true)
            capturedSamples.set(0)
            val reader = thread(name = "echolab-read") {
                val buf = ShortArray(rate / 10)
                var zeroReads = 0
                while (capturing.get()) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        chunks.add(buf.copyOf(n))
                        capturedSamples.addAndGet(n.toLong())
                        zeroReads = 0
                    } else if (++zeroReads > 50) {
                        Log.e(TAG, "capture wedged: read()<=0 x50 (last=$n)")
                        lines.appendLine("WEDGED: read()<=0 x50 (last=$n)")
                        break
                    }
                }
            }

            Thread.sleep(ambientMs.toLong())
            val ambientEnd = capturedSamples.get()

            track.play()
            var off = 0
            val step = 9600 // 100ms of 48k mono s16
            while (off < far.size) {
                val n = minOf(step, far.size - off)
                val w = track.write(far, off, n)
                if (w <= 0) { lines.appendLine("track.write=$w at off=$off"); break }
                off += w
            }
            // let the buffered tail drain through the speaker
            Thread.sleep(600)
            val playEnd = capturedSamples.get()

            Thread.sleep(2000)
            capturing.set(false)
            reader.join(2000)
            track.stop()

            // --- analyze ---
            val all = ShortArray(chunks.sumOf { it.size })
            var p = 0
            for (c in chunks) { c.copyInto(all, p); p += c.size }

            val ambient = all.copyOfRange(0, ambientEnd.toInt().coerceAtMost(all.size))
            // skip the first 500ms of playback so track-start transients don't skew RMS
            val playStart = (ambientEnd + rate / 2).toInt().coerceAtMost(all.size)
            val play = all.copyOfRange(playStart, playEnd.toInt().coerceAtMost(all.size))
            val tail = all.copyOfRange(playEnd.toInt().coerceAtMost(all.size), all.size)

            val aRms = rms(ambient); val pRms = rms(play); val tRms = rms(tail)
            lines.appendLine("samples total=${all.size} ambient=${ambient.size} play=${play.size} tail=${tail.size}")
            lines.appendLine("ambientRMS=%.1f playRMS=%.1f tailRMS=%.1f".format(aRms, pRms, tRms))
            lines.appendLine("ambientPeak=${peak(ambient)} playPeak=${peak(play)} tailPeak=${peak(tail)}")
            val db = 20 * Math.log10((pRms + 1e-9) / (aRms + 1e-9))
            lines.appendLine("echo-over-ambient=%.1f dB (0 dB would be perfect cancellation)".format(db))

            val tag = if (rate == 16000) mode else "${mode}_$rate"
            writeWav(File(getExternalFilesDir(null), "${tag}_mic.wav"), all, rate)
            report(tag, lines.toString())
            Log.i(TAG, "=== EchoLab done, mode=$mode ===\n$lines")
        } finally {
            capturing.set(false)
            try { aec?.release() } catch (_: Exception) {}
            try { ns?.release() } catch (_: Exception) {}
            try { record?.stop() } catch (_: Exception) {}
            try { record?.release() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            try {
                am.mode = savedMode
                am.isSpeakerphoneOn = savedSpeaker
            } catch (_: Exception) {}
        }
    }

    /**
     * Simulate Jarvis's wake→session mic handoff with no GPT involved: capture 3s on the
     * recognition route (the wake detector's config), close it, open the comm route + AEC (the
     * session's config) and capture 9s more — while a continuous voice speaks across the
     * transition. Reports the dead-gap timings; the two WAVs show which words each side caught
     * and the comm route's soft-start level ramp.
     */
    private fun runHandoff() {
        val lines = StringBuilder()
        var rec1: AudioRecord? = null
        var rec2: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        val rate = 16000
        try {
            rec1 = buildRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate)
            if (rec1.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("rec1 init failed")
            rec1.startRecording()
            val a = ShortArray(rate * 3)
            var off = 0
            while (off < a.size) { val n = rec1.read(a, off, a.size - off); if (n <= 0) break; off += n }

            val t0 = android.os.SystemClock.elapsedRealtime()
            rec1.stop(); rec1.release(); rec1 = null
            val tClosed = android.os.SystemClock.elapsedRealtime()

            rec2 = buildRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate)
            if (rec2.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("rec2 init failed")
            aec = AcousticEchoCanceler.create(rec2.audioSessionId)?.apply { enabled = true }
            val tCreated = android.os.SystemClock.elapsedRealtime()
            var attempts = 0
            for (i in 0 until 25) {
                rec2.startRecording()
                attempts = i + 1
                if (rec2.recordingState == AudioRecord.RECORDSTATE_RECORDING) break
                Thread.sleep(40)
            }
            val tStarted = android.os.SystemClock.elapsedRealtime()
            val b = ShortArray(rate * 9)
            off = 0
            var tFirstRead = 0L
            while (off < b.size) {
                val n = rec2.read(b, off, b.size - off); if (n <= 0) break
                if (tFirstRead == 0L) tFirstRead = android.os.SystemClock.elapsedRealtime()
                off += n
            }
            lines.appendLine("handoff timings (ms): close=${tClosed - t0} create+aec=${tCreated - tClosed} " +
                "start=${tStarted - tCreated} (attempts=$attempts) firstData=${tFirstRead - tStarted}")
            lines.appendLine("TOTAL dead gap ≈ ${tFirstRead - t0} ms")
            lines.appendLine("aec=${aec?.enabled ?: "create-failed"}")
            writeWav(File(getExternalFilesDir(null), "handoff_a.wav"), a, rate)
            writeWav(File(getExternalFilesDir(null), "handoff_b.wav"), b, rate)
            report("handoff", lines.toString())
            Log.i(TAG, "=== handoff done ===\n$lines")
        } finally {
            try { aec?.release() } catch (_: Exception) {}
            try { rec1?.stop(); rec1?.release() } catch (_: Exception) {}
            try { rec2?.stop(); rec2?.release() } catch (_: Exception) {}
        }
    }

    private fun buildRecord(source: Int, rate: Int): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, rate)) // >= 0.5s so the reader can never underrun the HAL
    }

    private fun rms(s: ShortArray): Double {
        if (s.isEmpty()) return 0.0
        var acc = 0.0
        for (v in s) acc += v.toDouble() * v
        return sqrt(acc / s.size)
    }

    private fun peak(s: ShortArray): Int {
        var m = 0
        for (v in s) { val a = if (v < 0) -v.toInt() else v.toInt(); if (a > m) m = a }
        return m
    }

    private fun report(mode: String, text: String) {
        try { File(getExternalFilesDir(null), "${mode}_report.txt").writeText(text) } catch (_: Exception) {}
    }

    private fun writeWav(f: File, samples: ShortArray, rate: Int) {
        val dataLen = samples.size * 2
        val hdr = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        hdr.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        hdr.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        hdr.put("data".toByteArray()).putInt(dataLen)
        f.outputStream().use { out ->
            out.write(hdr.array())
            val bb = java.nio.ByteBuffer.allocate(dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (s in samples) bb.putShort(s)
            out.write(bb.array())
        }
    }

    companion object { private const val TAG = "EchoLab" }
}
