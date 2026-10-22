#!/usr/bin/env python3
"""Generate nezzel-update.json from the APK and marked EN/AR notes, optionally FR (stdlib, -I safe)."""
import hashlib
import json
from pathlib import Path
import re
import sys

SEMVER = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?")
APK_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,180}\.apk")
MAX_LONG = 9223372036854775807


def clean_note(line):
    line = line.replace("**", "").replace("`", "")
    line = re.sub(r"\[([^]]+)]\([^)]*\)", r"\1", line)
    line = re.sub(r"<[^>]*>", "", line)
    return "".join(char for char in line if not (ord(char) < 32 or 127 <= ord(char) <= 159
                                               or 0x202A <= ord(char) <= 0x202E
                                               or 0x2066 <= ord(char) <= 0x2069)).strip()[:1000]


def notes_block(body, language):
    blocks = re.findall(r"<!-- nezzel:notes:" + language + r" -->(.*?)<!-- /nezzel:notes -->", body, re.DOTALL)
    if len(blocks) != 1 or "<!-- nezzel:notes:" in blocks[0]:
        raise ValueError("exactly one notes block required for " + language)
    notes = [clean_note(line[2:]) for line in blocks[0].splitlines() if line.startswith("- ")]
    notes = [note for note in notes if note][:8]
    if not notes:
        raise ValueError("nonempty notes required for " + language)
    return notes


def generate(version, code, apk_path, notes_path):
    if not SEMVER.fullmatch(version):
        raise ValueError("invalid semver (omit the v prefix)")
    prerelease = version.split("+", 1)[0].partition("-")[2]
    if any(len(part) > 1 and part.startswith("0") and part.isascii() and part.isdigit()
           for part in prerelease.split(".")):
        raise ValueError("numeric prerelease identifiers must not start with zero")
    if not re.fullmatch(r"[1-9][0-9]*", code) or int(code) > MAX_LONG:
        raise ValueError("versionCode must be a positive signed 64-bit integer")
    apk = Path(apk_path)
    if not APK_NAME.fullmatch(apk.name) or ".." in apk.name:
        raise ValueError("unsafe APK basename")
    digest = hashlib.sha256()
    size = 0
    with apk.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            size += len(block)
            digest.update(block)
    if not 0 < size <= MAX_LONG - 64 * 1024 * 1024:
        raise ValueError("APK size is out of range")
    body = Path(notes_path).read_text(encoding="utf-8")
    manifest = {"schema": 1, "version": version, "versionCode": int(code),
                "apk": {"name": apk.name, "size": size, "sha256": digest.hexdigest()},
                "notes": {language: notes_block(body, language) for language in ("en", "ar")}}
    if "<!-- nezzel:notes:fr -->" in body:
        manifest["notes"]["fr"] = notes_block(body, "fr")
    output = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n"
    if len(output.encode("utf-8")) > 64 * 1024:
        raise ValueError("manifest exceeds 64 KiB")
    return output


def main():
    if len(sys.argv) != 5:
        print("usage: update-manifest.py <version> <versionCode> <apk-path> <notes.md>", file=sys.stderr)
        return 2
    try:
        output = generate(*sys.argv[1:])
    except (OSError, ValueError, UnicodeError) as error:
        print("update-manifest: " + str(error), file=sys.stderr)
        return 1
    sys.stdout.write(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
