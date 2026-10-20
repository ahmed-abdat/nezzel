#!/usr/bin/env python3
"""Offline native notices and APK provenance gate. Python standard library only."""
import argparse
import csv
import fnmatch
import hashlib
from pathlib import Path, PurePosixPath
import re
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / 'scripts/native-sources.tsv'
PAYLOAD_ENTRIES = tuple('lib/arm64-v8a/' + name for name in (
    'libffmpeg.zip.so', 'libpython.zip.so', 'libffmpeg.so', 'libffprobe.so',
    'libpython.so', 'libqjs.so'))
OPTIONAL_YTDLP = 'res/raw/ytdlp'


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def components(manifest=MANIFEST):
    with Path(manifest).open(encoding='utf-8', newline='') as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    if not rows or len({r['name'] for r in rows}) != len(rows):
        raise ValueError('Empty manifest or duplicate component names')
    for row in rows:
        for key in ('source_file', 'source_url', 'sha256'):
            if not row.get(key):
                raise ValueError(f"Missing {key}: {row['name']}")
        if len({len(row[k].split(';')) for k in ('source_file', 'source_url', 'sha256')}) != 1:
            raise ValueError(f"Source list length mismatch: {row['name']}")
    return sorted(rows, key=lambda row: row['name'])


def source_inputs(row):
    for filename, url, digest in zip(*(row[k].split(';') for k in ('source_file', 'source_url', 'sha256'))):
        if filename != 'unknown' and (Path(filename).name != filename or filename in ('.', '..')):
            raise ValueError(f'Unsafe source filename: {filename}')
        yield filename, url, digest


def verify(path, expected):
    if not re.fullmatch('[0-9a-f]{64}', expected):
        raise ValueError(f'Unknown SHA-256 pin: {path.name}; pin verified input before release')
    if sha256(path) != expected:
        raise ValueError(f'SHA-256 mismatch: {path.name}')


def notice_files(path, explicit):
    """Read archive members, never extract paths or follow links onto the host."""
    patterns = [] if explicit == 'unknown' else explicit.split(';')
    def wanted(name):
        base = PurePosixPath(name).name
        upper = base.upper()
        return (any(upper.startswith(prefix) for prefix in
                    ('COPYING', 'LICENSE', 'LICENCE', 'NOTICE', 'COPYRIGHT'))
                or any(fnmatch.fnmatchcase(name, pattern) or
                       fnmatch.fnmatchcase(base, pattern) or
                       fnmatch.fnmatchcase(name, '*/' + pattern) for pattern in patterns))
    texts = []
    def add(name, data):
        # Some historical notices are Latin-1. Decode reversibly, without dropped bytes.
        try:
            text = data.decode('utf-8-sig')
        except UnicodeDecodeError:
            text = data.decode('latin-1')
        text = '\n'.join(line.rstrip(' \t') for line in
                         text.replace('\r\n', '\n').replace('\r', '\n').split('\n')).strip() + '\n'
        if text.strip():
            texts.append((name, text))
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            for member in sorted(archive.infolist(), key=lambda item: item.filename):
                if not member.is_dir() and wanted(member.filename):
                    add(member.filename, archive.read(member))
    elif tarfile.is_tarfile(path):
        with tarfile.open(path, 'r:*') as archive:
            for member in sorted(archive.getmembers(), key=lambda item: item.name):
                if member.isfile() and wanted(member.name):
                    with archive.extractfile(member) as stream:
                        add(member.name, stream.read())
    elif path.suffix == '.pem':
        # CA bundle is its own source; retain its leading licence/copyright comments.
        comments = []
        for line in path.read_text(encoding='utf-8').splitlines():
            if line and not line.startswith('#'):
                break
            comments.append(line)
        if comments:
            add(path.name, '\n'.join(comments).encode())
    return texts


