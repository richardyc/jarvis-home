package com.avera.jarvis

import java.io.File

/**
 * Runtime secrets/config: `files/.env` on the device, KEY=VALUE lines, overriding the build-time
 * defaults from secrets.properties (BuildConfig). Change a key without rebuilding:
 *
 *   adb shell "run-as com.avera.jarvis sh -c 'echo OPENROUTER_API_KEY=sk-... > files/.env'"
 *
 * then restart the app. Known keys: OPENAI_API_KEY, OPENROUTER_API_KEY.
 */
object Env {
    private val map = HashMap<String, String>()

    fun init(ctx: android.content.Context) {
        map.clear()
        runCatching {
            val f = File(ctx.filesDir, ".env")
            if (!f.exists()) return
            for (raw in f.readLines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val i = line.indexOf('=')
                if (i <= 0) continue
                map[line.substring(0, i).trim()] = line.substring(i + 1).trim().trim('"')
            }
            if (map.isNotEmpty())
                android.util.Log.i("Jarvis", ".env loaded: ${map.keys.joinToString()}")   // names only, never values
        }
    }

    operator fun get(key: String): String = map[key] ?: when (key) {
        "OPENAI_API_KEY" -> BuildConfig.OPENAI_API_KEY
        "OPENROUTER_API_KEY" -> BuildConfig.OPENROUTER_API_KEY
        else -> ""
    }
}
