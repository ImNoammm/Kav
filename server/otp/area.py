"""The planner for one area of Israel rather than all of it, for a server of your own on a smaller machine: the timetable
and the map prepare.py left, cut to a box. The planner's graph for all of Israel takes about 16 GB to build; one city's,
a fraction of that.

The map keeps every street that reaches into the box, whole. The timetable keeps the trips that stop at least twice in
the box, with only their stops in it, and whatever those name: their routes, shapes, stations with their entrances and
the walks inside them, transfers and translations. A trip that leaves the box and comes back keeps its times, with a
gap between.

    python3 area.py <dir> <south,west,north,east>
    python3 area.py <dir> restore

works on <dir>/israel-gtfs.zip and <dir>/israel.osm.pbf in place; the whole map is kept as <dir>/israel-whole.osm.pbf.keep
(not a .pbf to the planner, which takes in every .pbf it finds), and is put back by restore() before the next prepare.py.
Python's own library and pyosmium (pip install osmium)."""
import csv
import io
import os
import sys
import zipfile

WHOLE = "israel-whole.osm.pbf.keep"
CSV = dict(lineterminator="\n")


def box(text):
    """south,west,north,east as numbers, or ValueError."""
    south, west, north, east = (float(x) for x in text.split(","))
    if not (-90 <= south < north <= 90 and -180 <= west < east <= 180):
        raise ValueError("an area is south,west,north,east, south of north and west of east: %r" % text)
    return south, west, north, east


def restore(where):
    """The whole map back where prepare.py looks for it (its date kept, so it's fetched again on its own schedule)."""
    whole = os.path.join(where, WHOLE)
    if os.path.exists(whole):
        os.replace(whole, os.path.join(where, "israel.osm.pbf"))


def rows(z, name):
    with z.open(name) as f:
        reader = csv.reader(io.TextIOWrapper(f, "utf-8-sig", newline=""))
        header = next(reader, [])
        for row in reader:
            yield header, row


def table(z, name):
    """A table's header and its rows as dicts (for the small tables)."""
    with z.open(name) as f:
        reader = csv.DictReader(io.TextIOWrapper(f, "utf-8-sig", newline=""))
        return reader.fieldnames or [], list(reader)


def streamed(out, z, name, keep):
    """A big table copied as it's read, only the rows keep(header, row) wants: never all of it in memory."""
    with out.open(name, "w", force_zip64=True) as raw:
        f = io.TextIOWrapper(raw, "utf-8", newline="")
        w = csv.writer(f, **CSV)
        first = True
        for header, row in rows(z, name):
            if first:
                w.writerow(header)
                first = False
            if keep(header, row):
                w.writerow(row)
        f.flush()
        f.detach()


def write(out, name, header, items):
    buf = io.StringIO()
    w = csv.writer(buf, **CSV)
    w.writerow(header)
    for row in items:
        w.writerow([row.get(h, "") for h in header] if isinstance(row, dict) else row)
    out.writestr(name, buf.getvalue())


