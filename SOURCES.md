# Release sources and native component provenance

Nezzel source: https://github.com/ahmed-abdat/nezzel. Each GitHub Release APK is
accompanied in that same release by the following assets. Obtainium installs the APK;
the source assets remain available alongside it.

| Asset | Contents |
|---|---|
| `nezzel-<version>-source.tar.gz` | Git archive of this repository's `v<version>` tag, including Nezzel's build scripts and changes. |
| `nezzel-<version>-native-sources.tar` | The copyleft source inputs below, including the CA bundle (its own source), readline's `readline83-001` patch, plus Python, QuickJS, pycryptodomex, xz, and libwebp 1.6.0 with its detached signature. |
| `termux-packages-snapshots.tar.xz` | Complete Git archives of every distinct Termux commit below: recipes, patches, build-package.sh, scripts and NDK patches, each under `termux-packages/<commit>/`. |
| `youtubedl-android-0.18.1-source.tar.gz` | Wrapper commit `d725d5c9a18c3a99a13ee0308bf78275dc310760`; excludes every `jniLibs` directory and `library/src/main/res/raw/ytdlp`. |
| `newpipe-extractor-0.26.5-source.tar.gz` | NewPipeExtractor tag `v0.26.5`, commit `f9e6bb808f82bf3e4dc1f2a29a16fd376931c8ef` (GPL-3.0-or-later). |
| `rhino-1.8.1-source.tar.gz` | Rhino tag `Rhino1_8_1_Release`, commit `f2d1ffef9dafbd625ca1ea79b8893ecbaee07e61` (MPL-2.0; V8-derived parts BSD-3-Clause). |
| `protobuf-javalite-4.35.1-source.tar.gz` | Protocol Buffers tag `v35.1`, commit `35cd01f9fe9afbeea38cc7b979a3b6bfcde82c03` (BSD-3-Clause). |
| `nanojson-e9d656dd-source.tar.gz` | TeamNewPipe nanojson fork, commit `e9d656ddb49a412a5a0a5d5ef20ca7ef09549996` (Apache-2.0). |
| `desugar_jdk_libs-2.1.5-source.tar.gz` | google/desugar_jdk_libs commit `73170c345e6a762fc6a1f0301bb15218850023ef` (GPL-2.0 with Classpath Exception). Upstream has no 2.x tags; this commit sets `VERSION_JDK11.txt` to 2.1.5 and is the last before the 2.1.5 Maven release (supported inference, not attested upstream; 2026-10-23). Covers desugar_jdk_libs only: desugar_jdk_libs_configuration is BSD-3-Clause R8 code. |
| `SOURCES.md` | This inventory and expected APK payload hashes. |
| `SHA256SUMS` | SHA-256 of each of the other source assets above. |

These are inventory and provenance records, not a legal-compliance certification.
The checked-in `NOTICES-native.txt` is generated from every pinned input in
`scripts/native-sources.tsv` (no component missing). A generation made with
`--allow-missing` is not release evidence.

## Identification and modifications

The wrapper's 0.18.1 tag contains prebuilt Termux payloads. Packages were identified
by version strings and ZIP entry mtimes corresponding to `SOURCE_DATE_EPOCH`.
Of 72 Termux package pins, 16 have exact commit-time matches, 54 fall within a
squash-merged PR's CI build window, and two have unknown build commits. For a
`pr-merge` row: **Built by Termux CI from the PR merged as commit X; recipe as in X.**
The merge commit is a recipe reference, not a claim that the binary was built from
that commit byte-for-byte. These binaries have not been independently rebuilt.

Upstream documents describe Android NDK r28c, API 24 and clang 19.0.1 in the
termux-packages environment, with the prefix `/data/data/com.termux/files/usr`.
Only arm64-v8a is covered here. Recipe files in the snapshots include the Termux
patches and build configuration. Recipe licence fields sometimes describe an entire
source package rather than the shipped library; the table uses the research's
corrections (for example liblzma 0BSD and zeromq MPL-2.0).

