package com.lagradost.cloudstream3.utils.diagnostics

import android.os.Process
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Passive same-PID Logcat bridge. Process diagnostics never come from app message text. */
internal object PluginLogCollector {
    private val running = AtomicBoolean(false)
    private val nextStartAt = AtomicLong(0)
    private const val COOLDOWN_MS = 30_000L

    internal data class Health(
        val state: String = "not_started", val launches: Int = 0, val gaps: Int = 0,
        val gapOpen: Boolean = false, val lastStop: String = "not_exposed",
        val lastReason: String = "not_exposed", val lastStart: String = "not_exposed",
        val lastRecord: String = "not_exposed", val lastResume: String = "not_exposed",
        val lastGapMs: Long? = null
    ) {
        fun fields(): String = "collector_scope=app_process collector_launches=$launches collector_gap_count=$gaps collector_gap_open=$gapOpen collector_last_stop_at=$lastStop collector_last_stop_reason=$lastReason collector_last_start_at=$lastStart collector_last_record_at=$lastRecord collector_last_resume_at=$lastResume collector_last_gap_ms=${lastGapMs ?: "not_exposed"} lost_plugin_events=not_determined"
    }
    @Volatile private var health = Health()
    private var gapSince: Long? = null // Supervisor thread only; survives cooldown/restart cycles.
    // Only hashes are retained, never raw app lines. Suppress -T 1 boundary replay after restart.
    private val seen = linkedSetOf<String>()

    fun status(): String = health.state
    fun snapshot(): Health = health
    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
    private fun event(action: String, details: String = "", warning: Boolean = false) {
        ProviderTrace.collectorEvent(if (warning) "WARN" else "INFO", "action=$action $details")
    }

    fun start() {
        if (SystemClock.elapsedRealtime() < nextStartAt.get() || !running.compareAndSet(false, true)) return
        try {
            Thread({ supervise() }, "cs-diagnose-plugin-log").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            health = health.copy(state = "unavailable reason=worker_start_failed", gaps = health.gaps + 1,
                gapOpen = true, lastStop = stamp(), lastReason = "worker_start_failed")
            gapSince = SystemClock.elapsedRealtime()
            nextStartAt.set(SystemClock.elapsedRealtime() + COOLDOWN_MS)
            running.set(false)
            // Do not include arbitrary exception messages/paths.
            event("stopped", "reason=worker_start_failed", true)
        }
    }

    private fun supervise() {
        try {
            for (attempt in 1..3) {
                val startedAt = stamp()
                health = health.copy(state = "starting", launches = health.launches + 1, lastStart = startedAt)
                event("start", "attempt=$attempt started_at=$startedAt read_from=near_newest")
                val reason = readProcess()
                val stoppedAt = stamp()
                if (gapSince == null) gapSince = SystemClock.elapsedRealtime()
                health = health.copy(state = "stopped reason=$reason", gaps = health.gaps + 1,
                    gapOpen = true, lastStop = stoppedAt, lastReason = reason)
                event("stopped", "attempt=$attempt stopped_at=$stoppedAt reason=$reason possible_gap=true lost_plugin_events=not_determined", true)
                val delay = PluginLogPolicy.retryDelayMs(attempt)
                if (delay == null) {
                    health = health.copy(state = "unavailable reason=$reason retry_limit_reached")
                    event("retry_limit", "launches_in_cycle=$attempt cooldown_ms=$COOLDOWN_MS", true)
                    break
                }
                health = health.copy(state = "retry_wait reason=$reason")
                event("retry_scheduled", "next_attempt=${attempt + 1} delay_ms=$delay", true)
                Thread.sleep(delay) // Collector worker only; never blocks the UI/provider thread.
            }
        } catch (_: InterruptedException) {
            health = health.copy(state = "unavailable reason=worker_interrupted", gapOpen = true,
                lastStop = stamp(), lastReason = "worker_interrupted", gaps = health.gaps + 1)
            if (gapSince == null) gapSince = SystemClock.elapsedRealtime()
            event("stopped", "reason=worker_interrupted possible_gap=true", true)
            Thread.currentThread().interrupt()
        } finally {
            // Publish cooldown before releasing the gate. No forever-true started flag.
            nextStartAt.set(SystemClock.elapsedRealtime() + COOLDOWN_MS)
            running.set(false)
        }
    }

    private fun readProcess(): String {
        var child: java.lang.Process? = null
        var stderrThread: Thread? = null
        val stderr = AtomicReference<String?>(null)
        var failure: String? = null
        var exitCode: Int? = null
        var firstRecord = true
        try {
            val process = ProcessBuilder("logcat", "--pid=${Process.myPid()}", "-v", "threadtime", "-T", "1", "*:V")
                .redirectErrorStream(false).start()
            child = process
            health = health.copy(state = "stream_open waiting_for_record")
            stderrThread = Thread({
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            val reason = PluginLogPolicy.stderrReason(line) ?: continue
                            // Explicit access diagnostic wins over generic stderr, without retaining text.
                            if (reason == "logcat_reported_access_denied") stderr.set(reason)
                            else stderr.compareAndSet(null, reason)
                        }
                    }
                } catch (_: Exception) { stderr.compareAndSet(null, "stderr_read_failed") }
            }, "cs-diagnose-logcat-stderr").apply { isDaemon = true; start() }
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val parsed = PluginLogPolicy.parseStdout(line) ?: continue
                    val receivedAt = stamp()
                    val since = gapSince
                    health = health.copy(state = "listening (provider-tagged entries only)", lastRecord = receivedAt,
                        gapOpen = false, lastResume = if (since != null) receivedAt else health.lastResume,
                        lastGapMs = if (since != null) SystemClock.elapsedRealtime() - since else health.lastGapMs)
                    if (since != null) {
                        gapSince = null
                        event("resumed", "resumed_at=$receivedAt observed_gap_ms=${health.lastGapMs} lost_plugin_events=not_determined replay=near_newest_only", true)
                    }
                    val fingerprint = MessageDigest.getInstance("SHA-256").digest(line.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    val boundaryReplay = firstRecord && fingerprint in seen
                    firstRecord = false
                    seen.add(fingerprint)
                    if (seen.size > 256) seen.remove(seen.first())
                    // Preserve identical repeated live messages; deduplicate only the -T 1 boundary.
                    if (boundaryReplay) continue
                    ProviderTrace.pluginLog(parsed.priority, parsed.tag, parsed.message)
                }
            }
            exitCode = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
        } catch (_: SecurityException) {
            failure = "process_security_exception"
        } catch (_: Exception) {
            failure = "process_or_read_failed"
        } finally {
            child?.destroy()
            try { stderrThread?.join(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        return stderr.get()?.takeIf { it == "logcat_reported_access_denied" }
            ?: failure ?: if (stderr.get() != null) stderr.get()!!
            else "stdout_ended exit_code=${exitCode ?: "not_exposed"}"
    }
}
