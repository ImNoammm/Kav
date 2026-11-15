"""Tests for open_data.py on a small timetable made here: cd tools && python3 -m unittest test_open_data"""
import csv
import gzip
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch
import datetime

import open_data


def gtfs(path, tables):
    with zipfile.ZipFile(path, "w") as z:
        for name, rows in tables.items():
            out = io.StringIO()
            csv.writer(out, lineterminator="\n").writerows(rows)
            z.writestr(name, "﻿" + out.getvalue())


MOT = {
    "agency.txt": [["agency_id", "agency_name", "agency_url", "agency_timezone", "agency_lang", "agency_phone"],
                   ["2", "רכבת ישראל", "https://rail.example.org", "Asia/Jerusalem", "he", "5770"],
                   ["3", "אגד", "https://egged.example.org", "Asia/Jerusalem", "he", "2800"],
                   ["20", "כרמלית", "https://carmelit.example.org", "Asia/Jerusalem", "he", "8787"]],
    "stops.txt": [["stop_id", "stop_code", "stop_name", "stop_desc", "stop_lat", "stop_lon", "location_type", "parent_station",
                   "zone_id"],
                  ["1", "38831", "בי''ס בר לב/בן יהודה", "רחוב: בן יהודה 74 עיר: כפר סבא רציף:  קומה: ", "32.183985", "34.917554", "0", "", "38831"],
                  ["2", "63235", "העצמאות/שד. ירושלים", "רחוב: העצמאות עיר: אריאל רציף:  קומה: ", "32.102837", "35.178064", "0", "", "63235"],
                  ["3", "40000", "ת. מרכזית/רציפים", "רחוב: הרצל עיר: כפר סבא רציף: 4 קומה: ", "32.19", "34.92", "0", "", "40000"],
                  ["4", "17038", "תל אביב סבידור מרכז", "רחוב: מסילת ברזל עיר: תל אביב יפו", "32.083", "34.798", "0", "", "17038"],
                  ["5", "17000", "חיפה מרכז", "רחוב: מסילת ברזל עיר: חיפה", "32.82", "34.99", "0", "", "17000"]],
    "routes.txt": [["route_id", "agency_id", "route_short_name", "route_long_name", "route_desc", "route_type", "route_color"],
                   ["10", "3", "5", "בי''ס בר לב/בן יהודה-כפר סבא<->העצמאות/שד. ירושלים-אריאל-1#", "10005-1-#", "3", "FF9933"],
                   ["11", "20", "1", "מרכז הכרמל-חיפה<->עיר תחתית-חיפה-20", "93001-2-0", "5", ""],
                   ["12", "2", "", "תל אביב סבידור מרכז-תל אביב יפו<->חיפה מרכז-חיפה", "100", "2", ""]],
    "trips.txt": [["route_id", "service_id", "trip_id", "trip_headsign", "direction_id", "shape_id"],
                  ["10", "1", "t1", "אריאל", "0", "s1"], ["10", "1", "t2", "אריאל", "0", "s2"],
                  ["10", "1", "t3", "אריאל", "0", "s1"], ["11", "1", "t4", "עיר תחתית", "1", ""],
                  ["12", "1", "t5", "חיפה מרכז", "0", ""]],
    "stop_times.txt": [["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"],
                       ["t1", "08:00:00", "08:00:00", "1", "1"], ["t1", "08:30:00", "08:30:00", "2", "2"],
                       ["t2", "09:00:00", "09:00:00", "1", "1"], ["t2", "09:30:00", "09:30:00", "3", "2"],
                       ["t3", "10:00:00", "10:00:00", "1", "1"], ["t3", "10:30:00", "10:30:00", "2", "2"],
                       ["t4", "08:00:00", "08:00:00", "3", "1"], ["t4", "08:08:00", "08:08:00", "1", "2"],
                       ["t5", "08:00:00", "08:00:00", "4", "1"], ["t5", "09:00:00", "09:00:00", "5", "2"]],
    "shapes.txt": [["shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence"],
                   ["s1", "32.183985", "34.917554", "1"], ["s1", "32.15", "35.0", "2"], ["s1", "32.102837", "35.178064", "3"],
                   ["s2", "32.183985", "34.917554", "1"], ["s2", "32.19", "34.92", "2"]],
    "calendar.txt": [["service_id", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "start_date",
                      "end_date"], ["1", "1", "1", "1", "1", "1", "0", "0", "20261113", "20261213"]],
    "fare_attributes.txt": [["fare_id", "price", "currency_type", "payment_method", "transfers", "agency_id", "transfer_duration"],
                            ["201", "8.00", "ILS", "0", "0", "", ""], ["202", "14.50", "ILS", "0", "0", "", ""],
                            ["211", "11.50", "ILS", "0", "0", "", ""]],
    "fare_rules.txt": [["fare_id", "route_id", "origin_id", "destination_id", "contains_id"],
                       ["201", "", "38831", "40000", ""], ["202", "", "38831", "63235", ""], ["211", "", "17038", "17000", ""],
                       ["201", "11", "", "", ""]],
    "translations.txt": [["trans_id", "lang", "translation"],
                         ["בי\"ס בר לב/בן יהודה", "EN", "Bar Lev School/Ben Yehuda"],
                         ["בי''ס בר לב/בן יהודה", "AR", "مدرسة بار ليڤ/بن يهودا"],
                         ["כפר סבא", "EN", "Kfar Saba"], ["אריאל", "EN", "Ariel"]],
}
NAIM = {
    "agency.txt": [["agency_id", "agency_name", "agency_url", "agency_timezone"], ["1", "Ryde", "https://weekend.example.org", "Asia/Jerusalem"]],
    "stops.txt": [["stop_id", "stop_code", "stop_name", "stop_lat", "stop_lon"],
                  ["a", "38831", "בר לב", "32.18399", "34.91756"], ["7", "", "כיכר רבין", "32.08", "34.78"]],
    "routes.txt": [["route_id", "agency_id", "route_short_name", "route_long_name", "route_type"],
                   ["5", "1", "705", "מגבעתיים אל תל אביב יפו 705", "3"]],
    "trips.txt": [["route_id", "service_id", "trip_id", "shape_id"], ["5", "w", "1_x", "9"]],
    "stop_times.txt": [["trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"],
                       ["1_x", "22:00:00", "22:00:00", "a", "1"], ["1_x", "22:20:00", "22:20:00", "7", "2"]],
    "shapes.txt": [["shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence"],
                   ["9", "32.18399", "34.91756", "1"], ["9", "32.08", "34.78", "2"]],
    "calendar.txt": [["service_id", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "start_date",
                      "end_date"], ["w", "0", "0", "0", "0", "0", "1", "1", "20261113", "20261213"]],
}
RAIL = {"roads": [[[32.083, 34.798], [32.5, 34.9], [32.82, 34.99]]], "routes": {"12": 0}}


