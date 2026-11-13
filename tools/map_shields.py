#!/usr/bin/env python3
# Where the map draws road numbers: fixed points along each numbered road, in levels, as other maps place them. Taken
# from the tiles, a number was placed again with each zoom's tiles and moved about; these points never move, and each
# zoom only adds the next level between the ones already shown, so a number stays about a hand's width from the next
# one of its road on screen.
#
# usage: python3 tools/map_shields.py dist/map/israel.pmtiles android/app/src/main/assets/map/shields.geojson
# needs: pip install pmtiles mapbox-vector-tile
import gzip, json, math, sys
from pmtiles.reader import Reader, MmapSource, all_tiles
import mapbox_vector_tile as mvt

Z = 14
# The classes MapShields colours, and the zoom each may show from at the earliest.
CLASSES = {"motorway": ("freeway", 8), "trunk": ("main", 8), "primary": ("regional", 11), "secondary": ("local", 12)}
# One number every this many metres along a road from this zoom on (MapLibre zoom, 512 px tiles: about 250 px apart).
LEVELS = [(16000, 10), (8000, 11), (4000, 12), (2000, 13), (1000, 14)]
STEP = 250  # metres between the candidate points sampled along each road


def lonlat(tx, ty, x, y, extent):
    n = 2 ** Z
    lon = (tx + x / extent) / n * 360 - 180
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (ty + 1 - y / extent) / n))))
    return lon, lat


def metres(a, b):
    k = math.cos(math.radians((a[1] + b[1]) / 2)) * 111320
    return math.hypot((a[0] - b[0]) * k, (a[1] - b[1]) * 110540)


def main(src, out):
    r = Reader(MmapSource(open(src, "rb")))
    candidates = {}  # (ref, kind) -> [(lon, lat)]
    for (z, x, y), data in all_tiles(r.get_bytes):
        if z != Z:
            continue
        layer = mvt.decode(gzip.decompress(data), default_options={"y_coord_down": False}).get("roads")
        if not layer:
            continue
        extent = layer.get("extent", 4096)
        for f in layer["features"]:
            p = f["properties"]
            ref, cls = str(p.get("ref") or ""), p.get("kind_detail")
            if cls not in CLASSES or not ref or ";" in ref or len(ref) > 4 or p.get("is_link"):
                continue
            g = f["geometry"]
            lines = g["coordinates"] if g["type"] == "MultiLineString" else [g["coordinates"]] if g["type"] == "LineString" else []
            for line in lines:
                pts = [lonlat(x, y, cx, cy, extent) for cx, cy in line]
                # A point every STEP metres along the line, from its start.
                carry = 0.0
                for a, b in zip(pts, pts[1:]):
                    d = metres(a, b)
                    t = STEP - carry
                    while t <= d:
                        u = t / d
                        candidates.setdefault((ref, cls), []).append((a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u))
                        t += STEP
                    carry = (carry + d) % STEP
    features = []
    for (ref, cls), pts in sorted(candidates.items()):
        kind, earliest = CLASSES[cls]
        chosen = []  # (lon, lat, zoom)
        for spacing, zoom in LEVELS:
            for p in pts:
                if all(metres(p, (c[0], c[1])) >= spacing for c in chosen):
                    chosen.append((p[0], p[1], zoom))
        for lon, lat, zoom in chosen:
            features.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [round(lon, 5), round(lat, 5)]},
                             "properties": {"ref": ref, "kind": kind, "z": max(zoom, earliest)}})
    # Coarser levels first: the map places a layer's markers in the file's order, so one that's there stays as more
    # come in around it. A sort key on the layer did that too, but had the map draw each marker on its own.
    features.sort(key=lambda f: f["properties"]["z"])
    json.dump({"type": "FeatureCollection", "features": features}, open(out, "w"), separators=(",", ":"), ensure_ascii=False)
    print(f"{len(features)} points for {len(candidates)} roads -> {out}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
