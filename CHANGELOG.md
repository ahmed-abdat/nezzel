# Changelog

Notable changes are recorded here in Keep a Changelog style, newest first.
Earlier entries were reconstructed from tags, commit history and recorded build notes.
Version entries describe existing builds, not public APK availability.

## [0.5.5] - 2026-10-31

### Changed

- A download paused for over a day keeps Try again for 7 days. After that it asks you to share or paste the link again.

### Fixed

- Opening a saved video after Android closed Nezzel in the background no longer crashes.

## [0.5.4] - 2026-10-31

### Added

- Type words on Home to search YouTube, then open a result's download options.
- Share a file to WhatsApp from the viewer. The next audio file plays when one ends.
- Playlists and many links at once can be saved as Small or MP3. A photo carousel can save only its sound.
- A video with no sound at all says No sound.

### Changed

- Statuses save into the folder you chose in Settings, like every other download.
- A download paused for over a day shows as failed, with Try again.
- A playlist card says when some items used another quality or MP3.
- Quality names say what you really get, and MP3 never goes above what the source has (except the usual 192 kbps).
- Saved files share one menu on Home, and a failed download offers Change quality.
- Match wallpaper colors follows the phone's own palette on Samsung too.

### Fixed

- Music keeps playing in the mini player when you share a link to Nezzel.
- Losing the network while a download is still getting ready now waits for it to come back.
- An always-on VPN with no network under it counts as offline, so downloads wait.
- Photos keep their real shape in the preview. Pinterest photo pins save again, full size. AVIF images are recognised.
- Saving a photo still works after you share another one.
- Removing a failed download no longer leaves a half-saved file behind.
- Opening the keyboard on Home no longer pushes the page under the status bar.
- Clearer messages for a TikTok post blocked in your region and a Reddit post that links to a private video or an article.

## [0.5.3] - 2026-10-29

### Added

- Hold a video for 2× speed. Swipe a video or photo down to close it, or to leave full screen.

### Changed

- After an update, Nezzel opens on Home. What's new stays in About.
- A quality you confirm in the share sheet is the one the app picks next.
- Opening a link again shows its last preview at once, while the options load.

### Fixed

- If saving fails after the file has already downloaded, Retry saves it again without downloading.
- A failed MP3 conversion keeps the audio and Retry converts it again.

## [0.5.2] - 2026-10-28

### Changed

- The Home logo row adds Reddit, Pinterest, Vimeo and SoundCloud.

### Fixed

- A dropped connection, including airplane mode, says Waiting for connection and keeps the percent and size.
- A drop while a download is still preparing waits for the network instead of failing.

## [0.5.1] - 2026-10-27

### Added

- Play saved videos and audio in Nezzel. Audio keeps playing from Home or the lock screen, in a bar above the tabs.
- Player controls: tap to show or hide them, double-tap to skip, speed, full screen and volume.
- Reddit photos and galleries can be saved. Public Reddit videos load again.

### Changed

- Built-in site support is yt-dlp 2026.10.26.
- TikTok titles show emoji, and a taller picture is offered when the post has one.
- The screen stays on while a video plays.

### Fixed

- YouTube Mix links open the video they came from.
- A Telegram video that's too big for the web says it opens only in Telegram.
- Reddit links whose titles use accented letters load again. An 18+ photo post asks you to sign in.

## [0.5.0] - 2026-10-25

### Added

- New look: a floating tab bar, with your downloads and recent files on Home.
- Statuses tab: save WhatsApp statuses you have already opened.
- Download a YouTube playlist, or several links at once, then retry any that failed.
- TikTok photo posts: keep the photos, the sound, or both.
- Downloads: grid view, swipe between files, rename, sort and search. Pause all, resume all and retry all.
- A Home screen widget, and your old downloads come back after a reinstall.
- Nezzel can keep itself up to date in the background. On Android 16, a running download can show its percent on the lock screen.
- While Help improve Nezzel is on, a failed link sends its report on its own.

### Fixed

- Home no longer crashes on the first download after the list was restored.
- A playlist no longer crashes when one download hands off to the next.

## [0.4.9] - 2026-10-24

### Added

- Pause a download and resume it later; a dropped connection waits and resumes where it stopped.
- Up to 3 downloads at once (Settings → Advanced, default 2).
- Shortcuts: a "Paste link" Quick Settings tile, app-icon shortcuts, and "Open in Nezzel" for a link selected in a browser.
- Copy the link of any saved file from Downloads.
- On mobile data, a large download says how much data it will use.

### Changed

- YouTube download options show up about 8× faster (about 2.5 s instead of 20 s on the same phone).
- Flatter screens: settings, notes, quality rows and the empty Downloads tab drop their boxes; Home's footer fits one row.

### Fixed

- Saved audio keeps its title and cover, and Downloads shows the cover instead of a music icon.
- More link forms work, and a cut-off download is retried instead of saved broken.
- A dropped connection during the format re-check waits for the network.

## [0.4.8] - 2026-10-22

### Added

- French: the app follows a French phone, or pick Français in Settings. Sizes show in octets (Ko, Mo, Go).
- What's new in French after an update.
- Settings: message us on WhatsApp.
- Website in Arabic, English and French, each at its own address, with a language menu.

### Changed

- Facebook stories explain that they need a login instead of showing a generic error.
- README rewritten with screenshots; privacy details moved to PRIVACY.md; CONTRIBUTING explains how to build from source.
- Website: the demo phone shows your local time; on an iPhone the download line says Nezzel is Android only.

### Fixed

- Saved file names that mix Arabic and Latin text show in the right order.
- Downloads list: "12 of 30 MB" instead of "12 MB of 30 MB", and audio rates show as kbps.

## [0.4.7] - 2026-10-22

### Added

- AVIF photos (Android 12+): Facebook now serves some photos only as AVIF; they load and save as .avif. Older Android versions say the image type isn't supported.
- Report this link: when Nezzel can't read a link, a button opens WhatsApp with the link and app version filled in, ready for you to send to the developer. Nothing is sent unless you press Send.

### Changed

- Facebook posts with several photos show every photo the post page exposes (up to 20), so you can pick which to save, like Instagram and X. Before, only the first photo showed.
- Error sheet: Try again and Paste another link share one row, each label on one line; with large text or a long translation they stack. The engine update and Close sit together below.

### Fixed

- Facebook's newer share links (/share/… with no p/v/r letter) show the post's photos instead of "couldn't read this link".
- Facebook links that redirect to the regional web.facebook.com page, and photos on the zero-rated image servers, load again.
- Facebook video share links (/share/v/, /share/r/) that open a post now find the post's video instead of failing.
- A Facebook post with photos and a video shows the photos and offers the video too.
- Fresh install: a link that the built-in engine couldn't read is read again by itself as soon as the first automatic engine update finishes, instead of staying on "couldn't read this link".
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
