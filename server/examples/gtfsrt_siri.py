"""An adapter from GTFS-Realtime, the live format most transit agencies publish, to the SIRI answers Kav's server reads
(LIVE_URL, server/LIVE.md). It reads the agency's two feeds every 15 seconds and its static GTFS once (for the stops'
codes), and answers Kav's two questions. Python's own library only, protobuf decoding included.

    GTFS_ZIP=gtfs.zip VEHICLES=https://agency/vehicle_positions.pb TRIPS=https://agency/trip_updates.pb \\
    TZ_NAME=Europe/Paris python3 examples/gtfsrt_siri.py                  serves on :8200 (PORT to change it)
    python3 conformance.py live http://127.0.0.1:8200/siri                 checks it

Kav's ids are numbers, as Israel's are: the feed's route_id and its stops' stop_code must be numeric (see server/LIVE.md).
FEED_HEADER=Name:value adds a header to the feed requests, for an agency that wants an API key in one."""
import csv
import datetime
import http.server
import io
import json
import os
import struct
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from zoneinfo import ZoneInfo

ZONE = ZoneInfo(os.environ.get("TZ_NAME", "UTC"))
EVERY = 15


def fields(data):
    """A protobuf message's fields: {number: [values]}, varints as ints, fixed32 and fixed64 as bytes, the rest bytes."""
    out, i = {}, 0
    while i < len(data):
        key, i = varint(data, i)
        number, kind = key >> 3, key & 7
        if kind == 0:
            value, i = varint(data, i)
        elif kind == 1:
            value, i = data[i:i + 8], i + 8
        elif kind == 2:
            size, i = varint(data, i)
            value, i = data[i:i + size], i + size
        elif kind == 5:
            value, i = data[i:i + 4], i + 4
        else:
            raise ValueError("protobuf wire type %d" % kind)
        out.setdefault(number, []).append(value)
    return out


