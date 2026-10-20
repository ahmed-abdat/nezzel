# Nezzel

Built by [Hululsoft](https://hululsoft.mr).

Nezzel is an ad-free Android app for saving videos, audio and photos from supported public links. Share or paste a link, review the available options, then tap Download or Save.

Processing runs on your device, with Arabic and English support and no Nezzel account or tracking. Some links won’t work; private, sign-in-required and protected content aren’t supported.

## بالعربية

Nezzel تطبيق أندرويد لحفظ الفيديو والصوت والصور من الروابط العامة المدعومة، بلا إعلانات أو تتبّع. شارك رابطًا أو الصقه، واختر من الخيارات المتاحة، ثم اضغط «تنزيل» أو «حفظ».

تتم المعالجة على جهازك، بواجهة عربية وإنجليزية ودون حساب في Nezzel. قد لا يعمل كل رابط؛ فالتطبيق لا يدعم المحتوى الخاص أو المحمي أو الذي يتطلب تسجيل الدخول.

**التثبيت والتحديث:** نزّل ملف APK من [صفحة الإصدارات](https://github.com/ahmed-abdat/nezzel/releases) وافتحه على هاتفك. لتصلك التحديثات تلقائيًا، أضف رابط هذا المستودع في تطبيق [Obtainium](https://github.com/ImranR98/Obtainium). كل إصدار جديد يُثبَّت فوق القديم ويحتفظ بملفاتك وإعداداتك.

## Features

- Foreground clipboard suggestions and optional automatic loading of metadata/options.
- Actual available video/audio formats; unknown sizes stay unknown and estimates are labelled.
- Audio extraction and MP3 conversion using the bundled engine.
- Public photo posts and photo selection for supported carousels.
- Original GIF image saves; previews stay still, while the saved file keeps its animation.
- Download queue, cancellation, explicit retry, notifications, and saved-file Open/Share/Delete.
- Save to `Download/Nezzel` or a folder chosen through Android’s system picker.
- Arabic RTL, light/dark themes, and font scaling.

## Tested sites

These link types passed checks on a real phone (Galaxy A34) in October 2026. **Saved** means a download or image save completed; **Options** means inspection passed but saving was not checked. This is not a guarantee for every URL or a full Android compatibility matrix.

### Video

| Site | Passed cases | Check |
| --- | --- | --- |
| YouTube | Watch videos and Shorts, including merged audio/video | Saved |
| Facebook | Public videos and Reels | Saved |
| Instagram | Video posts and Reels | Saved |
| TikTok | Public videos | Saved |
| X / Twitter | Videos and silent GIF-style videos (saved as video) | Saved |
| LinkedIn | Public video posts | Saved |
| Snapchat | Spotlight video | Saved |
| Pinterest | Video pins, including an HLS case | Saved |
| Bluesky | Video posts | Saved |
| Telegram | Public channel video posts | Saved |
| Dailymotion, Vimeo | Public videos | Saved |
| Direct links | MP4 video | Saved |
| Imgur, Odysee, 9GAG, Bilibili, Streamable | Video posts | Options |
| Twitch, Kick | Clips | Options |

### Audio

| Site | Passed cases | Check |
| --- | --- | --- |
| YouTube / YouTube Music | Audio-only M4A and MP3 conversion | Saved |
| SoundCloud | Tracks | Options |
| Mixcloud | Mixes | Options |
| Bandcamp | Tracks with an MP3 option | Options |

### Photos and GIF images

| Site | Passed cases | Check |
| --- | --- | --- |
| Facebook | Public photo posts | Saved |
| Instagram | Photos and carousel photo selection | Saved |
| X / Twitter | Photo posts | Saved |
| LinkedIn | Public image posts and an original animated GIF | Saved |
| Telegram | Public channel photo posts | Options |
| Bluesky | Photo posts | Saved |
| Direct links | JPEG image | Saved |

VK has inspection-only evidence and is not claimed as a verified download site. Rumble blocked requests in testing. Threads, Reddit, Tumblr and TikTok slideshow images are not claimed supported. Login-required, private and DRM-protected content is outside scope. Site changes can break previously working links; [report a sanitized example](CONTRIBUTING.md#reporting-a-broken-link).

## Screenshots

<!-- Replace with current, sanitized Arabic/English screenshots before public launch. -->
Screenshots coming soon: Home, quality selection, Downloads, and Arabic RTL.

## Install

Download `nezzel-<version>.apk` from [GitHub Releases](https://github.com/ahmed-abdat/nezzel/releases) and open it on your phone. Android may ask you to allow installation from the app opening the APK.

**Updates:** Nezzel does not check for app updates itself. To get them automatically, add `https://github.com/ahmed-abdat/nezzel` in [Obtainium](https://github.com/ImranR98/Obtainium), or watch this repository's releases on GitHub. Every release is signed with the same key, so a new APK installs over the old one and keeps your files and settings. The download engine (yt-dlp) updates itself weekly inside the app.

Requires **Android 10+ (API 29)** and **arm64-v8a**. Other CPU architectures are not packaged. Native libraries are 16 KB page-aligned; device testing so far is on 4 KB-page phones.

**Source for bundled components:** every GitHub Release also carries the corresponding source for the native components inside the APK (FFmpeg, Python, QuickJS and their libraries). See [SOURCES.md](SOURCES.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Build from source

Kotlin + Jetpack Compose Material3.

Install JDK 17 and an Android SDK containing **Platform 37.0 (revision 2)** and **Build Tools 36.0.0**. Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to your SDK directory. The project disables automatic JDK/SDK installation. The wrapper uses Gradle 9.3.1; AGP 9.1.1 and Kotlin 2.4.21 are pinned in the build files.

From a POSIX shell (Windows users can use WSL with a Linux JDK/SDK):

```sh
git clone https://github.com/ahmed-abdat/nezzel.git
cd nezzel
# Set JAVA_HOME and ANDROID_HOME for your installation before continuing.
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
export GRADLE_USER_HOME="$PWD/.gradle-home"
./gradlew :app:assembleDebug --max-workers=2
./gradlew --stop
```

The first build needs network access for the pinned Gradle distribution and dependencies. Dependency locks and SHA-256 verification are checked in. With all inputs cached, add `--offline`. The build generates a local debug signing key under ignored `.gradle-home/`; no maintainer signing key is needed. Output: `app/build/outputs/apk/debug/app-debug.apk`. Run `./gradlew --stop` after a failed build too.

See [CONTRIBUTING.md](CONTRIBUTING.md) for tests and contribution rules, and [CHANGELOG.md](CHANGELOG.md) for version notes.

## Project layout and engine

| Module | Responsibility |
| --- | --- |
| `:app` | Compose UI, intake, jobs, notifications, MediaStore publication and settings |
| `:core` | Pure Kotlin domain types, validation and format policy |
| `:engine` | yt-dlp adapter, metadata/error mapping, public-post readers and bounded HTTP/image handling |

The legacy package name `dev.ahmed.savlino` is intentional. Nezzel uses pinned **youtubedl-android 0.18.1** to run yt-dlp locally with bundled Python and FFmpeg. yt-dlp extracts media information and downloads selected streams; FFmpeg merges/converts when required. Public-photo readers handle supported post layouts separately. Only real engine results are offered; test fakes are not shipped.

The bundled yt-dlp version is 2025.11.12. Startup maintenance checks upstream STABLE seven days after the last success, automatically at most once per day, and defers while downloads are queued/running. Settings also offers a manual update. Downloaded replacements are not independently hash-pinned by Nezzel; the bundled dependency pins do not cover them. See [the engine inventory](THIRD_PARTY_NOTICES.md).

## Privacy and permissions

No analytics or telemetry. Processing stays on the device; requests go directly to source sites/CDNs and upstream engine-update infrastructure. This is not an offline app. Automatic option loading can fetch metadata and previews when Nezzel is foregrounded or accepts a shared/pasted link; turn off **Show options automatically** for manual inspection. There is no background clipboard monitoring. Saving media always requires an explicit action. Local job records support progress and retry; source URLs for retryable jobs have bounded retention. URLs and clipboard contents must never be logged.

App updates are checked through GitHub at most once a day after a successful automatic check while **Check for app updates** is on (default). Settings can turn this off or check manually. The primary request downloads `nezzel-update.json` from the latest release on `github.com` (with redirects to GitHub release-asset hosts). If that fetch or manifest is unavailable or invalid, Nezzel tries `api.github.com` once. GitHub sees your IP address and the Nezzel version in the User-Agent. No cookies, account or device identifiers are sent. Update notifications use permission already granted for downloads; updates never request notification permission. APK downloads happen only after you tap Download update, and Android asks you to confirm installation. The downloaded APK must match its SHA-256 digest, this app's package and signing certificates, and the release version code. Files and settings are preserved.

| Permission | Purpose |
| --- | --- |
| `INTERNET` | Inspection, previews, downloads and engine updates |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | User-confirmed download work |
| `POST_NOTIFICATIONS` | Download progress/results and app updates on Android 13+; denial does not prevent downloads |
| `REQUEST_INSTALL_PACKAGES` | User-confirmed app updates through Android PackageInstaller |
| `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` | WorkManager execution, connectivity constraints and rescheduling |
| App-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX internal receiver protection; signature-level, no user prompt |

The latter WorkManager permissions come from dependency manifest merging. No broad storage, contacts, microphone, or location permission is requested. MediaStore manages saved files.

## Contributing and security

Read [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and [SECURITY.md](SECURITY.md). Keep reports free of private URLs, cookies, account details and device identifiers.

## License and responsible use

Copyright (C) 2026 Hululsoft. Nezzel is licensed under **GPL-3.0-or-later** (see [LICENSE](LICENSE)). Third-party components keep their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The Hululsoft name and logo are not covered by the GPL; forks must not present themselves as made by Hululsoft.

Download only content you own or have permission or another legal right to download. Respect applicable law and site terms. Nezzel is not affiliated with the supported sites and does not provide a way to bypass DRM or sign-in requirements.
