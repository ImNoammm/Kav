"""The export expands the server's exact profiles and keeps its fleet values."""
import datetime
import gzip
import json
import os
import tempfile
import unittest
from unittest.mock import patch

import open_data_extra as extra
from test_open_data import gtfs, MOT


class Extra(unittest.TestCase):
    def test_server_profiles_expand_to_week_hours_without_inventing_weekends(self):
        with tempfile.TemporaryDirectory() as root:
            day = datetime.datetime.now(extra.live.ISRAEL).date().isoformat()
            gtfs(os.path.join(root, "mot.zip"), MOT)
            with gzip.open(os.path.join(root, day + ".json.gz"), "wt") as f:
                json.dump({}, f)
            source = {"modified": "2026-11-03T10:44:53", "resource": "fixture"}
            def rebuild(lines, today, out, timetables, fetch):
                with open(os.path.join(out, "taps.json"), "w") as f:
                    json.dump({"source": source}, f)
                return None, {"routes": {"10": {"weekday": "0123q" + "0" * 19}}, "from": day, "to": day,
                              "month": day[:7], "days": {"weekday": 20}}
            with patch.object(extra.live, "busy_rebuild", side_effect=rebuild), patch.object(extra.live, "fleet_file", return_value=(source, {"1234567": ["midibus", 0, 2024, "gas"]})):
                doc = extra.collect(os.path.join(root, "mot.zip"), root)
            self.assertEqual(len(doc["busy"]), 5 * 24)
            self.assertEqual({r[2] for r in doc["busy"]}, set(range(5)))
            self.assertEqual([r[4] for r in doc["busy"][:5]], list("0123q"))
            self.assertEqual(doc["fleet"], [["1234567", "midibus", 0, 2024, "gas"]])
            self.assertEqual(doc["sources"]["mot-busy"]["published"], source["modified"])

    def test_missing_saved_timetables_fail_before_any_fetch(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ValueError):
                extra.collect("absent.zip", root)
