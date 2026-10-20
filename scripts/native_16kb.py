#!/usr/bin/env python3
"""Inspect nested Android payloads and validate a five-library WebP replacement."""
import argparse
import copy
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile

WEBP_LIBS = ("libsharpyuv.so", "libwebp.so", "libwebpdecoder.so",
             "libwebpdemux.so", "libwebpmux.so")
FFMPEG_AAR_SHA256 = "0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49"


def elf_loads(data):
    """Return bitness, machine and LOAD (offset, virtual address, alignment)."""
    if data[:4] != b"\x7fELF" or data[4:6] not in (b"\x01\x01", b"\x02\x01"):
        raise ValueError("not a little-endian Android ELF")
    bits = 64 if data[4] == 2 else 32
    header = struct.unpack_from("<16sHHIQQQIHHHHHH" if bits == 64 else
                                "<16sHHIIIIIHHHHHH", data)
    layout = "<IIQQQQQQ" if bits == 64 else "<IIIIIIII"
    if header[9] < struct.calcsize(layout) or header[10] == 0xffff:
        raise ValueError("invalid/unsupported program header table")
    loads = []
    for index in range(header[10]):
        ph = struct.unpack_from(layout, data, header[5] + index * header[9])
        if ph[0] == 1:
            loads.append((ph[2], ph[3], ph[7]) if bits == 64 else
                         (ph[1], ph[2], ph[7]))
    if not loads:
        raise ValueError("no PT_LOAD segments")
    return bits, header[2], loads


def unaligned(loads):
    return any(align < 0x4000 or align & (align - 1) or
               offset % align != address % align for offset, address, align in loads)


