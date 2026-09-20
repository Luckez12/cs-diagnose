package com.lagradost.cloudstream3.utils.diagnostics

import java.net.URI
import java.util.Locale

/** Sanitizes arbitrary plugin Log.d messages before they are saved in the in-memory trace. */
internal object ProviderSafeText {
    private val urls = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
    private val secrets = Regex("(?i)(\\b(?:authorization|proxy-authorization|cookie|set-cookie|token|access_token|refresh_token|api[_-]?key|signature|sig|password|secret|session(?:id)?|jwt)\\b\\s*[:=]\\s*)([^\\s,;&}]+)")
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{8,}")
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
    private val longOpaque = Regex("\\b[A-Za-z0-9_-]{65,}\\b")
    private val sensitiveHeaders = Regex("(?i)\\b(?:authorization|proxy-authorization|cookie|set-cookie)\\b[\\\"']?\\s*[:=]\\s*.*$")

    fun url(raw: String): String {
        return try {
        val uri = URI(raw.trimEnd('.', ',', ')', ']'))
        val host = uri.host?.lowercase(Locale.US) ?: return "[URL redacted]"
        if (uri.scheme?.lowercase(Locale.US) !in listOf("http", "https")) return "[URL redacted]"
        val path = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }.take(6)
            .joinToString("/", prefix = "/") { segment ->
                if (segment.length > 28 || segment.contains('@') || segment.contains('%') ||
                    segment.matches(Regex("(?i)[a-f0-9]{24,}")) ||
                    segment.contains(Regex("(?i)token|secret|signature|session|auth|key"))) ":id" else segment
            }
        "${uri.scheme}://$host$path${if (uri.rawQuery != null || uri.rawFragment != null) "?[redacted]" else ""}"
        } catch (_: Exception) { "[URL redacted]" }
    }

    fun message(input: String): String {
        var value = input.take(1600).replace('\n', ' ').replace('\r', ' ')
        value = urls.replace(value) { url(it.value) }
        value = bearer.replace(value, "Bearer [REDACTED]")
        value = sensitiveHeaders.replace(value, "[SENSITIVE HEADER REDACTED]")
        value = secrets.replace(value) { "${it.groupValues[1]}[REDACTED]" }
        value = jwt.replace(value, "[JWT REDACTED]")
        value = longOpaque.replace(value, "[OPAQUE REDACTED]")
        return value.take(420)
    }
}
