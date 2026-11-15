# TEST_INFRA.md — Opaque-Box End-to-End Test Infrastructure & 4-Tier Verification Architecture

## 1. Purpose, Scope & Zero-Import Opaque-Box Philosophy

`:e2e-tests` is the authoritative, requirement-driven **opaque-box End-to-End (E2E) verification suite** for **WIFIREDEYE** (`applicationId = "com.a55.wifiradar"`), engineered specifically for the **Samsung Galaxy A55 5G** (`SM-A556B / SM-A556E / SM-A5560`, Exynos 1480 `arm64-v8a`, `1080×2340` `19.5:9` FHD+ 120Hz Super AMOLED, dual-band Wi-Fi 6 `802.11a/b/g/n/ac/ax` HE80 `2×2 MIMO`, X-axis LRA haptic motor, Android 14/15 One UI).

### Strict Zero-Import Enforcement (Dual-Layer Guardrail)
`:e2e-tests` treats the entire production codebase (`:core:*`, `:feature:*`, `:cli-runner`, and `:app`) as a sealed black box:
1. **Compile-Time Classpath Isolation (`e2e-tests/build.gradle.kts`)**:
   - The `dependencies { ... }` block of `:e2e-tests` declares **zero `project(...)` dependencies**.
   - `kotlinc` compiles `:e2e-tests` solely against Android SDK 35 public APIs, Jetpack Compose UI Test, Robolectric `4.18`, Roborazzi `1.79.0`, AndroidX Test, and `kotlinx-serialization-json`. Importing any internal class (`com.a55.wifiradar.core.*`, `com.a55.wifiradar.feature.*`, `com.a55.wifiradar.cli.*`, or `com.a55.wifiradar.MainActivity`) is a compile-time error.
   - At test execution time (`tasks.named<Test>("testDebugUnitTest")`), `:app`'s runtime `unit_test_config_directory`, merged `AndroidManifest.xml`, compiled resources (`apk-for-local-test.ap_`), and transitive runtime classes are prepended to `testDebugUnitTest.classpath` while `isIncludeAndroidResources = false` prevents `:e2e-tests` from emitting a competing `/com/android/tools/test_config.properties`.
2. **Static Gate Enforcement (`e2e-tests/run_e2e.sh`)**:
   - Before any build or test run, `run_e2e.sh` executes:
     ```bash
     ! grep -rnE "^\s*import\s+com\.a55\.wifiradar\.(core|feature|cli|MainActivity)" /home/antigravity/e2e-tests/src/
     ```
   - Any violation immediately aborts with exit code `2`.

---

## 2. Three-Pronged Real Production Execution Architecture

```text
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                  :e2e-tests (479 @Test)                                  │
│                     Zero com.a55.wifiradar.(core|feature|cli|MainActivity) imports       │
└───────────────┬──────────────────────────────────┬───────────────────────────────────────┘
                │                                  │                                       │
                ▼                                  ▼                                       ▼
┌───────────────────────────────┐  ┌───────────────────────────────┐  ┌────────────────────────────────┐
│ PRONG 1: OS-Boundary App UI   │  │ PRONG 2: Black-Box CLI Engine │  │ PRONG 3: Compiled APK Binary   │
│ • PackageManager launchIntent │  │ • ProcessBuilder subprocess   │  │ • Android SDK 35 build-tools   │
│   for "com.a55.wifiradar"     │  │   bin/cli-runner analyze      │  │   aapt2 dump badging/xmltree   │
│ • ActivityScenario + Compose  │  │ • JSON & Markdown stdout      │  │ • apksigner verify v2/v3 certs │
│ • Robolectric 4.18 (SDK 34)   │  │ • Non-zero exit + stderr      │  │ • apkanalyzer dex/manifest     │
│ • ExtendedShadowWifiManager   │  │   on invalid CLI/JSON         │  │ • ZIP ABI & DEX hygiene audit  │
│ • Skia NATIVE + Roborazzi PNG │  │ • 48 IEEE 802.11 IE vectors   │  │ • Host 2-CA cacerts.p12 checks │
└───────────────────────────────┘  └───────────────────────────────┘  └────────────────────────────────┘
```

