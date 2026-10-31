#!/bin/sh
# App + core + engine JVM suite; no network during tests. Dependencies must already be cached.
# Default is the push suite. --full adds -PnezzelNightly=true (slow transport/timeout and cost tests).
set -eu
cd "$(dirname "$0")/.."
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$PWD/.gradle-home"}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
gradle_args=
if [ "${1:-}" = "--full" ]; then
    gradle_args=-PnezzelNightly=true
elif [ $# -gt 0 ]; then
    echo "usage: sh scripts/test.sh [--full]" >&2
    exit 2
fi
./gradlew :app:testDebugUnitTest :core:test :engine:testDebugUnitTest --offline --max-workers=2 $gradle_args
