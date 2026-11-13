"""The setup page and dashboard of a copy of Kav's server (server/selfhost): the owner's account first, then a short
wizard for what runs, then the dashboard for running it: its parts, the people who may use it, updates and storage.

It writes .env from what's chosen and starts the rest with Docker Compose. The settings as chosen are kept in
data/setup-state.json, and .env is made from them. Who may sign in to it are the server's own admins (accounts.py,
shared with kav-live). The very first visit needs the one-time code install.sh shows, and only until the owner's
account exists. Python's own library only. The pages: pages.py."""
import hashlib
import http.server
import json
import os
import re
import secrets
import shlex
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DIR = os.environ.get("HOST_DIR") or os.path.dirname(HERE)
# accounts.py and conformance.py: beside this in its image, two folders up in the repository.
sys.path[:0] = [HERE, os.path.join(HERE, "..", "..")]
import accounts  # noqa: E402
import conformance  # noqa: E402

DATA = os.path.join(DIR, "data")
ENV = os.path.join(DIR, ".env")
STATE = os.path.join(DATA, "setup-state.json")
CODE_FILE = os.path.join(DATA, "setup-code")
PEOPLE = os.path.join(DATA, "history", "accounts.sqlite3")
MOT_URL = "https://moran.mot.gov.il/Channels/HTTPChannel/SmQuery/2.8/json"
REPO = "ImNoammm/kav"  # where releases come from, unless KAV_REPO in .env names a fork
# A release's own files, kept beside .env: the services, what each .env line means, the HTTPS proxy's settings.
RELEASE_FILES = ("docker-compose.yml", ".env.example", "Caddyfile")
SESSION_S = 12 * 3600
PACK_MOST = 500 << 20  # bytes a model pack may be
HEALTHY_WITHIN = 240  # seconds an update has to come up healthy in, or it's rolled back

# What the setup page sets in .env. Anything else there (set by hand) is kept as it is.
MANAGED = ("COMPOSE_PROFILES", "MOT_KEY", "LIVE_URL", "DOMAIN", "PUBLIC_URL", "ACCESS", "PLANNER", "OTP_URL", "PLAN_URL",
           "OFFICIAL_URL", "PREDICT_URL", "ETA", "MODELS_URL", "MODELS_SHA256", "GTFS_URL", "OSM_URL", "BOUNDS",
           "ALERTS_SOURCE", "ALERTS_URL", "ALERTS_HEADERS", "TRANSLATE_URL",
           "BUILD_MEMORY", "PLANNER_MEMORY", "STAGE_PREPARE", "STAGE_AREA", "STAGE_WALKS", "STAGE_PREDICT", "STAGE_CLOCKS", "STAGE_GRAPH", "STAGE_BUNDLE")
DEFAULTS = {"live": "mot", "MOT_KEY": "", "LIVE_URL": "", "address": "domain", "DOMAIN": "", "PUBLIC_URL": "",
            "ACCESS": "accounts", "planner": "kav", "OTP_URL": "", "PLAN_URL": "", "official": False, "predictor": "kav",
            "pack": {}, "PREDICT_URL": "", "GTFS_URL": "", "OSM_URL": "", "area": "", "stages": {},
            "alerts": "off", "ALERTS_URL": "", "ALERTS_HEADERS": "", "english": False}
# The planner for one area alone (build/build.py's AREA, ../otp/area.py): south,west,north,east. Gush Dan measured the
# most of them (7 Nov 2026): the nightly build within 4 GB (Java 3 of them), the planner answering within 2.
AREAS = {"gush-dan": "31.95,34.70,32.20,34.95", "jerusalem": "31.70,35.10,31.88,35.30",
         "haifa": "32.73,34.95,32.90,35.15", "beersheba": "31.18,34.70,31.32,34.88"}
ISRAEL = (29.4, 34.2, 33.4, 35.9)  # south, west, north, east: an area has to be in it
STAGES = ("prepare", "area", "walks", "predict", "clocks", "graph", "bundle")  # build/build.py's


def owned(path):
    """A file this writes as root belongs to whoever owns the folder, so they can read and edit it."""
    st = os.stat(DIR)
    try:
        os.chown(path, st.st_uid, st.st_gid)
    except OSError:
        pass


