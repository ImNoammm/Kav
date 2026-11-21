"""Kav's live data for a developer holding a key: the buses on the road and Kav's predicted arrivals, in a process of its
own beside live.py, so that nothing a developer does can slow a rider's answer.

live.py writes each national snapshot's buses (DATA/vehicles.json) and the ministry's lines (DATA/lines.json), and Kav's
predictor writes its predicted arrivals (ETA, its now.json): this reads those files and nothing else. It has no network
client, so it never asks the ministry, nor anyone else. Nothing about riders is in what it reads: no searches, no shared
trips, no stops anyone looked at.

Keys are made by hand on the server (python3 developers.py key ...) and kept hashed in KEYS/keys.json. A count per key
and day, and what is left of each key's minute, in KEYS/usage.json, is all that is kept of their requests; requests are
not logged.

Settings come from the environment: DATA, ETA, KEYS and PORT.
"""
import datetime
import gzip
import hashlib
import io
import json
import math
import os
import re
import secrets
import signal
import sys
import threading
import time
import zoneinfo
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

DATA = os.environ.get("DATA", "/data")  # live.py's DEVELOPERS folder
ETA = os.environ.get("ETA", "/eta/now.json")  # Kav's predicted arrivals (server/PREDICTOR.md)
KEYS = os.environ.get("KEYS", "/keys")
PORT = int(os.environ.get("PORT", "8080"))
ISRAEL = zoneinfo.ZoneInfo("Asia/Jerusalem")  # a key's day ends at midnight in Israel

PER_MINUTE = 60  # a new key's requests a minute
PER_DAY = 10000  # and a day: the buses change every 15 seconds, so one a quarter minute all day is 5,760
STALE = 300  # a snapshot older than this is too old to give (live.py's STALE)
OLD_POSITION = 600  # a position older than this isn't where the bus is (live.py's)
AHEAD = 120  # seconds a report may be stamped after now (live.py's)
PREDICTIONS_OLD = 120  # predictions older than this are given no more (live.py's Predictions.OLD)
RAIL_OFFSET = 8_000_000  # route ids from here up aren't the ministry's (live.py's): never given
MOST_STOPS = 10  # stops in one arrivals request
MOST_ROUTES = 20  # routes in one vehicles request
ALL_KEPT = 5  # seconds the answer with every bus is given again as made, its age_s that much behind at most
MAX_WORKING = 8  # requests answered at once; more are answered busy
MAX_CONNECTIONS = 64  # connections held at once; more are closed at once
SAVE_EVERY = 60  # seconds between writes of the refusals' counts (a request let through is written at once)
SOURCE = "Israel Ministry of Transport real-time data (SIRI), through Kav's server"
KEY_FORM = re.compile(r"^kavdev_[A-Za-z0-9_-]{43}$")
BOX = re.compile(r"^(-?\d{1,3}(?:\.\d{1,7})?),(-?\d{1,3}(?:\.\d{1,7})?),(-?\d{1,3}(?:\.\d{1,7})?),(-?\d{1,3}(?:\.\d{1,7})?)$")
NUMBER = re.compile(r"^[0-9A-Za-zא-ת]{1,8}$")


def log(*parts):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *parts, flush=True)


def today(now=None):
    return datetime.datetime.fromtimestamp(time.time() if now is None else now, ISRAEL).date().isoformat()


def next_midnight(now):
    day = datetime.datetime.fromtimestamp(now, ISRAEL).date() + datetime.timedelta(days=1)
    return datetime.datetime.combine(day, datetime.time(), ISRAEL).timestamp()


def hashed(key):
    """A key as it's kept: its sha256. A key is 32 random bytes, so its hash needs no salt nor slowness."""
    return hashlib.sha256(key.encode()).hexdigest()


def write_json(path, doc):
    """In place, for any reader: written aside, then renamed over the old one; the owner's alone."""
    with open(path + ".part", "w", encoding="utf-8") as f:
        os.fchmod(f.fileno(), 0o600)
        json.dump(doc, f, ensure_ascii=False, indent=1)
    os.replace(path + ".part", path)


