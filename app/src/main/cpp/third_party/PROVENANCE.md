# Vendored third-party source

The native sources this project compiles live in this directory as ordinary tracked files.
There are no git submodules and the native build performs no network access (OpenSSL and
Oboe come as prebuilt Prefab AARs from Maven, see `../CMakeLists.txt`).

Copied into SharePlus on 2026-09-29 from `manu-tech-code/airplay-receiver` (branch `dev`),
which did the original vendoring and patching. SharePlus did not re-fetch from upstream.

| Directory  | Upstream                                         | Pinned revision                            | Upstream date | License   |
|------------|--------------------------------------------------|--------------------------------------------|---------------|-----------|
| `UxPlay`   | https://github.com/FDH2/UxPlay.git               | `462153392f2e30937424922039ff9f0cda5e7b1a` | 2026-08-22    | GPL-3.0   |
| `libplist` | https://github.com/libimobiledevice/libplist.git | `f41b1ea67045e0c09339974d83e389972d84f166` | 2026-03-30    | LGPL-2.1  |

## Local modifications

### `UxPlay`: patched and trimmed

Six patches are **already applied** to the source in this directory. They are kept in
`../patches/UxPlay/` purely as a record of how this tree diverges from upstream; nothing
applies them at build time. To rebase onto a newer UxPlay, re-apply them against a fresh
checkout of the pinned commit:

    git apply --unidiff-zero ../patches/UxPlay/*.patch

The patches cover: an HLS playlist crash, a use-after-free on playlist refresh, on-demand
refresh of live media playlists, video-sender compatibility, video reset on an abandoned play
connection, and volume delivery while the audio stream is paused.

Only `lib/` (the AirPlay protocol core), `LICENSE` and `README.md` are kept. The desktop
program (`uxplay.cpp`, GStreamer `renderers/`, man page, service files, Bluetooth LE beacon
scripts, top-level CMake) was removed because Android uses its own JNI bridge and MediaCodec.

### `libplist`: trimmed

Only `src/`, `include/`, `libcnary/` and the licence/author files are kept. The autotools
build, Cython bindings, docs, tools, tests and fuzz corpora were removed; `../CMakeLists.txt`
compiles the library sources directly.

### Removed on 2026-09-29

Earlier revisions also vendored OpenSSL 3.4.4 (+ `openssl-cmake`) and FFmpeg. Neither could be
built on a Windows host (NOTES.md 2.2), so OpenSSL now comes from Google's Prefab package
`com.android.ndk.thirdparty:openssl` and FFmpeg (only a software-ALAC fallback) was dropped.

## Licensing

UxPlay is GPL-3.0, so the resulting application is subject to GPL-3.0 terms. Each directory
retains its upstream license file; do not remove them.
