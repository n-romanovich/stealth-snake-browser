# Stealth Snake

A lightweight Android browser engineered for real-time anti-fingerprinting, strict session isolation, and zero-trace wiping with a Chrome-camouflaged interface.

## Features

- **Minimal permissions**: only `INTERNET` and `ACCESS_NETWORK_STATE`. No storage, camera, microphone, or location permissions.
- **Session isolation & Wipe**:
  - One-tap session wipe clears cookies, WebStorage, cache, history, and rotates the per-session fingerprint seed.
  - Optional Auto-Wipe on app exit.
- **Anti-fingerprinting (`DocumentStart` JS + WebView hardening)**:
  - Per-session Canvas / WebGL noise (`getImageData`, `toDataURL`, `readPixels`)
  - WebAudio frequency/buffer noise
  - `visibilityState` / `hasFocus` masking and `resize` / viewport locking
  - Neutralized `DeviceMotion` / `DeviceOrientation` fallback
  - `performance.now()` coarsening (20 µs)
- **Network & behavioral controls**:
  - Built-in + custom domain blocklist (`shouldInterceptRequest`)
  - Local WebRTC ICE blocking and `navigator.sendBeacon` / `ping` blocking
  - External URI scheme blocking (`intent://`, `tel:`, `mailto:`, `tg://`, etc.)
  - JS `alert` / `confirm` / `prompt` suppression and idle Micro-Jitter
- **VPN Kill-Switch**:
  - Checks `ConnectivityManager` active network for `TRANSPORT_VPN` before loading pages and blocks HTTP/HTTPS requests (`403`) if VPN disconnects.
  - Optional toggle to open the local start page immediately while keeping network blocking active until VPN connects.
  - Note: Android WebView does not expose raw socket routing; for kernel-level enforcement across all sockets and system DNS, also enable **Always-on VPN** + **Block connections without VPN** in Android settings.
- **Presets & custom profiles**:
  - Built-in **Max Stealth** and **Compatibility** presets, plus custom user profiles (`+`) for per-site adjustments.
- **Built-in troubleshooting guide**:
  - Searchable EN/RU guide covering how each setting affects websites and what to toggle when something breaks.
- **Private screenshot storage & optional downloads**:
  - Save screenshots inside app-private storage (`filesDir/captures`) or to `Pictures/StealthSnake`, with optional Wipe cleanup.
  - Downloads disabled by default; when enabled, saved via `MediaStore` to `Downloads/StealthSnake` with local history tracking.
- **Sessions, PIN Lock, Reader Mode & Offline Pages**:
  - Multi-session tabs with isolated WebViews and per-session fingerprint seeds.
  - Optional 4–8 digit PIN lock with background timeout, `FLAG_SECURE`, and auto-wipe after 5 failed attempts.
  - Built-in Reader Mode and local Offline Pages archive viewer.

## Project structure

```text
app/src/main/
  assets/
    blocklist.txt             # Default tracker/ad/telemetry host rules
    snake_core.js             # DocumentStart anti-fingerprinting & behavioral script
  java/com/nromanovich/stealthsnake/
    SnakeApp.kt               # Application entry point & dynamic theme setup
    MainActivity.kt           # Browser UI, WebView lifecycle, VPN Kill-Switch orchestration
    BrowserSession.kt         # Isolated WebView session & fingerprint seed state
    BrowserUiHelper.kt        # Overflow menu, onboarding & sessions sheet
    SettingsActivity.kt       # Settings, presets, custom profiles, theme & search options
    SiteFixesActivity.kt      # Troubleshooting guide UI
    SiteFixGuide.kt           # Bilingual fuzzy search & guide entries
    SiteInfoSheet.kt          # Per-site security & blocklist sheet
    SnakePreferences.kt       # SharedPreferences store & custom profile serialization
    SnakeClient.kt            # Request blocking, VPN check, external scheme guard
    SnakeChrome.kt            # Progress, dialogs, fullscreen, download hooks
    SnakeScript.kt            # Runtime JS configuration & seed injector
    BlocklistEngine.kt        # Domain/path blocklist matcher
    VpnGuard.kt               # Active network VPN capability check
    MicroJitterController.kt  # Idle micro-jitter behavioral protection
    ReaderModeHelper.kt       # Reader mode extraction & formatting
    OfflinePagesManager.kt    # Offline page archive storage
    OfflineViewerActivity.kt  # Offline page viewer UI
    CaptureManager.kt         # Private & MediaStore screenshot capture
    CapturesActivity.kt       # Hidden screenshots viewer & exporter
    DownloadHelper.kt         # MediaStore & private downloader
    DownloadHistoryStore.kt   # Local download history store
    DownloadsActivity.kt      # Downloads history UI
    PinManager.kt             # Salted PBKDF2 PIN hashing & verification
    PinLockCoordinator.kt     # Lock timeout & failed-attempt auto-wipe
    PinDialogHelper.kt        # PIN entry & setup dialogs
    SnakeTheme.kt             # Palettes (Obsidian, AMOLED, Midnight, Emerald Splash, Slate Light)
app/src/test/
  java/com/nromanovich/stealthsnake/
    *Test.kt                  # JVM/Robolectric unit test suites (21 files)
testdata/                     # Blocklist test fixtures
test/js/                      # CDP harness tests for snake_core.js (snake.test.mjs, lib/)
```

## Build & test

Requires JDK 17 and Android SDK 34.

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
node --test test/js/snake.test.mjs
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.
