# CS Diagnose — AI Handover

Date: 2026-09-21. Latest patch: **v14 Playback Source Diagnostic**.

Read `PROJECT_CONTEXT.md` and `WORKFLOW_RULES.md` first. The latest ACTUAL user's GitHub repo, if accessible, is ground truth; this patch was prepared by overlaying available source ZIP and v6–v13 patches, and does not guarantee there were no independent GitHub edits.

## Last request and evidence

The user tested provider **4KHDHub**, title Euphoria. Full Timeline: 10 links (5 x 2160p, 5 x 1080p) returned in ~8.4s but selected streams failed playback. Different player attempts reported an AC3 parser `ArrayIndexOutOfBoundsException`, `HttpDataSource.InvalidResponseCodeException` (actual HTTP status not recorded by v13), and `UnrecognizedInputFormatException`. The previous log could not match each chosen link/server with the failure. The user said `mula now` to implement Playback Source Diagnostic, not to fix a particular video URL, and explicitly does not permit local APK builds.

## Deliverable

Patch ZIP `cs-diagnose-playback-source-v14.zip` contains exactly these relative repo-root paths:

- `app/src/main/java/com/lagradost/cloudstream3/utils/diagnostics/PlaybackSourceTrace.kt` (new): stable source_ref per URL and safe selected/received link details; capture typed Media3 HTTP error response code, response Content-Type, sanitized failing host/endpoint, Range start, header **names only**, and observed parser/format categories. No HTTP probes or raw exception messages.
- `app/src/main/java/com/lagradost/cloudstream3/utils/diagnostics/ProviderTrace.kt`: structured safe detail for exception outcome; late failure after first-frame PASS remains `FAIL PLAYER`; human-readable live error step from actual captured reason.
- `app/src/main/java/com/lagradost/cloudstream3/ui/APIRepository.kt`: replace number-only `LINK_RECEIVED` note with source-ref link record.
- `app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt`: selected link record at player start; snapshot each player's op/link for callbacks; typed error capture before the existing `PLAYER_ERROR` event.
- `PROJECT_CONTEXT.md`, `WORKFLOW_RULES.md`, `AI_HANDOVER.md` (updated, always include with future patches).

## Constraints / verification

- Preserve official Settings, original Logcat and Diagnostic UI; Diagnose remains below Extensions. Stable ARM64 separate install/build flavor unchanged. No workflow YAML in patch ZIP; existing auto-unzip and auto/manual build on GitHub are unchanged.
- Kotlin/JVM stub test covered source_ref matching, HTTP 403/Content-Type/redirected-host/Range, no emitted header values/URL query secrets, and late player failure being recorded as `PLAYER`. **This is not full Android compilation.** GitHub build and on-device test are still pending; inspect real Kotlin build log if it fails.
- v14 intentionally doesn't promise to log all successful video HTTP status/Content-Type, live media payload bytes or every plugin's private HTTP client; only details available from observed player exceptions are logged. `UnrecognizedInputFormatException` does not prove HTML was returned; an HTTP 403 alone doesn't prove Cloudflare.
- The extension's own output may be collected in Plugin Logs only best-effort. Link URL fingerprints can differ after plugins rewrite source URLs. If user asks for deeper detail, investigate the actual source and request explicit permission before changing the extension or UI.
