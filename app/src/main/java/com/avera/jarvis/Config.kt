package com.avera.jarvis

import android.content.Context

/** Minimal flat-YAML loader (key: value lines). "yaml or sth" — dependency-free. */
data class Config(
    val model: String,
    val voice: String,
    val vadSilenceMs: Int,
    val maxSessionMs: Long,
    val idleTimeoutMs: Long,
    val micEnabled: Boolean,
    val micGain: Float,
    val animate: Boolean,
    val wakeWord: Boolean,
    val wakeThreshold: Float,
    val wifiSsid: String,
    val wifiPass: String,
    val wifiBssid: String,
    val instructions: String,
    val homeCity: String
) {
    companion object {
        fun load(ctx: Context): Config {
            val map = HashMap<String, String>()
            runCatching {
                ctx.assets.open("config.yaml").bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) continue
                        val idx = line.indexOf(':')
                        if (idx < 0) continue
                        val k = line.substring(0, idx).trim()
                        var v = line.substring(idx + 1).trim()
                        // strip trailing inline comment for numeric values
                        val hash = v.indexOf(" #")
                        if (hash > 0 && !v.startsWith("\"")) v = v.substring(0, hash).trim()
                        if (v.length >= 2 &&
                            ((v.startsWith("\"") && v.endsWith("\"")) ||
                             (v.startsWith("'") && v.endsWith("'")))
                        ) v = v.substring(1, v.length - 1)
                        map[k] = v
                    }
                }
            }
            return Config(
                model = map["model"] ?: "gpt-realtime-2.1-mini",
                voice = map["voice"] ?: "alloy",
                vadSilenceMs = map["vad_silence_ms"]?.toIntOrNull() ?: 600,
                maxSessionMs = map["max_session_ms"]?.toLongOrNull() ?: 600_000L,
                idleTimeoutMs = map["idle_timeout_ms"]?.toLongOrNull() ?: 60_000L,
                micEnabled = (map["mic_enabled"] ?: "true").trim().lowercase() != "false",
                micGain = map["mic_gain"]?.toFloatOrNull()?.coerceIn(1f, 8f) ?: 6f,
                animate = (map["animate"] ?: "true").trim().lowercase() != "false",
                wakeWord = (map["wake_word"] ?: "true").trim().lowercase() != "false",
                wakeThreshold = map["wake_threshold"]?.toFloatOrNull() ?: 0.5f,
                wifiSsid = map["wifi_ssid"] ?: "",
                wifiPass = map["wifi_pass"] ?: "",
                wifiBssid = map["wifi_bssid"] ?: "",
                instructions = map["instructions"] ?: "",
                homeCity = map["home_city"] ?: "San Francisco, California"
            )
        }
    }
}
