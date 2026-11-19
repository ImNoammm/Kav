# Kav's server API

Everything the app asks a Kav server, version 1. Kav's own server is `server/live.py`, and a copy of it runs as
described in [server/selfhost](selfhost/README.md). A server written another way works with the app as long as it
answers these. A change that breaks them goes to `/live/v2/`.

The parts you can swap without writing a whole server have guides of their own:
[a planner](PLANNER.md), [a predictor](PREDICTOR.md) and [a live source](LIVE.md).

## Who may use a server

`GET /live/v1/status` answers anyone on a copy, and says how to sign in, as `"access"`:

- **`key`** (Kav's own server): every request is signed with the key the app is built with.
- **`accounts`** (a copy's default): a person signs each phone in once, and the phone signs every request with a token
  of its own.
- **`open`**: no signing at all. Each address may make 120 requests a minute, and gets 429 past that. The address is
  the one the connection comes from, or, when that's a private-network address (a proxy on the server's own network,
  such as the self-hosted `https` service), the last address in its `X-Forwarded-For` header.

### Signing

A signed request has the header

```
X-Kav: <slot>.<mac>
```

- `slot` is the current ten-minute window, `floor(unix seconds / 600)`.
- `mac` is the first 32 hex characters of `HMAC-SHA256(key, "<slot>:<path>")`, where `<path>` is the URL-decoded path
  without the query, starting with `/`.
- `live.py` takes a slot up to three hours off, for phones whose clocks are wrong.

The key is the app's own on Kav's server, and a phone's token on a server with accounts. A refused request gets 404, as
if the address weren't there.

### Signing a phone in

```
POST /live/v1/auth/login
{"username": "...", "password": "...", "device": "<phone model>"}
or {"invite": "<code from an invite QR>", "device": "<phone model>"}

→ 200 {"device": "<id>", "token": "<secret>", "user": "<username>", "admin": false}
→ 401 {"error": "<why, in words for the person>"}
→ 429 {"error": "..."} while the username is paused (five wrong passwords in a row pause it for 15 minutes)
```

From then on, the phone signs each request with its **token** as the key, and says which phone it is:

```
X-Kav-Device: <id>
```

The token never travels again. `POST /live/v1/auth/logout`, signed, revokes it. When a signed request is refused, the
phone has been signed out (by the person, or by an admin on the setup page), and the app asks to sign in again.

An invite works once, for 7 days. The setup page shows it as a QR code:

```
kav://server?address=<host>&invite=<code>
```

On an open server the QR carries only the address.

## Addresses

All under `/live/v1/`, all `GET` unless said, all JSON.

| Path | What it answers |
|---|---|
| `status` | What the server is set up for and whether each part works |
| `bus/<plate>` | What kind of bus that is, by the plate at the end of its vehicle id |
| `area/<south>,<west>,<north>,<east>` | Every vehicle in that box, for the Live map |
| `stops/<code>,<code>,...` | Arrivals at those stops, for stop boards |
| `follow/<route>,<route>,...` | The vehicles on those routes, for a trip under way |
| `line/<number>` | The vehicles on every route with that line number |
| `shape/<route>` | A route's road |
| `late/<route>,<route>,...` | How late those routes usually run against the timetable, up to 16 at once |
| `plan/...` | Trip routes: see [Plan](#plan) |
| `busy/<route>,<route>,...` | How busy those routes usually are at each hour of the week, from Rav-Kav taps, up to 16 at once |
| `bundle/manifest.json`, `bundle/manifest.json.sig`, `bundle/il.kav.gz` | The week's timetable the app downloads |
| `alerts/he`, `alerts/en` | Israel's line alerts with every notice, in Hebrew or English: see [alerts](#alerts) |
| `translate` (`POST`) | Notices the phone got from Google, in English: see [translate](#translate) |
| `auth/login`, `auth/logout` (`POST`) | Signing a phone in and out, on a server with accounts |

Route ids and stop codes are the Ministry of Transport's own: GTFS `route_id` and `stop_code`, both numbers.

A request whose headers and body have not all arrived within 30 seconds of its first byte is cut off.

### status

```json
{"ok": true, "at": 1793900000, "vehicles": 4543, "source": "main",
 "planner": true, "predictions": true, "timetable": "2026-11-07", "bundleKey": "<base64>",
 "api": 1, "capabilities": {"live": true, "planner": "otp", "predictions": true, "links": true},
 "access": "accounts", "alerts": "gtfsrt"}
```

- `ok`: the live snapshot is fresh. `at`: when it was taken. `vehicles`: how many it has. `source`: `main` or `backup`.
- `planner`: whether the planner answers with a timetable that hasn't run out. Left out when the server has none.
- `predictions`: whether Kav's own arrival times (or yours) are fresh and say nothing is wrong.
- `timetable`: the first day of the week's timetable it hands out.
- `bundleKey`: the public half of the key that timetable is signed with, on a copy, for the app to pin.
- `alerts`: where its [line alerts](#alerts) come from, `gtfsrt`, once it has a list. Left out when it has none.
- `capabilities`: what the server is set up for, whether or not it works right now. `planner` is `"otp"`, `"own"` (a
  planner at `PLAN_URL`) or `null`. The app hides what a server isn't set up for.

### area, follow, line

```json
{"at": 1793900000, "next": 1793900015, "v": [[...], ...], "lines": {"10966": [...]}}
```

- `next`: when to ask again, unix seconds.
- Each vehicle in `v`: `[journey, lat, lon, reported, bearing, speed]`. `journey` is
  `<route>_<first departure>_<vehicle>` as the ministry names them, `reported` is unix seconds, `bearing` is degrees
  (or null), `speed` is km/h (or null). The route is the journey's first part.
- `lines`: the routes in it, as in [Plan](#plan).

`area` answers 400 for a box more than 0.4 degrees a side (a city or so). `area` and `line` answer 503 while there's no
fresh snapshot.

### stops

```json
{"at": 1793900000, "next": 1793900015, "a": [[...], ...], "lines": {...}}
```

Each arrival in `a` starts with these, in this order:

```
[stop, route, journey, line number, first departure, timetable time, expected time,
 lat, lon, reported, bearing, speed, place on the trip, platform]
```

Times are unix seconds, 0 when unknown. The position is null when the vehicle isn't reporting one. The platform is a
train's, once the operator has given it. More fields can follow these; a client reads the ones it knows.

### bus

`{"made": "2026-11-14", "bus": {"size": "articulated", "seats": 49, "year": 2020, "power": "diesel"}}`

From the Ministry of Transport's daily file of the operators' buses (data.gov.il, dataset `bus_fleet`), checked each
morning. `size`: `bus`, `articulated`, `midibus` or `minibus`; `power`: `diesel`, `electric` or `gas`; a field the file
doesn't give isn't there. The plate is the last part of a vehicle id, digits only; it isn't said back. 404 when the bus
isn't in the file (`{"error": "not in the fleet file"}`), 503 before the first file and when it was last checked more
than 14 days ago (`{"error": "fleet out of date", "made": ...}`). The file says nothing of a bus's accessibility.

### late

```json
{"made": "2026-11-13", "from": "2026-10-23", "to": "2026-11-11", "days": 20, "early": 60, "late": 300,
 "routes": {"7020": {"morning": {"late": 465, "on": 329, "runs": 90, "days": 15}, "all": {...}}}}
```

Figured each morning from the server's own recorded passings against the ministry's timetable of each day. `made`: the
day they were figured; `from` and `to`: the first and last day they come from, `days` how many of those (a day is
judged once its passings and the next day's are complete, so `to` is two days back). By band of the week, by when a trip
sets out: Sunday to Thursday `morning` (6 to 9), `midday` (9 to 14), `evening` (14 to 19), `night` (19 to 6); `friday`;
`saturday`; and `all` of them. In each: `late`, the median lateness at its stops in seconds (early below 0); `on`, per
thousand passings from `early` seconds early to `late` seconds late; `runs` and `days` behind it. A band is there only
with 60 runs on 3 days or more; a route without any isn't there. 503 before the first figures, and when the last were
made more than 3 days ago (`{"error": "lateness out of date", "made": ...}`).

### busy

```json
{"made": "2026-11-14", "from": "2026-10-01", "to": "2026-10-31",
 "routes": {"2532": {"weekday": "000003222222122222221111", "friday": "100003222222221200000000", "saturday": "000000000000000000221111"}}}
```

From the Ministry of Transport's count of Rav-Kav taps by line, direction and hour of each day of a month (data.gov.il,
dataset `tikufim-2021`), asked each morning and fetched when the file changes. `from` and `to`: the month counted;
`made`: the day the levels were last told from it. By kind of day (`weekday` Sunday to Thursday, `friday`, `saturday`), an
hour a character from 00 to 23, by the taps per trip on the road in that hour against that kind of day's three highest: `3`
one of its busiest hours (8 in 10 of them or more, and among that day's 4 highest hours, never more than a third of the
hours told), `1` one of its quieter (under half), `2` about average, `q` a day whose three highest are under 15 taps a trip,
`0` too little to say (under 20 taps a day or a trip on the road, or a kind of day with under 4 such hours). Fridays and
Saturdays count only the days on the clock (summer or winter time) of the server's saved timetables of that kind of day,
Shabbat coming an hour earlier on winter time; with under 3 such days in the month, that kind of day isn't there. Taps are
boardings, not riders on board: never how full a bus is. All ways of a line and direction share its levels; trains and the
light rail have none. A route without levels isn't there. 503 before the first levels, and when the month ended more than
75 days ago (`{"error": "crowding figures out of date", "to": ...}`).

### shape

`{"route": 10966, "shape": "<polyline>"}`. 404 when the route has no shape, 503 before the timetable is loaded.

### bundle

The app's timetable for the seven days from today, which phones take in place of the week they shipped with.
`manifest.json` is `{"format": "KAV6", "from": "<first day>", "days": 7, "sha256": "<of il.kav.gz>", "size": <bytes>}`.
`manifest.json.sig` is its ECDSA P-256 SHA-256 signature, base64, made with the server's own key (its public half is
`bundleKey` in `status`). `il.kav.gz` has the manifest's sha256 as its ETag, and answers 304 to a phone that has it.

### alerts

Israel's whole list of line alerts, with every notice in it, for the phone to find its own lines in: the server never
learns which lines anyone rides (`server/alerts.py`, from a GTFS-Realtime feed, [self-hosting](selfhost/README.md#line-alerts)).
Gzipped for a client that takes it (Android's does), about 70 KB.

```json
{"at": 1794226400, "lang": "en", "source": "gtfsrt",
 "lines": [{"name": "קניון עופר/הורדה-פתח תקווה<->ת. מרכזית פתח תקווה-פתח תקווה-1#", "category": 4, "label": "Delays",
            "ids": ["3162118693", "3162118694"]}],
 "notices": {"3162118693": {"category": 3, "label": "Detour", "title": "...", "body": "...", "html": false,
                            "from": 1608841800, "to": 1608850800, "url": "https://www.afikim-t.co.il",
                            "detour": {"skipped": [33001], "added": [33002, 33003]}}}}
```

- `lines`: each way of a line with an alert, by the ministry's own name for it (GTFS `route_long_name`, the way the app
  names it too), the worse `category` of its alerts (4 a disruption, 3 a change of service) with its `label`, and the
  ids of every alert on it.
- `notices`: each alert once. `html`: the body is HTML (the English list has plain text).
  `from` and `to` are unix seconds, 0 when open. `detour`, when the source says: the stop codes not served and the ones
  added. `label` is the kind in Kav's own words, or the source's.
- In English, a notice not translated yet comes in Hebrew; the server translates it within a minute or two.

404 when the server has no alerts source, 503 before its first list is in. The app asks at most every ten minutes, and
only while a screen with rides is open.

### translate

`POST /live/v1/translate`, signed, with `{"texts": ["...", ...]}` (up to 20 texts of up to 4000 characters each) answers
`{"texts": [...]}` in English, in the same order. The app sends it the notices it fetched itself, from Google,
when Kav is in English; they're public alerts, and nothing of the trip comes with them. It uses the same translator and
store as the English alert list. Texts it has already translated answer at once. 400 for texts out of bounds; 503
`{"error": "busy"}` when a text needs the translator and it stays busy with other texts for more than 5 seconds, or the
English isn't all in within 12 seconds of the request, the translator's name lookup and connection included (what was
done is kept, so asking again is quicker); 502 when the translator doesn't answer; 404 on a server without a translator.
What phones ask for is kept for later requests: at most 5,000 texts and 16 million characters of their English, the
oldest going first, and a text longer than that is answered but not kept. The alerts' own translations are kept.

## Plan

```
GET /live/v1/plan/<from lat>,<from lon>,<to lat>,<to lon>/<unix seconds><way>[/<modes>][/via/<lat>,<lon>[,<minutes>][;<lat>,<lon>[,<minutes>]...]][/as/<settings>]
```

- `<way>`: `d` leave at the time, `a` arrive by it, `n` leave now, `l` the last way there that night.
- `<modes>`: the GTFS route types allowed, comma-separated (`0` light rail, `2` train, `3` bus, ...). All when left out.
- `via`: up to three places to pass on the way. `<minutes>` (optional, up to 999): how long to stay at the place; the
  route on leaves it that much after getting there, at the soonest. Left out, the place is only passed.
- `as`: the rider's planner settings, one letter each, in this order (up to 8 letters, letters or digits):
  1. walking pace: `s` slow (1 m/s), `f` fast (1.6 m/s);
  2. walking: `l` less (a minute on foot counts twice), `m` "I don't mind walking" (counts half);
  3. changes: `f` fewer (each change counts twice);
  4. time at the stop: `t` tight (no spare time before the bus, half a minute at a change), `r` relaxed (a minute and a
     half before the bus, three at a change).

  Pace and time at the stop go to the planner. Walking and changes are weighed in Kav's list only (the planner is asked
  as for the usual): the usual's list in the rider's order, and at most one more route, the one the rider's weights find
  cheapest in the same answers. The first route is the usual's first route unless another walks at most 15 minutes more
  in all (with `l`, at most a minute more) and either gets there no later, or at most 8 minutes later saving a change
  (`f`, `m`) or over a minute on foot (`l`). Arriving by a time, later is setting out earlier.

  `n`, any other letter, or a letter left out is the usual for that setting, and `/as/nnnn` or no `as` at all plans as
  before. With `t`, a route the server's chance tables give less than an even chance of making every bus is tagged
  `likely_missed`. A server from before settings answers 404 to a path with them: ask again without.

The answer, which a [planner of your own](PLANNER.md) gives in the same shape:

```json
{
  "it": [
    {
      "dep": 1793900000, "arr": 1793902100,
      "tags": ["least_walking"],
      "legs": [
        {"k": "w", "dep": 1793900000, "arr": 1793900300, "m": 350, "shape": "<polyline>",
         "turns": [["DEPART", "Herzl", 120, 32.07, 34.78], ["RIGHT", "Kaplan", 230, 32.071, 34.781]]},
        {"k": "r", "line": "10966", "trip": "30027349", "dep": 1793900400, "arr": 1793901900,
         "sdep": 1793900400, "first": 1793899800, "live": true, "head": "Tel Aviv University",
         "stops": ["21345", "21360", "21402"], "shape": "<polyline>",
         "next": [[1793900900, "30027350"], [1793901500, "30027351", 1]],
         "alts": [{"line": "10970", "trip": "...", "dep": 1793900700, "arr": 1793902000, "...": "..."}]}
      ]
    }
  ],
  "stops": {"21345": ["21345", "Dizengoff Center", 32.0754, 34.7749, ""]},
  "lines": {"10966": ["8", "Tel Aviv University", "Tel Aviv", "Dizengoff Center", "Tel Aviv", 5, 3, "long name"]},
  "agencies": {"5": "Dan"},
  "errors": []
}
```

- **`it`**: the routes, in the order to show them.
  - `dep` and `arr`: unix seconds.
  - `tags`: why a route stands out (`least_walking`, `low_transfer`, `after_shabbat`, `likely_missed`, and the like). May be
    empty.
  - `sec` (optional): the section a route belongs to, by the app's ids: 3 the timetable card, 521 walking or cycling all
    the way, 522 transit with a bike, 1461 East Jerusalem's operators, 1561 the taxi card, 1581 a taxi to transit. Left
    out for the main list.
  - `fare` (optional): the route's regular fare in agorot paying in an app, the ministry's fares added up as the price
    control order has it (changes within 90 minutes on the fare up to 15 km included, and a day pass where that is
    less). Left out where any of it is unsure. `singles` beside it: the single fares added up, where a day pass made
    `fare` less.
- **A leg's `k`**:
  - `w` walk: `m` is metres. `turns` (optional) are `[direction, street, metres, lat, lon]`, from OpenTripPlanner's
    walking steps.
  - `b` cycle, `t` taxi (with `m`, and `from` and `to` as `[lat, lon]`), `x` waiting for the taxi to come.
  - `via` (optional, on any leg): the first leg on from a place on the way, with which place it is, counted from 1.
    The rider is at the place from the end of the leg before to this leg's `dep`. `stay` beside it: the seconds they
    asked to stay there.
  - `r` ride: `line` is a key in `lines`. `stops` are keys in `stops`, from where it's boarded to where it's left.
    `sdep` is the timetable's departure, `first` is when the trip left its first stop (how the app finds the vehicle),
    `live` means the times are live, `head` is the destination shown on the vehicle.
  - A ride's optional extras: `next`, the line's next departures from the same stop, each `[unix seconds, trip]` with a
    `1` after them when it's live. `before`, its earlier ones since the time asked about, in the same form. `alts`, the
    other lines from the same stop to the same stop, each a ride with its own `next` (and `before`), `arr` null where
    the planner didn't say.
- **`shape`**: a Google-encoded polyline, precision 5.
- **`stops`**: `[code, name, lat, lon, platform]`.
- **`lines`**: `[number, destination, destination's city, origin, origin's city, agency id, GTFS route type, long name]`.
- **`agencies`**: the operators' names, by agency id.
- **`errors`**: OpenTripPlanner's routing error codes (`NO_TRANSIT_CONNECTION`, `OUTSIDE_SERVICE_PERIOD`, ...). Empty when
  there are none.

## Trip links

Not under `/live/v1/`: these are the addresses links are made on.

| Path | What it answers |
|---|---|
| `POST /api/share` (signed as above) | `{"trip": {...}, "days": 3 \| 7 \| 30, "card": "<base64 JPEG>"}` → 201 `{"url": "https://<server>/<code>", "expiresAt": <unix seconds>}` |
| `GET /api/share/<code>` (anyone) | The trip as it was shared, or 410 once it has expired |
| `GET /<code>` (anyone) | The trip's page, which opens it in Kav or the rider's own transit app |
| `GET /t/<code>.jpg` (anyone) | The card a chat shows with the link |

The trip's `data` is the app's own, `from` and `to` are `"<lat>,<lon>"`. A code is 7 letters and digits. The app opens a
copy's links as `kav://trip?host=<server>&id=<code>`.

Each share makes a link of its own, the same trip shared again too, and never changes another link. A card is a JPEG of
up to 200 KiB; a bigger one is refused with 400 (the app then shares the trip without it). New links can be refused
while links already made keep opening: 503 `{"error": "too many new trip links just now; try again in a few minutes"}`
when the allowance for all riders together is spent (60 links and 8 MB an hour, up to 20 links or 4 MB at once, refilled
as the hour goes; a restart of the server fills it again), and 507 `{"error": "no room for new trip links"}` when the
server keeps 50,000 links, or its file would pass 6 GiB, or its disk is down to its last 5%.