def read(out, name):
    return list(csv.DictReader(io.TextIOWrapper(gzip.open(os.path.join(out, name)), encoding="utf-8")))


class OpenDataTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.mot, self.naim = os.path.join(self.dir, "mot.zip"), os.path.join(self.dir, "naim.zip")
        self.rail = os.path.join(self.dir, "rail-shapes.json")
        gtfs(self.mot, MOT)
        gtfs(self.naim, NAIM)
        with open(self.rail, "w") as f:
            json.dump(RAIL, f)
        self.out = os.path.join(self.dir, "out")
        self.extra = os.path.join(self.dir, "extra.json")
        with open(self.extra, "w") as f:
            json.dump({"checked": datetime.date.today().isoformat(), "busy_to": datetime.date.today().isoformat(),
                       "busy": [["10", "1", 0, 8, "3"]], "fleet": [["1234567", "bus", 40, 2025, "electric"]],
                       "sources": {k: {"published": "2026-11-03T10:44:53.865253", "sha256": "ab" * 32} for k in ("mot-busy", "mot-fleet")}}, f)
        env = patch.dict(os.environ, KAV_EXTRA=self.extra)
        env.start()
        self.addCleanup(env.stop)
        self.manifest = open_data.build(self.mot, self.out, self.naim, self.rail)

    def test_stops_carry_names_their_timetable_s_position_and_the_routes_at_them(self):
        stops = {s["stop_id"]: s for s in read(self.out, "stops.csv.gz")}
        first = stops["1"]
        self.assertEqual((first["code"], first["name_en"], first["name_ar"], first["street_he"], first["city_he"], first["city_en"]),
                         ("38831", "Bar Lev School/Ben Yehuda", "مدرسة بار ليڤ/بن يهودا", "בן יהודה 74", "כפר סבא", "Kfar Saba"))
        # The weekend line calls at the ministry's stop of the same code, and at a stop of its own, NAIM_OFFSET up.
        self.assertEqual(first["route_ids"], "10 11 9000005")
        self.assertEqual(stops["9000007"]["route_ids"], "9000005")
        # Ariel's stop 63235 stays where the ministry puts it: the planner's correction (server/otp/prepare.py's
        # STOP_POINTS) is OpenStreetMap's node, which these files don't carry.
        self.assertEqual((stops["2"]["lat"], stops["2"]["lon"]), ("32.102837", "35.178064"))
        self.assertNotIn("moved", stops["2"])
        self.assertEqual(stops["3"]["platform"], "4")

    def test_routes_are_typed_as_the_app_has_them(self):
        routes = {r["route_id"]: r for r in read(self.out, "routes.csv.gz")}
        bus, carmelit, train, weekend = routes["10"], routes["11"], routes["12"], routes["9000005"]
        self.assertEqual((bus["kind"], bus["mot_color"], bus["mot_line_id"], bus["direction"], bus["variant"]),
                         ("bus", "FF9933", "10005", "1", "#"))
        self.assertEqual((bus["from_he"], bus["from_city_en"], bus["to_city_en"], bus["headsign_he"]),
                         ("בי''ס בר לב/בן יהודה", "Kfar Saba", "Ariel", "אריאל"))
        self.assertEqual((carmelit["type"], carmelit["kind"]), ("7", "funicular"))
        self.assertEqual((train["kind"], train["mot_line_id"], train["direction"]), ("train", "100", ""))
        self.assertEqual((weekend["operator_he"], weekend["number"], weekend["to_city_he"]), (open_data.NAIM_NAME, "705", "תל אביב יפו"))

    def test_lines_and_operators_carry_the_colours_the_app_draws_them_in(self):
        routes = {r["route_id"]: r for r in read(self.out, "routes.csv.gz")}
        # Last in each row, so a reader going by name, as the page says, takes the column in its stride.
        self.assertEqual(list(routes["10"])[-2:], ["mot_color", "color"])
        # A bus in Egged's colour, the Carmelit, a train and light rail in their modes', a weekend line in none.
        self.assertEqual([routes[r]["color"] for r in ("10", "11", "12", "9000005")], ["018369", "0A822E", "1F6FD0", ""])
        operators = {r["agency_id"]: r for r in read(self.out, "operators.csv.gz")}
        self.assertEqual((list(operators["3"])[-1], operators["3"]["color"], operators["20"]["color"]), ("color", "018369", ""))

    def test_roads_are_each_route_s_most_common_shape(self):
        # To five decimals as server/live.py's encode() rounds them (32.183985 to 32.18398).
        roads = json.load(gzip.open(os.path.join(self.out, "roads.geojson.gz")))
        by_route = {r: f for f in roads["features"] for r in f["properties"]["route_ids"]}
        self.assertEqual(by_route["10"]["geometry"]["coordinates"], [[34.91755, 32.18398], [35.0, 32.15], [35.17806, 32.10284]])
        self.assertEqual(by_route["9000005"]["properties"]["number"], "705")
        rail = json.load(gzip.open(os.path.join(self.out, "rail-roads.geojson.gz")))
        self.assertEqual([f["properties"]["route_ids"] for f in rail["features"]], [["12"]])
        self.assertEqual(rail["features"][0]["geometry"]["coordinates"][0], [34.798, 32.083])

    def test_fares_are_the_ministry_s_pairs_and_kav_s_rules(self):
        self.assertEqual([tuple(r.values()) for r in read(self.out, "fares.csv.gz")],
                         [("17038", "17000", "211", "1150"), ("38831", "40000", "201", "800"), ("38831", "63235", "202", "1450")])
        doc = json.load(gzip.open(os.path.join(self.out, "fares.json.gz")))
        self.assertEqual(doc["route_fares"], {"11": "201"})
        near = next(p for p in doc["products"] if p["fare_id"] == "201")
        self.assertEqual((near["price_agorot"], near["kind"], near["up_to_km"], near["includes_changes_minutes"]), (800, "bus", 15, 90))
        self.assertEqual(doc["day_passes"]["bus"][0], {"up_to_km": 15, "price_agorot": 1750})

    def test_the_manifest_matches_the_files_and_names_each_licence(self):
        names = [f["name"] for f in self.manifest["files"]]
        self.assertEqual(names, ["stops.csv.gz", "routes.csv.gz", "operators.csv.gz", "roads.geojson.gz", "fares.csv.gz",
                                 "fares.json.gz", "rail-roads.geojson.gz", "busy.csv.gz", "fleet.csv.gz"])
        for f in self.manifest["files"]:
            with open(os.path.join(self.out, f["name"]), "rb") as g:
                data = g.read()
            self.assertEqual((len(data), hashlib.sha256(data).hexdigest()), (f["size"], f["sha256"]))
        licences = {f["name"]: f["licence"] for f in self.manifest["files"]}
        self.assertEqual(licences["rail-roads.geojson.gz"], "ODbL-1.0")
        self.assertEqual({v for k, v in licences.items() if k not in ("rail-roads.geojson.gz", "busy.csv.gz", "fleet.csv.gz")}, {"CC-BY-4.0"})
        self.assertEqual(set(self.manifest["sources"]), {"mot-gtfs", "tel-aviv-weekend", "osm", "kav", "kav-colours", "mot-busy", "mot-fleet"})
        self.assertEqual(self.manifest["sources"]["mot-gtfs"]["from"], "20261113")
        with open(os.path.join(self.out, "manifest.json"), encoding="utf-8") as f:
            self.assertEqual(json.load(f), self.manifest)

    def test_busy_and_fleet_keep_server_values_and_licences(self):
        self.assertEqual(read(self.out, "busy.csv.gz"), [{"route_id": "10", "direction": "1", "day": "0", "hour": "8", "level": "3"}])
        self.assertEqual(read(self.out, "fleet.csv.gz")[0]["plate"], "1234567")
        self.assertEqual(self.manifest["sources"]["mot-busy"]["published"], "2026-11-03T10:44:53")
        self.assertEqual(self.manifest["sources"]["mot-busy"]["modified"], "2026-11-03T10:44:53.865253")
        for entry in self.manifest["files"][-2:]:
            self.assertEqual(entry["licence"], "data-gov-il")
            self.assertIn("Israel Ministry of Transport, data.gov.il", entry["credit"])

    def test_old_inputs_refuse_to_build(self):
        with open(self.extra) as f:
            extra = json.load(f)
        extra["checked"] = "2000-01-01"
        with open(self.extra, "w") as f:
            json.dump(extra, f)
        with self.assertRaises(ValueError):
            open_data.build(self.mot, os.path.join(self.dir, "old"), self.naim, self.rail)

    def test_bad_busy_and_fleet_rows_are_refused(self):
        for name, column, value in (("busy.csv.gz", "level", "4"), ("fleet.csv.gz", "plate", "unknown")):
            rows = read(self.out, name)
            rows[0][column] = value
            self.rewrite(name, rows, list(rows[0]))
        problems = open_data.check(self.out, floors=self.SMALL, first_run=True)
        self.assertIn("invalid busy route, day, hour or level", problems)
        self.assertIn("invalid fleet row", problems)

    def test_fare_rules_claim_no_rider_s_discount(self):
        rules = json.load(gzip.open(os.path.join(self.out, "fares.json.gz")))["rules"]
        self.assertFalse([r for r in rules if "half" in r or "Senior citizens pay" in r])
        self.assertTrue(any("regular full fares" in r for r in rules))

    def test_each_file_credits_its_own_sources_and_only_the_train_roads_openstreetmap(self):
        credit = {f["name"]: f["credit"] for f in self.manifest["files"]}
        self.assertEqual([n for n, c in credit.items() if "OpenStreetMap" in c], ["rail-roads.geojson.gz"])
        self.assertEqual([n for n, c in credit.items() if "Sammy Zaionz" in c], ["routes.csv.gz", "operators.csv.gz"])
        self.assertEqual(credit["fares.csv.gz"], "Israel Ministry of Transport; Kav (kavapp.link)")
        self.assertEqual(credit["rail-roads.geojson.gz"],
                         "Israel Ministry of Transport; © OpenStreetMap contributors (https://www.openstreetmap.org/copyright); "
                         "Kav (kavapp.link)")
        self.assertIn("Tel Aviv-Yafo Municipality", credit["stops.csv.gz"])
        self.assertEqual({k: sorted(v) for k, v in self.manifest["licences"].items()},
                         {"CC-BY-4.0": ["name", "url"], "ODbL-1.0": ["name", "url"], "GPL-3.0": ["name", "url"], "data-gov-il": ["name", "url"]})

    def test_the_openstreetmap_extract_is_named_when_given(self):
        doc = open_data.build(self.mot, os.path.join(self.dir, "osm"), self.naim, self.rail, osm_sha256="ab" * 32)
        self.assertEqual(doc["sources"]["osm"]["sha256"], "ab" * 32)

    # The gate (finding 1): what is published must be whole, above its floors, near last night's and well formed.

    SMALL = dict({n: (1, 1) for n in ("stops.csv.gz", "routes.csv.gz", "operators.csv.gz", "roads.geojson.gz", "fares.csv.gz",
                                      "fares.json.gz", "rail-roads.geojson.gz", "busy.csv.gz", "fleet.csv.gz")},
                 roads_cover=0.5, rail_cover=1.0, stops_served=0.5, stop_moved_most=1_000, stops_moved=0.2)


    def replace_payload(self, name, raw):
        data = open_data.gz(raw)
        with open(os.path.join(self.out, name), "wb") as stream:
            stream.write(data)
        path = os.path.join(self.out, "manifest.json")
        with open(path) as stream:
            doc = json.load(stream)
        next(e for e in doc["files"] if e["name"] == name).update(size=len(data), sha256=hashlib.sha256(data).hexdigest())
        with open(path, "w") as stream:
            json.dump(doc, stream)

    def test_rehashed_invalid_geojson_is_refused(self):
        for name in ("roads.geojson.gz", "rail-roads.geojson.gz"):
            with self.subTest(name=name):
                with gzip.open(os.path.join(self.out, name)) as stream:
                    raw = stream.read()
                self.replace_payload(name, raw.rstrip()[:-1])
                self.assertTrue(any("GeoJSON" in p for p in open_data.check(self.out, floors=self.SMALL, first_run=True)))
                self.replace_payload(name, raw)

    def test_geojson_structure_and_finite_pairs_are_checked(self):
        import copy
        for name in ("roads.geojson.gz", "rail-roads.geojson.gz"):
            with gzip.open(os.path.join(self.out, name)) as stream:
                good = json.load(stream)
            changes = [lambda d: d.update(type="Feature"), lambda d: d.update(features={}),
                       lambda d: d["features"][0].update(type="other"),
                       lambda d: d["features"][0]["geometry"].update(type="Point"),
                       lambda d: d["features"][0]["geometry"].update(coordinates=[[1, 2]]),
                       lambda d: d["features"][0]["geometry"].update(coordinates=[[float("nan"), 2], [3, 4]]),
                       lambda d: d["features"][0]["geometry"].update(coordinates=[[True, 2], [3, 4]]),
                       lambda d: d["features"][0]["properties"].update(route_ids=[7])]
            for change in changes:
                with self.subTest(name=name, change=changes.index(change)):
                    doc = copy.deepcopy(good)
                    change(doc)
                    self.replace_payload(name, json.dumps(doc).encode())
                    self.assertTrue(any("GeoJSON" in p for p in open_data.check(self.out, floors=self.SMALL, first_run=True)))
            self.replace_payload(name, json.dumps(good).encode())

    def test_blank_csv_lines_do_not_inflate_the_measured_count(self):
        name = "busy.csv.gz"
        with gzip.open(os.path.join(self.out, name)) as stream:
            raw = stream.read()
        self.replace_payload(name, raw + b"\n" * 100)
        path = os.path.join(self.out, "manifest.json")
        with open(path) as stream:
            doc = json.load(stream)
        next(e for e in doc["files"] if e["name"] == name)["rows"] += 100
        with open(path, "w") as stream:
            json.dump(doc, stream)
        self.assertTrue(any("manifest says" in p for p in open_data.check(self.out, floors=self.SMALL, first_run=True)))

    def test_every_payload_count_must_match_the_manifest(self):
        path = os.path.join(self.out, "manifest.json")
        for name in open_data.NAMES:
            with self.subTest(name=name):
                doc = json.loads(json.dumps(self.manifest))
                next(e for e in doc["files"] if e["name"] == name)["rows"] += 10
                with open(path, "w") as stream:
                    json.dump(doc, stream)
                self.assertTrue(any("manifest says" in p for p in open_data.check(self.out, floors=self.SMALL, first_run=True)))

    def test_the_gate_passes_a_whole_night(self):
        self.assertEqual(open_data.check(self.out, floors=self.SMALL, first_run=True), [])

    def test_the_gate_refuses_a_feed_whose_roads_are_missing(self):
        broken = dict(MOT, **{"shapes.txt": MOT["shapes.txt"][:1]})
        gtfs(os.path.join(self.dir, "broken.zip"), broken)
        out = os.path.join(self.dir, "broken")
        open_data.build(os.path.join(self.dir, "broken.zip"), out, self.naim, self.rail)
        problems = open_data.check(out, floors=self.SMALL, first_run=True)
        self.assertTrue(any("lines with a road" in p for p in problems), problems)

    def test_the_gate_refuses_a_night_far_below_the_last_unless_told(self):
        last = {f["name"]: dict(f, rows=f["rows"] * 2) for f in self.manifest["files"]}
        problems = open_data.check(self.out, last, floors=self.SMALL)
        self.assertEqual(len(problems), 9, problems)
        self.assertTrue(all("fell to" in p for p in problems))
        self.assertEqual(open_data.check(self.out, last, floors=self.SMALL, accept_drop=True), [])

    def test_the_gate_refuses_a_missing_or_altered_file(self):
        os.remove(os.path.join(self.out, "roads.geojson.gz"))
        with open(os.path.join(self.out, "stops.csv.gz"), "ab") as f:
            f.write(b"x")
        problems = open_data.check(self.out, floors=self.SMALL, first_run=True)
        self.assertIn("roads.geojson.gz is missing", problems)
        self.assertIn("stops.csv.gz isn't the file its manifest names", problems)

    def test_the_gate_refuses_files_that_disagree(self):
        rows = read(self.out, "stops.csv.gz")
        rows[0]["route_ids"] += " 999"
        out = io.StringIO()
        w = csv.DictWriter(out, list(rows[0]), lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
        data = open_data.gz(out.getvalue().encode())
        with open(os.path.join(self.out, "stops.csv.gz"), "wb") as f:
            f.write(data)
        manifest_path = os.path.join(self.out, "manifest.json")
        with open(manifest_path, encoding="utf-8") as f:
            doc = json.load(f)
        entry = next(f for f in doc["files"] if f["name"] == "stops.csv.gz")
        entry.update(size=len(data), sha256=hashlib.sha256(data).hexdigest())
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(doc, f)
        self.assertEqual(open_data.check(self.out, floors=self.SMALL, first_run=True), ["a stop or a road names a route routes.csv.gz hasn't"])

    def rewrite(self, name, rows, header):
        """A file of the night replaced by these rows, its manifest entry with it (what a broken build could leave)."""
        out = io.StringIO()
        w = csv.DictWriter(out, header, lineterminator="\n", extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
        data = open_data.gz(out.getvalue().encode())
        with open(os.path.join(self.out, name), "wb") as f:
            f.write(data)
        path = os.path.join(self.out, "manifest.json")
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        next(e for e in doc["files"] if e["name"] == name).update(size=len(data), sha256=hashlib.sha256(data).hexdigest())
        with open(path, "w", encoding="utf-8") as f:
            json.dump(doc, f)

    def test_the_gate_refuses_routes_without_their_colour_column_or_with_a_bad_colour(self):
        rows = read(self.out, "routes.csv.gz")
        self.rewrite("routes.csv.gz", rows, [c for c in rows[0] if c != "color"])
        self.assertTrue(any("routes.csv.gz's columns" in p for p in open_data.check(self.out, floors=self.SMALL, first_run=True)))
        rows[0]["color"] = "red"
        self.rewrite("routes.csv.gz", rows, list(rows[0]))
        self.assertEqual(open_data.check(self.out, floors=self.SMALL, first_run=True), ["a colour isn't six hex digits"])

    # v5, 4: the colours keep the licence of the code they come from

    def test_the_colour_column_is_said_to_be_under_kav_s_code_licence_and_the_rest_under_the_file_s(self):
        notes = {f["name"]: f.get("licence_note") for f in self.manifest["files"]}
        self.assertEqual({n for n, note in notes.items() if note and n not in ("busy.csv.gz", "fleet.csv.gz")}, {"routes.csv.gz", "operators.csv.gz"})
        for name in ("routes.csv.gz", "operators.csv.gz"):
            for said in ("color", "Sammy Zaionz", "GPL-3.0", "CC BY 4.0"):
                self.assertIn(said, notes[name])
        self.assertEqual(self.manifest["licences"]["GPL-3.0"]["url"], "https://www.gnu.org/licenses/gpl-3.0.html")
        with open(os.path.join(open_data.HERE, "site", "docs", "data.md"), encoding="utf-8") as f:
            page = " ".join(f.read().split())
        self.assertIn("contributed by Sammy Zaionz, and is under that code's licence, GPL-3.0", page)

    # v6, 3: stops inside Israel and the West Bank by a real outline, none moved far, few moved at all

    def moved(self, change):
        """The night's stops changed by change(rows), with last night's as built; check()'s problems against it."""
        last = {f["name"]: f for f in self.manifest["files"]}
        last_stops = os.path.join(self.dir, "last-stops.csv.gz")
        if not os.path.exists(last_stops):
            shutil.copy(os.path.join(self.out, "stops.csv.gz"), last_stops)
        rows = list(csv.DictReader(io.TextIOWrapper(gzip.open(last_stops), encoding="utf-8")))
        change(rows)
        self.rewrite("stops.csv.gz", rows, list(rows[0]))
        return lambda **kw: open_data.check(self.out, last, floors=kw.pop("floors", self.SMALL), previous_stops=last_stops, **kw)

    def test_the_gate_refuses_a_stop_in_jordan_though_inside_the_old_box(self):
        problems = self.moved(lambda rows: rows[0].update(lat="31.896872", lon="35.984825"))()  # Amman (the UN's record)
        self.assertIn("1 stop outside Israel and the West Bank, more than 2 km past their outline: 1", problems)

    def test_every_place_kav_s_stops_are_is_inside_the_outline_and_its_neighbours_capitals_are_not(self):
        inside = [(32.183985, 34.917554), (29.549580, 34.966804), (33.282204, 35.578985), (30.485723, 35.175049),
                  (31.778, 35.235), (32.2211, 35.2544)]  # Kfar Saba, Eilat, the Golan, the Arava, Jerusalem, Nablus
        outside = [(31.896872, 35.984825), (29.532, 35.0063), (33.8938, 35.5018), (30.0444, 31.2357), (33.5138, 36.2765)]
        self.assertEqual([open_data.within_outline(*p) for p in inside], [True] * len(inside))
        self.assertEqual([open_data.within_outline(*p) for p in outside], [False] * len(outside))

    def test_the_gate_refuses_one_stop_moved_5_km_unless_told(self):
        check = self.moved(lambda rows: rows[0].update(lat="%.6f" % (float(rows[0]["lat"]) + 0.045)))
        self.assertEqual([p.split(" moved")[0] for p in check()], ["stop 1"])
        self.assertEqual(check(accept_drop=True), [])

    def test_the_gate_refuses_many_stops_moved_150_m_and_passes_a_few(self):
        nudge = lambda r: r.update(lat="%.6f" % (float(r["lat"]) + 0.00135))  # about 150 m
        self.assertEqual(self.moved(lambda rows: nudge(rows[0]))(), [])  # 1 of 6: under this test's 20%
        check = self.moved(lambda rows: [nudge(r) for r in rows[:3]])
        self.assertEqual(check(), ["3 of 6 stops moved over 50 m from last night's, over 20.0%"])
        self.assertEqual(check(accept_drop=True), [])

    # v6, 4: without last night's manifest, nothing is published but a first night or by hand

    def test_the_gate_refuses_a_night_without_last_night_s_manifest_unless_told(self):
        self.assertEqual(open_data.check(self.out, floors=self.SMALL),
                         ["last night's manifest isn't here to compare with (the first night: KAV_DATA_FIRST_RUN)"])
        self.assertEqual(open_data.check(self.out, floors=self.SMALL, first_run=True), [])
        self.assertEqual(open_data.check(self.out, floors=self.SMALL, accept_drop=True), [])
        env = dict(os.environ, KAV_DATA_FIRST_RUN="", KAV_DATA_ACCEPT_DROP="")
        run = subprocess.run([sys.executable, os.path.join(open_data.HERE, "tools", "open_data.py"), "check", self.out,
                              os.path.join(self.dir, "not-there.json")], capture_output=True, text=True, env=env, timeout=120)
        self.assertEqual(run.returncode, 1)
        self.assertIn("last night's manifest isn't here", run.stdout)

    # v6, 5: every field of the manifest of its type and in its range

    def test_the_gate_refuses_a_manifest_with_any_field_of_the_wrong_type_or_range(self):
        good = json.load(open(os.path.join(self.out, "manifest.json"), encoding="utf-8"))
        self.assertEqual(open_data.manifest_problems(good), [])
        for i, change in enumerate((lambda d: d.update(contact=[]), lambda d: d.update(licences=False),
                                    lambda d: d.update(sources=False), lambda d: d.update(docs="ftp://x"),
                                    lambda d: d.update(version=2), lambda d: d.update(built="yesterday"),
                                    lambda d: d["files"][0].update(rows=-1), lambda d: d["files"][0].update(rows=True),
                                    lambda d: d["files"][0].update(size=1.5), lambda d: d["files"][0].update(credit=None),
                                    lambda d: d["files"][0].update(sources=False), lambda d: d["files"][0].update(changed=11),
                                    lambda d: d["files"][0].update(licence="Invented-1.0"),
                                    lambda d: d["files"][0].update(sources=["nowhere"]), lambda d: d["files"][1].update(licence_note=5),
                                    lambda d: d["files"][0].update(extra="x"), lambda d: d["licences"]["CC-BY-4.0"].update(url=None))):
            doc = json.loads(json.dumps(good))
            change(doc)
            with self.subTest(change=i):
                self.assertTrue(open_data.manifest_problems(doc))
                with open(os.path.join(self.out, "manifest.json"), "w", encoding="utf-8") as f:
                    json.dump(doc, f)
                self.assertTrue(open_data.check(self.out, floors=self.SMALL, first_run=True))

    def test_the_gate_refuses_to_judge_the_stops_without_last_night_s(self):
        last = {f["name"]: f for f in self.manifest["files"]}
        missing = os.path.join(self.dir, "none.csv.gz")
        self.assertEqual(open_data.check(self.out, last, floors=self.SMALL, previous_stops=missing),
                         ["last night's stops.csv.gz isn't here to compare the stops with"])
        with open(missing, "wb") as f:
            f.write(b"not last night's")
        self.assertEqual(open_data.check(self.out, last, floors=self.SMALL, previous_stops=missing),
                         ["last night's stops.csv.gz isn't here to compare the stops with"])

    def test_the_gate_refuses_a_manifest_of_more_than_one_json_value(self):
        # The checker's bytes: an invalid object (version true, no files, no folder), a newline, then the night's manifest.
        path = os.path.join(self.out, "manifest.json")
        with open(path, encoding="utf-8") as f:
            good = f.read()
        bad = dict(json.loads(good), version=True, files=[])
        del bad["folder"]
        with open(path, "w", encoding="utf-8") as f:
            f.write(json.dumps(bad) + "\n" + good)
        problems = open_data.check(self.out, floors=self.SMALL, first_run=True)
        self.assertEqual(len(problems), 1)
        self.assertIn("Extra data", problems[0])

    def test_the_gate_s_rules_are_in_the_published_repository(self):
        # The gate runs site/scripts/data-pull.sh --check-manifest: the workflow's checkout must have it.
        with open(os.path.join(open_data.HERE, ".gitignore"), encoding="utf-8") as f:
            ignored = f.read().split()
        self.assertNotIn("site/scripts/data-pull.sh", ignored)
        self.assertTrue(os.path.exists(os.path.join(open_data.HERE, "site", "scripts", "data-pull.sh")))

    def test_the_real_bounds_for_moved_stops_are_the_ones_measured_on_the_ministry_s_own_history(self):
        self.assertEqual((open_data.FLOORS["stop_moved_most"], open_data.MOVED_FAR, open_data.FLOORS["stops_moved"]),
                         (1_000, 50, 0.001))

    def test_the_gate_s_own_floors_refuse_a_set_this_small(self):
        problems = open_data.check(self.out, first_run=True)
        self.assertTrue(problems and all("under its floor" in p for p in problems), problems)

    def test_the_same_timetable_makes_the_same_files(self):
        again = open_data.build(self.mot, os.path.join(self.dir, "again"), self.naim, self.rail)
        self.assertEqual([f["sha256"] for f in again["files"]], [f["sha256"] for f in self.manifest["files"]])
        # Not only because both ran in the same second: no gzip carries a time.
        for f in self.manifest["files"]:
            if f["name"].endswith(".gz"):
                with open(os.path.join(self.out, f["name"]), "rb") as g:
                    self.assertEqual(g.read(8)[4:], b"\0\0\0\0")

    def test_without_the_weekend_lines_or_train_roads_their_parts_are_left_out(self):
        bare = open_data.build(self.mot, os.path.join(self.dir, "bare"), os.path.join(self.dir, "none.zip"), None)
        self.assertNotIn("rail-roads.geojson.gz", [f["name"] for f in bare["files"]])
        self.assertEqual(set(bare["sources"]), {"mot-gtfs", "kav", "kav-colours", "mot-busy", "mot-fleet"})
        self.assertEqual(set(bare["licences"]), {"CC-BY-4.0", "GPL-3.0", "data-gov-il"})
        self.assertEqual([r["route_id"] for r in read(os.path.join(self.dir, "bare"), "routes.csv.gz")], ["10", "11", "12"])

    def test_the_app_s_names_and_colours_are_read_from_its_own_code(self):
        # Fails loudly if ui/Operators.kt or ui/Common.kt changes shape, instead of the files losing them.
        operators, plates = open_data.app_colours()
        self.assertGreaterEqual(len(operators), 20)
        self.assertEqual(operators[3], ("Egged", "018369"))
        self.assertEqual(set(plates), {"Tram", "Rail", "Taxi", "Carmelit", "Rakavlit"})

if __name__ == "__main__":
    unittest.main()
