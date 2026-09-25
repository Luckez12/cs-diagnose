package com.lagradost.cloudstream3.utils.diagnostics

import android.content.Context
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
    private fun s(ctx: Context, key: String, vararg args: Any): String =
        DiagnosticText.get(ctx, key, *args)

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

    private fun sectionLabel(ctx: Context, op: Long, labels: Map<Long, String>): String =
        labels[op]?.replace('_', ' ') ?: s(ctx, "unknown_section")

    private fun liveAction(ctx: Context, stage: String, provider: String, label: String? = null): String = when (stage) {
        "HOMEPAGE" -> s(ctx, "loading_home", provider)
        "HOMEPAGE_SECTION" -> s(ctx, "loading_section", label ?: s(ctx, "unknown"))
        "SEARCH", "QUICK_SEARCH" -> s(ctx, "searching_provider", provider)
        "METADATA" -> s(ctx, "loading_details")
        "LINKS" -> s(ctx, "searching_links")
        "EXTRACTOR" -> s(ctx, "resolving_extractor")
        "HTTP" -> s(ctx, "waiting_site", provider)
        "CLOUDFLARE" -> s(ctx, "waiting_security")
        "PLAYER", "PLAYBACK" -> s(ctx, "preparing_video")
        else -> s(ctx, "running_provider")
    }

    private fun liveEvent(ctx: Context, e: Entry, labels: Map<Long, String>, itemCounts: Map<Long, Int>, contentNames: Map<Long, String>): String? {
        val stage = e.stage.uppercase(Locale.US)
        val level = e.level
        val label = sectionLabel(ctx, e.op, labels)
        if (stage == "STACK" || stage == "HOMEPAGE_RESULT") return null
        if (stage == "METADATA_DETAIL") {
            val title = field(e.info, "title")?.replace('_', ' ') ?: s(ctx, "unknown")
            val kind = when (field(e.info, "type")) {
                "Anime" -> s(ctx, "kind_anime")
                "TvSeries", "AsianDrama", "Cartoon" -> s(ctx, "kind_series")
                "Movie", "AnimeMovie" -> s(ctx, "kind_movie")
                else -> s(ctx, "kind_content")
            }
            val year = field(e.info, "year")?.takeUnless { it == "unknown" }?.let { " ($it)" } ?: ""
            val count = field(e.info, "rekod") ?: s(ctx, "unknown")
            val seasons = field(e.info, "musim")
            val numbered = field(e.info, "episod_unik")
            val versions = field(e.info, "versi")
            val details = when {
                count == "tidak_berkenaan" -> s(ctx, "no_episode_list")
                numbered != null -> s(ctx, "episode_records_unique", count, versions ?: "?", numbered)
                seasons != null -> s(ctx, "episode_records_seasons", count, seasons)
                else -> s(ctx, "episode_records", count)
            }
            return s(ctx, "metadata_summary", kind, title, year, details)
        }
        if (stage == "EPISODE_DETAIL") {
            val title = field(e.info, "title")?.replace('_', ' ') ?: s(ctx, "kind_content")
            if (field(e.info, "selection") == "movie") return s(ctx, "movie_selected", title)
            val epName = field(e.info, "episode_name")?.takeUnless { it == "Unnamed" }
                ?.let { " (${it.replace('_', ' ')})" } ?: ""
            return s(ctx, "episode_selected", title, field(e.info, "season") ?: "?", field(e.info, "episode") ?: "?", epName)
        }
        if (stage == "PLAYER_SELECTED" && level == "INFO")
            return s(ctx, "video_selected", field(e.info, "source_ref") ?: s(ctx, "unknown"),
                field(e.info, "server")?.replace('_', ' ') ?: s(ctx, "unknown_server"),
                field(e.info, "quality") ?: s(ctx, "unknown"))
        if (stage == "PLAYBACK_HTTP_ERROR" && level == "INFO")
            return s(ctx, "video_http_rejected", field(e.info, "http_status") ?: "?")
        if (stage == "PLAYBACK_FORMAT_ERROR" && level == "INFO") return s(ctx, "video_format_error")
        if (stage == "PLUGIN_LOG") return when(level) {
            "ERROR", "WARN" -> s(ctx, "plugin_problem")
            else -> null
        }
        if (stage == "CLOUDFLARE") {
            val step = field(e.info, "step")
            return when {
                step == "webview_wait" -> s(ctx, "waiting_security")
                step == "webview_return" -> if (field(e.info, "clearance") == "true")
                    s(ctx, "security_complete_retry") else s(ctx, "security_unconfirmed")
                step == "retry_request" -> s(ctx, "security_retry")
                step == "retry_result" -> s(ctx, "security_retry_status", field(e.info, "status") ?: s(ctx, "unknown"))
                level == "START" -> s(ctx, "security_detected")
                level == "FAIL" -> s(ctx, "security_failed")
                level == "PASS" -> s(ctx, "security_finished")
                else -> null
            }
        }
        if (stage == "HTTP") {
            val code = field(e.info, "status")
            return when {
                level == "WARN" && code != null -> s(ctx, "site_http_warn", code)
                level == "WARN" -> s(ctx, "site_warn")
                level == "FAIL" -> s(ctx, "site_failed")
                else -> null
            }
        }
        if (level == "SLOW") return when(stage) {
            "HOMEPAGE_SECTION" -> s(ctx, "section_slow", label, field(e.info, "elapsed") ?: "?")
            "HOMEPAGE" -> s(ctx, "home_slow")
            "LINKS" -> s(ctx, "links_slow")
            else -> s(ctx, "process_slow", stage.lowercase(Locale.US))
        }
        if (level == "FAIL") return when(stage) {
            "HOMEPAGE_SECTION" -> s(ctx, "section_failed", label)
            "HOMEPAGE" -> s(ctx, "home_failed")
            "LINKS" -> s(ctx, "links_failed")
            "SEARCH", "QUICK_SEARCH" -> s(ctx, "search_failed")
            "METADATA" -> s(ctx, "metadata_failed")
            else -> s(ctx, "provider_failed")
        }
        if (level == "CANCEL" && stage == "HOMEPAGE_SECTION") return s(ctx, "section_canceled", label)
        if (level == "START") return when(stage) {
            "HOMEPAGE" -> s(ctx, "home_started", field(e.info, "provider") ?: "provider")
            "HOMEPAGE_SECTION" -> s(ctx, "section_started", label)
            "METADATA" -> s(ctx, "metadata_started", contentNames[e.session] ?: s(ctx, "kind_content"))
            "LINKS" -> s(ctx, "links_started")
            "SEARCH", "QUICK_SEARCH" -> s(ctx, "search_started")
            else -> null
        }
        if (level == "PASS") return when(stage) {
            "HOMEPAGE" -> s(ctx, "home_finished")
            "HOMEPAGE_SECTION" -> {
                val count = itemCounts[e.op]
                if (count == null) s(ctx, "section_finished_unknown", label)
                else s(ctx, "section_finished_count", label, count)
            }
            "METADATA" -> s(ctx, "metadata_finished", contentNames[e.session] ?: s(ctx, "kind_content"))
            "SEARCH", "QUICK_SEARCH" -> s(ctx, "search_finished")
            "LINKS" -> s(ctx, "links_finished")
            "PLAYER", "PLAYBACK", "FIRST_FRAME" -> s(ctx, "video_first_frame")
            else -> null
        }
        return null
    }

    private fun liveStatus(ctx: Context, now: Long): String {
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
                else labels[e.op] = s(ctx, "section_number", index ?: "?")
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
            appendLine(s(ctx, "live_status"))
            appendLine()
            if (sectionsRunning.isNotEmpty()) {
                appendLine(s(ctx, "now_sections", sectionsRunning.size))
                sectionsRunning.take(12).forEach { (id, task) ->
                    val seconds = (now - task.since).coerceAtLeast(0L) / 1000L
                    appendLine(s(ctx, "section_waiting", sectionLabel(ctx, id, labels), seconds))
                }
                if (sectionsRunning.size > 12) appendLine(s(ctx, "more_sections", sectionsRunning.size - 12))
                val networkCount = running.count { it.value.stage == "HTTP" }
                if (networkCount > 0)
                    appendLine(s(ctx, "waiting_responses", networkCount))
                val challenge = running.firstOrNull { it.value.stage == "CLOUDFLARE" }
                if (challenge != null)
                    appendLine(s(ctx, "security_unlinked"))
                // Requests on a shared client might run for several sections. Do not
                // assign any single HTTP request to a section without verified context.
            } else if (latest != null) {
                val task = latest.value
                val seconds = (now - task.since).coerceAtLeast(0L) / 1000L
                appendLine(s(ctx, "now_status", liveAction(ctx, task.stage, task.provider, labels[latest.key]?.replace('_', ' '))))
                appendLine(s(ctx, "waiting_seconds", seconds))
            } else {
                appendLine(s(ctx, "now_idle"))
            }
            appendLine()
            appendLine(s(ctx, "history"))
            if (dropped > 0) appendLine(s(ctx, "dropped_old", dropped))
            var lastSession: Long? = null
            var shown = 0
            entries.forEach { e ->
                val description = liveEvent(ctx, e, labels, items, contentNames) ?: return@forEach
                if (lastSession != e.session) {
                    if (shown > 0) appendLine()
                    val provider = providers[e.session]
                    val content = contentNames[e.session]?.let { " · $it" } ?: ""
                    appendLine("— ${if (provider == null) s(ctx, "session", e.session) else "$provider$content · ${s(ctx, "session", e.session)}"} —")
                    lastSession = e.session
                }
                appendLine("${e.at}  $description")
                shown++
            }
            if (shown == 0) appendLine(s(ctx, "no_steps"))
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

    private fun displayEvent(ctx: Context, e: Entry): String = if (e.stage == "PLUGIN_LOG") {
        val tag = field(e.info, "tag") ?: "Plugin"
        val attribution = field(e.info, "attribution")
        val source = when (attribution) {
            "single_active_request" -> s(ctx, "attr_active")
            "provider_session_only" -> s(ctx, "attr_provider")
            "tag_only_unlinked" -> s(ctx, "attr_unlinked")
            else -> s(ctx, "attr_unverified")
        }
        "${e.at}  [$tag] ${e.level}  session=${e.session} #${e.op}\n" +
            "  ${pluginMessage(e.info)}\n  ${s(ctx, "origin_label")}: $source"
    } else "${e.at} session=${e.session} #${e.op} ${e.level} ${e.stage} ${e.info}"

    /** Existing UI consumes these strings; only the spinner categories and report change. */
    fun report(ctx: Context, section: String, importantOnly: Boolean): String = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        if (section == "Live Status") return@synchronized liveStatus(ctx, now)
        val selected = entries.filter {
            section == "Full timeline" || it.section == section ||
                (section == "Links / Extractor" && isLinkDiscovery(it))
        }
        val active = pending.filterValues {
            section == "Full timeline" || sectionOf(it.stage) == section
        }
        buildString {
            appendLine("CLOUDSTREAM PROVIDER DIAGNOSTIC — ${if (importantOnly) s(ctx, "important") else s(ctx, "full_trace")}")
            appendLine(s(ctx, "report_counts", DiagnosticText.section(ctx, section), selected.size, active.size, dropped))
            if (section == "Plugin Logs") appendLine(s(ctx, "collector_status", PluginLogCollector.status()))
            if (active.isNotEmpty()) {
                appendLine()
                appendLine(s(ctx, "in_progress"))
                active.forEach { (id, task) ->
                    val elapsed = now - task.since
                    appendLine("session=${task.session} #$id ${task.stage} provider=${task.provider} waiting=${elapsed}ms${if (elapsed >= SLOW_MS) " [SLOW]" else ""}")
                }
            }
            if (importantOnly) {
                // Summarise repeated non-final load errors by player operation + Media3
                // load task. Full Trace is unchanged: every START/ERROR/TARGET stays intact.
                // Count observed callbacks, not inferred network attempts or retry success.
                val loadKey: (Entry) -> Pair<Long, String> = { e ->
                    e.op to (field(e.info, "load_id") ?: "not_exposed")
                }
                val loadErrors = selected.filter { it.stage == "PLAYBACK_NET_ERROR" }
                    .groupBy(loadKey)
                val loadStarts = selected.filter { it.stage == "PLAYBACK_NET_START" }
                    .groupBy(loadKey)
                val significant = selected.filter { e ->
                    e.level == "FAIL" || e.level == "SLOW" ||
                        (e.stage == "PLAYBACK_NET_ERROR" && loadErrors[loadKey(e)]?.last() === e) ||
                        (e.stage == "PLUGIN_LOG" && (e.level == "ERROR" || pluginFinalNoLinks(e)))
                }
                fun importantDisplay(e: Entry): String {
                    if (e.stage != "PLAYBACK_NET_ERROR") return displayEvent(ctx, e)
                    val key = loadKey(e)
                    val errors = loadErrors[key].orEmpty()
                    val starts = loadStarts[key].orEmpty()
                    val statuses = errors.mapNotNull { field(it.info, "http_status") }
                        .distinct().joinToString(",").ifBlank { "not_exposed" }
                    return "${e.at} session=${e.session} #${e.op} INFO PLAYBACK_LOAD_FAILURES " +
                        "source_ref=${field(e.info, "source_ref") ?: "unknown"} " +
                        "load_id=${key.second} starts_observed=${starts.size} " +
                        "errors_observed=${errors.size} http_statuses=$statuses " +
                        "last_error_type=${field(e.info, "error_type") ?: "unknown"} " +
                        "last_content_type=${field(e.info, "content_type") ?: "not_available"} " +
                        "nonfinal_load_errors=true (${s(ctx, "see_fulltrace")})"
                }
                if (section == "Plugin Logs") {
                    // A poster URL being selected is not evidence that its image loaded.
                    val posterSelections = selected.count {
                        it.stage == "PLUGIN_LOG" && pluginMessage(it.info).contains(Regex("""(?i)_POSTER\b"""))
                    }
                    if (posterSelections > 0) {
                        appendLine()
                        appendLine(s(ctx, "poster_selections", posterSelections))
                        appendLine(s(ctx, "poster_fulltrace"))
                    }
                }
                appendLine()
                appendLine(s(ctx, "failures_slowness"))
                if (significant.isEmpty()) appendLine(s(ctx, "no_failures"))
                if (significant.any { it.stage == "PLAYBACK_NET_ERROR" })
                    appendLine(s(ctx, "callback_note"))
                significant.takeLast(80).forEach { e ->
                    appendLine(importantDisplay(e))
                    appendLine()
                }
                if (selected.any { it.level == "WARN" && it.stage == "HTTP" })
                    appendLine(s(ctx, "http_retry_note"))
            } else {
                appendLine()
                appendLine("=== ${DiagnosticText.section(ctx, section).uppercase(java.util.Locale.getDefault())} (${selected.size}) ===")
                if (selected.isEmpty()) appendLine(s(ctx, "no_events"))
                selected.forEachIndexed { index, e ->
                    if (index > 0 && e.stage != "STACK") appendLine()
                    appendLine(displayEvent(ctx, e))
                }
            }
        }
    }

    fun important(ctx: Context): String = report(ctx, "Live Status", true)
    fun full(ctx: Context): String = report(ctx, "Full timeline", false)
}