Nezzel replaces exactly five members of `libffmpeg.zip.so`:
`usr/lib/libsharpyuv.so`, `usr/lib/libwebp.so`, `usr/lib/libwebpdecoder.so`,
`usr/lib/libwebpdemux.so`, and `usr/lib/libwebpmux.so`. Original WebP was
**1.6.0-rc1**, from Termux recipe commit
`b279d510b0ee2d35ba76c8928e736532edb340a9`, original source archive SHA-256
`a8822fbd36e43fa1e5a83a7104d86c5be8692cee1e323d57030b5562ef884a8a`.
The table's libwebp row records the **shipped 1.6.0 final** source; its Termux
commit/match refer only to that original base recipe, and its Termux revision is
unknown because the replacement is a Nezzel build. `scripts/build-webp-16kb.sh`
uses NDK r27d (27.3.13750724), API 29, unversioned SONAMEs and 16 KB linker page
alignment. The script and detailed flags/provenance are in the Nezzel source and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Preserve COPYING, AUTHORS and
PATENTS and the signature/key provenance with this source. Other payload member
contents are unchanged by Nezzel; ZIP recompression changes the outer hash.

The wrapper maintainer edited Python's `usr/lib/python3.12/tempfile.py` to use
`/storage/emulated/0/tmp` as its default temporary directory. The payload retains
`tempfile.py.orig`; the Python tarball is upstream source, and this paragraph
records the wrapper modification. Mutagen 1.47.0 matches its PyPI sdist except
for omitted `py.typed`. Pycryptodomex 3.23.0 includes 40 `.abi3.so` modules built
on-device with Termux clang 20.1.8; the exact build command was not recorded.
Bundled yt-dlp is 2026.10.26 (Unlicense), tag commit
`f264a6cbd1f630894da0f368df00f8aae50140e0` (its `RELEASE_GIT_HEAD` reads
`c91f8de0bfb3577894abad716eaaa01659e6bd14`). It is the upstream release asset
`yt-dlp`, unchanged, in `engine/src/main/res/raw/ytdlp`, which replaces the
wrapper's 2025.11.12 copy; the engine build fails unless its hash, recorded below,
matches. It includes yt-dlp-ejs 0.8.0.

## Component source inputs

`scripts/native-sources.tsv` is the machine-readable source. Semicolon-separated
files, URLs and hashes pair by position (readline includes its patch;
libandroid-support includes wcwidth). `ff;py` means both payloads. `LicenseRef-*`
labels retain research descriptions where an exact SPDX identifier is not pinned;
the source notice texts govern. `copyleft=yes` conservatively includes alternative
copyleft licences such as FreeType. The wrapper has its own source asset above.

`unknown` means unknown, not a zero checksum or permission to skip verification.
The four source-in-recipe packages (libandroid-glob, libandroid-posix-semaphore,
libandroid-stub and libcrypt) have no upstream tarball pin: their source is in
the referenced Termux snapshot. For complete notice generation, prepare component
source archives from those snapshot directories, record the actual filenames and
SHA-256 in the TSV, and place them with the other inputs. Likewise pin the locally
archived wrapper source before using it as a notice input. No script fetches data.
For libc++ and libandroid-stub the recorded commit is where the NDK recipe was
read, **not** a determined build commit (`match=unknown`). NDK archive hashes
identify the recipe input, not proof that every shipped binary was rebuilt from it.
Permissive-source hashes not downloaded during the initial research are recipe
pins only. The notice generator verifies the actual files supplied later.

