# CS Diagnose — Project Context

Last updated: 2026-09-21. Patch version: v14 (Playback Source & Player Error Correlation).

## Purpose

CS Diagnose is a *separate* Android application built from the CloudStream source the user uploaded (`cloudstream-master.zip`) for diagnosing malfunctioning CloudStream providers. The user watches normally using the official CloudStream app. CS Diagnose is a developer-facing diagnostic build, not a new streaming provider and not the VUEO/Nuvio project. Do not confuse VUEO logs or provider-specific sample titles with CS Diagnose issues.

## Existing design (as last reported by the user)

- The user wants original CloudStream Settings and original Logcat UI unchanged. There is one additional **Diagnose** entry below Extensions in Settings, opening a standalone Provider Diagnostic page.
- Diagnostic page: Android status bar and CloudStream bottom navigation remain visible; Important / Full trace mode, selectable category, searchable log, and Copy / Clear / Close.
- Categories: Live Status, Provider Process, HTTP / Network, Plugin Logs, Links / Extractor, Player, Full timeline. Live Status is the first category (not a separate panel or an Overview category).
- Live Status is human-readable, chronological, append-only until Clear or retention limit, separated into provider sessions. A completed process must not remove earlier log lines. Multiple simultaneously loading homepage sections must not be merged or mislabeled.
- Full trace keeps technical events, real outcomes, HTTP response codes, timing, verified Cloudflare challenge stages, extractor attempts, player state, plugin's own logged messages, and safe stack-trace data when instrumented.
- Logs from `android.util.Log` inside installed provider extensions are bridged into Plugin Logs on a *best-effort* same-PID Android Logcat basis. `tag=Anichin` examples in user-provided `AnichinProvider.kt.txt` include HOME, POSTER, DISCOVERY and DONE messages; these are extension logs, not instrumentation built into all providers.
- Logs may contain user content names, provider hosts and sanitized URLs. Do not store raw tokens/cookies/authorization headers, response bodies or signed URL query strings in a copied report. Redaction is best-effort; tell the user to check reports before sharing.

## Current v13 patch scope

This patch builds on v12 (Diagnostic Search + Plugin Logs), without replacing CloudStream's application UI or building an APK:

1. Search keeps an entire multi-line trace event when any line (including the sanitized URL) matches. It preserves relevant Live Status session headings; Copy uses the filtered report already shown in the UI.
2. Full Trace > Plugin Logs formats each event with time, provider tag, level, session, message and attribution on separate readable lines; source text remains sanitized.
3. Important > Plugin Logs summarizes how many POSTER URL-selection logs were observed, without listing long URLs and **without claiming the poster downloaded successfully**. Explicit `success=false` / `emitted=0` plugin result records are surfaced; WARNING alone is not treated as a final failure.
4. Links / Extractor also shows the plugin's link discovery and final-result logs (`*_DISCOVERY`, `*_DONE`, extractor/link indicators), while full Plugin Logs remains the complete provider-tagged stream. Do not assume that every plugin logs these steps.

## Limits / next work

- The installed extension may use a private HTTP client or suppress its own exceptions; CS Diagnose cannot know invisible steps or guess the reason for Link not found.
- A plugin's log that arrives outside an active provider operation can be shown as unlinked. Do not associate it with an arbitrary current session.
- Existing screenshots showed repeated metadata requests and UI mistakes in older patches. Avoid reintroducing earlier experimental Settings/Logcat UI code.
- Only the **GitHub Actions build on the user's repository** will establish whether the full Android project compiles and what the APK actually displays. No APK build was run for v13 here.


## v14 — Playback Source Diagnostic (2026-09-21)

- `APIRepository.loadLinks` calls `PlaybackSourceTrace.received` per returned ExtractorLink: source_ref (SHA-256-derived 16-hex identifier), actual video host, server label, type and quality. No full media URL or signed query recorded.
- `CS3IPlayer.loadPlayer` records PLAYER_SELECTED for the chosen link with the SAME source_ref, redacted endpoint, provider-supplied server/name labels, quality, format, referer HOST, and **presence/names only** of selected request headers (never values).
- Player listener binds a snapshot of the attempt ID/link, preventing callbacks from a previous player from being attached to a newly selected link. Subsequent failures record source_ref, player error code, typed HTTP response code when Media3 exposes InvalidResponseCodeException, response Content-Type when available, sanitized *failing* host/URI, Range start, and classification of observed AC3 parser / unrecognized format failures. Typed stack trace remains in Full Trace.
- The existing `ProviderTrace.exception` accepts structured safe details; if a late player failure happens after first frame/PLAYER PASS, it is still labeled FAIL PLAYER, not FAIL REQUEST.
- Diagnostic UI/Settings/Logcat/workflow are unchanged. No new preflight network call or forced retry is introduced. Source error does not prove server block/codec problem without actual status/content/stack; Content-Type/HTTP status can be absent for decoder errors or sources handled outside instrumented player.
- Search and sections already present in v13 continue working; Important > Player includes actual code and source_ref, Full trace > Player includes detailed evidence. Match identical source_ref values across Links / Extractor and Player. Different link URLs (including signed query variations) produce different source_ref IDs.
- Redaction is best-effort. Check any report before sharing, especially plugin-authored logs. Actual GitHub APK compilation/device behavior remain unverified; only source checks and Kotlin stub tests are run locally, **never an Android APK build**.
