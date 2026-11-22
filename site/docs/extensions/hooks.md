---
title: "Hook reference"
editLink: false
---

<div v-pre>

# Hook reference

Everything your code can provide to Kav with `Kav.provide(name, hook)`, every field Kav hands it, and every answer
it may give. Input and answer are JSON text. Kav calls a hook on a thread of its own, never the main one, within the
hook's deadline. Taken from Kav 5's code, paths under `android/app/src/main/java/uk/noammm/kav/`: `ext/Hooks.kt`,
`ext/NativeRules.kt`, `ext/Pipeline.kt`, `ext/BoardAnswers.kt`, `data/KavPlan.kt`. If this page and the code
disagree, the code is right; please report it.

A hook only runs when the manifest declares its contribution ([native.md](/docs/extensions/native) has the table). Cards, pages,
settings and themes have a page of their own: [screens.md](/docs/extensions/screens).

```kotlin
kav.provide("results.annotate") { input ->
    val routes = JSONObject(input).getJSONArray("routes")
    val out = JSONArray()
    for (i in 0 until routes.length()) out.put(JSONArray().put("quiet"))
    out.toString()
}
```

## The five route rules

After a planner answers a search, Kav puts the routes through the rules the rider switched on, in this order:
walking model, transfer policy, filters, ranker, annotators (`ext/Pipeline.kt`). A rider has at most one walking
model, one transfer policy and one ranker; filters and annotators run in the order they were switched on.

Each rule is **one call per search** with every route in it. The input is the whole search:

| Field | What it is |
|---|---|
| `query` | What was asked (below). |
| `routes` | Every route being judged. |
| `walks` | Every walk in those routes. |
| `transfers` | Every change in those routes. |

A rule answers a JSON array for one of those lists: `walk.time` for `walks`, `transfer.policy` for `transfers`, the
other three for `routes`. The array has **exactly one entry per item, in the same order**. Any other length, or an
answer that isn't an array, is refused and the rule is skipped for that search.

Taxi cards (a route with a taxi leg and no ride) are never sent to a rule and keep their places.

### `query`

| Field | Type | What it is |
|---|---|---|
| `way` | string | `"d"` leaving at a time, `"a"` arriving by a time, `"l"` the last way there tonight. |
| `at` | number | The time asked about, in seconds since 1970 (UTC). When the rider asked to leave now, the time of the search. |
| `now` | number | When the search was made, same unit. |
| `settings` | string | The rider's own planner settings, four letters (`data/PlanSettings.kt`): pace `s` slow, `n` usual, `f` fast; walking `l` less, `n` usual, `m` more; changes `n` usual, `f` fewer; time to spare `t` tight, `n` usual, `r` relaxed. The usual is `"nnnn"`. |
| `locale` | string | `"he"` or `"en"`. |

### A route

