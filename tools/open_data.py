"""Kav's open data: Israel's stops, lines, their roads and the official fares, as Kav reads them, in plain CSV, GeoJSON
and JSON, gzipped, for anyone to use without Kav's code. site/docs/data.md describes every file and its licence; the
nightly workflow (.github/workflows/data.yml) builds them and puts them on the "data" release, and Kav's site serves
them at kavapp.link/data/v1/.

    python3 tools/open_data.py
    python3 tools/open_data.py check <folder> [<last night's manifest.json> <last night's stops.csv.gz>]

The second form is the gate the workflow passes before anything is published (check()). It is there to catch Kav's own
pipeline or a broken feed, not to second-guess the ministry, whose stop positions the files copy as they are. It checks:
  - the manifest: every field of its type, in its range and of its shape, by the one list of rules Kav's site applies
    too (site/scripts/data-pull.sh, at shape(); the gate runs its --check-manifest);
  - each file: matching size and sha256, measured rows matching the manifest and above the floor, and its columns;
  - both GeoJSON files: parsed FeatureCollections of LineStrings with finite coordinate pairs;
  - against last night's published manifest: no file under 90% of last night's rows or bytes;
  - the files together: every route, operator, road and fare they name found, the roads covering 95% of the ministry's
    lines and 95% of its trains, 75% of stops called at by some line;
  - every stop inside Israel and the West Bank: within 2 km of Natural Earth's outline (OUTLINE, below);
  - against last night's stops.csv.gz: no stop moved more than 1 km, and no more than 0.1% of the stops more than 50 m
    (the ministry's own feed, night to night from 16 Jul to 13 Nov 2026, moved one stop 751 m at most and never more
    than 0.026% of them over 50 m: FLOORS).
Without last night's manifest and stops it publishes nothing, but the first night or a night run by hand with
KAV_DATA_FIRST_RUN; KAV_DATA_ACCEPT_DROP lets a real drop or move through, seen by a person. It does not check that
the ministry's data is right: a stop the ministry itself puts in the wrong place within those bounds, a wrong fare or a
wrong timetable pass, as do changes smaller than the bounds.

Settings come from the environment:
  KAV_GTFS         the ministry's timetable (default .cache/israel-gtfs.zip, where tools/fetch.sh leaves it)
  KAV_NAIM         Tel Aviv's weekend lines (default .cache/naim-gtfs.zip; left out when it isn't there)
  KAV_RAIL_SHAPES  the trains' roads matched to OpenStreetMap, server/otp/rail_shapes.py's rail-shapes.json
                   (rail-roads.geojson.gz is left out without it)
  KAV_EXTRA        JSON made by tools/open_data_extra.py from the server routines and saved timetables
  KAV_OUT          where the files go (default .cache/data)
  KAV_OSM_SHA256   the sha256 of the OpenStreetMap extract the train roads were matched on, for the manifest
  KAV_DATA_ACCEPT_DROP  1 to let check pass a night far below the one before, or with stops moved far (a real change,
                   seen by a person)
  KAV_DATA_FIRST_RUN    1 to let check pass a night with no published night before it to compare with

Only the standard library, and jq for the manifest's rules (above). The timetable is read with the server's own code
(server/live.py, server/otp/prepare.py), so a stop, a line or a fare here is the one Kav's app and server show.
"""
import csv
import datetime
import gzip
import hashlib
import io
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import zipfile
import zoneinfo

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path[:0] = [os.path.join(HERE, "server"), os.path.join(HERE, "server", "otp")]
import open_data_source as live  # noqa: E402
import prepare  # noqa: E402

VERSION = 1  # the files' layout: a change that breaks it goes to /data/v2/ and the release "data-v2"
ISRAEL = zoneinfo.ZoneInfo("Asia/Jerusalem")
# As tools/export_web_bundle.py reads stop_desc: the city and the platform of a bus station's stop.
CITY_RE = re.compile(r"עיר:\s*(.*?)\s*(?:רציף:|קומה:|$)")
PLATFORM_RE = re.compile(r"רציף:\s*([^\s:]+)(?=\s|$)")
STREET_RE = re.compile(r"רחוב:\s*(.*?)\s*(?:עיר:|$)")
NAIM_NAME = "נעים בסופ״ש"  # the weekend lines' operator, as the app names it (data/Search.kt NAIM_NAME)
# Kav's own kinds of line (server/live.py's route_line()), in words.
KINDS = {0: "light_rail", 2: "train", 3: "bus", 5: "cable_car", 7: "funicular", 8: "share_taxi", 711: "shuttle",
         715: "on_demand_bus"}
# The fare ids of the ministry's tables as server/live.py reads them: by bus, light rail or cable car (bus) or by train,
# and the distance each is for. 22x are to and from Eilat, without VAT. 206 and 226 are further than 225 km, which the
# price control order prices at 225 km's fare to Eilat's area however far, so Kav prices none with them.
FARE_KM = {"201": 15, "202": 40, "204": 120, "205": 225, "206": None, "211": 15, "212": 40, "213": 75, "214": 120,
           "215": 225, "221": 15, "222": 40, "224": 120, "225": 225, "226": None}
