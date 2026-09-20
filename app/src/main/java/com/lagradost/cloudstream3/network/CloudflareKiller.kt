package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.diagnostics.ProviderTrace
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI


@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    init {
        // Needs to clear cookies between sessions to generate new cookies.
        safe {
            // This can throw an exception on unsupported devices :(
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /**
     * Gets the headers with cookies, webview user agent included!
     * */
    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()

        when (val cookies = savedCookies[request.url.host]) {
            null -> {
                val response = chain.proceed(request)
                if(!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) {
                    return@runBlocking response
                } else {
                    // A 403 alone does not prove Cloudflare; this branch also checked
                    // the server header. Do not include cookies, full URLs or headers.
                    val trace = ProviderTrace.beginCloudflare(request.url.host, response.code)
                    ProviderTrace.note(trace, "CLOUDFLARE", "step=initial_response challenge=confirmed host=${request.url.host}")
                    response.close()
                    try {
                        ProviderTrace.note(trace, "CLOUDFLARE", "step=challenge_resolution_start")
                        val solved = bypassCloudflare(request, trace)
                        if (solved != null) {
                            ProviderTrace.note(trace, "CLOUDFLARE", "step=retry_result status=${solved.code}")
                            if (solved.code in ERROR_CODES && solved.header("Server") in CLOUDFLARE_SERVERS) {
                                ProviderTrace.failure(trace, "ChallengeStillActive", "status=${solved.code}")
                            } else {
                                ProviderTrace.finish(trace, "result=retry_completed status=${solved.code}")
                            }
                            Log.d(TAG, "Cloudflare retry completed for host=${request.url.host} status=${solved.code}")
                            return@runBlocking solved
                        }
                        ProviderTrace.failure(trace, "ChallengeUnresolved", "step=no_clearance")
                    } catch (t: Exception) {
                        ProviderTrace.exception(trace, t)
                        throw t
                    }
                }
            }
            else -> {
                return@runBlocking proceed(request, cookies)
            }
        }

        debugWarning({ true }) { "Failed cloudflare at: ${request.url}" }
        return@runBlocking chain.proceed(request)
    }

    private fun getWebViewCookie(url: String): String? {
        return safe {
            CookieManager.getInstance()?.getCookie(url)
        }
    }

    /**
     * Returns true if the cf cookies were successfully fetched from the CookieManager
     * Also saves the cookies.
     * */
    private fun trySolveWithSavedCookies(request: Request): Boolean {
        // Not sure if this takes expiration into account
        return getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved ->
                if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
            }
        } ?: false
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentMap = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        val headers =
            getHeaders(request.headers.toMap() + userAgentMap, cookies + request.cookies)
        return app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).await()
    }

    private suspend fun bypassCloudflare(request: Request, trace: Long): Response? {
        val url = request.url.toString()

        // If no cookies then try to get them
        // Remove this if statement if cookies expire
        if (!trySolveWithSavedCookies(request)) {
            ProviderTrace.note(trace, "CLOUDFLARE", "step=webview_wait host=${request.url.host}")
            Log.d(TAG, "Loading Cloudflare WebView for host=${request.url.host}")
            WebViewResolver(
                // Never exit based on url
                Regex(".^"),
                // Cloudflare needs default user agent
                userAgent = null,
                // Cannot use okhttp (i think intercepting cookies fails which causes the issues)
                useOkhttp = false,
                // Match every url for the requestCallBack
                additionalUrls = listOf(Regex("."))
            ).resolveUsingWebView(
                url
            ) {
                trySolveWithSavedCookies(request)
            }
            ProviderTrace.note(trace, "CLOUDFLARE", "step=webview_return clearance=${savedCookies.containsKey(request.url.host)}")
        } else {
            ProviderTrace.note(trace, "CLOUDFLARE", "step=existing_clearance_available")
        }

        val cookies = savedCookies[request.url.host] ?: return null
        ProviderTrace.note(trace, "CLOUDFLARE", "step=retry_request host=${request.url.host}")
        return proceed(request, cookies)
    }
}