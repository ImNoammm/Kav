"""Open file source routines copied from the 4.4 server. No running server imports this module."""
import array

import calendar

import csv

import datetime

import gzip

import http.client

import io

import ipaddress

import itertools

import json

import math

import os

import re

import secrets

import socket

import ssl

import statistics

import threading

import time

import urllib.request

import zoneinfo

GTFS_URL = os.environ.get("GTFS_URL", "https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip")

REALTIME = os.environ.get("REALTIME", "")  # the folder shared with the planner, for its live updates

def ends(name):
    """Where a route starts and ends: MOT names it "stop-city<->stop-city-direction", the direction a digit and then the
    variant: "#", a digit or a letter ("-1א")."""
    left, _, right = name.partition("<->")
    right = re.sub(r"-[0-9]+[#0-9א-ת]*$", "", right)

    def split(part):
        stop, dash, city = part.rpartition("-")
        return (stop.strip(), city.strip()) if dash else (part.strip(), "")
    return split(left), split(right)

NAIM_OFFSET = 9_000_000

NAIM_LIVE_URL = os.environ.get("NAIM_LIVE_URL", "https://api.goryde.com/api/v1/core/c/101/gtfs-rt/vehicle-positions")

NAIM_LIVE_MOST = 1 << 20  # bytes; tonight's 57 buses were 3,750

naim_lookups = threading.BoundedSemaphore(1)  # the city's name being looked up, one at a time (a hung one keeps the slot)

def naim_lookup(host, until):
    """host's addresses (getaddrinfo's), looked up on a thread of its own and given up at until; one lookup at a time, so
    a resolver that hangs holds one thread and nothing piles up behind it."""
    if not naim_lookups.acquire(blocking=False):
        raise OSError("the city's name: a lookup still under way")
    got, done = {}, threading.Event()

    def look():
        try:
            got["addresses"] = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
        except OSError as e:
            got["error"] = e
        finally:
            naim_lookups.release()
            done.set()
    threading.Thread(target=look, daemon=True).start()
    if not done.wait(max(0.0, until - time.monotonic())):
        raise OSError("the city's name: lookup too slow")
    if "error" in got:
        raise got["error"]
    return got["addresses"]

def naim_public(address):
    try:
        return ipaddress.ip_address(address).is_global
    except ValueError:
        return False