FARE_RULES = [
    "Fares are the Ministry of Transport's tables (fare_attributes.txt and fare_rules.txt in its GTFS), priced as the "
    "price control order has them: צו פיקוח על מחירי מצרכים ושירותים (מחירי נסיעה בקווי שירות באוטובוסים ומחירי נסיעה "
    "ברכבת מקומית), תשפ\"ב-2022, first schedule, items 2 and 4.",
    "A ride's fare is the one fares.csv gives for the stop codes it is boarded and left at. Some lines have one fare for "
    "the whole line instead (route_fares here), which counts where the stops have none.",
    "The fare up to 15 km (201, or 221 to and from Eilat) includes changing to more buses, light rail or cable car rides "
    "boarded within 90 minutes of validating it, as long as each stays within 15 km of where the fare was first boarded. "
    "Trains are a fare of their own and end that.",
    "Paying in an app, a day pass costs less where it is less than the day's single fares (item 6, the most efficient "
    "combination). Which day pass is set by the farthest ride of the day; 'bus' passes cover buses, light rail and cable "
    "cars, 'rail' passes trains too. An app's day starts at 04:00.",
    "The prices are the regular full fares. Discounts by the rider's profile (students, senior citizens and others) "
    "are not in these files: the ministry sets who is entitled to which.",
    "unsure_stops: stops whose position today puts one of their pairs on the wrong side of 15 km by more than 0.5 km, so "
    "they moved after their fares were set; Kav doesn't trust distances worked out from them.",
]
SOURCES = {
    "mot-busy": {"name": "Israel Ministry of Transport, boarding counts", "url": "https://data.gov.il/dataset/tikufim-2021",
                 "terms": "https://data.gov.il/terms-of-use"},
    "mot-fleet": {"name": "Israel Ministry of Transport, bus fleet", "url": "https://data.gov.il/dataset/bus_fleet",
                  "terms": "https://data.gov.il/terms-of-use"},
    "mot-gtfs": {"name": "Israel Ministry of Transport, GTFS timetable", "url": live.GTFS_URL,
                 "terms": "https://www.gov.il/he/pages/gtfs_general_transit_feed_specifications"},
    "tel-aviv-weekend": {"name": "Tel Aviv-Yafo Municipality, weekend lines (Na'im BeSofash) GTFS", "url": prepare.NAIM_URL,
                         "terms": "https://opendatasource.tel-aviv.gov.il/en/Pages/FAQ.aspx"},
    "osm": {"name": "OpenStreetMap, railway tracks (Geofabrik's israel-and-palestine extract)", "url": prepare.OSM_URL,
            "terms": "https://www.openstreetmap.org/copyright"},
    "kav": {"name": "Kav (its reading of the ministry's tables, and its operators' English names)",
            "url": "https://github.com/ImNoammm/kav"},
    "kav-colours": {"name": "The operators' colours Kav's app draws them in, by Sammy Zaionz (ui/Operators.kt)",
                    "url": "https://github.com/szaionz"},
}
# Each file's credit is its own sources', then Kav's, which publishes it (put()): only the train roads hold
# OpenStreetMap's data.
CREDITS = {"mot-busy": "Israel Ministry of Transport, data.gov.il", "mot-fleet": "Israel Ministry of Transport, data.gov.il","mot-gtfs": "Israel Ministry of Transport", "tel-aviv-weekend": "Tel Aviv-Yafo Municipality",
           "osm": "© OpenStreetMap contributors (https://www.openstreetmap.org/copyright)",
           "kav-colours": "operator colours by Sammy Zaionz", "kav": "Kav (kavapp.link)"}
LICENCES = {
    "data-gov-il": {"name": "Data.gov.il database use licence", "url": "https://data.gov.il/terms-of-use"},
    "CC-BY-4.0": {"name": "Creative Commons Attribution 4.0", "url": "https://creativecommons.org/licenses/by/4.0/"},
    "ODbL-1.0": {"name": "Open Database License 1.0", "url": "https://opendatacommons.org/licenses/odbl/1-0/"},
    "GPL-3.0": {"name": "GNU General Public License 3.0", "url": "https://www.gnu.org/licenses/gpl-3.0.html"},
}
# The colours are Sammy Zaionz's contribution to Kav's code, which is GPL-3.0: they keep that licence in the files.
COLOUR_NOTE = ("The color column is the operator and line colours from Kav's source code, contributed by Sammy Zaionz, "
               "and is under that code's licence, GPL-3.0; the rest of this file is under CC BY 4.0.")


