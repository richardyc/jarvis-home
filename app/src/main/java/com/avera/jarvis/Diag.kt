package com.avera.jarvis

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Probe which capture configuration actually yields mic audio, and whether the echo canceller
 * attaches to it. The mic lives on TDM slot 1, so a mono capture normally returns the undriven
 * slot 0 (random 0 / -32768) — but the HAL may route voice-communication captures differently,
 * and AEC only binds to certain sources. Measure rather than assume.
 */
object Diag {
    private data class Cfg(val name: String, val source: Int, val rate: Int, val mask: Int, val chans: Int)

    /**
     * The decisive AEC test: play a tone out the speaker and measure how much of it the mic hears,
     * with the echo canceller on versus off. "Attached" is not "working" — only a drop in the
     * measured echo proves the HAL is actually feeding it the loopback reference.
     */
    /**
     * Measure the REAL canceller (WebRTC AEC3), driven exactly as the live session drives it:
     * far-end paced against the playback head, mic at 16kHz, 10ms frames. Reports how much of
     * Jarvis's own voice survives — mic RMS before vs after cancellation, per second so we can
     * watch AEC3 converge.
     */
    fun aec3Test(ctx: android.content.Context) {
        Thread {
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxV = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val prev = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxV * 0.85).toInt(), 0)
            var track: android.media.AudioTrack? = null
            var rec: AudioRecord? = null
            try {
                if (!Aec.isReady) { Log.e("Jarvis", "aec3Test: AEC3 not loaded"); return@Thread }
                Thread.sleep(12000)   // the speaker amp takes seconds to come up; without it, silence
                val outRate = 24000
                val far = FarEnd()

                val minOut = android.media.AudioTrack.getMinBufferSize(outRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, outRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minOut, outRate * 2), android.media.AudioTrack.MODE_STREAM)

