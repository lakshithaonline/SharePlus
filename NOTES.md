# NOTES.md — SharePlus build/debug log

## Phase 0 — Environment check (2026-09-29)

### 0.1 — SDK config
- Project was a fresh Android Studio TV template (`com.example.shareplus`, package/appId not yet renamed — will do that in Phase 2.3 alongside the JNI port).
- Found: `compileSdk = 37`, `targetSdk = 37`, `minSdk = 36`. minSdk violated the "≤ 30" rule (TV is Android 11 / API 30) — the app would have refused to install.
- Fix: set `minSdk = 30` in `app/build.gradle.kts` (kept compileSdk/targetSdk at 37, the newest installed platform). Left compileSdk/targetSdk alone per project rules.
- Verified: `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` (no system JDK on PATH) + `./gradlew.bat assembleDebug` → **BUILD SUCCESSFUL**.
- Installed SDK platforms: `android-29`, `android-36`, `android-37.0`. No `android-30` platform installed, but that's not required to target minSdk 30 — compileSdk 37 is a superset.
- **NDK: not installed. CMake: not installed.** No `cmdline-tools` component either, so no `sdkmanager` CLI to install them from Bash/PowerShell directly. Plan: let AGP auto-download NDK/CMake once Phase 2.2 adds `externalNativeBuild`/`CMakeLists.txt` (this is a normal, automatic step in Gradle syncs when SDK licenses are accepted) — if that fails, will ask you to install "NDK (Side by side)" + "CMake" via Android Studio's SDK Manager UI instead.

### 0.2 / 0.4 — Physical steps (done)
- TV paired over ADB via Wi-Fi at `192.168.68.52:5555`. `adb` is not on PATH — either prefix commands with
  `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"` (PowerShell) or add platform-tools to PATH for the session.
- iPhone + TV + laptop all on the same home Wi-Fi (not guest).

### 0.3 — Device info (TV: JVC 55" JV5524M, adb serial `192.168.68.52:5555`)
- `ro.product.model` = `JVC G24 TV`, `ro.product.manufacturer` = `skyworth`, `device:anaheim`
- `ro.build.version.sdk` = **30**, `ro.build.version.release` = **11** — matches expectations.
- `ro.product.cpu.abilist` = **`armeabi-v7a,armeabi`** — this TV is **32-bit only** (no arm64-v8a!).
  This is the single most important constraint for Phase 2: NDK/CMake `abiFilters` must target
  `armeabi-v7a` only, and any prebuilt `.so` (UxPlay, libplist, OpenSSL, FFmpeg) must have a 32-bit build.
- MemTotal ≈ 2.8 GB.
- Decoders (`dumpsys media.player`), relevant to Phase 4/5:
  - `OMX.MS.AVC.Decoder` (H.264): hw-accelerated, profiles up to High/5.1, 2 concurrent instances.
  - `OMX.MS.HEVC.Decoder` (H.265): hw-accelerated, Main/Main10/Main10HDR10 up to 5.1 — available if we
    ever do Phase 9's H.265 extra, but AirPlay mirroring is H.264 so this isn't on the critical path.
  - `c2.android.aac.decoder` (AAC): software, profiles include **LC, HE, HE_PS, LD, ELD, XHE** — ELD is
    what AirPlay mirror-mode audio needs, and it's supported.

### 0.5 — Go/No-Go (PASSED)
- Installed `io.github.jqssun.airplay` v0.0.31 (universal APK, has an `armeabi-v7a` slice) on the TV.
- User confirmed picture + sound both worked mirroring the iPhone to it. Network/mDNS/AirPlay is
  fully viable on this Wi-Fi — no client isolation or guest-network issues.
- Uninstalled the test APK afterward. Phases 1+ build the real receiver into SharePlus itself.

## Phase 1 — Project foundation

