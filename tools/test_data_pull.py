"""Tests for site/scripts/data-pull.sh, run as it is against a release served here, with a key made for the test.
cd tools && python3 -m unittest test_data_pull     (KAV_TEST_PULL=<script> runs them against another copy of it)"""
import base64
import fcntl
import gzip
import hashlib
import http.server
import json
import os
from os import link
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest

SCRIPT = os.environ.get("KAV_TEST_PULL") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "site",
                                                          "scripts", "data-pull.sh")
# Each file's most bytes, as site/scripts/data-pull.sh's FILES: 526 MB together, over its 500 MB for all.
MOST = {"stops.csv.gz": 50_000_000, "routes.csv.gz": 20_000_000, "operators.csv.gz": 1_000_000, "roads.geojson.gz": 300_000_000,
        "fares.csv.gz": 100_000_000, "fares.json.gz": 5_000_000, "rail-roads.geojson.gz": 50_000_000, "busy.csv.gz": 20_000_000, "fleet.csv.gz": 5_000_000}
NAMES = ["stops.csv.gz", "routes.csv.gz", "operators.csv.gz", "roads.geojson.gz", "fares.csv.gz", "fares.json.gz",
         "rail-roads.geojson.gz", "busy.csv.gz", "fleet.csv.gz"]


def night(n):
    return "202611%02dT000000Z" % n


def payload(tag=""):
    return {n: gzip.compress(("%s %s" % (n, tag)).encode(), mtime=0) for n in NAMES}


def manifest(files, folder):
    """A manifest in the generator's shape for these {name: bytes}, built at the night's time."""
    built = "%s-%s-%sT%s:%s:%s+00:00" % (folder[:4], folder[4:6], folder[6:8], folder[9:11], folder[11:13], folder[13:15])
    return {"format": "kav-open-data", "version": 1, "built": built, "folder": folder, "docs": "https://kavapp.link/docs/data",
            "contact": "noam@kavapp.link", "sources": {"mot-gtfs": {"name": "Israel Ministry of Transport", "url": "https://example.org/gtfs"}},
            "licences": {"CC-BY-4.0": {"name": "Creative Commons Attribution 4.0", "url": "https://creativecommons.org/licenses/by/4.0/"}},
            "files": [{"name": n, "size": len(b), "sha256": hashlib.sha256(b).hexdigest(), "rows": 1, "licence": "CC-BY-4.0",
                       "credit": "test", "sources": ["mot-gtfs"], "changed": "test"} for n, b in files.items()]}


class Release:
    """What the release serves, by name, and what was asked of it."""

    def __init__(self):
        self.assets, self.asked, self.before = {}, [], {}
        release = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                name = self.path.lstrip("/")
                release.asked.append(name)
                if name in release.before:
                    release.before.pop(name)()
                body = release.assets.get(name)
                if isinstance(body, list):  # one answer after another, the last for every later ask
                    body = body.pop(0) if len(body) > 1 else body[0]
                if body is None:
                    self.send_response(404)
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *args):
                pass
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = "http://127.0.0.1:%d" % self.server.server_port

    def publish(self, doc, files, key, signature=None, dated=None):
        """A night as tools/data_publish.sh leaves it, and under the plain names the first version of the script read;
        dated: other bytes for the night's own manifest, signed as they are (the moving manifest.json stays doc)."""
        raw = json.dumps(doc, indent=1).encode()
        sign = lambda data: base64.b64encode(
            subprocess.run(["openssl", "dgst", "-sha256", "-sign", key], input=data, capture_output=True, check=True).stdout)
        sig = signature if signature is not None else sign(raw)
        folder = doc["folder"]
        self.assets = {"manifest.json": raw, "manifest.json.sig": sig, folder + ".manifest.json": dated or raw,
                       folder + ".manifest.json.sig": sign(dated) if dated else sig}
        for name, body in files.items():
            self.assets[folder + "." + name] = body
            # The first version read the manifest's names plainly: a file named like the signature came after it.
            self.assets[name] = [self.assets[name], body] if name in self.assets else body


