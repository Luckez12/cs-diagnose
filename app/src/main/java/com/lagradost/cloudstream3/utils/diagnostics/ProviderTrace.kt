package com.lagradost.cloudstream3.utils.diagnostics

import android.os.Looper
import android.os.SystemClock
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-app provider trace. Does not read, write, or change CloudStream's Logcat UI. */
object ProviderTrace {
    private const val MAX_ENTRIES = 2500
    private const val MAX_OPERATIONS = 3200
    private const val SLOW_MS = 5000L

    private data class TraceContext(val session: Long, val op: Long, val provider: String)
    private data class Pending(
        val provider: String,
        val stage: String,
        val session: Long,
        val since: Long,
        val heapAtStart: Long
    )
    private data class Entry(
        val at: String,
        val op: Long,
        val session: Long,
        val level: String,
        val stage: String,
        val info: String,
        val section: String
    )

    private val context = ThreadLocal<TraceContext?>()
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val pending = linkedMapOf<Long, Pending>()
    private val operationSessions = linkedMapOf<Long, Long>()
    // Store a hash, never the provider's content URL or episode payload.
    private val contentSessions = linkedMapOf<String, Long>()
    private val contentNames = linkedMapOf<Long, String>()
    private var counter = 0L
    private var dropped = 0L
    private var ignoreThrough = 0L
    private val recentNetwork = linkedMapOf<Long, ArrayDeque<String>>()
    // Only public hostnames, never URL paths, query strings or provider credentials.
    private val providerHosts = linkedMapOf<String, String>()
    private val providerStages = setOf(
        "HOMEPAGE", "HOMEPAGE_SECTION", "SEARCH", "QUICK_SEARCH", "METADATA", "LINKS"
    )

    /** Register the provider's own origin; does not imply every other host belongs to it. */
    fun registerProviderHost(provider: String, origin: String) {
        val host = try { java.net.URI(origin).host?.lowercase(Locale.US) } catch (_: Exception) { null }
        if (host.isNullOrBlank()) return
        synchronized(lock) {
            providerHosts[provider] = host
            while (providerHosts.size > 120) providerHosts.remove(providerHosts.keys.first())
        }
    }

    private fun hostMatches(requestHost: String, providerHost: String): Boolean =
        requestHost.equals(providerHost, ignoreCase = true) ||
            requestHost.endsWith(".$providerHost", ignoreCase = true)

    /**
     * OkHttp may execute on a separate thread from the provider coroutine. A host match
     * is only a hint, not proof: correlate ONLY if exactly one active provider session
     * matches its registered origin. Unknown/ambiguous HTTP is not attributed.
     */
    fun beginNetwork(stage: String, host: String, details: String): Long? = synchronized(lock) {
        if (context.get() != null) return@synchronized begin(stage, host, details)
        val sessions = pending.values.asSequence()
            .filter { it.stage in providerStages }
            .filter { providerHosts[it.provider]?.let { origin -> hostMatches(host, origin) } == true }
            .map { it.session }.distinct().toList()
        if (sessions.size != 1) return@synchronized null
        begin(stage, host, "$details attribution=host_match", sessions.single())
    }

    /** Only a challenge confirmed by CloudflareKiller creates an unlinked network trace. */
    fun beginCloudflare(host: String, code: Int): Long =
        beginNetwork("CLOUDFLARE", host, "challenge=detected status=$code")
            ?: begin("CLOUDFLARE", host, "challenge=detected status=$code attribution=unlinked")


    // Diagnostic retains provider-supplied content titles (user-requested), but never
    // raw URLs, request bodies, credentials or arbitrary exception messages.
    private fun clean(value: String, limit: Int = 140): String =
        value.replace(Regex("""[^\p{L}\p{M}\p{N} _.=:+()/-]"""), "_").take(limit)

    /** Human-readable homepage section label, not the section URL/data or a content title. */
    fun sectionValue(name: String): String {
        val safe = ProviderSafeText.message(name)
            .replace(Regex("""[^\p{L}\p{M}\p{N} _.\-]"""), " ")
            .trim().replace(Regex("\\s+"), "_").take(64).trim('_')
        return safe.ifBlank { "Unnamed" }
    }

