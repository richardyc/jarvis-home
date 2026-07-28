package com.avera.jarvis

import android.util.Log

/**
 * WebRTC AEC3 — a real acoustic echo canceller, the same class of code Chrome and Google's own
 * assistant use. Removes Jarvis's voice from the microphone so the user can just talk over him.
 *
 * Why we do this ourselves: this board's platform canceller is broken. The audio HAL tries to read
 * its hardware echo reference from the same PCM device the mic occupies and fails
 * ("pcm_read error for HW reference -22"), so AcousticEchoCanceler cancels nothing. The retail ROM
 * sidesteps it the same way we do — software cancellation against a copy of the playback.
 *
 * Everything is 16 kHz mono in 10 ms frames (160 samples), AEC3's native format.
 *
 * Pacing matters more than anything else here: [processReverse] must be fed roughly when the audio
 * actually LEAVES THE SPEAKER, not when we hand it to AudioTrack — there are seconds of buffer in
 * between, and a reference that runs ahead of the echo cancels nothing.
 */
object Aec {
    const val RATE = 16000
    const val FRAME = 160          // 10ms

    @Volatile private var ready = false
    val isReady: Boolean get() = ready

    init {
        ready = try {
            System.loadLibrary("jarvisaec")
            nativeInit().also { Log.i("Jarvis", "AEC3 init: $it") }
        } catch (e: Throwable) {
            Log.e("Jarvis", "AEC3 unavailable: $e")
            false
        }
    }

    /** Far-end (what the speaker is emitting). Frame is 160 samples @16kHz mono. */
    fun processReverse(frame: ShortArray) {
        if (ready) nativeProcessReverse(frame)
    }

    /** Near-end (mic). Echo is removed IN PLACE. delayMs: speaker→mic acoustic + buffer delay. */
    fun processCapture(frame: ShortArray, delayMs: Int): Boolean =
        ready && nativeProcessCapture(frame, delayMs)

    private external fun nativeInit(): Boolean
    private external fun nativeRelease()
    private external fun nativeProcessReverse(frame: ShortArray)
    private external fun nativeProcessCapture(frame: ShortArray, delayMs: Int): Boolean
}
