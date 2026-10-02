package com.lagradost.cloudstream3.utils.diagnostics

/** Pure rules: stdout records are evidence, never process-access diagnostics. */
internal object PluginLogPolicy {
    data class Line(val priority: Char, val tag: String, val message: String)
    private val record = Regex("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+([VDIWEAF])\\s+(.+?):\\s?(.*)$")

    fun parseStdout(line: String): Line? = record.matchEntire(line)?.let {
        Line(it.groupValues[1][0], it.groupValues[2].trim(), it.groupValues[3])
    }

    fun stderrReason(line: String): String? {
        // Even a formatted app record accidentally appearing on stderr is not an access failure.
        if (parseStdout(line) != null) return null
        val text = line.trim()
        if (!Regex("^(?:/system/bin/)?logcat:", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        return if (Regex("\\b(?:permission denied|operation not permitted|access denied)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
            "logcat_reported_access_denied" else "logcat_stderr_reported"
    }

    fun retryDelayMs(attempt: Int): Long? = when (attempt) {
        1 -> 1000L
        2 -> 2000L
        else -> null // At most three launches per recovery cycle, even after a long healthy stream.
    }
}
