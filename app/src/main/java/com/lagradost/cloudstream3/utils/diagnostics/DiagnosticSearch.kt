package com.lagradost.cloudstream3.utils.diagnostics

/** Search the report that has ALREADY been sanitized by ProviderTrace.
 * Full Trace entries are multi-line blocks: keep the event header, context and
 * sanitized URL when any line in that event matches the search term.
 * Live Status uses provider/session headings: searching a provider shows its
 * history; searching a step shows matching steps under their session headings.
 */
internal object DiagnosticSearch {
    private val sessionHeading = Regex("^[–—-]\\s+.+(?:[•·]).*Sesi\\s*#")

    fun filter(report: String, query: String): String {
        val term = query.trim()
        if (term.isEmpty()) return report
        val isLiveStatus = report.startsWith("LIVE STATUS")
        val hits = if (isLiveStatus) searchSessions(report, term) else searchEvents(report, term)
        return buildString {
            appendLine("Hasil carian: ${hits.size}")
            appendLine()
            if (hits.isEmpty()) append("Tiada log yang sepadan.")
            else append(hits.joinToString("\n\n"))
        }
    }

    private fun searchEvents(report: String, term: String): List<String> {
        // ProviderTrace.report separates each event with an empty line. Never
        // treat the section title as a match for every event in the category.
        return report.trim().split(Regex("\\n[ \\t]*\\n+"))
            .drop(1) // report header (and IN PROGRESS if present)
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains(term, ignoreCase = true) }
    }

    private fun searchSessions(report: String, term: String): List<String> {
        val matches = mutableListOf<String>()
        var header: String? = null
        val events = mutableListOf<String>()
        fun flush() {
            val heading = header
            if (heading != null) {
                val visible = if (heading.contains(term, ignoreCase = true)) events
                    else events.filter { it.contains(term, ignoreCase = true) }
                if (visible.isNotEmpty()) matches.add((listOf(heading) + visible).joinToString("\n"))
            } else {
                // Current running status, before the first historical session.
                matches.addAll(events.filter { it.contains(term, ignoreCase = true) })
            }
            events.clear()
        }
        for (line in report.lines()) {
            if (sessionHeading.containsMatchIn(line)) {
                flush()
                header = line
            } else {
                events.add(line)
            }
        }
        flush()
        return matches.filter { it.isNotBlank() }
    }
}
