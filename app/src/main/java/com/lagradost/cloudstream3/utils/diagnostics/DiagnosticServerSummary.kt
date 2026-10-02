package com.lagradost.cloudstream3.utils.diagnostics

/** Reduce retained observations only. Never fetch, retry, probe or infer a playable stream. */
internal object DiagnosticServerSummary {
    data class Event(val op: Long, val session: Long, val level: String, val stage: String, val info: String, val provider: String = "unknown")
    data class Playback(val op: Long, val ref: String, var frame: Boolean = false, var failed: Boolean = false,
                        var cancelled: Boolean = false, var firstFrameMs: String = "not_exposed",
                        var http: String = "not_exposed", var reason: String = "not_exposed") {
        val status: String get() = when {
            failed && frame -> "summary_frame_then_failed"
            failed -> "summary_failed"
            frame -> "summary_frame"
            cancelled -> "summary_cancelled"
            else -> "summary_pending"
        }
    }
    data class Server(val owner: Long?, val name: String, val provider: String,
                      val refs: MutableSet<String> = linkedSetOf(), var empty: Boolean = false,
                      val attempts: MutableList<Playback> = mutableListOf(),
                      var byseVerification: Boolean = false) {
        val extraction: String get() = when {
            refs.isNotEmpty() && empty -> "summary_extraction_mixed"
            refs.isNotEmpty() -> "summary_links_found"
            empty -> "summary_extraction_empty"
            else -> "summary_extraction_unknown"
        }
    }

    private fun field(info: String, key: String): String? =
        Regex("(?:^|\\s)" + Regex.escape(key) + "=([^ ]+)").find(info)?.groupValues?.getOrNull(1)

