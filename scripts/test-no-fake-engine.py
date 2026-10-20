#!/usr/bin/env python3
"""Offline artifact-isolation regressions; no Android toolchain required."""
from pathlib import Path
import io
import shutil
import subprocess
import tempfile
import zipfile


def archive(path, entries):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for name, data in entries.items():
            output.writestr(name, data)


def jar(data):
    stream = io.BytesIO()
    archive(stream, {"dev/ahmed/savlino/engine/Adapter.class": data})
    return stream.getvalue()


with tempfile.TemporaryDirectory(prefix="nezzel-artifact-check-") as temp:
    root = Path(temp)
    (root / "scripts").mkdir()
    checker = root / "scripts/check-no-fake-engine.sh"
    shutil.copyfile(Path(__file__).with_name("check-no-fake-engine.sh"), checker)
    apk = root / "app/build/outputs/apk/debug/app-debug.apk"
    aar = root / "engine/build/outputs/aar/engine-release.aar"
    apk.parent.mkdir(parents=True)
    aar.parent.mkdir(parents=True)

    def check(expected, label):
        result = subprocess.run(["sh", str(checker)], capture_output=True, text=True)
        assert result.returncode == expected, (label, result.returncode, result.stdout, result.stderr)
        print("PASS:", label)

    check(1, "missing artifacts fail closed")
    archive(apk, {"classes.dex": b"\x00real adapter", "classes2.dex": b"\x00other class"})
    archive(aar, {"classes.jar": jar(b"\x00real adapter")})
    check(0, "clean compressed APK and AAR pass")
    archive(apk, {"classes.dex": b"clean", "classes2.dex": b"\x00FakeEngine"})
    check(1, "secondary DEX FakeEngine rejected")
    archive(apk, {"classes.dex": b"clean"})
    release = apk.parent.parent / "release/app-release-unsigned.apk"
    release.parent.mkdir()
    archive(release, {"classes.dex": b"\x00FakeEngine"})
    check(1, "release APK FakeEngine rejected")
    release.unlink()
    archive(aar, {"classes.jar": jar(b"\x00FakeEngine")})
    check(1, "compressed AAR bytecode FakeEngine rejected")
    archive(aar, {"classes.jar": jar(b"clean")})
    nested = aar.parent / "nested/other.aar"
    nested.parent.mkdir()
    archive(nested, {"classes.jar": jar(b"\x00FakeEngine")})
    check(1, "nested engine AAR FakeEngine rejected")
