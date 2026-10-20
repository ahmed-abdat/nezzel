#!/bin/sh
# App + core + engine JVM suite; no network during tests. Dependencies must already be cached.
set -eu
cd "$(dirname "$0")/.."
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$PWD/.gradle-home"}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
trap './gradlew --stop' EXIT HUP INT TERM
./gradlew :app:testDebugUnitTest :core:test :engine:testDebugUnitTest --offline --max-workers=2
