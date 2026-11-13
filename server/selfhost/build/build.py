"""Kav's trip planner on a server of your own (../docker-compose.yml): built on the first start, then every night at
REBUILD_AT, Israel time, from that night's timetable, by the steps server/otp/rebuild.sh takes on Kav's own server, here
in one container rather than one each. kav-otp serves each new graph as soon as it's moved in, and the old one until
then; a build that fails leaves it alone. With PLANNER=off, or PLANNER=own for a planner of your own, it builds the
timetable only.

The first start also makes the key the app's timetable is signed with (data/signing/bundle.key), whose public half
kav-live gives the app with its status, and every start fetches the arrival model's files for kav-eta (MODELS_URL, taken
only when they hash to MODELS_SHA256).
"""
import base64
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import time
import urllib.request
import zoneinfo

CODE = "/kav/server/otp"  # prepare.py and the steps after it, from the repository
BUNDLER = "/kav/tools/export_web_bundle.py"
BUILD, SERVE, OFFICIAL = "/data/otp/build", "/data/otp/serve", "/data/otp/official"
OFFICIAL_BUILD, OFFICIAL_SERVE = "/data/otp/build-official", "/data/otp/serve-official"
REALTIME, HISTORY, MODELS, SIGNING = "/data/realtime", "/data/history", "/data/models", "/data/signing"
ISRAEL = zoneinfo.ZoneInfo("Asia/Jerusalem")
REBUILD_AT = datetime.time.fromisoformat(os.environ.get("REBUILD_AT", "00:30"))
MEMORY = int(os.environ.get("BUILD_MEMORY", "15"))  # GB the container may take, Java one less
MODELS_URL = os.environ.get("MODELS_URL", "")
MODELS_SHA256 = os.environ.get("MODELS_SHA256", "")
OFFICIAL_URL = os.environ.get("OFFICIAL_URL", "")  # set when the official planner is on: its graph is built too
# off: live buses and the timetable only; own: those, and a planner of your own (OTP_URL or PLAN_URL) plans the trips.
PLANNER = os.environ.get("PLANNER", "on").lower() not in ("off", "0", "no", "false", "own")
# south,west,north,east: the planner for that area alone, for a smaller machine (../otp/area.py). The app's timetable
# stays all of Israel's. Empty: all of it.
AREA = os.environ.get("AREA", "").strip()
RETRY = 3600  # seconds before a first build that failed is tried again
# The nightly build's steps, in order, each replaceable by a command of your own (STAGE_<NAME>, say
# STAGE_PREDICT="python3 /stages/my_predict.py"), or left out (STAGE_<NAME>=skip): server/selfhost/README.md, "Build steps".
STAGES = ("prepare", "area", "walks", "predict", "clocks", "graph", "bundle")
STAGE_FOLDER = "/stages"
# The setup page's "Rebuild now": a file it leaves here, taken as soon as the build loop sees it (within a minute).
REBUILD_NOW = "/data/otp/rebuild-now"
# What the last build did, for the setup page: {"at", "ok", "minutes", "said"}.
LAST_BUILD = "/data/otp/last-build.json"  # ./stages beside docker-compose.yml, where commands of your own live


def log(*parts):
    print(datetime.datetime.now(ISRAEL).strftime("%Y-%m-%d %H:%M:%S"), *parts, flush=True)


def run(args, out=None, **env):
    """One step, its output in the log or in the file out; raises CalledProcessError when it fails."""
    log("  " + " ".join(args))
    if out is None:
        subprocess.run(args, check=True, env={**os.environ, **env})
        return
    with open(out, "w") as f:
        subprocess.run(args, check=True, stdout=f, stderr=subprocess.STDOUT, env={**os.environ, **env})


