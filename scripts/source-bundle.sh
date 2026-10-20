#!/bin/sh
# Offline only. Required clones must already contain every referenced Git object.
set -eu
if [ "$#" -ne 2 ]; then
    echo 'Usage: NEZZEL_SOURCES=<tarballs> TERMUX_PACKAGES=<clone> YTDL_ANDROID=<clone> WEBP_SRC=<dir> sh scripts/source-bundle.sh <version> <outdir>' >&2
    exit 2
fi
: "${NEZZEL_SOURCES:?Missing NEZZEL_SOURCES (verified native source files)}"
: "${TERMUX_PACKAGES:?Missing TERMUX_PACKAGES (local clone)}"
: "${YTDL_ANDROID:?Missing YTDL_ANDROID (local clone)}"
: "${WEBP_SRC:?Missing WEBP_SRC (libwebp tarball and signature)}"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export GIT_NO_LAZY_FETCH=1 GIT_NO_REPLACE_OBJECTS=1 GIT_TERMINAL_PROMPT=0
exec python3 - "$script_dir" "$1" "$2" <<'PY'
import copy
import gzip
import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

sys.dont_write_bytecode = True
script_dir, version, output = sys.argv[1:]
root = Path(script_dir).parent
spec = importlib.util.spec_from_file_location('native_notices', Path(script_dir) / 'native-notices.py')
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)
WRAPPER = 'd725d5c9a18c3a99a13ee0308bf78275dc310760'


def git(repo, *args, **kwargs):
    return subprocess.run(['git', '-c', 'protocol.allow=never', '-C', str(repo), *args],
                          check=True, **kwargs)


def normalized(member):
    member = copy.copy(member)
    member.uid = member.gid = 0
    member.uname = member.gname = ''
    member.mtime = 0
    member.pax_headers = {}
    return member


def append_archive(destination, archive_path, exclude=lambda name: False):
    with tarfile.open(archive_path) as archive:
        for member in sorted(archive.getmembers(), key=lambda item: item.name):
            if not exclude(member.name):
                stream = archive.extractfile(member) if member.isfile() else None
                try:
                    destination.addfile(normalized(member), stream)
                finally:
                    if stream:
                        stream.close()


def archive(repo, ref, prefix, out, paths=()):
    git(repo, 'archive', '--format=tar', '--prefix=' + prefix, '--output=' + str(out), ref,
        *(['--', *paths] if paths else []))


def gzip_copy(source, target):
    with source.open('rb') as stream, target.open('wb') as dest:
        with gzip.GzipFile(filename='', mode='wb', fileobj=dest, mtime=0) as zipped:
            shutil.copyfileobj(stream, zipped)


