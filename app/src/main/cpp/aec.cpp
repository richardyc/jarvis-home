// JNI glue for WebRTC's Audio Processing Module (AEC3).
//
// The platform echo canceller is broken on this board — the audio HAL fails to read its hardware
// echo reference ("pcm_read error for HW reference -22"), so it cancels nothing. The retail ROM
// doesn't use it either: Google's assistant runs its own software canceller against a loopback of
// the playback. We do the same, except our reference is better than a loopback — we synthesise the
// audio Jarvis speaks, so we hand AEC3 the exact far-end signal.
//
// Everything runs at 16 kHz mono, 10 ms frames (160 samples), which is AEC3's native rate.

#include <jni.h>
#include <memory>
#include <mutex>
#include <android/log.h>

#include "modules/audio_processing/include/audio_processing.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "JarvisAec", __VA_ARGS__)

namespace {

constexpr int kRate = 16000;
constexpr int kFrame = 160;   // 10 ms

std::mutex g_lock;
rtc::scoped_refptr<webrtc::AudioProcessing> g_apm;
webrtc::StreamConfig g_cfg(kRate, 1);

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_avera_jarvis_Aec_nativeInit(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lock);
    g_apm = webrtc::AudioProcessingBuilder().Create();
    if (!g_apm) {
        LOG("AudioProcessingBuilder().Create() returned null");
        return JNI_FALSE;
    }

    webrtc::AudioProcessing::Config cfg;
    cfg.echo_canceller.enabled = true;
    cfg.echo_canceller.mobile_mode = false;      // AEC3, not the weak fixed-point AECM
    cfg.echo_canceller.enforce_high_pass_filtering = true;
    cfg.high_pass_filter.enabled = true;
    cfg.noise_suppression.enabled = true;
    cfg.noise_suppression.level =
        webrtc::AudioProcessing::Config::NoiseSuppression::kModerate;
    // AGC off: this mic is quiet and an AGC would ride the residual echo up between words,
    // which is exactly the signal we don't want amplified.
    cfg.gain_controller1.enabled = false;
    cfg.gain_controller2.enabled = false;
    g_apm->ApplyConfig(cfg);

    LOG("AEC3 initialised @ %d Hz mono, %d-sample frames", kRate, kFrame);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_avera_jarvis_Aec_nativeRelease(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lock);
    g_apm = nullptr;
}

/** Far-end: what the speaker is rendering right now. Must be paced against playback, not writes. */
JNIEXPORT void JNICALL
Java_com_avera_jarvis_Aec_nativeProcessReverse(JNIEnv* env, jobject, jshortArray frame) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (!g_apm) return;
    if (env->GetArrayLength(frame) < kFrame) return;
    jshort* buf = env->GetShortArrayElements(frame, nullptr);
    g_apm->ProcessReverseStream(reinterpret_cast<int16_t*>(buf), g_cfg, g_cfg,
                                reinterpret_cast<int16_t*>(buf));
    env->ReleaseShortArrayElements(frame, buf, 0);
}

/** Near-end: the mic. Echo is removed in place. Returns false if the APM rejected the frame. */
JNIEXPORT jboolean JNICALL
Java_com_avera_jarvis_Aec_nativeProcessCapture(JNIEnv* env, jobject, jshortArray frame, jint delayMs) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (!g_apm) return JNI_FALSE;
    if (env->GetArrayLength(frame) < kFrame) return JNI_FALSE;
    jshort* buf = env->GetShortArrayElements(frame, nullptr);
    g_apm->set_stream_delay_ms(delayMs);
    int err = g_apm->ProcessStream(reinterpret_cast<int16_t*>(buf), g_cfg, g_cfg,
                                   reinterpret_cast<int16_t*>(buf));
    env->ReleaseShortArrayElements(frame, buf, 0);   // 0 = copy back the cleaned audio
    return err == webrtc::AudioProcessing::kNoError ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