def epoch(text):
    return int(datetime.datetime.fromisoformat(text).timestamp())


def is_number(x):
    """A number a file may hold: an int or a float, finite; never True or False."""
    return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)


def fresh(got, now, old):
    """Whether a file's data, made at got["at"], may be given: no older than old, and not stamped more than AHEAD ahead."""
    return got is not None and now - got["at"] <= old and got["at"] - now <= AHEAD


class Keys:
    """The keys (keys.json, written by the key command alone), read again whenever the file changes, so a revoked key is
    refused from its next request; and each key's requests this minute and today, counted here and kept in usage.json:
    written at each request let through, so neither a stop nor a restart loses the day's count or refills the minute's
    allowance, and with the refusals every SAVE_EVERY seconds and on SIGTERM."""

    def __init__(self, folder):
        self.path, self.usage_path = os.path.join(folder, "keys.json"), os.path.join(folder, "usage.json")
        self.lock = threading.Lock()
        self.seen, self.by_hash = None, {}
        self.buckets = {}  # key id: [requests left this minute, when counted]
        self.counts = {}  # key id: {"day", "n", "refused"}
        self.changed = False
        self.writing = threading.Lock()  # one write of usage.json at a time, outside self.lock (write())
        self.version = self.written = 0
        self.failing = False  # usage.json couldn't be written last time (write())
        self.load_usage()

    def load_usage(self):
        """What usage.json kept, each entry taken only as it should be: a damaged file or entry is left out."""
        try:
            with open(self.usage_path, encoding="utf-8") as f:
                doc = json.load(f)
        except Exception:
            return
        if not isinstance(doc, dict):
            return
        if isinstance(doc.get("keys"), dict):
            for kid, c in doc["keys"].items():
                if isinstance(c, dict) and isinstance(c.get("day"), str) and isinstance(c.get("n"), int) \
                        and isinstance(c.get("refused", 0), int):
                    self.counts[kid] = {"day": c["day"], "n": c["n"], "refused": c.get("refused", 0)}
        if isinstance(doc.get("minute"), dict):
            for kid, b in doc["minute"].items():
                if isinstance(b, list) and len(b) == 2 and all(is_number(x) for x in b):
                    self.buckets[kid] = [float(b[0]), float(b[1])]

    def current(self):
        try:
            st = os.stat(self.path)
            seen = (st.st_ino, st.st_mtime_ns, st.st_size)
        except OSError:
            seen = None
        if seen != self.seen:
            by_hash = {}
            if seen is not None:
                try:
                    with open(self.path, encoding="utf-8") as f:
                        listed = json.load(f)["keys"].items()
                    for kid, k in listed:
                        try:
                            entry = dict(k, id=str(kid), per_minute=int(k.get("per_minute", PER_MINUTE)),
                                         per_day=int(k.get("per_day", PER_DAY)))
                            if not isinstance(k["hash"], str) or entry["per_minute"] < 1 or entry["per_day"] < 1:
                                raise ValueError("limits start at 1")
                        except (TypeError, ValueError, KeyError, AttributeError):
                            log("keys.json: key", str(kid)[:16], "unreadable, refused")
                            continue
                        by_hash[k["hash"]] = entry
                except Exception as e:
                    # A broken file lets no key in rather than every one.
                    log("keys unreadable:", type(e).__name__)
                    by_hash = {}
            self.seen, self.by_hash = seen, by_hash
        return self.by_hash

    def admit(self, key, now=None):
        """(status, answer, the key or None, seconds to wait or None): 0 when admitted, else the refusal."""
        now = time.time() if now is None else now
        if not key:
            return 401, {"error": "no key: send it in the X-Kav-Key header"}, None, None
        with self.lock:
            k = self.current().get(hashed(key)) if KEY_FORM.match(key) else None
            if k is None:
                return 401, {"error": "unknown key"}, None, None
            if k.get("revoked"):
                return 403, {"error": "this key was revoked"}, k, None
            count = self.count_of(k, now)
            per_minute, per_day = k["per_minute"], k["per_day"]
            bucket = self.buckets.setdefault(k["id"], [float(per_minute), now])
            bucket[0] = min(float(per_minute), bucket[0] + max(0.0, now - bucket[1]) * per_minute / 60)
            bucket[1] = now
            self.changed = True
            if count["n"] >= per_day:
                count["refused"] += 1
                wait = int(next_midnight(now) - now) + 1
                return 429, {"error": "over the day's limit of %d requests" % per_day, "retry_after": wait}, k, wait
            if bucket[0] < 1:
                count["refused"] += 1
                wait = int((1 - bucket[0]) * 60 / per_minute) + 1
                return 429, {"error": "over the limit of %d requests a minute" % per_minute, "retry_after": wait}, k, wait
            bucket[0] -= 1
            count["n"] += 1
            doc = self.usage()
        self.write(*doc)
        return 0, None, k, None

    def refund(self, k, now=None):
        """A request let through that got nothing for it (busy, or no recent data): not counted against the day after
        all. The minute's allowance stays spent, so a client retrying through an outage is still held to its limit; and
        a request from a day that has ended gives nothing back to the new one."""
        now = time.time() if now is None else now
        with self.lock:
            count = self.counts.get(k["id"])
            if count is None or count["day"] != today(now) or count["n"] <= 0:
                return
            count["n"] -= 1
            doc = self.usage()
        self.write(*doc)

    def count_of(self, k, now):
        day = today(now)
        count = self.counts.get(k["id"])
        if count is None or count["day"] != day:
            count = self.counts[k["id"]] = {"day": day, "n": 0, "refused": 0}
        return count

    def left_today(self, k, now=None):
        count = self.counts.get(k["id"]) or {}
        used = count.get("n", 0) if count.get("day") == today(now) else 0
        return max(0, k["per_day"] - used), used

    def usage(self):
        """(version, the counts as usage.json keeps them), self.lock held: a copy, written after it is let go."""
        self.version += 1
        self.changed = False
        return self.version, {"keys": {kid: dict(c) for kid, c in self.counts.items()},
                              "minute": {kid: list(b) for kid, b in self.buckets.items()}}

    def write(self, version, doc):
        """usage.json as doc, unless a newer version is already written: the disk is waited for outside self.lock, so a
        slow write holds up no other key's check, and an older count never lands over a newer one."""
        with self.writing:
            if version <= self.written:
                return
            try:
                write_json(self.usage_path, doc)
                self.written = version
                self.failing = False
            except OSError as e:
                # Said once, till a write works again: not a line for every request.
                if not self.failing:
                    log("counts not saved:", repr(e))
                    self.failing = True

    def save(self):
        with self.lock:
            doc = self.usage() if self.changed else None
        if doc:
            self.write(*doc)

    def keep_saving(self):
        while True:
            time.sleep(SAVE_EVERY)
            self.save()


