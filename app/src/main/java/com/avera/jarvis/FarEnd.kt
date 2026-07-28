package com.avera.jarvis

/**
 * The far-end reference for the echo canceller: what the speaker is actually emitting.
 *
 * Two jobs, and both matter more than they look:
 *
 *  - RATE. Jarvis's voice arrives from the API at 24 kHz; AEC3 runs at 16 kHz. 24→16 is exactly
 *    3 samples in, 2 out, so each output lands either on an input sample or halfway between two.
 *
 *  - PACING. This is the thing that makes or breaks an echo canceller. AEC3 wants the reference at
 *    the moment the audio LEAVES THE SPEAKER — but we hand AudioTrack seconds of speech in advance.
 *    Feeding it at write time would run the reference seconds ahead of the echo and cancel nothing.
 *    So we buffer it here and release it against AudioTrack's playback head, which is the one honest
 *    clock we have for what the speaker is doing right now.
 */
class FarEnd {
    private val ring = ShortArray(16000 * 12)      // 12s at 16kHz
    private var writeIdx = 0L                       // absolute 16kHz sample index written
    private var fedIdx = 0L                         // absolute 16kHz sample index handed to AEC3
    private val carry = ShortArray(2)               // input samples spanning a chunk boundary
    private var carryLen = 0

    @Synchronized
    fun reset() {
        writeIdx = 0; fedIdx = 0; carryLen = 0
        java.util.Arrays.fill(ring, 0)
    }

    /** Append PCM16 @24kHz (as sent to AudioTrack), resampling to 16kHz. */
    @Synchronized
    fun append24k(pcm: ByteArray, len: Int) {
        val n = len / 2
        val inBuf = ShortArray(carryLen + n)
        for (i in 0 until carryLen) inBuf[i] = carry[i]
        var b = 0
        for (i in 0 until n) {
            inBuf[carryLen + i] = ((pcm[b].toInt() and 0xff) or (pcm[b + 1].toInt() shl 8)).toShort()
            b += 2
        }
        // consume in groups of 3 → emit 2 (positions 0 and 1.5)
        var i = 0
        while (i + 2 < inBuf.size) {
            put(inBuf[i])
            put((((inBuf[i + 1].toInt() + inBuf[i + 2].toInt()) / 2).toShort()))
            i += 3
        }
        carryLen = inBuf.size - i
        for (k in 0 until carryLen) carry[k] = inBuf[i + k]
    }

    private fun put(s: Short) {
        ring[(writeIdx % ring.size).toInt()] = s
        writeIdx++
    }

    /**
     * Hand AEC3 every reference frame that has now been rendered, up to the playback head.
     * @param playedFrames AudioTrack.playbackHeadPosition, in 24kHz frames
     */
    @Synchronized
    fun feedUpTo(playedFrames: Long, frame: ShortArray) {
        val target = playedFrames * 2 / 3          // 24kHz frames → 16kHz samples
        while (fedIdx + Aec.FRAME <= target && fedIdx + Aec.FRAME <= writeIdx) {
            for (i in 0 until Aec.FRAME) {
                frame[i] = ring[((fedIdx + i) % ring.size).toInt()]
            }
            Aec.processReverse(frame)
            fedIdx += Aec.FRAME
        }
    }
}
