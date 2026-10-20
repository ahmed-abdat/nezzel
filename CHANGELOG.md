# Changelog

Notable changes are recorded here in Keep a Changelog style, newest first.
Earlier entries were reconstructed from tags, commit history and recorded build notes.
Version entries describe existing builds, not public APK availability.

## [Unreleased]

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