    private fun contentKey(provider: String, url: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((provider + "\u0000" + url).toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** A repeated load of one provider URL uses the same session. No URL is logged. */
    fun beginContent(provider: String, url: String): Long = synchronized(lock) {
        val key = contentKey(provider, url)
        val existing = contentSessions[key]
        val op = begin("METADATA", provider, "content_ref=$key", existing)
        if (existing == null) contentSessions[key] = operationSessions[op] ?: op
        while (contentSessions.size > 160) contentSessions.remove(contentSessions.keys.first())
        op
    }

    /** Summarise a real LoadResponse, including cached responses; never guess episodes. */
    fun metadataDetails(op: Long, provider: String, requestedUrl: String, response: LoadResponse) = synchronized(lock) {
        val session = operationSessions[op] ?: op
        // A response may use a canonical URL different from the URL used to request it.
        contentSessions[contentKey(provider, requestedUrl)] = session
        contentSessions[contentKey(provider, response.url)] = session
        while (contentSessions.size > 160) contentSessions.remove(contentSessions.keys.first())
        val title = sectionValue(response.name).replace('_', ' ').take(80)
        contentNames[session] = title
        while (contentNames.size > 160) contentNames.remove(contentNames.keys.first())
        val summary = when (response) {
            is AnimeLoadResponse -> {
                val all = response.episodes.values.flatten()
                val numbered = all.mapNotNull { ep -> ep.episode?.let { number -> (ep.season ?: 0) to number } }.distinct().size
                "rekod=${all.size} episod_unik=$numbered versi=${response.episodes.size}"
            }
            is TvSeriesLoadResponse ->
                "rekod=${response.episodes.size} musim=${response.episodes.mapNotNull { it.season }.distinct().size}"
            else -> "rekod=tidak_berkenaan"
        }
        // Encode labels as single safe fields; real URLs, signed episode payloads and keys are never written.
        note(op, "METADATA_DETAIL",
            "title=${sectionValue(response.name)} type=${response.type.name} year=${response.year ?: "unknown"} $summary")
    }

    /** Selection is observable in the app even when the extension's internal lookups are not. */
    fun episodeSelected(provider: String, contentUrl: String, title: String,
                        season: Int?, episode: Int?, episodeName: String?, isMovie: Boolean) = synchronized(lock) {
        val key = contentKey(provider, contentUrl)
        val session = contentSessions[key]
        val op = begin("EPISODE_SELECTION", provider, "content_ref=$key", session)
        contentNames[operationSessions[op] ?: op] = sectionValue(title).replace('_', ' ').take(80)
        note(op, "EPISODE_DETAIL",
            "title=${sectionValue(title)} " +
            if (isMovie) "selection=movie" else
                "season=${season ?: "unknown"} episode=${episode ?: "unknown"} episode_name=${sectionValue(episodeName ?: "")}")
        finish(op)
    }

    private fun stamp(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    private fun heapMb(): Long = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576L

    // Keep the existing page and spinner UI. Only simplify its category choices.
    val sections = listOf(
        "Live Status", "Provider Process", "HTTP / Network", "Plugin Logs",
        "Links / Extractor", "Player", "Full timeline"
    )

    private fun sectionOf(stage: String): String {
        val name = stage.uppercase(Locale.US)
        return when {
            name.contains("HTTP") || name.contains("NETWORK") || name.contains("CLOUDFLARE") -> "HTTP / Network"
            name == "PLUGIN_LOG" -> "Plugin Logs"
            name.contains("LINK") || name.contains("EXTRACT") || name.contains("SUBTITLE") -> "Links / Extractor"
            name.contains("PLAYER") || name.contains("PLAYBACK") || name.contains("FIRST_FRAME") || name.contains("BUFFER") -> "Player"
            // Homepage, Search, Metadata and previously unclassified app-stage events
            // live together; no events are removed from Full timeline.
            else -> "Provider Process"
        }
    }

    private fun record(op: Long, level: String, stage: String, info: String) = synchronized(lock) {
        if (op <= ignoreThrough) return@synchronized
        if (entries.size >= MAX_ENTRIES) { entries.removeFirst(); dropped++ }
        val owner = if (stage == "STACK") {
            pending[op]?.stage ?: entries.lastOrNull { it.op == op && it.stage != "STACK" }?.stage
        } else null
        entries.addLast(Entry(stamp(), op, operationSessions[op] ?: op, level,
            stage, if (stage == "PLUGIN_LOG") ProviderSafeText.message(info) else clean(info, 420), sectionOf(owner ?: stage)))
    }

    fun begin(stage: String, provider: String, details: String = "", sessionOverride: Long? = null): Long = synchronized(lock) {
        PluginLogCollector.start()
        val id = ++counter
        val session = sessionOverride ?: context.get()?.session ?: id
        val safeStage = clean(stage)
        val safeProvider = clean(provider)
        pending[id] = Pending(safeProvider, safeStage, session, SystemClock.elapsedRealtime(), heapMb())
        operationSessions[id] = session
        while (operationSessions.size > MAX_OPERATIONS) operationSessions.remove(operationSessions.keys.first())
        while (recentNetwork.size > 120) recentNetwork.remove(recentNetwork.keys.first())
        val actor = if (safeStage == "HTTP" || safeStage == "CLOUDFLARE") "host" else "provider"
        record(id, "START", safeStage, "$actor=$safeProvider ${clean(details)} thread=${clean(Thread.currentThread().name)} main=${Looper.myLooper() == Looper.getMainLooper()}")
        id
    }

    /** Propagate the operation ID across coroutine dispatcher switches and nested homepage jobs. */
    suspend fun <T> inOperation(op: Long, action: suspend () -> T): T {
        val ctx = synchronized(lock) {
            pending[op]?.let { TraceContext(it.session, op, it.provider) }
        } ?: return action()
        return withContext(context.asContextElement(ctx)) { action() }
    }

    /** Android Log.d/i/w/e from this process; tag must match a registered provider.
     * A message without an active operation is shown as unlinked, never assigned to
     * some unrelated or already-completed request. */
    internal fun pluginLog(priority: Char, tag: String, message: String) = synchronized(lock) {
        fun matches(provider: String): Boolean {
            val short = provider.substringBefore(" _ ").substringBefore(' ')
            return tag.equals(provider, ignoreCase = true) ||
                tag.equals(short, ignoreCase = true) ||
                tag.startsWith("${short}Provider", ignoreCase = true) ||
                message.startsWith("[$provider]", ignoreCase = true)
        }
        // Never collect arbitrary Android/system log tags, even if they share the app PID.
        val registered = providerHosts.keys.filter(::matches)
        if (registered.isEmpty()) return@synchronized
        val active = pending.entries.filter { (_, task) ->
            task.stage in providerStages && task.provider in registered && task.session > ignoreThrough
        }
        val sessions = active.map { it.value.session }.distinct()
        // Android logcat has no coroutine context: if several homepage sections
        // are active, link to their common session but NOT to an arbitrary section.
        val chosen = active.singleOrNull()
        val oneSession = sessions.singleOrNull()
        val eventOp = chosen?.key ?: (++counter).also {
            operationSessions[it] = oneSession ?: 0L
        }
        val level = when (priority) { 'E', 'F' -> "ERROR"; 'W' -> "WARN"; else -> "INFO" }
        val attribution = when {
            chosen != null -> "single_active_request"
            oneSession != null -> "provider_session_only"
            else -> "tag_only_unlinked"
        }
        // ProviderSafeText runs in record(): no raw plugin text enters the report.
        record(eventOp, level, "PLUGIN_LOG",
            "tag=${clean(tag)} attribution=$attribution ${ProviderSafeText.message(message)}")
    }

    /** App hook from the shared extractor function; direct/custom extractors may bypass it. */
    fun extractorEvent(event: String, name: String, url: String, count: Int, elapsedMs: Long) {
        val ctx = context.get() ?: return
        val level = if (event == "ERROR" || event == "NO_MATCH") "WARN" else "INFO"
        record(ctx.op, level, "EXTRACTOR",
            "event=${clean(event)} name=${clean(name)} target=${ProviderSafeText.url(url)} links=$count elapsed=${elapsedMs}ms")
    }

    /** A request lacking this context must not be attributed to an arbitrary provider. */
    fun hasOperationContext(): Boolean = context.get() != null

    fun note(op: Long, stage: String, details: String) {
        record(op, "INFO", clean(stage), details)
    }

    fun noteCurrent(stage: String, details: String) {
        context.get()?.op?.let { note(it, stage, details) }
    }

    fun finish(op: Long, details: String = "") = synchronized(lock) {
        val item = pending.remove(op) ?: return@synchronized
        val elapsed = SystemClock.elapsedRealtime() - item.since
        val actor = if (item.stage == "HTTP" || item.stage == "CLOUDFLARE") "host" else "provider"
        record(op, "PASS", item.stage,
            "$actor=${item.provider} elapsed=${elapsed}ms heapDelta=${heapMb() - item.heapAtStart}MB ${clean(details)}")
        if (elapsed >= SLOW_MS) record(op, "SLOW", item.stage, "elapsed=${elapsed}ms")
    }

    /** An HTTP 4xx/5xx attempt may be recovered by the extension. Don't mark the provider failed. */
    fun httpWarning(op: Long, code: Int, details: String = "") = synchronized(lock) {
        val item = pending.remove(op) ?: return@synchronized
        val info = "host=${item.provider} status=$code elapsed=${SystemClock.elapsedRealtime() - item.since}ms ${clean(details)}"
        recentNetwork.getOrPut(item.session) { ArrayDeque() }.apply {
            if (size == 6) removeFirst()
            addLast("HTTP_$code host=${item.provider}")
        }
        record(op, "WARN", "HTTP", info)
    }

    /** HTTP attempt failed, but provider may retry on a different host or connection. */
    fun httpTransportFailure(op: Long, cause: Throwable) = synchronized(lock) {
        val item = pending.remove(op) ?: return@synchronized
        val kind = clean(cause.javaClass.simpleName)
        recentNetwork.getOrPut(item.session) { ArrayDeque() }.apply {
            if (size == 6) removeFirst()
            addLast("HTTP_$kind host=${item.provider}")
        }
        record(op, "WARN", "HTTP",
            "host=${item.provider} exception=$kind elapsed=${SystemClock.elapsedRealtime() - item.since}ms (attempt; provider may retry)")
        cause.stackTrace.take(12).forEach { frame ->
            note(op, "STACK", "at=${clean(frame.className)}.${clean(frame.methodName)}:${frame.lineNumber}")
        }
    }

    fun failure(op: Long, type: String, details: String = "") = synchronized(lock) {
        val item = pending.remove(op)
        // A player may render its first frame (PASS) and then fail during a later
        // segment. Preserve the original PLAYER stage for that late failure.
        val previousStart = if (item == null) entries.firstOrNull { it.op == op && it.level == "START" } else null
        val stage = item?.stage ?: previousStart?.stage ?: "REQUEST"
        val elapsed = item?.let { SystemClock.elapsedRealtime() - it.since } ?: 0L
        val actor = if (stage == "HTTP" || stage == "CLOUDFLARE") "host" else "provider"
        val recent = if (stage == "LINKS") recentNetwork[item?.session ?: operationSessions[op]]
            ?.joinToString(";", prefix = " recent_http_attempts=", postfix = " (not necessarily cause)") ?: "" else ""
        record(op, "FAIL", stage,
            "$actor=${item?.provider ?: previousStart?.info?.substringAfter("provider=")?.substringBefore(' ') ?: "unknown"} type=${clean(type)} elapsed=${elapsed}ms ${clean(details)}$recent")
    }

    fun cancelled(op: Long) = synchronized(lock) {
        val item = pending.remove(op) ?: return@synchronized
        record(op, "CANCEL", item.stage, "provider=${item.provider} elapsed=${SystemClock.elapsedRealtime() - item.since}ms")
    }

    /** Throwable.message can contain a signed URL or credential, so retain class + safe frames.
     * `details` must already consist of structured, non-secret fields, never raw exception text. */
    fun exception(op: Long, cause: Throwable, details: String = "") {
        if (cause is CancellationException) { cancelled(op); return }
        failure(op, cause.javaClass.simpleName.ifBlank { "Throwable" }, details)
        var t: Throwable? = cause
        var depth = 0
        while (t != null && depth < 3) {
            val current = t
            note(op, "STACK", "cause=$depth type=${clean(current.javaClass.name)}")
            current.stackTrace.take(18).forEach { frame ->
                note(op, "STACK", "at=${clean(frame.className)}.${clean(frame.methodName)}:${frame.lineNumber}")
            }
            t = current.cause
            depth++
        }
    }

    suspend fun <T> observe(stage: String, provider: String, details: String = "", action: suspend () -> T): T {
        val op = begin(stage, provider, details)
        return try {
            val result = inOperation(op) { action() }
            if (stage == "HOMEPAGE_SECTION") {
                if (result is HomePageResponse) {
                    val returnedNames = result.items.take(5)
                        .joinToString("+") { sectionValue(it.name) }
                    note(op, "HOMEPAGE_RESULT",
                        "groups=${result.items.size} items=${result.items.sumOf { it.list.size }} names=$returnedNames")
                } else if (result == null) {
                    note(op, "HOMEPAGE_RESULT", "empty=true")
                }
            }
            finish(op)
            result
        } catch (t: Throwable) {
            exception(op, t)
            throw t
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear(); pending.clear(); operationSessions.clear(); recentNetwork.clear()
        contentSessions.clear(); contentNames.clear(); dropped = 0L
        // Prevent an old in-flight operation from repopulating a freshly cleared report.
        ignoreThrough = counter
    }

    // Live Status deliberately uses observed facts rather than guessing what a plugin is
    // doing. Extractor-internal parsing / poster loading cannot be inferred from silence.
    // A section starts with a provider-declared MainPageData.name; retain its label
    // per operation ID so concurrent sections never borrow another section's name.
    private fun field(info: String, key: String): String? =
        Regex("(?:^|\\s)" + Regex.escape(key) + "=([^ ]+)")
            .find(info)?.groupValues?.getOrNull(1)

    private fun sectionLabel(op: Long, labels: Map<Long, String>): String =
        labels[op]?.replace('_', ' ') ?: "bahagian tidak dikenal pasti"

    private fun liveAction(stage: String, provider: String, label: String? = null): String = when (stage) {
        "HOMEPAGE" -> "Sedang memuatkan halaman utama $provider."
        "HOMEPAGE_SECTION" -> "Sedang memuatkan bahagian ${label ?: "tidak dikenal pasti"}."
        "SEARCH", "QUICK_SEARCH" -> "Sedang mencari kandungan dalam $provider."
        "METADATA" -> "Sedang mendapatkan maklumat kandungan."
        "LINKS" -> "Sedang mencari pautan video."
        "EXTRACTOR" -> "Sedang mendapatkan pautan daripada extractor."
        "HTTP" -> "Sedang menunggu respons laman $provider."
        "CLOUDFLARE" -> "Sedang menunggu pemeriksaan keselamatan laman."
        "PLAYER", "PLAYBACK" -> "Sedang menyediakan video untuk dimainkan."
        else -> "Sedang menjalankan proses provider."
    }

    private fun liveEvent(e: Entry, labels: Map<Long, String>, itemCounts: Map<Long, Int>, contentNames: Map<Long, String>): String? {
        val stage = e.stage.uppercase(Locale.US)
        val level = e.level
        val label = sectionLabel(e.op, labels)
        if (stage == "STACK" || stage == "HOMEPAGE_RESULT") return null
        if (stage == "METADATA_DETAIL") {
            val title = field(e.info, "title")?.replace('_', ' ') ?: "tidak diketahui"
            val kind = when (field(e.info, "type")) {
                "Anime" -> "Anime"
                "TvSeries", "AsianDrama", "Cartoon" -> "Siri"
                "Movie", "AnimeMovie" -> "Filem"
                else -> "Kandungan"
            }
            val year = field(e.info, "year")?.takeUnless { it == "unknown" }?.let { " ($it)" } ?: ""
            val entryCount = field(e.info, "rekod") ?: "tidak diketahui"
            val seasonCount = field(e.info, "musim")
            val numbered = field(e.info, "episod_unik")
            val versions = field(e.info, "versi")
            return "$kind: $title$year. " + when {
                entryCount == "tidak berkenaan" -> "Tiada senarai episod untuk jenis kandungan ini."
                numbered != null -> "Provider memulangkan $entryCount rekod episod" +
                    (if (versions != null) " merangkumi $versions versi" else "") +
                    "; $numbered nombor episod berbeza dikenal pasti."
                else -> "Provider memulangkan $entryCount rekod episod" +
                    (if (seasonCount != null) " daripada $seasonCount musim" else "") + "."
            }
        }
        if (stage == "EPISODE_DETAIL") {
            val title = field(e.info, "title")?.replace('_', ' ') ?: "kandungan"
            return if (field(e.info, "selection") == "movie") "Filem dipilih: $title."
                else "Memilih $title — musim ${field(e.info, "season") ?: "?"}, episod ${field(e.info, "episode") ?: "?"}" +
                    (field(e.info, "episode_name")?.takeUnless { it == "Unnamed" }?.let { " (${it.replace('_', ' ')})" } ?: "") + "."
        }
        if (stage == "PLAYER_SELECTED" && level == "INFO") {
            return "Sumber video dipilih (${field(e.info, "source_ref") ?: "ID tidak tersedia"}); " +
                "${field(e.info, "server")?.replace('_', ' ') ?: "server tidak diketahui"}, " +
                "kualiti ${field(e.info, "quality") ?: "tidak diketahui"}."
        }
        if (stage == "PLAYBACK_HTTP_ERROR" && level == "INFO") {
            return "Permintaan video ditolak oleh server (HTTP ${field(e.info, "http_status") ?: "?"}); " +
                "lihat Player untuk host dan jenis respons."
        }
        if (stage == "PLAYBACK_FORMAT_ERROR" && level == "INFO") {
            return "Player gagal membaca format media; lihat Player untuk jenis ralat sebenar."
        }
        if (stage == "PLUGIN_LOG") return when (level) {
            "ERROR", "WARN" -> "Plugin melaporkan masalah. Butiran ada dalam Plugin Logs."
            else -> null
        }
        if (stage == "CLOUDFLARE") {
            val step = field(e.info, "step")
            return when {
                step == "webview_wait" -> "Sedang menunggu pemeriksaan keselamatan laman."
                step == "webview_return" -> if (field(e.info, "clearance") == "true")
                    "Pemeriksaan keselamatan selesai; sambungan akan dicuba semula."
                    else "Pemeriksaan keselamatan ditutup tanpa pengesahan berjaya."
                step == "retry_request" -> "Mencuba sambungan semula selepas pemeriksaan keselamatan."
                step == "retry_result" -> "Cubaan semula menerima respons laman (${field(e.info, "status") ?: "status tidak diketahui"})."
                level == "START" -> "Pemeriksaan keselamatan laman dikesan."
                level == "FAIL" -> "Pemeriksaan keselamatan laman gagal."
                level == "PASS" -> "Proses pemeriksaan keselamatan selesai."
                else -> null
            }
        }
        if (stage == "HTTP") {
            val code = field(e.info, "status")
            return when {
                level == "WARN" && code != null ->
                    "Laman memberi respons $code; permintaan ini mungkin dicuba semula."
                level == "WARN" -> "Sambungan laman mengalami masalah; semak HTTP / Network."
                level == "FAIL" -> "Permintaan ke laman gagal; semak HTTP / Network."
                else -> null
            }
        }
        if (level == "SLOW") return when (stage) {
            "HOMEPAGE_SECTION" -> "Bahagian $label mengambil masa lebih lama (${field(e.info, "elapsed") ?: "?"}ms)."
            "HOMEPAGE" -> "Halaman utama mengambil masa lebih lama daripada biasa."
            "LINKS" -> "Pencarian pautan video mengambil masa lebih lama daripada biasa."
            else -> "Proses ${stage.lowercase(Locale.US)} mengambil masa lebih lama daripada biasa."
        }
        if (level == "FAIL") return when (stage) {
            "HOMEPAGE_SECTION" -> "Bahagian $label gagal dimuatkan."
            "HOMEPAGE" -> "Halaman utama gagal dimuatkan."
            "LINKS" -> "Pencarian pautan video gagal. Semak Links / Extractor dan HTTP / Network."
            "SEARCH", "QUICK_SEARCH" -> "Carian kandungan gagal."
            "METADATA" -> "Maklumat kandungan gagal dimuatkan."
            else -> "Proses provider gagal. Semak Full trace untuk butiran."
        }
        if (level == "CANCEL" && stage == "HOMEPAGE_SECTION")
            return "Memuatkan bahagian $label dibatalkan."
        if (level == "START") return when (stage) {
            "HOMEPAGE" -> "Mula memuatkan halaman utama ${field(e.info, "provider") ?: "provider"}."
            "HOMEPAGE_SECTION" -> "Mula memuatkan bahagian $label."
            "METADATA" -> "Mula mendapatkan maklumat ${contentNames[e.session] ?: "kandungan"}."
            "LINKS" -> "Mula mencari pautan video."
            "SEARCH", "QUICK_SEARCH" -> "Mula mencari kandungan."
            else -> null
        }
        if (level == "PASS") return when (stage) {
            "HOMEPAGE" -> "Halaman utama selesai dimuatkan."
            "HOMEPAGE_SECTION" -> {
                val count = itemCounts[e.op]
                if (count == null) "Bahagian $label selesai dimuatkan (jumlah hasil tidak diketahui)."
                else "Bahagian $label selesai dimuatkan ($count item)."
            }
            "METADATA" -> "Maklumat ${contentNames[e.session] ?: "kandungan"} berjaya diperoleh."
            "SEARCH", "QUICK_SEARCH" -> "Carian kandungan selesai."
            "LINKS" -> "Pencarian pautan video selesai."
            "PLAYER", "PLAYBACK", "FIRST_FRAME" -> "Video mula dipaparkan oleh player."
            else -> null
        }
        return null
    }

    private fun liveStatus(now: Long): String {
        // Historical entries stay append-only. In-flight sections are listed separately,
        // never merged or guessed from a shared HTTP request/Cloudflare challenge.
        val running = pending.entries.filter { it.key > ignoreThrough }
        val labels = mutableMapOf<Long, String>()
        val items = mutableMapOf<Long, Int>()
        val providers = mutableMapOf<Long, String>()
        entries.forEach { e ->
            if (e.stage == "HOMEPAGE_SECTION" && e.level == "START") {
                val name = field(e.info, "section")
                val index = field(e.info, "index")
                if (!name.isNullOrBlank() && name != "Unnamed") labels[e.op] = name
                else labels[e.op] = "Bahagian_${index ?: "?"}"
            }
            if (e.stage == "HOMEPAGE_RESULT")
                field(e.info, "items")?.toIntOrNull()?.let { items[e.op] = it }
            if (e.stage in providerStages)
                field(e.info, "provider")?.takeIf { it.isNotBlank() }?.let { providers[e.session] = it }
        }
        running.forEach { (_, task) ->
            if (task.stage in providerStages) providers[task.session] = task.provider
        }
        val sectionsRunning = running.filter { it.value.stage == "HOMEPAGE_SECTION" }
        val latest = running.maxWithOrNull(compareBy<Map.Entry<Long, Pending>> {
            when (it.value.stage) {
                "CLOUDFLARE" -> 6; "HTTP" -> 5; "EXTRACTOR", "LINKS" -> 4
                "HOMEPAGE_SECTION" -> 3; "HOMEPAGE" -> 2; else -> 1
            }
        }.thenBy { it.key })

        return buildString {
            appendLine("LIVE STATUS")
            appendLine()
            if (sectionsRunning.isNotEmpty()) {
                appendLine("SEKARANG: ${sectionsRunning.size} bahagian halaman utama sedang dimuatkan:")
                sectionsRunning.take(12).forEach { (id, task) ->
                    val seconds = (now - task.since).coerceAtLeast(0L) / 1000L
                    appendLine("• ${sectionLabel(id, labels)} — menunggu ${seconds}s.")
                }
                if (sectionsRunning.size > 12) appendLine("• ${sectionsRunning.size - 12} bahagian lain sedang dimuatkan.")
                val networkCount = running.count { it.value.stage == "HTTP" }
                if (networkCount > 0)
                    appendLine("Sedang menunggu $networkCount respons laman (belum dapat dipadankan dengan bahagian tertentu).")
                val challenge = running.firstOrNull { it.value.stage == "CLOUDFLARE" }
                if (challenge != null)
                    appendLine("Pemeriksaan keselamatan laman sedang berjalan (belum dapat dipadankan dengan bahagian tertentu).")
                // Requests on a shared client might run for several sections. Do not
                // assign any single HTTP request to a section without verified context.
            } else if (latest != null) {
                val task = latest.value
                val seconds = (now - task.since).coerceAtLeast(0L) / 1000L
                appendLine("SEKARANG: ${liveAction(task.stage, task.provider, labels[latest.key]?.replace('_', ' '))}")
                appendLine("Menunggu ${seconds}s.")
            } else {
                appendLine("SEKARANG: Tiada proses aktif yang dapat dikesan.")
            }
            appendLine()
            appendLine("SEJARAH PROSES")
            if (dropped > 0) appendLine("Nota: $dropped rekod paling lama digugurkan kerana had memori.")
            var lastSession: Long? = null
            var shown = 0
            entries.forEach { e ->
                val description = liveEvent(e, labels, items, contentNames) ?: return@forEach
                if (lastSession != e.session) {
                    if (shown > 0) appendLine()
                    val provider = providers[e.session]
                    val content = contentNames[e.session]?.let { " · $it" } ?: ""
                    appendLine("— ${if (provider == null) "Sesi #${e.session}" else "$provider$content · Sesi #${e.session}"} —")
                    lastSession = e.session
                }
                appendLine("${e.at}  $description")
                shown++
            }
            if (shown == 0) appendLine("Belum ada langkah yang dapat dikenal pasti. Buka provider dahulu.")
        }.trimEnd()
    }

    /** Plugin logs are already sanitized at collection time; format without re-reading Logcat. */
    private fun pluginMessage(info: String): String =
        info.substringAfter(" attribution=", info).substringAfter(' ', info)

    private fun isLinkDiscovery(e: Entry): Boolean = e.stage == "PLUGIN_LOG" &&
        (e.info.contains(Regex("""(?i)\b(?:DISCOVERY|EXTRACTOR|LOADLINKS|LINKS|STREAM|_DONE)\b""")) ||
            e.info.contains(Regex("""(?i)_(?:DISCOVERY|DONE)(?:\s|$)""")))

    private fun pluginFinalNoLinks(e: Entry): Boolean {
        if (e.stage != "PLUGIN_LOG") return false
        val msg = pluginMessage(e.info)
        // Explicit plugin result only. WARN by itself is not proof of failure.
        return msg.contains(Regex("""(?i)\bsuccess=false\b""")) ||
            msg.contains(Regex("""(?i)\bemitted=0\b""")) ||
            msg.contains(Regex("""(?i)\blinks=0\b""")) ||
            (msg.contains("_DONE") && msg.contains(Regex("""(?i)\bcandidates=0\b""")))
    }

    private fun displayEvent(e: Entry): String = if (e.stage == "PLUGIN_LOG") {
        val tag = field(e.info, "tag") ?: "Plugin"
        val attribution = field(e.info, "attribution")
        val source = when (attribution) {
            "single_active_request" -> "permintaan aktif"
            "provider_session_only" -> "sesi provider; langkah khusus tidak disahkan"
            "tag_only_unlinked" -> "tidak dipadankan dengan sesi"
            else -> "sesi tidak disahkan"
        }
        "${e.at}  [$tag] ${e.level}  sesi=${e.session} #${e.op}\n" +
            "  ${pluginMessage(e.info)}\n  Sumber: $source"
    } else "${e.at} session=${e.session} #${e.op} ${e.level} ${e.stage} ${e.info}"

    /** Existing UI consumes these strings; only the spinner categories and report change. */
    fun report(section: String, importantOnly: Boolean): String = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        if (section == "Live Status") return@synchronized liveStatus(now)
        val selected = entries.filter {
            section == "Full timeline" || it.section == section ||
                (section == "Links / Extractor" && isLinkDiscovery(it))
        }
        val active = pending.filterValues {
            section == "Full timeline" || sectionOf(it.stage) == section
        }
        buildString {
            appendLine("CLOUDSTREAM PROVIDER DIAGNOSTIC — ${if (importantOnly) "IMPORTANT" else "FULL TRACE"}")
            appendLine("Section: $section | events: ${selected.size} | active: ${active.size} | discarded: $dropped")
            if (section == "Plugin Logs") appendLine("Plugin log collector: ${PluginLogCollector.status()}")
            if (active.isNotEmpty()) {
                appendLine()
                appendLine("IN PROGRESS")
                active.forEach { (id, task) ->
                    val elapsed = now - task.since
                    appendLine("session=${task.session} #$id ${task.stage} provider=${task.provider} waiting=${elapsed}ms${if (elapsed >= SLOW_MS) " [SLOW]" else ""}")
                }
            }
            if (importantOnly) {
                val significant = selected.filter {
                    it.level == "FAIL" || it.level == "SLOW" ||
                        // An observed media-load error may recover; include as evidence, never
                        // mistake it for a final PLAYER failure.
                        it.stage == "PLAYBACK_NET_ERROR" ||
                        (it.stage == "PLUGIN_LOG" && (it.level == "ERROR" || pluginFinalNoLinks(it)))
                }
                if (section == "Plugin Logs") {
                    // A poster URL being selected is not evidence that its image loaded.
                    val posterSelections = selected.count {
                        it.stage == "PLUGIN_LOG" && pluginMessage(it.info).contains(Regex("""(?i)_POSTER\b"""))
                    }
                    if (posterSelections > 0) {
                        appendLine()
                        appendLine("Poster: $posterSelections log pemilihan URL gambar; status muat turun gambar tidak disahkan.")
                        appendLine("URL poster tersedia dalam Full trace (disensor).")
                    }
                }
                appendLine()
                appendLine("KEGAGALAN / PROSES PERLAHAN")
                if (significant.isEmpty()) appendLine("Tiada kegagalan akhir atau proses perlahan direkodkan di sini.")
                if (significant.any { it.stage == "PLAYBACK_NET_ERROR" })
                    appendLine("Nota: PLAYBACK_NET_ERROR ialah ralat cubaan muat data; player mungkin mencuba semula. Semak FAIL PLAYER untuk kegagalan akhir.")
                significant.takeLast(80).forEach { e ->
                    appendLine(displayEvent(e))
                    appendLine()
                }
                if (selected.any { it.level == "WARN" && it.stage == "HTTP" })
                    appendLine("HTTP warnings may have recovered; check Full trace for retries.")
            } else {
                appendLine()
                appendLine("=== ${section.uppercase(Locale.US)} (${selected.size}) ===")
                if (selected.isEmpty()) appendLine("No events recorded.")
                selected.forEachIndexed { index, e ->
                    if (index > 0 && e.stage != "STACK") appendLine()
                    appendLine(displayEvent(e))
                }
            }
        }
    }

    fun important(): String = report("Live Status", true)
    fun full(): String = report("Full timeline", false)
}