def naim_fetch(until, url=None, lookup=naim_lookup, public=naim_public, context=None):
    """The city's feed by until (time.monotonic()) at the latest, else an OSError: https on port 443, its host's every
    address public, the connection made to an address so checked, no redirect, NAIM_LIVE_MOST bytes at most. Every wait
    is no longer than what is left, and a watchdog cuts the connection at until, whatever is being read then (the
    handshake, the status line, the headers, the body); the socket is closed whatever happens."""
    url = url or NAIM_LIVE_URL

    def left():
        remaining = until - time.monotonic()
        if remaining <= 0:
            raise OSError("too slow")
        return remaining
    u = urllib.parse.urlsplit(url)
    try:
        port = u.port
    except ValueError:
        port = -1
    if u.scheme != "https" or not u.hostname or port not in (None, 443) or u.username or u.password:
        raise OSError("not the city's feed: %r" % url[:200])
    path = (u.path or "/") + ("?" + u.query if u.query else "")
    if any(c in path for c in " \r\n"):
        raise OSError("not a plain path")
    addresses = lookup(u.hostname, until)
    if not addresses or not all(public(a[4][0]) for a in addresses):
        raise OSError("%s is not only at public addresses" % u.hostname)
    sock = None
    for family, kind, proto, _, address in addresses:
        try:
            sock = socket.socket(family, kind, proto)
            sock.settimeout(left())
            sock.connect(address)
            break
        except OSError:
            sock.close()
            sock = None
    if sock is None:
        raise OSError("no connection to %s" % u.hostname)
    # The watchdog shuts the connection down through a copy of its descriptor (TLS keeps the same one), unless the fetch
    # has ended and closed it first: guard makes the two exclusive, so a descriptor reused since is never touched.
    fd, guard, ended = sock.fileno(), threading.Lock(), []

    def cut():
        with guard:
            if ended:
                return
            try:
                copy = socket.fromfd(fd, sock.family, sock.type)
            except OSError:
                return
            try:
                copy.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            finally:
                copy.close()
    watchdog = threading.Timer(left(), cut)
    watchdog.daemon = True
    watchdog.start()
    try:
        sock.settimeout(left())
        sock = (context or ssl.create_default_context()).wrap_socket(sock, server_hostname=u.hostname)
        sock.settimeout(left())
        sock.sendall(("GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: kav-server\r\nAccept-Encoding: identity\r\n"
                      "Connection: close\r\n\r\n" % (path, u.hostname)).encode())
        sock.settimeout(left())
        r = http.client.HTTPResponse(sock, method="GET")
        r.begin()
        if r.status != 200:  # a redirect too: never followed
            raise OSError("HTTP %d" % r.status)
        parts, size = [], 0
        while True:
            sock.settimeout(left())
            part = r.read1(1 << 16)
            if not part:
                left()
                return b"".join(parts)
            size += len(part)
            if size > NAIM_LIVE_MOST:
                raise OSError("weekend buses: over %d bytes" % NAIM_LIVE_MOST)
            parts.append(part)
    except (OSError, http.client.HTTPException, ValueError) as e:
        if time.monotonic() >= until:
            raise OSError("too slow") from e
        raise e if isinstance(e, OSError) else OSError(repr(e)) from e
    finally:
        watchdog.cancel()
        with guard:
            ended.append(True)
            sock.close()

def ministry_lines(text):
    """route id: its line and direction in the ministry's own numbering ("15066-1"), from route_desc ("15066-1-#"), the
    numbering of its count of Rav-Kav taps (keep_busy())."""
    found = {}
    for r in csv.DictReader(io.StringIO(text)):
        line, _, rest = (r.get("route_desc") or "").partition("-")
        direction = rest.partition("-")[0]
        if line.isdigit() and direction.isdigit():
            found[r["route_id"]] = "%s-%s" % (line, direction)
    return found

def route_line(agency, number, name, kind):
    name = name.strip()
    if kind == 5 and agency == "20":
        kind = 7  # Carmelit
    if kind == 3 and ("שאטל" in name or "שאטל" in number):
        kind = 711
    (origin, from_city), (to, to_city) = ends(name)
    return [number.strip(), to, to_city, origin, from_city, int(agency), kind, name]

def encode(points):
    """A path as Google's encoded polyline, to five decimals (about a metre), which the app already reads."""
    out, last_lat, last_lon = [], 0, 0
    for lat, lon in points:
        lat, lon = round(lat * 1e5), round(lon * 1e5)
        for step in (lat - last_lat, lon - last_lon):
            v = ~(step << 1) if step < 0 else step << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1f)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        last_lat, last_lon = lat, lon
    return "".join(out)

def decode(text):
    """The points of an encoded polyline, as encode writes them."""
    points, i, lat, lon = [], 0, 0, 0
    while i < len(text):
        steps = []
        for _ in range(2):
            v = shift = 0
            while True:
                b = ord(text[i]) - 63
                i += 1
                v |= (b & 0x1f) << shift
                shift += 5
                if b < 0x20:
                    break
            steps.append(~(v >> 1) if v & 1 else v >> 1)
        lat, lon = lat + steps[0], lon + steps[1]
        points.append((lat / 1e5, lon / 1e5))
    return points

