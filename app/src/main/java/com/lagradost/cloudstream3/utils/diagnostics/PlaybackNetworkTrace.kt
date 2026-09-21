package com.lagradost.cloudstream3.utils.diagnostics

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.io.IOException
import java.net.URI
import java.util.Locale

/**
 * Passive observation of actual Media3 load events from an individual ExoPlayer instance.
 * One listener is attached to each player and captures that attempt's op and selected source.
 * Does not open any HTTP connection, inspect response bodies, alter headers or retry loads.
 * Media3 load callbacks may refer to media manifests, segments, subtitles or cache reads;
 * they are NOT a wire-level record of every underlying network request or all redirect hops.
 */
@OptIn(UnstableApi::class)
object PlaybackNetworkTrace {
    private fun host(raw: String): String = try {
        URI(raw).host.orEmpty().lowercase(Locale.US)
            .takeIf { it.matches(Regex("[a-z0-9.-]{1,253}")) } ?: "unknown"
    } catch (_: Exception) { "unknown" }

    private fun header(headers: Map<String, List<String>>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()?.trim()

    /** Only explicit, allowlisted response metadata is ever emitted; never log all headers. */
    private fun responseInfo(headers: Map<String, List<String>>): String {
        val rawType = header(headers, "Content-Type")?.substringBefore(';')?.trim().orEmpty()
        val contentType = rawType.takeIf {
            it.matches(Regex("[A-Za-z0-9!#$.+^_-]{1,50}/[A-Za-z0-9!#$.+^_-]{1,50}"))
        } ?: "not_available"
        val typeHint = when {
            contentType.equals("text/html", true) || contentType.equals("application/xhtml+xml", true) -> "html"
            contentType.equals("application/json", true) || contentType.endsWith("+json", true) -> "json"
            contentType.startsWith("video/") || contentType.startsWith("audio/") ||
                contentType == "application/vnd.apple.mpegurl" ||
                contentType == "application/x-mpegurl" ||
                contentType == "application/dash+xml" -> "media_type"
            contentType == "not_available" -> "not_available"
            else -> "other_or_generic"
        }
        // Classification is a hint from declared Content-Type, never a claim about actual bytes.
        val size = header(headers, "Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
        val range = header(headers, "Content-Range")?.takeIf {
            it.length <= 90 && it.matches(Regex("bytes (?:[0-9]+-[0-9]+|\\*)/(?:[0-9]+|\\*)", RegexOption.IGNORE_CASE))
        }
        val acceptRange = header(headers, "Accept-Ranges")?.lowercase(Locale.US)
            ?.takeIf { it == "bytes" || it == "none" }
        return "response_headers_available=${headers.isNotEmpty()} content_type=$contentType " +
            "response_type_hint=$typeHint content_length=${size ?: "not_available"} " +
            "content_range=${range?.replace(' ', '_') ?: "not_available"} " +
            "accept_ranges=${acceptRange ?: "not_available"}"
    }

    private fun method(code: Int): String = when (code) {
        1 -> "GET"; 2 -> "POST"; 3 -> "HEAD"; else -> "method_$code"
    }

    private fun requestInfo(info: LoadEventInfo, media: MediaLoadData): String {
        val spec = info.dataSpec
        val headers = spec.httpRequestHeaders.keys.map { it.lowercase(Locale.US) }.toSet()
        return "load_id=${info.loadTaskId} data_type=${media.dataType} " +
            "track_type=${media.trackType} method=${method(spec.httpMethod)} " +
            "host=${host(spec.uri.toString())} request_url=${ProviderSafeText.url(spec.uri.toString())} " +
            "range_start=${spec.position} requested_length=${spec.length} " +
            "range_header_present=${"range" in headers} " +
            "referer_header_present=${"referer" in headers} " +
            "origin_header_present=${"origin" in headers}"
    }

    private fun resultInfo(info: LoadEventInfo): String {
        val initial = info.dataSpec.uri.toString()
        val final = info.uri.toString()
        return "final_host=${host(final)} final_url=${ProviderSafeText.url(final)} " +
            "redirect_observed=${initial != final} redirect_hops=not_exposed"
    }

    /** A single instance per player prevents events from an old source being attributed to a new one. */
    fun listener(op: Long, link: ExtractorLink): AnalyticsListener {
        val source = PlaybackSourceTrace.sourceRef(link.url)
        val prefix = "source_ref=$source "
        return object : AnalyticsListener {
            override fun onLoadStarted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                retryCount: Int
            ) {
                ProviderTrace.note(op, "PLAYBACK_NET_START", prefix +
                    "retry_count=$retryCount " + requestInfo(loadEventInfo, mediaLoadData))
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                // Media3 does not expose a reliable successful HTTP response code here.
                // Some data may come from cache rather than the network.
                ProviderTrace.note(op, "PLAYBACK_NET_COMPLETE", prefix +
                    "load_id=${loadEventInfo.loadTaskId} http_status=not_exposed " +
                    "duration_ms=${loadEventInfo.loadDurationMs} bytes_read=${loadEventInfo.bytesLoaded} " +
                    "cache_or_network=not_determined " + responseInfo(loadEventInfo.responseHeaders) +
                    " " + resultInfo(loadEventInfo))
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                val causes = generateSequence(error as Throwable) { it.cause }.take(8).toList()
                val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                val headers = http?.headerFields?.takeIf { it.isNotEmpty() }
                    ?: loadEventInfo.responseHeaders
                val actualFailingUrl = http?.dataSpec?.uri?.toString()
                ProviderTrace.note(op, "PLAYBACK_NET_ERROR", prefix +
                    "load_id=${loadEventInfo.loadTaskId} http_status=${http?.responseCode ?: "not_exposed"} " +
                    "error_type=${error.javaClass.simpleName} deepest_cause=${causes.last().javaClass.simpleName} " +
                    "duration_ms=${loadEventInfo.loadDurationMs} bytes_read=${loadEventInfo.bytesLoaded} " +
                    "was_canceled=$wasCanceled load_error_not_final=true " + responseInfo(headers) +
                    " failing_host=${host(actualFailingUrl ?: loadEventInfo.uri.toString())} " +
                    "failing_url=${ProviderSafeText.url(actualFailingUrl ?: loadEventInfo.uri.toString())}")
                // Separate compact record guarantees the sanitized failing URI remains visible
                // even if the detailed response record exceeds ProviderTrace's 420-char limit.
                ProviderTrace.note(op, "PLAYBACK_NET_TARGET", prefix +
                    "load_id=${loadEventInfo.loadTaskId} " +
                    "failing_host=${host(actualFailingUrl ?: loadEventInfo.uri.toString())} " +
                    "failing_url=${ProviderSafeText.url(actualFailingUrl ?: loadEventInfo.uri.toString())} " +
                    "redirect_observed=${loadEventInfo.dataSpec.uri.toString() != loadEventInfo.uri.toString()} " +
                    "redirect_hops=not_exposed")
                // This load error does not imply a final PLAYER failure; Media3 may recover.
            }

            override fun onLoadCanceled(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                ProviderTrace.note(op, "PLAYBACK_NET_CANCEL", prefix +
                    "load_id=${loadEventInfo.loadTaskId} " + resultInfo(loadEventInfo) +
                    " duration_ms=${loadEventInfo.loadDurationMs} bytes_read=${loadEventInfo.bytesLoaded}")
            }
        }
    }
}
