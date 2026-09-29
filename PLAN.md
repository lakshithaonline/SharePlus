# PLAN.md — SharePlus Development Plan (Claude Code builds it)

**Goal:** iPhone (iOS 26.6.1) → Screen Mirroring → JVC Google TV (Android 11), with audio. Free.
**Strategy:** port the proven open-source receiver (UxPlay core + jqssun Android app, via the
manu-tech-code fork) into the SharePlus project, then make it TV-first and stable.
Porting a working base is far more reliable than rewriting the AirPlay protocol.

Legend: 🤖 = Claude Code does it · 👤 = user does a physical step · ✅ = verification that must pass

```
iPhone ──Wi-Fi──► [UxPlay C core: mDNS + RTSP + FairPlay + decrypt]
                        │ JNI
                        ▼
          [Kotlin: MediaCodec → SurfaceView]  [MediaCodec → AudioTrack]
```

---

## Phase 0 — Environment check
- [x] 0.1 🤖 Read `app/build.gradle.kts`: report compileSdk / targetSdk / minSdk; set **minSdk ≤ 30**
      (keep the new compile/target SDK). Confirm NDK + CMake are installed (`local.properties`, SDK dir).
- [x] 0.2 👤 TV developer mode on, USB/Wireless debugging on, TV paired with ADB.
- [x] 0.3 🤖 `adb devices`; record `ro.build.version.sdk` (expect 30), `ro.product.cpu.abilist`,
      the decoders (`adb shell dumpsys media.player` or list the codecs) in NOTES.md.
- [x] 0.4 👤 iPhone + TV on the **same Wi-Fi** (not guest; 5 GHz band or TV on Ethernet).
- [x] 0.5 🤖 Go/No-Go: download the prebuilt jqssun APK from GitHub Releases, install it on the TV.
      👤 Mirror the iPhone to it. ✅ Picture + sound work → uninstall it → continue.
      ❌ Fix the network (client isolation / guest Wi-Fi / mDNS blocked) before going further.

## Phase 1 — Project foundation
- [x] 1.1 🤖 Manifest for TV: leanback feature, touchscreen not required, banner, LEANBACK_LAUNCHER,
      permissions (INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE,
      WAKE_LOCK). ✅ The app launches from the TV apps row.
- [x] 1.2 🤖 Clone `manu-tech-code/airplay-receiver` to `../reference/` and write an architecture
      summary + file map to NOTES.md (native, JNI, Kotlin player, HLS parts).
- [x] 1.3 🤖 Git init, `.gitignore`, first commit.

## Phase 2 — Native core
- [x] 2.1 🤖 Vendor `third_party` (UxPlay, libplist, OpenSSL, FFmpeg) into `app/src/main/cpp`, keeping licences.
- [x] 2.2 🤖 CMakeLists + `externalNativeBuild` + `abiFilters` for the TV's ABI(s).
      ✅ `assembleDebug` succeeds; the `.so` files are in the APK (`unzip -l`).
- [x] 2.3 🤖 Port the JNI bridge (adapt package names to `com.example.shareplus`).
      ✅ The app starts on the TV with no `UnsatisfiedLinkError`.

## Phase 3 — Discovery & connection
- [x] 3.1 🤖 `AirPlayServer` (start/stop, MulticastLock, server name "SharePlus"), running in a
      foreground service or tied to the Activity lifecycle.
      👤 Open Screen Mirroring on the iPhone. ✅ "SharePlus" is in the list.
- [x] 3.2 🤖 Connection lifecycle logging. 👤 Tap SharePlus on the iPhone.
      ✅ logcat shows the pairing/FairPlay handshake succeeded and the stream started.

## Phase 4 — Video
- [x] 4.1 🤖 Video frames over JNI (direct ByteBuffers, correct thread attach/detach).
- [x] 4.2 🤖 `VideoDecoder`: MediaCodec H.264 → SurfaceView, csd handling, low-latency flags
      where supported on API 30.
      👤 Mirror. ✅ iPhone screen on the TV (🤖 confirms with `screencap`).
- [ ] 4.3 🤖 Aspect ratio + portrait/landscape switching + mid-stream resolution changes.
      👤 Rotate the iPhone. ✅ Clean switch, no stretching.

## Phase 5 — Audio
- [ ] 5.1 🤖 AAC-ELD (mirror mode) via MediaCodec → AudioTrack; ALAC (audio-only mode) via FFmpeg.
      👤 Play a YouTube video. ✅ Sound plays from the TV; iPhone volume works.
- [ ] 5.2 🤖 A/V sync using RTP timestamps. ✅ No visible lip-sync drift over 5 min.

## Phase 6 — TV experience
- [x] 6.1 🤖 Idle screen: "Ready to mirror", TV name, IP, simple instructions.
- [x] 6.2 🤖 States Idle → Connecting → Mirroring → Disconnected; release codecs on stop.
- [ ] 6.3 🤖 D-pad: Back = disconnect/exit; OK = info overlay. Keep the screen on while mirroring.
- [ ] 6.4 🤖 Network loss → auto-restart the server; app backgrounded → stop cleanly.
- [ ] 6.5 🤖 App banner + icon.