def shapes_from(archive):
    """route id: the road its trips most often take, encoded. A shape's points come one after another in shapes.txt,
    so only one shape is held at a time."""
    counts = {}
    with archive.open("trips.txt") as f:
        for r in csv.DictReader(io.TextIOWrapper(f, encoding="utf-8-sig")):
            if r["shape_id"]:
                seen = counts.setdefault(int(r["route_id"]), {})
                seen[r["shape_id"]] = seen.get(r["shape_id"], 0) + 1
    wanted = {}
    for route, seen in counts.items():
        wanted.setdefault(max(seen, key=seen.get), []).append(route)
    found = {}
    with archive.open("shapes.txt") as f:
        rows = csv.reader(io.TextIOWrapper(f, encoding="utf-8-sig"))
        column = {name: i for i, name in enumerate(next(rows))}
        shape, lat, lon, order = (column[c] for c in ("shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence"))
        for key, points in itertools.groupby(rows, key=lambda r: r[shape]):
            routes = wanted.get(key)
            if not routes:
                continue
            path = encode((float(r[lat]), float(r[lon])) for r in sorted(points, key=lambda r: int(r[order])))
            for route in routes:
                found[route] = path
    return found

FARE_BUS = {"201", "202", "204", "205", "221", "222", "224", "225"}  # by bus, light rail or cable car, up to 15, 40,

FARE_RAIL = {"211", "212", "213", "214", "215"}  # by train, up to 15, 40, 75, 120 and 225 km

FARE_NEAR = {"201", "221"}  # up to 15 km: the one fare that includes changing to more rides (item 4)

TRANSFER_WINDOW = 90 * 60  # those rides are boarded within this of the fare's validation

NEAR_KM, NEAR_SPARE_KM = 15, 0.5  # the reach of the fare up to 15 km, and how far off it a distance worked out here may be

FARE_PAIR, FARE_IDS = 1_000_000, 32  # how fares_from() packs a pair of stop codes and its fare into one number

def distance_km(p, q):
    """Kilometres between two places as the crow flies, on the earth's ellipsoid: the ministry's fare distances come out
    within metres of it where its stops haven't moved since, a sphere's up to 0.3% off them."""
    lat, e2 = math.radians((p[0] + q[0]) / 2), 0.00669437999014
    w = 1 - e2 * math.sin(lat) ** 2
    return math.hypot(6378.137 * (1 - e2) / w ** 1.5 * math.radians(q[0] - p[0]),
                      6378.137 / math.sqrt(w) * math.cos(lat) * math.radians(q[1] - p[1]))

def fares_from(archive):
    """The fares in the ministry's timetable (a zip): {"ids": its fare ids in order, "price": agorot by fare id, "pairs":
    every stop pair's, sorted, packed as (boarding code * FARE_PAIR + alighting code) * FARE_IDS + the fare's place in ids,
    "routes": a whole route's by route id (None where two are named), "unsure": the stop codes whose places a pair's fare
    puts more than NEAR_SPARE_KM the other side of NEAR_KM (a stop moved since), whose distances aren't trusted}."""
    read = lambda name: csv.DictReader(io.TextIOWrapper(archive.open(name), encoding="utf-8-sig"))
    price = {r["fare_id"]: round(float(r["price"]) * 100) for r in read("fare_attributes.txt") if r["currency_type"] == "ILS"}
    ids = sorted(price)
    if len(ids) > FARE_IDS:
        raise ValueError("more fares than FARE_IDS: %d" % len(ids))
    at = {f: i for i, f in enumerate(ids)}
    place = {}
    for r in read("stops.txt"):
        code = r["zone_id"] or r["stop_code"]
        if code.isdigit():
            place[int(code)] = (float(r["stop_lat"]), float(r["stop_lon"]))
    pairs, routes, unsure = [], {}, set()
    for r in read("fare_rules.txt"):
        fare, origin, to = r["fare_id"], r["origin_id"], r["destination_id"]
        if fare not in at or r.get("contains_id"):
            continue
        if r["route_id"] and not origin and not to:
            route = int(r["route_id"])
            routes[route] = fare if routes.get(route, fare) == fare else None
        elif not r["route_id"] and origin.isdigit() and to.isdigit() and int(origin) < FARE_PAIR and int(to) < FARE_PAIR:
            origin, to = int(origin), int(to)
            pairs.append((origin * FARE_PAIR + to) * FARE_IDS + at[fare])
            if fare in FARE_BUS and origin in place and to in place:
                km = distance_km(place[origin], place[to])
                if (fare in FARE_NEAR) != (km <= NEAR_KM) and abs(km - NEAR_KM) > NEAR_SPARE_KM:
                    unsure.update((origin, to))
    pairs.sort()
    return {"ids": ids, "price": price, "pairs": array.array("q", pairs), "routes": routes, "unsure": frozenset(unsure)}

