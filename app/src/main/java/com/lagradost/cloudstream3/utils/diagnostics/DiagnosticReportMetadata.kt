package com.lagradost.cloudstream3.utils.diagnostics

import android.content.Context
import android.os.Build
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.plugins.PluginManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small export-only context. No network access, plugin file paths, repository URLs or device IDs. */
internal object DiagnosticReportMetadata {
    fun wrap(ctx: Context, report: String, section: String, full: Boolean, query: String): String = buildString {
        appendLine(DiagnosticText.get(ctx, "export_title"))
        appendLine("diagnostic_schema=server_summary_v1")
        appendLine("exported_at=" + SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
        appendLine("app_version=${ProviderSafeText.message(BuildConfig.VERSION_NAME)} app_version_code=${BuildConfig.VERSION_CODE} android_api=${Build.VERSION.SDK_INT}")
        appendLine("mode=${if (full) "FullTrace" else "Important"} section=${ProviderTrace.sectionValue(section)}")
        appendLine("search_query=${ProviderSafeText.message(query).ifBlank { "none" }}")
        val collector = PluginLogCollector.snapshot()
        appendLine("plugin_log_collector=${ProviderSafeText.message(collector.state)}")
        appendLine(collector.fields())
        if (collector.gaps > 0 || collector.gapOpen) appendLine(DiagnosticText.get(ctx, "collector_gap_note"))
        val identities = ProviderTrace.retainedProviderIdentities()
        val versions = runCatching { (PluginManager.getPluginsLocal() + PluginManager.getPluginsOnline()).toList() }.getOrNull()
        for ((identity, name) in identities) {
            val version = runCatching {
                val api = APIHolder.apis.filter { ProviderTrace.providerIdentity(it.name) == identity }.singleOrNull()
                val path = api?.sourcePlugin
                versions?.filter { path != null && it.filePath == path }?.singleOrNull()?.version?.takeIf { it >= 0 }
            }.getOrNull()
            appendLine("provider=${ProviderTrace.sectionValue(name)} installed_plugin_version=${version ?: "not_exposed"}")
        }
        appendLine(DiagnosticText.get(ctx, "export_note"))
        appendLine()
        append(report)
    }
}
