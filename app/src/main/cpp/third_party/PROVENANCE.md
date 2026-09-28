# Vendored third-party source

Everything this project builds against lives in this directory as ordinary
tracked files. There are no git submodules and the native build performs no
network access. This file records where each tree came from so it can be
audited, diffed against upstream, or re-synced later.

Vendored on 2026-09-22 (original dates below); copied as-is into SharePlus on
2026-09-30 from `manu-tech-code/airplay-receiver` (branch `dev`), which did the
original vendoring/trimming/patching described below. SharePlus did not
re-fetch from the upstream URLs — this table is preserved from that repo for
audit purposes. See `../../../../../NOTES.md` (Phase 1.2) for how SharePlus's
own JNI bridge, written separately, differs from that reference.

| Directory       | Upstream                                      | Pinned revision / version                  | Upstream date | License        |
|-----------------|-----------------------------------------------|--------------------------------------------|---------------|----------------|
| `UxPlay`        | https://github.com/FDH2/UxPlay.git            | `462153392f2e30937424922039ff9f0cda5e7b1a` | 2026-08-22    | GPL-3.0        |
| `libplist`      | https://github.com/libimobiledevice/libplist.git | `f41b1ea67045e0c09339974d83e389972d84f166` | 2026-03-30    | LGPL-2.1       |
| `openssl-cmake` | https://github.com/viaduck/openssl-cmake.git  | `4edd36a8dab5f85a8f92650b5bdf6e0cab13aab8` (branch `v3`) | 2026-03-10 | MPL-2.0    |
| `ffmpeg`        | https://github.com/FFmpeg/FFmpeg.git          | `38b88335f99e76ed89ff3c93f877fdefce736c13` | 2026-06-17    | LGPL-2.1+      |
| `openssl`       | https://mirror.viaduck.org/openssl/openssl-3.4.4.tar.gz | 3.4.4                            | 2026-02-02    | Apache-2.0     |

The OpenSSL tarball had SHA-256
`7bdf55ac20f2779e99e5eca306f824fad2b37dee5a06cc35ed5a8b85a6060010`,
matching what `openssl-cmake` would previously have downloaded and verified.

## Local modifications

### `UxPlay` — patched

Six patches are **already applied** to the source in this directory. They are
kept as files in `../patches/UxPlay/` purely as a record of how this tree
diverges from upstream; nothing applies them at build time any more. To see the
divergence, or to rebase onto a newer UxPlay, re-apply them against a fresh
checkout of the pinned commit above:

    git apply --unidiff-zero ../patches/UxPlay/*.patch

The patches cover: an HLS playlist crash, a use-after-free on playlist refresh,
on-demand refresh of live media playlists, video-sender compatibility, video
reset on an abandoned play connection, and volume delivery while the audio
stream is paused.

### `openssl-cmake` — patched

`cmake/BuildOpenSSL.cmake` originally fetched an OpenSSL tarball over the
network. Its `ExternalProject_Add(openssl ...)` now copies from the vendored
`../openssl` tree instead, via `OPENSSL_VENDORED_SOURCE_DIR` (set in
`../CMakeLists.txt`). The copy is deliberate: OpenSSL configures and builds
in-source, so building directly in the vendored tree would scatter generated
headers and object files through version control.

### `ffmpeg` — trimmed

Only `tests/ref/` (23M of FATE reference fixtures) was removed. Nothing in the
build reads it; it is consumed solely by `make fate`, which this project never runs.

`tests/` and `doc/` themselves are **kept**, and deliberately so. FFmpeg's
top-level `Makefile` includes `doc/Makefile`, `doc/examples/Makefile` and
`tests/Makefile` unconditionally (lines 135, 136, 208), so deleting those
directories breaks `make` with "No rule to make target". Note that `configure`
still succeeds without them -- the failure only appears at build time, so
verify any further trimming with an actual compile, not just a configure.

The build itself uses `--disable-all --enable-avcodec --enable-decoder=alac`.

### `openssl` — trimmed

`test/` (29M) and `demos/` were removed, taking the tree from 75M to 45M.
`openssl-cmake` forces `COMMAND_TEST` to a no-op under `CROSS_ANDROID`, so
`make test` never runs.

`fuzz/` and `doc/` were **kept** even though they are not built: OpenSSL's root
`build.info` lists them unconditionally in `SUBDIRS`, and `Configure` aborts
without them. Removing `test/` additionally requires the `no-tests` flag, which
`../CMakeLists.txt` passes through `OPENSSL_PARAMS`.

## Licensing

UxPlay is GPL-3.0, so the resulting application is subject to GPL-3.0 terms.
FFmpeg is configured without any GPL-only components, leaving it under LGPL-2.1+.
Each directory retains its upstream license file; do not remove them.
