CS Diagnose — based on the newly supplied official cloudstream-master.zip.

Android app, shared/library modules, Gradle wrapper, and official LICENSE retained.
Original LogcatDialog.kt, logcat.xml, and settings layouts/styles remain byte-for-byte unchanged
except one added legacy diagnostic menu item in main_settings.xml. The existing Compose
settings menu has exactly one new Diagnostic item; its existing layout is preserved.

Trace instrumentation: APIRepository (homepage/sections/metadata/search/links),
RequestsHelper shared HTTP client, RepoLinkGenerator (cache), CS3IPlayer (playback),
plus ProviderTrace, ProviderHttpTrace and an independent Diagnostic page.

Limitations: HTTP from extension-owned clients that bypass the shared app client and
private extractor internals may be invisible; HTTP warnings are individual attempts,
not automatically a terminal provider failure. The source ZIP has no embedded workflows.

App ID: com.luckez.csdiagnose.debug | build: :app:assembleStableDebug | arm64-v8a APK only.
Put two separate YAML files into .github/workflows in the new repository.
No APK compiled or built in the authoring environment; use GitHub Actions.
