"""Roads for the trains, which the ministry's feed has none of. The rail part of the planner's timetable is matched to
OpenStreetMap's tracks by pfaedle (rebuild.sh), and each route's road goes to <realtime>/rail-shapes.json, which
server/live.py serves with the rest.

    python3 rail_shapes.py cut <dir>                <dir>/israel-gtfs.zip -> <dir>/rail.zip, the trains alone
    python3 rail_shapes.py write <dir> <realtime>   <dir>/gtfs-out, pfaedle's answer -> <realtime>/rail-shapes.json
"""
import collections
import csv
import io
import json
import math
import os
import sys
import zipfile

RAIL = "2"
# A stretch between two stations the match takes this much longer than the straight line, and kilometres more, went
# round a gap in OpenStreetMap's tracks (one under works is left out): it's drawn straight, as the feed would have it.
DETOUR = 1.6
DETOUR_M = 3000


def cut(where):
    """The rail routes of <where>/israel-gtfs.zip, with their trips, stops and calendars, as <where>/rail.zip."""
    with zipfile.ZipFile(os.path.join(where, "israel-gtfs.zip")) as src:
        def rows(name):
            with src.open(name) as f:
                yield from csv.reader(io.TextIOWrapper(f, encoding="utf-8-sig"))

        routes = {r[0] for r in rows("routes.txt") if r[5] == RAIL}
        it = rows("trips.txt")
        head = next(it)
        route, trip, service = (head.index(k) for k in ("route_id", "trip_id", "service_id"))
        trips, services = set(), set()
        for r in it:
            if r[route] in routes:
                trips.add(r[trip])
                services.add(r[service])
        it = rows("stop_times.txt")
        head = next(it)
        stops = {r[head.index("stop_id")] for r in it if r[head.index("trip_id")] in trips}
        it = rows("stops.txt")
        head = next(it)
        stops |= {r[head.index("parent_station")] for r in it if r[0] in stops and r[head.index("parent_station")]}

        keep = {"agency.txt": lambda h, r: True, "routes.txt": lambda h, r: r[0] in routes,
                "trips.txt": lambda h, r: r[h.index("trip_id")] in trips,
                "stop_times.txt": lambda h, r: r[h.index("trip_id")] in trips, "stops.txt": lambda h, r: r[0] in stops,
                "calendar.txt": lambda h, r: r[0] in services, "calendar_dates.txt": lambda h, r: r[0] in services}
        with zipfile.ZipFile(os.path.join(where, "rail.zip.part"), "w", zipfile.ZIP_DEFLATED) as dst:
            for name, wanted in keep.items():
                if name not in src.namelist():
                    continue
                out = io.StringIO()
                w = csv.writer(out)
                it = rows(name)
                head = next(it)
                w.writerow(head)
                w.writerows(r for r in it if wanted(head, r))
                dst.writestr(name, out.getvalue())
    os.replace(os.path.join(where, "rail.zip.part"), os.path.join(where, "rail.zip"))
    return {"routes": len(routes), "trips": len(trips)}


def metres(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def road(path, stations):
    """The trip's road from its stations' match along path, with any stretch that went round a gap drawn straight."""
    at, out, straightened = 0, [], 0
    ends = []
    for s in stations:
        at = min(range(at, len(path)), key=lambda i: metres(s, path[i]))
        ends.append(at)
    for (a, b), (i, j) in zip(zip(stations, stations[1:]), zip(ends, ends[1:])):
        part = path[i:j + 1]
        along = sum(metres(p, q) for p, q in zip(part, part[1:]))
        direct = metres(a, b)
        if along > DETOUR * direct and along - direct > DETOUR_M:
            part = [a, b]
            straightened += 1
        out += part if not out else part[1:]
    return out, straightened


def table(folder, name):
    with open(os.path.join(folder, name), encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def write(where, realtime):
    """Each rail route's road, its most common trip's, in <realtime>/rail-shapes.json: {"roads": [[[lat, lon], ...], ...],
    "routes": {route id: its road's index}}, as most routes share a road with others."""
    folder = os.path.join(where, "gtfs-out")
    paths = collections.defaultdict(list)
    for r in table(folder, "shapes.txt"):
        paths[r["shape_id"]].append((int(r["shape_pt_sequence"]), float(r["shape_pt_lat"]), float(r["shape_pt_lon"])))
    paths = {k: [(lat, lon) for _, lat, lon in sorted(v)] for k, v in paths.items()}
    stops = {r["stop_id"]: (float(r["stop_lat"]), float(r["stop_lon"])) for r in table(folder, "stops.txt")}
    calls = collections.defaultdict(list)
    for r in table(folder, "stop_times.txt"):
        calls[r["trip_id"]].append((int(r["stop_sequence"]), r["stop_id"]))
    trips = collections.defaultdict(collections.Counter)
    one = {}
    for t in table(folder, "trips.txt"):
        if t["shape_id"] in paths:
            trips[t["route_id"]][t["shape_id"]] += 1
            one.setdefault(t["shape_id"], t["trip_id"])
    roads, routes, straightened = {}, {}, 0
    for route, shapes in trips.items():
        shape = shapes.most_common(1)[0][0]
        stations = [stops[s] for _, s in sorted(calls[one[shape]]) if s in stops]
        points, n = road(paths[shape], stations)
        straightened += n
        # Five decimals, as the app's roads are sent (about a metre).
        key = tuple((round(lat, 5), round(lon, 5)) for lat, lon in points)
        routes[route] = roads.setdefault(key, len(roads))
    path = os.path.join(realtime, "rail-shapes.json")
    with open(path + ".part", "w") as f:
        json.dump({"roads": [list(map(list, r)) for r in roads], "routes": routes}, f, separators=(",", ":"))
    os.replace(path + ".part", path)
    return {"routes": len(routes), "roads": len(roads), "stretches drawn straight": straightened}


if __name__ == "__main__":
    if sys.argv[1] == "cut":
        print("rail for matching:", cut(sys.argv[2]), flush=True)
    else:
        print("rail shapes:", write(sys.argv[2], sys.argv[3]), flush=True)
