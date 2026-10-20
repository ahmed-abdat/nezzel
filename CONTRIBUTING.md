# Contributing to Nezzel

Small, focused pull requests are welcome. Check existing issues first and describe the problem before proposing a broad feature. Scope: public media, on-device processing, no embedded browser or account login. Contributions are accepted under the project license, GPL-3.0-or-later.

## Setup and checks

Follow the [README build setup](README.md#build-from-source): JDK 17, Android Platform 37.0 revision 2, Build Tools 36.0.0, and `ANDROID_HOME` pointing to your SDK. Use the checked-in wrapper and dependency locks. No maintainer credentials, phone, or emulator are needed for JVM checks.

From the repository root:

```sh
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
export GRADLE_USER_HOME="$PWD/.gradle-home"
./gradlew :app:testDebugUnitTest :core:test :engine:testDebugUnitTest lintDebug assembleDebug --max-workers=2
./gradlew --stop
```

Run `./gradlew --stop` even if a check fails. First-run dependency downloads need network access; add `--offline` only after the inputs are cached. `sh scripts/test.sh` is the offline JVM-only shortcut and stops Gradle automatically; set `ANDROID_HOME` first. Keep Gradle workers at two or fewer. Device/AVD tests are separate: compilation and Robolectric do not prove device acceptance. Say in your PR which checks ran on a real device or emulator; never claim device results from JVM tests.

## Code and UI conventions

- Match surrounding Kotlin, Compose, naming and formatting. Keep domain logic in `:core`, engine details in `:engine`, Android presentation/storage in `:app`.
- Add UI strings to both `app/src/main/res/values/strings.xml` and `app/src/main/res/values-ar/strings.xml`. No hard-coded user-facing text; use plurals where needed.
- Use start/end layout directions. Keep URLs and filenames LTR within Arabic layouts. Check English/Arabic, light/dark, large text and TalkBack; touch targets are at least 48 dp. No purple accents, including dynamic colors.
- Offer only engine-returned formats. Unknown sizes remain unknown; estimates must be labelled. Keep fakes in test fixtures and never invent progress or successful saves.
- Never log URLs, clipboard contents, cookies or credentials. Use synthetic IDs and reserved example domains in tests. Do not commit real page dumps, device serials, signing keys or private links.
- Preserve foreground-only clipboard access, draft/modal guards and explicit Download/Save confirmation. No account login, background clipboard monitoring or new transfer path without a reviewed scope decision.
- Add a focused regression check for a bug. Tests should assert observable results and cleanup, not just a returned status. Keep dependency changes separate, pinned, locked, verified and documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Site support and mapping changes

Start under `engine/src/main/kotlin/dev/ahmed/savlino/engine/`:

- `YtdlpErrorMapper.kt`: sanitized error classification; `YtdlpMetadataMapper.kt` and `YtdlpProgressMapper.kt`: returned formats and measured progress.
- `YtdlpCommand.kt`, `YoutubedlEngine.kt`, `YtdlpRuntime.kt`: execution, admission and runtime integration.
- `MediaSourceInspector.kt`, `mediaimage/PublicImageInspector.kt`: route selection and public-image inspection.
- `facebook/`, `instagram/`, `linkedin/`, `x/`: post identity and attachment parsers; `mediahttp/` enforces transport, origin and size/time budgets.

Tests mirror those packages in `engine/src/test/kotlin/`; reduced fixtures and provenance notes live in `engine/src/test/resources/`. Use the existing mapper/parser tests as examples. Error messages from extractors are untrusted and can contain private URLs: reproduce with synthetic diagnostics and check the typed error. A new mapping does not prove a site works. Add a supported-site claim only after a device test; in the PR, record the link type, result, app/engine version and saved-file check, without the original URL or device serial.

Preserve private/login/DRM refusal, redirect/address guards, attachment ownership and request budgets. Do not solve extractor failures by forwarding cookies or disabling site protections.

## Reporting a broken link

Use the bug template. Include the site, link type (for example reel, public video, photo post or carousel), Android version, app version/build, engine version from Settings, steps, and the visible error. Explain whether inspection or saving failed and whether another public post works.

A URL is **not required**. Never share private/unlisted links, signed CDN URLs, tokens, cookies, clipboard dumps or unredacted logs. Use a sanitized shape such as `https://example.com/posts/POST_ID`. If useful, provide a separate public, rights-cleared reproduction you are comfortable publishing. Crop/redact screenshots, including filenames and notifications. Report security issues privately through [SECURITY.md](SECURITY.md).

## Pull request checklist

- [ ] One focused change; linked issue and before/after behavior described.
- [ ] Relevant regression checks passed; exact commands/results included.
- [ ] App changes passed app JVM tests, lint and debug assembly; Gradle stopped.
- [ ] UI text exists in English and Arabic; RTL, accessibility and theme effects reviewed.
- [ ] No private URLs, logs, device identifiers, credentials or generated build outputs.
- [ ] Only actual formats/progress are shown; estimates and unknown values stay honest.
- [ ] Dependency/license changes and remaining device checks are documented.
- [ ] Public docs and metadata updated where behavior changes; no unrun device checks claimed.
