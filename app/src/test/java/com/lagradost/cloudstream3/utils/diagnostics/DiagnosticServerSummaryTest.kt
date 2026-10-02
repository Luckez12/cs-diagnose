package com.lagradost.cloudstream3.utils.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticServerSummaryTest {
    private fun event(op: Long, stage: String, info: String, level: String = "INFO", session: Long = op) =
        DiagnosticServerSummary.Event(op, session, level, stage, info, "MSM21 _")
    private val start = event(23, "LINKS", "provider=MSM21 _ casting=false", "START")
    private val link = event(23, "LINK_RECEIVED", "server=upnsMalaySub_6 source_ref=abc number=1")
    private val selected = event(34, "PLAYER_SELECTED", "server=upnsMalaySub_6 source_ref=abc")

    @Test fun `eight option inventory retains missing and unsuccessful extraction rows`() {
        val options = "abyssMalaySub[nume=1,type=mv] || playeMalaySub 2[nume=2,type=mv] || rpmplMalaySub 3[nume=3,type=mv] || seekpMalaySub 4[nume=4,type=mv] || p2pstMalaySub 5[nume=5,type=mv] || upnsMalaySub 6[nume=6,type=mv] || bysesMalaySub 7[nume=7,type=mv] || mixdrMalaySub 8[nume=8,type=mv]"
        val rows = DiagnosticServerSummary.build(listOf(start,
            event(23, "PLUGIN_LOG", "tag=MSM21 attribution=single_active_request MSM21_OPTIONS count=8 $options"), link,
            event(23, "PLUGIN_LOG", "tag=MSM21 attribution=single_active_request MSM21_V12_OPTION_DONE label=mixdrMalaySub 8 mirrors=1 success=false")))
        assertEquals(8, rows.size)
        assertEquals("summary_links_found", rows.single { it.name == "upnsMalaySub_6" }.extraction)
        assertEquals("summary_extraction_empty", rows.single { it.name == "mixdrMalaySub_8" }.extraction)
        assertEquals("summary_extraction_unknown", rows.single { it.name == "abyssMalaySub" }.extraction)
    }

    @Test fun `an extracted link is not playback success and retry error is not final failure`() {
        val rows = DiagnosticServerSummary.build(listOf(start, link, selected,
            event(34, "PLAYBACK_NET_ERROR", "source_ref=abc load_id=6 http_status=403 load_error_not_final=true")))
        assertEquals("summary_links_found", rows.single().extraction)
        assertEquals("summary_pending", rows.single().attempts.single().status)
        assertEquals("not_exposed", rows.single().attempts.single().http)
    }

    @Test fun `frame and later failure both survive in the same attempt`() {
        val attempt = DiagnosticServerSummary.build(listOf(start, link, selected,
            event(34, "PLAYER", "first_frame=yes elapsed=5439ms", "PASS"),
            event(34, "PLAYER", "http_status=403", "FAIL"))).single().attempts.single()
        assertEquals("summary_frame_then_failed", attempt.status)
        assertEquals("5439", attempt.firstFrameMs)
        assertEquals("403", attempt.http)
    }

    @Test fun `different playback attempts do not overwrite an earlier failure`() {
        val rows = DiagnosticServerSummary.build(listOf(start, link, selected,
            event(34, "PLAYER", "http_status=404", "FAIL"),
            event(35, "PLAYER_SELECTED", "server=upnsMalaySub_6 source_ref=abc"),
            event(35, "PLAYER", "first_frame=yes elapsed=1000ms", "PASS")))
        assertEquals(listOf("summary_failed", "summary_frame"), rows.single().attempts.map { it.status })
    }

    @Test fun `ambiguous discovery origins and trailer selection are not assigned to a provider`() {
        val rows = DiagnosticServerSummary.build(listOf(start, link,
            event(24, "LINKS", "provider=Other", "START"),
            event(24, "LINK_RECEIVED", "server=Another source_ref=abc"), selected,
            event(39, "PLAYER_SELECTED", "server=YouTube source_ref=trailer")))
        val attempted = rows.filter { it.attempts.isNotEmpty() }
        assertEquals(2, attempted.size)
        assertTrue(attempted.all { it.owner == null && it.provider == "unknown" })
        assertTrue(rows.filter { it.owner != null }.all { it.attempts.isEmpty() })
    }

    @Test fun `unlinked delayed terminal does not close another discovery operation`() {
        val rows = DiagnosticServerSummary.build(listOf(start, link,
            event(33, "PLUGIN_LOG", "tag=MSM21 attribution=tag_only_unlinked MSM21_V12_OPTION_DONE label=abyssMalaySub mirrors=1 success=false", session = 0)))
        assertEquals(1, rows.size)
        assertEquals("upnsMalaySub_6", rows.single().name)
    }

    @Test fun `source change before first frame records cancellation without a failure`() {
        val attempt = DiagnosticServerSummary.build(listOf(start, link, selected,
            event(34, "PLAYER", "reason=source_changed", "CANCEL"))).single().attempts.single()
        assertEquals("summary_cancelled", attempt.status)
        assertEquals("source_changed", attempt.reason)
        assertFalse(attempt.failed)
    }

    @Test fun `report search finds a summary row without adding unrelated servers`() {
        val rows = DiagnosticServerSummary.build(listOf(start, link,
            event(23, "LINK_RECEIVED", "server=Other source_ref=def")))
        val body = "HEADER\ncounts\n\n" + DiagnosticServerSummary.render(rows) { it }
        val hit = DiagnosticSearch.filter(body, "upnsMalaySub_6")
        assertTrue(hit.contains("links_op=23"))
        assertFalse(hit.contains("server=Other"))
        assertTrue(rows.all { it.attempts.isEmpty() })
    }
}
