# CS Diagnose — AI Handover

Date: 2026-09-21. Latest patch: **v13 Search + Plugin Log Readability**.

**Read `PROJECT_CONTEXT.md` and `WORKFLOW_RULES.md` first.** Use the most recent actual repository tree or source ZIP as ground truth; old patches are not guaranteed to represent the GitHub repository's current files. The user asked for a patch ZIP with correct embedded paths, never a locally built APK.

## Last user request

The user showed Plugin Logs capturing `ANICHIN_V57_HOME` and multiple `ANICHIN_V57_POSTER` lines and asked to improve search, readability, and reduce poster URL clutter in Important, while making link discovery results visible for diagnosing `Link not found`. They previously requested context documentation in the next patch for handing the project to another AI.

## v13 deliverables

- `app/src/main/java/com/lagradost/cloudstream3/utils/diagnostics/DiagnosticDialog.kt`: delegates existing search UI to `DiagnosticSearch.filter`; no change to page layout, Settings or Logcat.
- `app/src/main/java/com/lagradost/cloudstream3/utils/diagnostics/DiagnosticSearch.kt`: searches sanitized multi-line events as blocks; preserves Live Status session headings for matching events and all history for matching provider headings.
- `app/src/main/java/com/lagradost/cloudstream3/utils/diagnostics/ProviderTrace.kt`: Plugin Logs formatted in readable multi-line events; Important summarizes selected poster URL logs without printing their URLs; explicit plugin link failures shown; Links / Extractor includes plugin discovery/completion entries.
- `PROJECT_CONTEXT.md`, `WORKFLOW_RULES.md`, `AI_HANDOVER.md` — update these on every patch.

## Validation / open items

- ZIP structure and pure Kotlin search tests can be checked locally. **No Android APK compiled or installed here.** If GitHub Actions reports Kotlin errors, inspect the actual build log and fix only grounded errors.
- On-device acceptance: after a provider logs HOME/POSTER, Important > Plugin Logs should show a short poster selection count. Full trace > Plugin Logs should preserve each sanitized line with its tag and URL. Search for part of the poster path or `ANICHIN_V57_DISCOVERY` should show matching complete events. Links / Extractor should include discovery and DONE messages emitted when pressing Play.
- If the provider's `loadLinks` returns false without logging its internal error/timeout, the generic APK cannot invent the missing reason. Add logging to that specific extension only with the user's consent.
- Plugin message collector remains best-effort, Android access and active session mapping are limited; unlinked events must stay unlinked.