class Data:
    """The files read, each again only when it has changed: the buses and the lines from live.py, the predictions from
    Kav's predictor. Each is parsed once for all the requests until the next; one that can't be, whatever is wrong with
    it, is said once and the last good one kept."""

    def __init__(self, folder, eta):
        self.folder, self.eta = folder, eta
        self.lock = threading.Lock()
        self.held = {}  # name: (the file's stat, what was made of it)

    def read(self, name, path, make):
        try:
            st = os.stat(path)
            seen = (st.st_ino, st.st_mtime_ns, st.st_size)
        except OSError:
            return None
        with self.lock:
            hit = self.held.get(name)
            if hit is not None and hit[0] == seen:
                return hit[1]
            try:
                with open(path, encoding="utf-8") as f:
                    made = make(json.load(f))
            except Exception as e:
                # Said once, and the last good one kept, till the file changes again.
                log(name, "unreadable:", type(e).__name__)
                made = hit[1] if hit else None
            self.held[name] = (seen, made)
            return made

    def lines(self):
        def make(doc):
            if not isinstance(doc["lines"], dict):
                raise ValueError("lines")
            return doc["lines"]
        return self.read("lines", os.path.join(self.folder, "lines.json"), make) or {}

    def vehicles(self):
        """{"at", "v": each bus as given, "trips": (route, first departure): vehicle}, or None before the first file. A
        row not as live.py writes it (visits()) is left out."""
        def make(doc):
            if not isinstance(doc["v"], list) or not is_number(doc["at"]):
                raise ValueError("vehicles")
            out, trips = [], {}
            for row in doc["v"]:
                try:
                    route, key, lat, lon, reported, bearing, speed = row
                    if not (isinstance(route, int) and not isinstance(route, bool) and isinstance(key, str)
                            and all(is_number(x) for x in (lat, lon, reported))
                            and all(x is None or is_number(x) for x in (bearing, speed))):
                        continue
                    _, first, vehicle = key.split("_", 2)
                    first = epoch(first)
                except (TypeError, ValueError):
                    continue
                if route >= RAIL_OFFSET:
                    continue
                out.append({"route": route, "vehicle": vehicle, "trip_start": first, "lat": lat, "lon": lon,
                            "bearing": bearing, "speed_kmh": speed, "reported": reported})
                trips[(route, first)] = vehicle
            return {"at": int(doc["at"]), "v": out, "trips": trips, "all": {}}
        return self.read("vehicles", os.path.join(self.folder, "vehicles.json"), make)

    def predictions(self):
        """{"at", "stops": stop code: [(arrival, route, first departure, its place on the trip)]}, or None. A run or a
        place not as server/PREDICTOR.md has them is left out, and the rest kept."""
        def make(doc):
            if not isinstance(doc["runs"], dict) or not is_number(doc["at"]):
                raise ValueError("predictions")
            stops = {}
            for run, ahead in doc["runs"].items():
                route, _, first = str(run).partition("_")
                if not (route.isdigit() and first.isdigit() and isinstance(ahead, list)):
                    continue
                route, first = int(route), int(first)
                if route >= RAIL_OFFSET:
                    continue
                for place in ahead:
                    try:
                        order, code, at = place
                        if not (is_number(order) and is_number(at) and (isinstance(code, int) or str(code).isdigit())):
                            continue
                    except (TypeError, ValueError):
                        continue
                    stops.setdefault(str(int(code)), []).append((int(at), route, first, int(order)))
            for rows in stops.values():
                rows.sort()
            return {"at": int(doc["at"]), "stops": stops}
        return self.read("predictions", self.eta, make)