### Prong 1 — OS-Boundary Full-App UI, Hardware Shadow & Lifecycle E2E
- **Entry Point**: Resolves the launcher `Intent` exclusively through `context.packageManager.getLaunchIntentForPackage("com.a55.wifiradar")` and launches it via `ActivityScenario.launch<Activity>(launchIntent)`.
- **Samsung Galaxy A55 5G Viewport**: `@RunWith(RobolectricTestRunner::class)`, `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, `@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")` (`1080×2340` `19.5:9` FHD+ at `3.0x` density; plus `w389dp-h842dp-420dpi`, `w411dp-h891dp-400dpi`, `w780dp-h360dp-land-450dpi`, `fontScale = 2.0f`, and RTL `ar-rSA-ldrtl`).
- **OS System Boundaries Controlled (`AppE2eRobot` + `ExtendedShadowWifiManager` + `ScanResultFactory`)**:
  - `ExtendedShadowWifiManager`: Extends Robolectric `ShadowWifiManager` at the `android.net.wifi.WifiManager` OS boundary to model retail Galaxy A55 5G capabilities (`is24GHzBandSupported() = true`, `is5GHzBandSupported() = true`, `is6GHzBandSupported() = false`, `11ax = true`, `11be = false`), `isScanThrottleEnabled()`, `registerScanResultsCallback()`, active `startScan()` invocation counting, and `SecurityException` / `DeadObjectException` Binder fault injection.
  - `ScanResultFactory`: Builds real SDK 34 `ScanResult` instances (`WifiScanResultBuilder`, `WifiSsid.fromBytes`, AOSP `idExt`-stripped `ScanResult.InformationElement`, Wi-Fi 7 MLO fields), `WifiInfo` (`ShadowWifiInfo`), and `RangingResult` (`RangingResultBuilder`, avoiding SDK 35-only `RangingResult$Builder` `NoClassDefFoundError` on SDK 34).
  - `ShadowLocationManager`, `ShadowConnectivityManager` (`TRANSPORT_WIFI` without `NET_CAPABILITY_INTERNET`), `ShadowVibrator` (3-tier LRA `PRIMITIVE_TICK` / amplitude `OneShot` / `EFFECT_TICK`), `ShadowAudioTrack` (Hanning-windowed PCM listener), `ShadowWifiRttManager`, `ShadowPackageManager`, and `ShareIntentInspector` (`ACTION_CHOOSER` / `ACTION_SEND` + `FileProvider` `content://` URI verification).
  - **Skia Native Visual & WCAG AAA Pixel Audit**: Captures full-window bitmaps via Roborazzi (`RoborazziTaskType.Record`) to verify true `#000000` AMOLED backgrounds and `>= 7.0:1` WCAG AAA foreground contrast.

### Prong 2 — Black-Box `:cli-runner` RF Physics, 802.11 IE & Analytics Subprocess Harness
- **Entry Point**: `CliRunnerHarness` spawns `/home/antigravity/cli-runner/build/install/cli-runner/bin/cli-runner` via `ProcessBuilder` with fast C1 JVM flags (`-Xms32m -Xmx256m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC`) and pipes `CliScenarioBuilder` JSON frames over `stdin` or file input (`analyze --input <path|-> --format <json|markdown> [--target-bssid <mac>] [--env <OPEN|HOME_OFFICE|HEAVY_WALLS>] [--redact-macs]`).
- **Schema-Resilient Strict Verification (`CliJsonLens`)**: Parses `stdout` as strict RFC 8259 `JsonObject`, verifying IEEE channel/bonding math, UTF-8/null-byte/hex SSID decoding, offline IEEE OUI & LAA bit-flip parent-vendor attribution, 3-level AP/MLO/Mesh clustering, 48 Python-verified binary 802.11 Information Elements (`BeaconIeVectors`), `A+`–`F` security & Evil-Twin scoring, motion-adaptive Kalman + EMA filtering, FSPL/TPC/1m/RTT distance math, linear-mW CCI/ACI & Best-Channel recommendations, `0–100` Environment Health Score, and MAC-redacted JSON/Markdown exports.