| Component | Version / Termux revision | Licence | Termux recipe commit | Match | Source file | SHA-256 |
|---|---|---|---|---|---|---|
| brotli | 1.1.0 / 3 | MIT | `39c207877312881a48330f60a50926cd4dc6eebb` | pr-merge | brotli-v1.1.0.tar.gz | `e720a6ca29428b803f4ad165371771f5398faba397edf6778837a18599ea13ff` |
| ca-certificates | 1:2025.09.09 / 0 | MPL-2.0 | `2b652dc77b9eaae411092512c6dfd53e55366261` | exact | cacert-2025-09-09.pem | `f290e6acaf904a4121424ca3ebdd70652780707e28e8af999221786b86bb1975` |
| ffmpeg | 7.1.1 / 6 | GPL-3.0-or-later | `de5f604e60ba4a9d0d989c8d56e0c307fe6e3a0c` | exact | ffmpeg-7.1.1.tar.xz | `733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1` |
| fftw | 3.3.10 / 4 | GPL-2.0-or-later | `89f0f483fdec354b4f931f8054fe73d6534aed9f` | pr-merge | fftw-3.3.10.tar.gz | `56c932549852cddcfafdab3820b0200c7742675be92179e59e6215b340e26467` |
| fontconfig | 2.17.1 / 1 | LicenseRef-HPND-style | `172373a9a0ea7c3eb638d5aa59b2de04155920b3` | pr-merge | fontconfig-2.17.1.tar.gz | `82e73b26adad651b236e5f5d4b3074daf8ff0910188808496326bd3449e5261d` |
| freetype | 2.14.1 / 0 | FTL OR GPL-2.0-or-later | `5ae132396a0a0f1245e1acc4159d2808f789771f` | exact | freetype-2.14.1.tar.xz | `32427e8c471ac095853212a37aef816c60b42052d4d9e48230bab3bdf2936ccc` |
| fribidi | 1.0.16 / 1 | LGPL-2.1-or-later | `172373a9a0ea7c3eb638d5aa59b2de04155920b3` | pr-merge | fribidi-1.0.16.tar.xz | `1b1cde5b235d40479e91be2f0e88a309e3214c8ab470ec8a2744d82a5a9ea05c` |
| game-music-emu | 0.6.4 / 1 | LGPL-2.1-or-later | `7ad48c69fb48bc5cca3af2e8599fdcfba402cf42` | pr-merge | libgme-0.6.4-src.tar.gz | `6f94eac735d86bca998a7ce1170d007995191ef6d4388345a0dc5ffa1de0bafa` |
| gdbm | 1.26 / 1 | GPL-3.0-or-later | `ee7f26236ab58b99836da32ab0a6ff06e4fd9eab` | pr-merge | gdbm-1.26.tar.gz | `6a24504a14de4a744103dcb936be976df6fbe88ccff26065e54c1c47946f4a5e` |
| glib | 2.86.0 / 0 | LGPL-2.1-or-later | `beeaa5a6f73d66891198bbd8ac22a5105d42461f` | pr-merge | glib-2.86.0.tar.xz | `b5739972d737cfb0d6fd1e7f163dfe650e2e03740bb3b8d408e4d1faea580d6d` |
| harfbuzz | 11.5.1 / 0 | LicenseRef-Old-MIT | `ea357eb04b933c5f555723f14309b520e4dd8537` | exact | harfbuzz-11.5.1.tar.gz | `624ddaa8211a57b538360555f7358dac9fa7a9e6000b65de06a91dbb392882ca` |
| libandroid-glob | 0.6 / 3 | BSD-3-Clause | `ee7f26236ab58b99836da32ab0a6ff06e4fd9eab` | pr-merge | unknown | `unknown` |
| libandroid-posix-semaphore | 0.1 / 4 | MIT | `951b902e406cd9b556608ae6fce33de479785500` | pr-merge | unknown | `unknown` |
| libandroid-stub | 28c / 0 | NCSA | `de5f604e60ba4a9d0d989c8d56e0c307fe6e3a0c` | unknown | unknown | `unknown` |
| libandroid-support | 29 / 1 | Apache-2.0 AND MIT | `951b902e406cd9b556608ae6fce33de479785500` | pr-merge | libandroid-support-29.tar.gz; wcwidth-4.tar.gz | `8f74ce0f9cf70ec29f548696c248cac0ba560a2d8916a0fe1cf9316d5f167a80`; `08489e00f797ffb3b71f9c1894c83e5ebe12c90a2ee0e1f9dc7c6eb29a2ff1a8` |
| libaom | 3.13.1 / 0 | BSD-2-Clause AND LicenseRef-AOM-Patent | `ee424a59ea4a051d897d0e6349f979b8474142e7` | exact | libaom-3.13.1.tar.gz | `19e45a5a7192d690565229983dad900e76b513a02306c12053fb9a262cbeca7d` |
| libass | 0.17.4 / 1 | ISC | `e1ff8ce05fa15543d14949aebb0ad4257f66ade9` | pr-merge | libass-0.17.4.tar.xz | `78f1179b838d025e9c26e8fef33f8092f65611444ffa1bfc0cfac6a33511a05a` |
| libbluray | 1.3.4 / 3 | LGPL-2.1-or-later | `4e92f82889d9fd55c4113220739c58015f78eaf8` | pr-merge | libbluray-1.3.4.tar.bz2 | `478ffd68a0f5dde8ef6ca989b7f035b5a0a22c599142e5cd3ff7b03bbebe5f2b` |
| libbz2 | 1.0.8 / 8 | bzip2-1.0.6 | `3c4d453c36d78d0d131c69686ef72a5cde760aed` | pr-merge | bzip2-1.0.8.tar.xz | `47fd74b2ff83effad0ddf62074e6fad1f6b4a77a96e121ab421c20a216371a1f` |
| libc++ | 28c / 0 | Apache-2.0 WITH LLVM-exception | `de5f604e60ba4a9d0d989c8d56e0c307fe6e3a0c` | unknown | android-ndk-r28c-linux.zip | `59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc` |
| libcrypt | 0.2 / 6 | BSD-2-Clause | `45cea5eecfa99e50b05af56ace33ed708348ad9b` | pr-merge | unknown | `unknown` |
| libdav1d | 1.5.1 / 1 | BSD-2-Clause | `629ff376a3917a4c73ca73fd0f041a00036eeecb` | pr-merge | dav1d-1.5.1.tar.bz2 | `4eddffd108f098e307b93c9da57b6125224dc5877b1b3d157b31be6ae8f1f093` |
| libexpat | 2.7.3 / 0 | MIT | `c855a09b4cd7efea1fd80a34b3f428ea18645205` | exact | expat-2.7.3.tar.bz2 | `59c31441fec9a66205307749eccfee551055f2d792f329f18d97099e919a3b2f` |
| libffi | 3.4.7 / 1 | MIT | `951b902e406cd9b556608ae6fce33de479785500` | pr-merge | libffi-3.4.7.tar.gz | `138607dee268bdecf374adf9144c00e839e38541f75f24a1fcf18b78fda48b2d` |
| libgmp | 6.3.0 / 2 | LGPL-3.0-or-later OR GPL-2.0-or-later | `45cea5eecfa99e50b05af56ace33ed708348ad9b` | pr-merge | gmp-6.3.0.tar.xz | `a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898` |
| libgnutls | 3.8.10 / 0 | LGPL-2.1-or-later | `8b115f41bf740fb6447f17461d0fe63e49657fa6` | pr-merge | gnutls-3.8.10.tar.xz | `db7fab7cce791e7727ebbef2334301c821d79a550ec55c9ef096b610b03eb6b7` |
| libgraphite | 1.3.14 / 3 | LGPL-2.1-or-later OR MPL-2.0 OR GPL-2.0-or-later | `8219f9af13fe9c34d161fad802b07a3073fd84e6` | pr-merge | graphite-1.3.14.tar.gz | `7a3b342c5681921ce2e0c2496509d30b5b078399d5a7bd2358f95166d57d91df` |
| libiconv | 1.18 / 1 | LGPL-2.1-or-later | `055cf406a9bdad3a6560f29f2d7492190a753c95` | pr-merge | libiconv-1.18.tar.gz | `3b08f5f4f9b4eb82f151a7040bfd6fe6c6fb922efe4b1659c66ea933276965e8` |
| libidn2 | 2.3.8 / 1 | LGPL-3.0-or-later OR GPL-2.0-or-later | `eec56cc0cba4d05c45c957ccb5ff6e43d0ee316f` | pr-merge | libidn2-2.3.8.tar.gz | `f557911bf6171621e1f72ff35f5b1825bb35b52ed45325dcdee931e5d3c0787a` |
| liblzma | 5.8.1 / 1 | 0BSD | `79ab86d132c63379155ebeeff017226515c8ebd7` | pr-merge | xz-5.8.1.tar.xz | `0b54f79df85912504de0b14aec7971e3f964491af1812d83447005807513cd9e` |
| libmp3lame | 3.100 / 7 | LGPL-2.0-or-later | `629ff376a3917a4c73ca73fd0f041a00036eeecb` | pr-merge | lame-3.100.tar.gz | `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e` |
| libmpg123 | 1.33.2 / 0 | LGPL-2.1-only | `35e00fa17008fe2304846d221b792c528269f215` | exact | mpg123-1.33.2.tar.bz2 | `2c54fabbfa696dce8f9b137c8ef7a429a061f8fe633cd7d0a511809855f2c219` |
| libnettle | 3.10.2 / 1 | LGPL-3.0-or-later OR GPL-2.0-or-later | `302d028ce652b1b3e6d04451f669c5903c747798` | pr-merge | nettle-3.10.2.tar.gz | `fe9ff51cb1f2abb5e65a6b8c10a92da0ab5ab6eaf26e7fc2b675c45f1fb519b5` |
| libogg | 1.3.6 / 1 | BSD-3-Clause | `bdf29393ae7fdca670f2d81d0071fa5bff560563` | pr-merge | libogg-1.3.6.tar.xz | `5c8253428e181840cd20d41f3ca16557a9cc04bad4a3d04cce84808677fa1061` |
| libopencore-amr | 0.1.6 / 1 | Apache-2.0 | `6399cc97eba01910dcf10d869b9654816e8f60f0` | pr-merge | opencore-amr-0.1.6.tar.gz | `483eb4061088e2b34b358e47540b5d495a96cd468e361050fae615b1809dc4a1` |
| libopenmpt | 0.8.3 / 0 | BSD-3-Clause | `7a18dbd4d464c0c05f21ffe9068d18eecdf223db` | exact | libopenmpt-0.8.3+release.autotools.tar.gz | `25d486a4da9728819274ed0959fd79a1c6358954710d54c14047c6457c8ca8ac` |
| libopus | 1.5.2 / 1 | BSD-3-Clause | `39c207877312881a48330f60a50926cd4dc6eebb` | pr-merge | opus-1.5.2.tar.gz | `65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1` |
| libpng | 1.6.50 / 1 | libpng-2.0 | `3d8967a7a9b07d53f08aef1c1028c2d527cab7bf` | pr-merge | libpng-1.6.50.tar.xz | `4df396518620a7aa3651443e87d1b2862e4e88cad135a8b93423e01706232307` |
| librav1e | 0.8.1 / 0 | BSD-2-Clause AND LicenseRef-AOM-Patent | `7dad7e0de9b402a0de840be519f68f462b34e52c` | exact | librav1e-v0.8.1.tar.gz | `06d1523955fb6ed9cf9992eace772121067cca7e8926988a1ee16492febbe01e` |
| libsamplerate | 0.2.2 / 3 | BSD-2-Clause | `0d17211475283c3ccda4bab5967ab2e96ef62490` | pr-merge | libsamplerate-0.2.2.tar.gz | `16e881487f184250deb4fcb60432d7556ab12cb58caea71ef23960aec6c0405a` |
| libsodium | 1.0.20 / 1 | ISC | `0e500765db2e9be8eb24a1546693a1e922d9a743` | pr-merge | 1.0.20-RELEASE.tar.gz | `8e5aeca07a723a27bbecc3beef14b0068d37e7fc0e97f51b3f1c82d2a58005c1` |
| libsoxr | 0.1.3 / 8 | LGPL-2.1 | `4d38f1f21c760538f51dd99ee4199cba80c1d713` | pr-merge | soxr-0.1.3-Source.tar.xz | `b111c15fdc8c029989330ff559184198c161100a59312f5dc19ddeb9b5a15889` |
| libsqlite | 3.50.4 / 1 | LicenseRef-Public-Domain | `210cf16e36268a18e169d99ff463932c7331aac5` | pr-merge | sqlite-src-3500400.zip | `b7b4dc060f36053902fb65b344bbbed592e64b2291a26ac06fe77eec097850e9` |
| libsrt | 1.5.4 / 1 | MPL-2.0 | `e5ced5ae68a36a8a0bfc960cad752997cbe5ad00` | pr-merge | srt-1.5.4.tar.gz | `d0a8b600fe1b4eaaf6277530e3cfc8f15b8ce4035f16af4a5eb5d4b123640cdd` |
| libssh | 0.11.3 / 0 | LGPL-2.1-only | `d59a0d9c7bb5c4558ad9fcc27917354afcd06116` | exact | libssh-0.11.3.tar.xz | `7d8a1361bb094ec3f511964e78a5a4dba689b5986e112afabe4f4d0d6c6125c3` |
| libtheora | 1.2.0 / 1 | BSD-3-Clause | `39c207877312881a48330f60a50926cd4dc6eebb` | pr-merge | libtheora-1.2.0.tar.gz | `279327339903b544c28a92aeada7d0dcfd0397b59c2f368cc698ac56f515906e` |
| libudfread | 1.1.2 / 1 | LGPL-2.1-or-later | `041682f415c34b08ab63e272fb58d50623072049` | pr-merge | libudfread-1.1.2.tar.gz | `2bf16726ac98d093156195bb049a663e07d3323e079c26912546f4e05c77bac5` |
| libunistring | 1.3 / 1 | LGPL-3.0-or-later OR GPL-2.0-or-later | `aa55d5550262d9bef7893eab61be8792309f2c28` | pr-merge | libunistring-1.3.tar.xz | `f245786c831d25150f3dfb4317cda1acc5e3f79a5da4ad073ddca58886569527` |
| libv4l | 1.24.1 / 2 | LGPL-2.1-or-later AND GPL-2.0-only | `fb77115e46e14a83c92d95d8c91d5e359a1fc0e5` | pr-merge | v4l-utils-1.24.1.tar.bz2 | `cbb7fe8a6307f5ce533a05cded70bb93c3ba06395ab9b6d007eb53b75d805f5b` |
| libvidstab | 1.1.1 / 3 | GPL-2.0-or-later | `9e61527bd205ce3c630bfe96ba07a1018d34dd09` | pr-merge | vid.stab-1.1.1.tar.gz | `9001b6df73933555e56deac19a0f225aae152abbc0e97dc70034814a1943f3d4` |
| libvmaf | 3.0.0 / 2 | BSD-2-Clause-Patent | `9e61527bd205ce3c630bfe96ba07a1018d34dd09` | pr-merge | libvmaf-v3.0.0.tar.gz | `7178c4833639e6b989ecae73131d02f70735fdb3fc2c7d84bc36c9c3461d93b1` |
| libvo-amrwbenc | 0.1.3 / 2 | Apache-2.0 | `5a9aab51d3acda612d37cf31a6c2cca0e2fc8dbe` | pr-merge | vo-amrwbenc-0.1.3.tar.gz | `5652b391e0f0e296417b841b02987d3fd33e6c0af342c69542cbb016a71d9d4e` |
| libvorbis | 1.3.7 / 4 | BSD-3-Clause | `5a9aab51d3acda612d37cf31a6c2cca0e2fc8dbe` | pr-merge | libvorbis-1.3.7.tar.xz | `b33cc4934322bcbf6efcbacf49e3ca01aadbea4114ec9589d1b1e9d20f72954b` |
| libvpx | 1:1.15.2 / 0 | BSD-3-Clause | `de5f604e60ba4a9d0d989c8d56e0c307fe6e3a0c` | exact | libvpx-v1.15.2.tar.gz | `26fcd3db88045dee380e581862a6ef106f49b74b6396ee95c2993a260b4636aa` |
| libwebp | 1.6.0 / unknown | BSD-3-Clause | `b279d510b0ee2d35ba76c8928e736532edb340a9` | exact | libwebp-1.6.0.tar.gz | `e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564` |
| libx264 | 1:0.164.3191 / 1 | GPL-2.0-or-later | `172373a9a0ea7c3eb638d5aa59b2de04155920b3` | pr-merge | x264-4613ac3c15fd75cebc4b9f65b7fb95e70a3acce1.tar.bz2 | `2a1b197fd1fbc85045794f18c9353648a9ae3cbe194b7b92d523d096f9445464` |
| libx265 | 4.1 / 1 | GPL-2.0-or-later | `ead21c69e0d85642f5d14fa7adc81de88c03c5c9` | pr-merge | x265_4.1.tar.gz | `a31699c6a89806b74b0151e5e6a7df65de4b49050482fe5ebf8a4379d7af8f29` |
| libxml2 | 2.14.6 / 0 | MIT | `4281a7121498021bdfe63bcf723f6ae4a1a31952` | exact | libxml2-2.14.6.tar.xz | `7ce458a0affeb83f0b55f1f4f9e0e55735dbfc1a9de124ee86fb4a66b597203a` |
| libzimg | 3.0.6 / 0 | WTFPL | `c4886f10c55533a8d81d2fdc46c87dc9f0ebcea6` | pr-merge | release-3.0.6.tar.gz | `be89390f13a5c9b2388ce0f44a5e89364a20c1c57ce46d382b1fcc3967057577` |
| libzmq | 4.3.5 / 2 | MPL-2.0 | `9e61527bd205ce3c630bfe96ba07a1018d34dd09` | pr-merge | zeromq-4.3.5.tar.gz | `6653ef5910f17954861fe72332e68b03ca6e4d9c7160eb3a8de5a5a913bfab43` |
| littlecms | 2.17 / 1 | MIT | `629ff376a3917a4c73ca73fd0f041a00036eeecb` | pr-merge | lcms2.17.tar.gz | `6e6f6411db50e85ae8ff7777f01b2da0614aac13b7b9fcbea66dc56a1bc71418` |
| mutagen | 1.47.0 / unknown | GPL-2.0-or-later | `unknown` | unknown | mutagen-1.47.0.tar.gz | `719fadef0a978c31b4cf3c956261b3c58b6948b32023078a2117b1de09f0fc99` |
| ncurses | 6.5.20240831 / 3 | LicenseRef-MIT-X11 | `4661b33dee05d9abb63b659656cce831eceb4557` | pr-merge | a480458efb0662531287f0c75116c0e91fe235cb.tar.gz | `ec6122c3b8ab930d1477a1dbfd90299e9f715555a98b6e6805d5ae1b0d72becd` |
| ocl-icd | 2.3.4 / 0 | BSD-2-Clause | `bbfee719d07045285152d558037ad6d8709d8464` | exact | ocl-icd-v2.3.4.tar.gz | `1a302b71b7304cca5a36f69d017b1af2b762cc4c2dd1c0c0e2fc1933db25c9cc` |
| openssl | 1:3.5.2 / 0 | Apache-2.0 | `2816521e738405af97debbc50d5c0e8b3ac7a007` | pr-merge | openssl-3.5.2.tar.gz | `c53a47e5e441c930c3928cf7bf6fb00e5d129b630e0aa873b08258656e7345ec` |
| pcre2 | 10.46 / 0 | BSD-3-Clause WITH PCRE2-exception | `891fec900d5fca6a0adc83c59cde6550e254d31a` | exact | pcre2-10.46.tar.bz2 | `15fbc5aba6beee0b17aecb04602ae39432393aba1ebd8e39b7cabf7db883299f` |
| pycryptodomex | 3.23.0 / unknown | BSD-2-Clause AND LicenseRef-Public-Domain | `unknown` | unknown | pycryptodomex-3.23.0.tar.gz | `71909758f010c82bc99b0abf4ea12012c98962fbf0583c2164f8b84533c2e4da` |
| python | 3.12.11 / 1 | PSF-2.0 | `4661b33dee05d9abb63b659656cce831eceb4557` | pr-merge | Python-3.12.11.tar.xz | `c30bb24b7f1e9a19b11b55a546434f74e739bb4c271a3e3a80ff4380d49f7adb` |
| quickjs | 1:20250426 / 1 | MIT | `e8a4d2992e062737acbab946eb423104e86b124c` | pr-merge | quickjs-2025-04-26.tar.xz | `2f20074c25166ef6f781f381c50d57b502cb85d470d639abccebbef7954c83bf` |
| readline | 8.3.1 / 1 | GPL-3.0-or-later | `7417c8f39534796bf9a7844b3156504dcb53fb17` | pr-merge | readline-8.3.tar.gz; readline83-001 | `fe5383204467828cd495ee8d1d3c037a7eba1389c22bc6a041f627976f9061cc`; `21f0a03106dbe697337cd25c70eb0edbaa2bdb6d595b45f83285cdd35bac84de` |
| rubberband | 4.0.0 / 1 | GPL-2.0-or-later | `3e7d243649eb28e2d2c874353850a6687047157d` | pr-merge | rubberband-4.0.0.tar.bz2 | `af050313ee63bc18b35b2e064e5dce05b276aaf6d1aa2b8a82ced1fe2f8028e9` |
| svt-av1 | 3.1.2 / 0 | BSD-3-Clause-Clear AND LicenseRef-AOM-Patent | `53e7064ad82e4b3f1e8eec0ef2413c25f3cc370c` | exact | SVT-AV1-v3.1.2.tar.gz | `d0d73bfea42fdcc1222272bf2b0e2319e9df5574721298090c3d28315586ecb1` |
| xvidcore | 1.3.7 / 1 | GPL-2.0-or-later | `10e3e33a583ffd6031bc5c83c7c88b56c05f79b7` | pr-merge | xvidcore-1.3.7.tar.gz | `abbdcbd39555691dd1c9b4d08f0a031376a3b211652c0d8b3b8aa9be1303ce2d` |
| youtubedl-android | 0.18.1 / unknown | GPL-3.0-only | `unknown` | unknown | youtubedl-android-0.18.1-source.tar.gz | `unknown` |
| zlib | 1.3.1 / 1 | Zlib | `aa55d5550262d9bef7893eab61be8792309f2c28` | pr-merge | zlib-1.3.1.tar.xz | `38ef96b8dfe510d42707d9c781877914792541133e1870841463bfa73f883e32` |

