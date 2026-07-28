package com.avera.jarvis

import android.media.AudioFormat

/**
 * Microphone capture geometry. On this device (Lenovo ThinkSmart View, standard Qualcomm mic array
 * behind a normal audio HAL) we capture plain MONO — channel 0 is the primary, loudest mic and is
 * what VOICE_RECOGNITION expects. (The earlier Ivy panel needed a stereo capture off TDM slot 1;
 * that hardware is gone.)
 */
object AudioIn {
    const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
    const val MIC_CHANNEL = 0
    const val CHANNELS = 1
}