## Phase 7 — Hardening
- [ ] 7.1 👤+🤖 30-min session; 🤖 monitors memory and logcat for leaks/errors. ✅ No crash.
- [ ] 7.2 👤 Reconnect 10× in a row. ✅ Every time works.
- [ ] 7.3 👤 TV sleep/wake with the app open. ✅ Recovers.
- [ ] 7.4 🤖 Fix every issue found; log each in NOTES.md.

## Phase 8 — Release
- [ ] 8.1 🤖 Release signing config (keystore outside the repo, tell the user to back it up).
- [ ] 8.2 🤖 `assembleRelease` (R8/ProGuard rules for JNI classes), install, re-test Phase 4–5.
- [ ] 8.3 🤖 README: how to install on the TV, licence (GPLv3), credits.

## Phase 9 — Background receiver, idle screen, quality, HLS, sleep handling
> **Depends on Phases 1–8 being done first** (vendored UxPlay, JNI bridge, working video/audio
> pipeline, TV UX states). None of that exists in SharePlus yet — only the external prebuilt
> jqssun APK has been tested so far, for the Phase 0.5 go/no-go check.

- [ ] 9.1 🤖 Background receiver: `AirPlayServer` in a foreground service, independent of any
      Activity, so it keeps running under Netflix/YouTube.
      - 🤖 On iPhone connect, bring the mirroring Activity to front (needs "Display over other
        apps" — grant via `adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow` since this TV
        has no Settings UI for it).
      - 🤖 `RECEIVE_BOOT_COMPLETED` receiver starts the service on TV boot.
      - 🤖 Foreground service + notification channel, restart-on-kill, survive low-memory killer
        as well as Android allows.
      👤 Open YouTube on the TV, mirror from the iPhone. ✅ The app pops to front automatically.
- [ ] 9.2 🤖 Idle screen: full-screen TV design — large clock, date, TV name as it appears on the
      iPhone's Screen Mirroring list, and the guest hint ("On your iPhone: swipe down from the
      top-right → Screen Mirroring → [TV name]"). Slow subtle motion or dimming to avoid burn-in.
      ✅ 🤖 Screenshot via `screencap`, shown to you for review.
- [ ] 9.3 🤖 Quality/smoothness:
      - HEVC mirroring (UxPlay `-h265` / `ScreenMultiCodec`) when the TV decoder supports it
        (confirmed: `OMX.MS.HEVC.Decoder`, Main/Main10/Main10HDR10 up to 5.1), falling back to H.264.
      - Request up to 4K/60fps from the iPhone if the decoder can handle it.
      - `MediaFormat.KEY_LOW_LATENCY` on API 30 where supported.
      - Render frames on their RTP-timestamp schedule; drop late frames instead of queuing lag.
      - Switch TV display refresh to 60Hz while mirroring if supported.
      - Hidden stats overlay (fps, bitrate, resolution, codec, latency), toggled with D-pad OK.
      ✅ 👤+🤖 Before/after stats with the same iPhone content.
- [ ] 9.4 🤖 YouTube AirPlay (HLS): enable UxPlay's HLS video-streaming mode so the iPhone
      YouTube app's AirPlay icon plays directly on the TV via Media3/ExoPlayer, with iPhone-side
      play/pause/seek.
      👤 Cast a YouTube video from the iPhone app. ✅ Plays on the TV, transport controls work.
- [ ] 9.5 🤖 Fix: session ends when the iPhone locks/sleeps.
      - First confirm in logcat that this is iOS-side (iPhone stops mirroring on lock) — no TV-side
        fix can prevent that.
      - a) On disconnect, show "Paused — unlock your iPhone to continue" for ~10s, then idle screen.
      - b) Reconnect must be instant/reliable — no stale session blocking the next connection.
      - c) Idle screen tip: "Keep iPhone awake: Settings → Display & Brightness → Auto-Lock → Never".
      - Note: 9.4's YouTube AirPlay keeps playing through iPhone lock, since the TV streams the
        video itself once handed the URL.

## Phase 10 — Further extras (optional)
- [ ] Settings: TV name, resolution, fps, PIN

---

## Troubleshooting (for Claude Code)
| Symptom | Check first |
|---|---|
| Won't install | minSdk > 30; ABI mismatch (`INSTALL_FAILED_NO_MATCHING_ABIS`) |
| Not in iPhone list | MulticastLock, server started, same subnet, router client isolation |
| In list, connect fails | Handshake errors in logcat; compare init with the reference |
| Black screen, audio OK | csd/SPS-PPS not given to MediaCodec; surface not ready |
| Stutter/lag | Decoder queue backlog, resolution/fps, Wi-Fi band |
| Crash in native code | `adb logcat` tombstone + `ndk-stack` with symbols |
| Apple TV app shows no video | Expected: Apple DRM |