    fun build(events: List<Event>): List<Server> {
        val rows = linkedMapOf<Pair<Long, String>, Server>()
        val byseInventories = linkedMapOf<Long, MutableSet<String>>()
        val byseVerificationOps = linkedSetOf<Long>()
        val linkOwners = events.filter { it.stage == "LINKS" && it.level == "START" }.map { it.op }.toSet()
        fun row(op: Long, name: String, provider: String): Server =
            rows.getOrPut(op to name) { Server(op, name, provider) }
        for (event in events) {
            if (event.stage == "LINK_RECEIVED") {
                val name = field(event.info, "server") ?: continue
                val ref = field(event.info, "source_ref") ?: continue
                // Explicit callback belongs to this LINKS operation even if its START aged out.
                row(event.op, name, event.provider).refs.add(ref)
            }
            if (event.stage != "PLUGIN_LOG" || event.session <= 0 || event.op !in linkOwners) continue
            if (field(event.info, "tag") != "MSM21" || field(event.info, "attribution") != "single_active_request") continue
            val msg = event.info.substringAfter(" attribution=").substringAfter(' ')
            // This opt-in adapter accepts only known MSM21 evidence grammars.
            // Other plugins remain supported through standard LINK_RECEIVED/player observations.
            if (msg.startsWith("MSM21_OPTIONS count=")) {
                val options = msg.replaceFirst(Regex("^MSM21_OPTIONS count=\\d+\\s*"), "")
                Regex("(?:^|\\|\\|)\\s*([^\\[|]+)\\[nume=\\d+,type=[^]]+]")
                    .findAll(options).forEach { match ->
                        val label = match.groupValues[1].trim()
                        if (label.isNotEmpty()) {
                            val name = ProviderTrace.sectionValue(label)
                            row(event.op, name, event.provider)
                            // Family evidence has no label: require a unique option in explicit inventory.
                            if (Regex("^byses(?:MalaySub)?(?:_\\d+)?$", RegexOption.IGNORE_CASE).matches(name)) {
                                byseInventories.getOrPut(event.op) { linkedSetOf() }.add(name)
                            }
                        }
                    }
            }
            if (msg == "MSM21_V15_BYSE_BLOCKED reason=human_verification_required") {
                byseVerificationOps.add(event.op)
            }
            if (msg.startsWith("MSM21_V12_OPTION_DONE ")) {
                val label = Regex("\\blabel=(.+?)\\s+mirrors=\\d+\\s+success=(true|false)(?:\\s|$)").find(msg) ?: continue
                val server = row(event.op, ProviderTrace.sectionValue(label.groupValues[1]), event.provider)
                if (label.groupValues[2] == "false") server.empty = true
                // success=true alone does not replace an observed link callback.
            }
        }
        // Resolve after reading inventory so log delivery order cannot select the wrong server.
        // This is an explicit family/inventory match, not an originating-coroutine guarantee.
        for (op in byseVerificationOps) {
            val name = byseInventories[op]?.singleOrNull() ?: continue
            rows[op to name]?.byseVerification = true
        }
        val byRef = rows.values.flatMap { server -> server.refs.map { it to server } }
            .groupBy({ it.first }, { it.second })
        val players = linkedMapOf<Long, Playback>()
        val standalone = mutableListOf<Server>()
        for (event in events) {
            if (event.stage != "PLAYER_SELECTED" || event.op in players) continue
            val ref = field(event.info, "source_ref") ?: continue
            val playback = Playback(event.op, ref)
            // Several retained discovery operations can emit the same URL. Do not guess a parent.
            val owner = byRef[ref]?.singleOrNull() ?: Server(
                null, field(event.info, "server") ?: "unknown", "unknown"
            ).also { standalone.add(it) }
            owner.attempts.add(playback)
            players[event.op] = playback
        }
        for (event in events) {
            val playback = players[event.op] ?: continue
            if (event.stage == "FIRST_FRAME" || (event.stage == "PLAYER" && event.level == "PASS" && field(event.info, "first_frame") == "yes")) {
                playback.frame = true
                if (event.level == "PASS") playback.firstFrameMs = field(event.info, "elapsed")?.removeSuffix("ms") ?: "not_exposed"
            }
            if (event.stage == "PLAYER" && event.level == "FAIL") {
                playback.failed = true
                playback.http = field(event.info, "http_status") ?: "not_exposed"
            }
            if (event.stage == "PLAYER" && event.level == "CANCEL") {
                playback.cancelled = true
                playback.reason = field(event.info, "reason") ?: "not_exposed"
            }
            // PLAYBACK_NET_ERROR is deliberately not a final playback outcome.
        }
        return rows.values.toList() + standalone
    }

    fun render(rows: List<Server>, text: (String) -> String): String = buildString {
        appendLine(text("summary_title"))
        appendLine(text("summary_note"))
        appendLine()
        if (rows.isEmpty()) appendLine(text("summary_empty"))
        for ((index, row) in rows.withIndex()) {
            if (index > 0) appendLine()
            appendLine("server=${row.name} provider=${row.provider} links_op=${row.owner ?: "not_determined"} unique_links_observed=${row.refs.size}")
            appendLine("  ${text("summary_extraction")}: ${text(if (row.owner == null) "summary_origin_unknown" else row.extraction)}")
            if (row.byseVerification) {
                appendLine("  ${text("summary_verification")}: ${text("summary_byse_verification")}")
                appendLine("  verification_reason=human_verification_required evidence_source=provider_log evidence_code=MSM21_V15_BYSE_BLOCKED server_match=unique_byse_inventory attribution=single_active_request")
            }
            if (row.attempts.isEmpty()) appendLine("  ${text("summary_playback")}: ${text("summary_not_tested")}")
            for (attempt in row.attempts) {
                appendLine("  ${text("summary_playback")}: ${text(attempt.status)}")
                appendLine("  player_op=${attempt.op} source_ref=${attempt.ref} first_frame_ms=${attempt.firstFrameMs} final_http_status=${attempt.http} cancel_reason=${attempt.reason}")
            }
        }
    }.trimEnd()
}