### Prong 3 — Compiled Debug & Signed Release APK Binary Verification
- **Entry Point**: `ApkBinaryInspector` executes real Android SDK 35 tools (`/home/antigravity/.android-sdk/build-tools/35.0.1/aapt2`, `apksigner`, and `/home/antigravity/.android-sdk/cmdline-tools/latest/bin/apkanalyzer`) + `ZipFile` entry audits against `app/build/outputs/apk/debug/app-debug.apk` and signed `app/build/outputs/apk/release/*.apk`.
- **Verifies**: `package="com.a55.wifiradar"`, `minSdkVersion=30`, `targetSdkVersion=35`, launchable `com.a55.wifiradar.MainActivity`, all 7 required permissions, **absence** of `android.permission.INTERNET`, **absence** of `neverForLocation` (`0x10000`) on `NEARBY_WIFI_DEVICES`, **absence** of `maxSdkVersion` on `ACCESS_FINE_LOCATION`, `required="false"` on `wifi.rtt` & `location.gps`, `FileProvider` `exported="false"` + `grantUriPermissions="true"`, APK Signature Scheme v2/v3 cryptographic verification, zero leaked Robolectric/JUnit classes in release DEX, and Galaxy A55 5G `arm64-v8a` ABI compatibility.

---

## 3. Multi-Tier Suite Architecture (`Tiers 1–4` + Reserved `Tier 5`)

| Tier | Package | Purpose | Minimum Required | Implemented `@Test` Count |
|------|---------|---------|------------------|---------------------------|
| **Tier 1** | `com.a55.wifiradar.e2e.tier1.*` | Core Functional Happy-Path E2E (`>= 5` distinct tests for **every** feature `F01`–`F36`) | `>= 180` (`5 × 36`) | **`209`** (`5–6` per feature) |
| **Tier 2** | `com.a55.wifiradar.e2e.tier2.*` | Boundary, Corner, Malformed IE, Throttle, Dropout & Negative E2E (`>= 5` for **every** feature `F01`–`F36`) | `>= 180` (`5 × 36`) | **`208`** (`5–6` per feature) |
| **Tier 3** | `com.a55.wifiradar.e2e.tier3.*` | Cross-Feature Pairwise & Multi-Module Interaction Matrix (`100%` `36/36` features participating) | `>= 36` | **`42`** |
| **Tier 4** | `com.a55.wifiradar.e2e.tier4.*` | Multi-Step Real-World Samsung Galaxy A55 5G User Journeys (`ORIGINAL_REQUEST.md` end-to-end) | `>= 18` | **`20`** |
| **Tier 5** | `com.a55.wifiradar.e2e.tier5.*` | *(Reserved for Milestone M7 Phase 2)* White-box adversarial coverage-hardening regression tests | Post-`TEST_READY.md` | Auto-discovered by `run_e2e.sh` |
| **TOTAL** | `com.a55.wifiradar.e2e.*` | **Complete Opaque-Box Verification Suite (`0` `@Ignore`, `0` internal imports)** | **`>= 414`** | **`479`** |

### Reserved Tier 5 Hook (`M7` Phase 2 Adversarial Coverage Hardening)
`./e2e-tests/run_e2e.sh` natively supports `--tier 5` (`--tests "com.a55.wifiradar.e2e.tier5.*"`) and automatically executes Tier 5 at the end of `--tier all` whenever `.kt` test files are added under `e2e-tests/src/test/kotlin/com/a55/wifiradar/e2e/tier5/` during Milestone `M7` Phase 2.

---

## 4. Complete `F01`–`F36` Feature-to-Test Traceability Matrix

