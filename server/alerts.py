"""Line alerts for Kav (/live/v1/alerts/<lang>, server/API.md): Israel's whole list of service alerts with every notice
in it, from one source (ALERTS_SOURCE):

- "gtfsrt": a GTFS-Realtime ServiceAlerts feed at ALERTS_URL (the Ministry of Transport's, which it gives on request to
  ptsupport@mot.gov.il, or any other), asked every five minutes and never more than 12 times an hour, as the ministry's
  ICD asks. A route's cancelled stops and its "oar" extension commands become the notice's detour.
- "off": none, and /live/v1/status says so.

The phone downloads the list and finds its own lines in it, so the server never learns anyone's trip. English comes from
a translator answering LibreTranslate's API (TRANSLATE_URL, translate.py's by default): each text is translated once and
kept (TRANSLATIONS), and a line whose numbers would change (line numbers, stop codes, times) keeps its Hebrew. Kav's own words name the kinds of alert. Python's own library
only, like live.py."""

import collections
import gzip
import hashlib
import html
import io
import itertools
import json
import os
import re
import sqlite3
import ssl
import threading
import time
import urllib.request

SOURCE = os.environ.get("ALERTS_SOURCE", "off").lower()
URL = os.environ.get("ALERTS_URL", "")
# Headers the feed wants, as a JSON object: {"Ocp-Apim-Subscription-Key": "..."}.
HEADERS = json.loads(os.environ.get("ALERTS_HEADERS", "") or "{}")
TRANSLATE_URL = os.environ.get("TRANSLATE_URL", "").rstrip("/")
TRANSLATE_KEY = os.environ.get("TRANSLATE_KEY", "")
TRANSLATIONS = os.environ.get("TRANSLATIONS", os.path.join(os.environ.get("HISTORY") or ".", "translations.sqlite3"))
LINES_AT_ONCE = 32  # lines in one request to the translator (translate.py takes 64)

# The ministry's server leaves an intermediate certificate out of its chain; MOT_CA holds it, as for live.py.
TLS = ssl.create_default_context()
if os.environ.get("MOT_CA"):
    TLS.load_verify_locations(os.environ["MOT_CA"])

POLL_EVERY = 300  # the ministry makes a new package every five minutes
MOST_AN_HOUR = 12  # and asks to be asked no more than this

# GTFS-Realtime effects, as Kav names them, and how bad: 4 a disruption, 3 a change of service.
KINDS = {
    1: ("no_service", 4), 2: ("reduced_service", 4), 3: ("delays", 4), 4: ("detour", 3), 5: ("extra_service", 3),
    6: ("service_change", 3), 9: ("stop_moved", 3), 11: ("accessibility", 3),
}
WORDS = {
    "no_service": ("No service", "אין שירות"),
    "reduced_service": ("Reduced service", "שירות מצומצם"),
    "delays": ("Delays", "עיכובים"),
    "detour": ("Detour", "שינוי מסלול"),
    "extra_service": ("Extra service", "תגבור"),
    "service_change": ("Service change", "שינוי בשירות"),
    "stop_moved": ("Stop moved", "תחנה הועתקה"),
    "accessibility": ("Accessibility", "נגישות"),
    "alert": ("Service alert", "הודעת שירות"),
}


def log(*parts):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), "alerts:", *parts, flush=True)


# The protobuf wire format, the few parts of it a feed of alerts uses.

def varint(data, i):
    shift = result = 0
    while True:
        b = data[i]
        i += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, i
        shift += 7


