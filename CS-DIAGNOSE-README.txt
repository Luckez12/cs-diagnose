CS Diagnose — corrected legacy Settings and original legacy Logcat.

Based on the user-uploaded cloudstream-master.zip. The supplied upstream source
contains two Settings implementations; the shipped upstream navigation opens
SettingsFragment2 (Compose). This build deliberately routes navigation_settings
to the upstream SettingsFragment (legacy) to match the requested older Settings
UI. The legacy SettingsUpdates shows its own original Logcat dialog.

The existing upstream settings and Logcat classes, resources, colors and styles
have not been redesigned. One Diagnostic entry below Extensions was added to
main_settings.xml and wired in SettingsFragment.kt. Provider Diagnostic has its
own independent page. The upstream Compose settings screen is not active and
is unchanged relative to the supplied upstream source.

App ID: com.luckez.csdiagnose.debug; build target: :app:assembleStableDebug;
ABI: arm64-v8a only. Build via GitHub Actions, not locally.

IMPORTANT FOR EXISTING REPO: Auto-unzip overlays files; it cannot remove older
files that were previously committed but are not in this ZIP. To guarantee a
clean result, use a fresh/clean repo or remove older tracked source files before
extracting this archive. Keep .github/workflows/*.yml separate from this ZIP.

Diagnostic limitations: extension-owned HTTP clients may not be observable and
HTTP error attempts do not necessarily indicate terminal provider failure.
