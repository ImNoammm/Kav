"""A planner of your own, as small as one can be: it answers Kav's server (PLAN_URL) with one route, walking straight
there. Start from it and plan real trips instead. It passes the conformance check:

    python3 examples/planner.py                                     serves on :9000 (PORT to change it)
    python3 conformance.py plan http://127.0.0.1:9000/plan          checks it

Then in .env: PLANNER=own and PLAN_URL=http://<this machine>:9000/plan. The protocol: server/PLANNER.md."""
import http.server
import json
import math
import os
import urllib.parse

WALK_SPEED = 1.3  # metres a second


def polyline(points):
    """A Google-encoded polyline, precision 5, as Kav's routes carry their shapes."""
    out, last = [], (0, 0)
    for lat, lon in points:
        now = (round(lat * 1e5), round(lon * 1e5))
        for d in (now[0] - last[0], now[1] - last[1]):
            d = ~(d << 1) if d < 0 else d << 1
            while d >= 0x20:
                out.append(chr((0x20 | (d & 0x1f)) + 63))
                d >>= 5
            out.append(chr(d + 63))
        last = now
    return "".join(out)


def plan(frm, to, when, way):
    """Kav's routes for one trip: here, a single walk. way: d leave at `when`, a arrive by it, n now, l the last way."""
    metres = 6371000 * math.hypot(math.radians(to[0] - frm[0]),
                                  math.radians(to[1] - frm[1]) * math.cos(math.radians(frm[0])))
    takes = round(metres / WALK_SPEED)
    dep = when - takes if way in "al" else when
    walk = {"k": "w", "dep": dep, "arr": dep + takes, "m": round(metres), "shape": polyline([frm, to])}
    return {"it": [{"dep": dep, "arr": dep + takes, "tags": [], "legs": [walk]}], "stops": {}, "lines": {}, "agencies": {},
            "errors": []}


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        q = {k: v[0] for k, v in urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).items()}
        if "from" not in q:
            body = {"ok": True}  # a plain GET: Kav's server asks this to tell the app the planner is up
        else:
            point = lambda s: tuple(float(x) for x in s.split(","))
            body = plan(point(q["from"]), point(q["to"]), int(q["time"]), q.get("way", "d"))
        raw = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


if __name__ == "__main__":
    http.server.ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "9000"))), Handler).serve_forever()