def table(z, name):
    """A GTFS table's rows as dicts; none when the zip hasn't it."""
    if name not in z.namelist():
        return []
    with io.TextIOWrapper(z.open(name), encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def translations(z):
    """{"EN": {Hebrew: English}, "AR": {...}} from the ministry's translations.txt, in its layout from before the standard."""
    found = {"EN": {}, "AR": {}}
    for r in table(z, "translations.txt"):
        if r["lang"] in found and r["translation"].strip():
            found[r["lang"]][r["trans_id"].strip()] = r["translation"].strip()
    return found


def translated(names, he):
    """A Hebrew name in the other language, "" for none. translations.txt writes gershayim as " where stops.txt has two
    apostrophes (as tools/export_web_bundle.py's in_english())."""
    return names.get(he) or names.get(he.replace("''", '"')) or ""


def app_colours(root=HERE):
    """The colours the app draws lines in, read from its own code so they never drift: {agency id: (English name, hex)}
    from ui/Operators.kt (the operators' colours are Sammy Zaionz's), and {plate: hex} from ui/Common.kt's mode plates
    (Tram, Rail, Taxi, Carmelit, Rakavlit)."""
    ui = os.path.join(root, "android", "app", "src", "main", "java", "uk", "noammm", "kav", "ui")
    with open(os.path.join(ui, "Operators.kt"), encoding="utf-8") as f:
        operators = {int(a): (None if en == "null" else en.strip('"'), argb[2:])
                     for a, en, argb in re.findall(r'Operator\((\d+), "[^"]*", (null|"[^"]*"), 0x([0-9A-Fa-f]{8})\)', f.read())}
    with open(os.path.join(ui, "Common.kt"), encoding="utf-8") as f:
        plates = dict(re.findall(r"private val (\w+)Plate = ModePlate\(Color\(0xFF([0-9A-Fa-f]{6})\)", f.read()))
    return operators, plates


def hued(hex6):
    """Whether the colour has a hue at all (ui/Operators.kt's hued()): the app keeps its plain plate for black and grey."""
    rgb = [int(hex6[i:i + 2], 16) for i in (0, 2, 4)]
    return max(rgb) - min(rgb) > .15 * 255


def line_colour(kind, agency, operators, plates):
    """The colour of a line's plate in the app (ui/Common.kt's linePlate()) before it is lifted or deepened for a light or
    dark screen: its mode's for trains, light rail, share taxis, the Carmelit and cable cars, else its operator's, else
    none."""
    plate = ("Rail" if kind == 2 else "Tram" if kind in (0, 12) else "Taxi" if kind in (8, 715) else
             "Carmelit" if kind == 7 else "Rakavlit" if kind in (5, 6) else None)
    if plate:
        return plates[plate]
    colour = operators.get(agency, (None, ""))[1].upper()
    return colour if colour and hued(colour) else ""


def serving(z, trip_route, naim):
    """{stop id: {route id}}: every route a trip of which calls at the stop, in the ministry's timetable and the weekend
    lines'."""
    found, last, at = {}, None, None
    with io.TextIOWrapper(z.open("stop_times.txt"), encoding="utf-8-sig", newline="") as f:
        rows = csv.reader(f)
        head = next(rows)
        trip, stop = head.index("trip_id"), head.index("stop_id")
        for r in rows:
            if r[trip] != last:
                last, at = r[trip], trip_route.get(r[trip])
            if at is not None:
                found.setdefault(r[stop], set()).add(at)
    naim_route = {t["trip_id"]: t["route_id"] for t in naim.get("trips.txt", [])}
    for s in naim.get("stop_times.txt", []):
        if s["trip_id"] in naim_route:
            found.setdefault(s["stop_id"], set()).add(naim_route[s["trip_id"]])
    return found


def by_id(value):
    return (0, int(value), "") if value.isdigit() else (1, 0, value)


def stop_rows(stops, naim, routes_at, names):
    """stops.csv: each of the ministry's stops and stations, and the weekend lines' stops it hasn't, where their own
    timetable puts them (not the planner's corrections, server/otp/prepare.py's STOP_POINTS, which come from
    OpenStreetMap)."""
    rows = []
    for r in stops + naim.get("stops.txt", []):
        lat, lon = float(r["stop_lat"]), float(r["stop_lon"])
        desc = r.get("stop_desc") or ""
        city = (CITY_RE.search(desc) or [None, ""])[1].strip()
        street = (STREET_RE.search(desc) or [None, ""])[1].strip()
        name = r["stop_name"].strip()
        rows.append([r["stop_id"], r["stop_code"], name, translated(names["EN"], name), translated(names["AR"], name),
                     street, city, translated(names["EN"], city), (PLATFORM_RE.search(desc) or [None, ""])[1],
                     "%.6f" % lat, "%.6f" % lon, r.get("location_type") or "0", r.get("parent_station") or "",
                     " ".join(sorted(routes_at.get(r["stop_id"], ()), key=by_id))])
    return sorted(rows, key=lambda row: by_id(row[0]))


def route_rows(routes, agency_names, headsigns, names, operators, plates):
    """routes.csv: each of the ministry's routes (a line one way, one variant) and the weekend lines', as Kav's server
    types them (route_line()), with the colour Kav draws each in, last."""
    rows = []
    for r in routes:
        number, to, to_city, origin, from_city, agency, kind, long_name = live.route_line(
            r["agency_id"], r["route_short_name"], r["route_long_name"], int(r["route_type"]))
        desc = (r.get("route_desc") or "").split("-")
        line_id, direction, variant = (desc + ["", ""])[:3] if len(desc) == 3 else (desc[0], "", "")
        rows.append([r["route_id"], str(agency), agency_names.get(r["agency_id"], ""), number, long_name, origin, from_city,
                     to, to_city, translated(names["EN"], origin), translated(names["EN"], from_city),
                     translated(names["EN"], to), translated(names["EN"], to_city), headsigns.get(r["route_id"], ""),
                     line_id, direction, variant, str(kind), KINDS.get(kind, ""), (r.get("route_color") or "").upper(),
                     line_colour(kind, agency, operators, plates)])
    return sorted(rows, key=lambda row: by_id(row[0]))


def common_headsigns(trips):
    """{route id: the destination its trips show most often}."""
    counts = {}
    for t in trips:
        sign = (t.get("trip_headsign") or "").strip()
        if sign:
            seen = counts.setdefault(t["route_id"], {})
            seen[sign] = seen.get(sign, 0) + 1
    return {route: max(sorted(seen), key=seen.get) for route, seen in counts.items()}


def road_features(paths, lines):
    """GeoJSON features, one per road ({route id: encoded polyline}), with every route whose trips most often take it."""
    shared = {}
    for route, path in paths.items():
        shared.setdefault(path, []).append(route)
    features = []
    for path, routes in shared.items():
        routes.sort()
        line = lines.get(routes[0]) or [""] * 8
        features.append({"type": "Feature", "properties": {
            "route_ids": [str(r) for r in routes], "number": line[0], "agency_id": line[5], "type": line[6]},
            "geometry": {"type": "LineString", "coordinates": [[lon, lat] for lat, lon in live.decode(path)]}})
    features.sort(key=lambda f: int(f["properties"]["route_ids"][0]))
    return features


def geojson(features):
    return json.dumps({"type": "FeatureCollection", "features": features}, ensure_ascii=False,
                      separators=(",", ":")).encode()


def fare_rows(fares):
    """fares.csv: every stop pair the ministry gives a fare, from live.fares_from()'s packed pairs."""
    rows = []
    for packed in fares["pairs"]:
        pair, fare = divmod(packed, live.FARE_IDS)
        origin, to = divmod(pair, live.FARE_PAIR)
        fare_id = fares["ids"][fare]
        rows.append([str(origin), str(to), fare_id, str(fares["price"][fare_id])])
    return rows


def fares_doc(fares):
    """fares.json.gz: the fare ids with their prices, the whole-line fares, the day passes and the rules."""
    def kind(fare_id):
        return "bus" if fare_id in live.FARE_BUS or fare_id in ("206", "226") else "rail" if fare_id in live.FARE_RAIL else ""
    return {
        "version": VERSION, "currency": "ILS", "unit": "agorot",
        "products": [{"fare_id": f, "price_agorot": fares["price"][f], "kind": kind(f), "up_to_km": FARE_KM.get(f),
                      "eilat_without_vat": f.startswith("22"), "includes_changes_minutes":
                      live.TRANSFER_WINDOW // 60 if f in live.FARE_NEAR else 0, "used_by_kav": f in live.FARE_BUS | live.FARE_RAIL}
                     for f in fares["ids"]],
        "route_fares": {str(r): f for r, f in sorted(fares["routes"].items()) if f},
        "day_passes": {k: [{"up_to_km": km, "price_agorot": p} for km, p in zip((15, 40, 75, 120, 225), v)]
                       for k, v in live.DAY_PASS.items()},
        "app_day_starts": "%02d:00" % (live.APP_DAY_FROM // 3600),
        "unsure_stops": sorted(str(c) for c in fares["unsure"]),
        "rules": FARE_RULES,
    }


def gz(data):
    """gzip with no name or time in it, so the same data makes the same file (and checksum) every night."""
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb", compresslevel=9, mtime=0) as f:
        f.write(data)
    return out.getvalue()


def csv_bytes(header, rows):
    out = io.StringIO()
    w = csv.writer(out, lineterminator="\n")
    w.writerow(header)
    w.writerows(rows)
    return out.getvalue().encode()


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


STOP_FIELDS = ["stop_id", "code", "name_he", "name_en", "name_ar", "street_he", "city_he", "city_en", "platform", "lat",
               "lon", "location_type", "parent_station", "route_ids"]
ROUTE_FIELDS = ["route_id", "agency_id", "operator_he", "number", "name_he", "from_he", "from_city_he", "to_he",
                "to_city_he", "from_en", "from_city_en", "to_en", "to_city_en", "headsign_he", "mot_line_id", "direction",
                "variant", "type", "kind", "mot_color", "color"]
OPERATOR_FIELDS = ["agency_id", "name_he", "name_en", "url", "phone", "color"]
FARE_FIELDS = ["from_code", "to_code", "fare_id", "price_agorot"]


BUSY_FIELDS = ["route_id", "direction", "day", "hour", "level"]
FLEET_FIELDS = ["plate", "size", "seats", "year", "power"]


def build(gtfs, out, naim_path=None, rail_shapes=None, root=HERE, osm_sha256=None):
    """Writes the files and manifest.json into out; returns the manifest."""
    os.makedirs(out, exist_ok=True)
    operators, plates = app_colours(root)
    files, sources = [], {}

    def put(name, data, rows, licence, used, changed, note=None):
        path = os.path.join(out, name)
        with open(path + ".part", "wb") as f:
            f.write(data)
        os.replace(path + ".part", path)
        files.append({"name": name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "rows": rows,
                      "licence": licence, "credit": "; ".join([CREDITS[u] for u in used if u != "kav"] + [CREDITS["kav"]]),
                      "sources": used,
                      "changed": changed, **({"licence_note": note} if note else {})})

    with zipfile.ZipFile(gtfs) as z:
        published = max(datetime.datetime(*i.date_time) for i in z.infolist())
        calendar = table(z, "calendar.txt")
        sources["mot-gtfs"] = dict(SOURCES["mot-gtfs"], sha256=sha256_of(gtfs), published=published.isoformat(),
                                   **({"from": min(c["start_date"] for c in calendar), "to": max(c["end_date"] for c in calendar)}
                                      if calendar else {}))
        names = translations(z)
        with io.TextIOWrapper(z.open("stops.txt"), encoding="utf-8-sig", newline="") as f:
            listed = list(csv.reader(f))
        naim = {}
        if naim_path and os.path.exists(naim_path):
            naim = prepare.naim_feed(naim_path, listed[0], listed[1:])
            for a in naim["agency.txt"]:
                a["agency_name"] = NAIM_NAME
            sources["tel-aviv-weekend"] = dict(SOURCES["tel-aviv-weekend"], sha256=sha256_of(naim_path))
        weekend = ["tel-aviv-weekend"] if naim else []
        stops = [dict(zip(listed[0], r)) for r in listed[1:]]
        trips = table(z, "trips.txt")
        routes = table(z, "routes.txt") + naim.get("routes.txt", [])
        agencies = table(z, "agency.txt") + naim.get("agency.txt", [])
        agency_names = {a["agency_id"]: a["agency_name"].strip() for a in agencies}

        rows = stop_rows(stops, naim, serving(z, {t["trip_id"]: t["route_id"] for t in trips}, naim), names)
        put("stops.csv.gz", gz(csv_bytes(STOP_FIELDS, rows)), len(rows), "CC-BY-4.0", ["mot-gtfs"] + weekend + ["kav"],
            "English and Arabic names from the ministry's translations; city, street and platform taken out of stop_desc; "
            "the routes that call at each stop.")
        rows = route_rows(routes, agency_names, common_headsigns(trips + naim.get("trips.txt", [])), names, operators, plates)
        lines = {int(r[0]): [r[3], "", "", "", "", int(r[1]), int(r[17]), r[4]] for r in rows}
        put("routes.csv.gz", gz(csv_bytes(ROUTE_FIELDS, rows)), len(rows), "CC-BY-4.0",
            ["mot-gtfs"] + weekend + ["kav-colours", "kav"], "Each route's ends read out of its name, the Carmelit and "
            "shuttles given kinds of their own, the ministry's line id, direction and variant taken out of route_desc, "
            "and the colour the app draws the line in.", COLOUR_NOTE)
        rows = sorted(([a["agency_id"], a["agency_name"].strip(),
                        operators.get(int(a["agency_id"]), (None,))[0] or translated(names["EN"], a["agency_name"].strip()),
                        a.get("agency_url") or "", a.get("agency_phone") or "",
                        operators.get(int(a["agency_id"]), (None, ""))[1].upper()] for a in agencies),
                       key=lambda row: by_id(row[0]))
        put("operators.csv.gz", gz(csv_bytes(OPERATOR_FIELDS, rows)), len(rows), "CC-BY-4.0",
            ["mot-gtfs"] + weekend + ["kav-colours", "kav"], "English names and each operator's colour, as the app has them.",
            COLOUR_NOTE)

        paths = live.shapes_from(z)
        if naim:
            with zipfile.ZipFile(naim_path) as n:
                paths.update({live.NAIM_OFFSET + r: p for r, p in live.shapes_from(n).items()})
        features = road_features(paths, lines)
        put("roads.geojson.gz", gz(geojson(features)), len(features), "CC-BY-4.0", ["mot-gtfs"] + weekend,
            "The road each route's trips most often take (shapes.txt), one feature per road with every route on it, "
            "to five decimals (about a metre).")

        fares = live.fares_from(z)
        rows = fare_rows(fares)
        put("fares.csv.gz", gz(csv_bytes(FARE_FIELDS, rows)), len(rows), "CC-BY-4.0", ["mot-gtfs"],
            "The stop pairs of fare_rules.txt with each fare's price; rows naming a route or a fare in another currency "
            "left out, as Kav's server reads them.")
        doc = fares_doc(fares)
        put("fares.json.gz", gz(json.dumps(doc, ensure_ascii=False, indent=1).encode()), len(doc["products"]), "CC-BY-4.0",
            ["mot-gtfs", "kav"], "Kav's reading of the price control order: what each fare id is for, the day passes "
                                 "and the rules, beside the ministry's prices.")

    if rail_shapes and os.path.exists(rail_shapes):
        with open(rail_shapes, encoding="utf-8") as f:
            doc = json.load(f)
        roads = [live.encode(r) for r in doc["roads"]]
        features = road_features({int(route): roads[i] for route, i in doc["routes"].items()}, lines)
        sources["osm"] = dict(SOURCES["osm"], **({"sha256": osm_sha256} if osm_sha256 else {}))
        put("rail-roads.geojson.gz", gz(geojson(features)), len(features), "ODbL-1.0", ["mot-gtfs", "osm"],
            "The ministry's trains matched to OpenStreetMap's tracks by pfaedle (server/otp/rail_shapes.py), one "
            "feature per road with every train route on it.")

    with open(os.environ["KAV_EXTRA"], encoding="utf-8") as f:
        extra = json.load(f)
    today = datetime.datetime.now(ISRAEL).date()
    made = datetime.date.fromisoformat(extra["checked"])
    if not 0 <= (today - made).days <= 1:
        raise ValueError("busy and fleet inputs were not checked today or yesterday")
    if (today - datetime.date.fromisoformat(extra["busy_to"])).days > live.BUSY_STALE:
        raise ValueError("boarding month is too old")
    for key in ("mot-busy", "mot-fleet"):
        sources[key] = dict(SOURCES[key], **extra["sources"][key])
        sources[key]["modified"] = sources[key]["published"]
        sources[key]["published"] = sources[key]["published"][:19]
    put("busy.csv.gz", gz(csv_bytes(BUSY_FIELDS, extra["busy"])), len(extra["busy"]), "data-gov-il", ["mot-busy", "mot-gtfs", "kav"],
        "Kav's server busy_rebuild: monthly boardings per trip, compared within each kind of day; exact server levels, "
        "expanded to Sunday=0 through Saturday=6. No estimate of seats occupied.",
        "Boarding counts: Data.gov.il database use licence; timetable: ministry GTFS terms. Credit the ministry. No state endorsement.")
    put("fleet.csv.gz", gz(csv_bytes(FLEET_FIELDS, extra["fleet"])), len(extra["fleet"]), "data-gov-il", ["mot-fleet", "kav"],
        "Kav's server fleet_file: plates retained, Hebrew size and power mapped to server words, invalid seats and years "
        "zeroed; unknown size and power empty; rows with no known attributes omitted.",
        "Data.gov.il database use licence. Credit the ministry. No state endorsement.")
    sources["kav"] = SOURCES["kav"]
    sources["kav-colours"] = SOURCES["kav-colours"]
    built = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    # Kav's site keeps each night's files in a folder of their own too, kavapp.link/data/v1/<folder>/<name>, which never
    # changes and can be cached for good (site/scripts/data-pull.sh).
    manifest = {"format": "kav-open-data", "version": VERSION, "built": built.isoformat(),
                "folder": built.strftime("%Y%m%dT%H%M%SZ"),
                "docs": "https://kavapp.link/docs/data", "contact": "noam@kavapp.link",
                "files": files, "sources": sources,
                "licences": {k: v for k, v in LICENCES.items()
                             if any(f["licence"] == k or k in f.get("licence_note", "") for f in files)}}
    with open(os.path.join(out, "manifest.json.part"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    os.replace(os.path.join(out, "manifest.json.part"), os.path.join(out, "manifest.json"))
    return manifest


# The gate (check()): each file's least rows and bytes, about seven tenths of 13 Nov 2026's (35,422 stops, 7,955 routes,
# 37 operators, 6,648 roads, 965,688 fare pairs, 15 fares, 137 train roads), and the least shares of the ministry's lines
# with a road, of its trains with a train road, and of stops some line calls at (that night: all, all and 87%).
FLOORS = {
    "stops.csv.gz": (30_000, 1_400_000), "routes.csv.gz": (6_000, 270_000), "operators.csv.gz": (25, 700),
    "roads.geojson.gz": (5_000, 19_000_000), "fares.csv.gz": (700_000, 2_000_000), "fares.json.gz": (10, 8_000),
    "rail-roads.geojson.gz": (100, 270_000), "busy.csv.gz": (200_000, 400_000), "fleet.csv.gz": (10_000, 25_000), "roads_cover": 0.95, "rail_cover": 0.95, "stops_served": 0.75,
    "stop_moved_most": 1_000, "stops_moved": 0.001,
}
NIGHT_TO_NIGHT = 0.9  # no file's rows or bytes below this share of last night's
# Against last night's stops: none moved over stop_moved_most metres, at most stops_moved of them over MOVED_FAR metres.
# The ministry's own feed, each night from 16 Jul to 13 Nov 2026 (the open-bus project's daily archive of it, 120
# nights): one stop moved 751 m at most, and at most 9 of 35,302 (0.026%) moved over 50 m in a night.
MOVED_FAR = 50
# Israel and the West Bank, as (lat, lon) rings: Natural Earth's 1:10m Admin 0 countries from Israel's point of view
# (ne_10m_admin_0_countries_isr, version 5.2.0-pre, naturalearthdata.com, public domain), Israel's outer ring and the
# West Bank part of Palestine, simplified to 0.004 degrees. Natural Earth's lines are good to about 1.5 km at that
# scale (the ministry's stops by the Arava border, in Eilat and on the Golan lie up to 1.4 km past them), so a stop
# counts as inside within OUTLINE_KM of them.
OUTLINE_KM = 2
OUTLINE = (
    ((31.21145, 34.24835), (31.2569, 34.31587), (31.28925, 34.3509), (31.30636, 34.3538), (31.35772, 34.34584),
     (31.39281, 34.36734), (31.53983, 34.55603), (31.58314, 34.4812), (31.76553, 34.60963), (31.92646, 34.69321),
     (31.95161, 34.71193), (32.03986, 34.74334), (32.06037, 34.74334), (32.0666, 34.75701), (32.28071, 34.83766),
     (32.72451, 34.94207), (32.81415, 34.94752), (32.83438, 34.95558), (32.84138, 34.97047), (32.82754, 35.00343),
     (32.82661, 35.02768), (32.85834, 35.06276), (32.90583, 35.0796), (32.91787, 35.07781), (32.92308, 35.06544),
     (32.99885, 35.07846), (33.05036, 35.0962), (33.06769, 35.09213), (33.08076, 35.10646), (33.08759, 35.09962),
     (33.08398, 35.18527), (33.0948, 35.20718), (33.09093, 35.23447), (33.10119, 35.28366), (33.08938, 35.30578),
     (33.05716, 35.33193), (33.05558, 35.34516), (33.08522, 35.44944), (33.08739, 35.48014), (33.14741, 35.51228),
     (33.22219, 35.52003), (33.28102, 35.54949), (33.28213, 35.56117), (33.25221, 35.58504), (33.24009, 35.60385),
     (33.25612, 35.61806), (33.26954, 35.62006), (33.27666, 35.65214), (33.32267, 35.69852), (33.33117, 35.74369),
     (33.34264, 35.76942), (33.40672, 35.8211), (33.36003, 35.80994), (33.3344, 35.76384), (33.31249, 35.80208),
     (33.2727, 35.7686), (33.24846, 35.80363), (33.20172, 35.80766), (33.18999, 35.8302), (33.16113, 35.8334),
     (33.12676, 35.81149), (33.11191, 35.81149), (33.09868, 35.8489), (33.08542, 35.8458), (32.99021, 35.85903),
     (32.94494, 35.88807), (32.89582, 35.84973), (32.86603, 35.83805), (32.85358, 35.84187), (32.82795, 35.83423),
     (32.74435, 35.75759), (32.71123, 35.68519), (32.67914, 35.636), (32.6819, 35.61176), (32.6409, 35.56055),
     (32.62548, 35.56406), (32.62124, 35.57254), (32.60755, 35.56561), (32.56039, 35.57998), (32.5544, 35.5744),
     (32.56039, 35.57254), (32.5461, 35.56561), (32.55295, 35.55941), (32.52559, 35.56561), (32.52559, 35.55197),
     (32.51882, 35.55197), (32.51918, 35.56137), (32.49773, 35.57998), (32.4704, 35.55941), (32.46419, 35.57254),
     (32.45673, 35.57254), (32.45053, 35.55941), (32.44371, 35.56561), (32.43621, 35.55197), (32.42944, 35.55941),
     (32.42944, 35.55197), (32.41397, 35.5591), (32.40957, 35.54515), (32.38472, 35.56096), (32.41479, 35.40686),
     (32.47096, 35.4018), (32.49448, 35.39291), (32.51011, 35.36314), (32.51047, 35.27044), (32.54264, 35.20863),
     (32.54171, 35.19075), (32.50791, 35.15168), (32.46314, 35.06435), (32.43412, 35.04471), (32.34459, 35.02156),
     (32.33573, 35.00068), (32.27847, 34.98994), (32.26764, 35.00937), (32.24439, 35.00761), (32.23201, 35.00027),
     (32.20158, 34.96141), (32.18645, 34.95822), (32.12399, 34.98694), (32.08958, 34.98422), (32.02077, 35.00724),
     (32.0111, 34.99677), (31.97878, 34.99784), (31.96723, 34.98669), (31.94492, 35.00773), (31.93377, 35.00542),
     (31.92443, 35.0299), (31.90864, 35.03901), (31.86111, 35.03432), (31.85384, 34.99854), (31.83337, 34.97743),
     (31.82934, 35.01294), (31.83273, 35.02749), (31.85332, 35.05602), (31.85416, 35.07632), (31.82396, 35.11247),
     (31.82484, 35.12139), (31.81429, 35.13696), (31.80874, 35.18617), (31.82512, 35.18359), (31.82011, 35.21576),
     (31.87725, 35.20526), (31.87918, 35.22202), (31.84261, 35.23335), (31.84437, 35.24998), (31.83859, 35.25723),
     (31.83128, 35.25157), (31.82627, 35.26494), (31.81222, 35.25587), (31.78968, 35.2629), (31.76965, 35.2527),
     (31.74863, 35.26281), (31.70933, 35.23797), (31.72319, 35.20526), (31.7201, 35.17942), (31.73205, 35.16673),
     (31.73735, 35.14895), (31.7331, 35.12574), (31.71768, 35.11423), (31.6768, 35.05923), (31.63037, 34.97361),
     (31.582, 34.93733), (31.49441, 34.92679), (31.42987, 34.88173), (31.39643, 34.86715), (31.36284, 34.87883),
     (31.34848, 34.90095), (31.34491, 34.92741), (31.3632, 35.04017), (31.36227, 35.16491), (31.38103, 35.22331),
     (31.4588, 35.33234), (31.48707, 35.39012), (31.49162, 35.45854), (31.40082, 35.45285), (31.33184, 35.41647),
     (31.3246, 35.42392), (31.303, 35.42226), (31.25768, 35.3957), (31.20461, 35.41069), (31.15955, 35.43621),
     (31.13221, 35.44324), (31.10374, 35.43849), (31.02395, 35.39156), (30.96328, 35.38526), (30.94514, 35.3741),
     (30.91258, 35.33493), (30.88995, 35.32222), (30.82282, 35.31663), (30.78024, 35.27953), (30.71978, 35.26382),
     (30.6171, 35.20532), (30.43019, 35.14001), (30.40615, 35.14001), (30.3614, 35.16212), (30.31396, 35.14176),
     (30.24467, 35.12523), (30.21609, 35.12481), (30.1549, 35.14528), (30.12338, 35.14528), (30.08974, 35.12905),
     (30.03403, 35.08626), (29.92339, 35.05412), (29.84231, 35.04895), (29.7331, 35.00254), (29.65196, 34.98983),
     (29.60812, 34.96699), (29.54564, 34.95135), (29.48994, 34.91082), (29.48969, 34.90301), (29.49006, 34.88673),
     (29.54572, 34.85527), (29.56964, 34.84824), (29.63876, 34.8501), (29.7417, 34.82437), (29.94024, 34.74137),
     (30.01259, 34.73321), (30.34451, 34.59947), (30.40962, 34.52692), (30.43871, 34.5265), (30.48217, 34.53622),
     (30.51333, 34.51038), (30.53033, 34.50438), (30.57167, 34.50221), (30.65121, 34.48041), (30.99449, 34.32951),
     (31.21145, 34.24835),),
    ((31.96723, 34.98669), (31.97878, 34.99784), (32.0111, 34.99677), (32.02077, 35.00724), (32.08958, 34.98422),
     (32.12399, 34.98694), (32.18645, 34.95822), (32.20158, 34.96141), (32.23201, 35.00027), (32.24439, 35.00761),
     (32.26764, 35.00937), (32.27847, 34.98994), (32.32304, 34.99655), (32.33573, 35.00068), (32.34459, 35.02156),
     (32.43412, 35.04471), (32.46314, 35.06435), (32.50791, 35.15168), (32.54171, 35.19075), (32.54264, 35.20863),
     (32.51047, 35.27044), (32.51011, 35.36314), (32.49448, 35.39291), (32.47096, 35.4018), (32.41479, 35.40686),
     (32.38472, 35.56096), (32.36795, 35.56561), (32.36795, 35.55941), (32.37482, 35.55941), (32.37482, 35.55197),
     (32.36795, 35.55197), (32.35821, 35.55734), (32.32821, 35.55652), (32.31335, 35.56561), (32.28263, 35.56086),
     (32.25191, 35.57254), (32.23759, 35.55941), (32.23759, 35.57254), (32.17439, 35.55517), (32.15509, 35.55941),
     (32.14703, 35.5467), (32.1352, 35.55197), (32.11081, 35.53523), (32.09923, 35.53461), (32.08683, 35.54515),
     (32.0751, 35.5283), (32.05784, 35.52282), (32.01169, 35.52468), (31.94456, 35.54587), (31.93661, 35.54515),
     (31.92735, 35.52747), (31.91924, 35.52468), (31.8392, 35.54908), (31.8193, 35.53833), (31.76535, 35.55941),
     (31.73507, 35.52758), (31.64112, 35.48014), (31.56856, 35.46422), (31.49188, 35.45916), (31.48707, 35.39012),
     (31.4588, 35.33234), (31.38103, 35.22331), (31.36227, 35.16491), (31.3632, 35.04017), (31.34491, 34.92741),
     (31.34848, 34.90095), (31.36284, 34.87883), (31.39643, 34.86715), (31.42987, 34.88173), (31.49441, 34.92679),
     (31.582, 34.93733), (31.63037, 34.97361), (31.6768, 35.05923), (31.71768, 35.11423), (31.7331, 35.12574),
     (31.73735, 35.14895), (31.73205, 35.16673), (31.7201, 35.17942), (31.72319, 35.20526), (31.70933, 35.23797),
     (31.74863, 35.26281), (31.76965, 35.2527), (31.78968, 35.2629), (31.81222, 35.25587), (31.82627, 35.26494),
     (31.83128, 35.25157), (31.83859, 35.25723), (31.84437, 35.24998), (31.84261, 35.23335), (31.87918, 35.22202),
     (31.87725, 35.20526), (31.82011, 35.21576), (31.82512, 35.18359), (31.80874, 35.18617), (31.81429, 35.13696),
     (31.82484, 35.12139), (31.82396, 35.11247), (31.85416, 35.07632), (31.85332, 35.05602), (31.83273, 35.02749),
     (31.82934, 35.01294), (31.83337, 34.97743), (31.85384, 34.99854), (31.86111, 35.03432), (31.90864, 35.03901),
     (31.92443, 35.0299), (31.93377, 35.00542), (31.94492, 35.00773), (31.96723, 34.98669),),
)
NAMES = ["stops.csv.gz", "routes.csv.gz", "operators.csv.gz", "roads.geojson.gz", "fares.csv.gz", "fares.json.gz",
         "rail-roads.geojson.gz", "busy.csv.gz", "fleet.csv.gz"]


def road_data(data):
    """Measured road count and route coverage from validated GeoJSON."""
    doc = json.loads(gzip.decompress(data))
    if not isinstance(doc, dict) or doc.get("type") != "FeatureCollection" or not isinstance(doc.get("features"), list):
        raise ValueError("not a FeatureCollection")
    routes = set()
    for feature in doc["features"]:
        if not isinstance(feature, dict) or feature.get("type") != "Feature":
            raise ValueError("not a Feature")
        props, geometry = feature.get("properties"), feature.get("geometry")
        if not isinstance(props, dict) or not isinstance(props.get("route_ids"), list) or not props["route_ids"] or any(
                not isinstance(i, str) or not re.fullmatch(r"[0-9]+", i) for i in props["route_ids"]):
            raise ValueError("invalid route_ids")
        if not isinstance(geometry, dict) or geometry.get("type") != "LineString":
            raise ValueError("not a LineString")
        points = geometry.get("coordinates")
        if not isinstance(points, list) or len(points) < 2 or any(
                not isinstance(point, list) or len(point) != 2 or any(
                    type(x) not in (int, float) or not math.isfinite(x) for x in point) or
                not -180 <= point[0] <= 180 or not -90 <= point[1] <= 90 for point in points):
            raise ValueError("invalid coordinate pairs")
        routes.update(props["route_ids"])
    return len(doc["features"]), routes


def header(out, name):
    with io.TextIOWrapper(gzip.open(os.path.join(out, name)), encoding="utf-8") as f:
        return next(csv.reader(f), None)


def within_outline(lat, lon):
    """Whether a place is inside OUTLINE, or within OUTLINE_KM of it."""
    for ring in OUTLINE:
        hit, j = False, len(ring) - 1
        for i in range(len(ring)):
            (yi, xi), (yj, xj) = ring[i], ring[j]
            if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
                hit = not hit
            j = i
        if hit:
            return True
    k = math.cos(math.radians(lat))
    for ring in OUTLINE:
        for (y1, x1), (y2, x2) in zip(ring, ring[1:] + ring[:1]):
            ax, ay, dx, dy = (x1 - lon) * k, y1 - lat, (x2 - x1) * k, y2 - y1
            t = max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy))) if dx or dy else 0.0
            if math.hypot(ax + t * dx, ay + t * dy) * 111.2 <= OUTLINE_KM:
                return True
    return False


def manifest_problems(doc):
    """What in a manifest isn't of the type, in the range and of the shape the rules allow: [] for nothing. The rules are
    one list, at shape() in site/scripts/data-pull.sh, which Kav's site applies before it takes a night; the gate runs
    that same check (with jq), so the two can't disagree."""
    with tempfile.NamedTemporaryFile("w", suffix=".json", encoding="utf-8") as f:
        json.dump(doc, f)
        f.flush()
        run = subprocess.run(["sh", os.path.join(HERE, "site", "scripts", "data-pull.sh"), "--check-manifest", f.name],
                             capture_output=True, text=True, timeout=60)
    return [] if run.returncode == 0 else ["the manifest doesn't keep the rules listed at shape() in site/scripts/data-pull.sh"]


def check(out, previous=None, floors=FLOORS, accept_drop=False, previous_stops=None, first_run=False):
    """What is wrong with the night in out, [] for nothing (the module's docstring says it in words): its manifest of the
    shape build() writes (manifest_problems()), the nine files there and matching it, each above its floor and not below
    NIGHT_TO_NIGHT of last night's (previous: {name: its manifest entry}) unless accept_drop, their columns the ones this
    version writes, every route, operator and fare they name found, the roads covering the lines and the stops served as
    floors says, every stop within OUTLINE, and against last night's stops.csv.gz (previous_stops, the file previous
    names) none moved over floors' stop_moved_most metres, nor more than its stops_moved share over MOVED_FAR, unless
    accept_drop. Without previous, nothing is published but the first night (first_run) or with accept_drop."""
    try:
        with open(os.path.join(out, "manifest.json"), encoding="utf-8") as f:
            doc = json.load(f)
    except (OSError, ValueError) as e:
        return ["no manifest to go by: %r" % e]
    problems = manifest_problems(doc)
    if problems:
        return problems
    files = {e["name"]: e for e in doc["files"]}
    if previous is None and not (first_run or accept_drop):
        problems.append("last night's manifest isn't here to compare with (the first night: KAV_DATA_FIRST_RUN)")
    coverage = {}
    for name in NAMES:
        entry, path = files.get(name), os.path.join(out, name)
        if entry is None or not os.path.isfile(path):
            problems.append(name + " is missing")
            continue
        with open(path, "rb") as f:
            data = f.read()
        if len(data) != entry["size"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
            problems.append(name + " isn't the file its manifest names")
            continue
        try:
            if name.endswith(".geojson.gz"):
                count, coverage[name] = road_data(data)
            elif name.endswith(".csv.gz"):
                with io.StringIO(gzip.decompress(data).decode("utf-8")) as stream:
                    count = sum(1 for _ in csv.DictReader(stream))
            else:
                content = json.loads(gzip.decompress(data))
                if not isinstance(content, dict) or not isinstance(content.get("products"), list):
                    raise ValueError("invalid fare products")
                count = len(content["products"])
        except (OSError, ValueError, TypeError, KeyError, EOFError) as error:
            problems.append("%s invalid %s: %s" % (name, "GeoJSON" if name.endswith(".geojson.gz") else "payload", error))
            continue
        if count != entry["rows"]:
            problems.append("%s has %d rows, manifest says %d" % (name, count, entry["rows"]))
        rows, size = floors[name]
        if count < rows or entry["size"] < size:
            problems.append("%s has %d rows and %d bytes, under its floor of %d and %d" % (name, count, entry["size"], rows, size))
        last = (previous or {}).get(name)
        if last and not accept_drop and (count < NIGHT_TO_NIGHT * last["rows"] or entry["size"] < NIGHT_TO_NIGHT * last["size"]):
            problems.append("%s fell to %d rows and %d bytes from last night's %d and %d" % (name, count, entry["size"],
                                                                                            last["rows"], last["size"]))
    if problems:
        return problems
    for name, want in (("stops.csv.gz", STOP_FIELDS), ("routes.csv.gz", ROUTE_FIELDS), ("operators.csv.gz", OPERATOR_FIELDS),
                       ("fares.csv.gz", FARE_FIELDS), ("busy.csv.gz", BUSY_FIELDS), ("fleet.csv.gz", FLEET_FIELDS)):
        if header(out, name) != want:
            problems.append("%s's columns are %s, not %s" % (name, header(out, name), want))
    if problems:
        return problems

    def table_of(name):
        with io.TextIOWrapper(gzip.open(os.path.join(out, name)), encoding="utf-8") as f:
            return list(csv.DictReader(f))
    stops, routes, operators = table_of("stops.csv.gz"), table_of("routes.csv.gz"), table_of("operators.csv.gz")
    busy, fleet = table_of("busy.csv.gz"), table_of("fleet.csv.gz")
    route_direction = {(r["route_id"], r["direction"]) for r in routes}
    if any((r["route_id"], r["direction"]) not in route_direction or r["day"] not in ("0", "1", "2", "3", "4", "5", "6") or
           not r["hour"].isdigit() or not 0 <= int(r["hour"]) <= 23 or r["level"] not in ("0", "1", "2", "3", "q") for r in busy):
        problems.append("invalid busy route, day, hour or level")
    if len({(r["route_id"], r["day"], r["hour"]) for r in busy}) != len(busy):
        problems.append("duplicate busy hour")
    if len({r["plate"] for r in fleet}) != len(fleet) or any(not r["plate"].isdigit() or int(r["plate"]) <= 0 or
            r["size"] not in ("", *live.FLEET_SIZES.values()) or r["power"] not in ("", *live.FLEET_POWER.values()) or
            not r["seats"].isdigit() or not 0 <= int(r["seats"]) < 200 or not r["year"].isdigit() or
            not (int(r["year"]) == 0 or 1980 <= int(r["year"]) <= 2100) for r in fleet):
        problems.append("invalid fleet row")
    route_ids, agencies = {r["route_id"] for r in routes}, {o["agency_id"] for o in operators}
    roads, rail = coverage["roads.geojson.gz"], coverage["rail-roads.geojson.gz"]
    if any(r["agency_id"] not in agencies for r in routes):
        problems.append("a route's operator isn't in operators.csv.gz")
    if any(not re.fullmatch(r"([0-9A-F]{6})?", row["color"]) for row in routes + operators):
        problems.append("a colour isn't six hex digits")
    if any(i not in route_ids for s in stops for i in s["route_ids"].split()) or roads - route_ids or rail - route_ids:
        problems.append("a stop or a road names a route routes.csv.gz hasn't")
    ministry = [r for r in routes if int(r["route_id"]) < live.NAIM_OFFSET]
    for what, ids, have, least in (
            ("the ministry's lines with a road", [r["route_id"] for r in ministry if r["type"] != "2"], roads, floors["roads_cover"]),
            ("its trains with a train road", [r["route_id"] for r in ministry if r["type"] == "2"], rail, floors["rail_cover"])):
        share = sum(i in have for i in ids) / len(ids) if ids else 0
        if share < least:
            problems.append("%s: %.1f%%, under %.0f%%" % (what, 100 * share, 100 * least))
    outside = [s["stop_id"] for s in stops if not within_outline(float(s["lat"]), float(s["lon"]))]
    if outside:
        problems.append("%d stop%s outside Israel and the West Bank, more than %d km past their outline: %s" % (
            len(outside), "" if len(outside) == 1 else "s", OUTLINE_KM, ", ".join(outside[:5])))
    if previous_stops is not None:
        try:
            with open(previous_stops, "rb") as f:
                data = f.read()
        except OSError:
            data = b""
        if hashlib.sha256(data).hexdigest() != ((previous or {}).get("stops.csv.gz") or {}).get("sha256"):
            problems.append("last night's stops.csv.gz isn't here to compare the stops with")
        else:
            before = {s["stop_id"]: s for s in csv.DictReader(io.StringIO(gzip.decompress(data).decode("utf-8")))}
            moved = sorted((live.distance_km((float(before[s["stop_id"]]["lat"]), float(before[s["stop_id"]]["lon"])),
                                             (float(s["lat"]), float(s["lon"]))) * 1000, s["stop_id"])
                           for s in stops if s["stop_id"] in before)
            far = sum(m > MOVED_FAR for m, _ in moved)
            if moved and moved[-1][0] > floors["stop_moved_most"] and not accept_drop:
                problems.append("stop %s moved %d m from last night's, over the most of %d m" % (
                    moved[-1][1], moved[-1][0], floors["stop_moved_most"]))
            if far > floors["stops_moved"] * len(moved) and not accept_drop:
                problems.append("%d of %d stops moved over %d m from last night's, over %.1f%%" % (
                    far, len(moved), MOVED_FAR, 100 * floors["stops_moved"]))
    platforms = [s for s in stops if s["location_type"] == "0"]
    served = sum(bool(s["route_ids"]) for s in platforms) / len(platforms) if platforms else 0
    if served < floors["stops_served"]:
        problems.append("stops some line calls at: %.1f%%, under %.0f%%" % (100 * served, 100 * floors["stops_served"]))
    with gzip.open(os.path.join(out, "fares.json.gz")) as f:
        products = {p["fare_id"] for p in json.load(f)["products"]}
    with io.TextIOWrapper(gzip.open(os.path.join(out, "fares.csv.gz")), encoding="utf-8") as f:
        named = {row[2] for row in csv.reader(f)} - {"fare_id"}
    if named - products:
        problems.append("fares.csv.gz names fares fares.json.gz hasn't: %s" % sorted(named - products))
    return problems


def main():
    if sys.argv[1:2] == ["check"]:
        previous = stops = None
        if len(sys.argv) > 3 and os.path.exists(sys.argv[3]):
            with open(sys.argv[3], encoding="utf-8") as f:
                previous = {e["name"]: e for e in json.load(f)["files"]}
            stops = sys.argv[4] if len(sys.argv) > 4 else ""
        problems = check(sys.argv[2], previous, accept_drop=os.environ.get("KAV_DATA_ACCEPT_DROP") == "1", previous_stops=stops,
                         first_run=os.environ.get("KAV_DATA_FIRST_RUN") == "1")
        for problem in problems:
            print("not published:", problem)
        print("not published: last night's files stay" if problems else "the night passes the gate")
        sys.exit(1 if problems else 0)
    cache = os.path.join(HERE, ".cache")
    manifest = build(os.environ.get("KAV_GTFS", os.path.join(cache, "israel-gtfs.zip")),
                     os.environ.get("KAV_OUT", os.path.join(cache, "data")),
                     os.environ.get("KAV_NAIM", os.path.join(cache, "naim-gtfs.zip")),
                     os.environ.get("KAV_RAIL_SHAPES"), osm_sha256=os.environ.get("KAV_OSM_SHA256"))
    for f in manifest["files"]:
        print("%-24s %10s bytes %9s rows  %s" % (f["name"], f["size"], f["rows"], f["licence"]))


if __name__ == "__main__":
    main()
