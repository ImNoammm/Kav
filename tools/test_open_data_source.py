"""Server data routine tests, without app endpoints or a running server."""
import datetime, gzip, json, os, shutil, tempfile, time, types, unittest
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import open_data_source as live

class Busy(unittest.TestCase):
    """How busy each line usually is at each hour of the week, from the ministry's Rav-Kav taps (busy_days(), busy_trips(),
        busy_levels(), busy_month(), busy_rebuild()), used by the hourly export."""

    LINES = {"7": "15066-1", "8": "15066-1", "9": "100-2"}

    def setUp(self):
            self.folder = tempfile.mkdtemp(prefix="kav-busy-test-")
            self.addCleanup(shutil.rmtree, self.folder, True)
            self.out, self.timetables = os.path.join(self.folder, "busy"), os.path.join(self.folder, "timetable")
            os.makedirs(self.timetables)
            # A Wednesday: route 7 at 07:40 for an hour, route 8 at 08:00 for half an hour, route 9 at 10:00 and 23:53:20 for
            # an hour; a Friday: route 7 at 08:00.
            self.saved("2026-11-11", {"7": {"27600": [0, 0]}, "8": {"28800": [0, 1]}, "9": {"86000": [0, 0], "36000": [0, 0]},
                                      "99": {"28800": [0, 0]}})
            self.saved("2026-11-13", {"7": {"28800": [0, 0]}})
            self.asked = []

    def saved(self, day, trips):
            with gzip.open(os.path.join(self.timetables, day + ".json.gz"), "wt") as f:
                json.dump({"day": day, "patterns": ["1,2"], "offsets": ["0,3600", "0,1800"], "trips": trips}, f)

    def test_the_days_that_count_are_the_month_s_usual_ones_by_kind(self):
            # October 2026: weekdays near 3.8 million taps, 27 Oct (a cut service) 2.2 million; Fridays 1.5, Saturdays 0.4.
            totals = {d: 0 for d in range(1, 32)}
            for d in range(1, 32):
                weekday = datetime.date(2026, 10, d).weekday()
                totals[d] = 1_500_000 if weekday == 4 else 400_000 if weekday == 5 else 3_800_000
            totals[27] = 2_200_000
            days = live.busy_days(totals, 2026, 10)
            self.assertEqual(days["friday"], [2, 9, 16, 23, 30])
            self.assertEqual(days["saturday"], [3, 10, 17, 24, 31])
            # Sunday to Thursday, 21 days, but the 27th.
            self.assertNotIn(27, days["weekday"])
            self.assertEqual(len(days["weekday"]), 20)

    def test_trips_on_the_road_count_in_each_hour_for_the_share_of_their_run_in_it(self):
            trips, days = live.busy_trips(self.timetables, self.LINES)
            self.assertEqual(days, {"weekday": 1, "friday": 1, "saturday": 0})
            line = trips["15066-1"]
            # 07:40 for an hour: a third in 7, two in 8; with the other way's half hour at 08:00, 5/3 in 8.
            self.assertAlmostEqual(line["weekday"][7], 1 / 3)
            self.assertAlmostEqual(line["weekday"][8], 5 / 3)
            self.assertAlmostEqual(line["friday"][8], 1)
            # After midnight on the next day, a Thursday: still a weekday, at 0.
            self.assertAlmostEqual(trips["100-2"]["weekday"][23], 400 / 3600)
            self.assertAlmostEqual(trips["100-2"]["weekday"][0], 3200 / 3600)
            self.assertAlmostEqual(trips["100-2"]["weekday"][10], 1)
            self.assertEqual(sorted(trips), ["100-2", "15066-1"])

    def levels(self):
            none = lambda: [0.0] * 24
            taps = {"A": {"weekday": none(), "friday": none()}, "B": {"weekday": none()}, "C": {"weekday": none()}}
            trips = {"A": {"weekday": none(), "friday": none()}, "B": {"weekday": none()}, "C": {"weekday": none()}}
            for h, (t, n) in {6: (80, 4), 7: (200, 4), 8: (192, 4), 9: (120, 4), 10: (10, 4), 11: (40, 0.5)}.items():
                taps["A"]["weekday"][h], trips["A"]["weekday"][h] = t, n
            taps["A"]["friday"][8], trips["A"]["friday"][8] = 72, 2
            for h in (6, 7, 8, 9):
                taps["B"]["weekday"][h], trips["B"]["weekday"][h] = 20, 2
            for h in (6, 7, 8):
                taps["C"]["weekday"][h], trips["C"]["weekday"][h] = 100, 2
            return live.busy_levels(taps, trips)

    def test_an_hour_is_told_against_the_line_s_own_busiest_hours(self):
            got = self.levels()
            hours = lambda text: {h: c for h, c in enumerate(text) if c != "0"}
            # Weekday taps per trip 20, 50, 48 and 30: the day's busiest three average 42.7; under half of it is one of its
            # quieter hours; of four hours told only the highest may be busiest (a third at most), so 48 is about average.
            self.assertEqual(hours(got["A"]["weekday"]), {6: "1", 7: "3", 8: "2", 9: "2"})
            # 10 taps an hour, half a trip on the road: too little to say.
            self.assertEqual(len(got["A"]["weekday"]), 24)
            # Friday 08:00 alone: one hour isn't enough to compare, so Friday isn't told.
            self.assertNotIn("friday", got["A"])
            self.assertNotIn("saturday", got["A"])
            # A day whose busiest hours have under BUSY_QUIET taps a trip is quiet whenever it can be told.
            self.assertEqual(hours(got["B"]["weekday"]), {6: "q", 7: "q", 8: "q", 9: "q"})
            # Three hours aren't enough to compare.
            self.assertNotIn("C", got)

    def portal(self, month=(2026, 10), rows=None, modified="2026-11-03T10:44:53", answer=None):
            """A fake data.gov.il: its answers to the job's requests, each request kept (self.asked)."""
            import urllib.parse
            fields = list(live.BUSY_FIELDS)
            rows = rows if rows is not None else self.taps_rows()

            def fetch(until, url=None):
                self.assertTrue(url.startswith("https://data.gov.il/api/3/action/"), url)
                self.assertGreater(until, time.monotonic())
                u = urllib.parse.urlsplit(url)
                action, q = u.path.rsplit("/", 1)[1], dict(urllib.parse.parse_qsl(u.query))
                self.asked.append((action, q))
                if answer:
                    got = answer(action, q)
                    if got is not None:
                        return got
                if action == "package_show":
                    result = {"resources": [
                        {"id": "old", "name": "מאגר תיקופי מסלקה בתחבורה ציבורית 2025", "datastore_active": True, "last_modified": "2026-03-10"},
                        {"id": "pdf", "name": "גילוי נאות", "datastore_active": False},
                        {"id": "new", "name": "מאגר תיקופי מסלקה בתחבורה ציבורית 2026", "datastore_active": True, "last_modified": modified}]}
                elif q.get("fields") == "year_key,month_key":
                    result = {"fields": [{"id": "year_key"}, {"id": "month_key"}], "records": [list(month)], "total": 1}
                else:
                    self.assertEqual(json.loads(q["filters"]), {"year_key": month[0], "month_key": month[1]})
                    start, limit = int(q["offset"]), int(q["limit"])
                    result = {"fields": [{"id": f} for f in fields], "records": rows[start:start + limit], "total": len(rows)}
                return json.dumps({"success": True, "result": result}).encode()
            return fetch

    def taps_rows(self):
            """October: line 15066 direction 1 at 07 and 08 with 5 and 100 taps each day but Saturdays (none) and 27 Oct (a
            cut service, none at all), line 100 direction 2 with 30 an hour from 05 to 22, and the trains to make up the day."""
            rows = []
            days = range(1, 32)

            def row(line, direction, hour, each):
                return [line, direction, hour] + [None if datetime.date(2026, 10, d).weekday() == 5 else 0 if d == 27 else each(d)
                                                  for d in days]
            rows.append(row(15066, 1, 7, lambda d: 5))
            rows.append(row(15066, 1, 8, lambda d: 100))
            rows.append(row(-1, -1, 8, lambda d: 9))
            for h in range(5, 23):
                rows.append(row(100, 2, h, lambda d: 30))
            return rows

    def test_the_month_is_read_from_the_portal_page_by_page_one_request_at_a_time(self):
            live.BUSY_PAGE, was = 7, live.BUSY_PAGE
            self.addCleanup(setattr, live, "BUSY_PAGE", was)
            waits = []
            got = live.busy_month(self.portal(), None, wait=waits.append)
            source, month, days, taps = got
            self.assertEqual(source, {"resource": "new", "modified": "2026-11-03T10:44:53"})
            self.assertEqual(month, "2026-10")
            # The package, the newest month, then 21 rows 7 at a time; a pause before each request after the first.
            self.assertEqual([a for a, q in self.asked], ["package_show"] + ["datastore_search"] * 4)
            self.assertEqual([q.get("offset") for a, q in self.asked[2:]], ["0", "7", "14"])
            self.assertEqual(waits, [live.BUSY_PAUSE] * 4)
            self.assertNotIn(27, days["weekday"])
            self.assertEqual(taps["15066-1"]["weekday"][8], 100)
            # Fridays by their clock: the 2nd to the 23rd on summer time; the 30th, winter time, alone and under 3.
            self.assertEqual(taps["15066-1"]["friday+0300"][7], 5)
            self.assertNotIn("friday+0200", taps["15066-1"])
            self.assertNotIn("-1--1", taps)
            # Unchanged since what was kept: the package alone is asked.
            self.asked.clear()
            self.assertIsNone(live.busy_month(self.portal(), source, wait=waits.append))
            self.assertEqual([a for a, q in self.asked], ["package_show"])

    def test_an_answer_that_isn_t_what_it_should_be_fails_the_whole_month(self):
            def bad(change):
                self.asked.clear()
                with self.assertRaises((OSError, ValueError, KeyError, TypeError)):
                    live.busy_month(self.portal(answer=change), None, wait=lambda s: None)
            bad(lambda a, q: b'{"success": false, "error": {}}' if a == "package_show" else None)
            bad(lambda a, q: b"<html>maintenance</html>")
            # Other fields than asked, a value that isn't a number, pages that don't add up, more rows than a month has.
            page = lambda result: json.dumps({"success": True, "result": result}).encode()
            fields = [{"id": f} for f in live.BUSY_FIELDS]
            months = lambda q: q.get("fields") == "year_key,month_key"
            bad(lambda a, q: None if a == "package_show" or months(q) else page({"fields": fields[::-1], "records": [], "total": 0}))
            bad(lambda a, q: None if a == "package_show" or months(q) else
                page({"fields": fields, "records": [[15066, 1, 7] + ["x"] * 31], "total": 1}))
            bad(lambda a, q: None if a == "package_show" or months(q) else
                page({"fields": fields, "records": [], "total": 5}))
            bad(lambda a, q: None if a == "package_show" or months(q) else
                page({"fields": fields, "records": [], "total": live.BUSY_ROWS + 1}))
            self.assertEqual(len([a for a, q in self.asked if a == "datastore_search"]), 2)

    def rebuild(self, fetch, today=datetime.date(2026, 11, 14)):
            return live.busy_rebuild(self.LINES, today, self.out, self.timetables, fetch=fetch, wait=lambda s: None)

    def loose(self):
            """Thresholds that let this small example be told."""
            for name, value in (("BUSY_CELLS", 1), ("BUSY_TAPS", 1), ("BUSY_TRIPS", 0.3), ("BUSY_QUIET", 0)):
                self.addCleanup(setattr, live, name, getattr(live, name))
                setattr(live, name, value)

    def test_a_failing_portal_keeps_the_month_it_had_and_asks_again_later(self):
            self.loose()
            self.rebuild(self.portal())
            with open(os.path.join(self.out, "taps.json"), "rb") as f:
                kept = f.read()

            def down(until, url=None):
                raise OSError("too slow")
            portal, table = self.rebuild(down, datetime.date(2026, 11, 15))
            self.assertIn("too slow", portal)
            self.assertEqual(table["made"], "2026-11-15")
            with open(os.path.join(self.out, "taps.json"), "rb") as f:
                self.assertEqual(f.read(), kept)
            self.assertFalse(live.busy_checked(self.out) == "2026-11-15")
            # Never a month at all and a failing portal: no levels.
            shutil.rmtree(self.out)
            self.assertEqual(self.rebuild(down)[1], None)
            self.assertFalse(os.path.exists(os.path.join(self.out, "busy.json")))

    def test_levels_for_far_fewer_routes_than_before_don_t_replace_them(self):
            self.loose()
            self.rebuild(self.portal())
            with open(os.path.join(self.out, "busy.json"), "rb") as f:
                before = f.read()
            # The next month has line 100 alone: two routes' levels of three would go.
            only = [r for r in self.taps_rows() if r[0] != 15066]
            portal, table = self.rebuild(self.portal(month=(2026, 11), rows=only, modified="2026-12-03"), datetime.date(2026, 12, 4))
            self.assertIn("fewer routes", portal)
            with open(os.path.join(self.out, "busy.json"), "rb") as f:
                self.assertEqual(f.read(), before)

    def clock(self, now):
            from unittest import mock
            at = datetime.datetime.fromisoformat(now)

            class Fixed(datetime.datetime):
                @classmethod
                def now(cls, tz=None):
                    return at.astimezone(tz) if tz else at
            return mock.patch.object(live, "datetime", types.SimpleNamespace(
                datetime=Fixed, date=datetime.date, time=datetime.time, timedelta=datetime.timedelta, timezone=datetime.timezone))

    def test_each_kind_of_day_is_told_against_its_own_busiest_hours(self):
            # 14 Nov audit: the 480's Friday-night peak made every weekday hour "one of its quieter". Weekdays at 20 to 26 taps
            # a trip, Friday after midnight at 80 to 120: each day against its own.
            none = lambda: [0.0] * 24
            taps, trips = {"A": {"weekday": none(), "friday": none()}}, {"A": {"weekday": none(), "friday": none()}}
            for h, t in zip(range(6, 18), (40, 44, 48, 52, 40, 40, 40, 40, 44, 48, 52, 52)):
                taps["A"]["weekday"][h], trips["A"]["weekday"][h] = t, 2
            for h, t in zip(range(0, 6), (240, 200, 160, 180, 200, 220)):
                taps["A"]["friday"][h], trips["A"]["friday"][h] = t, 2
            got = live.busy_levels(taps, trips)["A"]
            told = [c for c in got["weekday"] if c != "0"]
            self.assertEqual(len(told), 12)
            self.assertGreater(told.count("3"), 0)
            self.assertLess(told.count("1"), 6)

    def test_busiest_is_said_of_a_day_s_few_highest_hours_only(self):
            # 14 Nov audit: on a flat line nearly every hour is within 8 in 10 of the busiest (Jerusalem 1: 13 of 21). Busiest is
            # among the day's 4 highest, and never more than a third of the hours it tells.
            none = lambda: [0.0] * 24
            taps, trips = {"A": {"weekday": none(), "saturday": none()}}, {"A": {"weekday": none(), "saturday": none()}}
            for h in range(6, 21):
                taps["A"]["weekday"][h], trips["A"]["weekday"][h] = 100 + h, 2
            for h in range(18, 24):
                taps["A"]["saturday"][h], trips["A"]["saturday"][h] = 100 + h, 2
            got = live.busy_levels(taps, trips)["A"]
            # 15 weekday hours, all within 8 in 10 of the top: the 4 highest (17 to 20) are busiest, the rest about average.
            self.assertEqual({h: c for h, c in enumerate(got["weekday"]) if c == "3"}, {17: "3", 18: "3", 19: "3", 20: "3"})
            self.assertEqual(got["weekday"].count("2"), 11)
            # 6 Saturday hours: 2 busiest at most, the highest two.
            self.assertEqual({h for h, c in enumerate(got["saturday"]) if c == "3"}, {22, 23})

    def test_a_count_out_of_bounds_fails_the_whole_month(self):
            # 14 Nov audit: 1e300 passed and gave ordinary-looking levels; Infinity crashed the process.
            page = lambda result: json.dumps({"success": True, "result": result}).encode()
            fields = [{"id": f} for f in live.BUSY_FIELDS]
            months = lambda q: q.get("fields") == "year_key,month_key"
            for value in ("1e300", "-5", "Infinity", "NaN", "1000001"):
                row = '[15066, 1, 7, %s%s]' % (value, ", 0" * 30)
                body = ('{"success": true, "result": {"fields": %s, "records": [%s], "total": 1}}' % (json.dumps(fields), row)).encode()
                self.asked.clear()
                with self.assertRaises(ValueError, msg=value):
                    live.busy_month(self.portal(answer=lambda a, q, body=body: None if a == "package_show" or months(q) else body), None,
                                    wait=lambda s: None)
            # A million is still a count.
            good = page({"fields": fields, "records": [[15066, 1, 7, 1_000_000] + [0] * 30], "total": 1})
            self.assertIsNotNone(live.busy_month(self.portal(answer=lambda a, q: None if a == "package_show" or months(q) else good),
                                                 None, wait=lambda s: None))

    def test_each_write_of_a_table_goes_through_a_file_of_its_own(self):
            # 14 Nov audit: two jobs at once wrote through the same taps.json.part and left it broken once in three.
            from unittest import mock
            os.makedirs(self.out)
            path, used = os.path.join(self.out, "taps.json"), []
            real = os.replace

            def replace(src, dst):
                used.append(src)
                real(src, dst)
            with mock.patch.object(live.os, "replace", replace):
                live.busy_write(path, {"n": 1})
                live.busy_write(path, {"n": 2})
            self.assertEqual(len(set(used)), 2)
            self.assertTrue(all(os.path.dirname(u) == self.out and u.endswith(".part") for u in used))
            self.assertEqual(sorted(os.listdir(self.out)), ["taps.json"])
            with open(path) as f:
                self.assertEqual(json.load(f), {"n": 2})

    def month_rows(self, year, month, each, cut=()):
            """A month of the count of taps for line 15066 direction 1 at 06 to 09, each(day, hour) taps a day; the trains' -1 rows
            to make up the country's day; the days in cut have no taps at all (a strike)."""
            import calendar
            length = calendar.monthrange(year, month)[1]
            days = [datetime.date(year, month, d) for d in range(1, length + 1)]
            rows = [[15066, 1, h] + [0 if day.day in cut else each(day, h) for day in days] + [None] * (31 - length) for h in (6, 7, 8, 9)]
            rows.append([-1, -1, 8] + [0 if day.day in cut else 50 for day in days] + [None] * (31 - length))
            return rows

    def by_the_hour(self, days):
            """Route 7 setting out at 06, 07, 08 and 09 for an hour on each of these saved days."""
            for name in os.listdir(self.timetables):
                os.remove(os.path.join(self.timetables, name))
            for day in days:
                self.saved(day, {"7": {str(h * 3600): [0, 0] for h in (6, 7, 8, 9)}})

    def test_across_the_autumn_change_fridays_and_saturdays_count_on_the_timetables_clock_only(self):
            # 14 Nov audit, round 2: four of October 2026's Fridays were summer time and the saved timetables winter time, when
            # Shabbat comes an hour earlier; summer taps over winter trips made 21 Fridays mostly "quieter". 35, 80, 100 and 60
            # taps an hour on summer days, ten times that on the 30th and 31st (winter time).
            self.loose()
            each = lambda day, h: {6: 35, 7: 80, 8: 100, 9: 60}[h] * (10 if day.day >= 30 else 1)
            rows = self.month_rows(2026, 10, each)
            # Production's case: the saved timetables are winter time (a Wednesday, a Friday and a Saturday of November). One
            # winter Friday and one winter Saturday in October: under 3, so neither is told; weekdays are.
            self.by_the_hour(["2026-11-11", "2026-11-13", "2026-11-14"])
            portal, table = self.rebuild(self.portal(rows=rows))
            self.assertEqual(sorted(table["routes"]["7"]), ["weekday"])
            self.assertEqual(table["clocks"], {"friday": "+0200", "saturday": "+0200"})
            self.asked.clear()
            source, month, days, taps = live.busy_month(self.portal(rows=rows), None, wait=lambda s: None)
            self.assertEqual(days["friday+0300"], [2, 9, 16, 23])
            self.assertEqual(days["friday+0200"], [30])
            self.assertEqual(days["saturday+0300"], [3, 10, 17, 24])
            self.assertEqual(taps["15066-1"]["friday+0300"][6:10], [35, 80, 100, 60])
            self.assertNotIn("friday+0200", taps["15066-1"])
            self.assertNotIn("saturday+0200", taps["15066-1"])
            # A summer Friday saved instead: Friday is told, from the summer Fridays alone.
            shutil.rmtree(self.out)
            self.by_the_hour(["2026-11-11", "2026-10-23"])
            portal, table = self.rebuild(self.portal(rows=rows))
            self.assertEqual(table["clocks"], {"friday": "+0300"})
            self.assertEqual(table["routes"]["7"]["friday"][6:10], "1232")
            self.assertEqual(table["days"]["friday"], 4)

    def test_across_the_spring_change_fridays_count_on_the_timetables_clock_only(self):
            # Summer time starts on Friday 26 March 2027 at 02:00: 5, 12 and 19 March are winter Fridays, the 26th summer.
            self.loose()
            each = lambda day, h: {6: 35, 7: 80, 8: 100, 9: 60}[h] * (10 if day.day == 26 else 1)
            rows = self.month_rows(2027, 3, each)
            portal = lambda: self.portal(month=(2027, 3), rows=rows)
            # Summer timetables saved (April): one summer Friday in March, under 3: Friday shows nothing.
            self.by_the_hour(["2027-04-07", "2027-04-02"])
            problem, table = self.rebuild(portal(), datetime.date(2027, 4, 10))
            self.assertNotIn("friday", table["routes"]["7"])
            self.assertEqual(table["clocks"], {"friday": "+0300"})
            self.assertEqual(live.busy_clock(datetime.date(2027, 3, 19)), "+0200")
            self.assertEqual(live.busy_clock(datetime.date(2027, 3, 26)), "+0300")
            # Winter timetables saved: the three winter Fridays, the 26th left out of the means.
            shutil.rmtree(self.out)
            self.by_the_hour(["2027-03-10", "2027-03-12"])
            problem, table = self.rebuild(portal(), datetime.date(2027, 4, 10))
            with open(os.path.join(self.out, "taps.json")) as f:
                self.assertEqual(json.load(f)["taps"]["15066-1"]["friday+0200"][6:10], [35, 80, 100, 60])
            self.assertEqual((table["days"]["friday"], table["routes"]["7"]["friday"][6:10]), (3, "1232"))

    def test_under_three_fridays_on_the_timetables_clock_leave_friday_untold(self):
            # October 2026 with summer timetables saved, and the 9th and 16th cut: two summer Fridays are left (the 30th is
            # winter time), under 3, so Friday shows nothing that month; weekdays are told.
            self.loose()
            rows = self.month_rows(2026, 10, lambda day, h: {6: 35, 7: 80, 8: 100, 9: 60}[h], cut=(9, 16))
            self.by_the_hour(["2026-11-11", "2026-10-23"])
            problem, table = self.rebuild(self.portal(rows=rows))
            self.assertEqual(sorted(table["routes"]["7"]), ["weekday"])
            with open(os.path.join(self.out, "taps.json")) as f:
                self.assertEqual(json.load(f)["days"]["friday+0300"], [2, 23])

    def test_a_month_on_one_clock_comes_out_as_before(self):
            # December 2026 is winter time throughout, as are the saved timetables: every Friday counts, and the levels are
            # what the same means give told directly (busy_levels(), as before the clock rule).
            self.loose()
            each = lambda day, h: {6: 35, 7: 80, 8: 100, 9: 60}[h] * (2 if day.weekday() == 4 else 1)
            rows = self.month_rows(2026, 12, each)
            self.by_the_hour(["2026-12-09", "2026-12-11"])
            problem, table = self.rebuild(self.portal(month=(2026, 12), rows=rows), datetime.date(2027, 1, 5))
            trips, _ = live.busy_trips(self.timetables, self.LINES)
            weekday = [{6: 35, 7: 80, 8: 100, 9: 60}.get(h, 0) for h in range(24)]
            friday = [2 * x for x in weekday]
            self.assertEqual(table["routes"]["7"], live.busy_levels({"15066-1": {"weekday": weekday, "friday": friday}}, trips)["15066-1"])
            self.assertEqual((table["days"]["weekday"], table["days"]["friday"]), (23, 4))

    def test_each_route_s_line_comes_from_the_ministry_s_route_desc(self):
            text = ("route_id,agency_id,route_short_name,route_long_name,route_desc,route_type,route_color\n"
                    "7,5,66,A-a<->B-b-1#,15066-1-#,3,\n8,5,66,A-a<->C-c-1ג,15066-1-ג,3,\n12,2,,X<->Y,,2,\n")
            self.assertEqual(live.ministry_lines(text), {"7": "15066-1", "8": "15066-1"})
            self.assertEqual(live.ministry_lines("route_id,agency_id\n1,5\n"), {})



