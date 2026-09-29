# CLAUDE.md — SharePlus (iPhone → Android TV AirPlay receiver)

## Project
A free Android TV app that acts as an **AirPlay receiver**. The iPhone (iOS 26.6.1) uses
**Screen Mirroring** to show its screen + audio on the TV. This is a practical build, not an
academic exercise: **Claude Code owns development end to end.**

- Target TV: JVC 55" Google TV JV5524M, **Android 11 (API 30)**
- Language: Kotlin (app) + C/C++ via NDK/JNI (AirPlay core)
- AirPlay protocol core: **UxPlay** (GPLv3, confirmed working with iOS 26)
- Base / reference: `manu-tech-code/airplay-receiver` (fork of jqssun/android-airplay-server,
  UxPlay + libplist vendored in-tree; OpenSSL + Oboe from prebuilt Prefab AARs)
- Plan + progress: `PLAN.md`

## SDK rules (important)
- Keep the project's existing **compileSdk / targetSdk** (the newest SDK installed). Don't downgrade.
- **minSdk must be ≤ 30**, or the app won't install on the Android 11 TV. Check
  `app/build.gradle.kts` first and fix it if needed.
- Guard any API newer than 30 with `Build.VERSION.SDK_INT` checks.
- Use the NDK/CMake versions installed in Android Studio; pin them in Gradle once the build works.
- Build for the TV's real ABI (`adb shell getprop ro.product.cpu.abilist`); include
  `armeabi-v7a` if listed. Many TVs run 32-bit userland even on 64-bit chips.

## How Claude Code works on this project
1. **Claude does everything:** writes the code, edits Gradle/CMake/manifest, builds, installs
   on the TV, reads logcat, debugs and fixes bugs, refactors, and updates docs.
2. Work through `PLAN.md` in order. After each task: build → install on the TV → run the task's
   verification → fix until it passes → tick the task in PLAN.md → commit.
3. **Git:** commit after each passing task with a clear message. Never `git push`, force-push,
   or rewrite history without asking.
4. **Testing loop (use it every time):**
   - Build: `./gradlew assembleDebug`
   - Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   - Launch: `adb shell am start -n com.lakshithaonline.shareplus/com.example.shareplus.MainActivity`
   - Logs: `adb logcat -c` then `adb logcat -s SharePlus:V UxPlay:V AndroidRuntime:E MediaCodec:W`
   - Screenshot the TV: `adb exec-out screencap -p > tv.png`, then look at it
   - Crashes: search logcat for `FATAL EXCEPTION` / `tombstone` and fix the root cause
5. **Only ask the user for physical actions** Claude can't do: tapping Screen Mirroring on the
   iPhone, confirming what's on the TV screen or whether sound plays, pairing/reconnecting ADB.
   Ask with one clear instruction, e.g. "On iPhone: Control Centre → Screen Mirroring → SharePlus.
   Tell me what the TV shows." Then continue from logcat.
6. Fix root causes, not symptoms. Don't disable features to make an error go away without saying so.
7. Keep a short `NOTES.md` log of problems hit and how they were fixed.
8. Keep GPLv3 licence files and credits (UxPlay, jqssun, manu-tech-code, libplist, OpenSSL).

## Log tags
- Kotlin: `SharePlus`
- Native (C/JNI): `UxPlay`
