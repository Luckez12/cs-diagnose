# CS Diagnose — AI Handover

Date: 2026-09-21. Current patch: **v15 Playback Network Trace**. Read PROJECT_CONTEXT.md and WORKFLOW_RULES.md before making changes.

## Why v15

User tested 4KHDHub/Euphoria: 10 extracted links failed with different outcomes (AC3 parser exception, Media3 HTTP 403/404, unrecognized media format). v14 now correlates errors with individual server/selected link, but does not expose successful load response metadata or all attempt-level Media3 events. User explicitly wants only the most accurate possible information for future manual debugging, **not an automated fixer**. User said `mula now` to implement observation of actual playback responses without extra requests, UI changes or local APK build.

## Exact files delivered inside `cs-diagnose-playback-network-v15.zip`

- NEW `app/src/main/java/com/lagradost/cloudstream3/utils/diagnostics/PlaybackNetworkTrace.kt`: per-player passive Media3 AnalyticsListener logging load START/COMPLETE/ERROR/CANCEL; source_ref, load_id, actual URI/headers metadata when exposed; sanitized fields only. No request header values or response bodies. HTTP status on successful load explicitly unavailable; error code only from typed InvalidResponseCodeException. Media/cache source of loaded bytes is not established here.
- CHANGED `app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt`: attach observer to ExoPlayer instance for selected link; no changes to its media sources, transport or playback.
- CHANGED `app/src/main/java/com/lagradost/cloudstream3/utils/diagnostics/PlaybackSourceTrace.kt`: expose `sourceRef()` internally to use the identical hash for the per-player network observer.
- CHANGED `app/src/main/java/com/lagradost/cloudstream3/utils/diagnostics/ProviderTrace.kt`: include individual observed PLAYBACK_NET_ERROR events in Important and state explicitly that an attempt-level load error may recover.
- UPDATED PROJECT_CONTEXT.md, WORKFLOW_RULES.md, AI_HANDOVER.md (three required handover docs).

## Invariants, limitations and testing

- Settings, original Logcat, Diagnose UI, ABI/flavor/app package, all plugin code, Gradle and workflow YAMLs unchanged. Only selected source listener added. No probing, rewrites, forced retry, speculative diagnosis, or additional network traffic. ZIP has correct repo-relative paths and no YAML.
- The observer reports Media3 media-source load events; it does not see *every* underlying TCP/HTTP exchange or guarantee status codes on successful loads. It cannot classify the actual returned body as HTML/video from Content-Type alone; response header metadata may be missing on cache hits, and actual final URI is only available when Media3 supplies it. No raw response bodies, tokens or full signed URLs stored.
- Narrow Kotlin/JVM stub test of observer and static checks were run locally (no Android APK build/compilation). GitHub build and on-phone checks are still necessary. On build failure inspect GitHub Actions log. If a user reports events absent, check AnalyticsListener media-source dispatch for the selected stream before adding intrusive hooks; never promise missing data is available.
- Patch made against local v14 source snapshot; if current GitHub repo has other modifications, review changes before replacing overlapping files. After install test 4KHDHub → Euphoria → failed links and send **Full Trace → Player**. Look for `PLAYBACK_NET_START`, `PLAYBACK_NET_COMPLETE`, `PLAYBACK_NET_ERROR`, `PLAYBACK_NET_TARGET` with matching source_ref/load_id and existing `FAIL PLAYER`.