| ID | Feature | Tier 1 Class & Count | Tier 2 Class & Count | Tier 3 Pairwise IDs | Tier 4 Journey IDs | Prongs |
|----|---------|----------------------|----------------------|---------------------|--------------------|--------|
| **F01** | Toolchain, Dual-CA Truststore & 0-Swap Build Setup | `Tier1F01ToF04FoundationDesignE2eTest` (`6`) | `Tier2F01ToF04FoundationEdgeE2eTest` (`6`) | `T3_37`, `T3_38` | `T4_J19`, `T4_J20` | P1, P2, P3 |
| **F02** | Galaxy A55 5G True-AMOLED 120Hz Design System | `Tier1F01ToF04FoundationDesignE2eTest` (`6`) | `Tier2F01ToF04FoundationEdgeE2eTest` (`6`) | `T3_24`, `T3_38` | `T4_J01`, `T4_J13`, `T4_J20` | P1 |
| **F03** | Semantic Plain-English Translations Everywhere | `Tier1F01ToF04FoundationDesignE2eTest` (`6`) | `Tier2F01ToF04FoundationEdgeE2eTest` (`6`) | `T3_06`, `T3_24`, `T3_39` | `T4_J02`, `T4_J13`, `T4_J15` | P1, P2 |
| **F04** | Tap-to-Explain Inline Legend Pills | `Tier1F01ToF04FoundationDesignE2eTest` (`6`) | `Tier2F01ToF04FoundationEdgeE2eTest` (`6`) | `T3_06`, `T3_29` | `T4_J07`, `T4_J13` | P1 |
| **F05** | Multi-Band Frequency, Channel & Bonding Resolver | `Tier1F05ToF08DiscoveryRfCoreE2eTest` (`6`) | `Tier2F05ToF08DiscoveryRfCoreEdgeE2eTest` (`6`) | `T3_05`, `T3_16`, `T3_18`, `T3_39` | `T4_J03`, `T4_J06`, `T4_J11` | P1, P2 |
| **F06** | Strict UTF-8 / Hex / Null-Byte Hidden SSID Decoder | `Tier1F05ToF08DiscoveryRfCoreE2eTest` (`5`) | `Tier2F05ToF08DiscoveryRfCoreEdgeE2eTest` (`6`) | `T3_02`, `T3_40` | `T4_J15`, `T4_J17` | P1, P2 |
| **F07** | Offline IEEE OUI Vendor & LAA MAC Resolver | `Tier1F05ToF08DiscoveryRfCoreE2eTest` (`5`) | `Tier2F05ToF08DiscoveryRfCoreEdgeE2eTest` (`5`) | `T3_03`, `T3_40` | `T4_J10`, `T4_J11` | P1, P2 |
| **F08** | Physical AP Clustering, Wi-Fi 7 MLO & Mesh Grouping | `Tier1F05ToF08DiscoveryRfCoreE2eTest` (`5`) | `Tier2F05ToF08DiscoveryRfCoreEdgeE2eTest` (`5`) | `T3_03`, `T3_13`, `T3_17` | `T4_J04`, `T4_J19` | P1, P2 |
| **F09** | AOSP-Accurate Binary 802.11 Beacon IE Inspector | `Tier1F09ToF11BeaconSecurityE2eTest` (`6`) | `Tier2F09ToF11BeaconSecurityEdgeE2eTest` (`6`) | `T3_04`, `T3_41` | `T4_J07`, `T4_J15` | P1, P2 |
| **F10** | Out-of-Band 6 GHz RNR, WPS Model & 802.11k/v/r | `Tier1F09ToF11BeaconSecurityE2eTest` (`6`) | `Tier2F09ToF11BeaconSecurityEdgeE2eTest` (`5`) | `T3_03`, `T3_05`, `T3_14`, `T3_19` | `T4_J06`, `T4_J11` | P1, P2 |
| **F11** | Deep Security Scorer (`A+`–`F`) & Evil-Twin Detector | `Tier1F09ToF11BeaconSecurityE2eTest` (`6`) | `Tier2F09ToF11BeaconSecurityEdgeE2eTest` (`6`) | `T3_04`, `T3_20`, `T3_41` | `T4_J05`, `T4_J11`, `T4_J17` | P1, P2 |
| **F12** | 1D Motion-Adaptive Kalman + Time-Aware EMA Filter | `Tier1F12ToF14ProximityPhysicsE2eTest` (`5`) | `Tier2F12ToF14ProximityPhysicsEdgeE2eTest` (`5`) | `T3_08`, `T3_10`, `T3_42` | `T4_J02`, `T4_J12` | P1, P2 |
| **F13** | Handset-Calibrated Path-Loss & RTT Distance Engine | `Tier1F12ToF14ProximityPhysicsE2eTest` (`6`) | `Tier2F12ToF14ProximityPhysicsEdgeE2eTest` (`5`) | `T3_08`, `T3_09`, `T3_14`, `T3_42` | `T4_J02`, `T4_J12`, `T4_J18` | P1, P2 |
| **F14** | Velocity Trend, Stability Score & Bounded Ring Buffers | `Tier1F12ToF14ProximityPhysicsE2eTest` (`5`) | `Tier2F12ToF14ProximityPhysicsEdgeE2eTest` (`5`) | `T3_10`, `T3_11`, `T3_23` | `T4_J02`, `T4_J09`, `T4_J14` | P1, P2 |
| **F15** | Linear-mW Spectral Overlap, CCI/ACI & Best-Channel Engine | `Tier1F15ToF17AnalyticsExportCliE2eTest` (`6`) | `Tier2F15ToF17AnalyticsExportCliEdgeE2eTest` (`6`) | `T3_16`, `T3_17`, `T3_18`, `T3_19`, `T3_21`, `T3_39` | `T4_J03`, `T4_J11`, `T4_J19` | P1, P2 |
| **F16** | Wireless Environment Health Score (`0–100`) & Insights | `Tier1F15ToF17AnalyticsExportCliE2eTest` (`5`) | `Tier2F15ToF17AnalyticsExportCliEdgeE2eTest` (`5`) | `T3_20`, `T3_21`, `T3_22`, `T3_41` | `T4_J03`, `T4_J05`, `T4_J10` | P1, P2 |
| **F17** | Offline JSON & Markdown Exporter + CLI Runner | `Tier1F15ToF17AnalyticsExportCliE2eTest` (`5`) | `Tier2F15ToF17AnalyticsExportCliEdgeE2eTest` (`5`) | `T3_22`, `T3_23`, `T3_37`, `T3_39..T3_42` | `T4_J05`, `T4_J10`, `T4_J19` | P1, P2 |
| **F18** | Galaxy A55 5G Radio Capability Introspection | `Tier1F18ToF23PlatformHardwareE2eTest` (`6`) | `Tier2F18ToF23PlatformHardwareEdgeE2eTest` (`6`) | `T3_05`, `T3_09`, `T3_19`, `T3_28` | `T4_J06`, `T4_J18` | P1, P2 |
| **F19** | Progressive Self-Healing OS Readiness State Machine | `Tier1F18ToF23PlatformHardwareE2eTest` (`6`) | `Tier2F18ToF23PlatformHardwareEdgeE2eTest` (`6`) | `T3_25..T3_28`, `T3_30`, `T3_31` | `T4_J01` | P1 |
| **F20** | Hybrid Token-Bucket Active + Dual Passive Scanner | `Tier1F18ToF23PlatformHardwareE2eTest` (`6`) | `Tier2F18ToF23PlatformHardwareEdgeE2eTest` (`6`) | `T3_12`, `T3_25`, `T3_26`, `T3_29`, `T3_32`, `T3_33` | `T4_J08`, `T4_J14` | P1 |
| **F21** | Unthrottled `300 ms` Connected-AP Stream & Roam Logger | `Tier1F18ToF23PlatformHardwareE2eTest` (`6`) | `Tier2F18ToF23PlatformHardwareEdgeE2eTest` (`6`) | `T3_02`, `T3_12`, `T3_13`, `T3_17`, `T3_23`, `T3_34` | `T4_J04`, `T4_J08`, `T4_J16`, `T4_J17` | P1, P2 |
| **F22** | 3-Tier LRA Haptic & Hanning-Sine Audio Geiger Engine | `Tier1F18ToF23PlatformHardwareE2eTest` (`6`) | `Tier2F18ToF23PlatformHardwareEdgeE2eTest` (`6`) | `T3_10`, `T3_11`, `T3_15`, `T3_35` | `T4_J02`, `T4_J09` | P1, P2 |
| **F23** | Local BSSID Nickname & `1m` Calibration Persistence | `Tier1F18ToF23PlatformHardwareE2eTest` (`6`) | `Tier2F18ToF23PlatformHardwareEdgeE2eTest` (`6`) | `T3_01`, `T3_08`, `T3_22`, `T3_36` | `T4_J02`, `T4_J04`, `T4_J10` | P1, P2 |
| **F24** | Network Discovery Cards, Mesh Toggle & Connected Badge | `Tier1F24ToF26NetworksDiscoveryUiE2eTest` (`6`) | `Tier2F24ToF26NetworksDiscoveryEdgeE2eTest` (`6`) | `T3_01..T3_04`, `T3_06`, `T3_20`, `T3_25` | `T4_J01`, `T4_J04`, `T4_J16` | P1 |
| **F25** | Instant Search, Filter Chips & Stable Sorting | `Tier1F24ToF26NetworksDiscoveryUiE2eTest` (`6`) | `Tier2F24ToF26NetworksDiscoveryEdgeE2eTest` (`6`) | `T3_01`, `T3_07`, `T3_33` | `T4_J12`, `T4_J14` | P1 |
| **F26** | Live Network Detail Bottom Sheet | `Tier1F24ToF26NetworksDiscoveryUiE2eTest` (`6`) | `Tier2F24ToF26NetworksDiscoveryEdgeE2eTest` (`6`) | `T3_01`, `T3_02`, `T3_04..T3_07`, `T3_14` | `T4_J04`, `T4_J06`, `T4_J07`, `T4_J11`, `T4_J12` | P1 |
| **F27** | 1-Tap Proximity Launch, Target Picker & BSSID/SSID Lock | `Tier1F27ToF29ProximityHomingUiE2eTest` (`6`) | `Tier2F27ToF29ProximityHomingEdgeE2eTest` (`6`) | `T3_07`, `T3_12`, `T3_13`, `T3_15` | `T4_J02`, `T4_J04`, `T4_J18` | P1 |
| **F28** | 120Hz Radial Sonar Proximity Gauge & Sparkline Screen | `Tier1F27ToF29ProximityHomingUiE2eTest` (`6`) | `Tier2F27ToF29ProximityHomingEdgeE2eTest` (`6`) | `T3_08..T3_12`, `T3_14`, `T3_27` | `T4_J02`, `T4_J08`, `T4_J12`, `T4_J18` | P1 |
| **F29** | Graceful Target Dropout & Auto Re-Acquisition | `Tier1F27ToF29ProximityHomingUiE2eTest` (`6`) | `Tier2F27ToF29ProximityHomingEdgeE2eTest` (`6`) | `T3_11`, `T3_27`, `T3_35` | `T4_J09` | P1 |
| **F30** | Interactive Multi-Band Spectrum Canvas & Best-Channel UI | `Tier1F30ToF32SpectrumInsightsUiE2eTest` (`6`) | `Tier2F30ToF32SpectrumInsightsEdgeE2eTest` (`6`) | `T3_16..T3_19`, `T3_21`, `T3_24`, `T3_26`, `T3_28` | `T4_J03`, `T4_J06`, `T4_J11` | P1 |
| **F31** | CCI vs ACI Interference Breakdown & Multi-AP Time/Roam Graph | `Tier1F30ToF32SpectrumInsightsUiE2eTest` (`6`) | `Tier2F30ToF32SpectrumInsightsEdgeE2eTest` (`6`) | `T3_13`, `T3_16`, `T3_23`, `T3_34` | `T4_J04` | P1 |
| **F32** | Insights Health Dashboard, Security Audit & Share Export | `Tier1F30ToF32SpectrumInsightsUiE2eTest` (`6`) | `Tier2F30ToF32SpectrumInsightsEdgeE2eTest` (`6`) | `T3_20..T3_22`, `T3_24`, `T3_26`, `T3_36` | `T4_J03`, `T4_J05`, `T4_J10` | P1 |
| **F33** | Immutable Unidirectional State & Lifecycle Safety | `Tier1F33ToF36StabilityAndApkE2eTest` (`6`) | `Tier2F33ToF36StabilityAndApkEdgeE2eTest` (`6`) | `T3_15`, `T3_31`, `T3_32`, `T3_34`, `T3_35` | `T4_J14` | P1, P2 |
| **F34** | Defensive Android IPC & Coroutine Error Isolation | `Tier1F33ToF36StabilityAndApkE2eTest` (`6`) | `Tier2F33ToF36StabilityAndApkEdgeE2eTest` (`6`) | `T3_09`, `T3_30`, `T3_31` | `T4_J15` | P1, P2 |
| **F35** | Rotation & Process-Death State Survival | `Tier1F33ToF36StabilityAndApkE2eTest` (`6`) | `Tier2F33ToF36StabilityAndApkEdgeE2eTest` (`6`) | `T3_07`, `T3_15`, `T3_18`, `T3_29`, `T3_36` | `T4_J14` | P1 |
| **F36** | Full App Assembly, Manifest Hygiene & Signed A55 5G APK | `Tier1F33ToF36StabilityAndApkE2eTest` (`6`) | `Tier2F33ToF36StabilityAndApkEdgeE2eTest` (`6`) | `T3_36`, `T3_37`, `T3_38` | `T4_J20` | P1, P3 |