DAY_PASS = {"bus": (1750, 2900, 3750, 3750, 6050), "rail": (2300, 3250, 4200, 4700, 8050)}

APP_DAY_FROM = 4 * 3600

ISRAEL = zoneinfo.ZoneInfo("Asia/Jerusalem")  # a bus's day, whatever the server's clock

FLEET_API = os.environ.get("FLEET_API") or "https://data.gov.il/api/3/action/"

FLEET_DATASET = "bus_fleet"

FLEET_FIELDS = ("bus_license_id", "BusSize_nm", "SeatsNum", "production_year", "PropulsionType_nm")

FLEET_PAGE, FLEET_ROWS, FLEET_PAUSE, FLEET_WAIT = 5000, 40_000, 1.5, 60

FLEET_SIZES = {"אוטובוס": "bus", "מפרקי 3 דלתות": "articulated", "מפרקי 4 דלתות": "articulated", "מפרקי 5 דלתות": "articulated",
               "מידיבוס": "midibus", "מיניבוס": "minibus"}

FLEET_POWER = {"דיזל": "diesel", "חשמל": "electric", "גז טבעי דחוס": "gas"}

def fleet_get(action, params, fetch=None):
    """One answer of the portal's API (its result), from data.gov.il alone, within FLEET_WAIT seconds; else an exception."""
    url = FLEET_API + action + "?" + urllib.parse.urlencode(params)
    try:
        doc = json.loads((fetch or naim_fetch)(time.monotonic() + FLEET_WAIT, url=url))
    except OSError as e:
        raise OSError("data.gov.il: %s" % e) from e
    if not isinstance(doc, dict) or doc.get("success") is not True or not isinstance(doc.get("result"), dict):
        raise ValueError("data.gov.il: not an answer")
    return doc["result"]

def fleet_file(fetch, kept, wait=time.sleep):
    """The file of the operators' buses unless it is still the one kept (kept: its "source"): (source, {plate: [size, seats,
    year, power]}, "" or 0 where it doesn't say), or None when unchanged. A bus it says nothing of isn't kept. One request
    at a time, a pause before each but the first; any answer that isn't what it should be raises."""
    asked = []

    def get(action, params):
        if asked:
            wait(FLEET_PAUSE)
        asked.append(action)
        return fleet_get(action, params, fetch)
    files = [(str(r.get("last_modified") or ""), r["id"]) for r in get("package_show", {"id": FLEET_DATASET}).get("resources") or []
             if r.get("datastore_active") is True and isinstance(r.get("id"), str)]
    if len(files) != 1:
        raise ValueError("data.gov.il: %d files of buses" % len(files))
    modified, resource_id = files[0]
    source = {"resource": resource_id, "modified": modified}
    if kept == source:
        return None
    buses, read, total = {}, 0, None
    while total is None or read < total:
        page = get("datastore_search", {"resource_id": resource_id, "fields": ",".join(FLEET_FIELDS), "sort": "_id",
                                        "limit": FLEET_PAGE, "offset": read, "records_format": "lists"})
        if [f.get("id") for f in page["fields"]] != list(FLEET_FIELDS):
            raise ValueError("data.gov.il: other fields than asked")
        total = page["total"]
        if not isinstance(total, int) or total > FLEET_ROWS:
            raise ValueError("data.gov.il: %r buses" % (total,))
        rows = page["records"]
        if not rows and read < total:
            raise ValueError("data.gov.il: %d rows of %d" % (read, total))
        for plate, size, seats, year, power in rows:
            if type(plate) not in (int, float) or plate <= 0 or any(x is not None and type(x) not in (int, float) for x in (seats, year)):
                raise ValueError("data.gov.il: a row that isn't a bus")
            bus = [FLEET_SIZES.get(size, ""), int(seats) if seats and 0 < seats < 200 else 0,
                   int(year) if year and 1980 <= year <= 2100 else 0, FLEET_POWER.get(power, "")]
            if any(bus):
                buses[str(int(plate))] = bus
        read += len(rows)
    return source, buses

