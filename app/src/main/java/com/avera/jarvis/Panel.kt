package com.avera.jarvis

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Live panel state: the physical controls and sensors, surfaced to the UI.
 * Display preferences and the last light state persist across restarts — this thing runs 24/7 on
 * a bedside table, and a restart at 1am must come back dim and in night mode, not full-bright.
 */
class Panel(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("panel", Context.MODE_PRIVATE)

    var micMuted by mutableStateOf(false)
    var cameraCovered by mutableStateOf(false)
    var online by mutableStateOf(true)
    /** Wi-Fi channel we're associated to, and whether it's a DFS (radar) channel. */
    var wifiChannel by mutableStateOf(0)
    var wifiIsDfs by mutableStateOf(false)

    /** The panel's own CPU. Starved cores once masqueraded as a WiFi fault — keep them visible. */
    var cpuPercent by mutableStateOf(-1)
    var cpuCores by mutableStateOf(-1)
    var cpuGovernor by mutableStateOf("")
    var lux by mutableStateOf(-1f)
        private set

    /** Volume HUD: shown briefly when a key is pressed. Persisted so it survives restarts. */
    var volumePercent by mutableStateOf(prefs.getInt("volume", 75))
    var volumeShownAt by mutableStateOf(0L)
    /** Mute toast: shown briefly when the physical mic-mute key toggles. */
    var muteShownAt by mutableStateOf(0L)
    fun chooseVolume(pct: Int): Int {
        volumePercent = pct.coerceIn(0, 100)
        prefs.edit().putInt("volume", volumePercent).apply()
        return volumePercent
    }

    var settingsOpen by mutableStateOf(false)

    /** Model + voice picked in Settings; empty = whatever config.yaml says. */
    var modelChoice by mutableStateOf(prefs.getString("model_choice", "") ?: "")
        private set
    var voiceChoice by mutableStateOf(prefs.getString("voice_choice", "") ?: "")
        private set
    fun chooseModel(m: String) { modelChoice = m; prefs.edit().putString("model_choice", m).apply() }
    fun chooseVoice(v: String) { voiceChoice = v; prefs.edit().putString("voice_choice", v).apply() }

    var autoBrightness by mutableStateOf(prefs.getBoolean("auto_brightness", true))
        private set
    var manualBrightness by mutableStateOf(prefs.getFloat("manual_brightness", 0.6f))
        private set
    var warmthEnabled by mutableStateOf(prefs.getBoolean("warmth", true))
        private set

    /** What the window should use before the first lux reading arrives. */
    val initialBrightness: Float = prefs.getFloat("last_brightness", 0.5f)

    /**
     * Night mode with hysteresis and dwell: enter below ~3 lux, leave above ~8 lux, and only after
     * the reading has held for a few seconds — passing headlights or a phone screen glancing across
     * the sensor must not flip the panel. The very first reading applies immediately (startup).
     */
    var nightMode by mutableStateOf(prefs.getBoolean("was_dark", false))
        private set
    private var pendingSince = 0L
    private var lastPersistedB = -1f

    fun updateLux(l: Float) {
        val first = lux < 0f
        lux = l
        val wantNight = if (nightMode) l < NIGHT_EXIT_LUX else l < NIGHT_ENTER_LUX
        if (first) {
            setNight(wantNight)
        } else if (wantNight == nightMode) {
            pendingSince = 0L
        } else {
            val now = android.os.SystemClock.elapsedRealtime()
            if (pendingSince == 0L) pendingSince = now
            else if (now - pendingSince >= DWELL_MS) { setNight(wantNight); pendingSince = 0L }
        }
        val b = targetBrightness
        if (abs(b - lastPersistedB) > 0.01f) {
            lastPersistedB = b
            prefs.edit().putFloat("last_brightness", b).apply()
        }
    }

    private fun setNight(v: Boolean) {
        if (nightMode == v) return
        nightMode = v
        prefs.edit().putBoolean("was_dark", v).apply()
    }

    fun chooseAutoBrightness(on: Boolean) {
        autoBrightness = on
        prefs.edit().putBoolean("auto_brightness", on).apply()
    }

    fun chooseManualBrightness(v: Float) {
        manualBrightness = v.coerceIn(0.02f, 1f)
        prefs.edit().putFloat("manual_brightness", manualBrightness).apply()
    }

    fun chooseWarmth(on: Boolean) {
        warmthEnabled = on
        prefs.edit().putBoolean("warmth", on).apply()
    }

    /**
     * Screen brightness for the current light level. A bedroom at night reads near 0 lux, and the
     * panel is the only light source — so the floor is genuinely dim, not "dim for a phone".
     */
    val targetBrightness: Float
        get() {
            if (!autoBrightness) return manualBrightness
            val l = lux
            if (l < 0f) return initialBrightness            // no reading yet
            return when {
                l < 1f -> 0.03f                             // dark room, someone asleep
                l < 5f -> 0.08f
                l < 20f -> 0.20f
                l < 80f -> 0.46f
                l < 300f -> 0.8f
                else -> 1f                                  // daylight
            }
        }

    /** 0 = neutral, 1 = fully warm. Dark rooms get an amber cast; daylight stays true. */
    val warmth: Float
        get() {
            if (!warmthEnabled) return 0f
            val l = lux
            if (l < 0f) return 0f
            return when {
                l < 1f -> 0.5f
                l < 5f -> 0.4f
                l < 20f -> 0.28f
                l < 80f -> 0.15f
                else -> 0f
            }
        }

    private companion object {
        const val NIGHT_ENTER_LUX = 25f   // below this the room's lights are off → dark mode
        const val NIGHT_EXIT_LUX = 45f    // hysteresis so a small flicker doesn't flip it back
        const val DWELL_MS = 6000L
    }
}
