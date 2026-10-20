#!/bin/sh
# Check packaged production bytecode, including secondary DEX files and compressed AAR classes.
set -eu
cd "$(dirname "$0")/.."
scratch=$(mktemp -d "${TMPDIR:-/tmp}/nezzel-no-fake.XXXXXX")
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
apk_count=0
aar_count=0
for apk in app/build/outputs/apk/*/*.apk; do
    [ -f "$apk" ] || continue
    unzip -p "$apk" 'classes*.dex' > "$scratch/bytecode"
    if LC_ALL=C grep -a -q 'FakeEngine' "$scratch/bytecode"; then
        echo "FAIL: FakeEngine found in $apk" >&2
        exit 1
    fi
    apk_count=$((apk_count + 1))
done
if [ -d engine/build/outputs/aar ]; then
    find engine/build/outputs/aar -type f -name '*.aar' -print > "$scratch/aars"
    while IFS= read -r aar; do
        unzip -p "$aar" classes.jar > "$scratch/classes.jar"
        unzip -p "$scratch/classes.jar" > "$scratch/bytecode"
        if LC_ALL=C grep -a -q 'FakeEngine' "$scratch/bytecode"; then
            echo "FAIL: FakeEngine found in $aar" >&2
            exit 1
        fi
        aar_count=$((aar_count + 1))
    done < "$scratch/aars"
fi
if [ "$apk_count" -eq 0 ] || [ "$aar_count" -eq 0 ]; then
    echo 'FAIL: build an app APK and engine AAR before checking FakeEngine isolation' >&2
    exit 1
fi
echo "PASS: no FakeEngine in $apk_count APK(s) / $aar_count engine AAR(s)"
