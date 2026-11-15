"""Tests for tools/data_publish.sh with a stand-in gh that keeps the release in a folder and can stop at any change.
cd tools && python3 -m unittest test_data_publish     (KAV_TEST_PUBLISH=<script taking the output folder> tests another)"""
import fnmatch
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.environ.get("KAV_TEST_PUBLISH") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "data_publish.sh")
NAMES = ["stops.csv.gz", "routes.csv.gz", "operators.csv.gz", "roads.geojson.gz", "fares.csv.gz", "fares.json.gz",
         "rail-roads.geojson.gz", "busy.csv.gz", "fleet.csv.gz"]

# gh as the publish script uses it, on a folder: each change (an asset written or deleted) is counted, and the one
# numbered KAV_GH_STOP_AT is not made: gh stops there, as a run cancelled or failing at that moment would.
GH = r'''#!/usr/bin/env python3
import fnmatch, json, os, shutil, sys
release = os.environ["KAV_GH_RELEASE"]
count = os.path.join(release + ".changes")
def change():
    n = int(open(count).read()) + 1 if os.path.exists(count) else 1
    open(count, "w").write(str(n))
    if n == int(os.environ.get("KAV_GH_STOP_AT", "0")):
        sys.exit(42)
args = sys.argv[1:]
if args[:2] == ["release", "view"] and "--json" in args:
    print("\n".join(sorted(os.listdir(release)))) if "--jq" in args else print(json.dumps({"assets": [{"name": n} for n in sorted(os.listdir(release))]}))
elif args[:2] == ["release", "view"]:
    sys.exit(0 if os.path.isdir(release) else 1)
elif args[:2] == ["release", "create"]:
    os.makedirs(release)
elif args[:2] == ["release", "upload"]:
    for path in [a for a in args[3:] if not a.startswith("--")]:
        target = os.path.join(release, os.path.basename(path))
        if os.path.exists(target):
            if "--clobber" not in args:
                sys.exit("exists")
            change(); os.remove(target)
        change(); shutil.copy(path, target)
elif args[:2] == ["release", "delete-asset"]:
    change(); os.remove(os.path.join(release, args[3]))
elif args[:2] == ["release", "download"]:
    pattern = args[args.index("--pattern") + 1]
    found = [n for n in sorted(os.listdir(release)) if fnmatch.fnmatch(n, pattern)] if os.path.isdir(release) else []
    if not found:
        sys.exit("no assets match the file pattern")
    if "--output" in args:
        sys.stdout.buffer.write(open(os.path.join(release, found[0]), "rb").read())
    else:
        for n in found:
            shutil.copy(os.path.join(release, n), os.path.join(args[args.index("--dir") + 1], n))
else:
    sys.exit("gh: " + " ".join(args))
'''


def night_files(folder, tag):
    files = {n: ("%s %s" % (tag, n)).encode() for n in NAMES}
    doc = {"format": "kav-open-data", "version": 1, "folder": folder,
           "files": [{"name": n, "size": len(b), "sha256": hashlib.sha256(b).hexdigest()} for n, b in files.items()]}
    files["manifest.json"] = json.dumps(doc).encode()
    files["manifest.json.sig"] = ("signature of " + folder).encode()
    return files