def generate(sources, out, allow_missing=False, manifest=MANIFEST):
    sources, out = Path(sources), Path(out)
    sections, missing, occurrences = [], [], {}
    for row in components(manifest):
        texts, absent = [], []
        for filename, url, digest in source_inputs(row):
            path = sources / filename
            if filename == 'unknown' or not path.is_file():
                absent.append(filename)
                continue
            verify(path, digest)  # Partial mode never permits bad or unknown hashes.
            texts.extend(notice_files(path, row['license_files']))
        title = f"{row['name']} {row['version']} — {row['spdx']} — {row['source_url']}"
        if absent:
            missing.append(title + '\n  Missing source: ' + ', '.join(absent))
        if not texts and not absent:
            raise ValueError(f"No licence/copyright/notice files found: {row['name']}")
        if not absent and row['license_files'] != 'unknown':
            for pattern in row['license_files'].split(';'):
                if not any(fnmatch.fnmatchcase(name, pattern) or
                           fnmatch.fnmatchcase(PurePosixPath(name).name, pattern) or
                           fnmatch.fnmatchcase(name, '*/' + pattern) for name, _ in texts):
                    raise ValueError(f"Missing required notice: {row['name']}: {pattern}")
        # A multi-source component can have a patch with no separate licence file.
        for _, text in texts:
            occurrences[text] = occurrences.get(text, 0) + 1
        sections.append((title, texts, bool(absent)))
    if missing and not allow_missing:
        raise ValueError('Missing source inputs:\n' + '\n'.join(missing))
    shared = {text: f'TEXT-{index:03d}' for index, text in enumerate(
        sorted((t for t, count in occurrences.items() if count > 1),
               key=lambda t: hashlib.sha256(t.encode()).hexdigest()), 1)}
    output = ['Nezzel — native component notices\n',
              'Source inventory and provenance: SOURCES.md and scripts/native-sources.tsv\n',
              'Texts retain their upstream terms; component SPDX labels describe the shipped parts.\n',
              'Line endings and trailing horizontal whitespace are normalized.\n']
    if missing:
        output += ['\nMISSING — INCOMPLETE; regenerate without --allow-missing before release\n',
                   '\n\n'.join(missing) + '\n']
    for title, texts, absent in sections:
        output += ['\n' + '=' * 72 + '\n', title + '\n']
        if absent:
            output.append('MISSING source input(s); texts below may be incomplete.\n')
        for name, text in texts:
            output.append('\n--- ' + name + ' ---\n')
            output.append('See APPENDIX ' + shared[text] + '.\n' if text in shared else text)
    if shared:
        output.append('\nAPPENDIX — identical texts, reproduced once\n')
        for text, label in shared.items():
            output += ['\n' + label + '\n', text]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(''.join(output).encode('utf-8'))


def check_apk(apk, document=ROOT / 'SOURCES.md'):
    text = Path(document).read_text(encoding='utf-8')
    block = text.split('<!-- BEGIN APK PAYLOAD HASHES -->')[1].split('<!-- END APK PAYLOAD HASHES -->')[0]
    pins = dict(re.findall(r'\| `([^`]+)` \| `([0-9a-f]{64})` \|', block))
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        for name in PAYLOAD_ENTRIES + ((OPTIONAL_YTDLP,) if OPTIONAL_YTDLP in names else ()):
            if names.count(name) != 1:
                raise ValueError(f'Missing APK entry or duplicate: {name}')
            if name not in pins:
                raise ValueError(f'Missing APK hash pin: {name}')
            digest = hashlib.sha256()
            with archive.open(name) as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            if digest.hexdigest() != pins[name]:
                raise ValueError(f'SHA-256 mismatch: APK {name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    generate_parser = commands.add_parser('generate')
    generate_parser.add_argument('--sources', type=Path, required=True)
    generate_parser.add_argument('--out', type=Path, required=True)
    generate_parser.add_argument('--allow-missing', action='store_true')
    apk_parser = commands.add_parser('check-apk')
    apk_parser.add_argument('--apk', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'generate':
            generate(args.sources, args.out, args.allow_missing)
        else:
            check_apk(args.apk)
    except (ValueError, OSError, IndexError, tarfile.TarError, zipfile.BadZipFile) as error:
        parser.exit(1, f'error: {error}\n')


if __name__ == '__main__':
    main()
