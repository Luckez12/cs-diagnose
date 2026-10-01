package com.lagradost.cloudstream3.utils.diagnostics

/** Split only the rendered text; retain one complete, sanitized logical event in the trace. */
internal object DiagnosticRecordText {
    private const val PART_LENGTH = 420

    fun parts(text: String): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            var end = minOf(offset + PART_LENGTH, text.length)
            if (end < text.length) {
                // Prefer a field boundary, preserving the space for exact reassembly.
                val space = text.lastIndexOf(' ', end - 1)
                if (space > offset) end = space + 1
                // A large field may contain a supplementary Unicode character.
                if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            }
            result.add(text.substring(offset, end))
            offset = end
        }
        return result
    }

    fun display(header: String, text: String, recordId: Long, identity: String): String {
        val parts = parts(text)
        if (parts.size == 1) return "$header $text"
        return buildString {
            append("$header record_id=$recordId parts=${parts.size}")
            parts.forEachIndexed { index, part ->
                append("\n  DETAIL record_id=$recordId $identity part=${index + 1}/${parts.size} text=$part")
            }
        }
    }
}