def write(path, text, private=False):
    if not os.path.isdir(os.path.dirname(path)):
        os.makedirs(os.path.dirname(path))
        owned(os.path.dirname(path))
    with open(path + ".part", "w", encoding="utf-8") as f:
        f.write(text)
    if private:
        os.chmod(path + ".part", 0o600)
    owned(path + ".part")
    os.replace(path + ".part", path)


# The state: the settings as chosen, whether the wizard is done, the last start and update.

state_lock = threading.Lock()


def load():
    try:
        with open(STATE, encoding="utf-8") as f:
            got = json.load(f)
    except (OSError, ValueError):
        got = {}
    got.setdefault("settings", dict(DEFAULTS))
    for k, v in DEFAULTS.items():
        got["settings"].setdefault(k, v)
    # A source of alerts an older version had and this one hasn't is none.
    if got["settings"]["alerts"] not in ("gtfsrt", "off"):
        got["settings"]["alerts"] = "off"
    got.setdefault("done", False)
    got.setdefault("auto_update", False)
    got.setdefault("start", {})
    got.setdefault("update", {})
    return got


def save(st):
    with state_lock:
        write(STATE, json.dumps(st, indent=1), private=True)


def one_time_code():
    """The code that opens the page before the owner's account exists, made on the first start."""
    if not os.path.exists(CODE_FILE):
        write(CODE_FILE, secrets.token_urlsafe(12), private=True)
    with open(CODE_FILE) as f:
        return f.read().strip()


def people():
    os.makedirs(os.path.dirname(PEOPLE), exist_ok=True)
    owned(os.path.dirname(PEOPLE))
    store = accounts.Store(PEOPLE)
    owned(PEOPLE)
    return store


SAFE = re.compile(r"[A-Za-z0-9_./:,@+=%-]*")


def env_value(v):
    """A value as .env holds it for Compose: plain when it can be, else quoted so Compose takes it as typed. Unquoted,
    Compose would put its own variables in for a $ (a build step's "$BUILD" would be gone) and end it at " #". Never
    more than a line."""
    v = str(v).replace("\r", "").replace("\n", "")
    if SAFE.fullmatch(v):
        return v
    if "'" not in v:
        return "'%s'" % v
    return '"%s"' % v.replace("\\", "\\\\").replace('"', '\\"').replace("$", "$$")


def env_unquote(v):
    if len(v) >= 2 and v[0] == v[-1] == "'":
        return v[1:-1]
    if len(v) >= 2 and v[0] == v[-1] == '"':
        return re.sub(r'\\(.)', r"\1", v[1:-1]).replace("$$", "$")
    return v


