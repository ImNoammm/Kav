"""Checks a part of your own against the contract Kav's server reads it by, and says what's wrong in plain words.

    python3 conformance.py plan http://my-planner:9000/plan     a planner of your own (PLAN_URL, the Kav Plan Protocol)
    python3 conformance.py predictions data/eta/out/now.json    a predictor of your own (the predictions file, or the
                                                                address it serves it at, PREDICT_URL)
    python3 conformance.py live http://my-source:8100/siri       a live source of your own (LIVE_URL, SIRI StopMonitoring)

Exit code 0 when it conforms. The contracts: server/PLANNER.md, server/PREDICTOR.md and server/LIVE.md. Python's own
library only."""
import json
import sys
import time
import urllib.parse
import urllib.request

LEG_KINDS = {"w", "b", "t", "x", "r"}


def plan_problems(doc):
    """What's wrong with a planner's answer, as Kav's routes (server/API.md#plan); empty when nothing is."""
    out = []
    if not isinstance(doc, dict):
        return ["the answer isn't a JSON object"]
    routes = doc.get("it")
    if not isinstance(routes, list):
        return ['no "it": the routes list is missing']
    for name in ("stops", "lines", "agencies"):
        if name in doc and not isinstance(doc[name], dict):
            out.append('"%s" isn\'t an object' % name)
    stops, lines = doc.get("stops") or {}, doc.get("lines") or {}
    for i, it in enumerate(routes):
        at = "route %d" % (i + 1)
        if not isinstance(it, dict):
            out.append(at + " isn't an object")
            continue
        for key in ("dep", "arr"):
            if not isinstance(it.get(key), int):
                out.append('%s: "%s" isn\'t a whole number of unix seconds' % (at, key))
        if isinstance(it.get("dep"), int) and isinstance(it.get("arr"), int) and it["arr"] < it["dep"]:
            out.append(at + " arrives before it leaves")
        legs = it.get("legs")
        if not isinstance(legs, list) or not legs:
            out.append(at + ': no "legs"')
            continue
        for j, leg in enumerate(legs):
            where = "%s, leg %d" % (at, j + 1)
            if not isinstance(leg, dict) or leg.get("k") not in LEG_KINDS:
                out.append('%s: "k" must be one of %s' % (where, ", ".join(sorted(LEG_KINDS))))
                continue
            for key in ("dep", "arr"):
                if not isinstance(leg.get(key), int):
                    out.append('%s: "%s" isn\'t a whole number of unix seconds' % (where, key))
            if leg["k"] == "r":
                if str(leg.get("line")) not in lines:
                    out.append('%s: its line %r isn\'t in "lines"' % (where, leg.get("line")))
                calls = leg.get("stops")
                if not isinstance(calls, list) or len(calls) < 2:
                    out.append('%s: a ride needs "stops", from where it\'s boarded to where it\'s left' % where)
                else:
                    out += ['%s: stop %r isn\'t in "stops"' % (where, s) for s in calls if str(s) not in stops]
                if "trip" not in leg:
                    out.append('%s: a ride needs "trip", its trip\'s id' % where)
    for sid, s in stops.items():
        if not (isinstance(s, list) and len(s) >= 4 and isinstance(s[2], (int, float)) and isinstance(s[3], (int, float))):
            out.append('stop %s: must be [code, name, lat, lon, platform]' % sid)
    for lid, line in lines.items():
        if not (isinstance(line, list) and len(line) >= 7 and isinstance(line[6], int)):
            out.append('line %s: must be [number, destination, its city, origin, its city, agency id, route type, name]' % lid)
    return out


def predictions_problems(doc, now=None):
    """What's wrong with a predictions file (server/PREDICTOR.md); empty when nothing is."""
    now = time.time() if now is None else now
    if not isinstance(doc, dict):
        return ["the file isn't a JSON object"]
    out = []
    if doc.get("version", 1) != 1:
        out.append('"version" is %r; Kav\'s server reads 1' % doc.get("version"))
    if not isinstance(doc.get("at"), int):
        out.append('"at" isn\'t a whole number of unix seconds')
    elif now - doc["at"] > 120:
        out.append('"at" is %d seconds old: past 120, Kav\'s server ignores the file' % (now - doc["at"]))
    runs = doc.get("runs")
    if not isinstance(runs, dict):
        return out + ['no "runs" object']
    for key, stops in runs.items():
        route, _, first = key.rpartition("_")
        if not route or not first.isdigit():
            out.append('run %r: its key must be "<route id>_<first departure, unix seconds>"' % key)
        if not isinstance(stops, list) or not all(isinstance(s, list) and len(s) == 3 for s in stops):
            out.append('run %s: must be a list of [place on the trip, stop code, arrival]' % key)
    return out