def line_of(lines, route):
    """The route's line as the vehicles answer gives it, from live.py's catalog row (parse_routes())."""
    row = lines.get(str(route))
    if not isinstance(row, list) or len(row) < 8:
        return None
    number, to, to_city, origin, from_city, agency, kind, name = row[:8]
    return {"line": number, "agency": agency, "type": kind, "from": origin, "from_city": from_city, "to": to,
            "to_city": to_city, "name": name}


def takes_gzip(accepted):
    """Whether an Accept-Encoding takes gzip: named, and not with q=0."""
    for item in accepted.split(","):
        name, _, params = item.strip().partition(";")
        if name.strip().lower() in ("gzip", "*"):
            q = params.strip().lower()
            return not (q.startswith("q=") and q[2:].strip() in ("0", "0.0", "0.00", "0.000"))
    return False


class Deadline(io.RawIOBase):
    """A connection's reading for Handler: each request's headers within `within` seconds of the first byte read for it,
    however slowly they trickle in (the socket's own timeout bounds only each wait). live.py's own, as it is there."""

    def __init__(self, connection, within):
        self.connection, self.within, self.until = connection, within, None

    def readable(self):
        return True

    def readinto(self, b):
        if self.until is None:
            got = self.connection.recv_into(b)  # waiting for a request, as long as the socket's timeout
            self.until = time.monotonic() + self.within
            return got
        left = self.until - time.monotonic()
        if left <= 0:
            raise TimeoutError("request not in within %d seconds" % self.within)
        wait = self.connection.gettimeout()
        self.connection.settimeout(left if wait is None else min(wait, left))
        try:
            return self.connection.recv_into(b)
        finally:
            self.connection.settimeout(wait)