### 1.1 — TV manifest (done)
- Added the 5 permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
  `CHANGE_WIFI_MULTICAST_STATE`, `WAKE_LOCK`) to `AndroidManifest.xml`. Leanback feature,
  banner, and `LEANBACK_LAUNCHER` category were already present in the template.
- Verified 3 ways: (1) `cmd package query-activities -c LEANBACK_LAUNCHER` lists
  `com.example.shareplus/.MainActivity`; (2) it shows up in the Google TV launcher's "Your apps"
  row (as a default green Android-robot icon — no custom icon set yet); (3) `am start` brings it
  to foreground (`mResumedActivity` confirms) with nothing in logcat (`FATAL`/`AndroidRuntime`).
- Note: this TV runs the **Google TV launcher skin**, not stock Android TV/Leanback launcher.
  Freshly-sideloaded apps don't appear in the curated top carousel (that's Play-Store-driven
  content), only in "Your apps" — expected launcher behavior, not a manifest problem.

### 1.2 — Reference repo architecture (`manu-tech-code/airplay-receiver`, branch `dev`)

Cloned (shallow, read-only study material) to `../reference/airplay-receiver`. Per CLAUDE.md rule 7,
don't copy large chunks from it — this section is so we (and future sessions) don't have to
re-read the whole thing; point to these files and write our own version.