def stage(name, default, out=None):
    """One step of the nightly build: Kav's own (default, a command), or the one STAGE_<NAME> gives, run in a shell with
    the build's folders in its environment (BUILD, OFFICIAL, HISTORY, REALTIME, MODELS) and AREA; skipped with
    STAGE_<NAME>=skip.
    Every step reads and writes the same files in BUILD (israel-gtfs.zip, israel.osm.pbf, graph.obj)."""
    own = os.environ.get("STAGE_" + name.upper(), "").strip()
    if own.lower() == "skip":
        log("  %s: skipped (STAGE_%s=skip)" % (name, name.upper()))
        return
    folders = dict(BUILD=BUILD, OFFICIAL=OFFICIAL, HISTORY=HISTORY, REALTIME=REALTIME, MODELS=MODELS, STAGES=STAGE_FOLDER,
                   AREA=AREA)
    if own:
        log("  %s: your own (STAGE_%s)" % (name, name.upper()))
        run(["sh", "-c", own], out, **folders)
    else:
        default(folders, out)


def put(path, data):
    """Writes path whole or not at all: kav-live may be reading it."""
    with open(path + ".part", "wb") as f:
        f.write(data)
    os.replace(path + ".part", path)


def models():
    """The arrival model's files in MODELS, fetched again when the ones there aren't MODELS_SHA256's."""
    mark = os.path.join(MODELS, ".sha256")
    if not MODELS_URL:
        return
    try:
        with open(mark) as f:
            if f.read().strip() == MODELS_SHA256:
                return
    except OSError:
        pass
    part = os.path.join(MODELS, ".download")
    try:
        req = urllib.request.Request(MODELS_URL, headers={"User-Agent": "kav-build"})
        with urllib.request.urlopen(req, timeout=120) as r, open(part, "wb") as f:
            shutil.copyfileobj(r, f, 1 << 20)
        with open(part, "rb") as f:
            got = hashlib.file_digest(f, "sha256").hexdigest()
        if got != MODELS_SHA256:
            raise ValueError("their sha256 is %s, not MODELS_SHA256" % got)
        # Unpacked beside the old files, which then make way for it whole: a pack without the train models doesn't
        # keep an earlier pack's, and one that fails to unpack leaves the old in place.
        fresh = os.path.join(MODELS, ".next")
        shutil.rmtree(fresh, ignore_errors=True)
        with tarfile.open(part) as t:
            t.extractall(fresh, filter="data")
        for name in os.listdir(MODELS):
            if not name.startswith("."):
                path = os.path.join(MODELS, name)
                shutil.rmtree(path) if os.path.isdir(path) and not os.path.islink(path) else os.remove(path)
        for name in os.listdir(fresh):
            os.replace(os.path.join(fresh, name), os.path.join(MODELS, name))
        os.rmdir(fresh)
        put(mark, got.encode())
        log("the arrival model's files are in:", ", ".join(sorted(n for n in os.listdir(MODELS) if not n.startswith("."))))
    except Exception as e:
        log("the arrival model's files weren't fetched (%r): kav-eta waits for them" % e)
    finally:
        if os.path.exists(part):
            os.remove(part)


def signing_key():
    """The key the app's timetable is signed with, made the first time; its public half, as the app pins one, beside
    the timetable for kav-live to give."""
    key = os.path.join(SIGNING, "bundle.key")
    if not os.path.exists(key):
        old = os.umask(0o077)
        try:
            run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", key + ".part"])
        finally:
            os.umask(old)
        os.replace(key + ".part", key)
    public = subprocess.run(["openssl", "ec", "-in", key, "-pubout", "-outform", "DER"], check=True,
                            capture_output=True).stdout
    put(os.path.join(REALTIME, "bundle-key"), base64.b64encode(public))
    return key