class PullTest(unittest.TestCase):
    def setUp(self):
        self.addCleanup(os.umask, os.umask(0o022))  # the folders as a user's are made: others may not write in them
        self.dir = tempfile.mkdtemp()
        self.key = os.path.join(self.dir, "key.pem")
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", self.key], check=True)
        der = subprocess.run(["openssl", "pkey", "-in", self.key, "-pubout", "-outform", "DER"], capture_output=True,
                             check=True).stdout
        self.public = base64.b64encode(der).decode()
        self.release = Release()
        self.addCleanup(self.release.server.server_close)
        self.addCleanup(self.release.server.shutdown)
        self.data = os.path.join(self.dir, "data")
        self.v = os.path.join(self.data, "v1")
        self.outside = os.path.join(self.dir, "outside")
        with open(self.outside, "w") as f:
            f.write("OUTSIDE")

    def pull(self, doc, files, signature=None, dated=None):
        self.release.publish(doc, files, self.key, signature, dated)
        before = len(self.release.asked)
        run = subprocess.run(["sh", SCRIPT, self.data], capture_output=True, text=True, timeout=60,
                             env=dict(os.environ, KAV_DATA_RELEASE=self.release.url, KAV_DATA_PUBLIC_KEY=self.public))
        run.asked = self.release.asked[before:]
        return run

    def again(self):
        before = len(self.release.asked)
        run = subprocess.run(["sh", SCRIPT, self.data], capture_output=True, text=True, timeout=60,
                             env=dict(os.environ, KAV_DATA_RELEASE=self.release.url, KAV_DATA_PUBLIC_KEY=self.public))
        run.asked = self.release.asked[before:]
        return run

    def install(self, n, tag=""):
        files = payload(tag or str(n))
        run = self.pull(manifest(files, night(n)), files)
        self.assertEqual(run.returncode, 0, run.stderr)
        return files

    def served(self, name="manifest.json"):
        """The bytes Caddy would serve at /data/v1/<name>, and which night latest is."""
        path = os.path.join(self.v, "latest", name)
        with open(path, "rb") as f:
            return f.read()

    def latest_night(self):
        return json.loads(self.served())["folder"]

    def symlinks(self):
        return [os.path.join(d, n) for d, dirs, files in os.walk(self.v) for n in dirs + files
                if os.path.islink(os.path.join(d, n))]

    # The whole path

    def test_a_night_goes_in_whole_and_latest_is_a_folder_of_its_files(self):
        files = self.install(13)
        self.assertEqual({n: self.served(n) for n in NAMES}, files)
        self.assertEqual(self.latest_night(), night(13))
        self.assertFalse(os.path.islink(os.path.join(self.v, "latest")))
        self.assertEqual(self.symlinks(), [])

    def test_the_same_night_again_changes_nothing_and_a_new_one_downloads_only_what_changed(self):
        files = self.install(13, "same")
        again = self.pull(manifest(files, night(13)), files)
        self.assertEqual((again.returncode, again.asked), (0, ["manifest.json"]))
        newer = dict(files, **{"stops.csv.gz": gzip.compress(b"new stops", mtime=0)})
        run = self.pull(manifest(newer, night(14)), newer)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual([a for a in run.asked if a.endswith(".gz")], [night(14) + ".stops.csv.gz"])
        kept, made = (os.stat(os.path.join(self.v, n, "routes.csv.gz")) for n in (night(13), night(14)))
        self.assertEqual((made.st_mtime_ns, made.st_size, made.st_nlink), (kept.st_mtime_ns, kept.st_size, 2))
        self.assertNotEqual(made.st_ino, kept.st_ino)

    def test_a_bad_signature_or_checksum_leaves_latest_as_it_was(self):
        self.install(13)
        files = payload("x")
        run = self.pull(manifest(files, night(14)), files, signature=base64.b64encode(b"not a signature"))
        self.assertNotEqual(run.returncode, 0)
        self.assertFalse(any(a.endswith(".gz") for a in run.asked))
        doc = manifest(files, night(14))
        doc["files"][0]["sha256"] = "0" * 64
        self.assertNotEqual(self.pull(doc, files).returncode, 0)
        self.assertEqual(self.latest_night(), night(13))

    # Finding 3: only the manifest's known shape, the seven files, sizes in bounds; never the manifest as a payload

    def refused(self, doc, files):
        self.install(13)
        run = self.pull(doc, files)
        self.assertNotEqual(run.returncode, 0, "accepted: " + run.stdout)
        self.assertFalse(any(a.endswith(".gz") or a.endswith(".txt") for a in run.asked), run.asked)
        self.assertEqual(self.latest_night(), night(13))
        self.assertEqual(sorted(os.listdir(os.path.join(self.v, "latest"))), sorted(NAMES + ["manifest.json", "manifest.json.sig"]))
        return run

    def test_a_signed_manifest_missing_one_of_the_seven_files_is_refused(self):
        files = payload("x")
        del files["roads.geojson.gz"]
        self.refused(manifest(files, night(14)), files)

    def test_a_signed_manifest_naming_any_other_file_is_refused(self):
        files = dict(payload("x"), **{"unexpected.txt": b"unexpected"})
        self.refused(manifest(files, night(14)), files)

    def test_a_signed_manifest_naming_its_own_signature_as_a_file_is_refused(self):
        files = dict(payload("x"), **{"manifest.json.sig": base64.b64encode(b"overwritten")})
        self.refused(manifest(files, night(14)), files)

    def test_a_signed_manifest_with_keys_of_its_own_is_refused(self):
        files = payload("x")
        doc = manifest(files, night(14))
        doc["extra"] = "x"
        self.refused(doc, files)
        doc = manifest(files, night(14))
        doc["files"][0]["path"] = "../escape"
        self.refused(doc, files)

    def test_a_file_bigger_than_its_bounds_is_refused_before_any_download(self):
        files = dict(payload("x"), **{"operators.csv.gz": b"x" * 1_000_001})
        self.refused(manifest(files, night(14)), files)

    def test_a_file_bigger_than_its_manifest_says_stops_downloading_and_is_refused(self):
        self.install(13)
        files = dict(payload("x"), **{"roads.geojson.gz": b"x" * (8 << 20)})
        doc = manifest(files, night(14))
        next(f for f in doc["files"] if f["name"] == "roads.geojson.gz")["size"] = 1
        run = self.pull(doc, files)
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual(self.latest_night(), night(13))
        self.assertFalse(os.path.exists(os.path.join(self.v, night(14))))

    def test_a_night_whose_built_time_isnt_its_name_is_refused(self):
        files = payload("x")
        doc = manifest(files, night(14))
        doc["built"] = "2026-11-12T00:00:00+00:00"
        self.refused(doc, files)

    # Finding 4: never back in time; this night and the one before stay; latest whole or the script says so

    def test_an_older_night_signed_and_whole_is_refused_and_latest_stays_whole(self):
        self.install(12)
        self.install(13)
        old = payload("10")
        run = self.pull(manifest(old, night(10)), old)
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual(self.latest_night(), night(13))
        self.assertEqual(sorted(n for n in os.listdir(self.v) if n[0].isdigit()), [night(12), night(13)])

    def test_a_night_ahead_of_the_clock_is_refused(self):
        self.install(13)
        files = payload("x")
        doc = manifest(files, "29991231T000000Z")
        self.assertNotEqual(self.pull(doc, files).returncode, 0)
        self.assertEqual(self.latest_night(), night(13))

    def test_this_night_and_the_one_before_stay_and_older_go(self):
        for n in (12, 13, 14):
            self.install(n)
        self.assertEqual(sorted(n for n in os.listdir(self.v) if n[0].isdigit()), [night(13), night(14)])
        self.assertEqual(self.latest_night(), night(14))

    def test_with_latest_broken_and_nothing_to_take_the_script_says_so(self):
        self.install(13)
        subprocess.run(["rm", "-rf", os.path.join(self.v, night(13))], check=True)
        self.release.assets = {}
        run = subprocess.run(["sh", SCRIPT, self.data], capture_output=True, text=True, timeout=60,
                             env=dict(os.environ, KAV_DATA_RELEASE=self.release.url, KAV_DATA_PUBLIC_KEY=self.public))
        self.assertNotEqual(run.returncode, 0)

    def test_with_latest_broken_the_next_pull_puts_a_whole_night_back(self):
        files = self.install(13)
        subprocess.run(["rm", "-rf", os.path.join(self.v, night(13))], check=True)
        run = self.pull(manifest(files, night(13)), files)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual({n: self.served(n) for n in NAMES}, files)

    # Finding 5: no symlink is ever made, and any found is removed, never followed

    def test_a_symlink_in_a_kept_night_is_never_followed_or_published(self):
        files = self.install(13)
        stops = os.path.join(self.v, night(13), "stops.csv.gz")
        os.remove(stops)
        os.symlink(self.outside, stops)
        run = self.pull(manifest(files, night(14)), files)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(self.served("stops.csv.gz"), files["stops.csv.gz"])
        self.assertEqual(self.symlinks(), [])
        with open(self.outside) as f:
            self.assertEqual(f.read(), "OUTSIDE")

    def test_symlinks_anywhere_in_the_folder_are_removed(self):
        files = self.install(13)
        os.symlink("/", os.path.join(self.v, "anywhere"))
        os.symlink(self.outside, os.path.join(self.v, night(13), "extra"))
        os.symlink(os.path.dirname(self.outside), os.path.join(self.v, ".new"))
        run = self.pull(manifest(files, night(13)), files)
        self.assertEqual(self.symlinks(), [])
        with open(self.outside) as f:
            self.assertEqual(f.read(), "OUTSIDE")
        # The night that held a stray link went, and the same night came back from the release, whole.
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual({n: self.served(n) for n in NAMES}, files)



    def test_a_regular_lock_keeps_its_inode_and_contents(self):
        self.install(13)
        lock = os.path.join(self.v, ".lock")
        with open(lock, "wb") as stream:
            stream.write(b"LOCK")
        inode = os.stat(lock).st_ino
        self.assertEqual(self.again().returncode, 0)
        self.assertEqual(os.stat(lock).st_ino, inode)
        with open(lock, "rb") as stream:
            self.assertEqual(stream.read(), b"LOCK")

    def test_a_hardlinked_lock_never_truncates_an_outside_file(self):
        old = self.install(13)
        lock = os.path.join(self.v, ".lock")
        os.unlink(lock)
        link(self.outside, lock)
        run = self.again()
        with open(self.outside, "rb") as stream:
            self.assertEqual(stream.read(), b"OUTSIDE")
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual({name: self.served(name) for name in old}, old)

    def test_a_symlinked_lock_is_not_followed(self):
        os.makedirs(self.v)
        os.symlink(self.outside, os.path.join(self.v, ".lock"))
        files = payload("13")
        self.assertEqual(self.pull(manifest(files, night(13)), files).returncode, 0)
        with open(self.outside) as f:
            self.assertEqual(f.read(), "OUTSIDE")
        self.assertEqual(self.symlinks(), [])

    def test_latest_as_a_symlink_from_the_first_version_is_replaced_by_a_folder(self):
        old = os.path.join(self.v, night(12))
        os.makedirs(old)
        os.symlink(night(12), os.path.join(self.v, "latest"))
        files = payload("13")
        self.assertEqual(self.pull(manifest(files, night(13)), files).returncode, 0)
        self.assertFalse(os.path.islink(os.path.join(self.v, "latest")))
        self.assertEqual(self.latest_night(), night(13))

    # v5, 1: files are put together in a folder of the script's own that nothing else can write, each opened as a new
    # file that no link can redirect, checked a plain file of one link before and after its sha256; a run that fails
    # leaves nothing of itself and has written nowhere else.

    def plant_during(self, asset, name):
        """The moment the release is asked for asset, a link to the outside file is put at name, over what is there, in
        every folder of v1 a night is being put together in (a name starting with a dot): as another program could."""
        def plant():
            for d in os.listdir(self.v):
                folder = os.path.join(self.v, d)
                if d.startswith(".") and os.path.isdir(folder) and not os.path.islink(folder):
                    target = os.path.join(folder, name)
                    if os.path.lexists(target):
                        os.remove(target)
                    os.symlink(self.outside, target)
        self.release.before[asset] = plant

    def test_a_link_put_in_while_a_file_downloads_is_never_written_through_and_the_night_before_stays(self):
        old = self.install(13)
        files = payload("14")
        self.plant_during(night(14) + ".stops.csv.gz", "stops.csv.gz")
        run = self.pull(manifest(files, night(14)), files)
        self.assertNotEqual(run.returncode, 0)
        self.assertIn(night(14) + ".stops.csv.gz", run.asked)
        with open(self.outside) as f:
            self.assertEqual(f.read(), "OUTSIDE")
        self.assertEqual(self.latest_night(), night(13))
        self.assertEqual({n: self.served(n) for n in NAMES}, old)
        self.assertEqual(self.symlinks(), [])
        self.assertEqual(sorted(os.listdir(self.v)), [".lock", night(13), "latest"])

    def test_a_link_put_in_while_the_manifest_downloads_changes_nothing(self):
        old = self.install(13)
        files = payload("14")
        self.plant_during(night(14) + ".manifest.json", "manifest.json")
        run = self.pull(manifest(files, night(14)), files)
        self.assertNotEqual(run.returncode, 0)
        with open(self.outside) as f:
            self.assertEqual(f.read(), "OUTSIDE")
        self.assertEqual({n: self.served(n) for n in NAMES}, old)
        self.assertEqual(sorted(os.listdir(self.v)), [".lock", night(13), "latest"])

    def test_a_data_folder_others_may_write_in_is_refused_before_anything_is_asked(self):
        old = self.install(13)
        os.chmod(self.v, 0o777)
        files = payload("14")
        run = self.pull(manifest(files, night(14)), files)
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual(run.asked, [])
        self.assertEqual({n: self.served(n) for n in NAMES}, old)

    # v5, 2: what is served is checked against its sha256 on every run, put right from the release, and the run fails
    # when it can't be.

    def spoil(self, name, old, new):
        path = os.path.join(self.v, "latest", name)
        with open(path, "rb") as f:
            data = f.read()
        self.assertIn(old, data)
        with open(path, "r+b") as f:  # in place: the night's folder holds the same file
            f.write(data.replace(old, new, 1))

    def test_a_served_file_gone_bad_is_put_right_from_the_release(self):
        files = self.install(13)
        self.spoil("stops.csv.gz", files["stops.csv.gz"][:4], b"XXXX")
        run = self.again()
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertIn(night(13) + ".stops.csv.gz", run.asked)
        self.assertEqual({n: self.served(n) for n in NAMES}, files)
        self.assertEqual(self.latest_night(), night(13))

    def test_a_served_manifest_gone_bad_is_put_right_from_the_release(self):
        files = self.install(13)
        right = self.served()
        self.spoil("manifest.json", b'"rows": 1', b'"rows": 7')
        run = self.again()
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual((self.served(), {n: self.served(n) for n in NAMES}), (right, files))

    def test_a_served_file_gone_bad_that_the_release_cant_put_right_fails_the_run(self):
        files = self.install(13)
        self.spoil("roads.geojson.gz", files["roads.geojson.gz"][:4], b"XXXX")
        for name in (night(13) + ".roads.geojson.gz", "roads.geojson.gz"):
            del self.release.assets[name]
        run = self.again()
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual(self.latest_night(), night(13))

    # v5, 3: a night's name stands for its files: the same name given to other files is refused, and what is served stays.

    def test_a_night_s_name_given_again_to_other_files_is_refused(self):
        files = self.install(13)
        right = self.served()
        other = payload("other")
        run = self.pull(manifest(other, night(13)), other)
        self.assertNotEqual(run.returncode, 0)
        self.assertFalse(any(a.endswith(".gz") for a in run.asked), run.asked)
        self.assertEqual((self.served(), {n: self.served(n) for n in NAMES}), (right, files))

    # v6, 5: every field of a signed manifest of its type and in its range, every licence and source a file names given

    def test_a_signed_manifest_with_any_field_of_the_wrong_type_or_range_is_refused(self):
        files = payload("x")
        for i, change in enumerate((lambda d: d.update(contact=[]), lambda d: d.update(licences=False), lambda d: d.update(sources=False),
                       lambda d: d.update(docs="ftp://somewhere"), lambda d: d["files"][0].update(rows=-1),
                       lambda d: d["files"][0].update(rows=1.5), lambda d: d["files"][0].update(credit=None),
                       lambda d: d["files"][0].update(sources=False), lambda d: d["files"][0].update(changed=11),
                       lambda d: d["files"][0].update(licence="Invented-1.0"), lambda d: d["files"][0].update(sources=["nowhere"]),
                       lambda d: d["files"][0].update(licence_note=5), lambda d: d["licences"]["CC-BY-4.0"].update(url=None),
                       lambda d: d["sources"]["mot-gtfs"].update(name=7))):
            doc = manifest(files, night(14))
            change(doc)
            with self.subTest(change=i):
                self.setUp()
                self.refused(doc, files)

    # v7, 1: the gate and the pull take the same manifests, by one list of rules: every bad one refused by both

    BAD = [  # (what is wrong, the change): one at least for each rule in site/scripts/data-pull.sh's list
        ("1 a key more", lambda d: d.update(extra="x")), ("1 a key fewer", lambda d: d.pop("contact")),
        ("2 format", lambda d: d.update(format="kav")), ("2 version true", lambda d: d.update(version=True)),
        ("2 version as text", lambda d: d.update(version="1")), ("2 version 2", lambda d: d.update(version=2)),
        ("3 folder not a night", lambda d: d.update(folder="tonight")), ("3 built not the folder", lambda d: d.update(built="2026-11-12T00:00:00+00:00")),
        ("4 docs https:// alone", lambda d: d.update(docs="https://")), ("4 docs not https", lambda d: d.update(docs="http://kavapp.link/docs/data")),
        ("4 docs with a space", lambda d: d.update(docs="https://kavapp.link/docs data")), ("4 contact no @", lambda d: d.update(contact="kavapp.link")),
        ("4 contact a list", lambda d: d.update(contact=[])),
        ("5 licences false", lambda d: d.update(licences=False)), ("5 licences empty", lambda d: d.update(licences={})),
        ("5 a licence's url https:// alone", lambda d: d["licences"]["CC-BY-4.0"].update(url="https://")),
        ("5 a licence's key more", lambda d: d["licences"]["CC-BY-4.0"].update(note="x")),
        ("6 sources false", lambda d: d.update(sources=False)), ("6 sources empty", lambda d: d.update(sources={})),
        ("6 a source's name a number", lambda d: d["sources"]["mot-gtfs"].update(name=7)),
        ("6 a source's other value a number", lambda d: d["sources"]["mot-gtfs"].update(rows=3)),
        ("7 files not a list", lambda d: d.update(files={})), ("7 a file fewer", lambda d: d["files"].pop()),
        ("8 a file's key more", lambda d: d["files"][0].update(path="x")), ("8 a file's key fewer", lambda d: d["files"][0].pop("credit")),
        ("9 sha256 in capitals", lambda d: d["files"][0].update(sha256=d["files"][0]["sha256"].upper())),
        ("9 size 0", lambda d: d["files"][0].update(size=0)), ("9 size as text", lambda d: d["files"][0].update(size="10")),
        ("9 size over its most", lambda d: d["files"][2].update(size=1_000_001)), ("9 rows 0", lambda d: d["files"][0].update(rows=0)),
        ("9 rows true", lambda d: d["files"][0].update(rows=True)), ("9 rows 1.5", lambda d: d["files"][0].update(rows=1.5)),
        ("10 licence not given", lambda d: d["files"][0].update(licence="Invented-1.0")),
        ("10 credit empty", lambda d: d["files"][0].update(credit="")), ("10 changed a number", lambda d: d["files"][0].update(changed=11)),
        ("10 sources empty", lambda d: d["files"][0].update(sources=[])), ("10 a source not given", lambda d: d["files"][0].update(sources=["nowhere"])),
        ("10 licence_note empty", lambda d: d["files"][1].update(licence_note="")), ("10 licence_note a number", lambda d: d["files"][1].update(licence_note=5)),
        ("11 each file at its most, together over the most for all", lambda d: [f.update(size=MOST[f["name"]]) for f in d["files"]]),
    ]

    def test_the_gate_and_the_pull_give_the_same_answer_for_every_manifest(self):
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import open_data
        files = payload("x")
        for what, change in [("a whole manifest", lambda d: None)] + self.BAD:
            doc = manifest(files, night(14))
            change(doc)
            with self.subTest(what):
                self.setUp()
                self.install(13)
                pull = self.pull(doc, files).returncode == 0
                gate = not open_data.manifest_problems(doc)
                self.assertEqual((pull, gate), (what == "a whole manifest",) * 2)

    # v8, 1: exactly one JSON object, and the night checked whole before latest is touched

    def two_values(self, doc, keep_folder=False):
        """The checker's bytes: an invalid object (version true, no files, without its folder or with it), a newline,
        then doc."""
        bad = dict(doc, version=True, files=[])
        if not keep_folder:
            del bad["folder"]
        return json.dumps(bad).encode() + b"\n" + json.dumps(doc, indent=1).encode()

    def as_it_is(self):
        """Everything in v1 as it is: each folder's names and bytes."""
        return {d: {n: open(os.path.join(self.v, d, n), "rb").read() for n in sorted(os.listdir(os.path.join(self.v, d)))}
                for d in sorted(os.listdir(self.v)) if os.path.isdir(os.path.join(self.v, d))}

    def test_the_shell_check_and_the_gate_take_exactly_one_json_object(self):
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import open_data
        doc = manifest(payload("x"), night(14))
        good = json.dumps(doc, indent=1).encode()
        for what, raw, takes in (("one object", good, True), ("one object, white space around it", b"\n " + good + b"\n", True),
                                 ("an invalid object, then a valid one", self.two_values(doc), False),
                                 ("an invalid object with the folder, then a valid one", self.two_values(doc, True), False),
                                 ("the same object twice", good + good, False), ("in a list", b"[" + good + b"]", False),
                                 ("a number before it", b"1 " + good, False), ("text after it", good + b" x", False),
                                 ("nothing", b"", False)):
            with self.subTest(what):
                path = os.path.join(self.dir, "check.json")
                with open(path, "wb") as f:
                    f.write(raw)
                shell = subprocess.run(["sh", SCRIPT, "--check-manifest", path], capture_output=True, timeout=60).returncode == 0
                try:
                    gate = not open_data.manifest_problems(json.loads(raw))  # as the gate reads a night's manifest.json
                except ValueError:
                    gate = False
                self.assertEqual((shell, gate), (takes, takes))

    def test_a_signed_manifest_of_two_json_values_is_refused_before_latest_is_touched(self):
        for keep_folder in (False, True):
            with self.subTest(keep_folder=keep_folder):
                self.setUp()
                self.install(13)
                before = self.as_it_is()
                files = payload("14")
                doc = manifest(files, night(14))
                run = self.pull(doc, files, dated=self.two_values(doc, keep_folder))
                self.assertNotEqual(run.returncode, 0)
                self.assertEqual(self.as_it_is(), before)  # latest, and everything else, exactly as it was
                self.assertEqual(sorted(os.listdir(self.v)), [".lock", night(13), "latest"])

    # v9: without a jq that works, or with latest's manifest read as nothing, the run stops with the tree untouched

    def broken_jq(self, kind):
        """A PATH of every tool the script runs but jq, which is: absent, a jq exiting 127, a jq printing nothing, or the
        real jq but for reading latest's manifest, which it reads as nothing."""
        bin_ = os.path.join(self.dir, "bin-" + kind)
        os.makedirs(bin_, exist_ok=True)
        for tool in ("sh", "dash", "bash", "mkdir", "stat", "id", "mktemp", "ls", "rm", "flock", "base64", "openssl", "cmp",
                     "curl", "dd", "wc", "date", "tr", "cut", "sha256sum", "touch", "chmod", "mv", "ln", "du", "grep", "cat"):
            where = shutil.which(tool)
            if where and not os.path.exists(os.path.join(bin_, tool)):
                os.symlink(where, os.path.join(bin_, tool))
        jq = os.path.join(bin_, "jq")
        body = {"absent": None, "exits 127": "exit 127\n", "prints nothing": "exit 0\n",
                "reads latest as nothing": 'case "$*" in *latest/manifest.json*) exit 0 ;; esac\n'
                                           'exec %s "$@"\n' % shutil.which("jq")}[kind]
        if body is not None:
            with open(jq, "w") as f:
                f.write("#!%s\n%s" % (shutil.which("sh"), body))
            os.chmod(jq, 0o755)
        return bin_

    def test_without_a_jq_that_works_a_run_stops_and_changes_nothing(self):
        shells = ["sh"] + (["dash"] if shutil.which("dash") else [])
        for kind in ("absent", "exits 127", "prints nothing", "reads latest as nothing"):
            for shell in shells:
                with self.subTest(jq=kind, shell=shell):
                    self.setUp()
                    files = self.install(13)
                    self.release.publish(manifest(payload("14"), night(14)), payload("14"), self.key)
                    before = self.as_it_is()
                    run = subprocess.run([shell, SCRIPT, self.data], capture_output=True, text=True, timeout=60,
                                         stdin=subprocess.DEVNULL,
                                         env=dict(os.environ, PATH=self.broken_jq(kind), KAV_DATA_RELEASE=self.release.url,
                                                  KAV_DATA_PUBLIC_KEY=self.public))
                    self.assertNotEqual(run.returncode, 0, run.stderr)
                    self.assertEqual(self.as_it_is(), before)  # latest and every night byte for byte as they were
                    self.assertEqual(sorted(os.listdir(self.v)), [".lock", night(13), "latest"])
                    self.assertEqual({n: self.served(n) for n in NAMES}, files)

    def test_latest_naming_a_night_that_isn_t_here_is_kept_not_deleted(self):
        files = self.install(13)
        subprocess.run(["rm", "-rf", os.path.join(self.v, night(13))], check=True)
        self.release.assets = {}  # nothing to take: the run fails, and latest, still whole, stays
        run = self.again()
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual({n: self.served(n) for n in NAMES}, files)

    # v6, 6: a run that can't take the lock succeeds only if it has itself checked that what is served is whole

    def locked(self):
        lock = open(os.path.join(self.v, ".lock"), "w")
        self.addCleanup(lock.close)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_a_run_that_cant_take_the_lock_fails_when_what_is_served_isnt_whole(self):
        files = self.install(13)
        self.spoil("stops.csv.gz", files["stops.csv.gz"][:4], b"XXXX")
        self.locked()
        run = self.again()
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual(run.asked, [])

    def test_a_run_that_cant_take_the_lock_succeeds_when_what_is_served_is_whole(self):
        files = self.install(13)
        self.locked()
        run = self.again()
        self.assertEqual((run.returncode, run.asked), (0, []), run.stderr)
        self.assertEqual({n: self.served(n) for n in NAMES}, files)
        self.assertEqual(sorted(os.listdir(self.v)), [".lock", night(13), "latest"])


if __name__ == "__main__":
    unittest.main()
