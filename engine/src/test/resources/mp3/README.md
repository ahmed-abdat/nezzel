Generated tones, no recorded media. FFmpeg 8 with LAME 3.100, 0.3 s of a 440 Hz stereo sine at 44.1 kHz,
128 kbit/s (13230 samples, 13 frames):
- `lame-info.mp3`: a plain single-pass encode. Its Info frame carries LAME's own delay/padding and tag CRC.
- `raw-frames.mp3`: `-reservoir 0 -write_xing 0 -id3v2_version 0`, bare frames as a chunk writes them.
- `copy-mux-info.mp3`: `raw-frames.mp3` piped through `-c copy`; its Info frame says delay 0, padding 0.