IN_ADDRESS = {"error": "send the key in the X-Kav-Key header, never in the address"}


class Handler(BaseHTTPRequestHandler):
    server_version = "kav-developers"
    sys_version = ""
    protocol_version = "HTTP/1.1"
    timeout = 10  # a connection silent this long is closed
    REQUEST_BY = 10  # and a request's headers must be in within this long of its first byte

    def log_message(self, *args):
        pass

    def setup(self):
        super().setup()
        self.rfile.close()
        self.reading = Deadline(self.connection, self.REQUEST_BY)
        self.rfile = io.BufferedReader(self.reading)

    def handle_one_request(self):
        self.reading.until = None  # the next request's own REQUEST_BY, from its first byte
        super().handle_one_request()

    def encoded(self, body, packed=None):
        """(the bytes to send, whether gzipped): made while the request holds its working slot. packed is (plain, gzipped),
        already made."""
        zipped = takes_gzip(self.headers.get("Accept-Encoding", ""))
        if packed is not None:
            return (packed[1], True) if zipped else (packed[0], False)
        raw = json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode()
        if zipped and len(raw) > 1024:
            return gzip.compress(raw, 5), True
        return raw, False

    def send(self, code, raw, zipped, headers=()):
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        if zipped:
            self.send_header("Content-Encoding", "gzip")
        for name, value in headers:
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        """The answer is made while holding a working slot, and written after letting it go: a client slow to read
        holds only its own connection."""
        try:
            code, raw, zipped, headers = self.respond()
        except Exception as e:
            log("request failed:", type(e).__name__)
            code, headers = 500, []
            raw, zipped = self.encoded({"error": "the developers' service failed on this request"})
        self.send(code, raw, zipped, headers)

    def respond(self):
        """(status, the bytes, whether gzipped, headers)."""
        path, _, query = self.path.partition("?")
        if not path.startswith("/v1/"):
            return (404, *self.encoded({"error": "not found"}), [])
        asked = parse_qs(query, keep_blank_values=True)
        # The key first, before a working slot is taken: a request without a valid key costs a lookup and no more.
        status, refusal, key, wait = keys.admit(self.headers.get("X-Kav-Key", "").strip())
        if status and status != 429:
            return (400, *self.encoded(IN_ADDRESS), []) if "key" in asked else (status, *self.encoded(refusal), [])
        now = time.time()
        if status:
            return (status, *self.encoded(refusal),
                    [("X-Kav-Left-Today", str(keys.left_today(key, now)[0])), ("Retry-After", str(wait))])
        if "key" in asked:
            return (400, *self.encoded(IN_ADDRESS), [("X-Kav-Left-Today", str(keys.left_today(key, now)[0]))])
        if not working.acquire(blocking=False):
            keys.refund(key, now)
            return (503, *self.encoded({"error": "busy, try again in a few seconds"}),
                    [("X-Kav-Left-Today", str(keys.left_today(key, now)[0])), ("Retry-After", "5")])
        try:
            if path == "/v1/status":
                code, body, packed = self.status(key, now)
            elif path == "/v1/vehicles":
                code, body, packed = self.vehicles(asked, now)
            elif path == "/v1/arrivals":
                code, body, packed = self.arrivals(asked, now)
            else:
                code, body, packed = 404, {"error": "not found"}, None
            raw, zipped = self.encoded(body, packed)
        finally:
            working.release()
        if code == 503:
            keys.refund(key, now)  # no recent data: nothing given, nothing counted against the day
        return code, raw, zipped, [("X-Kav-Left-Today", str(keys.left_today(key, now)[0]))]

    def status(self, key, now):
        vehicles, predictions = data.vehicles(), data.predictions()
        left, used = keys.left_today(key, now)
        return 200, {
            "source": SOURCE,
            "vehicles_at": vehicles["at"] if vehicles else None,
            "vehicles_age_s": int(now - vehicles["at"]) if vehicles else None,
            "arrivals_at": predictions["at"] if predictions else None,
            "arrivals_age_s": int(now - predictions["at"]) if predictions else None,
            "key": {"id": key["id"], "per_minute": key["per_minute"], "per_day": key["per_day"], "used_today": used,
                    "left_today": left},
        }, None

    def vehicles(self, asked, now):
        """Every bus on the road, or those in a box, on some routes or of a line number (and agency): one at most of each
        filter, combined."""
        try:
            box = route = number = agency = None
            if "bbox" in asked:
                m = BOX.match(asked["bbox"][-1])
                south, west, north, east = (float(x) for x in m.groups())
                if not (south < north and west < east):
                    raise ValueError()
                box = (south, west, north, east)
            if "route" in asked:
                listed = asked["route"][-1].split(",")
                if len(listed) > MOST_ROUTES:
                    raise ValueError()
                route = {int(r) for r in listed}
            if "line" in asked:
                number = asked["line"][-1]
                if not NUMBER.match(number):
                    raise ValueError()
            if "agency" in asked:
                agency = int(asked["agency"][-1])
        except (AttributeError, ValueError):
            return 400, {"error": "bbox=south,west,north,east, route=up to %d route ids, line=a line number, "
                                  "agency=an agency id" % MOST_ROUTES}, None
        got = data.vehicles()
        if not fresh(got, now, STALE):
            return 503, {"error": "no recent data", "at": got["at"] if got else None}, None
        lines = data.lines()
        everything = box is None and route is None and number is None and agency is None
        if everything:
            # The same for every request within ALL_KEPT seconds of the same snapshot: made once, kept plain and gzipped,
            # the same bytes for every request.
            packed = got["all"].get(int(now) // ALL_KEPT)
            if packed is not None:
                return 200, None, packed
        out, used = [], {}
        for v in got["v"]:
            if not (now - v["reported"] < OLD_POSITION and v["reported"] - now <= AHEAD):
                continue
            if box and not (box[0] <= v["lat"] <= box[2] and box[1] <= v["lon"] <= box[3]):
                continue
            if route and v["route"] not in route:
                continue
            line = used.get(v["route"]) or line_of(lines, v["route"])
            if number is not None and (line is None or line["line"] != number):
                continue
            if agency is not None and (line is None or line["agency"] != agency):
                continue
            used[v["route"]] = line
            out.append(dict(v, line=line["line"] if line else None, agency=line["agency"] if line else None))
        body = {"source": SOURCE, "at": got["at"], "age_s": int(now - got["at"]), "count": len(out), "vehicles": out,
                "lines": {str(r): l for r, l in used.items() if l}}
        if everything:
            plain = json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode()
            packed = (plain, gzip.compress(plain, 5))
            got["all"].clear()
            got["all"][int(now) // ALL_KEPT] = packed
            return 200, None, packed
        return 200, body, None

    def arrivals(self, asked, now):
        """Kav's predicted arrivals at up to MOST_STOPS stops, for the buses already on the road, soonest first; a bus
        named only from a snapshot fresh enough to give."""
        try:
            codes = list(dict.fromkeys(str(int(c)) for c in asked["stop"][-1].split(",")))
            if not 0 < len(codes) <= MOST_STOPS:
                raise ValueError()
        except (KeyError, ValueError):
            return 400, {"error": "stop=up to %d stop codes" % MOST_STOPS}, None
        got = data.predictions()
        if not fresh(got, now, PREDICTIONS_OLD):
            return 503, {"error": "no recent predictions", "at": got["at"] if got else None}, None
        lines, buses = data.lines(), data.vehicles()
        trips = buses["trips"] if fresh(buses, now, STALE) else {}
        stops = {}
        for code in codes:
            rows = []
            for at, route, first, place in got["stops"].get(code, ()):
                if at < now - 30:
                    continue
                line = line_of(lines, route)
                rows.append({"route": route, "line": line["line"] if line else None,
                             "agency": line["agency"] if line else None, "trip_start": first,
                             "vehicle": trips.get((route, first)), "stop_sequence": place, "arrival": at,
                             "in_s": int(at - now)})
            stops[code] = rows
        return 200, {"source": SOURCE + "; arrival times predicted by Kav", "at": got["at"],
                     "age_s": int(now - got["at"]), "stops": stops}, None


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64

    def process_request(self, request, client_address):
        if not connections.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            connections.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            connections.release()

    def handle_error(self, request, client_address):
        error = sys.exc_info()[1]
        if not isinstance(error, ConnectionError):
            log("request failed:", repr(error))


def key_command(args, folder=KEYS, out=print):
    """python3 developers.py key add NOTE [PER_MINUTE PER_DAY] | list | limit ID PER_MINUTE PER_DAY | revoke ID"""
    path = os.path.join(folder, "keys.json")
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
    except FileNotFoundError:
        doc = {"keys": {}}
    what = args[0] if args else ""
    try:
        return key_change(doc, path, what, args, folder, out)
    except ValueError:
        out(key_command.__doc__)
        return 2


def limits(said):
    per_minute, per_day = int(said[0]), int(said[1])
    if per_minute < 1 or per_day < 1:
        raise ValueError("limits start at 1")
    return per_minute, per_day


def key_change(doc, path, what, args, folder, out):
    if what == "add" and len(args) in (2, 4):
        key = "kavdev_" + secrets.token_urlsafe(32)
        kid = secrets.token_hex(4)
        per_minute, per_day = limits(args[2:]) if len(args) == 4 else (PER_MINUTE, PER_DAY)
        doc["keys"][kid] = {"hash": hashed(key), "note": args[1], "per_minute": per_minute, "per_day": per_day,
                            "made": today(), "revoked": None}
        write_json(path, doc)
        out("key %s made, %d a minute and %d a day. Give it now; it isn't kept and can't be shown again:" % (
            kid, per_minute, per_day))
        out(key)
        return 0
    if what == "list" and len(args) == 1:
        try:
            with open(os.path.join(folder, "usage.json"), encoding="utf-8") as f:
                counts = json.load(f)["keys"]
        except (OSError, ValueError, KeyError):
            counts = {}
        for kid, k in sorted(doc["keys"].items(), key=lambda kv: kv[1]["made"]):
            c = counts.get(kid) or {}
            out("%s  %s  %d/min %d/day  made %s  %s  %s: %d requests, %d refused" % (
                kid, "REVOKED " + k["revoked"] if k.get("revoked") else "active", k["per_minute"], k["per_day"],
                k["made"], k["note"], c.get("day", today()), c.get("n", 0), c.get("refused", 0)))
        return 0
    if what == "limit" and len(args) == 4 and args[1] in doc["keys"]:
        per_minute, per_day = limits(args[2:])
        doc["keys"][args[1]].update(per_minute=per_minute, per_day=per_day)
        write_json(path, doc)
        out("key %s: %s a minute, %s a day, from its next request" % (args[1], args[2], args[3]))
        return 0
    if what == "revoke" and len(args) == 2 and args[1] in doc["keys"]:
        doc["keys"][args[1]]["revoked"] = today()
        write_json(path, doc)
        out("key %s revoked: refused from its next request" % args[1])
        return 0
    raise ValueError(what)


keys = Keys(KEYS)
data = Data(DATA, ETA)
working = threading.BoundedSemaphore(MAX_WORKING)
connections = threading.BoundedSemaphore(MAX_CONNECTIONS)


if __name__ == "__main__":
    if sys.argv[1:2] == ["key"]:
        os.umask(0o077)
        sys.exit(key_command(sys.argv[2:]))
    threading.Thread(target=keys.keep_saving, daemon=True).start()
    # docker stop sends SIGTERM: the counts kept first.
    signal.signal(signal.SIGTERM, lambda *_: (keys.save(), os._exit(0)))
    server = Server(("0.0.0.0", PORT), Handler)
    log("listening on", PORT)
    server.serve_forever()
