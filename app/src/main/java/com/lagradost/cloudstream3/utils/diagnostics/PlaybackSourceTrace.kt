package com.lagradost.cloudstream3.utils.diagnostics

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Player-side evidence for an emitted ExtractorLink. The same source_ref is calculated
 * when a provider emits the link and when the app selects it. Do not use provider
 * names from the link's `source` label: that field often names a *video host*.
 * Never retain full media URLs, signed query strings, cookies or header values.
 */
object PlaybackSourceTrace {
    internal fun sourceRef(url: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun host(rawUrl: String?): String {
        val name = try { URI(rawUrl.orEmpty()).host.orEmpty().lowercase(Locale.US) }
            catch (_: Exception) { "" }
        // Hostnames only; never log URI userinfo or query strings.
        return name.takeIf { it.matches(Regex("[a-z0-9.-]{1,253}")) } ?: "unknown"
    }

    private fun headerFlags(names: Set<String>): String {
        val lower = names.map { it.lowercase(Locale.US) }.toSet()
        return listOf("referer", "origin", "range", "authorization", "cookie", "user-agent")
            .filter { it in lower }.joinToString("+").ifBlank { "none" }
    }

    /** One record per link, carrying an ID that can be matched to a selected player source. */
    fun received(op: Long, number: Int, link: ExtractorLink) {
        ProviderTrace.note(op, "LINK_RECEIVED",
            "number=$number source_ref=${sourceRef(link.url)} " +
                "server=${ProviderTrace.sectionValue(link.source)} host=${host(link.url)} " +
                "type=${link.type} quality=${link.quality}")
    }

    fun selected(op: Long, link: ExtractorLink) {
        ProviderTrace.note(op, "PLAYER_SELECTED",
            "source_ref=${sourceRef(link.url)} server=${ProviderTrace.sectionValue(link.source)} " +
                "name=${ProviderTrace.sectionValue(link.name)} host=${host(link.url)} " +
                "endpoint=${ProviderSafeText.url(link.url)} type=${link.type} quality=${link.quality} " +
                "referer_host=${host(link.referer)} request_header_names=${headerFlags(link.headers.keys)} " +
                "referer_present=${link.referer.isNotBlank()}")
    }

    private fun responseType(headers: Map<String, List<String>>): String {
        val contentType = headers.entries.firstOrNull {
            it.key.equals("Content-Type", ignoreCase = true)
        }?.value?.firstOrNull()?.substringBefore(';')?.trim().orEmpty()
        return contentType.takeIf {
            it.length in 3..100 && it.matches(Regex("[A-Za-z0-9!#$.+^_-]+/[A-Za-z0-9!#$.+^_-]+"))
        } ?: "not_reported"
    }

    /**
     * Observe the actual player exception, not a guessed preflight result. ExoPlayer's
     * Cronet/OkHttp request may use different headers/URL than the original link.
     */
    fun error(op: Long, link: ExtractorLink?, error: PlaybackException) {
        val ref = link?.let { sourceRef(it.url) } ?: "unlinked"
        val causes = generateSequence(error as Throwable) { it.cause }.take(8).toList()
        val invalid = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val terminal = causes.lastOrNull()
        if (invalid != null) {
            val spec = invalid.dataSpec
            ProviderTrace.note(op, "PLAYBACK_HTTP_ERROR",
                "source_ref=$ref http_status=${invalid.responseCode} " +
                    "request_host=${host(spec.uri.toString())} " +
                    "request_url=${ProviderSafeText.url(spec.uri.toString())} " +
                    "response_type=${responseType(invalid.headerFields)} " +
                    "request_header_names=${headerFlags(spec.httpRequestHeaders.keys)} " +
                    "range_start=${spec.position} error_type=InvalidResponseCodeException")
        }
        val hasUnknownFormat = causes.any { it.javaClass.simpleName == "UnrecognizedInputFormatException" }
        val ac3Parser = causes.any { throwable ->
            throwable.stackTrace.any { frame ->
                frame.className == "androidx.media3.extractor.Ac3Util" ||
                    frame.className == "androidx.media3.extractor.ts.Ac3Reader"
            }
        }
        if (hasUnknownFormat || ac3Parser) {
            ProviderTrace.note(op, "PLAYBACK_FORMAT_ERROR",
                "source_ref=$ref reason=${if (ac3Parser) "ac3_parser_error" else "unrecognized_media_format"} " +
                    "deepest_cause=${terminal?.javaClass?.simpleName ?: "unknown"}")
        }
        val finalDetail = "source_ref=$ref error_code=${error.errorCode}" +
            (invalid?.let { " http_status=${it.responseCode} response_type=${responseType(it.headerFields)}" } ?: "") +
            (if (hasUnknownFormat) " media_format=unrecognized" else "") +
            (if (ac3Parser) " audio_parser=ac3" else "")
        // Also retains the original structured stack trace without raw exception.message.
        ProviderTrace.exception(op, error, finalDetail)
    }
}
