package com.avera.jarvis

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tiny UI sounds. Generated as PCM and scaled by the same gain the voice uses, so the blip's
 * loudness reflects the actual volume level — the device's amp ignores stream/track volume, so the
 * only honest way to "hear the level" is to scale the samples themselves.
 */
object Sfx {
    private const val SR = 24000
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false

    /**
     * A soft two-note volume tick at [gain] (0..1). Two warm low tones crossfaded into each other so
     * it's smooth and elegant, not a shrill monotone. Rising for volume-up, falling for volume-down.
     */
    fun blip(gain: Float, rising: Boolean) {
        if (busy) return
        busy = true
        exec.execute {
            try {
                val g = gain.coerceIn(0f, 1f)
                // C5 / G4 — a gentle perfect fourth, low enough to be mellow.
                val hi = 523.25; val lo = 392.0
                val f1 = if (rising) lo else hi
                val f2 = if (rising) hi else lo
                val note = ms(70)          // each note ~70ms
                val overlap = ms(28)       // crossfade region — legato, no click
                val total = note * 2 - overlap
                val buf = ShortArray(total)
                addTone(buf, 0, note, f1, g, fadeIn = ms(12), fadeOut = overlap)
                addTone(buf, note - overlap, note, f2, g, fadeIn = overlap, fadeOut = ms(16))

                val at = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                at.write(buf, 0, buf.size)
                at.play()
                try { Thread.sleep((total * 1000L / SR) + 60) } catch (_: Exception) {}
                runCatching { at.stop(); at.release() }
            } finally { busy = false }
        }
    }

    /**
     * Timer chime: a gentle three-note rising arpeggio (C5–E5–G5), soft attack, long release —
     * a bedside bell, not an alarm klaxon. Called in a loop by TimerManager while ringing.
     */
    fun chime(gain: Float) {
        exec.execute {
            try {
                val g = gain.coerceIn(0.05f, 1f)
                val notes = doubleArrayOf(523.25, 659.25, 783.99)
                val note = ms(160); val gap = ms(40)
                val total = notes.size * (note + gap) + ms(220)   // trailing ring-out
                val buf = ShortArray(total)
                for ((i, f) in notes.withIndex()) {
                    val start = i * (note + gap)
                    val len = if (i == notes.lastIndex) note + ms(220) else note
                    addTone(buf, start, len, f, g * 0.8f, fadeIn = ms(10), fadeOut = len / 2)
                }
                val at = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                at.write(buf, 0, buf.size)
                at.play()
                try { Thread.sleep((total * 1000L / SR) + 60) } catch (_: Exception) {}
                runCatching { at.stop(); at.release() }
            } catch (_: Exception) {}
        }
    }

    private fun ms(m: Int) = SR * m / 1000

    /** Mix one enveloped sine into [buf] at [start] (raised-cosine fades so notes blend, no clicks). */
    private fun addTone(buf: ShortArray, start: Int, len: Int, freq: Double, gain: Float, fadeIn: Int, fadeOut: Int) {
        for (i in 0 until len) {
            val idx = start + i
            if (idx < 0 || idx >= buf.size) continue
            var env = gain.toDouble()
            if (i < fadeIn) env *= 0.5 * (1 - cos(PI * i / fadeIn))
            else if (i > len - fadeOut) env *= 0.5 * (1 - cos(PI * (len - i) / fadeOut))
            val s = sin(2.0 * PI * freq * i / SR) * 0.5 * env
            val v = (s * 32767).toInt() + buf[idx]
            buf[idx] = v.coerceIn(-32768, 32767).toShort()
        }
    }
}