def read_env():
    out = {}
    if os.path.exists(ENV):
        with open(ENV, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    out[k.strip()] = env_unquote(v.strip())
    return out


def env_of(s, old):
    """.env for the settings s: the parts as Compose profiles, the rest as the services read them. The server's
    internal key (KAV_APP_KEY, its own services' signature) is made once and kept."""
    out = {k: v for k, v in old.items() if k not in MANAGED}
    out.setdefault("KAV_APP_KEY", secrets.token_urlsafe(18))
    profiles = []
    if s["planner"] == "kav":
        profiles.append("planner")
        if s["official"]:
            profiles.append("official")
            out["OFFICIAL_URL"] = "http://otp-official:8080/otp/gtfs/v1"
    if s["predictor"] in ("kav", "pack"):
        profiles.append("predictor")
    if s["alerts"] == "gtfsrt" and s["ALERTS_URL"]:
        out["ALERTS_SOURCE"], out["ALERTS_URL"] = "gtfsrt", s["ALERTS_URL"]
        if s["ALERTS_HEADERS"]:
            out["ALERTS_HEADERS"] = s["ALERTS_HEADERS"]
    if out.get("ALERTS_SOURCE") and s["english"]:
        profiles.append("translate")
        out["TRANSLATE_URL"] = "http://translate:5000"
    if s["address"] == "domain" and s["DOMAIN"]:
        profiles.append("https")
        out["DOMAIN"] = s["DOMAIN"]
    elif s["PUBLIC_URL"]:
        out["PUBLIC_URL"] = "https://" + s["PUBLIC_URL"]
    out["COMPOSE_PROFILES"] = ",".join(profiles)
    out["PLANNER"] = {"kav": "on", "own": "own", "off": "off"}[s["planner"]]
    out["ACCESS"] = s["ACCESS"]
    if s["live"] == "mot" and s["MOT_KEY"]:
        out["MOT_KEY"] = s["MOT_KEY"]
    elif s["live"] == "other" and s["LIVE_URL"]:
        out["LIVE_URL"] = s["LIVE_URL"]
        if s["MOT_KEY"]:
            out["MOT_KEY"] = s["MOT_KEY"]
    if s["planner"] == "own":
        for k in ("OTP_URL", "PLAN_URL"):
            if s[k]:
                out[k] = s[k]
    if s["predictor"] == "url" and s["PREDICT_URL"]:
        out["PREDICT_URL"] = s["PREDICT_URL"]
    if s["predictor"] == "off":
        out["ETA"] = ""  # nothing predicted read: the ministry's times
    if s["predictor"] not in ("kav", "pack"):
        out["MODELS_URL"] = ""  # Kav's predictor isn't run: its model isn't fetched
    if s["predictor"] == "pack" and s["pack"].get("url"):
        out["MODELS_URL"], out["MODELS_SHA256"] = s["pack"]["url"], s["pack"]["sha256"]
    for k in ("GTFS_URL", "OSM_URL"):
        if s[k]:
            out[k] = s[k]
    if s["area"]:
        out["BOUNDS"] = s["area"]
        if s["planner"] == "kav":
            out["BUILD_MEMORY"], out["PLANNER_MEMORY"] = "4", "2"
    for name, command in s["stages"].items():
        if command:
            out["STAGE_" + name.upper()] = command
    return out


def hand_edits(s):
    """The .env lines this page writes that differ from what it would write now: changed by hand since, which saving the
    parts here would replace."""
    now = read_env()
    mine = env_of(s, now)
    return sorted(k for k in MANAGED if now.get(k, "") != mine.get(k, ""))


def put_env(out):
    write(ENV, "# Written by the setup page (setup/server.py). What each line does: .env.example\n" +
          "".join("%s=%s\n" % (k, env_value(v)) for k, v in out.items()), private=True)


def write_env(s):
    out = env_of(s, read_env())
    put_env(out)
    return out


def needs(s):
    """GB of memory the chosen parts take while running, and in all at night, when the build runs beside them (the
    planner keeps answering from the old graph while the new one is built). Measured 7 Nov 2026."""
    running, build = 1.0, 1.0  # kav-live; the timetable alone builds in well under a GB
    if s["planner"] == "kav":
        planner = 2 if s["area"] else 5
        running += planner * (2 if s["official"] else 1)
        build = 4 if s["area"] else 16
    if s["predictor"] in ("kav", "pack"):
        running += 2.5
    if s["alerts"] != "off" and s["english"]:
        running += 0.5  # kav-translate, 0.3 GB measured
    return running, running + build


def compose(*args, timeout=900):
    # PWD as a shell would set it: the setup service's own mounts (docker-compose.yml) are by it.
    try:
        run = subprocess.run(["docker", "compose", *args], cwd=DIR, capture_output=True, text=True, timeout=timeout,
                             env=dict(os.environ, PWD=DIR))
    except (OSError, subprocess.TimeoutExpired) as e:  # said on the dashboard, as any failure of Docker's is
        return 1, str(e)
    return run.returncode, (run.stdout + run.stderr).strip()


def up_others():
    """Everything chosen up to date, except this page's own service, which nothing here changes but an update. A
    container is made again only when its settings or its image changed (an update's), so a nightly build half way
    through goes on. Images not here yet are pulled."""
    rc, out = compose("config", "--services", timeout=60)
    if rc:
        return rc, out
    chosen = [n for n in out.split() if n != "setup"]
    # A part turned off since (its profile no longer in COMPOSE_PROFILES) keeps running through `up`: removed here.
    _, there = compose("ps", "--all", "--services", timeout=60)
    gone = [n for n in there.split() if n not in chosen and n != "setup"]
    if gone:
        compose("rm", "--stop", "--force", *gone, timeout=300)
    return compose("up", "-d", "--remove-orphans", *chosen)


def kavs(images):
    """Kav's own images among them: not Caddy's or others another project on this machine may use."""
    return [i for i in images if "/kav-" in i]


def drop_images(images):
    """Kav's images of a release this no longer runs; one still in use stays."""
    if kavs(images):
        try:
            subprocess.run(["docker", "image", "rm", *kavs(images)], capture_output=True, timeout=120)
        except (OSError, subprocess.TimeoutExpired):
            pass


def up_self(old_images=()):
    """This page's own service on the new release's image after an update, by a one-off copy of it: `up` from in here
    would stop this very container half way, before making its replacement (and Compose's own versions differ in what
    they count as a change, so it can't be left to tell). Then the release before's images, none of them in use any
    more, are removed."""
    gone = " ".join(shlex.quote(i) for i in kavs(old_images))
    return compose("run", "-d", "--rm", "--no-deps", "setup", "sh", "-c",
                   'sleep 3; cd "$HOST_DIR" && PWD="$HOST_DIR" docker compose up -d --force-recreate setup' +
                   (" && docker image rm %s" % gone if gone else ""), timeout=120)


def stop_self():
    """Lock setup: this page's own container stopped, by its id (its hostname). Not through Compose: the host's and this
    image's Compose versions don't always agree which container is the service. Stopped by hand, it stays stopped."""
    try:
        subprocess.run(["docker", "stop", socket.gethostname()], capture_output=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        pass


def services():
    """Each part's state as Compose has it: [(service, state, status)], the setup page's own left out."""
    rc, out = compose("ps", "--all", "--format", "json", timeout=30)
    rows = []
    for line in out.splitlines():
        try:
            got = json.loads(line)
        except ValueError:
            continue
        for s in got if isinstance(got, list) else [got]:
            if s.get("Service") not in (None, "setup", "init"):
                rows.append((s["Service"], s.get("State", ""), s.get("Status", "")))
    return sorted(rows)


def start(st):
    """Writes .env and starts everything chosen, in the background; the dashboard shows how it goes."""
    write_env(st["settings"])
    st["start"] = {"running": True, "at": int(time.time()), "log": ""}
    save(st)

    def go():
        rc, out = up_others()
        now = load()
        now["start"] = {"running": False, "at": int(time.time()), "ok": rc == 0, "log": out[-4000:]}
        save(now)
    threading.Thread(target=go, daemon=True).start()


def server_status():
    """kav-live's own account of its parts (its status, as the app asks it)."""
    try:
        with urllib.request.urlopen("http://live:8080/live/v1/status", timeout=4) as r:
            return json.load(r)
    except (OSError, ValueError):
        return None


def address(s):
    return s["DOMAIN"] if s["address"] == "domain" else s["PUBLIC_URL"]


def reachable(s):
    """Whether phones reach the server where it says it is: its status over HTTPS at its address, as the app asks it."""
    at = address(s)
    if not at:
        return None
    try:
        with urllib.request.urlopen("https://%s/live/v1/status" % at, timeout=8) as r:
            return json.load(r).get("access") is not None
    except (OSError, ValueError):
        return False


def last_build():
    out = {}
    for name, key in (("last-build.json", "last"), ("last-build.json.next", "next")):
        try:
            with open(os.path.join(DATA, "otp", name)) as f:
                text = f.read()
            out[key] = json.loads(text) if key == "last" else text.strip()
        except (OSError, ValueError):
            pass
    return out


def rebuild_now():
    write(os.path.join(DATA, "otp", "rebuild-now"), str(int(time.time())))


def machine():
    """This machine, for the wizard's first step: memory, processors, free disk, Docker."""
    mem = 0
    try:
        with open("/proc/meminfo") as f:
            mem = int(f.readline().split()[1]) / 1048576
    except (OSError, ValueError, IndexError):
        pass
    free = shutil.disk_usage(DIR).free / 1e9
    try:
        docker = subprocess.run(["docker", "version", "--format", "{{.Server.Version}}"], capture_output=True, text=True,
                                timeout=10).stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        docker = ""
    return {"memory": round(mem, 1), "cpus": os.cpu_count() or 0, "disk": round(free, 1), "docker": docker}


def test_live(s):
    """Asks the chosen live source once, as kav-live would: (works, what it said)."""
    if s["live"] == "none":
        return True, ""
    url = s["LIVE_URL"] if s["live"] == "other" else MOT_URL
    if not url:
        return False, "no address"
    key = "Key=%s&" % urllib.parse.quote(s["MOT_KEY"]) if s["MOT_KEY"] else ""
    try:
        q = url + ("&" if "?" in url else "?") + key + "MonitoringRef=AllActiveTripsFilter&StopVisitDetailLevel=normal"
        with urllib.request.urlopen(urllib.request.Request(q, headers={"User-Agent": "kav-setup"}), timeout=60) as r:
            doc = json.load(r)
    except urllib.error.HTTPError as e:
        return False, "HTTP %d" % e.code
    except (OSError, ValueError) as e:
        return False, str(e)[:200]
    problems = conformance.siri_problems(doc)
    if problems:
        return False, problems[0]
    parts = doc["Siri"]["ServiceDelivery"]["StopMonitoringDelivery"]
    parts = [parts] if isinstance(parts, dict) else parts
    return True, "%d vehicles" % sum(len(p.get("MonitoredStopVisit") or []) for p in parts)


def check_pack(url):
    """A model pack at url, fetched and read: {url, sha256, name, version, inputs}. Raises ValueError, in words, for one
    Kav's predictor couldn't run."""
    if not url.startswith("https://"):
        raise ValueError("a model pack's address starts with https://")
    digest, size = hashlib.sha256(), 0
    with tempfile.TemporaryFile() as f:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "kav-setup"}), timeout=120) as r:
            while chunk := r.read(1 << 20):
                size += len(chunk)
                if size > PACK_MOST:
                    raise ValueError("it's larger than %d MB" % (PACK_MOST >> 20))
                digest.update(chunk)
                f.write(chunk)
        f.seek(0)
        try:
            with tarfile.open(fileobj=f) as t:
                m = json.load(t.extractfile("manifest.json"))
        except (tarfile.TarError, KeyError, ValueError, TypeError):
            raise ValueError("it isn't a model pack: no manifest.json in a .tar.gz")
    if not isinstance(m, dict):
        raise ValueError("the model pack's manifest.json must be an object")
    if m.get("pack") != 1:
        raise ValueError("model pack format %r; Kav's predictor reads 1" % m.get("pack"))
    if m.get("inputs") not in ("live3", "live5"):
        raise ValueError("it's for inputs %r, which Kav's predictor doesn't make" % m.get("inputs"))
    return {"url": url, "sha256": digest.hexdigest(), "name": str(m.get("name", "")), "version": str(m.get("version", "")),
            "inputs": m["inputs"]}


