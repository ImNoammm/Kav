"""A live source of your own, as small as one can be: it answers Kav's server (LIVE_URL) with a recorded SIRI answer,
its times moved to now, so the buses in it show as if they were on the road. Start from it, or try a live source with no
key at all:

    python3 examples/live_replay.py recorded.json                 serves on :8100 (PORT to change it)
    python3 conformance.py live http://127.0.0.1:8100/siri        checks it

Then LIVE_URL=http://<this machine>:8100/siri. The contract: server/LIVE.md. recorded.json is any answer to the
all-active-trips question, as the ministry gives it (how to save one: server/LIVE.md, "Saving a recording")."""
import datetime
import http.server
import json
import os
import re
import sys
import urllib.parse

STAMP = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?[+-]\d\d:\d\d$")


def moved(value, by):
    """Every time in the answer moved by `by`, the dates in it too."""
    if isinstance(value, dict):
        return {k: moved(v, by) for k, v in value.items()}
    if isinstance(value, list):
        return [moved(v, by) for v in value]
    if isinstance(value, str) and STAMP.match(value):
        return (datetime.datetime.fromisoformat(value) + by).isoformat(timespec="seconds")
    return value


def answer(recorded, stops=None, now=None):
    """The recorded answer as of now; only the visits to `stops` (their codes) when a stop question asks for them."""
    delivery = recorded["Siri"]["ServiceDelivery"]
    then = datetime.datetime.fromisoformat(delivery["ResponseTimestamp"])
    now = now or datetime.datetime.now(then.tzinfo)
    doc = moved(recorded, now - then)
    if stops is not None:
        part = doc["Siri"]["ServiceDelivery"]["StopMonitoringDelivery"]
        part = part[0] if isinstance(part, list) else part
        part["MonitoredStopVisit"] = [v for v in part.get("MonitoredStopVisit") or []
                                      if (v.get("MonitoringRef") or v["MonitoredVehicleJourney"].get("MonitoredCall", {})
                                          .get("StopPointRef")) in stops]
    return doc


def handler(recorded):
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            ref = (q.get("MonitoringRef") or ["AllActiveTripsFilter"])[0]
            body = answer(recorded, None if ref == "AllActiveTripsFilter" else set(ref.split(",")))
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
    with open(sys.argv[1], encoding="utf-8") as f:
        recorded = json.load(f)
    http.server.ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8100"))), handler(recorded)).serve_forever()