## Limits of the pins

- PR CI builds are tied to the PR merged as the cited commit; recipe as in that commit.
- Identification uses version strings and build timestamps; no independent rebuild was performed.
- libc++ 28c and libandroid-stub 28c (libandroid/libmediandk/libOpenSLES) come from
  Android NDK r28c; their exact build commit is not determinable. The NDK ZIP recipe
  pin is `59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc`.
- Pycryptodomex's native build command is unrecorded; the source sdist is included.
- Toolchains and the builder image are not shipped: the original build environment
  is described as Android NDK r28c in termux-packages. The separate WebP rebuild
  uses the pinned r27d toolchain described above.
- This inventory covers arm64-v8a only.

The bundled yt-dlp hash does not attest to a later executable fetched by startup or
manual maintenance. Updated runtime version/hash/licence/source provenance must
be tracked separately. No device, 16 KB runtime or Play Store acceptance follows
from these source records or entry hashes.

## Offline reproduction and release gate

Prepare all files under the names in `source_file` (upstream archive basenames can
collide, so several are component-qualified). Preserve the exact bytes: generated
GitHub/GitLab archives can change; a hash mismatch must be investigated, never
silently re-pinned. Self-host the verified source assets with the release APK.
The WebP `.asc` is copied alongside its hash-verified tarball; the bundler does not
verify the signature or import a key. Trusted signature verification is recorded in
THIRD_PARTY_NOTICES.md and performed by the WebP rebuild procedure.