def siri_problems(doc, stops=None, now=None):
    """What's wrong with a live source's answer (server/LIVE.md): to the all-active-trips question, or with stops, to a
    question about those stops' arrivals. Empty when nothing is."""
    import datetime
    now = time.time() if now is None else now
    out = []
    try:
        delivery = doc["Siri"]["ServiceDelivery"]
    except (KeyError, TypeError):
        return ['no "Siri" → "ServiceDelivery"']
    def when(value, what):
        try:
            return datetime.datetime.fromisoformat(value).timestamp()
        except (TypeError, ValueError):
            out.append('%s: "%s" isn\'t an ISO time with its offset' % (what, value))
            return None
    at = when(delivery.get("ResponseTimestamp"), "ResponseTimestamp")
    if at is not None and abs(now - at) > 300:
        out.append("ResponseTimestamp is %d seconds from now: Kav's server takes a snapshot that old as stale" % abs(now - at))
    parts = delivery.get("StopMonitoringDelivery")
    parts = [parts] if isinstance(parts, dict) else parts
    if not isinstance(parts, list):
        return out + ['no "StopMonitoringDelivery"']
    visits = [v for p in parts for v in (p.get("MonitoredStopVisit") or [])]
    placed = 0
    for i, v in enumerate(visits[:2000]):
        where = "visit %d" % (i + 1)
        j = v.get("MonitoredVehicleJourney") or {}
        if not str(j.get("LineRef", "")).isdigit():
            out.append('%s: "LineRef" must be the route\'s id, a number' % where)
        when(j.get("OriginAimedDepartureTime"), where + ", OriginAimedDepartureTime")
        when(v.get("RecordedAtTime"), where + ", RecordedAtTime")
        loc = j.get("VehicleLocation")
        if loc:
            placed += 1
            try:
                float(loc["Latitude"]), float(loc["Longitude"])
            except (KeyError, TypeError, ValueError):
                out.append('%s: "VehicleLocation" needs "Latitude" and "Longitude"' % where)
        call = j.get("MonitoredCall") or {}
        if stops is not None:
            ref = str(v.get("MonitoringRef") or call.get("StopPointRef") or "")
            if ref not in stops:
                out.append("%s: it's at stop %r, which wasn't asked about" % (where, ref))
            if not (call.get("ExpectedArrivalTime") or call.get("AimedArrivalTime")):
                out.append('%s: a stop\'s arrival needs "ExpectedArrivalTime" (or "AimedArrivalTime")' % where)
        if len(out) > 20:
            out.append("… and more")
            break
    if stops is None and visits and not placed:
        out.append('no visit has a "VehicleLocation": the map would show no vehicles')
    return out


def ask_plan(url):
    """The trip conformance asks: central Tel Aviv to Tel Aviv University, leaving in ten minutes."""
    q = {"from": "32.075400,34.774900", "to": "32.113300,34.804400", "time": str(int(time.time()) + 600), "way": "d"}
    with urllib.request.urlopen(url + ("&" if "?" in url else "?") + urllib.parse.urlencode(q), timeout=60) as r:
        return json.load(r)


def ask_live(url, query):
    with urllib.request.urlopen(url + ("&" if "?" in url else "?") + query, timeout=60) as r:
        return json.load(r)


def main(args):
    if len(args) != 2 or args[0] not in ("plan", "predictions", "live"):
        print(__doc__)
        return 2
    try:
        if args[0] == "plan":
            problems = plan_problems(ask_plan(args[1]))
        elif args[0] == "live":
            every = ask_live(args[1], "MonitoringRef=AllActiveTripsFilter&StopVisitDetailLevel=normal")
            problems = ["all active trips: " + p for p in siri_problems(every)]
            # A stop that some vehicle is at, asked about as a stop board asks.
            parts = every["Siri"]["ServiceDelivery"]["StopMonitoringDelivery"]
            parts = [parts] if isinstance(parts, dict) else parts
            codes = [str(v.get("MonitoringRef") or "") for p in parts for v in p.get("MonitoredStopVisit") or []]
            codes = [c for c in codes if c][:3]
            if codes:
                stops = ask_live(args[1], "MonitoringRef=%s&StopVisitDetailLevel=normal&PreviewInterval=PT90M" % ",".join(codes))
                problems += ["stops %s: %s" % (",".join(codes), p) for p in siri_problems(stops, set(codes))]
        elif args[1].startswith(("http://", "https://")):
            with urllib.request.urlopen(args[1], timeout=20) as r:
                problems = predictions_problems(json.load(r))
        else:
            with open(args[1], encoding="utf-8") as f:
                problems = predictions_problems(json.load(f))
    except (OSError, ValueError) as e:
        problems = ["couldn't read it: %s" % e]
    for p in problems:
        print("✗", p)
    print("✓ conforms" if not problems else "%d problem%s" % (len(problems), "" if len(problems) == 1 else "s"))
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
