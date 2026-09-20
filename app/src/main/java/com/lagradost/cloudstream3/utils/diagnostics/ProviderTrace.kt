package com.lagradost.cloudstream3.utils.diagnostics

import android.os.Looper
import android.os.SystemClock
import com.lagradost.cloudstream3.HomePageResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
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


    // Callers supply only structured, non-secret fields. Never log arbitrary exception text,
    // request bodies, headers, full URLs, title strings or query parameters.
    private fun clean(value: String, limit: Int = 140): String =
        value.replace(Regex("[^a-zA-Z0-9 _.=:+()/-]"), "_").take(limit)

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
        val session = context.get()?.session ?: sessionOverride ?: id
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

    /**
     * Match plugin Log.d/i/w/e by tag to an active provider operation. Unmatched messages
     * never get silently attributed to a provider, even if they run in our app process.
     */
    internal fun pluginLog(priority: Char, tag: String, message: String) = synchronized(lock) {
        val eligible = pending.entries.filter { (_, task) ->
            task.stage in setOf("HOMEPAGE", "HOMEPAGE_SECTION", "SEARCH", "QUICK_SEARCH", "METADATA", "LINKS") &&
                task.session > ignoreThrough &&
                task.provider.isNotBlank() &&
                (tag.equals(task.provider, ignoreCase = true) ||
                 tag.startsWith(task.provider.substringBefore(" _ ").substringBefore(" "), ignoreCase = true) ||
                 message.startsWith("[${task.provider}]", ignoreCase = true))
        }
        val chosen = eligible.maxByOrNull { it.key } ?: return@synchronized
        val level = when (priority) { 'E', 'F' -> "ERROR"; 'W' -> "WARN"; else -> "INFO" }
        record(chosen.key, level, "PLUGIN_LOG", "tag=${clean(tag)} ${ProviderSafeText.message(message)}")
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
        val elapsed = item?.let { SystemClock.elapsedRealtime() - it.since } ?: 0L
        val actor = if (item?.stage == "HTTP" || item?.stage == "CLOUDFLARE") "host" else "provider"
        val recent = if (item?.stage == "LINKS") recentNetwork[item.session]
            ?.joinToString(";", prefix = " recent_http_attempts=", postfix = " (not necessarily cause)") ?: "" else ""
        record(op, "FAIL", item?.stage ?: "REQUEST",
            "$actor=${item?.provider ?: "unknown"} type=${clean(type)} elapsed=${elapsed}ms ${clean(details)}$recent")
    }

    fun cancelled(op: Long) = synchronized(lock) {
        val item = pending.remove(op) ?: return@synchronized
        record(op, "CANCEL", item.stage, "provider=${item.provider} elapsed=${SystemClock.elapsedRealtime() - item.since}ms")
    }

    /** Throwable.message can contain a signed URL or credential, so retain class + safe frames. */
    fun exception(op: Long, cause: Throwable) {
        if (cause is CancellationException) { cancelled(op); return }
        failure(op, cause.javaClass.simpleName.ifBlank { "Throwable" })
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
                    note(op, "HOMEPAGE_RESULT",
                        "groups=${result.items.size} items=${result.items.sumOf { it.list.size }}")
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
        entries.clear(); pending.clear(); operationSessions.clear(); recentNetwork.clear(); dropped = 0L
        // Prevent an old in-flight operation from repopulating a freshly cleared report.
        ignoreThrough = counter
    }

    // Live Status deliberately uses observed facts rather than guessing what a plugin is
    // doing. Extractor-internal parsing / poster loading cannot be inferred from silence.
    private fun field(info: String, key: String): String? =
        Regex("(?:^|\\s)" + Regex.escape(key) + "=([^ ]+)")
            .find(info)?.groupValues?.getOrNull(1)

    private fun liveAction(stage: String, provider: String): String = when (stage) {
        "HOMEPAGE" -> "Sedang memuatkan halaman utama ${provider}."
        "HOMEPAGE_SECTION" -> "Sedang memuatkan bahagian halaman utama."
        "SEARCH", "QUICK_SEARCH" -> "Sedang mencari kandungan dalam ${provider}."
        "METADATA" -> "Sedang mendapatkan maklumat kandungan."
        "LINKS" -> "Sedang mencari pautan video."
        "EXTRACTOR" -> "Sedang mendapatkan pautan daripada extractor."
        "HTTP" -> "Sedang menunggu respons laman ${provider}."
        "CLOUDFLARE" -> "Sedang menjalankan pemeriksaan keselamatan laman."
        "PLAYER", "PLAYBACK" -> "Sedang menyediakan video untuk dimainkan."
        else -> "Sedang menjalankan proses provider."
    }

    private fun liveEvent(e: Entry): String? {
        val stage = e.stage.uppercase(Locale.US)
        val level = e.level
        if (stage == "STACK") return null // Technical stack traces stay in Full trace.
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
                else -> null // Avoid flooding Live Status with successful parallel requests.
            }
        }
        if (level == "SLOW") return when (stage) {
            "HOMEPAGE", "HOMEPAGE_SECTION" -> "Halaman utama mengambil masa lebih lama daripada biasa."
            "LINKS" -> "Pencarian pautan video mengambil masa lebih lama daripada biasa."
            else -> "Proses ${stage.lowercase(Locale.US)} mengambil masa lebih lama daripada biasa."
        }
        if (level == "FAIL") return when (stage) {
            "HOMEPAGE", "HOMEPAGE_SECTION" -> "Halaman utama gagal dimuatkan."
            "LINKS" -> "Pencarian pautan video gagal. Semak Links / Extractor dan HTTP / Network."
            "SEARCH", "QUICK_SEARCH" -> "Carian kandungan gagal."
            "METADATA" -> "Maklumat kandungan gagal dimuatkan."
            else -> "Proses provider gagal. Semak Full trace untuk butiran."
        }
        if (level == "START") return when (stage) {
            "HOMEPAGE" -> "Mula memuatkan halaman utama ${field(e.info, "provider") ?: "provider"}."
            "HOMEPAGE_SECTION" -> "Mula memuatkan satu bahagian halaman utama."
            "METADATA" -> "Mula mendapatkan maklumat kandungan."
            "LINKS" -> "Mula mencari pautan video."
            "SEARCH", "QUICK_SEARCH" -> "Mula mencari kandungan."
            else -> null
        }
        if (level == "PASS") return when (stage) {
            "HOMEPAGE" -> "Halaman utama selesai dimuatkan."
            "HOMEPAGE_SECTION" -> "Satu bahagian halaman utama berjaya dimuatkan."
            "METADATA" -> "Maklumat kandungan berjaya diperoleh."
            "SEARCH", "QUICK_SEARCH" -> "Carian kandungan selesai."
            "LINKS" -> "Pencarian pautan video selesai."
            "PLAYER", "PLAYBACK", "FIRST_FRAME" -> "Video mula dipaparkan oleh player."
            else -> null
        }
        return null
    }

    private fun liveStatus(now: Long): String {
        // Live Status is an APPEND-ONLY HUMAN-READABLE VIEW over the retained trace.
        // Do not replace completed sessions with the latest session, or trim to 8/12
        // messages. Events remain visible until Clear or the bounded event history
        // reaches MAX_ENTRIES (oldest-first eviction to protect app memory).
        val running = pending.entries.filter { it.key > ignoreThrough }
        val latest = running.maxWithOrNull(compareBy<Map.Entry<Long, Pending>> {
            when (it.value.stage) {
                "CLOUDFLARE" -> 6; "HTTP" -> 5; "EXTRACTOR", "LINKS" -> 4
                "HOMEPAGE_SECTION" -> 3; "HOMEPAGE" -> 2; else -> 1
            }
        }.thenBy { it.key })

        // Build session labels from observed provider events, never from a network
        // hostname that may belong to an unrelated request.
        val labels = mutableMapOf<Long, String>()
        entries.forEach { e ->
            if (e.stage in providerStages) {
                field(e.info, "provider")?.takeIf { it.isNotBlank() }?.let {
                    labels[e.session] = it
                }
            }
        }
        running.forEach { (_, task) ->
            if (task.stage in providerStages) labels[task.session] = task.provider
        }

        return buildString {
            appendLine("LIVE STATUS")
            appendLine()
            if (latest != null) {
                val task = latest.value
                val seconds = (now - task.since).coerceAtLeast(0L) / 1000L
                appendLine("SEKARANG: ${liveAction(task.stage, task.provider)}")
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
                val description = liveEvent(e) ?: return@forEach
                if (lastSession != e.session) {
                    if (shown > 0) appendLine()
                    val label = labels[e.session]
                    appendLine("— ${if (label == null) "Sesi #${e.session}" else "$label · Sesi #${e.session}"} —")
                    lastSession = e.session
                }
                appendLine("${e.at}  $description")
                shown++
            }
            if (shown == 0) appendLine("Belum ada langkah yang dapat dikenal pasti. Buka provider dahulu.")
        }.trimEnd()
    }

    /** Existing UI consumes these strings; only the spinner categories and report change. */
    fun report(section: String, importantOnly: Boolean): String = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        if (section == "Live Status") return@synchronized liveStatus(now)
        val selected = entries.filter { section == "Full timeline" || it.section == section }
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
                        (it.stage == "PLUGIN_LOG" && it.level == "ERROR")
                }
                appendLine()
                appendLine("FAILURES / SLOW STAGES")
                if (significant.isEmpty()) appendLine("No failed or slow stages recorded in this section.")
                significant.takeLast(80).forEach { e ->
                    appendLine("${e.at} session=${e.session} #${e.op} ${e.level} ${e.stage} ${e.info}")
                }
                if (selected.any { it.level == "WARN" })
                    appendLine("HTTP warnings may have recovered; check Full trace for retries.")
            } else {
                appendLine()
                appendLine("=== ${section.uppercase(Locale.US)} (${selected.size}) ===")
                if (selected.isEmpty()) appendLine("No events recorded.")
                selected.forEachIndexed { index, e ->
                    if (index > 0 && e.stage != "STACK") appendLine()
                    appendLine("${e.at} session=${e.session} #${e.op} ${e.level} ${e.stage} ${e.info}")
                }
            }
        }
    }

    fun important(): String = report("Live Status", true)
    fun full(): String = report("Full timeline", false)
}
