package com.lagradost.cloudstream3.utils.diagnostics

import okhttp3.Interceptor
import okhttp3.Response

/** Only traced shared-client requests. Unrelated images/telemetry never enter provider trace. */
class ProviderHttpTrace : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!ProviderTrace.hasOperationContext()) return chain.proceed(chain.request())
        val request = chain.request()
        // Static endpoint components only. Strip IDs, userinfo, query, fragments and signed paths.
        val endpoint = request.url.pathSegments.take(4).joinToString("/", prefix = "/") { part ->
            if (part.matches(Regex("[a-zA-Z][a-zA-Z_-]{1,23}")) &&
                !part.contains(Regex("(?i)token|secret|auth|cookie|password|signature|key"))) part else ":id"
        }
        val id = ProviderTrace.begin("HTTP", request.url.host,
            "method=${request.method} endpoint=$endpoint")
        return try {
            val response = chain.proceed(request)
            val details = "status=${response.code} redirected=${response.priorResponse != null} bytes=${response.header("Content-Length")?.toLongOrNull() ?: -1}"
            if (response.code >= 400) ProviderTrace.httpWarning(id, response.code, details)
            else ProviderTrace.finish(id, details)
            response
        } catch (t: Exception) {
            ProviderTrace.exception(id, t)
            throw t
        }
    }
}