def bundle(key):
    """The app's own timetable for the seven days from today, from tonight's timetable with its real calendars and the
    weekend lines, its manifest signed, for phones to take in place of the week they shipped with; kav-live serves
    realtime/bundle. Not needed for a planner: if it fails, the phones keep the one they have."""
    new, old, served = (os.path.join(REALTIME, n) for n in ("bundle-new", "bundle-old", "bundle"))
    today = datetime.datetime.now(ISRAEL).date().isoformat()
    for folder in (new, old):
        shutil.rmtree(folder, ignore_errors=True)
    os.makedirs(new)
    try:
        run(["python3", BUNDLER], os.path.join(BUILD, "bundle.log"), KAV_FROM=today, KAV_BBOX="national", KAV_REGION="il",
            KAV_MANIFEST="1", KAV_GTFS=os.path.join(OFFICIAL, "mot-gtfs.zip"),
            KAV_NAIM=os.path.join(OFFICIAL, "naim-gtfs.zip"), KAV_OUT=new)
        os.remove(os.path.join(new, "il.kav"))
        signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key, os.path.join(new, "manifest.json")],
                                   check=True, capture_output=True).stdout
        put(os.path.join(new, "manifest.json.sig"), base64.b64encode(signature))
        if os.path.isdir(served):
            os.rename(served, old)
        os.rename(new, served)
        log("app timetable from", today)
    except (OSError, subprocess.CalledProcessError) as e:
        log("app timetable not built (%r): phones keep theirs" % e)
        if not os.path.isdir(served) and os.path.isdir(old):
            os.rename(old, served)
    finally:
        for folder in (new, old):
            shutil.rmtree(folder, ignore_errors=True)


def rebuild():
    """Tonight's timetable and planner, as rebuild.sh builds them, put in service."""
    started = time.time()
    # All of Israel's map back where prepare.py keeps it, if the last build cut it to an area.
    run(["python3", os.path.join(CODE, "area.py"), BUILD, "restore"])
    stage("prepare", lambda env, out: run(["python3", os.path.join(CODE, "prepare.py"), BUILD], out))
    stage("bundle", lambda env, out: bundle(signing_key()))
    if not PLANNER:
        publish()
        log("timetable rebuilt in %d minutes (no planner)" % round((time.time() - started) / 60))
        return
    # The planner for one area alone: after the app's timetable, which stays all of Israel's.
    if AREA:
        stage("area", lambda env, out: run(["python3", os.path.join(CODE, "area.py"), BUILD, AREA]))
    # Joins a few bus stops' sidewalks to the street the bus stops on, where OpenStreetMap leaves them apart.
    try:
        stage("walks", lambda env, out: run(["python3", os.path.join(CODE, "walks.py"), os.path.join(BUILD, "israel.osm.pbf")]))
    except subprocess.CalledProcessError:
        log("walks not mended: the map stays as it was")
    # The official planner's timetable: tonight's, with the ministry's own times, before predict.py moves the buses.
    if OFFICIAL_URL:
        shutil.rmtree(OFFICIAL_BUILD, ignore_errors=True)
        os.makedirs(OFFICIAL_BUILD)
        for name in ("israel-gtfs.zip", "israel.osm.pbf"):
            shutil.copy(os.path.join(BUILD, name), OFFICIAL_BUILD)
    # The buses moved to when they really get to each stop, from the passings kav-live has recorded.
    stage("predict", lambda env, out: run(["python3", os.path.join(CODE, "predict.py"), BUILD, HISTORY]))
    # The night the clocks change, the trips before it go where the ministry runs them.
    stage("clocks", lambda env, out: run(["python3", os.path.join(CODE, "clocks.py"), BUILD]))
    for name in ("build-config.json", "router-config.json"):
        shutil.copy(os.path.join(CODE, name), BUILD)
    graph = os.path.join(BUILD, "graph.obj")
    if os.path.exists(graph):
        os.remove(graph)
    try:
        stage("graph", lambda env, out: run(["java", "-Xmx%dg" % (MEMORY - 1), "-cp", "@/app/jib-classpath-file",
                                             "@/app/jib-main-class-file", BUILD, "--build", "--save"], out),
              os.path.join(BUILD, "build.log"))
    except subprocess.CalledProcessError:
        with open(os.path.join(BUILD, "build.log"), errors="replace") as f:
            log("planner build failed:\n" + "".join(f.readlines()[-20:]))
        raise
    shutil.copy(os.path.join(CODE, "router-config.json"), SERVE)
    os.replace(graph, os.path.join(SERVE, "graph.obj"))
    publish()
    log("planner rebuilt in %d minutes" % round((time.time() - started) / 60))
    if OFFICIAL_URL:
        official()