LATENESS = os.environ.get("LATENESS") or (os.path.join(REALTIME, "lateness") if REALTIME else "")

BUSY = os.environ.get("BUSY") or (os.path.join(REALTIME, "busy") if REALTIME else "")

BUSY_API = os.environ.get("BUSY_API") or "https://data.gov.il/api/3/action/"

BUSY_DATASET = "tikufim-2021"

BUSY_FIELDS = ("OfficeLineId", "Direction", "hour_a") + tuple("D%d" % d for d in range(1, 32))

BUSY_PAGE, BUSY_ROWS, BUSY_PAUSE, BUSY_WAIT = 5000, 150_000, 1.5, 60

BUSY_KINDS = ("weekday", "friday", "saturday")

BUSY_NORMAL, BUSY_DAYS = 0.7, {"weekday": 8, "friday": 3, "saturday": 3}

BUSY_TAPS, BUSY_TRIPS, BUSY_CELLS = 20, 1, 4

BUSY_HIGH, BUSY_LOW = 0.8, 0.5

BUSY_TOP = 4

BUSY_MOST = 1_000_000  # taps of a line in an hour of a day at most, and its ids; a count past it fails the month

BUSY_QUIET = 15

BUSY_SHRINK = 0.5  # levels for under this share of the routes before don't replace them

BUSY_STALE = 75  # days after the month's last that its levels are given: one month missed, not two

def busy_kind(day):
    return "friday" if day.weekday() == 4 else "saturday" if day.weekday() == 5 else "weekday"

def busy_clock(day):
    """Israel's clock that day, "+0300" on summer time, "+0200" on winter time (at noon: the change is at night)."""
    return datetime.datetime.combine(day, datetime.time(12), ISRAEL).strftime("%z")

def busy_clocks(folder):
    """The clock of the saved timetables of Fridays and of Saturdays: their latest saved day's ({kind: clock}, a kind
    with no saved day left out)."""
    clocks = {}
    names = sorted(os.listdir(folder)) if folder and os.path.isdir(folder) else []
    for name in names:
        if name.endswith(".json.gz"):
            day = datetime.date.fromisoformat(name[:10])
            if busy_kind(day) != "weekday":
                clocks[busy_kind(day)] = busy_clock(day)
    return clocks

def busy_days(totals, year, month, normal=BUSY_NORMAL):
    """The month's days that count, by kind: a day whose taps all over the country (totals {day: taps}) are under normal of
    its kind's median that month is left out (a holiday, a strike, a cut service)."""
    by = {kind: [] for kind in BUSY_KINDS}
    for d in range(1, calendar.monthrange(year, month)[1] + 1):
        by[busy_kind(datetime.date(year, month, d))].append(d)
    days = {}
    for kind, ds in by.items():
        middle = statistics.median(totals.get(d, 0) for d in ds) if ds else 0
        days[kind] = [d for d in ds if totals.get(d, 0) >= normal * middle]
    return days

