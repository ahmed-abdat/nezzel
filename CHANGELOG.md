# Changelog

Notable changes are recorded here in Keep a Changelog style, newest first.
Earlier entries were reconstructed from tags, commit history and recorded build notes.
Version entries describe existing builds, not public APK availability.

## [Unreleased]

### Added

- AVIF photos (Android 12+): Facebook now serves some photos only as AVIF; they load and save as .avif. Older Android versions say the image type isn't supported.

### Changed

- Error sheet: Try again and Paste another link share one row; the engine update and Close sit together below.

### Fixed

- Facebook's newer share links (/share/… with no p/v/r letter) show the post's photos instead of "couldn't read this link".
- Facebook links that redirect to the regional web.facebook.com page, and photos on the zero-rated image servers, load again.
- Facebook video share links (/share/v/, /share/r/) that open a post now find the post's video instead of failing.
- A Facebook post with photos and a video shows the photos and offers the video too.
- A Facebook post with only text or a shared link says there's no photo or video in it, and event links say they aren't supported, instead of "couldn't read this link".

## [0.4.6] - 2026-10-21

### Changed

- Settings redesign in plain words: an app card at the top (version, "You have the latest version" with a short check time, Check now or the waiting update), then grouped cards for General (language and theme pickers, wallpaper colours), Links, Saving and About. Show options automatically (still on by default), site support (engine) updates and the daily update check moved to a new Advanced page, with the engine version in a small support line; the licence paragraph and warranty line open with the licences.
- Downloads: a saved file's actions (Share, Open with, Show in Files, Delete) open in a bottom sheet headed by the file's thumbnail, title and size.
- Onboarding redesign: real site tiles and a slowly drifting logo wall on the first page, pages 2–4 as small pieces of the real app, bolder titles and shorter, confident copy EN/AR. Motion stays still under reduced motion.
- Update screen: a calm arc of site logos around the Nezzel mark, solid hero band, action slot pinned above the fold, confident titles.
- Match wallpaper colours (Android 12+) follows any wallpaper; the no-purple check and its explanation line are gone.
- Quality sheet is more compact: one short hint per row, tighter rows, one facts line (duration · channel · site), All qualities beside the first heading, and no ≈ footnote (≈ still marks estimated sizes; TalkBack says "estimated").
- Quality rows stay plain everywhere: Other versions show only the file type and a short codec name or audio rate (no format ids, merge partners or video bitrates), and versions that would look the same are left out; a remembered All qualities pick shows as a plain row in height order.
- Downloads: the running download leads, then the queue in the order it will run; a queued row shows its quality; progress reads "49% · 153 of 313 MB" with time left at the end.
- Open-source licences moved from About to a small link beside the support line in Advanced (notices still ship in the app); What's new opens in the app from notes bundled with each build, also on fresh installs and test builds.

### Fixed

- Last pick: a quality chosen in All qualities is remembered (by resolution, closest lower one when missing) and the «Last pick» badge no longer marks an older preset.
- LinkedIn photo posts with more photos than the grid shows ("+1") now load every photo; a members-only post says it isn't public instead of "couldn't read this link".
- A video+audio download's percent can no longer fall back when the second part starts reporting fragments.
- Show in Files on a chosen folder passes read access to that folder only; What's new always uses one version both languages have.
- Video+audio downloads show a real percent and time left from the first byte when the site reported exact sizes (before, the ring spun until the audio part started); once set, the total holds.

### Added

- Material Symbols icons (Apache-2.0) and Simple Icons site logos (CC0), noted in THIRD_PARTY_NOTICES and the in-app licences.

## [0.4.5] - 2026-10-20

### Added

- What's new: after an update, a sheet shows that version's notes once in the app language (Got it, Back or swipe closes it); About → What's new opens it again. Users updating from 0.4.4 get it from the cached release notes.
- Settings shows when Nezzel last checked for updates, and a dot on the Settings tab (announced by TalkBack) while an update is offered.

### Changed

- Update screen redesign: hero illustration, notes box with a link to the full notes, plain trust lines, one action slot that carries download progress, install and errors, plus Not now and Skip this version. The Home card leads with the first release note.
- Update downloads resume a matching partial file (HTTP Range) and a verified download survives a restart; space check asks for two copies; daily checks are jittered; a failed install offers the release page.