                val minRec = AudioRecord.getMinBufferSize(Aec.RATE, AudioIn.CHANNEL_MASK,
                    AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Aec.RATE,
                    AudioIn.CHANNEL_MASK, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minRec, Aec.RATE * 2))
                if (rec.state != AudioRecord.STATE_INITIALIZED) { Log.e("Jarvis", "aec3Test: no mic"); return@Thread }

                // 10s of broadband noise, syllable-modulated. Broadband on purpose: a small speaker
                // reproduces almost nothing below ~600Hz, so a bass-heavy probe measures nothing,
                // and AEC3's delay estimator needs wideband excitation to lock on anyway.
                val secs = 10
                val speech = ShortArray(outRate * secs)
                val rnd = java.util.Random(11)
                var fast = 0.0
                var slow = 0.0
                for (i in speech.indices) {
                    val w = rnd.nextDouble() * 2 - 1
                    fast = 0.40 * fast + 0.60 * w         // lowpass ~3.5kHz
                    slow = 0.96 * slow + 0.04 * fast      // ...minus below ~150Hz = speech band
                    val band = fast - slow
                    val env = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * 2.5 * i / outRate)
                    speech[i] = (16000 * band * env).toInt().coerceIn(-32767, 32767).toShort()
                }

                rec.startRecording()
                track.play()
                // feed playback in 100ms slices, and give FarEnd the same bytes (as the session does)
                Thread {
                    var off = 0
                    val slice = outRate / 10
                    while (off + slice <= speech.size) {
                        track.write(speech, off, slice)
                        val bytes = ByteArray(slice * 2)
                        for (i in 0 until slice) {
                            val v = speech[off + i].toInt()
                            bytes[i * 2] = (v and 0xff).toByte()
                            bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
                        }
                        far.append24k(bytes, bytes.size)
                        off += slice
                    }
                }.also { it.isDaemon = true; it.start() }

                val stereo = ShortArray(Aec.FRAME * AudioIn.CHANNELS)
                val mono = ShortArray(Aec.FRAME)
                val ref = ShortArray(Aec.FRAME)
                val rawPerSec = DoubleArray(secs)
                val cleanPerSec = DoubleArray(secs)
                for (sec in 0 until secs) {
                    var rawSq = 0.0; var clSq = 0.0; var cnt = 0
                    for (f in 0 until 100) {              // 100 x 10ms = 1s
                        var got = 0
                        while (got < stereo.size) {
                            val r = rec.read(stereo, got, stereo.size - got); if (r <= 0) break; got += r
                        }
                        if (got < stereo.size) break
                        for (i in 0 until Aec.FRAME) mono[i] = stereo[i * AudioIn.CHANNELS + AudioIn.MIC_CHANNEL]
                        for (v in mono) { rawSq += v.toDouble() * v; }
                        far.feedUpTo(track.playbackHeadPosition.toLong(), ref)
                        Aec.processCapture(mono, 60)
                        for (v in mono) { clSq += v.toDouble() * v; }
                        cnt += Aec.FRAME
                    }
                    if (cnt > 0) {
                        rawPerSec[sec] = Math.sqrt(rawSq / cnt)
                        cleanPerSec[sec] = Math.sqrt(clSq / cnt)
                    }
                }
                Log.i("Jarvis", "aec3Test: playbackHead=${track.playbackHeadPosition} " +
                        "(0 = the speaker never played → nothing to cancel)")
                val rawSteady = rawPerSec.drop(3).average()
                val clSteady = cleanPerSec.drop(3).average()
                val db = 20 * Math.log10(rawSteady / maxOf(clSteady, 0.5))
                Log.i("Jarvis", "aec3Test raw =[${rawPerSec.joinToString(", ") { "%.0f".format(it) }}]")
                Log.i("Jarvis", "aec3Test cln =[${cleanPerSec.joinToString(", ") { "%.0f".format(it) }}]")
                Log.i("Jarvis", "aec3Test STEADY raw=%.0f cleaned=%.0f → %.1f dB of echo removed"
                    .format(rawSteady, clSteady, db))
            } catch (e: Exception) {
                Log.e("Jarvis", "aec3Test failed: $e")
            } finally {
                runCatching { track?.stop(); track?.release() }
                runCatching { rec?.stop(); rec?.release() }
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, prev, 0)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun echoTest(ctx: android.content.Context) {
        Thread {
            // sequential — two captures at once would fight over the microphone
            for (rate in listOf(48000, 16000)) { echoTestAt(ctx, rate); Thread.sleep(800) }
            Log.i("Jarvis", "echoTest: ALL DONE")
        }.also { it.isDaemon = true; it.start() }
    }

    private fun echoTestAt(ctx: android.content.Context, rate: Int) {
        run {
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxV = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val prevV = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxV * 0.5).toInt(), 0)

            for (useAec in listOf(false, true)) {
                var track: android.media.AudioTrack? = null
                var rec: AudioRecord? = null
                var aecFx: AcousticEchoCanceler? = null
                try {
                    val minRec = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT)
                    rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                        AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minRec, rate * 2))
                    if (rec.state != AudioRecord.STATE_INITIALIZED) { Log.e("Jarvis", "echoTest: no mic"); return }
                    aecFx = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = useAec }

                    val minOut = android.media.AudioTrack.getMinBufferSize(rate,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    track = android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, rate,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minOut, rate * 2), android.media.AudioTrack.MODE_STREAM)
                    // 8 seconds of speech-like babble (band-limited noise, amplitude-modulated like
                    // syllables). A pure tone is a pathological AEC input; and the canceller ADAPTS,
                    // so we must let it converge and then measure the steady state — not the first
                    // half-second, which is what made this look like a feeble 9dB before.
                    val secs = 8
                    val tone = ShortArray(rate * secs)
                    var lp = 0.0
                    val rnd = java.util.Random(7)
                    for (i in tone.indices) {
                        lp = 0.86 * lp + 0.14 * (rnd.nextDouble() * 2 - 1)      // ~speech-band
                        val env = 0.55 + 0.45 * Math.sin(2.0 * Math.PI * 3.0 * i / rate)  // syllables
                        tone[i] = (11000 * lp * env).toInt().coerceIn(-32767, 32767).toShort()
                    }

                    rec.startRecording()
                    track.play()
                    Thread { track.write(tone, 0, tone.size) }.also { it.isDaemon = true; it.start() }

                    // sample every second so we can watch it converge
                    val perSec = DoubleArray(secs)
                    val buf = ShortArray(rate * 2)     // 1s stereo
                    for (sec in 0 until secs) {
                        var got = 0
                        while (got < buf.size) {
                            val n = rec.read(buf, got, buf.size - got); if (n <= 0) break; got += n
                        }
                        var sq = 0.0; var cnt = 0
                        var i = AudioIn.MIC_CHANNEL
                        while (i < got) { val v = buf[i].toDouble(); sq += v * v; cnt++; i += 2 }
                        perSec[sec] = if (cnt > 0) Math.sqrt(sq / cnt) else 0.0
                    }
                    val steady = perSec.drop(4).average()   // after convergence
                    Log.i("Jarvis", "echoTest ${rate}Hz AEC=${if (useAec) "ON " else "OFF"}: " +
                            "per-sec=[${perSec.joinToString(", ") { "%.0f".format(it) }}] steady=%.1f".format(steady))
                } catch (e: Exception) {
                    Log.e("Jarvis", "echoTest failed: $e")
                } finally {
                    runCatching { track?.stop(); track?.release() }
                    runCatching { rec?.stop(); rec?.release() }
                    runCatching { aecFx?.release() }
                }
                Thread.sleep(600)
            }
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, prevV, 0)
        }
    }

    fun probeCapture() {
        Thread {
            Log.i("Jarvis", "diag: AEC available=${AcousticEchoCanceler.isAvailable()} " +
                    "NS available=${NoiseSuppressor.isAvailable()}")
            val cfgs = listOf(
                Cfg("VOICE_RECOGNITION 48k stereo", MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    48000, AudioFormat.CHANNEL_IN_STEREO, 2),
                Cfg("VOICE_COMMUNICATION 48k stereo", MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    48000, AudioFormat.CHANNEL_IN_STEREO, 2),
                Cfg("VOICE_COMMUNICATION 16k mono", MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    16000, AudioFormat.CHANNEL_IN_MONO, 1),
                Cfg("VOICE_COMMUNICATION 16k stereo", MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    16000, AudioFormat.CHANNEL_IN_STEREO, 2),
                Cfg("MIC 48k stereo", MediaRecorder.AudioSource.MIC,
                    48000, AudioFormat.CHANNEL_IN_STEREO, 2)
            )
            for (c in cfgs) {
                try {
                    val min = AudioRecord.getMinBufferSize(c.rate, c.mask, AudioFormat.ENCODING_PCM_16BIT)
                    if (min <= 0) { Log.i("Jarvis", "diag: ${c.name} → unsupported"); continue }
                    val rec = AudioRecord(c.source, c.rate, c.mask,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min, c.rate * c.chans))
                    if (rec.state != AudioRecord.STATE_INITIALIZED) {
                        Log.i("Jarvis", "diag: ${c.name} → init FAILED"); rec.release(); continue
                    }
                    var aec = false
                    runCatching {
                        if (AcousticEchoCanceler.isAvailable())
                            aec = AcousticEchoCanceler.create(rec.audioSessionId)
                                ?.also { it.enabled = true }?.enabled == true
                    }
                    rec.startRecording()
                    val buf = ShortArray(c.rate / 2 * c.chans)   // 0.5s
                    var got = 0
                    while (got < buf.size) {
                        val n = rec.read(buf, got, buf.size - got); if (n <= 0) break; got += n
                    }
                    runCatching { rec.stop(); rec.release() }
                    // per-channel stats: which slot carries the mic?
                    val report = StringBuilder()
                    for (ch in 0 until c.chans) {
                        var mn = 32767; var mx = -32768; var sq = 0.0; var cnt = 0
                        var i = ch
                        while (i < got) {
                            val v = buf[i].toInt()
                            if (v < mn) mn = v
                            if (v > mx) mx = v
                            sq += v.toDouble() * v; cnt++
                            i += c.chans
                        }
                        val rms = if (cnt > 0) Math.sqrt(sq / cnt).toInt() else 0
                        report.append(" ch$ch[min=$mn max=$mx rms=$rms]")
                    }
                    Log.i("Jarvis", "diag: ${c.name} aecOn=$aec →$report")
                } catch (e: Exception) {
                    Log.i("Jarvis", "diag: ${c.name} → EXCEPTION ${e.message}")
                }
                Thread.sleep(300)
            }
            Log.i("Jarvis", "diag: capture probe COMPLETE")
        }.also { it.isDaemon = true; it.start() }
    }
}