def busy_trips(folder, lines, clocks=None):
    """Trips on the road by line (lines: {route: its line}), kind and hour, the mean over the saved timetables' days of
    each kind (LATENESS/timetable, lateness_keep_timetable()): each trip counted in each hour for the share of its run
    inside it, a tap being made while it runs; a Friday or Saturday only on its kind's clock in clocks, when given.
    (trips {line: {kind: [24]}}, days {kind: how many})."""
    trips, days = {}, {kind: 0 for kind in BUSY_KINDS}
    names = sorted(os.listdir(folder)) if folder and os.path.isdir(folder) else []
    for name in names:
        if not name.endswith(".json.gz"):
            continue
        day = datetime.date.fromisoformat(name[:10])
        if clocks is not None and busy_kind(day) in clocks and busy_clock(day) != clocks[busy_kind(day)]:
            continue
        days[busy_kind(day)] += 1
        with gzip.open(os.path.join(folder, name), "rt", encoding="utf-8") as f:
            doc = json.load(f)
        lengths = [int(o.rsplit(",", 1)[-1]) for o in doc["offsets"]]
        for route, runs in doc["trips"].items():
            line = lines.get(route)
            if line is None:
                continue
            mine = trips.setdefault(line, {kind: [0.0] * 24 for kind in BUSY_KINDS})
            for into, (_, offset) in runs.items():
                start = int(into)
                end = start + max(lengths[offset], 60)
                at = start
                while at < end:
                    edge = (at // 3600 + 1) * 3600
                    on = day + datetime.timedelta(days=at // 86400)
                    mine[busy_kind(on)][at % 86400 // 3600] += (min(edge, end) - at) / (end - start)
                    at = edge
    for mine in trips.values():
        for kind in BUSY_KINDS:
            mine[kind] = [x / days[kind] if days[kind] else 0.0 for x in mine[kind]]
    return trips, days

def busy_levels(taps, trips):
    """Each line's levels by kind of day, an hour a character, against that kind's own hours: "3" one of its busiest
    (among its BUSY_TOP highest, at most a third of the hours told, and BUSY_HIGH of its three highest or more), "2" about
    average, "1" one of its quieter (under BUSY_LOW of them), "q" a day whose three highest are under BUSY_QUIET taps a
    trip, "0" too little to say; a kind with under BUSY_CELLS hours to compare left out, a line with none left out too.
    taps, trips: {line: {kind: [24]}}, means a day."""
    out = {}
    for line, mine in taps.items():
        running = trips.get(line) or {}
        levels = {}
        for kind in BUSY_KINDS:
            counts, on = mine.get(kind) or [0.0] * 24, running.get(kind) or [0.0] * 24
            cells = {hour: count / on[hour] for hour, count in enumerate(counts) if on[hour] >= BUSY_TRIPS and count >= BUSY_TAPS}
            if len(cells) < BUSY_CELLS:
                continue
            ranked = sorted(cells.values(), reverse=True)
            top = statistics.mean(ranked[:3])
            least = ranked[min(BUSY_TOP, max(1, len(cells) // 3)) - 1]  # the lowest taps a trip that may be busiest
            chars = ["0"] * 24
            for hour, each in cells.items():
                chars[hour] = ("q" if top < BUSY_QUIET else "3" if each >= BUSY_HIGH * top and each >= least
                               else "1" if each < BUSY_LOW * top else "2")
            levels[kind] = "".join(chars)
        if levels:
            out[line] = levels
    return out

def busy_get(action, params, fetch=None):
    """One answer of the portal's API (its result), from data.gov.il alone, within BUSY_WAIT seconds; else an exception."""
    url = BUSY_API + action + "?" + urllib.parse.urlencode(params)
    try:
        doc = json.loads((fetch or naim_fetch)(time.monotonic() + BUSY_WAIT, url=url))
    except OSError as e:
        raise OSError("data.gov.il: %s" % e) from e
    if not isinstance(doc, dict) or doc.get("success") is not True or not isinstance(doc.get("result"), dict):
        raise ValueError("data.gov.il: not an answer")
    return doc["result"]

def busy_month(fetch, kept, wait=time.sleep):
    """The newest month of the count of taps, unless the file is still the one kept (kept: its "source"): (source, month,
    days that count by kind, taps {line: {kind: [24 means a day]}}), or None when unchanged; Fridays and Saturdays by their
    clock ("friday+0300", "friday+0200"...). One request at a time, a pause before each but the first; any answer that
    isn't what it should be raises, and nothing is kept of it."""
    asked = []

    def get(action, params):
        if asked:
            wait(BUSY_PAUSE)
        asked.append(action)
        return busy_get(action, params, fetch)
    files = []
    for r in get("package_show", {"id": BUSY_DATASET}).get("resources") or []:
        year = re.search(r"\b(20[0-9]{2})\b", str(r.get("name") or ""))
        if r.get("datastore_active") is True and year and isinstance(r.get("id"), str):
            files.append((int(year.group(1)), r["id"], str(r.get("last_modified") or "")))
    if not files:
        raise ValueError("data.gov.il: no year's file of taps")
    _, resource_id, modified = max(files)
    source = {"resource": resource_id, "modified": modified}
    if kept == source:
        return None
    newest = get("datastore_search", {"resource_id": resource_id, "limit": 1, "fields": "year_key,month_key",
                                      "sort": "year_key desc,month_key desc", "records_format": "lists"})
    year, month = (int(x) for x in newest["records"][0])
    if not 2020 <= year <= 2100 or not 1 <= month <= 12:
        raise ValueError("data.gov.il: no such month %r" % ((year, month),))
    length = calendar.monthrange(year, month)[1]
    totals, counts, read, total = {}, {}, 0, None
    while total is None or read < total:
        page = get("datastore_search", {"resource_id": resource_id, "filters": json.dumps({"year_key": year, "month_key": month}),
                                        "fields": ",".join(BUSY_FIELDS), "sort": "_id", "limit": BUSY_PAGE, "offset": read,
                                        "records_format": "lists"})
        if [f.get("id") for f in page["fields"]] != list(BUSY_FIELDS):
            raise ValueError("data.gov.il: other fields than asked")
        total = page["total"]
        if not isinstance(total, int) or total > BUSY_ROWS:
            raise ValueError("data.gov.il: %r rows in a month" % (total,))
        rows = page["records"]
        if not rows and read < total:
            raise ValueError("data.gov.il: %d rows of %d" % (read, total))
        for row in rows:
            if len(row) != len(BUSY_FIELDS) or not all(x is None or type(x) in (int, float) and low <= x <= BUSY_MOST
                                                        for low, part in ((-1, row[:3]), (0, row[3:])) for x in part):
                raise ValueError("data.gov.il: a row that isn't counts")
            line, direction, hour = (int(x) if x is not None else -1 for x in row[:3])
            days = [int(x or 0) for x in row[3:3 + length]]
            for d, n in enumerate(days, 1):
                totals[d] = totals.get(d, 0) + n
            if line > 0 and direction >= 0 and 0 <= hour <= 23:
                mine = counts.setdefault("%d-%d" % (line, direction), {})
                mine[hour] = [a + b for a, b in zip(mine.get(hour, [0] * length), days)]
        read += len(rows)
    days = busy_days(totals, year, month)
    for kind in ("friday", "saturday"):
        for d in days.pop(kind):
            days.setdefault(kind + busy_clock(datetime.date(year, month, d)), []).append(d)
    taps = {}
    for line, hours in counts.items():
        taps[line] = {key: [round(sum(hours[h][d - 1] for d in ds) / len(ds), 1) if h in hours else 0.0 for h in range(24)]
                      for key, ds in days.items() if len(ds) >= BUSY_DAYS[key[:-5] if key[-5:-4] == "+" else key]}
    return source, "%04d-%02d" % (year, month), days, taps

def busy_checked(out=None):
    """The day the portal was last asked successfully (checked.json), or None."""
    try:
        with open(os.path.join(out or BUSY, "checked.json"), encoding="utf-8") as f:
            return json.load(f)["day"]
    except (OSError, ValueError, KeyError, TypeError):
        return None

def busy_write(path, doc):
    """doc written whole as path, through a file of its own beside it (two writers never share one), then put in place."""
    part = "%s.%d.%s.part" % (path, os.getpid(), secrets.token_hex(4))
    try:
        with open(part, "x", encoding="utf-8") as f:
            json.dump(doc, f, separators=(",", ":"))
        os.replace(part, path)
    finally:
        if os.path.exists(part):
            os.remove(part)

def busy_rebuild(lines, today=None, out=None, timetables=None, fetch=None, wait=time.sleep):
    """keep_busy()'s morning: the portal asked (unless already today) for a new month of taps, kept as taps.json; the
    levels told from the month kept and the saved trips, written as busy.json unless for far fewer routes than before.
    Returns (why the portal or the levels failed, else None; the table written, else None)."""
    today = today or datetime.datetime.now(ISRAEL).date()
    out = out or BUSY
    timetables = timetables if timetables is not None else os.path.join(LATENESS, "timetable") if LATENESS else ""
    os.makedirs(out, exist_ok=True)
    try:
        with open(os.path.join(out, "taps.json"), encoding="utf-8") as f:
            kept = json.load(f)
    except (OSError, ValueError):
        kept = None
    problem = None
    if busy_checked(out) != today.isoformat():
        try:
            got = busy_month(fetch, kept.get("source") if isinstance(kept, dict) else None, wait)
            if got is not None:
                source, month, days, taps = got
                year, number = (int(x) for x in month.split("-"))
                kept = {"source": source, "month": month, "from": month + "-01",
                        "to": "%s-%02d" % (month, calendar.monthrange(year, number)[1]), "days": days, "taps": taps}
                busy_write(os.path.join(out, "taps.json"), kept)
            busy_write(os.path.join(out, "checked.json"), {"day": today.isoformat()})
        except (OSError, ValueError, KeyError, TypeError, IndexError, AttributeError) as e:
            problem = repr(e)[:300]
    if not isinstance(kept, dict):
        return problem, None
    # Fridays and Saturdays on the saved timetables' clock alone; a kind of day with under BUSY_DAYS such days has no
    # means kept, so it isn't told that month.
    clocks = busy_clocks(timetables)
    trips, _ = busy_trips(timetables, lines, clocks)
    keys = dict({"weekday": "weekday"}, **{kind: kind + clock for kind, clock in clocks.items()})
    taps = {line: {kind: mine[key] for kind, key in keys.items() if key in mine} for line, mine in kept["taps"].items()}
    levels = busy_levels(taps, trips)
    routes = {route: levels[line] for route, line in sorted(lines.items()) if line in levels}
    try:
        with open(os.path.join(out, "busy.json"), encoding="utf-8") as f:
            before = len(json.load(f)["routes"])
    except (OSError, ValueError, KeyError, TypeError):
        before = 0
    if len(routes) < BUSY_SHRINK * before:
        return (problem + "; " if problem else "") + "levels for %d routes, far fewer routes than %d before" % (len(routes), before), None
    told = {kind: len(kept["days"].get(key, ())) for kind, key in keys.items()}
    table = {"made": today.isoformat(), "month": kept["month"], "from": kept["from"], "to": kept["to"], "clocks": clocks,
             "days": {kind: n for kind, n in told.items() if n >= BUSY_DAYS[kind]},
             "least": {"taps": BUSY_TAPS, "trips": BUSY_TRIPS, "hours": BUSY_CELLS, "quiet": BUSY_QUIET}, "routes": routes}
    busy_write(os.path.join(out, "busy.json"), table)
    return problem, table
