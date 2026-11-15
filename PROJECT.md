# Project: A55 WiFi Radar — Wireless Network Scanner, Signal Proximity Meter & RF Analytics for Samsung Galaxy A55 5G

## Architecture

### 1. Product Vision & Target Hardware Profile
**A55 WiFi Radar** (`applicationId = "com.a55.wifiradar"`) is a native Android application engineered specifically for the **Samsung Galaxy A55 5G** (`SM-A556B / SM-A556E / SM-A5560`, Exynos 1480 `arm64-v8a`, Xclipse 530 GPU, 6.6" FHD+ `1080×2340` `19.5:9` **120Hz Super AMOLED** with Infinity-O top-center camera punch-hole, X-axis LRA haptic motor, Dual-Band Wi-Fi 6 `802.11a/b/g/n/ac/ax` HE80 `2×2 MIMO`, running Android 14 One UI 6.1 / Android 15 One UI 7+).

It delivers four tightly unified, zero-documentation capabilities:
1. **Complete Wireless Network Detection (`Networks` Tab)**: Detects every wireless network in reach across 2.4 / 5 / 6 GHz (plus out-of-band 6 GHz discovery via 2.4/5 GHz Reduced Neighbor Report `IE 201` on dual-band hardware), hidden null-byte/empty SSIDs, grouped Mesh/Multi-BSSID radios vs individual BSSID view, offline IEEE OUI vendor + WPS router model identification, custom BSSID nicknames, connected-network live spotlight, and instant search/filter/sort.
2. **Real-Time Signal Strength Proximity Meter (`Proximity` Tab)**: 1-tap homing onto any detected BSSID (or Mesh SSID) with a 120Hz zero-allocation radial sonar/arc gauge, 1D motion-adaptive Kalman + time-aware EMA smoothing, handset-calibrated FSPL/log-distance meter range (with `1m` user calibration & automatic `802.11mc/az` RTT upgrade), approaching/steady/moving-away velocity trend, bounded 120-sample sparkline + stability telemetry, optional 3-tier LRA haptic & Hanning-sine audio Geiger pulses, burst scan controls, and graceful `"Signal Lost · Last seen Xs ago"` recovery.
3. **State-of-the-Art Wireless Analytics (`Spectrum` & `Insights` Tabs)**: Interactive 2.4 / 5 / 6 GHz spectral overlap curves (`20/40/80/160/80+80/320 MHz`), linear-milliwatt Co-Channel (CCI) vs Adjacent-Channel (ACI) interference engine, canonical Best-Channel Recommender with 1-sentence plain-English explanation, AOSP-accurate binary 802.11 Beacon IE inspector (`BSS Load` client count & airtime %, MIMO streams, peak PHY Mbps, `BSS Color`, `802.11k/v/r`, `PMF`, Wi-Fi 7 MLO/puncturing), `A+`–`F` Security & Evil-Twin auditor, multi-AP RSSI time/roam logger, `0–100` Wireless Environment Health Score, and offline JSON/Markdown ShareSheet & clipboard export.
4. **Mesmerizing, Ultra-Stable, Self-Explanatory UI**: True `#000000` AMOLED surfaces, AAA `>= 7:1` contrast, tabular numerals (`tnum`), inline plain-English translations next to every technical unit, tap-to-explain inline legend pills, progressive self-healing OS Permission/Location/Wi-Fi Readiness Cards, `SavedStateHandle` rotation/process-death survival, and defensive IPC/coroutine exception isolation.

### 2. Multi-Module Acyclic Dependency Graph
```text
:e2e-tests  ──(opaque OS Intent / ShadowWifiManager / CLI / APK boundary)──▶ :app, :cli-runner
:app        ──▶ :feature:networks, :feature:proximity, :feature:spectrum, :feature:insights,
                :core:wifi-platform, :core:rf-engine, :core:designsystem, :core:model
:cli-runner ──▶ :core:rf-engine, :core:model
:feature:networks  ──▶ :core:designsystem, :core:model
:feature:proximity ──▶ :core:designsystem, :core:model
:feature:spectrum  ──▶ :core:designsystem, :core:model
:feature:insights  ──▶ :core:designsystem, :core:model
:core:wifi-platform ──▶ :core:model
:core:rf-engine     ──▶ :core:model   (100% pure Kotlin JVM — zero Android framework dependency)
:core:designsystem  ──▶ :core:model
:core:model         ──▶ (Pure Kotlin JVM: stdlib + kotlinx-coroutines-core + kotlinx-serialization-json)
```

### 3. Verified Build Toolchain & Cloud VM Guardrails (Mandatory)
- **JDK**: Preinstalled OpenJDK `17.0.21` at `/usr/lib/jvm/java-17-openjdk-amd64`.
- **Two-CA JVM Truststore Bootstrap (M1 Prerequisite)**: `/usr/lib/jvm/java-17-openjdk-amd64/lib/security/cacerts` is a broken symlink. M1 MUST import **both** `/etc/ssl/certs/ca-certificates.crt` and `/usr/local/share/ca-certificates/agent-gateway-ca.crt` into `/home/antigravity/.android-sdk/cacerts.p12` (`storepass = changeit`) and configure `-Djavax.net.ssl.trustStore=/home/antigravity/.android-sdk/cacerts.p12 -Djavax.net.ssl.trustStorePassword=changeit` in `gradle.properties`, test JVM `systemProperty` blocks, and `/home/antigravity/scripts/env.sh`.
- **Android SDK & Pin Versions**:
  - `ANDROID_HOME = /home/antigravity/.android-sdk`, `TMPDIR = /home/antigravity/.tmp`
  - SDK packages: `cmdline-tools;latest`, `platform-tools`, `platforms;android-35`, `build-tools;35.0.1`
  - `compileSdk = 35`, `targetSdk = 35`, `minSdk = 30`
  - Gradle `8.14.7`, AGP `8.13.2`, Kotlin & Compose Compiler `2.1.21` (or `2.2.21`/`2.3.21` verified compatible with AGP `8.13.2` & `kotlinx-serialization`), `androidx.compose:compose-bom:2026.05.01` (`minCompileSdk = 35` — NEVER use `2026.09.00` which requires SDK 37!), Robolectric `4.18` (`@Config(sdk = [34])` — NEVER use SDK 36+ which requires Java 21!), Roborazzi `1.79.0` (`@GraphicsMode(GraphicsMode.Mode.NATIVE)`).
- **2-vCPU / 7.8 GiB RAM / 0-Byte Swap Safety**:
  - `org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8 -Djavax.net.ssl.trustStore=/home/antigravity/.android-sdk/cacerts.p12 -Djavax.net.ssl.trustStorePassword=changeit`
  - `kotlin.daemon.jvmargs=-Xmx1536m`, `org.gradle.workers.max=2`, `maxParallelForks = 1`, test `maxHeapSize = "1536m"`.
- **Compose Anti-Hang & Accessibility Semantics Contract**:
  - Custom `Canvas` gauges/sparklines/spectrum graphs MUST use `Modifier.drawWithCache` (zero per-frame heap allocations) and attach `Modifier.semantics { contentDescription = ...; stateDescription = ... }` + `testTag(...)` so TalkBack users on Galaxy A55 5G and opaque-box Compose UI tests can read live rendered telemetry cleanly.
  - NEVER write infinite `while(true) { delay(16) }` coroutine loops on the main thread that block `ComposeTestRule.waitForIdle()`. Use standard state-driven `animateFloatAsState` or `rememberInfiniteTransition` respecting animation scale.

---

## Feature Inventory

Every feature discovered during the Step 0 Survey (`survey_1`, `survey_2`, `survey_3`) is enumerated below and assigned to an implementation milestone. Zero features are left unassigned.

| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| F01 | Toolchain, Dual-CA Truststore & 0-Swap Build Setup | Bootstrap `/home/antigravity/.android-sdk` (API 35, build-tools 35.0.1), 2-CA `cacerts.p12`, Gradle 8.14.7, AGP 8.13.2, Compose BOM 2026.05.01, Robolectric 4.18, Roborazzi 1.79.0, 0-swap JVM limits, and `scripts/env.sh`. | M1 | survey_1 |
| F02 | Galaxy A55 5G True-AMOLED 120Hz Design System | `#000000` OLED canvas, `#0B101B`/`#131C2E` slate surfaces, AAA `>=7:1` semantic palette, `tnum` tabular numerals, Infinity-O punch-hole & gesture `WindowInsets.safeDrawing`, 360–411dp + 200% font-scale adaptive layout, 4-tab bottom nav (`Networks`, `Proximity`, `Spectrum`, `Insights`). | M1, M6 | survey_2, survey_3 |
| F03 | Semantic Plain-English Translations Everywhere | Centralized semantic qualifiers paired inline with every dBm, MHz, channel, width, security, and stability metric; zero leaked raw enum/constant names. | M1, M4, M5 | survey_3 |
| F04 | Tap-to-Explain Inline Legend Pills | Dismissible inline popover/chip component (`dBm`, `CCI vs ACI`, `DFS/TDWR`, `PMF`, `OS Scan Limit`, `Kalman Smooth`, `PSC`, `BSS Load`) making external docs unnecessary. | M1, M4, M5 | survey_3 |
| F05 | Multi-Band Frequency, Channel & Bonding Resolver | Exact IEEE mapping for 2.4 GHz (Ch 1–14), 5 GHz (Ch 32–177, U-NII-1..4, DFS & TDWR flags), 6 GHz (Ch 1–233, U-NII-5..8, PSC flags), and 60 GHz across `20/40/80/160/80+80/320 MHz` widths. | M2 | survey_2, survey_3 |
| F06 | Strict UTF-8 / Hex / Null-Byte Hidden SSID Decoder | Decodes `WifiSsid` raw bytes & legacy `SSID`, classifies empty and `\x00*N` null-padded SSIDs as `Hidden Network`, renders invalid UTF-8 as safe hex, and reveals SSID when associated via `WifiInfo`. | M2 | survey_2, survey_3 |
| F07 | Offline IEEE OUI Vendor & LAA MAC Resolver | Embedded offline IEEE OUI manufacturer dictionary (zero internet permission) + Locally Administered MAC (`b0 & 0x02`) detection & parent-vendor inference. | M2 | survey_2, survey_3 |
| F08 | Physical AP Clustering, Wi-Fi 7 MLO & Mesh Grouping | 3-level hierarchy: individual `BssidRadio` -> `PhysicalApCluster` (MLD MAC / RNR / 5-octet prefix) -> `EssNetworkGroup` (`Mesh / Multi-AP`), keeping hidden BSSIDs distinct. | M2, M4 | survey_2, survey_3 |
| F09 | AOSP-Accurate Binary 802.11 Beacon IE Inspector | Safe read-only `ByteBuffer` parser (respecting AOSP `id=255` `idExt` stripping) for `BSS Load (IE 11)` clients & utilization %, `HT/VHT/HE/EHT` spatial streams (`1x1..8x8`), peak PHY Mbps, `BSS Color (IE 255/36)` collision warning, and Wi-Fi 7 puncturing/MLO (`IE 255/106..108`). | M2 | survey_2, survey_3 |
| F10 | Out-of-Band 6 GHz RNR, WPS Model & 802.11k/v/r | Decodes `RNR (IE 201)` co-located 6 GHz APs (vital on dual-band Galaxy A55 5G), `WPS/Vendor (IE 221)` router model/device name/hostname, `TPC (IE 35)` Tx dBm, `TIM (IE 5)` DTIM, `Country (IE 7)`, and `802.11k/v/r` (`IE 70, 127, 54`). | M2 | survey_2 |
| F11 | Deep Security Scorer (`A+`–`F`) & Evil-Twin Detector | Parses capabilities, security types, `RSN (IE 48)`, `RSNX SAE-H2E (IE 244)`, `Supported Rates` BSS Membership Selectors, `PMF (802.11w)`, and `WPS` to compute `0–100` (`A+`–`F`) security grades and flag Open Evil-Twin SSID clones. | M2, M5 | survey_2, survey_3 |
| F12 | 1D Motion-Adaptive Kalman + Time-Aware EMA Filter | Smooths multipath fading spikes while boosting gain when `|innovation| > 6 dB` during walking motion; resets covariance cleanly on `> 15s` gaps or target switch. | M2 | survey_2, survey_3 |
| F13 | Handset-Calibrated Path-Loss & RTT Distance Engine | Frequency-aware FSPL + indoor exponent (`Open 2.0`, `Home/Office 2.7`, `Heavy Walls 3.4`), Beacon `TPC` Tx offset, `-8 dBi` handset body offset, 1-tap `1m` user calibration, confidence interval range, and opportunistic `802.11mc/az` RTT upgrade. | M2, M3, M4 | survey_2, survey_3 |
| F14 | Velocity Trend, Stability Score & Bounded Ring Buffers | Sliding linear-regression slope (`Approaching` / `Steady` / `Moving Away` with hysteresis), `0–100%` stability score, and fixed-capacity ring buffers (`120` samples/BSSID, LRU `256` BSSIDs) for flat multi-hour memory. | M2, M4 | survey_2, survey_3 |
| F15 | Linear-mW Spectral Overlap, CCI/ACI & Best-Channel Engine | Computes $10^{\text{RSSI}/10}$ mW spectral overlap, penalizes Adjacent-Channel Interference ($\gamma=1.85$) over Co-Channel ($\gamma=1.0(1+u_{\text{QBSS}})$), scores channels (`0–100` & `0–5★`), and recommends canonical channels (`1/6/11`, non-DFS/DFS 5 GHz, PSC 6 GHz) with a 1-sentence plain-English reason. | M2, M5 | survey_2, survey_3 |
| F16 | Wireless Environment Health Score (`0–100`) & Insights | Composite score across Signal Coverage, Channel Crowding, Interference Purity, and Security Hygiene with sorted actionable plain-English insight cards. | M2, M5 | survey_3 |
| F17 | Offline JSON & Markdown Exporter + CLI Runner | Schema-complete JSON & readable Markdown report generator (with optional BSSID MAC redaction) + `:cli-runner` pure-JVM CLI (`analyze --input ... --format json|markdown`). | M2, M5 | survey_2, survey_3 |
| F18 | Galaxy A55 5G Radio Capability Introspection | Queries `is24GHzBandSupported()`, `is5GHzBandSupported()`, `is6GHzBandSupported()`, Wi-Fi standards, and `FEATURE_WIFI_RTT`; renders clear hardware explanation banner on 6 GHz views when `is6GHzBandSupported() == false`. | M3, M4, M5 | survey_2, survey_3 |
| F19 | Progressive Self-Healing OS Readiness State Machine | Detects `MissingPermission`, `ApproximateOnlyNeedsPrecise`, `PermanentDenial`, `LocationDisabled`, `WifiDisabledScanAlwaysOn`, and `WifiAndScanDisabled`; auto-recovers via live OS broadcasts and provides 1-tap system settings intents. | M3, M4 | survey_2, survey_3 |
| F20 | Hybrid Token-Bucket Active + Dual Passive Scanner | Dual `registerScanResultsCallback()` + `SCAN_RESULTS_AVAILABLE_ACTION` (`EXTRA_RESULTS_UPDATED`) listener, `4/120s` token-bucket pacing (`30s` steady, `Burst Homing 4×` mode, unthrottled `2.5s` when `isScanThrottleEnabled() == false`), and `45s` missed-beacon anti-flicker cache. | M3 | survey_2, survey_3 |
| F21 | Unthrottled `300 ms` Connected-AP Stream & Roam Logger | `NetworkRequest(TRANSPORT_WIFI)` without `NET_CAPABILITY_INTERNET` + `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` in `onCapabilitiesChanged` + `300 ms` `WifiInfo` poller + `RSSI_CHANGED_ACTION` + mesh BSSID `RoamEvent` tracker. | M3, M4, M5 | survey_2, survey_3 |
| F22 | 3-Tier LRA Haptic & Hanning-Sine Audio Geiger Engine | Exponential `1400 ms -> 85 ms` pulse interval & `320 -> 1400 Hz` pitch via `VibratorManager` (`PRIMITIVE_TICK` -> amplitude `OneShot` -> `EFFECT_TICK`) and click-free `AudioTrack` sine bursts; default OFF; strictly stopped on `ON_STOP`/dropout. | M3, M4 | survey_2, survey_3 |
| F23 | Local BSSID Nickname & `1m` Calibration Persistence | Offline `SharedPreferences` / atomic JSON store for user-defined router nicknames (`"Living Room Router"`) and `1m` RSSI calibration overrides. | M3, M4 | survey_2 |
| F24 | Network Discovery Cards, Mesh Toggle & Connected Badge | Grouped Mesh vs All-Radios (`BSSID`) toggle, connected emerald spotlight pill, scan freshness/throttle status bar, and high-contrast AP cards (`Networks` tab). | M4 | survey_3 |
| F25 | Instant Search, Filter Chips & Stable Sorting | Zero-lag search (SSID, BSSID, Vendor, Nickname, Channel, Security), filter chips (`All`, `2.4`, `5`, `6 GHz`, `Open/Weak`, `Wi-Fi 6+`, `Strong >= -65 dBm`), deterministic BSSID tie-break sorting, and 1-tap `Reset Filters`. | M4 | survey_2, survey_3 |
| F26 | Live Network Detail Bottom Sheet | Live-updating modal sheet showing full plain-English protocol/IE breakdown, `BSS Load`, MIMO streams, WPS model, RNR 6 GHz neighbors, nickname editor, `Copy Summary`, and 1-tap `Measure Proximity`. | M4 | survey_2, survey_3 |
| F27 | 1-Tap Proximity Launch, Target Picker & BSSID/SSID Lock | Launch directly from any AP/BSSID row into `Proximity` tab; defaults to `Single Router Lock (BSSID)` with optional `Follow Mesh (SSID)` toggle and quick Target Switcher pill. | M4 | survey_2, survey_3 |
| F28 | 120Hz Radial Sonar Proximity Gauge & Sparkline Screen | `drawWithCache` radial sonar/arc gauge + spring needle + peak-hold + smoothed/raw dBm & % + distance/environment/`1m` calibrate controls + velocity chevron + 60-sample sparkline + `Min/Max/Avg/Stability` + Burst & Geiger toggles + full accessibility `semantics`. | M4 | survey_2, survey_3 |
| F29 | Graceful Target Dropout & Auto Re-Acquisition | Transitions smoothly to `"Signal Lost · Last seen Xs ago"` preserving target lock and sparkline gap without resetting stats; auto-resumes when beacon returns. | M2, M4 | survey_2, survey_3 |
| F30 | Interactive Multi-Band Spectrum Canvas & Best-Channel UI | `Spectrum` tab with `2.4 / 5 / 6 GHz` switcher, translucent bell/trapezoid overlap curves (`20..320 MHz`, `80+80`, DFS/PSC backdrops), tap-to-highlight APs, Best-Channel card with 1-sentence reason, `Include DFS` toggle, and accessible channel score list. | M5 | survey_2, survey_3 |
| F31 | CCI vs ACI Interference Breakdown & Multi-AP Time/Roam Graph | Plain-English Co-Channel vs Adjacent-Channel cards + rolling multi-network RSSI history chart (`1m / 5m / 15m`) with BSSID roam markers. | M5 | survey_2, survey_3 |
| F32 | Insights Health Dashboard, Security Audit & Share Export | `Insights` tab with `0–100` Health Score ring, 4 sub-scores, actionable insight cards, security/Evil-Twin audit list, and Markdown/JSON preview + clipboard + `FileProvider` `ACTION_SEND` ShareSheet. | M5, M6 | survey_2, survey_3 |
| F33 | Immutable Unidirectional State & Lifecycle Safety | `@Immutable` `UiState` + `StateFlow` + `collectAsStateWithLifecycle()` halting active loops/haptics/audio when backgrounded. | M3, M4, M5, M6 | survey_3 |
| F34 | Defensive Android IPC & Coroutine Error Isolation | `runCatching` around all `WifiManager`, `LocationManager`, `ConnectivityManager`, `WifiRttManager`, and `Vibrator` calls (`SecurityException`, `RemoteException`, `IllegalStateException`) + `SupervisorJob`. | M3, M6 | survey_2, survey_3 |
| F35 | Rotation & Process-Death State Survival | `SavedStateHandle` / `rememberSaveable` preserving active tab, search/filter/sort, Grouped/BSSID mode, locked Proximity `BSSID`/preset, and Spectrum band across rotation and `ActivityScenario.recreate()`. | M4, M5, M6 | survey_3 |
| F36 | Full App Assembly, Manifest Hygiene & Signed A55 5G APK | Wire real modules into `:app` `MainActivity` & 4-tab `Scaffold`, configure `FileProvider` & exact permissions (`wifi.rtt` & `location.gps` `android:required="false"`), Roborazzi visual screenshots, and debug + signed release APKs verified via `aapt2`/`apkanalyzer`/`apksigner`. | M6 | survey_1, survey_2, survey_3 |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Toolchain Bootstrap, Multi-Module Skeleton, Frozen Contracts & AMOLED Design System | Bootstrap `/home/antigravity/.android-sdk` + 2-CA `cacerts.p12` + Gradle `8.14.7`; create all Gradle module skeletons; implement frozen `:core:model` contracts & semantic formatters (`F01, F03`) and `:core:designsystem` AMOLED theme/primitives (`F02, F04`); sync `/home/antigravity/PROJECT.md`. | none | IN_PROGRESS (`6fc84f407a445555d4bca0d839a3a6a0`) |
| M2 | Pure-JVM RF Physics, 802.11 IE Decoder, Offline OUI, Analytics & CLI Runner | Implement `:core:rf-engine` (`F05–F17, F29` domain logic) and `:cli-runner` (`F17` CLI JSON/Markdown replay harness) with comprehensive pure-JVM unit tests. | M1 | PLANNED |
| M3 | Android Wi-Fi Platform, Readiness Monitor, Token-Bucket Scanner & Geiger Feedback | Implement `:core:wifi-platform` (`F13` RTT adapter, `F18–F23, F33, F34`) tested thoroughly under Robolectric 4.18 shadows. | M1 | PLANNED |
| M4 | Network Discovery & 120Hz Proximity Meter UI Features | Implement `:feature:networks` and `:feature:proximity` (`F03, F04, F08, F13, F14, F18, F19, F21–F29, F33, F35`) with Robolectric + Compose UI + Roborazzi tests on `1080×2340` Galaxy A55 5G viewport. | M1 | PLANNED |
| M5 | Interactive Spectrum Graph & Wireless Insights / Export UI Features | Implement `:feature:spectrum` and `:feature:insights` (`F03, F04, F11, F15–F18, F21, F30–F33, F35`) with Robolectric + Compose UI + Roborazzi tests on `1080×2340` Galaxy A55 5G viewport. | M1 | PLANNED |
| M6 | Full Application Assembly, Navigation, ShareSheet & Signed Galaxy A55 5G APK | Wire `:core:wifi-platform` + `:core:rf-engine` + all 4 `:feature:*` modules into `:app` (`F02, F32–F36`), finalize `AndroidManifest.xml`, build & verify debug + signed release APKs, and capture full-app Roborazzi visual gallery. | M1, M2, M3, M4, M5 | PLANNED |
| M7 | Final Milestone: 100% E2E Pass (Tiers 1–4) & Adversarial Coverage Hardening (Tier 5) | Phase 1: Wait for `TEST_READY.md`, pass 100% of E2E Tiers 1 → 2 → 3 → 4 sequentially. Phase 2: White-box Tier 5 adversarial coverage hardening (`test-coverage-audit` Challenger → Worker → Reviewer loop until 0 gaps remain). | M6, E2E | PLANNED |
| E2E | Parallel E2E Testing Track (`TEST_INFRA.md` → `:e2e-tests` Tiers 1–4 → `TEST_READY.md`) | Requirement-driven opaque-box test infrastructure and Tiers 1–4 test suite across OS-boundary Robolectric+Compose UI, `:cli-runner`, and APK `aapt2`/`apkanalyzer`/`apksigner` checks. | M1 (toolchain ready) | IN_PROGRESS (`12e84f499d4237937e21054b841ae714`) |

---

## Interface Contracts

All shared types below MUST be defined in `:core:model` (`package com.a55.wifiradar.core.model`) during **M1** and kept stable so **M2, M3, M4, M5, and E2E** integrate cleanly without cross-module friction:

### 1. `:core:wifi-platform` ↔ `:core:rf-engine` (`RawScanBatch` & Platform Contracts)
- `WifiPlatformDataSource`:
  - `val rawScanBatches: StateFlow<RawScanBatch?>`
  - `val scanTelemetry: StateFlow<ScanTelemetry>`
  - `val connectedWifi: StateFlow<ConnectedWifiObservation?>`
  - `val readinessState: StateFlow<ScannerReadinessState>`
  - `val radioCapabilities: StateFlow<DeviceRadioCapabilities>`
  - `fun triggerActiveScan(burstMode: Boolean = false): ScanTriggerResult`
  - `fun setHighFrequencyConnectedPolling(enabled: Boolean)`
  - `fun refreshReadiness()`
  - `suspend fun requestRttRange(bssid: String): RttMeasurement?`
- `RawScanEntry`: `bssid: String`, `rawSsidBytes: ByteArray?`, `legacySsid: String?`, `rssiDbm: Int`, `frequencyMhz: Int`, `centerFreq0Mhz: Int`, `centerFreq1Mhz: Int`, `channelWidthCode: Int`, `wifiStandardCode: Int`, `capabilities: String`, `securityTypes: List<Int>`, `timestampMicros: Long`, `is80211mcResponder: Boolean`, `is80211azResponder: Boolean`, `apMldMacAddress: String?`, `informationElements: List<RawInformationElement>` (`id: Int`, `idExt: Int`, `bytes: ByteArray`).
- `UserPreferencesDataSource`:
  - `val bssidNicknames: StateFlow<Map<String, String>>`
  - `val oneMeterCalibrationsDbm: StateFlow<Map<String, Double>>`
  - `suspend fun setNickname(bssid: String, nickname: String?)`
  - `suspend fun setOneMeterCalibration(bssid: String, rssiAtOneMeterDbm: Double?)`
- `ProximityFeedbackController`:
  - `val feedbackSettings: StateFlow<GeigerFeedbackSettings>` (`hapticsEnabled: Boolean`, `audioEnabled: Boolean`)
  - `fun setHapticsEnabled(enabled: Boolean)`
  - `fun setAudioEnabled(enabled: Boolean)`
  - `fun updateSignal(smoothedRssiDbm: Double?, isSignalLost: Boolean)`
  - `fun stopAll()`

### 2. `:core:rf-engine` ↔ `:app` / `:cli-runner` (`RfAnalyticsEngine` & `WifiRadarSessionRepository`)
- `RfAnalyticsEngine` (pure stateless/stateful deterministic engine in `:core:rf-engine`):
  - `fun processScanSnapshot(...) : RadarSnapshot`
  - `fun updateProximityTarget(...) : ProximityTelemetry`
  - `fun computeSpectrumAnalysis(networks: List<BssidRadio>, band: WifiBand, includeDfs: Boolean, excludeConnectedSsid: Boolean): SpectrumAnalysis`
  - `fun computeEnvironmentHealth(snapshot: RadarSnapshot): EnvironmentHealthReport`
  - `fun exportDiagnosticJson(snapshot: RadarSnapshot, health: EnvironmentHealthReport, redactMacs: Boolean): String`
  - `fun exportDiagnosticMarkdown(snapshot: RadarSnapshot, health: EnvironmentHealthReport, redactMacs: Boolean): String`
- `WifiRadarRepository` (consumed by `:feature:networks`, `:feature:proximity`, `:feature:spectrum`, `:feature:insights` — zero direct dependency on Android `WifiManager`!):
  - `val radarSnapshot: StateFlow<RadarSnapshot>` (contains `radios: List<BssidRadio>`, `groupedNetworks: List<EssNetworkGroup>`, `physicalClusters: List<PhysicalApCluster>`, `scanTelemetry: ScanTelemetry`, `connectedWifi: ConnectedWifiObservation?`, `radioCapabilities: DeviceRadioCapabilities`, `roamEvents: List<RoamEvent>`, `rssiTimelines: Map<String, List<RssiSample>>`)
  - `val readinessState: StateFlow<ScannerReadinessState>`
  - `fun observeProximity(targetKey: ProximityTargetKey, environment: PropagationEnvironment): Flow<ProximityTelemetry>`
  - `fun computeSpectrum(band: WifiBand, includeDfs: Boolean, excludeOwnSsid: Boolean): Flow<SpectrumAnalysis>`
  - `val environmentHealth: StateFlow<EnvironmentHealthReport>`
  - `fun requestManualScan(burstMode: Boolean = false)`
  - `suspend fun setBssidNickname(bssid: String, nickname: String?)`
  - `suspend fun calibrateOneMeter(bssid: String, currentSmoothedDbm: Double)`
  - `suspend fun clearOneMeterCalibration(bssid: String)`
  - `fun exportSnapshot(format: ExportFormat, redactMacs: Boolean): ExportPayload`

### 3. `:cli-runner` Opaque-Box CLI Contract (`com.a55.wifiradar.cli.MainKt`)
- Invocation: `./gradlew :cli-runner:run --args="analyze --input <path|- > --format <json|markdown> [--target-bssid <mac>] [--env <OPEN|HOME_OFFICE|HEAVY_WALLS>] [--redact-macs]"` (also buildable as standalone fat/application distribution via `./gradlew :cli-runner:installDist` -> `cli-runner/build/install/cli-runner/bin/cli-runner`).
- Input JSON: `{ "deviceHas6Ghz": false, "frames": [ { "elapsedMs": 1000, "connectedBssid": "aa:bb:cc:00:00:01", "scans": [ { "ssid": "...", "rawSsidHex": null, "bssid": "...", "rssiDbm": -54, "frequencyMhz": 5180, "centerFreq0Mhz": 5210, "centerFreq1Mhz": 0, "channelWidthMhz": 80, "wifiStandard": "11ax", "capabilities": "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]", "ies": [ { "id": 11, "idExt": 0, "hex": "0c00400000" } ] } ] } ] }`
- Output (`--format json` or `--format markdown` on `stdout`, exit code `0` on valid input, non-zero with descriptive `stderr` message on invalid CLI usage).

### 4. Android Application & Opaque-Box UI Entry Points (`:app` ↔ `:e2e-tests`)
- Application ID: `com.a55.wifiradar`
- Launcher Activity: `com.a55.wifiradar.MainActivity` (`android.intent.action.MAIN` + `android.intent.category.LAUNCHER`)
- Bottom Navigation Labels (exact user-visible tab titles):
  1. `Networks`
  2. `Proximity`
  3. `Spectrum`
  4. `Insights`
- AndroidManifest Permissions: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `NEARBY_WIFI_DEVICES` (NO `neverForLocation` flag), `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `VIBRATE`.
- AndroidManifest `<uses-feature>`: `android.hardware.wifi` (`required="true"`), `android.hardware.wifi.rtt` (`required="false"`), `android.hardware.location.gps` (`required="false"`).

---

## Code Layout

```text
/home/antigravity/
├── PROJECT.md                          # Synced copy of authoritative project spec (M1+)
├── TEST_INFRA.md                       # E2E Testing Track architecture & methodology (E2E Track)
├── TEST_READY.md                       # E2E Testing Track completion signal (E2E Track)
├── scripts/
│   └── env.sh                          # Reusable JAVA_HOME / ANDROID_HOME / JAVA_TOOL_OPTIONS setup (M1)
├── settings.gradle.kts                 # Multi-module declaration (M1 exclusive)
├── build.gradle.kts                    # Root plugin configuration (M1 exclusive)
├── gradle.properties                   # 0-swap JVM heap caps & cacerts.p12 truststore (M1 exclusive)
├── gradle/
│   ├── libs.versions.toml              # Frozen version catalog (M1 exclusive)
│   └── wrapper/                        # Gradle 8.14.7 wrapper (M1 exclusive)
├── core/
│   ├── model/                          # Pure Kotlin JVM domain models, semantic formatters & interfaces (M1 exclusive)
│   ├── designsystem/                   # AMOLED M3 theme, semantic badges, legend pills, Canvas helpers (M1 exclusive)
│   ├── rf-engine/                      # Pure Kotlin JVM RF math, IE parser, OUI DB, Kalman, CCI/ACI, exporter (M2 exclusive)
│   └── wifi-platform/                  # Android WifiManager, Readiness, Token-Bucket, 300ms WifiInfo, Geiger (M3 exclusive)
├── cli-runner/                         # Pure Kotlin JVM CLI harness wrapping :core:rf-engine (M2 exclusive)
├── feature/
│   ├── networks/                       # Networks tab UI, ViewModel, Grouped/BSSID cards, Detail sheet (M4 exclusive)
│   ├── proximity/                      # Proximity tab UI, ViewModel, 120Hz Sonar Gauge, Sparkline, Geiger UI (M4 exclusive)
│   ├── spectrum/                       # Spectrum tab UI, ViewModel, 2.4/5/6GHz overlap Canvas, Best-Channel (M5 exclusive)
│   └── insights/                       # Insights tab UI, ViewModel, Health Score, CCI/ACI/Time/Security, Share (M5 exclusive)
├── app/                                # MainActivity, 4-tab NavHost, DI graph, FileProvider, AndroidManifest, APK signing (M6 exclusive after M1 shell)
└── e2e-tests/                          # Opaque-box E2E tests: Robolectric+Compose UI, CLI E2E, APK checks (E2E Track exclusive after M1 build.gradle.kts stub)
```

### Exclusive Write Ownership Rules (CRITICAL)
- Parallel Workers MUST NEVER write outside their milestone's assigned directories.
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `core/model/`, and `core/designsystem/` are frozen at the completion of **M1**.
- `e2e-tests/`, `TEST_INFRA.md`, and `TEST_READY.md` are owned **exclusively** by the **E2E Testing Track** (except during **M7 Phase 2 Tier 5**, where the M7 Worker integrates adversarial coverage hardening tests after `TEST_READY.md` is published and Tiers 1–4 pass).