def varint(data, i):
    shift = result = 0
    while True:
        b = data[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def text(m, n):
    return m[n][0].decode() if n in m else ""


def number(m, n):
    return m[n][0] if n in m else None


def real(m, n):
    return struct.unpack("<f", m[n][0])[0] if n in m else None


def stamp(seconds):
    return datetime.datetime.fromtimestamp(seconds, ZONE).isoformat(timespec="seconds")


def first_departure(trip):
    """When the trip set out, from its TripDescriptor's start_date and start_time (GTFS times can pass 24:00)."""
    day, at = text(trip, 3), text(trip, 2)
    if not day or not at:
        return None
    h, m, s = (int(x) for x in at.split(":"))
    noon = datetime.datetime(int(day[:4]), int(day[4:6]), int(day[6:]), 12, tzinfo=ZONE)
    return noon - datetime.timedelta(hours=12) + datetime.timedelta(hours=h, minutes=m, seconds=s)


class Feeds:
    """The agency's two feeds, read every EVERY seconds, and the stops' codes by their ids."""

    def __init__(self, vehicles, trips, gtfs, header=None):
        self.vehicles_url, self.trips_url, self.header = vehicles, trips, header
        self.vehicles, self.trips, self.at = [], [], time.time()
        with zipfile.ZipFile(gtfs) as z:
            stops = csv.DictReader(io.TextIOWrapper(z.open("stops.txt"), "utf-8-sig"))
            self.code = {s["stop_id"]: s.get("stop_code") or s["stop_id"] for s in stops}
            routes = csv.DictReader(io.TextIOWrapper(z.open("routes.txt"), "utf-8-sig"))
            self.names = {r["route_id"]: r.get("route_short_name") or r.get("route_long_name") or "" for r in routes}

    def get(self, url):
        req = urllib.request.Request(url, headers=dict([self.header]) if self.header else {})
        with urllib.request.urlopen(req, timeout=20) as r:
            return [fields(e) for e in fields(r.read()).get(2, [])]

    def read(self):
        self.vehicles = [fields(e[4][0]) for e in self.get(self.vehicles_url) if 4 in e]
        self.trips = [fields(e[3][0]) for e in self.get(self.trips_url) if 3 in e] if self.trips_url else []
        self.at = time.time()

    def keep_reading(self):
        while True:
            try:
                self.read()
            except Exception as e:
                print("feeds failing:", repr(e), flush=True)
            time.sleep(EVERY)


def journey(feeds, trip, vehicle, position=None):
    """A SIRI MonitoredVehicleJourney for one trip, as server/LIVE.md lists its fields."""
    out = {"LineRef": text(trip, 5), "OriginAimedDepartureTime": first_departure(trip).isoformat(timespec="seconds"),
           "VehicleRef": text(vehicle, 1) if vehicle else "", "PublishedLineName": feeds.names.get(text(trip, 5), ""),
           "FramedVehicleJourneyRef": {"DataFrameRef": first_departure(trip).date().isoformat(),
                                       "DatedVehicleJourneyRef": text(trip, 1)}}
    if position:
        out["VehicleLocation"] = {"Latitude": str(real(position, 1)), "Longitude": str(real(position, 2))}
        if real(position, 3) is not None:
            out["Bearing"] = str(round(real(position, 3)))
        if real(position, 5) is not None:
            out["Velocity"] = str(round(real(position, 5) * 3.6))
    return out


def all_trips(feeds):
    """The answer to MonitoringRef=AllActiveTripsFilter: every vehicle on a trip, where it is."""
    visits = []
    for v in feeds.vehicles:
        trip, position = (fields(v[1][0]) if 1 in v else {}), (fields(v[2][0]) if 2 in v else None)
        if not trip or not position or first_departure(trip) is None:
            continue
        stop = feeds.code.get(text(v, 7), "")
        visits.append({"RecordedAtTime": stamp(number(v, 5) or feeds.at), "MonitoringRef": stop,
                       "MonitoredVehicleJourney": dict(journey(feeds, trip, fields(v[8][0]) if 8 in v else None, position),
                                                       MonitoredCall={"StopPointRef": stop})})
    return siri(visits, feeds.at)


def at_stops(feeds, codes, ahead=5400):
    """The answer to MonitoringRef=<codes>: each trip due at those stops in the next 90 minutes, with its expected time
    there and, where the vehicle reports one, its place."""
    placed = {}
    for v in feeds.vehicles:
        if 1 in v and 2 in v:
            placed[text(fields(v[1][0]), 1)] = (fields(v[2][0]), number(v, 5))
    now, visits = time.time(), []
    for t in feeds.trips:
        trip = fields(t[1][0]) if 1 in t else {}
        if first_departure(trip) is None:
            continue
        vehicle = fields(t[3][0]) if 3 in t else None
        for u in (fields(x) for x in t.get(2, [])):
            code = feeds.code.get(text(u, 4), "")
            event = fields(u[2][0]) if 2 in u else fields(u[3][0]) if 3 in u else {}
            when = number(event, 2)
            if code not in codes or not when or not now - 60 <= when <= now + ahead:
                continue
            where, seen = placed.get(text(trip, 1), (None, None))
            visits.append({"RecordedAtTime": stamp(seen or number(t, 4) or feeds.at), "MonitoringRef": code,
                           "MonitoredVehicleJourney": dict(journey(feeds, trip, vehicle, where), MonitoredCall={
                               "StopPointRef": code, "Order": str(number(u, 1) or 0), "ExpectedArrivalTime": stamp(when)})})
    return siri(visits, feeds.at)


def siri(visits, at):
    return {"Siri": {"ServiceDelivery": {"ResponseTimestamp": stamp(at),
                                         "StopMonitoringDelivery": [{"MonitoredStopVisit": visits}]}}}


def handler(feeds):
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            ref = (q.get("MonitoringRef") or ["AllActiveTripsFilter"])[0]
            body = all_trips(feeds) if ref == "AllActiveTripsFilter" else at_stops(feeds, set(ref.split(",")))
            raw = json.dumps(body).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, *args):
            pass
    return Handler


if __name__ == "__main__":
    header = os.environ.get("FEED_HEADER")
    feeds = Feeds(os.environ["VEHICLES"], os.environ.get("TRIPS", ""), os.environ["GTFS_ZIP"],
                  tuple(header.split(":", 1)) if header else None)
    threading.Thread(target=feeds.keep_reading, daemon=True).start()
    http.server.ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8200"))), handler(feeds)).serve_forever()
