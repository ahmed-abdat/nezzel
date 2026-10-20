"""Offline CLI tests using a synthetic APK; the generator must also run with -I."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "update-manifest.py"


class UpdateManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "nezzel-0.4.4.apk"
        self.apk.write_bytes(b"fake-apk\x00\xff")
        self.notes = self.root / "notes.md"
        self.notes.write_text("<!-- nezzel:notes:en -->\n- **Fix** `video`\n<!-- /nezzel:notes -->\n"
                              "<!-- nezzel:notes:ar -->\n- إصلاح\n<!-- /nezzel:notes -->", encoding="utf-8")

    def run_generator(self, version="0.4.4", code="400"):
        return subprocess.run([sys.executable, "-I", str(SCRIPT), version, code, str(self.apk), str(self.notes)],
                              cwd=self.root, capture_output=True, text=True, encoding="utf-8")

    def test_isolated_cli_hashes_exact_bytes_and_extracts_both_languages(self):
        result = self.run_generator()
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads(result.stdout)
        self.assertEqual({"schema": 1, "version": "0.4.4", "versionCode": 400,
                          "apk": {"name": "nezzel-0.4.4.apk", "size": 10,
                                  "sha256": hashlib.sha256(b"fake-apk\x00\xff").hexdigest()},
                          "notes": {"en": ["Fix video"], "ar": ["إصلاح"]}}, manifest)
        self.assertNotIn("url", manifest["apk"])

    def test_missing_or_empty_language_block_fails_without_output(self):
        for body in ["", "<!-- nezzel:notes:en -->\n- Fix\n<!-- /nezzel:notes -->",
                     "<!-- nezzel:notes:en -->\n- Fix\n<!-- nezzel:notes:ar -->\n- إصلاح\n<!-- /nezzel:notes -->",
                     "<!-- nezzel:notes:en -->\n<!-- /nezzel:notes -->\n"
                     "<!-- nezzel:notes:ar -->\n- إصلاح\n<!-- /nezzel:notes -->"]:
            with self.subTest(body=body):
                self.notes.write_text(body, encoding="utf-8")
                result = self.run_generator()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)

    def test_notes_are_cleaned_and_bounded(self):
        lines = ["- **one** `two` [link](discard) <b>text</b>\u202e\u2066\x01"] + ["- " + "x" * 1005] * 9
        self.notes.write_text("\n".join("<!-- nezzel:notes:" + lang + " -->\n" + "\n".join(lines)
                                        + "\n<!-- /nezzel:notes -->" for lang in ["en", "ar"]), encoding="utf-8")
        result = self.run_generator()
        self.assertEqual(0, result.returncode, result.stderr)
        notes = json.loads(result.stdout)["notes"]["en"]
        self.assertEqual(8, len(notes))
        self.assertEqual("one two link text", notes[0])
        self.assertEqual(1000, len(notes[1]))

    def test_invalid_identity_and_empty_apk_fail(self):
        for version, code in [("v0.4.4", "400"), ("0.04.4", "400"), ("0.4.4-01", "400"),
                              ("0.4.4", "0"), ("0.4.4", "no"), ("0.4.4", "9223372036854775808")]:
            with self.subTest(version=version, code=code):
                result = self.run_generator(version, code)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)
        self.apk.write_bytes(b"")
        self.assertNotEqual(0, self.run_generator().returncode)


if __name__ == "__main__":
    unittest.main()
