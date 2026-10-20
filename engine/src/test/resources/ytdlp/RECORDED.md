# Recorded metadata fixtures

Retrieved from primary reporter output on the upstream yt-dlp issue tracker at 2026-10-17T23:56:32+00:00.
These are existing public extraction recordings, not Nezzel device/site acceptance evidence.
No extractor, engine, media request or device operation ran to collect these fixtures.

| Fixture | Recorded source | Upstream issue last updated |
|---|---|---|
| `recorded-youtube.json` | https://github.com/yt-dlp/yt-dlp/issues/14567 | 2025-11-08T19:23:40Z |
| `recorded-social-mp4.json` | https://github.com/yt-dlp/yt-dlp/issues/5807 | 2026-08-22T15:26:26Z |
| `recorded-youtube-hls-dubs.json` | Local `yt-dlp` 2026.08.19 `--dump-json` of one public 777 s YouTube video (2026-10-18, metadata only, no media transfer) | n/a |
| `recorded-audio.json` | https://github.com/yt-dlp/yt-dlp/issues/17651 | 2026-09-09T19:55:46Z |

## Filtering method

Read each existing issue body through GitHub's read-only issues API. Locate JSON objects
with Python `JSONDecoder.raw_decode`, including complete individual format objects inside
truncated surrounding output. Preserve original numeric, string, boolean and null values
only for `format_id`, `ext`, `vcodec`, `acodec`, `filesize`, `filesize_approx`, `width`,
`height`, `abr`, `tbr`, and `has_drm`. Wrap selected objects in a `formats` array.
Missing fields stay missing: no codecs, sizes or dimensions are inferred or added.
All URLs, headers, titles, content/user IDs, client metadata and credentials are omitted.

- YouTube: six complete recorded format objects from the truncated issue output: one
  storyboard, three audio-only rows, and two muxed video rows. The available complete
  subset has no video-only row; adaptive video-only coverage remains in controlled schema
  fixtures. Distinct format IDs retain their exact ASCII digits and suffixes.
- Social MP4: one actual muxed H.264/AAC MP4 row (`hls-2176`) from Twitter output.
  This is a reduced single-row shape from an **HLS** recording, not a live HTTP single-file
  recording. It supplies recorded muxed metadata without inventing codecs absent in the
  issue's HTTP rows. Existing `single-file.json` retains the controlled HTTP-shaped case.
- Audio: six actual SoundCloud audio-only rows, preserving AAC/MP3 codecs and exact versus
  approximate sizes. No video rows or synthetic conversion rows were added.

The older `youtube.json`, `single-file.json` and `audio.json` remain hand-curated boundary
fixtures as described in `README.md`; do not identify them as recordings.

- YouTube HLS + dubs (`recorded-youtube-hls-dubs.json`): all 188 format objects, reduced to
  `format_id`, `ext`, `vcodec`, `acodec`, `width`, `height`, `tbr`, `abr`, `filesize`,
  `filesize_approx`, `protocol`, `language_preference`, `has_drm`, plus root `duration`.
  No URLs, manifests, fragments, headers, thumbnails, titles or channel/video IDs. Covers DASH
  rows with HLS (`m3u8_native`) twins and 20 auto-dubbed audio groups beside the original.
