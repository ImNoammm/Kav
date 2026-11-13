#!/usr/bin/env python3
"""Builds the OpenFreeMap map pack Kav downloads from the maps-1 release (data/MapPack.kt).

Usage: python3 -I tools/map_packs.py SOURCE.tar.gz OFL.txt OUT_DIR

SOURCE.tar.gz is the pack szaionz built for PR #71
(https://github.com/szaionz/kav-offline-maps/releases/download/v0.0.3/openfreemap.tar.gz): an OpenMapTiles
extract of Israel made with planetiler from Geofabrik's israel-and-palestine file, OpenFreeMap's fonts and its
sprite. OFL.txt is Noto's licence (https://github.com/notofonts/latin-greek-cyrillic/blob/main/OFL.txt).

Writes to OUT_DIR:
  openfreemap-israel.pmtiles   the tiles, as they are
  openfreemap-assets.tar.gz    the sprite and the fonts: the 13 glyph ranges Kav's own map has (Latin, Greek,
                               Cyrillic, Hebrew, Arabic and punctuation) and an empty file for every other range,
                               so the map never asks for a glyph file that isn't there
  SHA256SUMS
"""
import gzip, hashlib, io, os, shutil, sys, tarfile, tempfile

SOURCE_SHA256 = '58744e91961230a87617b10566f1017c8357dace4a2d71dc2a289c7379bf341a'
STACKS = ['Noto Sans Regular', 'Noto Sans Bold', 'Noto Sans Italic']
KEPT = {'0-255', '256-511', '512-767', '768-1023', '1024-1279', '1280-1535', '1536-1791', '8192-8447', '8448-8703',
        '64256-64511', '64512-64767', '64768-65023', '65024-65279'}
SPRITES = ['ofm.json', 'ofm.png', 'ofm@2x.json', 'ofm@2x.png']


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def stub(stack, rng):
    # glyphs { stacks { name: stack, range: rng } }, as the empty ranges in assets/map/fonts.
    def field(n, data): return bytes([n << 3 | 2, len(data)]) + data
    return field(1, field(1, stack.encode()) + field(2, rng.encode()))


def main(source, ofl, out):
    if sha256(source) != SOURCE_SHA256:
        sys.exit('The source pack is not the one this script was written for.')
    os.makedirs(out, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        with tarfile.open(source, 'r:gz') as t:
            t.extractall(work, filter='data')
        shutil.copyfile(os.path.join(work, 'israel.pmtiles'), os.path.join(out, 'openfreemap-israel.pmtiles'))

        entries = []
        for name in SPRITES:
            entries.append(('sprites/' + name, open(os.path.join(work, 'sprites', name), 'rb').read()))
        entries.append(('fonts/OFL.txt', open(ofl, 'rb').read()))
        for stack in STACKS:
            for start in range(0, 65536, 256):
                rng = f'{start}-{start + 255}'
                path = os.path.join(work, 'fonts', stack, rng + '.pbf')
                data = open(path, 'rb').read() if rng in KEPT else stub(stack, rng)
                entries.append((f'fonts/{stack}/{rng}.pbf', data))

        # The same bytes every time: sorted, no owners, no times.
        raw = io.BytesIO()
        with tarfile.open(fileobj=raw, mode='w', format=tarfile.USTAR_FORMAT) as t:
            folders = sorted({os.path.dirname(n) for n, _ in entries} | {'fonts', 'sprites'})
            for d in folders:
                info = tarfile.TarInfo(d)
                info.type, info.mode, info.mtime = tarfile.DIRTYPE, 0o755, 0
                t.addfile(info)
            for name, data in sorted(entries):
                info = tarfile.TarInfo(name)
                info.size, info.mode, info.mtime = len(data), 0o644, 0
                t.addfile(info, io.BytesIO(data))
        with open(os.path.join(out, 'openfreemap-assets.tar.gz'), 'wb') as f:
            with gzip.GzipFile(fileobj=f, mode='wb', compresslevel=9, mtime=0, filename='') as z:
                z.write(raw.getvalue())

    names = ['openfreemap-israel.pmtiles', 'openfreemap-assets.tar.gz']
    with open(os.path.join(out, 'SHA256SUMS'), 'w') as f:
        for n in names:
            f.write(f'{sha256(os.path.join(out, n))}  {n}\n')
    for n in names:
        p = os.path.join(out, n)
        print(f'{n}: {os.path.getsize(p)} bytes, sha256 {sha256(p)}')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
