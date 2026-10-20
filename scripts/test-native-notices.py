#!/usr/bin/env python3
"""Offline notice/release-gate tests; synthetic sources and APKs only."""
import csv
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import os
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import zipfile
import warnings

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).with_name('native-notices.py')


class NativeNoticesTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), 'native-notices.py is not implemented')
        spec = importlib.util.spec_from_file_location('notices', SCRIPT)
        self.native = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.native)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = self.root / 'sources.tsv'
        self.rows = []

    def source(self, name, files=None):
        path = self.root / (name + '.tar.gz')
        with tarfile.open(path, 'w:gz') as archive:
            for filename, text in (files or {'COPYING': 'Same complete licence text.\n'}).items():
                data = text.encode()
                entry = tarfile.TarInfo(name + '/' + filename)
                entry.size = len(data)
                archive.addfile(entry, io.BytesIO(data))
        self.rows.append(dict(name=name, version='1', termux_rev='0', payload='ff',
            spdx='MIT', copyleft='no', termux_commit='unknown', match='unknown',
            source_url='https://example.org/' + path.name,
            sha256=hashlib.sha256(path.read_bytes()).hexdigest(), license_files='unknown',
            source_file=path.name))
        self.write_manifest()
        return path

    def write_manifest(self):
        with self.manifest.open('w', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=self.rows[0].keys(), delimiter='\t')
            writer.writeheader()
            writer.writerows(self.rows)

    def generate(self, missing=False):
        output = self.root / 'NOTICES.txt'
        self.native.generate(self.root, output, missing, self.manifest)
        return output.read_bytes()

    def test_changed_source_is_rejected_before_output(self):
        path = self.source('one')
        path.write_bytes(path.read_bytes() + b'tampered')
        with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
            self.generate(True)
        self.assertFalse((self.root / 'NOTICES.txt').exists())

    def test_identical_texts_share_one_appendix_without_losing_copyrights(self):
        self.source('one', {'COPYING': 'Same complete licence text.\n',
                            'NOTICE': 'Copyright One\n'})
        self.source('two', {'COPYING': 'Same complete licence text.\n',
                            'NOTICE': 'Copyright Two\n'})
        output = self.generate().decode()
        self.assertEqual(1, output.count('Same complete licence text.'))
        self.assertIn('APPENDIX', output)
        self.assertIn('Copyright One', output)
        self.assertIn('Copyright Two', output)
        self.assertIn('one 1 — MIT', output)
        self.assertIn('two 1 — MIT', output)

    def test_generation_is_byte_deterministic(self):
        self.source('one')
        self.assertEqual(self.generate(), self.generate())

    def test_missing_source_requires_explicit_partial_mode(self):
        self.source('one').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing source'):
            self.generate()
        self.assertIn(b'MISSING', self.generate(True))
        self.assertIn(b'one 1', self.generate(True))

    def test_available_archive_without_notice_fails_even_in_partial_mode(self):
        self.source('one', {'source.c': 'code'})
        with self.assertRaisesRegex(ValueError, 'No licence'):
            self.generate(True)

    def test_explicit_patent_file_and_nested_notices_are_retained(self):
        self.source('one', {'LICENSE': 'Licence', 'PATENTS': 'Patent grant',
                            'vendor/lib/NOTICE': 'Nested copyright', 'AUTHORS': 'Not required'})
        self.rows[0]['license_files'] = 'PATENTS'
        self.write_manifest()
        output = self.generate().decode()
        self.assertIn('Patent grant', output)
        self.assertIn('Nested copyright', output)
        self.assertNotIn('Not required', output)

    def test_explicit_required_notice_cannot_silently_disappear(self):
        self.source('one', {'LICENSE': 'Licence'})
        self.rows[0]['license_files'] = 'PATENTS'
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'Missing required notice'):
            self.generate(True)

    def test_unknown_hash_is_not_accepted_even_in_partial_mode(self):
        self.source('one')
        self.rows[0]['sha256'] = 'unknown'
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'Unknown SHA-256'):
            self.generate(True)

    def test_zip_sources_and_nonstandard_licence_filename(self):
        path = self.source('one')
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('one/docs/FTL.TXT', 'FreeType licence fixture')
            archive.writestr('one/COPYRIGHT', 'Copyright fixture')
        self.rows[0]['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.rows[0]['license_files'] = 'docs/FTL.TXT'
        self.write_manifest()
        output = self.generate().decode()
        self.assertIn('FreeType licence fixture', output)
        self.assertIn('Copyright fixture', output)

    def test_apk_mismatch_missing_and_duplicate_entries_fail(self):
        entries = {name: b'payload' for name in self.native.PAYLOAD_ENTRIES}
        document = self.root / 'SOURCES.md'
        pin = hashlib.sha256(b'payload').hexdigest()
        document.write_text('<!-- BEGIN APK PAYLOAD HASHES -->\n' + ''.join(
            f'| `{name}` | `{pin}` |\n' for name in entries) + '<!-- END APK PAYLOAD HASHES -->\n')
        apk = self.root / 'test.apk'
        def write(values):
            with zipfile.ZipFile(apk, 'w') as archive:
                for name, data in values.items():
                    archive.writestr(name, data)
        write(entries)
        self.native.check_apk(apk, document)
        write({**entries, 'res/raw/ytdlp': b'changed'})
        with self.assertRaisesRegex(ValueError, 'Missing APK hash pin'):
            self.native.check_apk(apk, document)
        write(entries)
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(apk, 'a') as archive:
                archive.writestr(next(iter(entries)), b'payload')
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.native.check_apk(apk, document)
        first = next(iter(entries))
        write({**entries, first: b'changed'})
        with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
            self.native.check_apk(apk, document)
        write({key: value for key, value in entries.items() if key != first})
        with self.assertRaisesRegex(ValueError, 'Missing APK entry'):
            self.native.check_apk(apk, document)

    @unittest.skipUnless(shutil.which('git') and shutil.which('xz'), 'installed git and xz required')
    def test_offline_bundle_contents_hashes_and_repeatability(self):
        repo = self.root / 'repo'
        scripts = repo / 'scripts'
        scripts.mkdir(parents=True)
        def git(*args):
            return subprocess.run(['git', '-C', str(repo), *args], check=True,
                                  capture_output=True).stdout.decode().strip()
        git('init', '-q')
        self.source('one')
        self.rows[0]['copyleft'] = 'yes'
        self.write_manifest()
        for name in ('source.kt', 'library/src/main/res/raw/ytdlp', 'ffmpeg/src/main/jniLibs/lib.so'):
            path = repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture')
        git('add', '.')
        git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.org', 'commit', '-qm', 'fixture')
        commit = git('rev-parse', 'HEAD')
        git('tag', 'v1.0')
        self.rows[0]['termux_commit'] = commit
        self.write_manifest()
        shutil.copyfile(self.manifest, scripts / 'native-sources.tsv')
        shutil.copyfile(SCRIPT, scripts / SCRIPT.name)
        bundle = SCRIPT.with_name('source-bundle.sh').read_text()
        # Substitute only the immutable wrapper pin with this fixture's commit;
        # all archive/filter/hash logic runs unchanged with real local Git and xz.
        bundle = bundle.replace('d725d5c9a18c3a99a13ee0308bf78275dc310760', commit)
        (scripts / 'source-bundle.sh').write_text(bundle)
        (repo / 'SOURCES.md').write_text('Fixture source inventory')
        (self.root / 'libwebp-1.6.0.tar.gz.asc').write_text('signature fixture')
        environment = {**os.environ, 'NEZZEL_SOURCES': str(self.root),
                       'TERMUX_PACKAGES': str(repo), 'YTDL_ANDROID': str(repo), 'WEBP_SRC': str(self.root)}
        output = self.root / 'bundle'
        command = ['sh', str(scripts / 'source-bundle.sh'), '1.0', str(output)]
        first = subprocess.run(command, env=environment, capture_output=True, text=True)
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        sums = (output / 'SHA256SUMS').read_bytes()
        self.assertEqual(5, len(sums.splitlines()))
        for line in sums.decode().splitlines():
            digest, filename = line.split('  ')
            self.assertEqual(digest, hashlib.sha256((output / filename).read_bytes()).hexdigest())
        with tarfile.open(output / 'youtubedl-android-0.18.1-source.tar.gz') as archive:
            self.assertTrue(any(name.endswith('/source.kt') for name in archive.getnames()))
            self.assertFalse(any('jniLibs' in name or name.endswith('/res/raw/ytdlp') for name in archive.getnames()))
        with tarfile.open(output / 'termux-packages-snapshots.tar.xz') as archive:
            self.assertTrue(archive.getmembers())
            self.assertTrue(all(member.mtime == member.uid == member.gid == 0 for member in archive.getmembers()))
            self.assertEqual(sorted(archive.getnames()), archive.getnames())
        second = subprocess.run(command, env=environment, capture_output=True, text=True)
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertEqual(sums, (output / 'SHA256SUMS').read_bytes())
        # Missing local inputs must fail without publishing a fresh output asset.
        (self.root / 'one.tar.gz').unlink()
        third = subprocess.run(command, env=environment, capture_output=True, text=True)
        self.assertNotEqual(0, third.returncode)
        self.assertIn('Missing source', third.stderr)
        self.assertEqual(sums, (output / 'SHA256SUMS').read_bytes())


if __name__ == '__main__':
    unittest.main()
