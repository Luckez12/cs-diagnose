package com.lagradost.cloudstream3.utils.diagnostics

import okhttp3.Interceptor
import okhttp3.Response

/** Only traced shared-client requests. Unrelated images/telemetry never enter provider trace. */
class ProviderHttpTrace : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Skip static assets: these are usually posters, fonts, or subtitle downloads.
        val last = request.url.pathSegments.lastOrNull()?.lowercase().orEmpty()
        if (last.matches(Regex(".*\\.(?:png|jpe?g|webp|gif|svg|ico|woff2?|ttf|css)"))) {
            return chain.proceed(request)
        }
        // Static endpoint components only. Strip IDs, userinfo, query, fragments and signed paths.
        val endpoint = request.url.pathSegments.take(4).joinToString("/", prefix = "/") { part ->
            if (part.matches(Regex("[a-zA-Z][a-zA-Z_-]{1,23}")) &&
                !part.contains(Regex("(?i)token|secret|auth|cookie|password|signature|key"))) part else ":id"
        }
        val id = ProviderTrace.beginNetwork("HTTP", request.url.host,
            "method=${request.method} endpoint=$endpoint") ?: return chain.proceed(request)
        return try {
            val response = chain.proceed(request)
            val redirects = generateSequence(response.priorResponse) { it.priorResponse }.count()
            val challengeHint = response.code in listOf(403, 503) &&
                (response.header("Server")?.contains("cloudflare", ignoreCase = true) == true ||
                    response.header("cf-ray") != null)
            val details = "status=${response.code} finalHost=${response.request.url.host} redirects=$redirects cache=${response.cacheResponse != null} reportedLength=${response.header("Content-Length")?.toLongOrNull() ?: -1} cf_signal=$challengeHint"
            if (response.code >= 400) ProviderTrace.httpWarning(id, response.code, details)
            else ProviderTrace.finish(id, details)
            response
        } catch (t: Exception) {
            ProviderTrace.httpTransportFailure(id, t)
            throw t
        }
    }
}