| Field | Type | What it is |
|---|---|---|
| `index` | number | Its place in `routes`. This is the number a ranker answers with. |
| `provider` | string | Whose plan it is: an extension planner's namespace, or `""` for Kav's own server. |
| `dep`, `arr` | number | When the rider leaves and arrives, seconds since 1970. |
| `fare` | number | The fare as the planner gave it (Kav's own server gives agorot), or `-1` when it isn't known. |
| `accessible` | boolean | Whether the planner marked the route accessible. |
| `tags` | string[] | The tags on the route so far: the planner's and Kav's own (below), and other extensions' as `x:<namespace>:<contribution>:<tag>`. |
| `legs` | object[] | The legs, in order. |

Kav's own tags (`data/KavPlan.kt` and `data/TripPlanner.kt`): `early_arrival`, `latest_departure`, `direct`,
`least_walking`, `low_transfer`, `last_departure`, `long_walk`, `long_wait_time`, `risky_transfer` (shown as "Tight
connection"), `likely_missed`, `high_frequency`, `flexible_route`, `after_shabbat`, `after_holiday`.

A card on a trip's page and a line under a search result are told about their route in this same shape
([screens.md](/docs/extensions/screens)).

### A leg

| Field | Type | What it is |
|---|---|---|
| `kind` | string | `"walk"`, `"wait"`, `"ride"`, `"taxi"`, `"bike"` or `"other"`. |
| `dep`, `arr` | number | Seconds since 1970. |
| `metres` | number | Its length. `0` when the planner gave none. |
| `insideMetres` | number | For a walk: how many of its meters are inside a station. `0` when the planner gave none. |
| `from`, `to` | object | Where it starts and ends: `{ stop, name, lat, lon }`. `stop` is a stop's reference (below) or `null` for a place that is not a stop; `name` the stop's name or `null`; `lat` and `lon` are degrees, to six decimals, and `0` when unknown. |
| `line` | string or null | For a ride: its line's reference. `null` for every other leg. |
| `lineNumber` | string or null | For a ride: the line's number as riders know it. May be `""`. `null` for every other leg. |
| `mode` | number | For a ride: the line's route type as its planner gave it. `2` is a train (Kav treats it as one), `3` a bus (what Kav assumes when a planner says nothing). `-1` when the line isn't known, and for every other leg. |
| `trip` | string or null | For a ride: the trip's reference, `null` when Kav has none for it. `null` for every other leg. |
| `stops` | number | For a ride: how many stops it calls at, counting the first and the last. `0` otherwise. |

A `wait` comes before each ride. It is at one stop, the one the ride leaves from: its `from` and `to` are both that
stop.

A **reference** names a thing in someone's data (`ext/Contracts.kt`): `publisher/dataset/kind/localId`, where kind
is `stop`, `route`, `trip` or `agency`. The Ministry of Transport's own are `il.mot/gtfs/stop/<stop code>`,
`il.mot/gtfs/route/<route id>` and `il.mot/gtfs/trip/<trip id>`. In the local id, `%`, `/` and `#` are written
`%25`, `%2F` and `%23`. The same local id from two publishers is two different things.

### `walk.time`: a walking model

Answers for `walks`. A walk:

| Field | Type | What it is |
|---|---|---|
| `route` | number | The route it is in (`routes[route]`). |
| `leg` | number | Which leg of that route it is. |
| `role` | string | `"access"` before the first ride, `"transfer"` between two, `"egress"` after the last, `"only"` when the route has no ride at all. |
| `metres` | number | Its length. |
| `insideMetres` | number | How many of those meters are inside a station. |
| `plannedSec` | number | How long the planner gave it, in seconds. |
| `fromKind`, `toKind` | string | What is at each end: `"place"`, `"stop"`, or `"station"` (a train's stop). |

**Answer:** for each walk, its time in seconds: a number, or `{"sec": number}`. Kav rounds it to a whole second.

```kotlin
kav.provide("walk.time") { input ->
    val walks = JSONObject(input).getJSONArray("walks")
    JSONArray().apply { for (i in 0 until walks.length()) put(walks.getJSONObject(i).getInt("metres") / 1.2) }.toString()
}
```

**What Kav does with it:**
- It takes the times as they are. It never cuts one back towards the planner's time.
- It refuses the **whole answer** when any time is negative. Whether a walk is believable is your business.
- It then checks every route again on the same timetable. If the walks before the first ride no longer fit, the
  rider leaves earlier, but never before the time they asked to leave at (or before now). If a change can no longer
  be made, or an arrive-by time is missed, the route leaves the list. It is only counted, as "1 route isn't
  catchable with your walking settings"; it is never shown lower down.
- When any time changed or a route left, it works its own tags out again for the routes that are left. A model that
  answers with the planner's own times changes nothing, tags included.

Deadline: 200 ms.

### `transfer.policy`: how changes are judged

Answers for `transfers`: every change from one ride to the next where only walking and waiting come between, except
a change at a stop the rider asked to stay at. A change:

| Field | Type | What it is |
|---|---|---|
| `route` | number | The route it is in. |
| `fromLeg`, `toLeg` | number | The ride arrived on and the ride boarded. |
| `fromMode`, `toMode` | number | Their route types (`2` a train, `3` a bus, `-1` unknown). |
| `walkMetres` | number | The walking between them, in meters. |
| `walkSec` | number | That walking's time: the walking model's when one answered, else the planner's. |
| `gapSec` | number | The time in hand: from arriving to the next ride leaving, less `walkSec`. Can be negative. |
| `sameStop` | boolean | Whether the rider gets off and boards at the same stop. |
| `fromStop`, `toStop` | string or null | The two stops' references. |

**Answer:** for each change, one of

| Answer | Meaning |
|---|---|
| a number | That many seconds to spare, as a hard rule. |
| `{"minSpareSec": n, "hard": b}` | `minSpareSec` seconds to spare (default `0`). `hard` defaults to `true`; only `false` makes it soft. |
| `{"forbid": "why"}` | The route is hidden, whatever its times. |
| `null` | No spare asked: the same as `0`. |

**What Kav does with it:**
- `minSpareSec` must be from 0 to 86,400 (a day), or the whole answer is refused.
- Hard: the walk plus the spare must fit before the next ride leaves, or the route is dropped and counted as not
  catchable.
- Soft: the route stays; when the spare doesn't fit, Kav puts its own "Tight connection" tag on it.
- Forbid: the route is dropped and counted as hidden ("1 route hidden by your extensions' rules"). A reason given as
  text is shown to the rider with that count; see "Reasons" below. `"forbid": true` forbids with no reason.
- When the policy changes nothing (no route dropped, no time moved), the routes stay exactly as they came: Kav does
  not work its own tags out again. A soft spare that doesn't fit still adds "Tight connection".

Deadline: 150 ms.

### `results.filter`: hiding routes

Answers for `routes`: the routes left after the walking model and the transfer policy, or after the filter before
this one.

**Answer:** for each route, `false` or a string (the reason) to drop it. Anything else keeps it. Dropped routes are
counted as hidden, and a reason is shown to the rider with that count.

#### Reasons, and the row of hidden routes

Kav keeps every route a rule left out, with the rule and the reason it gave. Under the routes the rider then sees a
quiet row: how many were hidden and by which extension ("2 routes hidden by "Calm changes""), your reasons in a small
line under it, as you gave them, each once, and a Show button that shows the hidden routes this once, after the kept
ones (`ui/ExtHiddenRoutes.kt`). Nothing is judged again and nothing is remembered. Write a few plain words: the row
shows two lines of reasons at most.

Deadline: 150 ms.

### `results.rank`: the order of Recommended

Called with the routes of Kav's main list only, and only when there are at least two.

**Answer:** the routes' `index` values in the order you want them: each a number, or an object with an `index`.
Every index must appear exactly once. Anything else is refused and Kav keeps its own order.

A ranker is never required: when it fails or is late the search is shown without it.

Deadline: 150 ms.

### `results.annotate`: tags on routes

Answers for `routes`.

**Answer:** for each route, a list of tags, or `{"tags": [...]}`, or anything else for none. A tag is any text; Kav
takes up to 64 per route, drops blank ones and repeats, and shows every one after its own tags, kept as the
extension's (`x:<namespace>:<contribution>:<tag>`), so it is never taken for one of Kav's.

A tag the manifest declares under `tags` shows with the words declared there, in the rider's language:

```json
"tags": { "quiet": { "en": "Quiet streets", "he": "רחובות שקטים" } }
```

Any other tag shows as its own text.

Deadline: 150 ms.

### Time, failure, and "required"

- The five rules share **400 ms** for the whole search, counted from the planner's answer. Each hook gets what is
  left, up to its own limit above. Kav shows its own routes first and applies the rules' changes when they arrive.
- With less than 20 ms left a hook is not asked at all: an optional rule is skipped for that search, a required one
  fails it visibly.
- A rider can mark a walking model, a transfer policy or a filter **required**. Kav switches a walking model and a
  transfer policy on as required, and a filter as optional. When a required rule fails, is late, or its extension
  is off, the search shows no routes and says so ("Can't use your walking rules (...) right now, so no routes are
  shown"), with a way to see the routes unjudged. An optional rule is skipped with a note ("Shown without ...: it
  didn't answer in time").
- A late or unreadable answer is written to the extension's log and skipped. Kav switches nothing off for it. A hook
  that throws is also counted as a crash ([native.md](/docs/extensions/native), "The crash guard and safe mode").
- A late hook is not stopped: Kav stops waiting and leaves it to finish. Keep rules quick.

## `plan.provide`: a planner

Declared as a contribution with capability `plan.provide`. Kav asks it instead of its own planner when the rider
picks it. Deadline: 30 seconds.

The input (`ext/Hooks.kt`, `NativePlanner`):

| Field | Type | What it is |
|---|---|---|
| `from`, `to` | [lat, lon] | Where from and where to, in degrees. Kav never sends a place's name. |
| `at` | number | The time asked about, seconds since 1970. |
| `way` | string | `"d"`, `"a"` or `"l"`, as in a query above. |
| `modes` | number[] | The route types the rider wants. |
| `settings` | string | The rider's four settings letters. |

It answers a plan, or `null` for no routes. Kav reads the plan with its own plan reader (`data/KavPlan.kt`, `parse`)
and treats every id in it as the extension's own. A trip with stops on the way is planned part by part and joined by
Kav.

The smallest plan Kav's own test planner returns (`android/app/src/debug/java/uk/noammm/kav/ext/Fixtures.kt`) has
this shape:

```json
{
  "stops": { "17038": ["17038", "Gamma Pier", 32.083, 34.783] },
  "agencies": { "5": "Gamma Lines" },
  "lines": { "7": ["7", "Gamma Market", "", "Gamma Pier", "", 5, 3, ""] },
  "it": [{
    "dep": 1795420860, "arr": 1795422780,
    "legs": [
      { "k": "w", "dep": 1795420860, "arr": 1795421100, "m": 300, "shape": "..." },
      { "k": "r", "line": "7", "trip": "1994912", "dep": 1795421100, "arr": 1795421700, "sdep": 1795421100, "stops": ["17038", "25565"], "shape": "..." }
    ]
  }]
}
```

- `stops`: id to `[code, name, lat, lon]`.
- `agencies`: operator id to name.
- `lines`: id to `[number, to, to city, from, from city, operator id, route type, the operator's own name for the
  line]`.
- `it`: the routes. A leg's `k` is `"w"` for a walk (`m` its meters, `im` its meters inside a station) or `"r"` for a
  ride.
- `shape` is a polyline in Google's encoding at five decimals.

The plan reader takes more than this (alternatives, fares, tags, sections, turns). **That part of the contract is not
written up here yet.**

Every planner's answer is read as plumbing (`ext/PlanChecks.kt`, `data/KavPlan.kt`):

- **One ceiling:** the whole answer may be at most 262,144 characters. Past it, it is refused whole, and the rider is
  told the planner answered something Kav can't read as routes.
- **Your words are shown as you gave them:** stop names, line ends, operators, street names, tags. Kav's screens cut
  what doesn't fit.
- **Ids of any length.** Kav keeps an id (a stop's, a line's, a trip's, an operator's) over 64 characters as a short
  stand-in made from it ("~" and 24 hex characters of its SHA-256), the same one every time, so nothing breaks; but
  the id your route rules or a later call see for it is the stand-in, not yours.
- **Only routes Kav can draw:** a route is dropped when it has no legs, ends before it starts, or has a leg that ends
  before it starts or starts before the leg before it ended. Whether a route is sensible (a fast walk, a day-long
  ride, a time other than the one asked) is your business.

When every route is dropped the rider is not told "no routes": they are told the planner answered routes Kav can't
show. What Kav sends the rider's route rules about your routes must also fit 512 KB; an answer that comes to more is
refused whole as too large. Short ids and no more stops and shape than a route needs keep you far from it.

Ids in your plan are your own: an id that looks like the ministry's is not taken for the ministry's, so its rides
get no live times from Kav.

## Timetables and stop boards: `board.provide`

A contribution with capability `board.provide` gives Kav stops of its own and what leaves them. Riders find those
stops in the Stops tab, in search and nearby, after Kav's own, with no mark of yours; opening one shows
Kav's stop board filled with your times, marked as yours, refreshed every 30 seconds; and a rider can save the stop
like any other (`ext/Hooks.kt`, `NativeBoard`). Kav asks only the stop's own publisher about it, wherever it shows
departures.

The timetable may be inside your code, or read from a server with Android's own networking. You provide two hooks.

### `board.stops`: the stops and lines

Asked once per release, and the answer is kept. The input is `{}`.

```kotlin
kav.provide("board.stops") { _ ->
    """{"stops": [{"id": "old-quay", "name": "Old Quay", "lat": 32.8201, "lon": 34.9712, "code": "Q1", "mode": 4}],
        "lines": {"blue": {"number": "B", "name": "Old Quay to Lighthouse Island", "mode": 4}}}"""
}
```

| Field | Rule |
|---|---|
| `stops[].id` | Your own id for the stop: text of 1 to 128 characters, or a whole number. No control characters, no tabs or line breaks. Unique. Kav never mixes it with another publisher's or its own. |
| `stops[].name` | Needed: text, not empty. Shown as you gave it. |
| `stops[].lat`, `lon` | Needed: degrees, within -90 to 90 and -180 to 180. |
| `stops[].code` | Optional: the code riders know the stop by. A search for exactly this finds the stop. |
| `stops[].mode` | Optional: the kind of stop, as a route type from 0 to 12 (0 tram, 1 metro, 2 train, 3 bus, 4 ferry, 5 cable car, 6 gondola, 7 funicular). Default 3. |
| `lines` | Optional: an object, each key a line's id. `number`, `name` and `mode`, each as above. |

At most 200,000 stops and 20,000 lines. An answer with no stops means the provider offers nothing.

Deadline: 60 seconds.

### `board.departures`: one stop's board

Called when a rider opens one of your stops, and again as the board refreshes:

| Field | Type | What it is |
|---|---|---|
| `stop` | string | The stop's id, as you gave it. |
| `from` | number | Seconds since 1970: departures from this time on are wanted. |
| `limit` | number | How many Kav will take. |

**Answer:** an array of departures, or `null` for none.

| Field | Rule |
|---|---|
| `line` | Needed: the id of a line. |
| `time` | Needed: the timetable's time, whole seconds since 1970, above 0 and not after 4102444800 (the start of the year 2100). |
| `estimated` | Optional: the time now expected, when you know better than the timetable. Leave it out when you don't: a `null` here is refused. |
| `trip` | Optional: your id for the trip. Without one Kav makes one from the line and the time. Leave it out when you have none: a `null` is refused. |
| `platform` | Optional. |
| `headsign` | Optional: where it goes. Leave it out when the line's name says it. |

Kav sorts the list itself, soonest first, by `estimated` when there is one.

**What Kav does with an answer** (`ext/BoardAnswers.kt`): it checks every field. Anything outside the rules above, or
more departures than `limit`, makes the whole answer malformed, and a malformed answer is shown as a provider that
could not answer, never as an empty board. Only `null` or an empty array is an empty board.

Deadline: 30 seconds.

`samples/native/native-rules` has a stop board for two stops of its own.

## What is not here yet

- The full shape of a plan (alternatives, fares, tags, sections, turns): see `plan.provide` above.
- Saving one of your lines: Kav has no page for an extension's line yet, so only stops can be saved.

</div>
