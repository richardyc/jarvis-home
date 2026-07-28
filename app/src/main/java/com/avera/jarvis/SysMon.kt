package com.avera.jarvis

import android.util.Log
import java.io.File

/**
 * Watches the panel's own CPU.
 *
 * This exists because of a bug that cost a day: MediaTek's hotplug governor parks three of the four
 * A35 cores to save power, and the panel ran the assistant on ONE core. The contention delayed the
 * network stack enough to stall packets for seconds and drop live conversations — a CPU problem
 * wearing a WiFi problem's clothes. A boot script now keeps all four online at full clock, and this
 * watches that it stays true, so the failure can never be invisible again.
 */
object SysMon {
    interface Listener {
        fun onSample(cpuPercent: Int, cores: Int, governor: String)
    }

    @Volatile private var running = false
    private var lastBusy = 0L
    private var lastTotal = 0L

    fun start(listener: Listener) {
        if (running) return
        running = true
        Thread {
            var warned = false
            while (running) {
                val cores = onlineCores()
                val gov = governor()
                val cpu = cpuPercent()

                // Shout once if the cores get parked again — this is the failure that hid as "WiFi".
                if (cores < 4 && !warned) {
                    warned = true
                    Log.w("Jarvis", "sysmon: only $cores/4 CPU cores online — the hotplug governor " +
                            "has parked them again; expect audio stutter and dropped sessions")
                } else if (cores >= 4) warned = false

                listener.onSample(cpu, cores, gov)
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false }

    /** Whole-device CPU busy percentage, from the delta between two /proc/stat samples. */
    private fun cpuPercent(): Int {
        val line = runCatching {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        }.getOrNull() ?: return -1
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 8 || f[0] != "cpu") return -1
        val nums = f.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 7) return -1
        val idle = nums[3] + nums[4]                 // idle + iowait
        val total = nums.sum()
        val dTotal = total - lastTotal
        val dBusy = (total - idle) - lastBusy
        lastTotal = total
        lastBusy = total - idle
        if (dTotal <= 0 || lastTotal == total) return -1
        return ((dBusy * 100) / dTotal).toInt().coerceIn(0, 100)
    }

    private fun onlineCores(): Int = runCatching {
        // "0-3" or "0,2-3" — count what the ranges actually cover
        File("/sys/devices/system/cpu/online").readText().trim()
            .split(",").sumOf { part ->
                if (part.contains("-")) {
                    val (a, b) = part.split("-").map { it.trim().toInt() }
                    b - a + 1
                } else if (part.isNotBlank()) 1 else 0
            }
    }.getOrDefault(-1)

    private fun governor(): String = runCatching {
        File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").readText().trim()
    }.getOrDefault("?")
}
