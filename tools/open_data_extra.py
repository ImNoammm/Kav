"""Nightly busy and fleet inputs, using the server's routines unchanged.

python3 tools/open_data_extra.py <ministry GTFS> <saved timetable folder> <output JSON>
The timetable folder is a read-only copy of LATENESS/timetable from Kav's server.
No arrivals, rider data, API keys or recordings are needed.
"""
import datetime
import hashlib
import json
import os
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "server"))
import open_data_source as live


def collect(gtfs, timetables, fetch=None):
    today = datetime.datetime.now(live.ISRAEL).date()
    names = sorted(n for n in os.listdir(timetables) if n.endswith(".json.gz"))
    if not names or (today - datetime.date.fromisoformat(names[-1][:10])).days > 2:
        raise ValueError("saved timetables are missing or old")
    with zipfile.ZipFile(gtfs) as z:
        text = z.read("routes.txt").decode("utf-8-sig")
    lines = live.ministry_lines(text)
    with tempfile.TemporaryDirectory() as work:
        problem, busy = live.busy_rebuild(lines, today=today, out=work, timetables=timetables, fetch=fetch)
        if problem or not busy or not busy["routes"]:
            raise ValueError(problem or "no busy levels")
        with open(os.path.join(work, "taps.json")) as f:
            taps = json.load(f)
    source, buses = live.fleet_file(fetch, None)
    if not buses:
        raise ValueError("empty fleet")
    rows = []
    for route, kinds in sorted(busy["routes"].items(), key=lambda p: int(p[0])):
        direction = lines[route].split("-")[1]
        for day in range(7):
            kind = "weekday" if day < 5 else "friday" if day == 5 else "saturday"
            for hour, level in enumerate(kinds.get(kind, "")):
                rows.append([route, direction, day, hour, level])
    def fingerprint(doc):
        return hashlib.sha256(json.dumps(doc, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    digest = hashlib.sha256()
    for name in names:
        digest.update(name.encode())
        with open(os.path.join(timetables, name), "rb") as f:
            digest.update(f.read())
    return {"checked": today.isoformat(), "busy_to": busy["to"], "month": busy["month"], "days": busy["days"],
            "busy": rows, "fleet": [[plate, *bus] for plate, bus in sorted(buses.items(), key=lambda p: int(p[0]))],
            "sources": {"mot-busy": {"published": taps["source"]["modified"], "resource": taps["source"]["resource"],
                                      "from": busy["from"], "to": busy["to"], "sha256": fingerprint(taps),
                                      "timetables_sha256": digest.hexdigest(), "timetables_from": names[0][:10],
                                      "timetables_to": names[-1][:10]},
                        "mot-fleet": {"published": source["modified"], "resource": source["resource"],
                                      "sha256": fingerprint(buses)}}}


if __name__ == "__main__":
    doc = collect(*sys.argv[1:3])
    with open(sys.argv[3], "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