def fields(data):
    """(field, value) of one message: an int for a varint, bytes for anything length-delimited."""
    i = 0
    while i < len(data):
        key, i = varint(data, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = varint(data, i)
        elif wire == 2:
            n, i = varint(data, i)
            value, i = data[i:i + n], i + n
        elif wire == 1:
            value, i = data[i:i + 8], i + 8
        elif wire == 5:
            value, i = data[i:i + 4], i + 4
        else:
            raise ValueError("unsupported protobuf wire type %d" % wire)
        yield number, value


# The ministry writes some characters in its texts as JSON escapes, a bullet as backslash-u-2022.
ESCAPED = re.compile(r"\\u([0-9a-fA-F]{4})")


def translated(data):
    """A TranslatedString: {language: text}, "" for one without a language."""
    out = {}
    for number, t in fields(data):
        if number == 1:
            text = language = ""
            for n, v in fields(t):
                if n == 1:
                    text = ESCAPED.sub(lambda m: chr(int(m.group(1), 16)), v.decode("utf-8"))
                elif n == 2:
                    language = v.decode("utf-8")
            out[language] = text
    return out


def parse_feed(data):
    """A FeedMessage's alerts: id, active periods, informed entities (agency, route, stop), effect, and the url, header
    and description by language ("oar" holds the ministry's extension commands)."""
    found = []
    for number, entity in fields(data):
        if number != 2:
            continue
        alert = {"id": "", "periods": [], "entities": [], "effect": 0, "url": {}, "title": {}, "body": {}, "deleted": False}
        has_alert = False
        for n, v in fields(entity):
            if n == 1:
                alert["id"] = v.decode("utf-8")
            elif n == 2:
                alert["deleted"] = bool(v)
            elif n == 5:
                has_alert = True
                for a, w in fields(v):
                    if a == 1:
                        period = dict(fields(w))
                        alert["periods"].append((period.get(1, 0), period.get(2, 0)))
                    elif a == 5:
                        e = {k: x.decode("utf-8") for k, x in fields(w) if k in (1, 2, 5) and isinstance(x, bytes)}
                        alert["entities"].append((e.get(1, ""), e.get(2, ""), e.get(5, "")))
                    elif a == 7:
                        alert["effect"] = w
                    elif a == 8:
                        alert["url"] = translated(w)
                    elif a == 10:
                        alert["title"] = translated(w)
                    elif a == 11:
                        alert["body"] = translated(w)
        if has_alert and not alert["deleted"] and alert["id"]:
            found.append(alert)
    return found


def oar_commands(text):
    """The ministry's extension commands ("route_id=4546,add_stop_id=5757,after_stop_id=34556;"), each a dict."""
    out = []
    for command in (text or "").split(";"):
        pairs = [p.split("=", 1) for p in command.strip().split(",") if "=" in p]
        if pairs:
            out.append({k.strip(): v.strip() for k, v in pairs})
    return out


def plain(text):
    """An HTML notice as text: its breaks and paragraphs as new lines."""
    text = re.sub(r"(?i)<\s*br\s*/?>|</\s*(p|div|li)\s*>", "\n", text or "")
    return re.sub(r"\n{3,}", "\n\n", html.unescape(re.sub(r"<[^>]+>", "", text))).strip()


def from_feed(alerts, route_name, stop_code, now):
    """The board of a GTFS-Realtime feed: alerts on routes, found by the ministry's name for each way, each notice once.
    An alert for a whole operator, a region or stops alone names no line, and isn't listed. Over ones are left out; ones
    still to come stay."""
    lines, notices = {}, {}
    for a in alerts:
        if a["periods"] and all(0 < end < now for _, end in a["periods"]):
            continue
        kind, category = KINDS.get(a["effect"], ("alert", 3))
        skipped, added, named = [], [], False
        for _agency, route, stop in a["entities"]:
            if route:
                name = route_name(route)
                if name:
                    named = True
                    held = lines.setdefault(name, {"category": 0, "kind": kind, "ids": []})
                    if category > held["category"]:
                        held["category"], held["kind"] = category, kind
                    if a["id"] not in held["ids"]:
                        held["ids"].append(a["id"])
                if stop:
                    code = stop_code(stop)
                    if code and code not in skipped:
                        skipped.append(code)
        if not named:
            continue
        for command in oar_commands(a["body"].get("oar", "")):
            code = stop_code(command.get("add_stop_id", ""))
            if code and code not in added:
                added.append(code)
        starts = [s for s, _ in a["periods"] if s]
        ends = [e for _, e in a["periods"]]
        notices[a["id"]] = {
            "category": category, "kind": kind,
            "title": a["title"].get("he") or a["title"].get("", ""), "body": a["body"].get("he") or a["body"].get("", ""),
            "title_en": a["title"].get("en", ""), "body_en": a["body"].get("en", ""), "html": False,
            "from": min(starts) if starts else 0, "to": 0 if not ends or 0 in ends else max(ends),
            "url": a["url"].get("he") or a["url"].get("en") or a["url"].get("", ""),
            "detour": {"skipped": skipped, "added": added} if skipped or added else None,
        }
    return {"lines": lines, "notices": notices}


# The ministry's English names (its GTFS translations.txt: stops "יוחנן הסנדלר/בלפור" as "Yohanan HaSandlar/Balfour",
# towns), and each street of a corner on its own, put in before a line is translated: the model reads "יוחנן הסנדלר" as
# "John the cobbler" and "קיבוץ גלויות" as "Kibbutz Postcards". A name is put in only where it can only be a name, as
# many are also words ("השלום", the peace): a whole stop after its code, both sides of a corner, the words after a
# street, junction or way to go, and a town before a title's dash.
NAME_WORD = r"[א-ת\"'׳״\-]+"
NAME_MARK = re.compile(r"(?:^|(?<=\s))(?:ו?[בל]?(?:רח'|רח׳|רחוב|שד'|שד׳|שדרות|צומת|כיכר|ככר|מחלף|גשר|סמטת)|[למב]כיוון|פינת|ימינה|שמאלה)\s+")
NAME_CORNER = re.compile(r"((?:%s\s+){0,3}%s)\s*/\s*(%s(?:\s+%s){0,3})" % (NAME_WORD, NAME_WORD, NAME_WORD, NAME_WORD))
NOT_NAMES = {"צפון", "דרום", "מזרח", "מערב"}  # streets too, but "לכיוון צפון" is northwards


def names_from(text):
    """The ministry's translations.txt (its layout from before the standard: the Hebrew, the language, the
    translation) as {Hebrew: English}, with each part of a corner's name."""
    import csv
    full, parts = {}, collections.defaultdict(collections.Counter)
    for r in csv.DictReader(io.StringIO(text)):
        he, en = (r.get("trans_id") or "").strip().replace("''", '"'), (r.get("translation") or "").strip()
        if r.get("lang") != "EN" or not en or not re.search("[א-ת]", he) or re.search("[א-ת]", en):
            continue
        full[he] = en
        hs, es = [p.strip() for p in he.split("/")], [p.strip() for p in en.split("/")]
        if len(hs) == len(es) > 1:
            for h, e in zip(hs, es):
                if re.search("[א-ת]", h) and e:
                    parts[h][e] += 1
    for h, seen in parts.items():
        full.setdefault(h, seen.most_common(1)[0][0])
    return full


# Headings the operators write the same way every time, in Kav's own words rather than the model's: it read a stop
# list's table heading "מק"ט תחנה / שם תחנה" as "There's a station there."
PHRASES = {
    "מק\"ט תחנה": "Stop code", "מק\"ט": "Stop code", "שם תחנה": "Stop name", "שם התחנה": "Stop name",
    "תחנות מבוטלות": "Cancelled stops", "תחנה מבוטלת": "Cancelled stop",
    "תחנה חלופית": "Alternative stop", "תחנות חלופיות": "Alternative stops", "תחנה זמנית חלופית": "Temporary stop",
    "תחנה זמנית": "Temporary stop", "תחנת מוצא חלופית": "Alternative first stop", "תחנה מוצא חלופית": "Alternative first stop",
    "ללא שינוי": "No change", "לשני הכיוונים": "In both directions", "בשני הכיוונים": "In both directions",
}


def phrase(line):
    """A line that's only such headings (a colon after, or several in a table's columns), in Kav's words; else None."""
    cells = [c.strip() for c in re.split(r"\t+|\s{3,}", line.strip()) if c.strip()]
    if not cells:
        return None
    out = []
    for cell in cells:
        bare = cell.rstrip(":").strip().replace("''", '"').replace("״", '"')
        if bare not in PHRASES:
            return None
        out.append(PHRASES[bare] + (":" if cell.endswith(":") else ""))
    return "\t".join(out)


def with_names(line, names):
    """A line with the names it can only mean put in English."""
    fixed = phrase(line)
    if fixed is not None:
        return fixed
    if not names:
        return line

    def name(words, at_end=False):
        # The longest run of words that's a name, at the start (or the end) of words: (how many, English).
        for n in range(min(5, len(words)), 0, -1):
            run = words[-n:] if at_end else words[:n]
            cand = " ".join(run).rstrip(",.:;)")
            if cand in names and cand not in NOT_NAMES:
                return n, names[cand] + " ".join(run)[len(cand):]
        return 0, None
    stop = re.match(r"^(\s*\d{4,6}\s*-?\s*)(.+?)\s*$", line)
    if stop and stop.group(2) in names:
        return stop.group(1) + names[stop.group(2)]

    def corner(m):
        left, right = m.group(1).split(), m.group(2).split()
        nl, el = name(left, at_end=True)
        nr, er = name(right)
        if not el or not er:
            return m.group(0)
        return " ".join(left[:len(left) - nl] + [el + "/" + er] + right[nr:])
    line = NAME_CORNER.sub(corner, line)
    # After each marker, last to first, so a name put in leaves the ones before where they were.
    for m in reversed(list(NAME_MARK.finditer(line))):
        rest = line[m.end():]
        words = re.match(r"(?:\S+(?:\s+|$)){1,5}", rest)
        if not words:
            continue
        n, en = name(words.group(0).split())
        if en:
            taken = re.match(r"(?:\S+\s*){%d}" % n, rest).group(0).rstrip()
            line = line[:m.end()] + en + rest[len(taken):]
    town = re.match(r"^(.+?)(\s+-\s|,)", line)
    if town and town.group(1).strip().replace("-", " ") in names:
        line = names[town.group(1).strip().replace("-", " ")] + line[len(town.group(1)):]
    # "מכיוון" is "from" and "because": before a name in English the model took it as the second.
    return re.sub(r"(^|(?<=\s))מכיוון(?=\s+[A-Za-z])", lambda m: "From" if m.start() == 0 else "from", line)


class Translations:
    """Hebrew to English through the translator, each text once, kept in SQLite. A line whose numbers come back
    changed keeps its Hebrew: line numbers, stop codes and times are never the translator's guess."""

    def __init__(self, path, url, key="", ask=None, names=None):
        self.url, self.key, self.ask = url, key, ask or self.post
        self.names = names or {}
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS t (hash TEXT PRIMARY KEY, en TEXT NOT NULL)")
        self.lock = threading.Lock()
        self.asking = threading.Lock()  # one request to the translator at a time
        self.wanted = collections.OrderedDict()

    @staticmethod
    def key_of(text):
        # "6:": line letters kept even when attached to Hebrew words.
        return hashlib.sha256(("6:" + text).encode("utf-8")).hexdigest()

    def english(self, text):
        """The kept English, or None, after which it's translated in the background (work())."""
        if not text or not re.search("[֐-׿]", text):
            return text
        h = self.key_of(text)
        with self.lock:
            row = self.db.execute("SELECT en FROM t WHERE hash = ?", (h,)).fetchone()
            if row:
                return row[0]
            self.wanted[h] = text
        return None

    def post(self, texts):
        body = json.dumps({"q": texts, "source": "he", "target": "en", "format": "text", "api_key": self.key}).encode()
        request = urllib.request.Request(self.url + "/translate", body, {"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=120) as r:
            return json.load(r)["translatedText"]

    def translate(self, text):
        lines = text.split("\n")
        # A line number with a Hebrew letter ("17ב'") goes as a five-digit number not in the text, which the model keeps
        # as stop codes are kept, and comes back as it was: the model made "17ב'" 172, and a seven-digit one 90901.
        held = {}
        spare = (str(n) for n in itertools.count(61001) if str(n) not in text)

        def hold(m):
            key = next(spare)
            held[key] = m.group(0)
            return key
        # Dates and times go the same way: the model turned "8-13/11/2026" into "8-13/2026", and a line whose numbers
        # change keeps its Hebrew.
        def held_line(line):
            # Not a date when a Hebrew letter follows: "60/61א" is two lines, and the letter must stay with its own.
            line = re.sub(r"(?<![\d/.:])\d{1,2}(?:-\d{1,2})?[./]\d{1,2}(?:[./](?:\d{4}|\d{2}))?(?![\d/:א-ת]|\.\d)", hold, line)
            line = re.sub(r"(?<![\d:])\d{1,2}:\d{2}(?![\d:])", hold, line)
            # Not a line when it's a number stuck to a short form ("5ק"מ", "5ש"ח"): held, that came back in Hebrew.
            return re.sub(r"(?<![\dA-Za-z])\d{1,3}[א-תA-Za-z]['׳]?(?![א-תA-Za-z]|[\"״][א-ת])", hold, line)
        named = {line: held_line(with_names(line, self.names)) for line in lines}
        asked = list(dict.fromkeys(named[line] for line in lines if re.search("[֐-׿]", named[line])))
        got = {}
        for start in range(0, len(asked), LINES_AT_ONCE):
            part = asked[start:start + LINES_AT_ONCE]
            got.update(zip(part, self.ask(part)))

        def kept(line):
            out = got.get(named[line], None if re.search("[֐-׿]", named[line]) else named[line])
            if out is None:
                return line
            for key, was in held.items():
                out = out.replace(key, was)
            if sorted(re.findall(r"\d+", out)) != sorted(re.findall(r"\d+", line)):
                return line
            return out
        return "\n".join(kept(line) for line in lines)

    def now(self, texts):
        """Each text in English at once, for a phone reading a notice from another source (/live/v1/translate):
        kept ones from the store, the rest translated and kept. Raises when the translator fails."""
        out = []
        for text in texts:
            en = self.english(text)
            if en is None:
                with self.asking:
                    en = self.translate(text)
                with self.lock:
                    self.db.execute("INSERT OR REPLACE INTO t VALUES (?, ?)", (self.key_of(text), en))
                    self.db.commit()
                    self.wanted.pop(self.key_of(text), None)
            out.append(en)
        return out

    def work(self, most=20):
        """Translates up to `most` waiting texts; how many of them have their English now."""
        with self.lock:
            batch = list(self.wanted.items())[:most]
        done = 0
        for h, text in batch:
            # Kept while it waited (a phone asked for it at once): not translated twice, and counted, so the list made
            # in English before it was kept is made again with it.
            with self.lock:
                if self.db.execute("SELECT 1 FROM t WHERE hash = ?", (h,)).fetchone():
                    self.wanted.pop(h, None)
                    done += 1
                    continue
            try:
                with self.asking:
                    en = self.translate(text)
            except Exception as e:
                # Set aside, so the texts behind it go on: it's asked for again with the list's next version.
                log("translation failing:", repr(e))
                with self.lock:
                    self.wanted.pop(h, None)
                continue
            with self.lock:
                self.db.execute("INSERT OR REPLACE INTO t VALUES (?, ?)", (h, en))
                self.db.commit()
                self.wanted.pop(h, None)
            done += 1
        return done


class Alerts:
    """The current board and the app's answer in each language, made again when the board or the translations change."""

    def __init__(self, source=SOURCE, translations=None):
        self.source, self.translations = source, translations
        self.board, self.at, self.version = None, 0, 0
        self.made = {}
        self.lock = threading.Lock()

    def take(self, board, at):
        with self.lock:
            self.board, self.at, self.version = board, at, self.version + 1
            self.made = {}

    def status(self):
        """Where the alerts come from, once a list is in; None while there's none."""
        return self.source if self.board is not None else None

    def answer(self, lang):
        with self.lock:
            board, at, version = self.board, self.at, self.version
            made = self.made.get(lang)
        if board is None:
            return None
        if made and made[0] == version:
            return made[1]["doc"]

        # Hebrew until its English is in: keep_translating() has the answer made again then.
        def english(he, given):
            if given or self.translations is None:
                return given or he
            en = self.translations.english(he)
            return he if en is None else en

        def label(entry):
            if "label" in entry:
                en, he = entry["label"]
                return (en or he) if lang == "en" else (he or en)
            en, he = WORDS.get(entry["kind"], WORDS["alert"])
            return en if lang == "en" else he

        notices = {}
        for id_, n in board["notices"].items():
            title, body, html_ = n["title"], n["body"], n["html"]
            if lang == "en":
                if html_ and not n["body_en"]:
                    body, html_ = plain(body), False
                title, body = english(title, n["title_en"]), english(body, n["body_en"])
            notice = {"category": n["category"], "label": label(n), "title": title, "body": body, "html": html_,
                      "from": n["from"], "to": n["to"], "url": n["url"]}
            if n["detour"]:
                notice["detour"] = n["detour"]
            notices[id_] = notice
        # Only alerts whose notice is in: a phone can't open the rest. A line whose alerts all wait still shows what it
        # has, without opening.
        out = {
            "at": at, "lang": lang, "source": self.source,
            "lines": [{"name": name, "category": e["category"], "label": label(e), "ids": [i for i in e["ids"] if i in notices]}
                      for name, e in board["lines"].items()],
            "notices": notices,
        }
        with self.lock:
            if self.version == version:
                self.made[lang] = (version, {"doc": out})
        return out

    def packed(self, lang):
        """The answer as JSON, gzipped once a version: the whole list with its notices is most of a megabyte."""
        doc = self.answer(lang)
        if doc is None:
            return None
        with self.lock:
            made = self.made.get(lang)
            if made and made[1]["doc"] is doc and "gz" in made[1]:
                return made[1]["gz"]
        gz = gzip.compress(json.dumps(doc, separators=(",", ":")).encode(), 6)
        with self.lock:
            made = self.made.get(lang)
            if made and made[1]["doc"] is doc:
                made[1]["gz"] = gz
        return gz


class Polls:
    """When a feed may be asked: every POLL_EVERY seconds, and never more than MOST_AN_HOUR times in an hour."""

    def __init__(self, every=POLL_EVERY, most=MOST_AN_HOUR):
        self.every, self.most, self.asked = every, most, collections.deque()

    def wait(self, now):
        """Seconds until the next ask may go out (0: now)."""
        while self.asked and now - self.asked[0] >= 3600:
            self.asked.popleft()
        due = self.asked[-1] + self.every if self.asked else now
        if len(self.asked) >= self.most:
            due = max(due, self.asked[0] + 3600)
        return max(0, due - now)

    def ask(self, now):
        self.asked.append(now)


current = Alerts()


def fetch_feed(url=URL, headers=HEADERS):
    request = urllib.request.Request(url, headers={"User-Agent": "kav-live", **headers})
    with urllib.request.urlopen(request, timeout=60, context=TLS) as r:
        return r.read()


def keep_feed(route_name, stop_code, polls=None, fetch=fetch_feed, sleep=time.sleep):
    polls = polls or Polls()
    while True:
        wait = polls.wait(time.time())
        if wait:
            sleep(wait)
            continue
        polls.ask(time.time())
        try:
            found = parse_feed(fetch())
            board = from_feed(found, route_name, stop_code, time.time())
            # Just started, live.py's lines and stop codes come in a moment after the first package: the same package
            # is read again until they do, for up to five minutes, without asking the ministry again.
            for _ in range(10):
                if board["lines"] or not any(route for a in found for _, route, _ in a["entities"]):
                    break
                sleep(30)
                board = from_feed(found, route_name, stop_code, time.time())
            current.take(board, int(time.time()))
            log("feed:", len(board["notices"]), "alerts on", len(board["lines"]), "ways")
        except Exception as e:
            log("feed failing:", repr(e))


def keep_translating(translations):
    while True:
        try:
            if translations.work():
                with current.lock:
                    current.made.pop("en", None)
                continue
        except Exception as e:
            log("translation failing:", repr(e))
        time.sleep(30)


def start(route_name, stop_code):
    """Runs the chosen source in the background. route_name(route_id) and stop_code(stop_id) come from the timetable
    live.py keeps."""
    if SOURCE == "off":
        return
    if TRANSLATE_URL:
        current.translations = Translations(TRANSLATIONS, TRANSLATE_URL, TRANSLATE_KEY)
        threading.Thread(target=keep_translating, args=(current.translations,), daemon=True).start()
    if SOURCE == "gtfsrt":
        if not URL:
            log("ALERTS_SOURCE=gtfsrt needs ALERTS_URL: no alerts")
            return
        threading.Thread(target=keep_feed, args=(route_name, stop_code), daemon=True).start()
    else:
        log("unknown ALERTS_SOURCE", repr(SOURCE), "(gtfsrt or off): no alerts")