# Updates: tagged releases from GitHub, a button (or automatic, when turned on), rolled back if not healthy. A release
# is its docker-compose.yml (and the files beside it) and its images in GitHub's registry, tagged as the release is.

def version():
    """The release this copy runs: KAV_VERSION in .env, as install.sh and updates set it; "" for one whose images were
    built from the repository (docker-compose.dev.yml), which gets no updates."""
    return read_env().get("KAV_VERSION", "")


def repo():
    return read_env().get("KAV_REPO") or REPO


def newer(tag, than):
    """Whether release `tag` is past the version `than` runs: by their numbers, never a step back."""
    def number(v):
        m = re.match(r"v(\d+(?:\.\d+)*)", v or "")  # Kav's tags; a bare commit id is no version
        return tuple(int(x) for x in m.group(1).split(".")) if m else None
    a, b = number(tag), number(than)
    return a is not None and b is not None and a > b


def latest():
    try:
        req = urllib.request.Request("https://api.github.com/repos/%s/releases/latest" % repo(),
                                     headers={"User-Agent": "kav-setup", "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(req, timeout=15) as r:
            got = json.load(r)
        return {"tag": got["tag_name"], "name": got.get("name") or got["tag_name"], "notes": (got.get("body") or "")[:4000],
                "url": got.get("html_url", "")}
    except (OSError, ValueError, KeyError):
        return None


def release_file(tag, name):
    url = "https://raw.githubusercontent.com/%s/%s/server/selfhost/%s" % (repo(), urllib.parse.quote(tag), name)
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "kav-setup"}), timeout=60) as r:
        return r.read().decode("utf-8")


