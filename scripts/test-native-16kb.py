#!/usr/bin/env python3
"""Offline regressions for nested payload discovery and fail-closed ELF checks."""
import io
import hashlib
import importlib.util
import os
from pathlib import Path
import struct
import subprocess
import stat
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


ROOT = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("native_16kb", ROOT / "native_16kb.py")
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


def elf(align=0x4000, bits=64, offset=0, address=0):
    ident = b"\x7fELF" + bytes((2 if bits == 64 else 1, 1, 1)) + bytes(9)
    if bits == 64:
        header = struct.pack("<16sHHIQQQIHHHHHH", ident, 3, 183, 1, 0, 64,
                             0, 0, 64, 56, 1, 64, 0, 0)
        return header + struct.pack("<IIQQQQQQ", 1, 5, offset, address, 0, 0, 0, align)
    header = struct.pack("<16sHHIIIIIHHHHHH", ident, 3, 40, 1, 0, 52,
                         0, 0, 52, 32, 1, 40, 0, 0)
    return header + struct.pack("<IIIIIIII", 1, offset, address, 0, 0, 0, 5, align)


def archive(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for name, data in entries.items():
            if isinstance(data, tuple):
                info = zipfile.ZipInfo(name)
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                output.writestr(info, data[0])
                continue
            output.writestr(name, data)
    return stream.getvalue()


class AlignmentTests(unittest.TestCase):
    def check_apk(self, entries):
        with tempfile.TemporaryDirectory(prefix="nezzel-16kb-test-") as temp:
            apk = Path(temp) / "fixture.apk"
            apk.write_bytes(archive(entries))
            # Explicit diagnostic mode exercises the real ELF parser without NDK tools.
            return subprocess.run(["sh", str(ROOT / "check-16kb.sh"),
                                   "--diagnostic", str(apk)],
                                  capture_output=True, text=True)

    def test_nested_versioned_python_object_cannot_be_skipped(self):
        result = self.check_apk({
            "lib/arm64-v8a/libffmpeg.so": elf(),
            "lib/arm64-v8a/libpython.zip.so": archive({
                "usr/lib/libpython3.12.so.1.0": elf(0x1000),
                "usr/lib/python3.12/lib-dynload/clean.so": elf(),
            }),
        })
        self.assertIn("libpython3.12.so.1.0", result.stdout)
        self.assertIn("1 unaligned", result.stdout)
        self.assertNotEqual(0, result.returncode)

    def test_only_32_bit_objects_are_exempt(self):
        result = self.check_apk({"lib/armeabi-v7a/libtest.so": elf(0x1000, bits=32)})
        self.assertIn("1 exempt (32-bit)", result.stdout)
        self.assertIn("0 unaligned", result.stdout)
        self.assertNotEqual(0, result.returncode, "diagnostic mode never grants acceptance")

    def test_large_alignment_with_incongruent_segment_fails(self):
        result = self.check_apk({"lib/arm64-v8a/libtest.so": elf(address=0x1000)})
        self.assertIn("1 unaligned", result.stdout)

    def test_invalid_object_and_empty_payload_fail(self):
        for name, data in (("libtest.so", b"not ELF"),
                           ("libffmpeg.zip.so", archive({"readme": b"empty"}))):
            with self.subTest(name=name):
                result = self.check_apk({"lib/arm64-v8a/" + name: data})
                self.assertIn("ERROR", result.stdout)
                self.assertNotEqual(0, result.returncode)

    def test_nested_traversal_is_never_extracted(self):
        result = self.check_apk({"lib/arm64-v8a/libffmpeg.zip.so": archive({
            "../../escape.so": elf(),
        })})
        self.assertIn("unsafe archive path", result.stdout)

    def test_aligned_diagnostic_is_never_a_pass(self):
        result = self.check_apk({"lib/arm64-v8a/libtest.so": elf()})
        self.assertIn("1 ELF", result.stdout)
        self.assertIn("0 unaligned", result.stdout)
        self.assertIn("DIAGNOSTIC", result.stdout)
        self.assertNotEqual(0, result.returncode)

    def test_symlink_alias_is_resolved_without_counting_it_as_elf(self):
        result = self.check_apk({"lib/arm64-v8a/libffmpeg.zip.so": archive({
            "usr/lib/libalias.so": (b"libactual.so.1",),
            "usr/lib/libactual.so.1": elf(),
        })})
        self.assertIn("1 ELF", result.stdout)
        self.assertIn("0 errors", result.stdout)

    def test_dangling_or_escaping_symlink_fails(self):
        for target in (b"absent.so", b"../../../outside.so"):
            result = self.check_apk({"lib/arm64-v8a/libffmpeg.zip.so": archive({
                "usr/lib/libalias.so": (target,),
                "usr/lib/libactual.so.1": elf(),
            })})
            self.assertIn("ERROR", result.stdout)

    def test_32_bit_cannot_be_exempted_inside_a_64_bit_abi(self):
        for data in (elf(bits=32), archive({"usr/lib/wrong.so": elf(bits=32)})):
            name = "libffmpeg.zip.so" if data[:2] == b"PK" else "wrong.so"
            result = self.check_apk({"lib/arm64-v8a/" + name: data})
            self.assertIn("ABI mismatch", result.stdout)
            self.assertIn("0 exempt", result.stdout)

    def test_full_checker_requires_matching_readelf_output(self):
        with tempfile.TemporaryDirectory(prefix="nezzel-16kb-tools-") as temp:
            temp = Path(temp)
            apk = temp / "fixture.apk"
            apk.write_bytes(archive({"lib/arm64-v8a/test.so": elf()}))
            zipalign = temp / "zipalign"
            zipalign.write_text("#!/bin/sh\nexit 0\n")
            zipalign.chmod(0o755)
            readelf = temp / "readelf"
            for output, expected in (("LOAD 0x0 0x0 0x0 0x0 0x0 R E 0x4000", 0),
                                     ("", 1)):
                readelf.write_text("#!/bin/sh\ncat <<'EOF'\n" + output + "\nEOF\n")
                readelf.chmod(0o755)
                result = subprocess.run(["sh", str(ROOT / "check-16kb.sh"), str(apk)],
                    env={**os.environ, "ZIPALIGN": str(zipalign), "READELF": str(readelf)},
                    capture_output=True, text=True)
                self.assertEqual(expected, result.returncode, result.stdout + result.stderr)


class RepackTests(unittest.TestCase):
    def test_repack_allows_only_added_system_dependencies_and_preserves_soname(self):
        cases = (
            (("libwebp.so",), ("libwebp.so", "libc.so"), False, True),
            (("libwebp.so",), ("libwebp.so", "libm.so"), False, True),
            (("libwebp.so",), ("libwebp.so", "libdl.so"), False, True),
            (("libwebp.so",), ("libwebp.so", "libc++_shared.so"), False, False),
            (("libwebp.so",), ("libc.so",), False, False),
            (("libwebp.so", "libc.so"), ("libwebp.so",), False, False),
            (("libwebp.so",), ("libwebp.so",), True, False),
        )
        with tempfile.TemporaryDirectory(prefix="nezzel-16kb-dependencies-") as temp:
            temp = Path(temp)
            aar = temp / "ffmpeg.aar"
            aar.write_bytes(archive({"jni/arm64-v8a/libffmpeg.zip.so": archive({
                "usr/lib/" + name: elf(0x1000) for name in native.WEBP_LIBS
            })}))
            libraries = temp / "libs"
            libraries.mkdir()
            for name in native.WEBP_LIBS:
                (libraries / name).write_bytes(elf())
            for old_needed, new_needed, wrong_soname, allowed in cases:
                with self.subTest(old=old_needed, new=new_needed, wrong_soname=wrong_soname):
                    def readelf(tool, option, path):
                        if option == "--program-headers":
                            return "LOAD 0x0 0x0 0x0 0x0 0x0 R E 0x4000"
                        if option == "--dynamic":
                            replacement = path.parent == libraries
                            soname = "wrong.so" if wrong_soname and replacement else path.name
                            needed = new_needed if replacement else old_needed
                            return f"0x0 (SONAME) Library soname: [{soname}]\n" + "\n".join(
                                f"0x0 (NEEDED) Shared library: [{name}]" for name in needed)
                        return "1: 00000000 12 FUNC GLOBAL DEFAULT 1 WebPExport"

                    output = temp / "candidate.so"
                    with mock.patch.object(native, "FFMPEG_AAR_SHA256", hashlib.sha256(aar.read_bytes()).hexdigest()), \
                         mock.patch.object(native, "readelf_tool", return_value="fixture-readelf"), \
                         mock.patch.object(native, "run_readelf", side_effect=readelf), \
                         mock.patch("sys.stdout", new_callable=io.StringIO):
                        if allowed:
                            native.repack(aar, libraries, output)
                            with zipfile.ZipFile(output) as result:
                                self.assertEqual(elf(), result.read("usr/lib/libwebpmux.so"))
                            output.unlink()
                        else:
                            with self.assertRaisesRegex(ValueError, "SONAME/dependencies/exported ABI mismatch"):
                                native.repack(aar, libraries, output)
                            self.assertFalse(output.exists())

    def test_repack_changes_only_five_contents_and_is_deterministic(self):
        with tempfile.TemporaryDirectory(prefix="nezzel-16kb-repack-") as temp:
            temp = Path(temp)
            original = {"usr/lib/" + name: elf(0x1000) for name in native.WEBP_LIBS}
            original.update({"usr/lib/other.so.1": elf(), "usr/etc/config": b"untouched"})
            payload = archive(original)
            aar = temp / "ffmpeg.aar"
            aar.write_bytes(archive({"jni/arm64-v8a/libffmpeg.zip.so": payload}))
            libraries = temp / "libs"
            libraries.mkdir()
            for name in native.WEBP_LIBS:
                (libraries / name).write_bytes(elf())

            def readelf(tool, option, path):
                if option == "--program-headers":
                    return "LOAD 0x0 0x0 0x0 0x0 0x0 R E 0x4000"
                if option == "--dynamic":
                    return f"0x000 (SONAME) Library soname: [{path.name}]"
                return "1: 00000000 12 FUNC GLOBAL DEFAULT 1 WebPExport"

            with mock.patch.object(native, "FFMPEG_AAR_SHA256", hashlib.sha256(aar.read_bytes()).hexdigest()), \
                 mock.patch.object(native, "readelf_tool", return_value="fixture-readelf"), \
                 mock.patch.object(native, "run_readelf", side_effect=readelf), \
                 mock.patch("sys.stdout", new_callable=io.StringIO):
                output = temp / "candidate.so"
                native.repack(aar, libraries, output)
                first = output.read_bytes()
                native.repack(aar, libraries, output)
                self.assertEqual(first, output.read_bytes())
                with zipfile.ZipFile(io.BytesIO(payload)) as before, zipfile.ZipFile(output) as after:
                    self.assertEqual(before.namelist(), after.namelist())
                    for name in original:
                        wanted = elf() if Path(name).name in native.WEBP_LIBS else original[name]
                        self.assertEqual(wanted, after.read(name))
                        self.assertEqual(before.getinfo(name).date_time, after.getinfo(name).date_time)
                        self.assertEqual(before.getinfo(name).external_attr, after.getinfo(name).external_attr)
                # One changed symbol must prevent replacement, not publish another ZIP.
                output.unlink()
                def wrong_export(tool, option, path):
                    if option == "--dyn-syms" and path.parent == libraries:
                        return "1: 00000000 12 FUNC GLOBAL DEFAULT 1 DifferentExport"
                    return readelf(tool, option, path)
                with mock.patch.object(native, "run_readelf", side_effect=wrong_export):
                    with self.assertRaisesRegex(ValueError, "exported ABI mismatch"):
                        native.repack(aar, libraries, output)
                self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
