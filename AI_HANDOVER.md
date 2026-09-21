# CS Diagnose — AI Handover

Date: 2026-09-21. Latest patch: **v15.1 Accuracy & Stability Fix**. Read `PROJECT_CONTEXT.md` and `WORKFLOW_RULES.md` before edits. The user prefers short, casual Malay replies, ZIP patches with correct repository-relative paths, and no local APK builds. The user uses official CloudStream separately; CS Diagnose observes provider/playback problems and **must never attempt automatic repairs or change behavior**.

## Current source of truth / caveat

v15.1 was prepared against `cs-diagnose-playback-network-v15.zip` present in the conversation runtime. User's live `cs-diagnose` GitHub contents were **not verified**. If they have made independent changes since v15, compare the three edited Kotlin files before applying. This v15.1 ZIP is a delta patch, not a whole source archive. No YAML included or changed; existing auto-unzip/build workflows remain in use.

## Files in `cs-diagnose-playback-accuracy-v15-1.zip`

1. `app/src/main/java/com/lagradost/cloudstream3/utils/diagnostics/PlaybackSourceTrace.kt`: explicit field names for supplied `ExtractorLink` Referer/header configuration; does not claim transport headers were sent. Error-side DataSpec header keys likewise explicitly labelled as such.
2. `app/src/main/java/com/lagradost/cloudstream3/utils/diagnostics/PlaybackNetworkTrace.kt`: labels explicit `dataSpec` header keys vs unknown on-wire headers, labels Media3 reported duration instead of per-attempt duration, avoids a truncated duplicate failing URL inside error entries; the sanitized failing URL belongs to TARGET. Compare initial/final URI without asserting full redirect history. HTTP status remains `not_exposed` when Media3 cannot expose it.
3. `app/src/main/java/com/lagradost/cloudstream3/utils/diagnostics/ProviderTrace.kt`: Important groups nonfinal playback load-error events by player operation + load ID; provides observed START/ERROR callback counts and distinct observed HTTP statuses. Full Trace retains all individual retries/errors/targets/stack frames unchanged.
4. `PROJECT_CONTEXT.md`, `WORKFLOW_RULES.md`, `AI_HANDOVER.md`: updated project context/rules.

## Why and what remains

The 4KHDHub Euphoria test showed one PixelDrain load ID reporting 4 separate HTTP 404 onLoadError callbacks followed by final PLAYER failure, and other servers gave HTML, x-zip Content-Type, AC3 parser errors or 403. An ExtractorLink may have Referer configured even when the event DataSpec contains no *explicit* Referer key, because factory defaults and actual Cronet request headers are not visible here. **Do not claim the Referer was or was not transmitted.** Full Trace retains all evidence; Important is a summary only, not a substitute for Full Trace.

An actual captured wire-level header trace would require carefully scoped passive instrumentation at the transport layer; do not add intrusive interceptors, probes, or changes to requests without discussing and verifying feasibility. The Media3 analytics callbacks alone cannot reliably expose a successful HTTP status, all redirects, every network request, or true payload format.

## Integration/testing

Place ZIP in repo root so existing auto-unzip workflow extracts and deletes it after success; GitHub Actions builds APK, not the assistant locally. Static source diff and ZIP integrity checks do not prove Android compile or phone behavior. After build, test several different links; compare Important summary for `starts_observed`, `errors_observed`, `http_statuses` and final PLAYER outcome against **Full Trace → Player**. Check `PLAYER_SELECTED link_referer_configured` vs `PLAYBACK_NET_START dataspec_referer_key`; both are observations at different layers and neither proves actual outgoing Referer.

Keep official Settings and Logcat unchanged, Diagnose menu under Extensions, Stable ARM64 debug with separate package ID, existing UI, Plugin Logs, Search and all provider timeline history. Update all three context docs with each future patch. Don't infer provider fixes from Diagnostic results; expose evidence and uncertainties.