class Fleet(unittest.TestCase):
    """What kind of bus each live bus is, from the ministry's file of the operators' buses (fleet_file(),
        fleet_file()), used by the fleet export."""

    ROWS = [[14906702, "מפרקי 4 דלתות", 49, 2020, "דיזל"], [8800687, "מידיבוס", 39, 2017, "חשמל"],
                [3177039, "אוטובוס", 59, 2016, "גז טבעי דחוס"], [5550001, "לא ידוע", 0, None, ""], [5550002, "", None, None, None]]

    def setUp(self):
            self.folder = tempfile.mkdtemp(prefix="kav-fleet-test-")
            self.addCleanup(shutil.rmtree, self.folder, True)
            self.out = os.path.join(self.folder, "fleet")
            self.asked = []

    def portal(self, rows=None, modified="2026-11-14T06:01:00", answer=None):
            """A fake data.gov.il: its answers to the job's requests, each request kept (self.asked)."""
            import urllib.parse
            rows = rows if rows is not None else self.ROWS

            def fetch(until, url=None):
                self.assertTrue(url.startswith("https://data.gov.il/api/3/action/"), url)
                u = urllib.parse.urlsplit(url)
                action, q = u.path.rsplit("/", 1)[1], dict(urllib.parse.parse_qsl(u.query))
                self.asked.append((action, q))
                if answer:
                    got = answer(action, q)
                    if got is not None:
                        return got
                if action == "package_show":
                    result = {"resources": [{"id": "fleet", "name": "ציי רכב אוטובוסים", "datastore_active": True, "last_modified": modified}]}
                else:
                    start, limit = int(q["offset"]), int(q["limit"])
                    result = {"fields": [{"id": f} for f in live.FLEET_FIELDS], "records": rows[start:start + limit], "total": len(rows)}
                return json.dumps({"success": True, "result": result}).encode()
            return fetch

    def test_the_file_is_read_page_by_page_keeping_what_riders_need_by_plate(self):
            live.FLEET_PAGE, was = 2, live.FLEET_PAGE
            self.addCleanup(setattr, live, "FLEET_PAGE", was)
            waits = []
            source, buses = live.fleet_file(self.portal(), None, wait=waits.append)
            self.assertEqual(source, {"resource": "fleet", "modified": "2026-11-14T06:01:00"})
            self.assertEqual([q.get("offset") for a, q in self.asked], [None, "0", "2", "4"])
            self.assertEqual(waits, [live.FLEET_PAUSE] * 3)
            self.assertEqual(buses["14906702"], ["articulated", 49, 2020, "diesel"])
            self.assertEqual(buses["8800687"], ["midibus", 39, 2017, "electric"])
            self.assertEqual(buses["3177039"], ["bus", 59, 2016, "gas"])
            # A bus the file knows nothing of isn't kept.
            self.assertNotIn("5550001", buses)
            self.assertNotIn("5550002", buses)
            # Unchanged since what was kept: the package alone is asked.
            self.asked.clear()
            self.assertIsNone(live.fleet_file(self.portal(), source, wait=waits.append))
            self.assertEqual([a for a, q in self.asked], ["package_show"])

    def test_an_answer_that_isn_t_what_it_should_be_fails_the_whole_file(self):
            page = lambda result: json.dumps({"success": True, "result": result}).encode()
            fields = [{"id": f} for f in live.FLEET_FIELDS]

            def bad(change):
                self.asked.clear()
                with self.assertRaises((OSError, ValueError, KeyError, TypeError)):
                    live.fleet_file(self.portal(answer=change), None, wait=lambda s: None)
            bad(lambda a, q: b'{"success": false}' if a == "package_show" else None)
            bad(lambda a, q: None if a == "package_show" else page({"fields": fields[::-1], "records": [], "total": 0}))
            bad(lambda a, q: None if a == "package_show" else page({"fields": fields, "records": [["x", "אוטובוס", 1, 2020, "דיזל"]], "total": 1}))
            bad(lambda a, q: None if a == "package_show" else page({"fields": fields, "records": [], "total": 3}))
            bad(lambda a, q: None if a == "package_show" else page({"fields": fields, "records": [], "total": live.FLEET_ROWS + 1}))
            self.assertEqual([a for a, q in self.asked], ["package_show", "datastore_search"])

    def clock(self, now):
            from unittest import mock
            at = datetime.datetime.fromisoformat(now)

            class Fixed(datetime.datetime):
                @classmethod
                def now(cls, tz=None):
                    return at.astimezone(tz) if tz else at
            return mock.patch.object(live, "datetime", types.SimpleNamespace(
                datetime=Fixed, date=datetime.date, time=datetime.time, timedelta=datetime.timedelta, timezone=datetime.timezone))
