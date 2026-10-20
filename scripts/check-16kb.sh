#!/bin/sh
# Full acceptance requires SDK zipalign and GNU/NDK readelf. No downloads.
set -eu
exec python3 "$(dirname "$0")/native_16kb.py" check "$@"