def readelf_tool():
    if os.environ.get("READELF"):
        return os.environ["READELF"]
    for name in ("llvm-readelf", "readelf", "greadelf"):
        if tool := shutil.which(name):
            return tool
    sdk = Path(os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools"))
    return next((str(p) for p in sorted(sdk.glob(
        "ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf"), reverse=True)), None)


def run_readelf(tool, option, path):
    return subprocess.check_output([tool, option, "--wide", str(path)],
                                   text=True, stderr=subprocess.STDOUT,
                                   env={**os.environ, "LC_ALL": "C"})


def verify_readelf(tool, path, loads):
    output = run_readelf(tool, "--program-headers", path)
    rows = [line.split() for line in output.splitlines()
            if line.strip().startswith("LOAD ")]
    parsed = [(int(row[1], 16), int(row[2], 16), int(row[-1], 16)) for row in rows]
    if parsed != loads:
        raise ValueError("readelf PT_LOAD output disagrees with ELF headers")


def safe_infos(archive):
    infos = archive.infolist()
    names = set()
    for info in infos:
        path = PurePosixPath(info.filename)
        if path.is_absolute() or ".." in path.parts or "\\" in info.filename:
            raise ValueError("unsafe archive path: " + info.filename)
        if info.filename in names:
            raise ValueError("duplicate archive path: " + info.filename)
        names.add(info.filename)
    return infos


def link_target(archive, info):
    seen = set()
    while stat.S_ISLNK(info.external_attr >> 16):
        if info.filename in seen:
            raise ValueError("cyclic archive symlink: " + info.filename)
        seen.add(info.filename)
        target = archive.read(info).decode("utf-8")
        if target.startswith("/") or "\\" in target:
            raise ValueError("unsafe archive symlink: " + info.filename)
        name = posixpath.normpath(str(PurePosixPath(info.filename).parent / target))
        if name.startswith("../") or name == "..":
            raise ValueError("unsafe archive symlink: " + info.filename)
        try:
            info = archive.getinfo(name)
        except KeyError:
            raise ValueError("dangling archive symlink: " + name) from None
    if archive.read(info)[:4] != b"\x7fELF":
        raise ValueError("native symlink target is not ELF: " + info.filename)


def check(apk, diagnostic=False):
    tool = None if diagnostic else readelf_tool()
    errors = bad = count = exempt = payloads = 0
    if diagnostic:
        print("DIAGNOSTIC: raw ELF headers only; no zipalign/readelf acceptance")
    else:
        sdk = Path(os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools"))
        zipalign = os.environ.get("ZIPALIGN", str(sdk / "build-tools/36.0.0/zipalign"))
        try:
            result = subprocess.run([zipalign, "-c", "-P", "16", "-v", "4", str(apk)])
            if result.returncode:
                errors += 1
                print("ERROR: zipalign failed", flush=True)
        except OSError as error:
            errors += 1
            print("ERROR: zipalign unavailable:", error, flush=True)
        if not tool:
            errors += 1
            print("ERROR: readelf/llvm-readelf missing; set READELF (raw-header diagnostics follow)")

    with tempfile.TemporaryDirectory(prefix="nezzel-16kb-") as temp:
        def scan(data, label, apk_abi):
            nonlocal errors, bad, count, exempt, payloads
            try:
                if zipfile.is_zipfile(io.BytesIO(data)):
                    if not label.endswith(("libffmpeg.zip.so", "libpython.zip.so")):
                        raise ValueError("unexpected ZIP disguised as .so")
                    payloads += 1
                    before = count
                    with zipfile.ZipFile(io.BytesIO(data)) as nested:
                        for info in safe_infos(nested):
                            if info.is_dir():
                                continue
                            if stat.S_ISLNK(info.external_attr >> 16):
                                if ".so" in PurePosixPath(info.filename).name:
                                    link_target(nested, info)
                                continue
                            content = nested.read(info)
                            if ".so" in PurePosixPath(info.filename).name or content[:4] == b"\x7fELF":
                                scan(content, label + "!/" + info.filename, apk_abi)
                    if before == count:
                        raise ValueError("payload contains no ELF objects")
                    return
                bits, machine, loads = elf_loads(data)
                if (bits, machine) not in ((64, 183), (64, 62), (32, 40), (32, 3)):
                    raise ValueError("unexpected Android ELF machine/class")
                expected = {"arm64-v8a": (64, 183), "x86_64": (64, 62),
                            "armeabi-v7a": (32, 40), "x86": (32, 3)}
                if apk_abi is not None and expected.get(apk_abi) != (bits, machine):
                    raise ValueError("ABI mismatch for APK directory " + apk_abi)
                count += 1
                path = Path(temp) / (str(count) + ".so")
                path.write_bytes(data)  # Never extract an archive-provided path.
                if tool:
                    verify_readelf(tool, path, loads)
                if bits == 32:
                    exempt += 1
                elif unaligned(loads):
                    bad += 1
                    print("UNALIGNED:", label, "PT_LOAD", ",".join(hex(p[2]) for p in loads))
            except (ValueError, struct.error, OSError, subprocess.CalledProcessError,
                    zipfile.BadZipFile) as error:
                errors += 1
                print("ERROR:", label, str(error))

        try:
            with zipfile.ZipFile(apk) as package:
                for info in safe_infos(package):
                    if not info.is_dir() and re.search(r"\.so(?:\.|$)", info.filename):
                        parts = PurePosixPath(info.filename).parts
                        apk_abi = parts[1] if len(parts) >= 3 and parts[0] == "lib" else None
                        scan(package.read(info), info.filename, apk_abi)
        except (ValueError, OSError, zipfile.BadZipFile) as error:
            errors += 1
            print("ERROR:", str(error))
    if count == 0:
        errors += 1
        print("ERROR: APK contains no ELF objects")
    print(f"SUMMARY: {count} ELF, {payloads} nested payloads, {exempt} exempt (32-bit), "
          f"{bad} unaligned, {errors} errors", flush=True)
    return 1 if bad or errors else (2 if diagnostic else 0)


SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so"}


def abi(tool, path):
    dynamic = run_readelf(tool, "--dynamic", path)
    soname = re.findall(r"\(SONAME\).*\[([^]]+)\]", dynamic)
    needed = sorted(re.findall(r"\(NEEDED\).*\[([^]]+)\]", dynamic))
    exports = {}
    for line in run_readelf(tool, "--dyn-syms", path).splitlines():
        row = line.split()
        if len(row) >= 8 and row[0].rstrip(":").isdigit() and row[4] in ("GLOBAL", "WEAK", "UNIQUE"):
            if row[6] != "UND" and row[5] in ("DEFAULT", "PROTECTED"):
                # Function code size changes; object/TLS size is part of the ABI.
                exports[row[7]] = (row[3:6], row[2] if row[3] in ("OBJECT", "TLS") else None)
    if len(soname) != 1 or not exports:
        raise ValueError("missing SONAME or exported ABI: " + str(path))
    return soname, needed, exports


def repack(aar, libraries, output):
    if hashlib.sha256(aar.read_bytes()).hexdigest() != FFMPEG_AAR_SHA256:
        raise ValueError("FFmpeg AAR SHA-256 mismatch")
    tool = readelf_tool()
    if not tool:
        raise ValueError("NDK llvm-readelf required")
    replacements = {}
    with zipfile.ZipFile(aar) as outer:
        original = outer.read("jni/arm64-v8a/libffmpeg.zip.so")
    with tempfile.TemporaryDirectory(prefix="nezzel-webp-abi-") as temp:
        with zipfile.ZipFile(io.BytesIO(original)) as source:
            infos = safe_infos(source)
            for name in WEBP_LIBS:
                new = libraries / name
                data = new.read_bytes()
                bits, machine, loads = elf_loads(data)
                if (bits, machine) != (64, 183) or unaligned(loads):
                    raise ValueError("unaligned or non-arm64 replacement: " + name)
                verify_readelf(tool, new, loads)
                old = Path(temp) / name
                old.write_bytes(source.read("usr/lib/" + name))
                old_abi, new_abi = abi(tool, old), abi(tool, new)
                # The NDK may add Android system libraries (always present) where the original inlined them.
                extra = set(new_abi[1]) - set(old_abi[1])
                same = (old_abi[0], old_abi[2]) == (new_abi[0], new_abi[2]) and set(old_abi[1]) <= set(new_abi[1])
                if not same or not extra <= SYSTEM_LIBS or new_abi[0] != [name]:
                    raise ValueError("SONAME/dependencies/exported ABI mismatch: " + name)
                replacements["usr/lib/" + name] = data
            staged = Path(temp) / "libffmpeg.zip.so"
            with zipfile.ZipFile(staged, "w") as target:
                target.comment = source.comment
                for info in infos:
                    target.writestr(copy.copy(info), replacements.get(info.filename,
                                    source.read(info)), compresslevel=9)
            with zipfile.ZipFile(staged) as target:
                if source.namelist() != target.namelist():
                    raise ValueError("ZIP entry list changed")
                for info in infos:
                    expected = replacements.get(info.filename, source.read(info))
                    if target.read(info.filename) != expected:
                        raise ValueError("ZIP content changed: " + info.filename)
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(staged, output)
    for name in WEBP_LIBS:
        print(hashlib.sha256((libraries / name).read_bytes()).hexdigest(), name)
    print(hashlib.sha256(output.read_bytes()).hexdigest(), output.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    checker = commands.add_parser("check")
    checker.add_argument("--diagnostic", action="store_true",
                         help="raw headers only; always exits nonzero")
    checker.add_argument("apk", type=Path)
    patcher = commands.add_parser("repack")
    patcher.add_argument("aar", type=Path)
    patcher.add_argument("libraries", type=Path)
    patcher.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.command == "check":
        return check(args.apk, args.diagnostic)
    repack(args.aar, args.libraries, args.output)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print("ERROR:", error, file=sys.stderr)
        sys.exit(1)