## [0.4.4] - 2026-10-20

### Added

- In-app updates: Nezzel checks GitHub once a day (Settings can turn it off or check now), shows a Home card and a notification once per version, and installs an update only after you confirm. Each update is verified first: SHA-256, same package, same signing certificate and a higher version code.
- Release manifest `nezzel-update.json` (generated by `scripts/update-manifest.py`) so checks work behind shared carrier IPs; the GitHub API is a fallback.

## [0.4.3] - 2026-10-20

First public release.

### Added

- Several links shared or pasted at once: the first opens, and after each sheet Nezzel offers the next one (Open or Skip all). The rest stay in memory only.
- Thumbnails and durations for videos saved to a chosen folder.
- 16 KB page-size support: a 16 KB-aligned arm64 FFmpeg WebP payload, plus a native alignment checker and a pinned WebP rebuild recipe.
- Local crash reports that can be shared or deleted explicitly from About; nothing is sent automatically.
- Complete licence notices for every bundled native library (Open-source licences → Native libraries), and each release carries the corresponding source (SOURCES.md).

### Changed

- New onboarding: four short pages with fixed illustrations, portrait and landscape.
- UI and copy polish: Settings puts everyday controls first with visible selection checkmarks; Home has an outlined link field; Downloads shows the current save folder; Options name the audio file type; clearer error messages.
- About no longer carries the company sales sentence or tagline.
- Links inside shared text or Arabic sentences are recognized; repeated links count once.
- Downloads shows phone-storage files right away while files in a chosen folder are checked in the background.

### Fixed

- Landscape onboarding fit, About’s Facebook link, custom-folder file labels and dimensions, and engine-version display.
- Facebook videos saved with a numeric title in the gallery: the title repair now runs on the device (FFmpeg finds its C++ library); the original is kept if repair fails.
- Recovery of an interrupted first-run engine setup; engine version checks can no longer hang.
- A photo being saved can no longer be removed by startup cleanup; full storage now shows the free-space message.
- Partial files in a chosen folder are cleaned up safely after Retry or Remove.
- A rejected or dismissed share no longer clears the current options; a quality picked in All quality options stays shown as selected.
- Retry clears the old failure notification.

## [0.4.2] - 2026-10-20

Build 310. Prepared and installed for testing; not publicly published.

### Added

- Public Telegram and Bluesky photo-post readers, retaining video inspection for video posts.

### Changed

- Facebook progressive MP4 options, preferring the site's `hd` file.
- H.264/AAC preference for compatible video playback; incompatible merge pairs retain MKV.
- Clearer English and Arabic onboarding and hints.

### Fixed

- Missing video options for Pinterest, Kick and Streamable cases.
- Saved-image dimensions no longer borrow thumbnail dimensions.
- Best video selection when a remembered preset is unavailable.
- Readable saved-file labels when provider titles are missing or numeric.

## [0.4.1] - 2026-10-20

Build 301. The recorded release check confirms the following behavior.

### Added

- Save original animated GIF images without conversion; GIF labels in Downloads and static previews.

## [0.4.0]

The build notes identify this version as predating original GIF image saving.
No tag or dated release entry is retained, so other changes are not assigned to this version here.

## [0.3.0] - 2026-10-19

### Added

- Select several saved files for sharing or deletion.
- Select several or all photos from supported Instagram and X carousels.

### Changed

- More responsive rows, navigation and Arabic/English labels.
- Clearer unavailable, sign-in, photo-only and invalid-post errors.

### Fixed

- First-run inspection waits for engine maintenance instead of showing an immediate failure.
- Confirming a queued download no longer waits for the current transfer to finish.
- Missing direct-video and HLS-audio options in supported cases.

## [0.2.0] - 2026-10-19

### Added

- Video/audio inspection, explicit download confirmation, audio extraction and MP3 conversion.
- Foreground clipboard suggestions, Paste and Android Share intake.
- Download progress, cancellation, retry, notifications and saved-file actions.
- Public-photo saving, including Facebook, LinkedIn, Instagram and X cases.
- Arabic/English interface, light/dark themes and local engine updates.

### Fixed

- Playback selection and handling of some video rows with unreported codecs.