try:
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)+(?:[-+][A-Za-z0-9.-]+)?', version):
        raise ValueError('Invalid release version (omit the v prefix)')
    dirs = {key: Path(os.environ[key]).resolve() for key in
            ('NEZZEL_SOURCES', 'TERMUX_PACKAGES', 'YTDL_ANDROID', 'WEBP_SRC')}
    for key, path in dirs.items():
        if not path.is_dir():
            raise ValueError(f'Missing input directory: {key}={path}')
    if not shutil.which('xz'):
        raise ValueError('Missing installed xz executable')
    tag = 'refs/tags/v' + version
    git(root, 'rev-parse', '--verify', tag + '^{commit}', stdout=subprocess.DEVNULL)
    git(dirs['YTDL_ANDROID'], 'cat-file', '-e', WRAPPER + '^{commit}')
    rows = native.components()
    commits = sorted({row['termux_commit'] for row in rows if row['termux_commit'] != 'unknown'})
    for commit in commits:
        if not re.fullmatch('[0-9a-f]{40}', commit):
            raise ValueError('Invalid Termux commit: ' + commit)
        git(dirs['TERMUX_PACKAGES'], 'cat-file', '-e', commit + '^{commit}')
    files = {}
    for row in rows:
        # Wrapper source is made from its pinned Git commit below.
        if row['name'] == 'youtubedl-android':
            continue
        if row['copyleft'] != 'yes' and row['name'] not in ('python', 'quickjs', 'pycryptodomex', 'liblzma', 'libwebp'):
            continue
        for filename, _, digest in native.source_inputs(row):
            path = dirs['WEBP_SRC' if row['name'] == 'libwebp' else 'NEZZEL_SOURCES'] / filename
            if not path.is_file():
                raise ValueError(f'Missing source: {row["name"]}: {path}')
            native.verify(path, digest)
            if filename in files and native.sha256(files[filename]) != digest:
                raise ValueError('Conflicting source filename: ' + filename)
            files[filename] = path
    signature = dirs['WEBP_SRC'] / 'libwebp-1.6.0.tar.gz.asc'
    if not signature.is_file():
        raise ValueError('Missing WebP detached signature: ' + str(signature))
    files[signature.name] = signature
    out = Path(output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.nezzel-sources-', dir=out) as tmp:
        work = Path(tmp)
        stage = work / 'assets'
        stage.mkdir()
        raw = work / 'git.tar'
        archive(root, tag, f'nezzel-{version}/', raw)
        gzip_copy(raw, stage / f'nezzel-{version}-source.tar.gz')
        with tarfile.open(stage / f'nezzel-{version}-native-sources.tar', 'w', format=tarfile.PAX_FORMAT) as dest:
            for filename, path in sorted(files.items()):
                # Resolve links so the archive always carries the bytes, never a local symlink.
                info = normalized(dest.gettarinfo(str(path.resolve()), arcname=filename))
                info.mode = 0o644
                with path.open('rb') as stream:
                    dest.addfile(info, stream)
        with tarfile.open(stage / f'nezzel-{version}-native-sources.tar') as check:
            if any(not member.isfile() for member in check.getmembers()):
                raise ValueError('Native sources archive must contain regular files only')
        snapshots = work / 'snapshots.tar'
        # Equivalent to GNU tar --sort=name --mtime=@0 --owner=0 --group=0;
        # stdlib normalization also works with macOS's BSD tar installed.
        with tarfile.open(snapshots, 'w', format=tarfile.PAX_FORMAT) as dest:
            for commit in commits:
                archive(dirs['TERMUX_PACKAGES'], commit, f'termux-packages/{commit}/', raw)
                append_archive(dest, raw)
        with (stage / 'termux-packages-snapshots.tar.xz').open('wb') as dest:
            subprocess.run(['xz', '-T1', '--lzma2=preset=6,dict=192MiB', '-c', str(snapshots)],
                           stdout=dest, check=True)
        archive(dirs['YTDL_ANDROID'], WRAPPER, 'youtubedl-android-0.18.1/', raw,
                ('.', ':(exclude)**/jniLibs/**', ':(exclude)library/src/main/res/raw/ytdlp'))
        wrapper_tar = work / 'wrapper.tar'
        def excluded(name):
            return 'jniLibs' in Path(name).parts or name.endswith('/library/src/main/res/raw/ytdlp')
        with tarfile.open(wrapper_tar, 'w', format=tarfile.PAX_FORMAT) as dest:
            append_archive(dest, raw, excluded)
        gzip_copy(wrapper_tar, stage / 'youtubedl-android-0.18.1-source.tar.gz')
        shutil.copyfile(root / 'SOURCES.md', stage / 'SOURCES.md')
        (stage / 'SHA256SUMS').write_text(''.join(
            native.sha256(path) + '  ' + path.name + '\n' for path in sorted(stage.iterdir())), encoding='utf-8')
        for path in sorted(stage.iterdir()):
            os.replace(path, out / path.name)
    print('Source assets written to ' + str(out))
except (ValueError, OSError, subprocess.CalledProcessError, tarfile.TarError) as error:
    sys.exit('error: ' + str(error))
PY