```sh
python3 scripts/native-notices.py generate --sources "$NEZZEL_SOURCES" \
  --out app/src/main/assets/licenses/NOTICES-native.txt
# Place libwebp-1.6.0.tar.gz in NEZZEL_SOURCES too for notice generation.
# The bundle script reads that file and its signature from WEBP_SRC.
sh scripts/source-bundle.sh <version> <outdir>
python3 scripts/native-notices.py check-apk --apk <release.apk>
```

The bundle script requires local `NEZZEL_SOURCES`, `TERMUX_PACKAGES`,
`YTDL_ANDROID` and `WEBP_SRC` directories, an existing `v<version>` tag, Python 3,
Git and xz. Missing inputs fail without network access (Git lazy fetching is disabled).
Tar entries are sorted by name with mtime/uid/gid zero and empty owner names,
equivalent to `--sort=name --mtime=@0 --owner=0 --group=0`; gzip headers have
mtime zero and no filename. Snapshots use `xz -T1 --lzma2=preset=6,dict=192MiB`.

The APK gate requires each of the six native entries exactly once and compares
its uncompressed bytes against this table. `res/raw/ytdlp` is compared when
present (resource shrinking can rename it). These pins describe the WebP-patched
release payload, not an arbitrary future engine update. A changed entry requires
new source/notice review and an explicit inventory update.

<!-- BEGIN APK PAYLOAD HASHES -->
| APK entry | Expected SHA-256 |
|---|---|
| `lib/arm64-v8a/libffmpeg.zip.so` | `ff8ad29f7512af84b195e21afa94d703416c3188f131aecc8a3df1e04f7d6221` |
| `lib/arm64-v8a/libpython.zip.so` | `7edecb6c9bd3a94456168d44911d8aea9f917bedef90c320a29500d92ac3eb62` |
| `lib/arm64-v8a/libffmpeg.so` | `1b8d5c371ea79e6e7a1930b31f2ad6a2731e48d75ceddc9c36dffda6e0092401` |
| `lib/arm64-v8a/libffprobe.so` | `a4d4a6d1562aa87934e99ee1a43f8090ec1040659cedb667d6edf3aa08408c5e` |
| `lib/arm64-v8a/libpython.so` | `58ba29cfc5a6d88281c07b2b480fb1d8cce1b78f280b002347f792cfd9cf5901` |
| `lib/arm64-v8a/libqjs.so` | `a0fe160a8cd76eec34132f945664856e96687d701e3b1ff28dff44ea1fad13bc` |
| `res/raw/ytdlp` | `e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3` |
<!-- END APK PAYLOAD HASHES -->
