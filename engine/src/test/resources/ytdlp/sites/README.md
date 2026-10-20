# Site format fixtures

Twelve site fixtures were supplied for this unit (eleven sanitized real captures
and modeled Pinterest). The additional modeled X fixture brings the total to
thirteen `fixture-*.json` files. Real captures preserve sanitized
yt-dlp structures (Facebook: desktop 2026.07.04). URLs remain `example.invalid`; no media,
cookies or credentials are included. These describe returned formats, not device
playback acceptance.

`fixture-pinterest.json` is modeled on the reported device failure: H.264
video-only HLS rows at 234–720p and an explicit audio-only HLS MP4 playlist whose
audio codec is absent. Its unknown sizes and audio codec are intentional.

`fixture-x.json` models the existing X device shape with reported HLS AAC audio
and codec-less direct MP4 alternatives, to guard against duplicate quality rows.

`SiteFormatsMappingTest` covers each fixture's offered files, presets and exact
download/merge arguments. It also exercises conservative codec/container pairs
and recovery boundaries. Existing neutral Video labels handle absent dimensions;
Facebook's sd/hd IDs never become invented 480p/720p facts.
