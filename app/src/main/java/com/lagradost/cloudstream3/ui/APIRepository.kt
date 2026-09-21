package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.diagnostics.ProviderTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class APIRepository(val api: MainAPI) {
    companion object {
        // 2 minute timeout to prevent bad extensions/extractors from hogging the resources
        // No real provider should take longer, so we hard kill them.
        private const val DEFAULT_TIMEOUT = 120_000L
        private const val MAX_TIMEOUT = 4 * DEFAULT_TIMEOUT
        private const val MIN_TIMEOUT = 5_000L

        var dubStatusActive = HashSet<DubStatus>()

        val noneApi = object : MainAPI() {
            override var name = "None"
            override val supportedTypes = emptySet<TvType>()
            override var lang = ""
        }
        val randomApi = object : MainAPI() {
            override var name = "Random"
            override val supportedTypes = emptySet<TvType>()
            override var lang = ""
        }

        fun isInvalidData(data: String): Boolean {
            return data.isEmpty() || data == "[]" || data == "about:blank"
        }

        data class SavedLoadResponse(
            val unixTime: Long,
            val response: LoadResponse,
            val hash: Pair<String, String>
        )

        private val cache = atomicListOf<SavedLoadResponse>()
        private var cacheIndex: Int = 0
        const val CACHE_SIZE = 20

        fun getTimeout(desired: Long?): Long {
            return (desired ?: DEFAULT_TIMEOUT).coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)
        }
    }

    private fun afterPluginsLoaded(forceReload: Boolean) {
        if (forceReload) {
            cache.clear()
        }
    }

    init {
        afterPluginsLoadedEvent += ::afterPluginsLoaded
    }

    val hasMainPage = api.hasMainPage
    val providerType = api.providerType
    val name = api.name
    val mainUrl = api.mainUrl
    val mainPage = api.mainPage
    val hasQuickSearch = api.hasQuickSearch
    val vpnStatus = api.vpnStatus

    private suspend fun <T> traceResult(stage: String, extra: String = "", contentUrl: String? = null,
                                         action: suspend () -> Resource<T>): Resource<T> {
        ProviderTrace.registerProviderHost(api.name, api.mainUrl)
        val id = if (stage == "METADATA" && contentUrl != null)
            ProviderTrace.beginContent(api.name, contentUrl)
        else ProviderTrace.begin(stage, api.name, extra)
        return try {
            val result = ProviderTrace.inOperation(id) { action() }
            when (result) {
                is Resource.Success -> {
                    val detail = when (val value = result.value) {
                        is SearchResponseList -> "items=${value.items.size}"
                        is LoadResponse -> {
                            if (stage == "METADATA" && contentUrl != null)
                                ProviderTrace.metadataDetails(id, api.name, contentUrl, value)
                            "metadata=loaded"
                        }
                        is List<*> -> "sections=${value.size} items=${value.filterIsInstance<HomePageResponse>().sumOf { response -> response.items.sumOf { it.list.size } }}"
                        else -> "result=success"
                    }
                    ProviderTrace.finish(id, detail)
                }
                is Resource.Failure -> ProviderTrace.failure(id,
                    if (result.isNetworkError) "NetworkFailure" else "ProviderFailure")
                else -> {
                    ProviderTrace.note(id, stage, "result=${result.javaClass.simpleName}")
                    ProviderTrace.finish(id)
                }
            }
            result
        } catch (t: Throwable) {
            ProviderTrace.exception(id, t)
            throw t
        }
    }

    suspend fun load(url: String): Resource<LoadResponse> {
        return traceResult("METADATA", contentUrl = url) { safeApiCall {
            withTimeout(getTimeout(api.loadTimeoutMs)) {
                if (isInvalidData(url)) throw ErrorLoadingException()
                val fixedUrl = api.fixUrl(url)
                val lookingForHash = Pair(api.name, fixedUrl)

                val cached = cache.withLock {
                    var found: LoadResponse? = null
                    for (item in cache) {
                        // 10 min save
                        if (item.hash == lookingForHash && (unixTime - item.unixTime) < 60 * 10) {
                            found = item.response
                            break
                        }
                    }
                    found
                }

                if (cached != null) {
                    ProviderTrace.noteCurrent("METADATA_CACHE", "hit=true")
                    return@withTimeout cached
                }
                api.load(fixedUrl)?.also { response ->
                    // Remove all blank tags as early as possible
                    response.tags = response.tags?.filter { it.isNotBlank() }
                    val add = SavedLoadResponse(unixTime, response, lookingForHash)

                    cache.withLock {
                        if (cache.size > CACHE_SIZE) {
                            cache[cacheIndex] = add // rolling cache
                            cacheIndex = (cacheIndex + 1) % CACHE_SIZE
                        } else {
                            cache.add(add)
                        }
                    }
                } ?: throw ErrorLoadingException()
            }
        }
    }
    }

    suspend fun search(query: String, page: Int): Resource<SearchResponseList> {
        if (query.isEmpty())
            return Resource.Success(newSearchResponseList(emptyList()))

        return traceResult("SEARCH", "page=$page") { safeApiCall {
            withTimeout(getTimeout(api.searchTimeoutMs)) {
                (api.search(query, page)
                    ?: throw ErrorLoadingException())
                //                .filter { typesActive.contains(it.type) }
            }
        }
    }
    }

    suspend fun quickSearch(query: String): Resource<SearchResponseList> {
        if (query.isEmpty())
            return Resource.Success(newSearchResponseList(emptyList()))

        return traceResult("QUICK_SEARCH") { safeApiCall {
            withTimeout(getTimeout(api.quickSearchTimeoutMs)) {
                newSearchResponseList(
                    api.quickSearch(query) ?: throw ErrorLoadingException(),
                    false
                )
            }
        }
    }
    }

    suspend fun waitForHomeDelay() {
        val delta = api.sequentialMainPageScrollDelay + api.lastHomepageRequest - unixTimeMS
        if (delta < 0) return
        delay(delta)
    }

    suspend fun getMainPage(page: Int, nameIndex: Int? = null): Resource<List<HomePageResponse?>> {
        val requestedSection = nameIndex?.let { index ->
            api.mainPage.getOrNull(index)?.let { ProviderTrace.sectionValue(it.name) }
        } ?: "all"
        return traceResult("HOMEPAGE", "page=$page section=$requestedSection") { safeApiCall {
            withTimeout(getTimeout(api.getMainPageTimeoutMs)) {
                api.lastHomepageRequest = unixTimeMS

                nameIndex?.let { api.mainPage.getOrNull(it) }?.let { data ->
                    listOf(
                        ProviderTrace.observe("HOMEPAGE_SECTION", api.name, "page=$page index=$nameIndex section=${ProviderTrace.sectionValue(data.name)}") {
                            api.getMainPage(
                                page,
                                MainPageRequest(data.name, data.data, data.horizontalImages)
                            )
                        }
                    )
                } ?: run {
                    if (api.sequentialMainPage) {
                        var first = true
                        api.mainPage.mapIndexed { index, data ->
                            if (!first) { // dont want to sleep on first request
                                ProviderTrace.noteCurrent("HOMEPAGE_QUEUE", "waitMs=${api.sequentialMainPageDelay}")
                                delay(api.sequentialMainPageDelay)
                            }
                            first = false

                            ProviderTrace.observe("HOMEPAGE_SECTION", api.name, "page=$page index=$index section=${ProviderTrace.sectionValue(data.name)}") {
                                api.getMainPage(
                                    page,
                                    MainPageRequest(data.name, data.data, data.horizontalImages)
                                )
                            }
                        }
                    } else {
                        with(CoroutineScope(coroutineContext)) {
                            api.mainPage.mapIndexed { index, data ->
                                async {
                                    ProviderTrace.observe("HOMEPAGE_SECTION", api.name, "page=$page index=$index section=${ProviderTrace.sectionValue(data.name)}") {
                                        api.getMainPage(
                                            page,
                                            MainPageRequest(data.name, data.data, data.horizontalImages)
                                        )
                                    }
                                }
                            }.map { it.await() }
                        }
                    }
                }
            }
        }
    }
    }

    suspend fun extractorVerifierJob(extractorData: String?) {
        safeApiCall {
            api.extractorVerifierJob(extractorData)
        }
    }

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val op = ProviderTrace.begin("LINKS", api.name, "casting=$isCasting")
        if (isInvalidData(data)) {
            ProviderTrace.failure(op, "InvalidEpisodeData")
            return false
        }
        val streams = java.util.concurrent.atomic.AtomicInteger()
        val subtitles = java.util.concurrent.atomic.AtomicInteger()
        return try {
            val result = ProviderTrace.inOperation(op) {
                withTimeout(getTimeout(api.loadLinksTimeoutMs)) {
                    api.loadLinks(data, isCasting,
                        { file ->
                            subtitles.incrementAndGet()
                            subtitleCallback(file)
                        },
                        { link ->
                            val number = streams.incrementAndGet()
                            ProviderTrace.note(op, "LINK_RECEIVED", "number=$number type=${link.type} quality=${link.quality}")
                            callback(link)
                        }
                    )
                }
            }
            val detail = "returned=$result streams=${streams.get()} subtitles=${subtitles.get()}"
            if (result && streams.get() > 0) ProviderTrace.finish(op, detail)
            else ProviderTrace.failure(op, "NoPlayableLinks", detail)
            result
        } catch (throwable: Throwable) {
            ProviderTrace.exception(op, throwable)
            logError(throwable)
            return false
        }
    }
}