def cut_timetable(src, dst, area):
    south, west, north, east = area
    with zipfile.ZipFile(src) as z:
        names = set(z.namelist())
        stop_head, stops = table(z, "stops.txt")
        inside = set()
        for s in stops:
            try:
                lat, lon = float(s["stop_lat"]), float(s["stop_lon"])
            except (KeyError, ValueError):
                continue
            if south <= lat <= north and west <= lon <= east:
                inside.add(s["stop_id"])

        # Each trip's calls in the box: kept when there are two at least, which a rider could ride between.
        calls = {}
        for header, row in rows(z, "stop_times.txt"):
            if row[header.index("stop_id")] in inside:
                trip = row[header.index("trip_id")]
                calls[trip] = calls.get(trip, 0) + 1
        trips = {t for t, n in calls.items() if n >= 2}
        del calls

        used = set()

        def call(header, row):
            ok = row[header.index("trip_id")] in trips and row[header.index("stop_id")] in inside
            if ok:
                used.add(row[header.index("stop_id")])
            return ok
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as out:
            streamed(out, z, "stop_times.txt", call)

            trip_head, trip_rows = table(z, "trips.txt")
            trip_rows = [t for t in trip_rows if t["trip_id"] in trips]
            write(out, "trips.txt", trip_head, trip_rows)
            routes = {t["route_id"] for t in trip_rows}
            shapes = {t.get("shape_id") for t in trip_rows} - {"", None}
            route_head, route_rows = table(z, "routes.txt")
            write(out, "routes.txt", route_head, [r for r in route_rows if r["route_id"] in routes])

            # The stops ridden from, their stations, and everything else in those stations (entrances, the nodes and
            # platforms the walks inside them go through).
            by_id = {s["stop_id"]: s for s in stops}
            stations = {by_id[s].get("parent_station") for s in used if s in by_id} - {"", None}
            keep = used | stations | {s["stop_id"] for s in stops if s.get("parent_station") in stations}
            write(out, "stops.txt", stop_head, [s for s in stops if s["stop_id"] in keep])

            stops_or_none, trips_or_none, routes_or_none = keep | {""}, trips | {""}, routes | {""}
            for name in sorted(names):
                if name in ("stop_times.txt", "trips.txt", "routes.txt", "stops.txt"):
                    continue
                if name == "shapes.txt":
                    streamed(out, z, name, lambda header, row: row[header.index("shape_id")] in shapes)
                elif name == "pathways.txt":
                    head, items = table(z, name)
                    write(out, name, head, [p for p in items if p["from_stop_id"] in keep and p["to_stop_id"] in keep])
                elif name == "transfers.txt":
                    head, items = table(z, name)
                    write(out, name, head, [x for x in items if x.get("from_stop_id", "") in stops_or_none
                                            and x.get("to_stop_id", "") in stops_or_none
                                            and x.get("from_trip_id", "") in trips_or_none
                                            and x.get("to_trip_id", "") in trips_or_none])
                elif name == "frequencies.txt":
                    head, items = table(z, name)
                    write(out, name, head, [x for x in items if x["trip_id"] in trips])
                elif name == "translations.txt":
                    head, items = table(z, name)
                    ids = {"stops": stops_or_none, "routes": routes_or_none, "trips": trips_or_none}
                    write(out, name, head, [x for x in items if x.get("table_name") not in ids
                                            or x.get("record_id", "") in ids[x["table_name"]]])
                elif name == "fare_rules.txt":
                    head, items = table(z, name)
                    write(out, name, head, [x for x in items if x.get("route_id", "") in routes_or_none])
                else:
                    out.writestr(name, z.read(name))
    return {"stops": len(keep), "trips": len(trips), "routes": len(routes)}


def cut_map(where, area):
    """The map cut to the box, every street that reaches into it whole, as osmium's complete_ways cuts it, in two passes
    of pyosmium (as walks.py uses it): what it keeps grows with the area, not with OpenStreetMap's ids, which took
    `osmium extract` near 4 GB for any part of Israel."""
    import osmium

    south, west, north, east = area
    whole, osm = os.path.join(where, WHOLE), os.path.join(where, "israel.osm.pbf")
    if not os.path.exists(whole):
        os.replace(osm, whole)
    source = osmium.io.File(whole, "pbf")  # its name says nothing of its format
    inside, nodes, ways, relations = set(), set(), set(), set()
    for o in osmium.FileProcessor(source):  # nodes, then ways, then relations, as a .pbf is sorted
        if o.is_node():
            if o.location.valid() and south <= o.location.lat <= north and west <= o.location.lon <= east:
                inside.add(o.id)
        elif o.is_way():
            refs = [n.ref for n in o.nodes]
            if any(r in inside for r in refs):
                ways.add(o.id)
                nodes.update(refs)
        elif o.is_relation():
            if any((m.type == "w" and m.ref in ways) or (m.type == "n" and m.ref in inside) for m in o.members):
                relations.add(o.id)
    nodes |= inside
    del inside
    with osmium.SimpleWriter(osmium.io.File(osm + ".part", "pbf"), overwrite=True) as w:
        for o in osmium.FileProcessor(source):
            if o.is_node() and o.id in nodes:
                w.add_node(o)
            elif o.is_way() and o.id in ways:
                w.add_way(o)
            elif o.is_relation() and o.id in relations:
                w.add_relation(o)
    os.replace(osm + ".part", osm)


def main(where, text):
    area = box(text)
    gtfs = os.path.join(where, "israel-gtfs.zip")
    cut = cut_timetable(gtfs, gtfs + ".part", area)
    os.replace(gtfs + ".part", gtfs)
    print("timetable cut to the area:", cut, flush=True)
    cut_map(where, area)
    print("map cut to the area: %d MB" % (os.path.getsize(os.path.join(where, "israel.osm.pbf")) // 1000000), flush=True)


if __name__ == "__main__":
    if sys.argv[2:] == ["restore"]:
        restore(sys.argv[1])
    else:
        main(sys.argv[1], sys.argv[2])