---

## 5. Runner Script (`./e2e-tests/run_e2e.sh`) & Cloud VM Guardrails

### Command-Line Usage
```bash
# Verify static zero-import guardrail + isolated Kotlin test compilation (< 10s, zero :app tasks)
./e2e-tests/run_e2e.sh --compile-only

# Run individual E2E verification tiers
./e2e-tests/run_e2e.sh --tier 1      # 209 core functional happy-path tests (F01-F36)
./e2e-tests/run_e2e.sh --tier 2      # 208 boundary, corner & negative tests (F01-F36)
./e2e-tests/run_e2e.sh --tier 3      # 42 cross-feature pairwise interaction tests (36/36 features)
./e2e-tests/run_e2e.sh --tier 4      # 20 real-world Samsung Galaxy A55 5G user journeys
./e2e-tests/run_e2e.sh --tier 5      # Reserved M7 Phase 2 adversarial hardening regressions

# Run a single feature slice across Tiers 1-3
./e2e-tests/run_e2e.sh --feature F05

# Run all tiers sequentially (1 -> 2 -> 3 -> 4 [-> 5 if present]) with flat peak RAM
./e2e-tests/run_e2e.sh --tier all
```

### 2-vCPU / 7.8 GiB RAM / 0-Byte Swap Cloud VM Guardrails
1. **Two-CA TLS Truststore**: Automatically sources `/home/antigravity/scripts/env.sh` and exports `-Djavax.net.ssl.trustStore=/home/antigravity/.android-sdk/cacerts.p12 -Djavax.net.ssl.trustStorePassword=changeit` across Gradle, Test Worker JVMs, and `:cli-runner` subprocesses.
2. **XFS Disk-Backed `TMPDIR`**: Directs `TMPDIR` and `java.io.tmpdir` to `/home/antigravity/.tmp` (avoiding the `3.9 GiB` root RAM `tmpfs`).
3. **Bounded Heap & Sequential Forking**: Enforces `--max-workers=2`, `maxParallelForks = 1`, `maxHeapSize = "1536m"` on the Robolectric/Skia test worker, and `-Xmx256m` on `:cli-runner` child processes, keeping total VM RSS comfortably below `4.2 GiB`.
4. **Re-Entrant Gradle Lock (`flock -w 600 /home/antigravity/.tmp/gradle.lock`)**: `run_e2e.sh` wraps every `./gradlew` invocation with a re-entrant `WIFIRADAR_GRADLE_LOCK_HELD=1 flock -w 600 /home/antigravity/.tmp/gradle.lock` guard and **never** passes `--no-daemon`, sharing the single `-Xmx2048m` Gradle daemon safely without self-deadlock or OOM churn.
