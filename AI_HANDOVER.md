# CS Diagnose — AI Handover

Date: 2026-10-02. Latest patch: **Save Diagnostic**. Read `PROJECT_CONTEXT.md` and `WORKFLOW_RULES.md` before edits. The user prefers short, casual Malay replies, ZIP patches with correct repository-relative paths, and no local APK builds. The user uses official CloudStream separately; CS Diagnose observes provider/playback problems and **must never attempt automatic repairs or change behavior**.

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

## v15.2 Localization patch (2026-09-24)
- Patch from the locally reconstructed official-source + v7–v15.1 patch chain, **not verified against latest live GitHub repo**. Do not silently overwrite independent changes. Modified: `DiagnosticDialog.kt`, `DiagnosticSearch.kt`, `ProviderTrace.kt`, `res/layout/main_settings.xml`; new: `DiagnosticText.kt`, `res/values/cs_diagnose_strings.xml`, `res/values-b+ms/cs_diagnose_strings.xml`; updated 3 handover docs. No workflow changes.
- App selected locale comes from CloudStream `CommonActivity.updateLocale()`/Activity resources, not phone language guesses. English default + Malay translation are provided; unsupported locales fall back to English. The dropdown maps translated captions to unchanged internal section IDs. Search recognizes localized Live Status/Session headings, and Copy uses localized report. Stored events and machine-readable Full Trace keys stay English; raw extension Log messages are never translated.
- Validate runtime appearance on GitHub-built APK by switching Settings language English → Malay → a third language, reopening Diagnose each time; check Live Status, Search, Important, Full Trace category labels, Copy, existing Settings and original Logcat. Android compilation and device UI have NOT been tested locally.

## Save diagnostic patch (2026-10-02)

- Base: user-uploaded `cs-diagnose-main (1).zip`, ZIP commit comment `05c7232191b9eaf7d1f15bc57e2d2672ca087a0f`. Compared all changed files with an untouched extraction of that upload; live GitHub contents were not fetched.
- Approved UI: one fixed action row **Copy | Save | Clear | Close**. Four equal-width 48dp-high buttons, reduced horizontal padding, 14sp font with 10–14sp autosizing, single-line labels. English/Malay Save and status resources follow the selected app locale. Actual device fit, font scaling and TV focus still require runtime verification.
- Save exports the same mode/category/search-filtered report as Copy, as UTF-8 `.txt`, without truncating the report. Default name includes the stable section ID, mode and local timestamp. The Android CreateDocument picker chooses the location/name; no new storage permissions, dependencies, manifest changes or workflows.
- MainActivity registers the document launcher in onCreate, before STARTED. A ViewModel holds the report snapshot in memory while the picker is open and across Activity rotation. No report is passed through Intent extras, saved-state Bundles or clipboard. IO runs on Dispatchers.IO, using application Context; success is shown only after stream close. Duplicate saves are blocked while picking/writing. Cancellation releases the snapshot without clearing trace history.
- If the OS kills the process while the picker is open, the snapshot is lost; return shows failure and the user must reopen Diagnose and save again. A failed write can leave an incomplete document. Export cannot recover entries already discarded by the existing 2500-entry trace ring.
- New: DiagnosticExport.kt. Modified: MainActivity.kt, DiagnosticDialog.kt, DiagnosticText.kt, default/Malay cs_diagnose_strings.xml, and these three project documents. Other provider/player/trace collection, Settings, Logcat and build configuration files are untouched.
- Validation: static source/diff checks, localized XML parsing and patch ZIP integrity/path checks. No local APK build, Android compilation, device UI verification or GitHub Actions run. Runtime acceptance: save a long Full Trace, compare file against Copy with identical filters; test Important, each category/search, BM/English, cancel, unavailable picker/IO failure, double tap, rotation and large font.
