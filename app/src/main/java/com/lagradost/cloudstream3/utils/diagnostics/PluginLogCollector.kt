package com.lagradost.cloudstream3.utils.diagnostics

import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * A best-effort bridge for Android Log.d/i/w/e messages written by installed plugins.
 * This is NOT a replacement for CloudStream's Logcat screen: it reads only our app PID,
 * and only messages tagged with the currently active provider (or prefixed [Provider]).
 * Android devices may restrict logcat access; we do not request privileged permissions.
 */
internal object PluginLogCollector {
    @Volatile private var started = false
    @Volatile private var state = "not started"

    fun status(): String = state

    @Synchronized fun start() {
        if (started) return
        started = true
        state = "starting"
        Thread({
            var process: java.lang.Process? = null
            try {
                // -T 1 starts near the newest entry, then follows subsequent entries.
                // The PID constraint avoids unrelated applications and system buffers.
                process = ProcessBuilder(
                    "logcat", "--pid=${Process.myPid()}", "-v", "threadtime", "-T", "1", "*:V"
                ).redirectErrorStream(true).start()
                state = "listening (provider-tagged entries only)"
                val pattern = Regex("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+([VDIWEAF])\\s+(.+?):\\s?(.*)$")
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val match = pattern.matchEntire(line) ?: continue
                        ProviderTrace.pluginLog(match.groupValues[1][0], match.groupValues[2].trim(), match.groupValues[3])
                    }
                }
                state = "stopped (logcat ended; plugin logs may be unavailable)"
            } catch (_: Exception) {
                state = "unavailable (use original Logcat for plugin messages)"
            } finally {
                process?.destroy()
            }
        }, "cs-diagnose-plugin-log").apply { isDaemon = true; start() }
    }
}
