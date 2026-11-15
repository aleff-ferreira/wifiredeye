# WIFIREDEYE

Native Android (Kotlin / Jetpack Compose) Wi-Fi scanner, signal-strength proximity meter and RF analytics app, tuned for the Samsung Galaxy A55 5G (120 Hz Super AMOLED, Android 14/15).

- **Networks** – every nearby network, grouped by SSID/mesh, with plain-English signal quality; tap one to home in on it.
- **Proximity** – live Kalman-smoothed signal gauge, distance estimate, optional haptic/audio Geiger feedback.
- **Spectrum** – 2.4 / 5 / 6 GHz channel overlap graph and best-channel recommendation.
- **Insights** – 0–100 environment health score, security audit (WPA3/PMF, Evil Twin), JSON/Markdown export.

Fully offline (no `INTERNET` permission).

## Build

```bash
source scripts/env.sh        # JDK 17 + Android SDK 35 paths
./gradlew :app:assembleDebug # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest  # unit + Robolectric/Roborazzi tests
```

## Install on the phone

Enable *Developer options → USB debugging* (or copy the APK to the phone and open it), then:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch grant **Location / Nearby devices** permission; Android requires it to read Wi-Fi scan results. Keep Location switched on.

## Layout

`:core:model` (contracts) · `:core:designsystem` · `:core:rf-engine` (pure JVM RF/IE analytics) · `:core:wifi-platform` (Android Wi-Fi, haptics) · `:feature:{networks,proximity,spectrum,insights}` · `:app` (wiring) · `:cli-runner` · `:e2e-tests` (opaque-box suite; see `TEST_INFRA.md`). Architecture notes: `PROJECT.md`.

## Ready-to-install APK

`dist/wifiredeye-debug.apk` (debug-signed, sideloadable). Download it from GitHub on the phone (or `adb install -r dist/wifiredeye-debug.apk`).

## Status / known limitations

- App builds; `:app` smoke test, `:core:*`, `:feature:*` and `:cli-runner` unit tests pass, except 5 `:core:wifi-platform` test-fixture failures (Base64/IP fixtures in `ConnectedWifiAndRttTest`, `ReadinessMonitorAndIntentsTest`, `ScanResultExtractorAndCacheTest`).
- Opaque-box `:e2e-tests` (479 cases): ~360 pass; ~119 still fail, mostly because the tests assume UI labels/flows (share-sheet intent, sort chip text, legend pill text) that differ from the shipped UI, plus a signed *release* APK that has not been built.
- **Not yet verified on a physical Galaxy A55 5G** – only Robolectric/Compose JVM tests have been run.
- `--include-dfs` CLI flag is accepted but does not yet change output.
