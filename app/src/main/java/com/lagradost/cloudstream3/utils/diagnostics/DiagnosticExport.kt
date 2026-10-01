package com.lagradost.cloudstream3.utils.diagnostics

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Memory only: never place a potentially large diagnostic in saved-instance-state/Intent extras. */
class DiagnosticExportState : ViewModel() {
    internal var pendingReport: String? = null
    internal var writing = false

    internal fun write(context: Context, uri: Uri, report: String, success: String, failure: String) {
        // Keep only application Context while IO runs; ViewModel survives Activity rotation.
        val app = context.applicationContext
        writing = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Document provider returned no output stream")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                }
                // Success is reported only after the writer and underlying stream close.
                Toast.makeText(app, success, Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never expose provider exception messages/URIs in a toast or diagnostic log.
                Toast.makeText(app, failure, Toast.LENGTH_LONG).show()
            } finally {
                writing = false
            }
        }
    }
}

internal object DiagnosticExport {
    private const val RESULT_KEY = "cs-diagnose-save-document"

    private fun state(activity: ComponentActivity): DiagnosticExportState =
        ViewModelProvider(activity)[DiagnosticExportState::class.java]

    /** Register from onCreate, before STARTED, including after Activity recreation. */
    fun register(activity: ComponentActivity): ActivityResultLauncher<String> {
        val state = state(activity)
        return activity.activityResultRegistry.register(
            RESULT_KEY, activity, ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            val report = state.pendingReport
            state.pendingReport = null
            if (uri == null) return@register // User cancelled: keep logs, no error toast.
            if (report == null) {
                // Process death discards the in-memory snapshot. Never claim an empty file is saved.
                Toast.makeText(activity, DiagnosticText.get(activity, "save_failed"), Toast.LENGTH_LONG).show()
                return@register
            }
            state.write(
                activity.applicationContext, uri, report,
                DiagnosticText.get(activity, "save_success"),
                DiagnosticText.get(activity, "save_failed")
            )
        }
    }

    fun save(
        activity: ComponentActivity,
        launcher: ActivityResultLauncher<String>,
        report: String,
        section: String,
        full: Boolean
    ) {
        val state = state(activity)
        if (state.pendingReport != null || state.writing) {
            Toast.makeText(activity, DiagnosticText.get(activity, "save_busy"), Toast.LENGTH_SHORT).show()
            return
        }
        // The caller supplies the same filtered report as Copy. Freeze before opening the picker.
        state.pendingReport = report
        val sectionName = section.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        val mode = if (full) "FullTrace" else "Important"
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        try {
            launcher.launch("diagnose_${sectionName}_${mode}_${timestamp}.txt")
        } catch (_: Exception) {
            state.pendingReport = null
            Toast.makeText(activity, DiagnosticText.get(activity, "save_failed"), Toast.LENGTH_LONG).show()
        }
    }
}