def update(tag):
    """To the release `tag`: its files, its images, everything started again; back to the release before, its files and
    its images, if the server doesn't come up healthy within HEALTHY_WITHIN."""
    st = load()
    before = version()
    st["update"] = {"running": True, "to": tag, "from": before, "at": int(time.time()), "log": ""}
    save(st)
    log = []

    def step(rc_out):
        log.append(rc_out[1])
        return rc_out[0] == 0

    def put(files, v):
        for name, text in files.items():
            write(os.path.join(DIR, name), text)
        put_env(dict(read_env(), KAV_VERSION=v))

    old = {}
    for name in RELEASE_FILES:
        try:
            with open(os.path.join(DIR, name), encoding="utf-8") as f:
                old[name] = f.read()
        except OSError:
            pass
    old_images = compose("config", "--images", timeout=60)[1].split()
    new = {}
    try:
        for name in RELEASE_FILES:
            new[name] = release_file(tag, name)
    except (OSError, UnicodeDecodeError) as e:
        log.append("%s: %s" % (name, e))
    # Every image pulled before anything is stopped. Not all of it downloaded: nothing has changed, nothing is started
    # again, and what did come is removed.
    got = len(new) == len(RELEASE_FILES)
    if got:
        put(new, tag)
        new_images = [i for i in compose("config", "--images", timeout=60)[1].split() if i not in old_images]
        got = step(compose("pull", "--quiet"))
        if not got:
            put(old, before)
            drop_images(new_images)
    if not got:
        st = load()
        st["update"] = {"running": False, "to": tag, "from": before, "at": int(time.time()), "ok": False, "got": False,
                        "log": ("Couldn't get %s:\n" % tag + "\n".join(log))[-4000:]}
        save(st)
        return
    ok = step(up_others())
    healthy = False
    if ok:
        until = time.time() + HEALTHY_WITHIN
        while time.time() < until and not healthy:
            time.sleep(10)
            healthy = server_status() is not None
    if not healthy:
        log.append("not healthy: back to %s" % before)
        put(old, before)
        step(up_others())
        drop_images(new_images)
    st = load()
    st["update"] = {"running": False, "to": tag, "from": before, "at": int(time.time()), "ok": healthy,
                    "log": "\n".join(log)[-4000:]}
    save(st)
    if healthy:
        up_self([i for i in old_images if i not in compose("config", "--images", timeout=60)[1].split()])