**Native core (`app/src/main/cpp/`)** — one shared lib `airplay_native.so`, built from:
UxPlay's own C sources (raop.c, raop_rtp*.c, httpd.c, dnssd.c, pairing.c, crypto.c,
fairplay_playfair.c, airplay_video.c, etc., from `third_party/UxPlay/lib`) + embedded `llhttp` and
`playfair` (also from UxPlay's tree) + this repo's own bridge files (`android_dnssd_shim.c`,
`android_raop_callbacks.c`, `native_bridge.cpp`, `audio_engine.cpp`) + `libplist` (built from
vendored source, static) + OpenSSL 3.4.4 (built in-tree via `openssl-cmake`, pointed at the
vendored source so it never fetches over the network — see `CMakeLists.txt:12-34`) + Oboe
(Google's low-latency audio lib, via a prefab AAR: `find_package(oboe REQUIRED CONFIG)`) + ffmpeg
(via a `BuildFFmpeg` cmake module — used only for **software ALAC decode**, not video). Links
against `mediandk` (NDK MediaCodec) directly, plus `m`, `log`, `android`. Has an opt-in
`-DSANITIZE=ON` for UBSan (+HWSan on arm64).

**JNI boundary** (`native_bridge.cpp:47-423` <-> `bridge/NativeBridge.kt`): one
`server_ctx_t` struct per server instance holding `raop_t*`, `dnssd_t*`, an
`android_callback_ctx_t` (JNI plumbing to call back into Kotlin), and an `AudioEngine*`.
Lifecycle: `nativeInit` (raop_init/raop_init2, dnssd_init, audio_engine_create) → `nativeStart`
(raop_start_httpd + dnssd_register_raop/airplay) → ... → `nativeStop`/`nativeDestroy`
(raop_stop_httpd, raop_destroy, audio_engine_destroy, dnssd_destroy). Every JNI export is named
`Java_com_manutechcode_airplay_bridge_NativeBridge_native*`, one-to-one with `external fun`
declarations in `NativeBridge.kt` (a Kotlin `object` that does `System.loadLibrary("airplay_native")`
in `init {}`). **Feature toggles are mostly just DNS-SD TXT-record/feature-bit flips**, not codec
logic: `nativeSetH265Enabled` sets AirPlay feature bit 42 (`SupportsScreenMultiCodec`);
`nativeSetHlsEnabled` sets raop's `"hls"` plist key (gates raop.c's HLS support) + feature bits
0 and 4; `nativeSetCodecs` (ALAC/AAC) only touches the dnssd TXT records — actual codec selection
happens Kotlin-side.

`android_raop_callbacks.h/c` bridges UxPlay's `raop_callbacks_t` (C function-pointer table) to a
Kotlin `RaopCallbackHandler` (`bridge/RaopCallbackHandler.kt`) via cached `jmethodID`s — one
callback per AirPlay event: `onVideoData`, `onAudioFormat`, `onVideoSize`, `onVolumeChange`,
`onClientVolume`, `onAudioTeardown`, `onConnectionInit/Destroy/Reset`, `onDisplayPin`,
`onMetadata`, `onCoverArt`, `onProgress`, `onDacpId`, `onMirrorRunning`, and the HLS-specific
`onVideoPlay/Scrub/Rate/Stop` + `onVideoSessionPoll`. **These fire on native httpd worker
threads, not the main thread** — `AirPlayService`'s implementations either mutate `StateFlow`s
(safe) or explicitly `_mainHandler.post {}` before touching `AudioManager`/UI. There's also a
mutex+condvar-guarded "playback info" snapshot (`android_raop_callbacks.h:41-50`): Kotlin pushes
ExoPlayer's position/duration/rate via `nativeUpdatePlaybackInfo`, and the native httpd thread
reads it to answer the sender's polling `GET /playback-info` — this is how the iPhone's HLS
transport UI (play/pause/scrub bar) stays in sync.

`android_dnssd_shim.h/c`: UxPlay's `dnssd.c` normally calls Apple's Bonjour/mDNSResponder, which
doesn't exist on Android. **The shim only builds the TXT-record key/value lists**
(`android_dnssd_get_raop_txt_*` etc.) — actual mDNS packet broadcasting is delegated to Kotlin's
`NsdServiceManager` (`discovery/NsdServiceManager.kt`), which uses stock `android.net.nsd.NsdManager`
+ a non-reference-counted `WifiManager.MulticastLock` to register `_raop._tcp` and `_airplay._tcp`.
This is the single biggest Android-specific adaptation UxPlay needed.

**Audio pipeline** (`audio_engine.h/cpp`): C++ engine wrapping Oboe. `audio_engine_create(log,
sampleRate, channels)` → `configure(cushionMs, percentilePct, oboeBufferFrames, forceSwAlac,
realtimePriority, lowLatency, benchmarkLog)` → `on_format(ct, spf)` (sender announces codec) →
`decode(data, len, ct, pts_ns)` per RTP audio packet → `start`/`pause` → `get_debug` (packed stats
struct, feeds the debug overlay) → `destroy`. `audio_engine_set_default_stream_values` feeds
`AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`/`_FRAMES_PER_BUFFER` to Oboe once at startup, needed by
its OpenSL ES backend on pre-AAudio devices (irrelevant for our API-30 TV, which has AAudio).

**Video pipeline** (`renderer/VideoRenderer.kt` + `VideoPipeline.kt` + `DecoderSelector.kt`) —
directly relevant to **Phase 9.3**:
- `VideoPipeline.kt`: a persistent GL/EGL thread with its own OES `SurfaceTexture`. The
  `MediaCodec` decodes into this app-owned surface, and the GL thread blits to whatever display
  `Surface` is currently attached — so surface swaps (fullscreen toggle, Activity recreation)
  don't require restarting the codec. The codec itself IS restarted per mirror session
  (`VideoRenderer.startSession/stopSession`) and on codec-type or fatal-error changes, always
  waiting for a fresh keyframe before restarting (`_isKeyframe`, `VideoRenderer.kt:166-184`).
- `DecoderSelector.kt` (ported from **Moonlight-android's MediaCodecHelper**) — the single most
  reusable file here. Encodes years of "which TV boxes lie about codec support": per-vendor
  HEVC blacklist/whitelist by codec-name prefix (Adreno version gating, Exynos, MediaTek,
  Amlogic, Realtek, HiSilicon, Qualcomm "qti"), Android version gates, `MEDIA_PERFORMANCE_CLASS`
  checks, emulator detection. `lowLatencyOptions()` (`DecoderSelector.kt:93-122`) tries, in order:
  `"low-latency"=1` (the API 30 `KEY_LOW_LATENCY` key, passed as a raw string) →
  `"vdec-lowlatency"=1` (older vendor key) → `KEY_OPERATING_RATE`/`KEY_PRIORITY` → vendor-specific
  extension keys — a retry "ladder" (`VideoRenderer._startWithLadder`) that reconfigures with
  progressively fewer risky options if `MediaCodec.configure()` throws.
- **Frame pacing** (already solves Phase 9.3's "render on time, don't burst"):
  `VideoRenderer.drainOutput()` uses the timed `releaseOutputBuffer(idx, timestampNs)` overload,
  anchoring the first frame's `presentationTimeUs` to `System.nanoTime()` and releasing every
  subsequent frame at that offset — i.e. frames come out paced to their original PTS spacing, not
  as fast as the decoder can produce them. Gated by a `scheduledOutputBufferRelease` pref.
- **Frame dropping** (Phase 9.3's "drop late frames instead of building lag"): if the decoder's
  input queue is still full after `FEED_RETRIES` (50 for the first frame, 10 after), the frame is
  dropped and `droppedFrames` increments (feeds the debug overlay) — `VideoRenderer.kt:146-164`.
- Our TV's decoder is `OMX.MS.HEVC.Decoder` (see NOTES.md Phase 0.3) — hardware-accelerated but
  from Microsoft's Windows-IoT-derived OMX stack (this is a Skyworth/JVC Google TV), which isn't
  in `DecoderSelector`'s vendor lists at all. We'll likely hit the "not whitelisted, falls back to
  a capability probe" path (`hevc()`, `DecoderSelector.kt:65-72`) rather than a known-good vendor
  match — worth extra scrutiny/testing once we get there.

**HLS / YouTube-AirPlay playback path** (`renderer/AirPlayVideoPlayer.kt` + `download/VideoDownloader.kt`)
— directly relevant to **Phase 9.4**. It's a thin Media3 ExoPlayer wrapper: native's
`onVideoPlay(location, startPositionSeconds)` callback (fired when UxPlay's raop.c handles the
sender's `POST /play` with a `Location:` HLS URL) calls `AirPlayVideoPlayer.play()`, which just does
`ExoPlayer.Builder(context).build()` + `setMediaItem(MediaItem.fromUri(location))`.
`onVideoScrub`/`onVideoRate`/`onVideoStop` map directly to ExoPlayer seek/speed/stop. The
`onPlaybackInfo` callback pushes ExoPlayer's live position/duration/rate back into native via
`nativeUpdatePlaybackInfo` every 250ms (`REPORT_INTERVAL_MS`), which is what native uses to answer
the sender's `/playback-info` poll (see JNI boundary section above) — that's the whole mechanism
behind the iPhone's transport controls stayin sync with TV-side playback. Getting HLS working
wasn't just "flip a flag": `cpp/patches/UxPlay/` has 6 patches on top of vendored UxPlay, 3 of them
HLS-specific bugfixes — `0001-fix-hls-playlist-crash`, `0002-fix-uaf-on-playlist-refresh`,
`0003-refresh-live-media-playlists-on-demand` (the other 3: `0004-video-sender-compat`,
`0005-signal-video-reset-on-abandoned-play-connection`, `0006-deliver-volume-while-audio-stream-paused`).

**Service lifecycle & boot-start** — directly relevant to **Phase 9.1**:
`service/AirPlayService.kt` extends `LifecycleService` (not a plain `Service`), runs as a
foreground service with `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`. On the first
client connecting (`onConnectionInit`, connection count 0→1), unless a PIN is required, it calls
`launchMainActivity()`
(`AirPlayService.kt:1075-1085`) which does
`startActivity(Intent(...).addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT))`
**directly from the Service — no `SYSTEM_ALERT_WINDOW` / "display over other apps" permission
anywhere in this codebase.** That works because starting an activity from a process with an
*active foreground service* is one of Android 10+'s background-activity-start exemptions — a
plain background app can't do this, but a foreground service can. **This corrects the assumption
in Phase 9.1** — we very likely don't need the `appops`/overlay-permission workaround at all; try
the plain foreground-service + `startActivity` approach first and only reach for `SYSTEM_ALERT_WINDOW`
if the TV's launcher skin (Google TV, not stock Leanback — see 1.1 note above) blocks it.
`WakeLock` is `PARTIAL_WAKE_LOCK` only ("airplay:server"), held for the server's lifetime — not a
screen wakelock. Boot-start: `BootReceiver.kt` listens for `ACTION_BOOT_COMPLETED` (+
`QUICKBOOT_POWERON` for some OEMs), checks a stored preference, then
`ContextCompat.startForegroundService(..., ACTION_START_SERVER)`. On low-memory-killer survival:
found nothing exotic — no special LMK-avoidance trick, just a correctly-implemented foreground
service + notification (Android's standard exemption) and avoiding anything that would make
Android want to kill the process (leaked wakelocks, ANRs). Worth setting that expectation with
the user for 9.1 rather than promising more than "as well as Android's normal rules allow."

**Vendored third_party build config**: OpenSSL and libplist are built from source in-tree (no
network fetch during build — see CMakeLists.txt comments). Oboe comes from a prebuilt Prefab AAR
dependency, not vendored source. ffmpeg is built via a separate `BuildFFmpeg` CMake module
(needed only for software ALAC decode — H.264/HEVC video decode uses MediaCodec, not ffmpeg).

**Other things noted, not deep-read** (lower priority, Compose UI / DI wiring, safe to defer):
`AirPlayApp.kt` is a bare `@HiltAndroidApp` Application class — **this whole app uses Dagger
Hilt DI**; we are not planning to adopt Hilt for SharePlus (keep it minimal per CLAUDE.md), so
this is a structural difference to expect when comparing the two codebases. `ui/` package
(MainScreen, LogsScreen, SettingsScreen, VideoSurfaceView, PlaybackSpeedSelector, gestures/) is
Compose TV UI we haven't read in detail. `audio/DacpController.kt` + `DacpPlayer.kt` +
`DmapParser.kt` handle the DACP remote-control / now-playing-metadata side channel (separate from
video/audio streaming) — not needed for our Phase 1-8 critical path.

**File map** (files actually read for this summary):

| File | Purpose |
|---|---|
| `cpp/CMakeLists.txt` | Native build: links UxPlay + own bridge + vendored OpenSSL/libplist/ffmpeg + Oboe |
| `cpp/native_bridge.cpp` | JNI entry points (`Java_com_manutechcode_airplay_bridge_NativeBridge_*`) |
| `cpp/android_raop_callbacks.h/c` | C callback table → Kotlin `RaopCallbackHandler` JNI dispatch |
| `cpp/android_dnssd_shim.h/c` | Builds dnssd TXT records only; NSD registration is Kotlin-side |
| `cpp/audio_engine.h` (`.cpp` not read in full) | Oboe-based audio decode/output engine, C API |
| `kotlin/.../bridge/NativeBridge.kt` | Kotlin `external fun` declarations, 1:1 with native_bridge.cpp |
| `kotlin/.../bridge/RaopCallbackHandler.kt` | Interface `AirPlayService` implements; called from native threads |
| `kotlin/.../service/AirPlayService.kt` | Foreground service, server lifecycle, connect/disconnect handling |
| `kotlin/.../discovery/NsdServiceManager.kt` | Android NSD registration (`_raop._tcp`, `_airplay._tcp`) + MulticastLock |
| `kotlin/.../renderer/VideoRenderer.kt` | MediaCodec lifecycle, keyframe-gated restart, frame pacing/dropping |
| `kotlin/.../renderer/VideoPipeline.kt` | Persistent GL/EGL thread, OES SurfaceTexture, blits to display Surface |
| `kotlin/.../renderer/DecoderSelector.kt` | Per-vendor HEVC/low-latency codec selection (ported from Moonlight-android) |
| `kotlin/.../renderer/AirPlayVideoPlayer.kt` | Media3 ExoPlayer wrapper for HLS/YouTube-AirPlay playback |
| `kotlin/.../AirPlayApp.kt` | `@HiltAndroidApp` Application (Dagger Hilt — not adopting for SharePlus) |
| `kotlin/.../BootReceiver.kt` | `ACTION_BOOT_COMPLETED` → starts the service if a pref is set |
| `cpp/patches/UxPlay/000{1-6}-*.patch` | 6 upstream UxPlay bugfixes: HLS crashes/UAF (3), sender compat, abandoned-connection video reset, volume-while-paused |
| `kotlin/.../renderer/VideoDownloader.kt`, `ui/*`, `audio/Dacp*.kt`, `EglCore.kt` | Not deep-read — download-video button, Compose UI, DACP remote metadata, low-priority EGL helper |

## Phase 2 — Native core

### 2.1 — Vendored third_party (done)
- Copied `UxPlay`, `libplist`, `openssl`, `openssl-cmake`, `ffmpeg` (147M total) verbatim from the
  reference repo's already-vendored/trimmed/patched trees into
  `app/src/main/cpp/third_party/`, plus `PROVENANCE.md` (added a note that SharePlus copied this
  from `manu-tech-code/airplay-receiver` rather than re-fetching upstream) and the UxPlay patch
  record (`app/src/main/cpp/patches/UxPlay/*.patch`, already applied — informational only, matches
  Phase 1.2's notes on why HLS needed 3 of the 6 patches).
- License files verified present in every vendored dir: `UxPlay/LICENSE` (GPL-3.0),
  `libplist/COPYING`+`COPYING.LESSER` (LGPL-2.1), `openssl-cmake/LICENSE` (MPL-2.0),
  `openssl/LICENSE.txt` (Apache-2.0), `ffmpeg/COPYING.GPLv2`+`COPYING.GPLv3`+`COPYING.LGPLv2.1`+
  `COPYING.LGPLv3`+`LICENSE.md`. Per CLAUDE.md rule 8 these must stay — resulting app is GPL-3.0
  because of UxPlay regardless of the other components' more permissive licenses.
- Not yet building anything with this — that's 2.2 (CMakeLists + externalNativeBuild). NDK/CMake
  still not installed (see 0.1) — need to resolve before 2.2 can succeed.

### 2.2 / 2.3 — Native build + JNI bridge (done)
- NDK 27.0.12077973 + CMake 3.22.1 installed via Android Studio; `ndkVersion` pinned in Gradle.
- **Problem:** OpenSSL (`openssl-cmake` → perl `Configure` + make) and FFmpeg (`configure` + make)
  can't build on a plain Windows host: FFmpeg's configure (run in Git bash) writes MSYS paths
  (`/c/Users/...`) that the NDK's native `make.exe` can't open; OpenSSL's build env picked a bash
  with no `perl`/`cygpath`.
- **Fix:** OpenSSL now comes from Google's prebuilt Prefab AAR
  `com.android.ndk.thirdparty:openssl:1.1.1q-beta-1` (`openssl::crypto`, ships `libcrypto.so`).
  UxPlay's `crypto.c` only needs 1.1.1-level EVP APIs (raw X25519/Ed25519 keys). FFmpeg is now
  optional (`-DSHAREPLUS_FFMPEG=ON`, `SHAREPLUS_HAVE_FFMPEG` guards in `audio_decoder.h`): it was
  only the software **ALAC** fallback. Mirroring audio is AAC-ELD via MediaCodec, and we advertise
  AAC (not ALAC) for audio-only mode. Vendored sources stay in-tree (and
  `-DSHAREPLUS_VENDORED_OPENSSL=ON` still builds them on a Linux/macOS host).
- Verified: APK contains `lib/armeabi-v7a/{libairplay_native,libcrypto,liboboe,libc++_shared}.so`;
  on the TV, `NativeBridge` loads with no `UnsatisfiedLinkError`.

## Phases 3–6 — first iPhone tests (2026-09-29)
- Native threads attached in `android_raop_callbacks.c` were never detached; ART aborts when
  such a thread exits (every session end). Added a pthread TLS destructor that detaches.
- UxPlay log levels are syslog-style (lower = worse); the bridge mapped them inverted. Fixed,
  native tag is now `UxPlay`, level INFO.
- Test 1: "SharePlus" listed on iPhone, mirroring shows the screen (portrait 500x1080).
- **Bug:** opening YouTube switched the stream to 1920x1080 (new in-band SPS) → we restarted
  the codec → `OMX.MS.AVC.Decoder` wouldn't accept input for ~5 s (~100 dropped frames, frozen
  picture). Fix: configure once with `KEY_MAX_WIDTH/HEIGHT` 1920x1080 and, when the decoder has
  `FEATURE_AdaptivePlayback`, let it switch resolution in place instead of restarting.
- **YouTube while mirroring:** iOS video apps switch to "AirPlay video" (`video_pause` in logs,
  iPhone goes black and becomes the remote). We didn't advertise HLS, so it stalled. Now HLS is
  on: `onVideoPlay` → Media3 ExoPlayer (`HlsPlayer`), rate/scrub from the iPhone map to
  pause/seek, position pushed back every 250 ms via `nativeUpdatePlaybackInfo`.
- UI redone per user request: light, tvOS-like (white card, AirPlay glyph, status dot, steps).
- `screencap` on this TV prints junk before the PNG; strip everything before `\x89PNG`.
- Test 2: adaptive decoder works (portrait 500x1080 ↔ 1920x1080 ↔ landscape 1920x888 switch in
  place, 2 drops in total). YouTube never used the HLS path: it treats the mirrored TV as a
  **second screen** (stream jumps to 1920x1080, iPhone goes black, no audio until play). That is
  the YouTube app's own behaviour, not a receiver fault. User chose plain mirroring only, so
  AirPlay video (HLS/ExoPlayer) was removed again and HLS is no longer advertised.
- UI: user wants a static screen, so the burn-in drift animation was removed.
- **4K attempt (reverted at user's request):** TV only exposes 1920x1080 @60/50 Hz display modes
  to apps (the >60 Hz is panel-internal). With H.265 on and 3840x2160 advertised, the iPhone sent
  HEVC 998x2160 at ~46 fps and `OMX.MS.HEVC.Decoder` output was **green and smeared**. Pinning
  SDR BT.709 colour keys was tried but never verified; user chose to go back to 1080p H.264,
  which is clean. Screenshot of the failure mode was taken via `screencap`.

## Settings + app icon (2026-09-29)
- The "menu with settings" the user remembered was the jqssun test APK from Phase 0.5, not
  SharePlus. Added `SettingsActivity` (tvOS-style list): TV name (restarts the server so the
  iPhone sees the new name), open-on-connect, start-on-boot, performance stats, version/IP,
  open-source licences (GPL credits per CLAUDE.md rule 8). Settings button on the home screen
  (focused by default); MENU key also opens it.
- App icon: adaptive vector (white AirPlay glyph on blue→indigo gradient, same as the home
  card); TV banner PNGs rendered with PIL (`drawable-*/banner.png`). Template robot webps removed
  (minSdk 30 always uses `mipmap-anydpi-v26`).
- Gotcha: installing an update makes the Google TV launcher relaunch the app ~4 s later; a
  simulated keypress in that window is lost.
