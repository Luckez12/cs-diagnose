package com.lagradost.cloudstream3.utils.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginLogPolicyTest {
    private fun record(message: String, tag: String = "MSM21") =
        "10-02 13:36:05.745  1234  5678 W $tag: $message"

    @Test fun `permission wording in app stdout remains a valid plugin message`() {
        for (message in listOf("Permission denied", "Operation not permitted", "Access denied", "MSM21 console play() not permitted")) {
            val parsed = PluginLogPolicy.parseStdout(record(message))!!
            assertEquals(message, parsed.message)
            assertEquals("MSM21", parsed.tag)
            assertNull(PluginLogPolicy.stderrReason(record(message)))
        }
    }

    @Test fun `unrelated webview errors do not become process access diagnoses`() {
        assertEquals("chromium", PluginLogPolicy.parseStdout(record("Permission denied", "chromium"))!!.tag)
        assertNull(PluginLogPolicy.stderrReason("WebView: Permission denied"))
        assertNull(PluginLogPolicy.stderrReason("Permission denied"))
    }

    @Test fun `only explicit logcat stderr reports identify access denial`() {
        assertEquals("logcat_reported_access_denied", PluginLogPolicy.stderrReason("logcat: Unable to open log device: Permission denied"))
        assertEquals("logcat_reported_access_denied", PluginLogPolicy.stderrReason("/system/bin/logcat: Operation not permitted"))
        assertNull(PluginLogPolicy.parseStdout("logcat: Permission denied"))
    }

    @Test fun `generic logcat process errors do not claim android denied access`() {
        assertEquals("logcat_stderr_reported", PluginLogPolicy.stderrReason("logcat: Unexpected EOF!"))
        assertEquals("logcat_stderr_reported", PluginLogPolicy.stderrReason("logcat: invalid option --pid"))
        assertNull(PluginLogPolicy.stderrReason("--------- beginning of main"))
    }

    @Test fun `threadtime parser preserves colons and unicode in plugin evidence`() {
        val parsed = PluginLogPolicy.parseStdout(record("MSM21: bukti 日本語: tail"))!!
        assertEquals('W', parsed.priority)
        assertEquals("MSM21: bukti 日本語: tail", parsed.message)
        assertNull(PluginLogPolicy.parseStdout("malformed record"))
    }

    @Test fun `recovery backoff stops after the third launch`() {
        assertEquals(1000L, PluginLogPolicy.retryDelayMs(1)!!)
        assertEquals(2000L, PluginLogPolicy.retryDelayMs(2)!!)
        assertNull(PluginLogPolicy.retryDelayMs(3))
        assertNull(PluginLogPolicy.retryDelayMs(4))
    }
}