def publish():
    """What kav-live reads of tonight's timetable: the live updates name trips by their route's direction in it and find
    each trip's stops in it; the weekend lines' timetable goes to its catalog."""
    for name, folder in (("directions.json", BUILD), ("trip-patterns.json", BUILD), ("naim-gtfs.zip", OFFICIAL)):
        if os.path.exists(os.path.join(folder, name)):
            with open(os.path.join(folder, name), "rb") as f:
                put(os.path.join(REALTIME, name), f.read())


def official():
    """The official planner's graph, from the timetable rebuild() kept, built as the main one is and after it. If it fails,
    the official planner keeps its last graph."""
    try:
        run(["python3", os.path.join(CODE, "clocks.py"), OFFICIAL_BUILD])
        for name in ("build-config.json", "router-config.json"):
            shutil.copy(os.path.join(CODE, name), OFFICIAL_BUILD)
        run(["java", "-Xmx%dg" % (MEMORY - 1), "-cp", "@/app/jib-classpath-file", "@/app/jib-main-class-file",
             OFFICIAL_BUILD, "--build", "--save"], os.path.join(OFFICIAL_BUILD, "build.log"))
        os.makedirs(OFFICIAL_SERVE, exist_ok=True)
        shutil.copy(os.path.join(CODE, "router-config.json"), OFFICIAL_SERVE)
        os.replace(os.path.join(OFFICIAL_BUILD, "graph.obj"), os.path.join(OFFICIAL_SERVE, "graph.obj"))
        log("official planner rebuilt")
    except (OSError, subprocess.CalledProcessError) as e:
        log("official planner not rebuilt (%r): it keeps the last one" % e)


def next_build(now):
    at = datetime.datetime.combine(now.date(), REBUILD_AT, ISRAEL)
    return at if at > now else datetime.datetime.combine(now.date() + datetime.timedelta(days=1), REBUILD_AT, ISRAEL)


def main():
    for folder in (BUILD, SERVE, OFFICIAL, REALTIME, MODELS, SIGNING):
        os.makedirs(folder, exist_ok=True)
    signing_key()
    models()
    # What a first build leaves: the planner's graph, or without the planner the timetable kav-live reads.
    first = os.path.join(SERVE, "graph.obj") if PLANNER else os.path.join(REALTIME, "trip-patterns.json")
    built = False
    while not built and not os.path.exists(first):
        log("building the first planner: about half an hour" if PLANNER else "building the first timetable")
        try:
            rebuild()
            built = True
        except Exception as e:
            log("the first build failed (%r): again in an hour" % e)
            time.sleep(RETRY)
            models()
    # Turned on after the first build: its timetable comes only with a whole build.
    if PLANNER and OFFICIAL_URL and not os.path.exists(os.path.join(OFFICIAL_SERVE, "graph.obj")):
        log("building the official planner, and the main one again: about three quarters of an hour")
        try:
            rebuild()
        except Exception as e:
            log("that build failed (%r): the next one tries again" % e)
    while True:
        now = datetime.datetime.now(ISRAEL)
        at = next_build(now)
        log("next build at", at.strftime("%Y-%m-%d %H:%M"))
        put(LAST_BUILD + ".next", at.isoformat().encode())
        while datetime.datetime.now(ISRAEL) < at and not os.path.exists(REBUILD_NOW):
            time.sleep(min(60, max(1, (at - datetime.datetime.now(ISRAEL)).total_seconds())))
        if os.path.exists(REBUILD_NOW):
            os.remove(REBUILD_NOW)
            log("rebuilding now, as asked on the setup page")
        models()
        started = time.time()
        try:
            rebuild()
            said = {"ok": True}
        except Exception as e:
            log("the build failed (%r): the planner keeps the last one" % e)
            said = {"ok": False, "said": repr(e)[:300]}
        put(LAST_BUILD, json.dumps(dict(said, at=int(time.time()), minutes=round((time.time() - started) / 60))).encode())


if __name__ == "__main__":
    main()
