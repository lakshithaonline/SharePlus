# SharePlus

A free **AirPlay receiver for Android TV**. Mirror your iPhone's screen and sound to the TV
with Control Centre → Screen Mirroring, no Apple TV needed.

Built and tested on a JVC 55" Google TV (JV5524M, Android 11, 32-bit ARM) with an iPhone on
iOS 26.

## Features
- iPhone/iPad **screen mirroring** with audio (H.264 1080p60, AAC-ELD)
- Portrait and landscape, with clean switching when you rotate
- Runs in the background: starts with the TV and jumps to the front when an iPhone connects
- The iPhone's volume buttons control the TV's volume
- Light, Apple-style TV interface with a Settings screen (TV name, auto-open, start on boot,
  performance stats)

Not supported: Apple TV app / DRM-protected video (Apple's DRM), "AirPlay video" hand-off
(YouTube plays through mirroring instead), 4K mirroring (this TV's HEVC path shows a green
picture, see `NOTES.md`).

## Install on a TV
1. On the TV: Settings → System → About → press **Android TV OS build** 7 times to enable
   developer options, then turn on **USB/Wireless debugging**.
2. From a computer on the same network:
   ```
   adb connect <TV-IP>:5555
   adb install -r SharePlus-release.apk
   ```
3. Open **SharePlus** from the TV's apps row. You can turn debugging off again afterwards.
   The app stays installed.

The iPhone and TV must be on the same Wi-Fi network (not a guest network).

## Building
Requirements: Android Studio (JDK, SDK 37, NDK 27.0.12077973, CMake 3.22.1).

```
./gradlew assembleDebug     # debug build, signed with the local debug key
./gradlew assembleRelease   # production build
```

Release signing reads a properties file **outside the repository**. Add this to
`local.properties` (gitignored):
```
shareplus.keystore=C:/path/to/shareplus-keystore.properties
```
with `storeFile`, `storePassword`, `keyAlias` and `keyPassword` inside. Without it,
`assembleRelease` produces an unsigned APK.

Only `armeabi-v7a` is built, matching the target TV (`abiFilters` in `app/build.gradle.kts`).

## Licence
SharePlus is free software, released under the **GNU General Public License v3** (see
`LICENSE`), because it is built on UxPlay.

### Credits
- [UxPlay](https://github.com/FDH2/UxPlay): the AirPlay server core (GPL-3.0)
- [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) and
  [manu-tech-code/airplay-receiver](https://github.com/manu-tech-code/airplay-receiver): Android
  port and JNI bridge this app is based on (GPL-3.0)
- libplist (LGPL-2.1), OpenSSL (Apache-2.0, prebuilt package), Oboe and AndroidX (Apache-2.0)

AirPlay, iPhone and Apple TV are trademarks of Apple Inc. SharePlus is not affiliated with Apple.

## Developer
**Lakshitha Geethmal**, lakshithageethmal@gmail.com, GitHub
[@lakshithaonline](https://github.com/lakshithaonline)
