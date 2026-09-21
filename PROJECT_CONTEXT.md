# CS Diagnose — Project Context

Last updated: 2026-09-21. Latest patch: **v15.1 Accuracy & Stability Fix**. This is the CS Diagnose Android project based on the user's supplied official CloudStream source, not VUEO or Nuvio. Normal watching takes place in the separate official CloudStream APK; CS Diagnose is intended only to collect the most accurate diagnostic evidence for providers, network/extractors, and playback. It must **not** fix, rewrite, probe, filter out, or change the behavior of provider/stream requests to diagnose them.

## Established interface and packaging

- Keep the original official CloudStream Settings and Logcat UI unchanged. Add only the separate **Diagnose** menu below Extensions in Settings. Diagnostic's page is already accepted; Important / Full trace, Search, Copy, Clear and Close are retained.
- Sections: Live Status, Provider Process, HTTP / Network, Plugin Logs, Links / Extractor, Player, Full timeline. Live Status replaces Overview IN THE CATEGORY MENU, not as an extra panel. It is a human-readable append-only history with correctly named concurrent homepage sections and in-flight steps.
- The App targets phone ARM64, Stable Debug, separate package ID so it installs alongside official CloudStream. Never change these or workflows without asking. ZIP patches have accurate relative repo-root paths, auto-unzip via GitHub Actions; workflow `.yml` files stay separate when they need changes.
- Source of truth is the user's CURRENT GitHub `cs-diagnose` repo. v15.1 is a delta against the locally available v15 ZIP, not a verified live snapshot of GitHub; reconcile any independent GitHub edits before applying.
- Three documentation files PROJECT_CONTEXT.md, WORKFLOW_RULES.md and AI_HANDOVER.md must be updated inside EVERY future source patch ZIP.

## Diagnostic principles

- Record real observed events and provenance, not guesses. An HTTP 403 does not prove Cloudflare. A plugin-produced source does not imply playback success. An HTML Content-Type is a reported response header, not inspection of actual data bytes. One Media3 onLoadError is not necessarily final; playback may retry.
- All logs remain searchable and long technical details belong in Full trace. Sensitive URLs/queries, tokens, cookies, authorization values, raw response bodies and arbitrary exception messages must not be stored in shared reports. Sanitize by default and instruct users to inspect the copied report before sharing (redaction best-effort).
- An extension's Log.d/i/w/e may be observable through same-PID Plugin Logs best-effort; its private HTTP client or suppressed internal steps may not be. Don't invent unobserved provider internals.

## Prior work through v14

- Provider homepage sections, metadata/title/episode context, HTTP and Cloudflare hooks where instrumented; source/extension log categories; links/extractor and player tracing.
- v14 assigns a stable `source_ref` hash of URL per received/selected link and records chosen server, quality, sanitized host and endpoint, v14 typed playback exceptions, HTTP status/Content-Type on InvalidResponseCodeException, AC3/unrecognized format indicators and safe stack frames. `LINK_RECEIVED` can be matched to `PLAYER_SELECTED` and `FAIL PLAYER` by source_ref.

## v15 implementation — passive Media3 playback-load evidence

- Added `PlaybackNetworkTrace.kt`. Each selected stream's ExoPlayer instance gets its own AnalyticsListener and frozen source_ref + PLAYER operation. From actual `onLoadStarted`, `onLoadCompleted`, `onLoadError` and `onLoadCanceled`, log source_ref, loadTaskId, data/track type, requested source, sanitized initial/final URL and host, whether a redirect was observed, Range position/requested length/explicit header-presence flags, observed duration, bytes read, and **allowlisted** response Content-Type, Content-Length, Content-Range, Accept-Ranges when exposed. Typed HTTP error code only when Media3 `InvalidResponseCodeException` exposes one. The `Content-Type` classification is a hint, not a sample of the body. Error details and sanitized failing URL are split to avoid the per-event 420-character cap.
- Successful Media3 loads **do not expose a reliable HTTP success status code** through this listener. Log `http_status=not_exposed` rather than fabricate 200 or 206. Media3 may serve cache reads; report `cache_or_network=not_determined`, not "network request definitely succeeded". A changed initial/final URI proves a redirect was observed but the number of hops is not available.
- Important includes `PLAYBACK_NET_ERROR` with an explicit **nonfinal** warning. Full trace keeps starts/completions/cancels and errors; user can match by load_id, source_ref and player op. No independent requests, preflight/probing, modified player data sources, altered headers or forced retries; app should play as before.
- Known limitation: AnalyticsListener is at the media-source layer, not a raw socket trace and may not report every chunk/redirect or successful wire HTTP code. Some subtitles/audio/media/cache events may be in the same player session; `media_data_type` and `track_type` are retained to distinguish without inventing stream attribution. The 2500-entry in-memory ring may discard oldest high-volume logs; retention and UI remain unchanged here.
- Only a narrow Kotlin/JVM stub test of the new observer, static source checks and ZIP validation were run. **No local Android APK build**, no GitHub compilation or phone verification. If GitHub fails, use the actual log to correct the code.

## v15.1 Accuracy & Stability Fix (2026-09-21)

- `PLAYER_SELECTED` now labels Referer and header names as *ExtractorLink configuration* (`link_referer_configured`, `link_header_names`), not proof of headers transmitted on the wire. `PLAYBACK_NET_START` labels explicit DataSpec keys (`dataspec_referer_key`, etc.) and explicitly says `transport_headers=not_observed`. Media3 observer cannot verify factory-default/Cronet-generated headers, so false does **not** establish missing Referer on HTTP transport.
- `PLAYBACK_NET_ERROR` retains typed HTTP code only when exposed, response metadata, and Media3's reported load duration; it no longer embeds a duplicate URI that could be cut to `https://` by the entry-size limit. `PLAYBACK_NET_TARGET` holds the sanitized failing URI. `initial_final_uri_differ` reports only a comparison of two observed URIs, **not** that no redirects occurred.
- Important groups repeated nonfinal `PLAYBACK_NET_ERROR` callbacks by **player op + load_id**. It shows numbers of observed START/ERROR callbacks and the observed HTTP status set, along with the final PLAYER failure if recorded. It does not equate callback count with actual network requests or treat each load error as final. Full Trace retains individual callbacks, targets and stack frames as before.
- No new HTTP calls, probes, retries, header changes, filtering of links or playback behavior changes. Original Settings/Logcat and Diagnose layout/sections remain unchanged.
- Static/ZIP checks are not an Android compilation or device verification. Build only via existing GitHub Actions. If source has since diverged from v15, review overlap before uploading.
