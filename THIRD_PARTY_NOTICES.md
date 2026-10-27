# Third-party notices and engine inventory

Nezzel uses youtubedl-android with bundled Python and FFmpeg for on-device processing.
This inventory records pinned input artifacts; it does not certify redistribution compliance.
Source inventory and offline release-asset tooling are in [SOURCES.md](SOURCES.md).
`app/src/main/assets/licenses/NOTICES-native.txt` is generated from every pinned, hash-verified source input
(no component missing) and is shown in the app under Open-source licences.

| Component | Pinned input / bundled version | License / source |
|---|---|---|
| youtubedl-android `library`, `common`, `ffmpeg` | `io.github.junkfood02.youtubedl-android:*:0.18.1` | GPL-3.0, [upstream](https://github.com/yausername/youtubedl-android); GPL text in `app/src/main/assets/licenses/youtubedl-android-GPL-3.0.txt` |
| yt-dlp | Bundled **2025.11.12**, commit `335653be82d5ef999cfc2879d005397402eebec1` | [Unlicense](https://github.com/yt-dlp/yt-dlp/blob/2025.11.12/LICENSE); bundled executable/dependency terms must also be retained |
| yt-dlp-ejs | **0.3.1**, bundled inside the yt-dlp zip in `youtubedl-android:library:0.18.1` | Unlicense, with MIT (astring) and ISC (meriyah) parts; [upstream](https://github.com/yt-dlp/ejs) |
| Python | **3.12.11**, Termux 3.12.11-1; bundled in the hashed library 0.18.1 runtime | [Python Software Foundation License Version 2 and historical licenses](https://docs.python.org/3.12/license.html), plus embedded third-party modules |
| FFmpeg | **7.1.1**, Termux 7.1.1-6, bundled in `ffmpeg:0.18.1`; observed `libavcodec.so.61.19.101` and `libavutil.so.59.39.100` | [GPL-3.0-or-later build](https://ffmpeg.org/legal.html); embedded `libavutil` reports that license and `--enable-gpl --enable-version3`; matching sources, Termux recipes and patches are selected by `scripts/source-bundle.sh`; see SOURCES.md |
| libwebp / SharpYUV | **1.6.0 / 0.4.2**, five libraries nested in the pinned FFmpeg payload | BSD-3-Clause, Copyright (c) 2010 Google Inc.; [tagged source](https://chromium.googlesource.com/webm/libwebp/+/refs/tags/v1.6.0/), full notice in `app/src/main/assets/licenses/libwebp-BSD-3-Clause.txt`; retain upstream AUTHORS and PATENTS with rebuilt artifacts |
| QuickJS | **2025-04-26**, Termux 1:20250426-1; `libqjs.so` and `usr/lib/quickjs/libquickjs.a` | [MIT](https://bellard.org/quickjs/), copyright Fabrice Bellard and Charlie Gordon; text in `app/src/main/assets/licenses/NOTICES-native.txt` |
| Native libraries (FFmpeg and Python) | 72 Termux packages, mutagen 1.47.0 and pycryptodomex 3.23.0; exact versions and source pins in [SOURCES.md](SOURCES.md) | Various open-source licences (SEE-NOTICES); generated `app/src/main/assets/licenses/NOTICES-native.txt`, currently incomplete; full inventory in `scripts/native-sources.tsv` |
| Jackson databind/core/annotations | **2.11.1**, matching wrapper runtime | Apache-2.0, [FasterXML](https://github.com/FasterXML/jackson-databind/tree/jackson-databind-2.11.1) |
| Apache Commons IO / Compress | **2.5 / 1.12**, wrapper transitive runtime | Apache-2.0, [Apache Commons](https://commons.apache.org/) |
| jsoup | **1.23.2**, `:engine` (parses already-fetched public post HTML only; no jsoup networking) | MIT, [jsoup](https://github.com/jhy/jsoup/blob/jsoup-1.23.2/LICENSE) |
| kotlinx.coroutines | **1.10.2** | Apache-2.0, [Kotlin](https://github.com/Kotlin/kotlinx.coroutines) |
| OkHttp `okhttp`, Android variant `okhttp-android` | **5.5.0** | Apache-2.0, [tagged source and license](https://github.com/lysine-dev/okhttp/tree/parent-5.5.0); full license in `app/src/main/assets/licenses/okhttp-APACHE-2.0.txt` |
| Okio `okio`, JVM variant `okio-jvm` | **3.18.2**, explicitly constrained above OkHttp's declared 3.18.1 | Apache-2.0, [tagged source and license](https://github.com/lysine-dev/okio/tree/parent-3.18.2); full license in `app/src/main/assets/licenses/okio-APACHE-2.0.txt` |
| AndroidX Startup runtime; annotation / annotation-jvm | **1.2.0; 1.10.0**, required by OkHttp Android | Apache-2.0, [AndroidX source](https://android.googlesource.com/platform/frameworks/support/); same Apache-2.0 text as shipped in `app/src/main/assets/licenses/okhttp-APACHE-2.0.txt` (no separate AndroidX asset) |
| AndroidX Media3 `media3-exoplayer`, `media3-session`, `media3-ui-compose` (with `media3-common`, `media3-container`, `media3-database`, `media3-datasource`, `media3-decoder`, `media3-extractor`); AndroidX Media, ExifInterface, Lifecycle Service, Concurrent Futures; Guava + failureaccess | **1.11.1**; 1.7.0, 1.3.6, 2.8.7, 1.2.0; 33.3.1-android + 1.0.2. Local playback of already-saved files only (owner 2026-10-26); no network data sources, Cast, IMA or native decoder extensions, no new native libraries | Apache-2.0, [AndroidX Media source](https://github.com/androidx/media/tree/1.11.1), [Guava](https://github.com/google/guava/tree/v33.3.1); same Apache-2.0 text as shipped in `app/src/main/assets/licenses/okhttp-APACHE-2.0.txt` |
| NewPipe Extractor | **v0.26.5**, tag commit `f9e6bb808f82bf3e4dc1f2a29a16fd376931c8ef`; JitPack `com.github.TeamNewPipe:NewPipeExtractor:v0.26.5`, `:engine` (YouTube options only) | GPL-3.0-or-later, [tagged source](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5); same GPL-3.0 text as `app/src/main/assets/licenses/youtubedl-android-GPL-3.0.txt` |
| nanojson (TeamNewPipe fork) | Commit **`e9d656ddb49a412a5a0a5d5ef20ca7ef09549996`**, JitPack, NewPipe Extractor transitive | Apache-2.0, [fork source](https://github.com/TeamNewPipe/nanojson/tree/e9d656ddb49a412a5a0a5d5ef20ca7ef09549996); text in `app/src/main/assets/licenses/nanojson-APACHE-2.0.txt` |
| Rhino `rhino`, `rhino-engine` | **1.8.1**, tag `Rhino1_8_1_Release` commit `f2d1ffef9dafbd625ca1ea79b8893ecbaee07e61`, explicitly constrained | MPL-2.0 (Exhibit A only; no "Incompatible With Secondary Licenses" notice), V8-derived parts BSD-3-Clause; `app/src/main/assets/licenses/rhino-MPL-2.0.txt` and `rhino-NOTICE.txt`; [source](https://github.com/mozilla/rhino/tree/Rhino1_8_1_Release) |
| Protocol Buffers `protobuf-javalite` | **4.35.1**, tag `v35.1` commit `35cd01f9fe9afbeea38cc7b979a3b6bfcde82c03`, explicitly constrained | BSD-3-Clause, Copyright 2008 Google Inc.; `app/src/main/assets/licenses/protobuf-BSD-3-Clause.txt`; [source](https://github.com/protocolbuffers/protobuf/tree/v35.1) |
| JSR 305 annotations | **3.0.2**, NewPipe Extractor transitive (annotations; R8 removed them from the release dex at U1, still listed because they are on the runtime classpath) | Apache-2.0 as declared by the artifact POM (the jar ships no LICENSE/NOTICE); `app/src/main/assets/licenses/jsr305-APACHE-2.0.txt`; [FindBugs JSR-305](https://github.com/findbugsproject/findbugs) |
| desugar_jdk_libs | **2.1.5**, `:app` core library desugaring (L8 dex in the APK); upstream commit `73170c345e6a762fc6a1f0301bb15218850023ef`: supported inference, not attested (2026-10-23): upstream has no 2.x tags and the POM names no commit; this commit prepares 2.1.5 (sets the version files) and is the last before the Maven release | GPL-2.0 WITH Classpath-exception-2.0; `app/src/main/assets/licenses/desugar_jdk_libs-GPL-2.0-CPE.txt`; [source](https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef) |
| desugar_jdk_libs_configuration | **2.1.5**, `:app` L8 configuration; its 29 conversion helper classes may be kept in the L8 dex | BSD-3-Clause, Copyright 2016 the R8 project authors (per the artifact POM and the LICENSE inside the jar); `app/src/main/assets/licenses/desugar_jdk_libs_configuration-BSD-3-Clause.txt`; [R8 source](https://r8.googlesource.com/r8) (the artifact names no commit; BSD-3 needs no source offer) |

## Artifact hashes and source plan

Maven Central origin: `https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/<module>/0.18.1/<module>-0.18.1.aar`.
NewPipe Extractor and nanojson resolve only from JitPack (`settings.gradle.kts` exclusive content for `com.github.TeamNewPipe`); every other input comes from Maven Central or Google Maven.
SHA-256 pins match the inspected artifacts and Gradle verification metadata:

| Input | SHA-256 |
|---|---|
| `library-0.18.1.aar` | `579b5fb480892b1abc2b218c2089699d52759cc8d7ba256bf876453f0365faef` |
| `ffmpeg-0.18.1.aar` | `0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49` |
| `common-0.18.1.aar` | `e0c0530bdbdf203e1598e900085d3582e27360731872872f3a67f23a9b24c0b5` |
| Bundled `res/raw/ytdlp` | `89a0d9058ea9018e380b7771898ff46e393a1986dcd13fef331693c87ce1fca4` |
| `library-0.18.1-sources.jar` (review input) | `26e94f33de0ba802f3507862d509f80d100c1c9065ded7556d16ff51249c4a9a` |
| `NewPipeExtractor-v0.26.5.jar` (JitPack; equals the 2026-10-23 spike download) | `923bf0a60938d570ad176ed7b08fa9fec7d0772d7a386ca89a2dd3b9212979c7` |
| `nanojson-e9d656ddb49a412a5a0a5d5ef20ca7ef09549996.jar` | `d495881a322b3c72e5d43284afd7d7021520028ca8b7c6ba861e3fd8d90614d5` |
| `rhino-1.8.1.jar` | `98a06d42bdd69da15bf9c2a7812b4cfe648ca9e64fe8baaeb502a32b0ed2c8ac` |
| `rhino-engine-1.8.1.jar` | `ff38ad60aa32a23942f60242a433a2583a5407841745510754a43e0e61dd95cf` |
| `protobuf-javalite-4.35.1.jar` | `45d3769189888e491ab7d58125f2014c2a86fb8104c7aa3878c723943fce7a14` |
| `jsr305-3.0.2.jar` | `766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7` |
| `desugar_jdk_libs-2.1.5.jar` | `d8044befae095781b9a80bf1faa92edc30382d75d437476784c1bf991598a976` |
| `desugar_jdk_libs_configuration-2.1.5.jar` | `7bc9051b3a1ec19806311dcb6aa9b9ba7ef9c22caa6f4810da55bde285fb7770` |

[SOURCES.md](SOURCES.md) records the 75-component source inventory, APK entry hashes,
source-asset contents and offline bundle instructions. Python 3.12.11, FFmpeg 7.1.1 and
QuickJS 2025-04-26 are pinned by versions and SOURCE_DATE_EPOCH timestamps to the
recorded Termux recipe commits. PR CI builds cite the merge commit as a recipe reference;
libc++/libandroid-stub build commits remain unknown. Binaries have not been independently
rebuilt; pycryptodomex's build command and the original builder image are unavailable.
The native source bundle and complete Termux snapshots are produced offline. Notices for
recipe-only packages (libandroid-glob, libandroid-posix-semaphore, libcrypt, libandroid-stub)
come from deterministic archives of their pinned Termux recipe directories; libc++ and
libandroid-stub also carry the LLVM project licence text (Apache-2.0 WITH LLVM-exception,
plus the legacy NCSA licence).
Publish matching source assets alongside each GitHub Release APK and run the APK hash
gate. This inventory does not establish redistribution compliance or runtime compatibility.

Bundled native terms include:

- FFmpeg GPL-3.0-or-later (`--enable-gpl --enable-version3`); x264, x265, xvidcore,
  vid.stab, rubberband and FFTW GPL-2.0-or-later.
- Python's readline 8.3.1 and gdbm 1.26 GPL-3.0-or-later; mutagen 1.47.0
  GPL-2.0-or-later; pycryptodomex 3.23.0 BSD-2-Clause/public-domain portions.
- LGPL libraries: libv4l (with separate GPL-2.0-only ov511/ov518 helper executables),
  GnuTLS, GMP, libunistring, libidn2, nettle, libiconv, FriBidi, LAME, Graphite,
  game-music-emu, libssh, libbluray, soxr, libudfread, mpg123 and GLib. FreeType is
  FTL OR GPL-2.0-or-later. Exact alternatives/versions are in SOURCES.md.
- MPL-2.0: libsrt, zeromq 4.3.5 and the Mozilla CA bundle dated 2025-09-09
  (`usr/etc/tls/cert.pem`, its own source form).
- Other native libraries: brotli, fontconfig, harfbuzz, libandroid-glob,
  libandroid-posix-semaphore, libandroid-stub, libandroid-support (including wcwidth),
  libaom, libass, bzip2, libc++, libcrypt, dav1d, expat, libffi, liblzma, libogg,
  opencore-amr, libopenmpt, opus, libpng, rav1e, libsamplerate, libsodium, sqlite,
  theora, libvmaf, vo-amrwbenc, vorbis, libvpx, libxml2, zimg, littlecms, ncurses,
  ocl-icd, OpenSSL, PCRE2, SVT-AV1 and zlib. Their individual licences, exceptions
  and patent notices are retained by the generated notice inventory as sources
  become available; a blanket MIT/Apache label does not describe these payloads.

The wrapper maintainer changed Python's tempfile.py default temporary directory to
`/storage/emulated/0/tmp` (original retained as tempfile.py.orig). See SOURCES.md
for this modification and the non-reproducible pycryptodomex build limits.

Jackson 2.11.1 is pinned to the wrapper's transitive version.
Nezzel uses only `ObjectMapper.readTree` on locally executed yt-dlp stdout, without default typing
or polymorphic binding. This limits the adapter's parsing surface; it is not a claim that this old
version has no vulnerabilities. A wrapper/Jackson upgrade and vulnerability review remain deferred;
the inventory does not imply a dependency security review.

Startup maintenance checks upstream STABLE yt-dlp seven days after the last success, at most once per day
automatically, and defers while downloads are queued/running or link inspection is pending. First-launch
automatic maintenance waits until the first yt-dlp inspection finishes or the next launch. Manual updates bypass cadence. The replacement executable is unpinned: the pinned wrapper's
upstream updater/transport has no independent Nezzel release-hash pin. This is a current implementation limitation. Settings shows
the actual installed version, including after failed replacement; old selections expire on version
change. The bundled artifact hashes above do not attest to a downloaded replacement. Matching runtime
version/hash/license/source provenance is still required before distribution.

## Packaging boundary

`jniLibs.useLegacyPackaging=true` preserves extracted executable payloads.
The existing app ABI filter remains **arm64-v8a for both build types**; other ABIs are not claimed.
The arm64 app jniLibs overlay contains the five rebuilt WebP libraries; all 112 ELF
objects in that payload pass NDK readelf alignment checks. Exact-path `pickFirsts`
selects the app overlay ahead of the AAR copy; a fresh APK entry hash must still
verify packaging. Current device evidence is for 4 KB pages; 16 KB runtime and
Play Store readiness are not claimed.

### Owner-approved WebP rebuild (issue #3, 2026-10-20; vendored candidate)

Only **arm64-v8a** ships (`app/build.gradle.kts` ABI filter; no x86_64 split).
The original Termux WebP was **1.6.0-rc1** (recipe commit
`b279d510b0ee2d35ba76c8928e736532edb340a9`); Nezzel ships **1.6.0 final**.
The pinned original payload's WebP decoder/encoder/demux/mux version functions return
`0x010600` (**1.6.0**); SharpYUV returns `0x040002` (**0.4.2**).
All five SONAMEs equal their unversioned filenames below. `strings` alone does
not identify the WebP release; AArch64 version-function instructions establish it.

Source: [official WebM libwebp-1.6.0.tar.gz](https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz),
[detached signature](https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz.asc).
Verified source SHA-256: `e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564`,
published by the [Homebrew formula](https://github.com/Homebrew/homebrew-core/blob/master/Formula/w/webp.rb).
On 2026-10-20 the maintainer downloaded the official tarball, matched this hash and
verified `gpgv`'s good signature from the WebP release signing key
`6B0E6B70976DE303EDF2F601F9C3D6BDB8232B5D`. The recipe requires a trusted
release keyring (`WEBP_GPG_KEYRING`); it never imports an arbitrary key.

`scripts/build-webp-16kb.sh <ffmpeg-0.18.1.aar>` pins NDK **r27d (27.3.13750724)**,
Android API 29, WebP 1.6.0 and the original AAR hash. It uses configure/make
(two workers), replaces the five libtool SONAME version rules with `-avoid-version`,
with linker flags `-Wl,--as-needed -Wl,-z,max-page-size=16384
-Wl,-z,common-page-size=16384 -Wl,--build-id=sha1`. It preserves SONAMEs and
exported names/types/bindings/visibility/data sizes. All original DT_NEEDED
entries must remain; additions are restricted to Android system libraries
`libc.so`, `libm.so`, `libdl.so`. In this build only libwebpdecoder/libwebpmux add
`libm.so`. All other ZIP entries retain their uncompressed bytes and
metadata; recompression can change compressed bytes. Python/zlib versions are printed.
It writes a candidate plus six hashes under ignored `build/webp-16kb/`, retaining
COPYING, AUTHORS and PATENTS. Preserve the source tarball, signature, trusted-key
provenance, NDK version and this recipe alongside the distribution source plan.

The maintainer built the candidate. It is vendored at
`app/src/main/jniLibs/arm64-v8a/libffmpeg.zip.so` (35,507,163 bytes).
`app/build.gradle.kts` keeps legacy packaging and uses an exact arm64 pick-first
rule. No other ABI is overridden. Generated `build/` files stay ignored; only
the payload and notices are vendored. Upstream COPYING is bundled as
`libwebp-BSD-3-Clause.txt`; AUTHORS and PATENTS are bundled as
`libwebp-AUTHORS.txt` and `libwebp-PATENTS.txt` in `app/src/main/assets/licenses/`.

| Patched arm64 artifact | SHA-256 |
|---|---|
| `libffmpeg.zip.so` | `ff8ad29f7512af84b195e21afa94d703416c3188f131aecc8a3df1e04f7d6221` |
| `usr/lib/libsharpyuv.so` | `70f6c54a0ff736fab94e5c860c4c707243c2bf31b3716127530147ea9c1a407e` |
| `usr/lib/libwebp.so` | `08cad6e10c5c60be6e33fb85caa23d0f85833986d8b536f5becd794bfd803c30` |
| `usr/lib/libwebpdecoder.so` | `d2d2ae6e612daf7be398cd51c080a4559bc97019a5aa5e46107ff3833723c667` |
| `usr/lib/libwebpdemux.so` | `adb5afbdb238afd8867140cf54875ed808e5cb33b7515e029ca222bf64a655c8` |
| `usr/lib/libwebpmux.so` | `196b0383501eeca8ed4fce856a7e31b9a0d4ded64d0b910018999f59d6f5ef70` |

Verified **original, unpatched** arm64 inputs retained for provenance:

| Original payload input | SHA-256 |
|---|---|
| `libffmpeg.zip.so` | `4664a895283f9f31786be39b0c484c1f96631f02c7c7f9c70a4a4a2da324a092` |
| `usr/lib/libsharpyuv.so` | `685901b3ad5896ceb176f35d831a41de14a43164c06b206da9925cfa6e1e099b` |
| `usr/lib/libwebp.so` | `ed4a82653eac41767ea809fa9e85df7dfb97ad3801f4323ffceb65697def7d6e` |
| `usr/lib/libwebpdecoder.so` | `ba39154a99271485c26d8e16954b866dae0873caaef92b132d412bf1f2fe52b4` |
| `usr/lib/libwebpdemux.so` | `7be0df22a61922d7f63d2b2563c0916fb058d4f2d9939586a2c71292b6d7bca2` |
| `usr/lib/libwebpmux.so` | `e6cca7833dd88679c5b09c1468c61cb75834bc24138e50d064f5f00375ca520c` |

Validation: actual NDK readelf verifies all 112 candidate ELF objects; exactly
five entry contents changed, and a repeated repack produces the same candidate
hash. The 140 Python ELF objects were already aligned, so Python needs no
analogous rebuild. The 0.4.3 release APK carries the vendored entry hash above and
`scripts/check-16kb.sh` reports 257 ELF objects, 0 unaligned. FFmpeg merging was
verified on a 4 KB-page phone; no 16 KB-page device has been tested yet.

Upgrade extraction is unchanged: `YtdlpRuntime.needsExtraction` compares the
stored `ffmpegLibVersion` with the installed ZIP length. Pinned 0.18.1
`FFmpeg.initFFmpeg` bytecode confirms that a mismatch deletes/recreates the
package, unzips and records the new length only after success. The change from
35,624,931 to 35,507,163 bytes therefore triggers re-extraction on upgrade.

## Bundled font and color utility

- **Noto Sans Arabic** variable font (wdth/wght), Copyright 2022 The Noto Project Authors. SIL Open Font License 1.1; modified 2026-10-19: only the vertical line metrics (hhea/OS/2 ascent 1100, descent 500, line gap 0) were reduced so Arabic lines honour the app's line heights; glyphs and names unchanged. Source: `google/fonts`, commit `8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5`, `ofl/notosansarabic/NotoSansArabic[wdth,wght].ttf`. Upstream SHA-256: `63111b5b2e074dd48cc67692e0a2726d86ee94c1c37fe8598257b7b4e87e869e`; bundled (modified) SHA-256: `bfcdf2f8a3b94f9ef7df16bb837d0bf84517781c60d7c632dd26f12f4c30d28b`. Bundled at `app/src/main/res/font/noto_sans_arabic.ttf`; full OFL at `app/src/main/assets/licenses/noto-sans-arabic-OFL.txt`. No downloadable-font service.
- **Material Color Utilities**, Copyright 2021 Google LLC, Apache License 2.0. `app/.../ui/theme/HctHue.kt` is a hue-only Kotlin adaptation of the standard-viewing CAM16 conversion in upstream `java/hct/Cam16.java`, `ViewingConditions.java` and `java/utils/ColorUtils.java` (https://github.com/material-foundation/material-color-utilities). It computes HCT hue for the wallpaper guard, without bundling a new library. Full license: `app/src/main/assets/licenses/material-color-utilities-APACHE-2.0.txt`.
- **Simple Icons** 16.34.0 (npm `simple-icons`, tarball SHA-256 `25887ddf96a084a82f9181dc3a7e99750d6b5ac4339fe9321d0e2bf14669dc65`), CC0 1.0: the vector paths of the YouTube, Facebook, Instagram, WhatsApp, TikTok, X, Telegram and Snapchat marks, converted unchanged to `app/src/main/res/drawable/logo_*.xml` (arc flags only re-spaced for Android's path parser). The marks remain trademarks of their owners; Nezzel shows them only as a decorative wall on the Update screen, hidden from TalkBack, with no claim of partnership or endorsement.
- **Material Symbols** (Rounded, weight 400) from npm `@material-symbols/svg-400` 0.47.5 (integrity `sha512-5AHuernGbTq1uq0YDb16QabESAaiYdqgDsDqF3Nk5usqP8pEeE5Nrt11jSs4/A/D7kOz5Ci6xyXMS/DsaxY/rw==`), Copyright Google LLC, Apache License 2.0: 15 icon paths converted unchanged to `app/src/main/res/drawable/ic_ms_*.xml` (a group shift moves the 0,-960 viewBox to 0,0) for the Settings rows and the saved-file actions sheet. Licence text: `app/src/main/assets/licenses/material-symbols-APACHE-2.0.txt`.
- AppCompat 1.7.1 and lifecycle-viewmodel-compose 2.8.7: AndroidX, Apache License 2.0. AndroidX ProfileInstaller 1.4.1 (installs the bundled baseline profile on sideloaded installs): AndroidX, Apache License 2.0. Benchmark Macro 1.5.0, UiAutomator and the androidx.baselineprofile Gradle plugin are build/test-only (the separate `:baselineprofile` test APK), never bundled in the app APK. Robolectric 4.14.1 and its Android SDK fixtures are test-only, pinned and resolved through Gradle dependency verification/locking; not bundled in the APK.