def read(path):
    with open(path, "rb") as f:
        return f.read()


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)
        self.bin = os.path.join(self.dir, "bin")
        os.makedirs(self.bin)
        with open(os.path.join(self.bin, "gh"), "w") as f:
            f.write(GH.replace("#!/usr/bin/env python3", "#!" + sys.executable))
        os.chmod(os.path.join(self.bin, "gh"), 0o755)
        self.release = os.path.join(self.dir, "release")

    def publish(self, files, stop_at=0):
        out = os.path.join(self.dir, "out")
        shutil.rmtree(out, ignore_errors=True)
        os.makedirs(out)
        for name, body in files.items():
            with open(os.path.join(out, name), "wb") as f:
                f.write(body)
        if os.path.exists(self.release + ".changes"):
            os.remove(self.release + ".changes")
        env = dict(os.environ, PATH=self.bin + os.pathsep + os.environ["PATH"], KAV_GH_RELEASE=self.release,
                   KAV_GH_STOP_AT=str(stop_at))
        run = subprocess.run(["sh", SCRIPT, out], capture_output=True, text=True, env=env, timeout=60)
        changes = int(read(self.release + ".changes")) if os.path.exists(self.release + ".changes") else 0
        return run, changes

    def reader(self):
        """What a reader of the release gets: manifest.json, then each file it names, from the night's own names
        where there are some (the first version used plain names). The night it is whole, None while manifest.json is
        away for the moment it is replaced; fails on a mixed or partial set."""
        path = os.path.join(self.release, "manifest.json")
        if not os.path.exists(path):
            return None
        raw = read(path)
        doc = json.loads(raw)
        folder = doc["folder"]
        own = os.path.exists(os.path.join(self.release, folder + ".manifest.json"))
        for f in doc["files"]:
            asset = os.path.join(self.release, (folder + "." if own else "") + f["name"])
            self.assertTrue(os.path.exists(asset), "manifest.json names %s, which isn't there" % asset)
            self.assertEqual(hashlib.sha256(read(asset)).hexdigest(), f["sha256"],
                             "%s isn't the file manifest.json names (%s)" % (f["name"], folder))
        sig = os.path.join(self.release, folder + ".manifest.json.sig" if own else "manifest.json.sig")
        self.assertEqual(read(sig), ("signature of " + folder).encode(), "the signature isn't this night's")
        if own:
            self.assertEqual(read(os.path.join(self.release, folder + ".manifest.json")), raw)
        return folder

    def test_a_night_published_whole_replaces_the_one_before_and_older_nights_go(self):
        for n in (11, 12, 13):
            run, _ = self.publish(night_files("202611%02dT000000Z" % n, n))
            self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.reader(), "20261113T000000Z")
        left = {a.split(".")[0] for a in os.listdir(self.release) if a != "manifest.json"}
        self.assertEqual(left, {"20261112T000000Z", "20261113T000000Z"})

    def test_stopped_at_any_change_a_reader_still_gets_one_whole_night(self):
        old, new = night_files("20261112T000000Z", "old"), night_files("20261113T000000Z", "new")
        run, _ = self.publish(old)
        self.assertEqual(run.returncode, 0, run.stderr)
        before = os.path.join(self.dir, "release-old")
        shutil.copytree(self.release, before)
        run, changes = self.publish(new)
        self.assertEqual(run.returncode, 0, run.stderr)
        seen = set()
        for stop in range(1, changes + 1):
            shutil.rmtree(self.release)
            shutil.copytree(before, self.release)
            run, _ = self.publish(new, stop_at=stop)
            self.assertNotEqual(run.returncode, 0, "the run went on past a stop at change %d" % stop)
            with self.subTest(stopped_at=stop):
                seen.add(self.reader())
        # Each stop left the old night or the new one, or manifest.json away for the moment of its replacing.
        self.assertLessEqual(seen, {"20261112T000000Z", "20261113T000000Z", None})
        self.assertIn("20261112T000000Z", seen)

    # v5, 3: a night's name stands for its bytes: never given to other ones, and the same night again changes nothing.

    def assets(self):
        return {n: read(os.path.join(self.release, n)) for n in os.listdir(self.release)}

    def test_a_night_s_name_is_never_given_to_other_bytes(self):
        run, _ = self.publish(night_files("20261113T000000Z", "first"))
        self.assertEqual(run.returncode, 0, run.stderr)
        before = self.assets()
        run, changes = self.publish(night_files("20261113T000000Z", "second"))
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual((changes, self.assets()), (0, before))
        self.assertEqual(self.reader(), "20261113T000000Z")

    def test_a_night_half_published_with_other_bytes_is_refused_too(self):
        first = night_files("20261113T000000Z", "first")
        os.makedirs(self.release)
        with open(os.path.join(self.release, "20261113T000000Z.roads.geojson.gz"), "wb") as f:
            f.write(b"other roads")
        before = self.assets()
        run, changes = self.publish(first)
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual((changes, self.assets()), (0, before))

    def test_the_same_night_published_again_uploads_none_of_its_files(self):
        files = night_files("20261113T000000Z", "same")
        run, _ = self.publish(files)
        self.assertEqual(run.returncode, 0, run.stderr)
        before = self.assets()
        run, _ = self.publish(files)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual((self.assets(), self.reader()), (before, "20261113T000000Z"))




    def test_cleanup_ignores_odd_and_unrelated_asset_names(self):
        self.assertEqual(self.publish(night_files("20261112T000000Z", "old"))[0].returncode, 0)
        odd = ["old file", "*.gz", "unrelated.txt", "20261101T000000Z.unrelated.txt", "line\nname", "20261101T000000Z.stops.csv.gz\nextra"]
        for name in odd:
            with open(os.path.join(self.release, name), "wb") as stream:
                stream.write(b"keep")
        run, _ = self.publish(night_files("20261113T000000Z", "new"))
        self.assertEqual(run.returncode, 0, run.stderr)
        for name in odd:
            self.assertEqual(read(os.path.join(self.release, name)), b"keep")

    def test_a_pointer_upload_that_does_nothing_is_reported_as_failure(self):
        old = night_files("20261112T000000Z", "old")
        self.assertEqual(self.publish(old)[0].returncode, 0)
        mock = os.path.join(self.bin, "gh")
        with open(mock) as stream:
            body = stream.read()
        body = body.replace('args = sys.argv[1:]', 'args = sys.argv[1:]\nif args[:2] == ["release", "upload"] and "--clobber" in args: sys.exit(0)')
        with open(mock, "w") as stream:
            stream.write(body)
        run, _ = self.publish(night_files("20261113T000000Z", "new"))
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual(self.reader(), "20261112T000000Z")


if __name__ == "__main__":
    unittest.main()
