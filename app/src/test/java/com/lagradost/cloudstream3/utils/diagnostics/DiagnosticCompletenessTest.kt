package com.lagradost.cloudstream3.utils.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCompletenessTest {
    @Test
    fun `playback fields beyond old limit remain searchable in one complete event`() {
        val fields = "source_ref=4b5fc70f8eb87bde load_id=6 " +
            "response_metadata=observed ".repeat(24) +
            "failing_host=storage.googleapis.com transport_headers=not_observed"
        val rendered = DiagnosticRecordText.display(
            "02:15:03.217 session=171 #171 INFO PLAYBACK_NET_ERROR", fields, 305,
            "session=171 #171 stage=PLAYBACK_NET_ERROR"
        )
        val report = "CLOUDSTREAM PROVIDER DIAGNOSTIC — Full Trace\nSection: Full Timeline\n\n$rendered"
        val result = DiagnosticSearch.filter(report, "storage.googleapis.com")
        assertTrue(result.startsWith("Search results: 1"))
        assertTrue(result.contains("source_ref=4b5fc70f8eb87bde"))
        assertTrue(result.contains("load_id=6"))
        assertTrue(result.contains("transport_headers=not_observed"))
        assertEquals(fields, DiagnosticRecordText.parts(fields).joinToString(""))
    }

    @Test
    fun `sanitization retains benign fields after 1600 characters and removes secrets there`() {
        val input = "stage=scan ".repeat(200) +
            "url=https://example.com/video.mp4?token=DO_NOT_RETAIN " +
            "password=ALSO_PRIVATE emitted=4 tail_field=preserved"
        val safe = ProviderSafeText.message(input)
        assertTrue(safe.length > 1600)
        assertTrue(safe.contains("emitted=4 tail_field=preserved"))
        assertFalse(safe.contains("DO_NOT_RETAIN"))
        assertFalse(safe.contains("ALSO_PRIVATE"))
        assertTrue(safe.contains("[redacted]"))
    }

    @Test
    fun `long plugin message and its tail survive continuation rendering`() {
        val safe = ProviderSafeText.message("MSM21_OPTIONS " + "server=abyss ".repeat(80) + "last_server=mixdrop")
        val rendered = DiagnosticRecordText.display("02:14:27.212 [MSM21] INFO session=44 #44", safe, 9, "session=44 #44 stage=PLUGIN_LOG")
        assertTrue(rendered.contains("last_server=mixdrop"))
        assertEquals(safe, DiagnosticRecordText.parts(safe).joinToString(""))
        assertTrue(rendered.contains("record_id=9"))
        assertFalse(rendered.contains("\n\n"))
    }

    @Test
    fun `splitting a large field preserves Unicode surrogate pairs`() {
        val text = "x".repeat(419) + "\uD83D\uDE00" + "y".repeat(900)
        val parts = DiagnosticRecordText.parts(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 420 })
        assertTrue(parts.none { it.isNotEmpty() && Character.isHighSurrogate(it.last()) })
        assertTrue(parts.none { it.isNotEmpty() && Character.isLowSurrogate(it.first()) })
    }

    @Test
    fun `short event keeps existing report format`() {
        assertEquals("HEADER source_ref=abc load_id=6", DiagnosticRecordText.display("HEADER", "source_ref=abc load_id=6", 5, "session=1 #1"))
    }

    @Test
    fun `provider identity is stable and does not collapse sanitized names`() {
        val fire = "MSM21 \uD83D\uDD25"
        val star = "MSM21 \u2B50"
        assertEquals(ProviderTrace.providerIdentity(fire), ProviderTrace.providerIdentity(fire))
        assertNotEquals(ProviderTrace.providerIdentity(fire), ProviderTrace.providerIdentity(star))
        assertNotEquals(ProviderTrace.providerIdentity(fire), ProviderTrace.providerIdentity("MSM21 _"))
    }

    private fun traceField(name: String): Any? = ProviderTrace::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(ProviderTrace)
    }

    private fun entryField(entry: Any, name: String): Any? = entry.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(entry)
    }

    private fun retainedEntries(): List<Any> = (traceField("entries") as Iterable<*>).filterNotNull()

    @Test
    fun `emoji provider registration attributes log to its original active operation`() {
        ProviderTrace.clear()
        ProviderTrace.registerProviderHost("MSM21 \uD83D\uDD25", "https://example.com")
        val op = ProviderTrace.begin("LINKS", "MSM21 \uD83D\uDD25")
        ProviderTrace.pluginLog('I', "MSM21", "MSM21_OPTIONS count=8")
        val entry = retainedEntries().last()
        assertEquals(op, entryField(entry, "op"))
        assertEquals(op, entryField(entry, "session"))
        assertTrue((entryField(entry, "info") as String).contains("attribution=single_active_request"))
        ProviderTrace.clear()
    }

    @Test
    fun `two active providers sharing a log tag remain unlinked`() {
        ProviderTrace.clear()
        ProviderTrace.registerProviderHost("MSM21 \uD83D\uDD25", "https://one.example.com")
        ProviderTrace.registerProviderHost("MSM21 \u2B50", "https://two.example.com")
        ProviderTrace.begin("LINKS", "MSM21 \uD83D\uDD25")
        ProviderTrace.begin("LINKS", "MSM21 \u2B50")
        ProviderTrace.pluginLog('I', "MSM21", "MSM21_OPTIONS count=8")
        val entry = retainedEntries().last()
        assertEquals(0L, entryField(entry, "session"))
        assertTrue((entryField(entry, "info") as String).contains("attribution=tag_only_unlinked"))
        ProviderTrace.clear()
    }

    @Test
    fun `closing old player leaves only new operation pending and does not duplicate terminals`() {
        ProviderTrace.clear()
        val old = ProviderTrace.begin("PLAYER", "selected_source")
        ProviderTrace.note(old, "PLAYBACK_NET_ERROR", "metadata=present ".repeat(40) + "failing_host=storage.googleapis.com")
        assertTrue((entryField(retainedEntries().last(), "info") as String).endsWith("failing_host=storage.googleapis.com"))
        ProviderTrace.cancelled(old, "source_changed")
        val new = ProviderTrace.begin("PLAYER", "selected_source")
        val active = traceField("pending") as Map<*, *>
        assertFalse(active.containsKey(old))
        assertTrue(active.containsKey(new))
        val cancellation = retainedEntries().first { entryField(it, "level") == "CANCEL" }
        assertTrue((entryField(cancellation, "info") as String).contains("reason=source_changed"))
        ProviderTrace.finish(new, "first_frame=yes")
        val count = retainedEntries().size
        ProviderTrace.cancelled(new, "released")
        ProviderTrace.cancelled(old, "released")
        assertEquals(count, retainedEntries().size)
        assertTrue((traceField("pending") as Map<*, *>).isEmpty())
        ProviderTrace.clear()
    }

}
