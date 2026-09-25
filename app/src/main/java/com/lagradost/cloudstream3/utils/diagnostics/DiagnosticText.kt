package com.lagradost.cloudstream3.utils.diagnostics

import android.content.Context
import com.lagradost.cloudstream3.R

/** Translated UI/report text only. Event codes, stored trace data and category IDs never change.
 * Resources resolve against the active CloudStream Activity locale. Unknown translations
 * fall back to default English resources, as with the rest of Android resource lookup.
 */
internal object DiagnosticText {
    fun section(ctx: Context, sectionId: String): String = when(sectionId) {
        "Live Status" -> get(ctx, "live_status")
        "Provider Process" -> get(ctx, "provider_process")
        "HTTP / Network" -> get(ctx, "http_network")
        "Plugin Logs" -> get(ctx, "plugin_logs")
        "Links / Extractor" -> get(ctx, "links_extractor")
        "Player" -> get(ctx, "player")
        "Full timeline" -> get(ctx, "full_timeline")
        else -> sectionId
    }

    fun get(ctx: Context, key: String, vararg args: Any): String {
        val resId = when(key) {
            "copy_toast" -> R.string.cs_diag_copy_toast
            "close_descr" -> R.string.cs_diag_close_descr
            "page_title" -> R.string.cs_diag_page_title
            "important" -> R.string.cs_diag_important
            "full_trace" -> R.string.cs_diag_full_trace
            "search_hint" -> R.string.cs_diag_search_hint
            "search_descr" -> R.string.cs_diag_search_descr
            "clear_search_descr" -> R.string.cs_diag_clear_search_descr
            "copy" -> R.string.cs_diag_copy
            "clear" -> R.string.cs_diag_clear
            "close" -> R.string.cs_diag_close
            "live_status" -> R.string.cs_diag_live_status
            "provider_process" -> R.string.cs_diag_provider_process
            "http_network" -> R.string.cs_diag_http_network
            "plugin_logs" -> R.string.cs_diag_plugin_logs
            "links_extractor" -> R.string.cs_diag_links_extractor
            "player" -> R.string.cs_diag_player
            "full_timeline" -> R.string.cs_diag_full_timeline
            "unknown_section" -> R.string.cs_diag_unknown_section
            "unknown" -> R.string.cs_diag_unknown
            "loading_home" -> R.string.cs_diag_loading_home
            "loading_section" -> R.string.cs_diag_loading_section
            "searching_provider" -> R.string.cs_diag_searching_provider
            "loading_details" -> R.string.cs_diag_loading_details
            "searching_links" -> R.string.cs_diag_searching_links
            "resolving_extractor" -> R.string.cs_diag_resolving_extractor
            "waiting_site" -> R.string.cs_diag_waiting_site
            "waiting_security" -> R.string.cs_diag_waiting_security
            "preparing_video" -> R.string.cs_diag_preparing_video
            "running_provider" -> R.string.cs_diag_running_provider
            "kind_anime" -> R.string.cs_diag_kind_anime
            "kind_series" -> R.string.cs_diag_kind_series
            "kind_movie" -> R.string.cs_diag_kind_movie
            "kind_content" -> R.string.cs_diag_kind_content
            "no_episode_list" -> R.string.cs_diag_no_episode_list
            "episode_records_unique" -> R.string.cs_diag_episode_records_unique
            "episode_records_seasons" -> R.string.cs_diag_episode_records_seasons
            "episode_records" -> R.string.cs_diag_episode_records
            "metadata_summary" -> R.string.cs_diag_metadata_summary
            "movie_selected" -> R.string.cs_diag_movie_selected
            "episode_selected" -> R.string.cs_diag_episode_selected
            "unknown_server" -> R.string.cs_diag_unknown_server
            "video_selected" -> R.string.cs_diag_video_selected
            "video_http_rejected" -> R.string.cs_diag_video_http_rejected
            "video_format_error" -> R.string.cs_diag_video_format_error
            "plugin_problem" -> R.string.cs_diag_plugin_problem
            "security_complete_retry" -> R.string.cs_diag_security_complete_retry
            "security_unconfirmed" -> R.string.cs_diag_security_unconfirmed
            "security_retry" -> R.string.cs_diag_security_retry
            "security_retry_status" -> R.string.cs_diag_security_retry_status
            "security_detected" -> R.string.cs_diag_security_detected
            "security_failed" -> R.string.cs_diag_security_failed
            "security_finished" -> R.string.cs_diag_security_finished
            "site_http_warn" -> R.string.cs_diag_site_http_warn
            "site_warn" -> R.string.cs_diag_site_warn
            "site_failed" -> R.string.cs_diag_site_failed
            "section_slow" -> R.string.cs_diag_section_slow
            "home_slow" -> R.string.cs_diag_home_slow
            "links_slow" -> R.string.cs_diag_links_slow
            "process_slow" -> R.string.cs_diag_process_slow
            "section_failed" -> R.string.cs_diag_section_failed
            "home_failed" -> R.string.cs_diag_home_failed
            "links_failed" -> R.string.cs_diag_links_failed
            "search_failed" -> R.string.cs_diag_search_failed
            "metadata_failed" -> R.string.cs_diag_metadata_failed
            "provider_failed" -> R.string.cs_diag_provider_failed
            "section_canceled" -> R.string.cs_diag_section_canceled
            "home_started" -> R.string.cs_diag_home_started
            "section_started" -> R.string.cs_diag_section_started
            "metadata_started" -> R.string.cs_diag_metadata_started
            "links_started" -> R.string.cs_diag_links_started
            "search_started" -> R.string.cs_diag_search_started
            "home_finished" -> R.string.cs_diag_home_finished
            "section_finished_unknown" -> R.string.cs_diag_section_finished_unknown
            "section_finished_count" -> R.string.cs_diag_section_finished_count
            "metadata_finished" -> R.string.cs_diag_metadata_finished
            "search_finished" -> R.string.cs_diag_search_finished
            "links_finished" -> R.string.cs_diag_links_finished
            "video_first_frame" -> R.string.cs_diag_video_first_frame
            "section_number" -> R.string.cs_diag_section_number
            "now_sections" -> R.string.cs_diag_now_sections
            "section_waiting" -> R.string.cs_diag_section_waiting
            "more_sections" -> R.string.cs_diag_more_sections
            "waiting_responses" -> R.string.cs_diag_waiting_responses
            "security_unlinked" -> R.string.cs_diag_security_unlinked
            "now_status" -> R.string.cs_diag_now_status
            "waiting_seconds" -> R.string.cs_diag_waiting_seconds
            "now_idle" -> R.string.cs_diag_now_idle
            "history" -> R.string.cs_diag_history
            "dropped_old" -> R.string.cs_diag_dropped_old
            "session" -> R.string.cs_diag_session
            "no_steps" -> R.string.cs_diag_no_steps
            "attr_active" -> R.string.cs_diag_attr_active
            "attr_provider" -> R.string.cs_diag_attr_provider
            "attr_unlinked" -> R.string.cs_diag_attr_unlinked
            "attr_unverified" -> R.string.cs_diag_attr_unverified
            "origin_label" -> R.string.cs_diag_origin_label
            "report_counts" -> R.string.cs_diag_report_counts
            "collector_status" -> R.string.cs_diag_collector_status
            "in_progress" -> R.string.cs_diag_in_progress
            "poster_selections" -> R.string.cs_diag_poster_selections
            "poster_fulltrace" -> R.string.cs_diag_poster_fulltrace
            "failures_slowness" -> R.string.cs_diag_failures_slowness
            "no_failures" -> R.string.cs_diag_no_failures
            "callback_note" -> R.string.cs_diag_callback_note
            "http_retry_note" -> R.string.cs_diag_http_retry_note
            "no_events" -> R.string.cs_diag_no_events
            "see_fulltrace" -> R.string.cs_diag_see_fulltrace
            "search_results" -> R.string.cs_diag_search_results
            "search_no_match" -> R.string.cs_diag_search_no_match
            "menu" -> R.string.cs_diag_menu
            else -> return key // Stable, non-sensitive fallback for an unknown UI key.
        }
        return ctx.getString(resId, *args)
    }
}