def keep_updated():
    """With automatic updates on, a new release is taken once a day."""
    while True:
        time.sleep(24 * 3600)
        if load()["auto_update"]:
            new = latest()
            if new and newer(new["tag"], version()):
                update(new["tag"])


def sizes():
    """What data/ holds, by part, in MB."""
    out = {}
    for name in sorted(os.listdir(DATA)) if os.path.isdir(DATA) else []:
        path, total = os.path.join(DATA, name), 0
        for root, _, files in os.walk(path):
            for f in files:
                try:
                    total += os.path.getsize(os.path.join(root, f))
                except OSError:
                    pass
        if os.path.isdir(path):
            out[name] = round(total / 1e6)
    return out


def history_export(out):
    """The recorded passings (data/history's day files) written to `out` as a tar, as they're read: ten weeks of them
    are gigabytes, and the days are gzip already. For training a model. Not the accounts or the trip links beside them."""
    folder = os.path.join(DATA, "history")
    with tarfile.open(fileobj=out, mode="w|") as t:
        for name in sorted(os.listdir(folder)) if os.path.isdir(folder) else []:
            if name.endswith((".tsv", ".tsv.gz")):
                t.add(os.path.join(folder, name), arcname="history/" + name)


# Sessions: the owner's and other admins' sign-in to this page.

sessions = {}  # id: {"name", "until", "csrf"}


def new_session(name):
    sid = secrets.token_urlsafe(24)
    sessions[sid] = {"name": name, "until": time.time() + SESSION_S, "csrf": secrets.token_urlsafe(16)}
    return sid


def session_of(cookie_header):
    for part in (cookie_header or "").split(";"):
        k, _, v = part.strip().partition("=")
        if k == "kav_setup" and v in sessions and sessions[v]["until"] > time.time():
            return v, sessions[v]
    return None, None


if __name__ == "__main__":
    import pages
    pages.S = sys.modules[__name__]  # the pages work through this module, one copy of its sessions and state
    one_time_code()
    st = load()  # a start or an update this service was in the middle of when it stopped won't finish now
    for job in ("start", "update"):
        if st[job].get("running"):
            st[job].update(running=False, ok=False, log=(st[job].get("log") or "") + "\nInterrupted: the setup page restarted.")
    save(st)
    threading.Thread(target=keep_updated, daemon=True).start()
    print("setup page on :8090", flush=True)
    http.server.ThreadingHTTPServer(("0.0.0.0", 8090), pages.Handler).serve_forever()
