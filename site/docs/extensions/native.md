---
title: "Kotlin and Java extensions"
editLink: false
---

<div v-pre>

# Kotlin and Java extensions

A Kav extension is Android code that runs inside Kav. You write it in Kotlin or Java against Kav's small API
(`kav.kavapp.api`), the kit builds it into one dex, and the dex goes into the bundle beside its `manifest.json`.
Kav loads it and calls your entry class once. From there you add cards to Kav's screens, screens of your own, a Home
and a tab, things on Kav's maps, a trip planner, route rules and stop boards. Anything else (location,
notifications, the camera, files, the network) you do with Android itself.

It runs in Kav's own process, with Kav's permissions and full access. Riders are told exactly that ("Runs its own
Android code: full access"), and they switch it on at their own risk, the way browser add-ons work. Kav does not
review, sandbox or approve it.

Taken from Kav 5's code: `android/app/src/main/java/kav/kavapp/api/` (the API) and `ext/Native.kt`,
`ext/NativeMap.kt`, `ext/NativeRules.kt`, `ext/Hooks.kt` (how Kav loads, calls and guards it).

## What you need

- **The kit**: `git clone https://github.com/ImNoammm/kav-extension-kit`, then `npm link` in its folder puts `kavx` on
  your `PATH`.
- **Node.js** 22.2 or newer, for `kavx`.
- **A JDK**, 17 or newer. Set `JAVA_HOME`, or have `java` and `javac` on your `PATH`.
- **The Android SDK**, with one platform (`platforms;android-36`) and the build tools (`build-tools;36.0.0` or
  newer). Android Studio installs it; so do the command-line tools from developer.android.com. Set `ANDROID_HOME`,
  or keep it at `~/Android/Sdk`.
- **For Kotlin, the Kotlin compiler.** Download `kotlin-compiler-<version>.zip` from
  <https://github.com/JetBrains/kotlin/releases>, unpack it, and put its `bin` folder on your `PATH` (or set
  `KOTLINC` to its `kotlinc`). The kit was tried with 2.2.10, the version Kav itself is built with.

No Gradle and no Android Studio project: the kit runs `kotlinc` or `javac`, then Android's own `d8` or R8.

## From nothing to a bundle

```
cp -r starter-kotlin ~/hello-kotlin
kavx check ~/hello-kotlin
kavx bundle ~/hello-kotlin
```

`kavx check` builds the folder and prints what Kav and riders would see, or why Kav would refuse it. `kavx bundle`
builds it the same way and writes `hello-kotlin.kavb`, a bundle with this one extension in it. For a source with
several extensions, put each in its own folder under `extensions/` and run `kavx bundle` on the folder above
([format.md](/docs/extensions/format)).

The build, for a folder whose manifest has `"native"` and which has a `src/` folder:

1. compiles every `.kt` and `.java` file under `src/` against `android.jar` and the kit's `api/kav-api.jar`;
2. turns the classes into one dex: with `d8` for Java, with R8 when there is Kotlin (below);
3. puts `manifest.json`, the dex (where `"native"` says) and every other file of the folder (an icon, pictures) in
   `<folder>/build/package/`, the extension's folder as the bundle holds it. `src/`, `build/` and files starting
   with a dot stay out.

The build also leaves `classes/` and `dex/` in `<folder>/build/`.

**Kotlin's standard library.** Kotlin code calls the Kotlin standard library, and Kav's release build shrinks and
renames its own copy, so an extension can't use Kav's. When there is Kotlin, the kit runs R8 with these rules: every
class of yours is kept exactly as it is, and the parts of the standard library your code uses are shrunk and moved
into a package of yours (`<your entry's package>.kotlin`), under short names, so they never meet Kav's. The starter's
dex is about 5 KB. `kotlinx` libraries (coroutines and so on) are not bundled: use Android's own threads and
handlers, or copy what you need.

**One dex.** Kav loads one dex file, so the code must fit one (65,536 methods). The build stops if it makes two.

**The API jar.** `api/kav-api.jar` is made from Kav's own sources by `node tools/make-api-jar.js <Kav's source
folder>`, and `api/src/` holds those sources to read. It is for compiling only: Kav provides these classes when it
loads your code, so they never go into your dex.

## The manifest's native part

```json
{
  "format": 1,
  "name": "hello-kotlin",
  "displayName": "Hello Kotlin",
  "version": "1.0.0",
  "release": 1,
  "minKav": 45,
  "api": 2,
  "description": "A card on Kav's Home and a screen of its own, written in Kotlin.",
  "native": { "dex": "classes.dex", "entry": "org.example.hello.Hello" },
  "contributions": [
    { "id": "card", "capability": "slot.home" },
    { "id": "screens", "capability": "page.provide" }
  ],
  "pages": [{ "id": "about", "title": { "en": "Hello Kotlin", "he": "שלום קוטלין" } }]
}
```

- `native.dex` is the dex's path inside the extension's folder (`classes.dex` is the usual name). Kav refuses an
  extension whose dex isn't there, and any other `.dex` file in it.
- `native.entry` is the full name of your class that implements `KavExtension`. It needs a public constructor with
  no arguments.
- `minKav: 45` is Kav 5. `api` is the API level your code uses: 2 and 3 run on every Kav 5, and 4 is needed for
  actions, screen parts, tickets and hooks ([below](#api-4-taking-over-kav)). API 4 came with Kav 5.1, version code 47:
  with `"api": 4`, set `minKav` to 47, so an older Kav lists your extension as needing a newer Kav. API 5 adds
  `kav.prefs()`, `kav.trips()`, `kav.onTrip()`, `kav.widget()` and `kav.refreshWidget()` and came with Kav 5.2, version code 48: with `"api": 5`, set `minKav` to 48. A Kav with an older API than your manifest says doesn't start your
  code.
- A manifest without `"native"`, or with `"runtime"`, was made for an older version of Kav: Kav lists it and never
  runs it.

The rest of the manifest is in [format.md](/docs/extensions/format). What you register in code only shows when the manifest
declares it too:

| In code | In the manifest |
|---|---|
| `kav.card("home", ...)`, `"results"`, `"trip"`, `"stop"` | a contribution `slot.home`, `slot.results`, `slot.trip` or `slot.stop` |
| `kav.screen("about", ...)` | a contribution `page.provide` and the page `{"id": "about", ...}` under `pages` |
| a tab | the same, with `"tab": true` on the page |
| `kav.home(...)` | a contribution `home.body`, and the page `"home"` (with `page.provide`) |
| `kav.action("pay", ...)` and every other action | `"api": 4` and a contribution `action.pay` (`action.<name>`) |
| `kav.part("home.pay", ...)` and every other part | `"api": 4` and a contribution `part.home.pay` (`part.<name>`) |
| `kav.hook(...)`, `kav.deoptimize(...)` | `"api": 4` and a contribution `hooks` |
| `kav.addTicket(...)`, `kav.kavAction(...)`, `kav.kavClassLoader()` | `"api": 4` |
| `kav.provide("plan.provide", ...)` | a contribution `plan.provide` |
| `kav.provide("walk.time", ...)` and the other route rules | a contribution with that capability |
| `kav.provide("board.stops", ...)`, `"board.departures"` | a contribution `board.provide` |
| `kav.provide("stop.photo", ...)` | a contribution `stop.photo` ([hooks.md](/docs/extensions/hooks#stop-photos-stopphoto)) |
| `kav.prefs()` | `"api": 5` |
| `kav.trips()` | `"api": 5` and a contribution `trips.history` ([hooks.md](/docs/extensions/hooks#the-riders-trips-tripshistory-and-tripsfollow-api-5)) |
| `kav.onTrip(...)` | `"api": 5` and a contribution `trips.follow` (the same) |
| `kav.widget("next", ...)`, `kav.refreshWidget("next")` | `"api": 5`, a contribution `widget.provide` and the widget `{"id": "next", ...}` under `widgets` ([screens.md](/docs/extensions/screens#home-screen-widgets)) |
| `kav.part("screen.overlay", "after", ...)` | `"api": 5` and a contribution `part.screen.overlay` ([below](#over-the-whole-screen)) |
| `kav.capture(...)`, `kav.captureBehind(...)` | `"api": 5` and a contribution `screen.capture` (the same) |
| `kav.settings()`, `kav.onSettings(...)` | `settings` ([screens.md](/docs/extensions/screens)) |
| `kav.map()`, `kav.settingsScreen(...)`, notifications, location | nothing, but say so in `adds` so riders are told |

## The API

Everything is in `kav.kavapp.api`, and its sources are in `api/src/`. A newer Kav keeps every method of an older
one; `kav.apiVersion()` says which level the Kav in front of you has (Kav 5.0 has 3, Kav 5.1 has 4, Kav 5.2 has 5). Plain
types only: text, numbers, JSON as text, and Android's own `Context`, `View` and `Bitmap`.

**When it runs.** Kav calls `start` once each time Kav starts, and at once when the rider switches the extension on
or an update of it arrives: no restart is needed. Never in safe mode.

**Threads.** `start` runs on a background thread. Kav asks a card or screen for its view on the main thread, each
time the screen shows it. Every `KavUi` method must be called on the main thread. Hooks, `onSettings` and a widget's
`content` and `tapped` are called on a thread of Kav's, never the main one. `KavMap` may be called from any thread; its tap and stop-look calls come on
the main thread.

### KavExtension

```kotlin
class Hello : KavExtension {
    override fun start(kav: Kav) {
        // register everything here
    }
}
```

`start(kav)` may throw: Kav catches it, counts it as a crash (below) and carries on without your extension.

### Kav

| Method | What it gives |
|---|---|
| `Kav.API` | the API level this jar was built for (5) |
| `apiVersion()` | the API level of the Kav that loaded you |
| `context()` | the application's `Context` |
| `id()` | your identity: your source and your name, `"github.<owner>.<repo>/<name>"`, or `"file.<digest>/<name>"` for a bundle added from a file |
| `locale()` | Kav's language, `"en"` or `"he"` |
| `prefs()` | API 5: the rider's display settings as JSON text, `{"stopNames": "en", "clock": 24, "locale": "en"}` ([hooks.md](/docs/extensions/hooks#planner-tabs-and-display-settings)) |
| `trips()` | API 5: the rider's recent trips as JSON text, `null` until the rider allows it ([hooks.md](/docs/extensions/hooks#the-riders-trips-tripshistory-and-tripsfollow-api-5)) |
| `onTrip(listener)` | API 5: told as the trip the rider follows starts, moves from step to step and ends, once the rider allows it (the same) |
| `widget(id, widget)` | API 5: a home screen widget's content, as JSON text Kav draws, and its taps ([screens.md](/docs/extensions/screens#home-screen-widgets)) |
| `refreshWidget(id)` | API 5: asks Kav to ask that widget for its content again now |
| `stops(query)` | API 5: stops from Kav's timetable on the phone, nearest first or by name ([below](#kavs-timetable-live-vehicles-and-ride-history)) |
| `vehicles(query)` | API 5: the live vehicles of a line, or around a place, as Kav's Live tab has them (the same) |
| `departures(query)` | API 5: a stop's next departures, as its board in Kav shows them (the same) |
| `addRides(rides)` | API 5: rides already over, into Kav's ride history (the same) |
| `plan(query)` | API 5: Kav's own planner, its routes each with a `ref` ([hooks.md](/docs/extensions/hooks#kavs-own-planner-kavplan-resultsadd-and-kavswitchtrip-api-5)) |
| `switchTrip(change)` | API 5: the trip under way goes on with a route `plan` gave (the same) |
| `alarm(id, atMillis)` | API 5: wakes Kav at a time, with Kav closed too, and tells `onAlarm` ([below](#alarms-and-files)) |
| `onAlarm(listener)` | API 5: told each of your alarms by its id (the same) |
| `pickFile(types, picked)` | API 5: Android's photo or file picker over Kav's screen; told the file chosen, or `null` (the same) |
| `capture(captured)` | API 5: a picture of Kav's screen as it shows now and the boxes of its pieces, on the main thread ([below](#over-the-whole-screen)) |
| `captureBehind(captured)` | API 5: the same, and with it the screen without its pieces (the same) |
| `card(on, card)` | a card on Kav's `"home"`, a line under each search result (`"results"`), a card on a trip's page (`"trip"`) or on a stop's board (`"stop"`) |
| `screen(page, screen)` | a screen for one of your pages |
| `home(screen)` | your Home body |
| `map()` | Kav's maps (`KavMap`) |
| `provide(name, hook)` | a hook Kav calls with JSON text and that answers JSON text |
| `settings()` | the rider's values for your settings, as JSON text (`{}` with none) |
| `onSettings(changed)` | told each time the rider changes one of your settings, with all of them as JSON text (`null` stops it) |
| `settingsScreen(screen)` | your own settings view, shown on your extension's page in Settings › Extensions under the settings you declare (or alone) |
| `pickPlace(picked)` | Kav's own place search over the screen; told the place chosen as JSON text (`{"name", "lat", "lon"}`), or `null` when the rider closed it, on the main thread |
| `log(text)` | a line in your log |
| `ui()` | Kav's components and theme (`KavUi`) |
| `action(name, action)` | API 4: take over one of Kav's actions ([below](#actions)) |
| `kavAction(name, activity, args)` | API 4: Kav's own handling of an action, with no extension asked |
| `part(name, where, card)` | API 4: a view `"before"` or `"after"` one of Kav's screen parts, or one that `"replace"`s it ([below](#screen-parts)) |
| `addTicket(ticket)` | API 4: a ticket in Kav's own tickets and ride history ([below](#tickets)) |
| `hook(method, before, after)` | API 4: a hook on one of Kav's own methods, or Android's ([below](#hooks-into-kav-itself)) |
| `deoptimize(method)` | API 4: have ART interpret a caller again so its runtime inlining does not skip a hook |
| `kavClassLoader()` | API 4: Kav's own `ClassLoader`, to find its classes by name |

```kotlin
val app = kav.context()
kav.log("started as ${kav.id()} in ${kav.locale()}")

// about: what the screen shows, as JSON text. Home: {}. A stop: {"stop": "il.mot/gtfs/stop/21345", "name": "..."}.
// A trip's page and a search result: {"trip": <the route, in the shape route rules get it (hooks.md)>}.
kav.card("stop") { context, about -> kav.ui().card(context, TextView(context).apply { text = JSONObject(about).optString("name") }) }
kav.card("results") { context, about ->
    val legs = JSONObject(about).getJSONObject("trip").getJSONArray("legs")
    TextView(context).apply { text = "${legs.length()} legs" }
}

// args: the page's arguments, as JSON text.
kav.screen("about") { context, _ -> kav.ui().blocks(context, """{"title": "About", "sections": [{"blocks": [{"type": "text", "text": "Hi"}]}]}""") }

kav.home { context, _ -> kav.ui().header(context, "My Home") }

// A route rule: the search in, one answer per route out (hooks.md).
kav.provide("results.filter") { input ->
    val routes = JSONObject(input).getJSONArray("routes")
    JSONArray().apply { for (i in 0 until routes.length()) put(i % 2 == 0) }.toString()
}

val pace = JSONObject(kav.settings()).optDouble("pace", 15.0)

// Your own settings view: anything you like, your own pickers and colors. Kav shows it under your declared settings.
kav.settingsScreen { context, _ -> ColourPicker(context) }

// Kav's place search, from anywhere in your code: the rider picks, you're told.
kav.pickPlace { place -> if (place != null) home = JSONObject(place) }

// Told when the rider changes a setting, with all of them. Not on the main thread.
kav.onSettings { all -> kav.log("pace is now ${JSONObject(all).optDouble("pace")}") }
```

`card`, `screen` and `home` may be called again later to replace what was registered; the next time the screen
shows, it asks the new one.

**Cards.** Kav puts each card where its slot is ([screens.md](/docs/extensions/screens)).
Kav calls `create` each time the screen shows the card, on the main thread, so make the view quickly and load
anything slow on a thread of your own.

**Settings** are declared in the manifest: `text`, `number`, `toggle`, `choice`, and the ones Kav picks with its own
pickers, `place`, `stop`, `time`, `date` and `color`, drawn by Kav on your extension's page
([screens.md](/docs/extensions/screens#settings)).

**Hooks** (`provide`) take JSON text and answer JSON text. [hooks.md](/docs/extensions/hooks) has every field:

| Name | Input | Answer |
|---|---|---|
| `walk.time`, `transfer.policy`, `results.filter`, `results.rank`, `results.annotate` | the search: `{"query", "routes", "walks", "transfers"}` | an array, one entry per walk, change or route |
| `plan.provide` | `{"from": [lat, lon], "to": [lat, lon], "at", "way", "modes", "settings"}` | a plan, or `null` for no routes |
| `board.stops` | `{}` | `{"stops": [...], "lines": {...}}` |
| `board.departures` | `{"stop", "from", "limit"}` | an array of departures |
| `results.add` | API 5: the search, `{"query", "routes", "trip"}` | `[{"ref", "start", "walks"}]`: routes from `plan`, first in the results ([hooks.md](/docs/extensions/hooks#kavs-own-planner-kavplan-resultsadd-and-kavswitchtrip-api-5)) |

A route rule is asked within the search's shared budget (400 ms for all rules together, at most 150 to 200 ms
each); a planner and a stop board within 30 seconds (`board.stops` 60). A hook that throws, answers late or answers
something Kav can't read is skipped and written to your log. A hook that throws also counts as a crash.
`samples/native/native-rules` has a planner, three route rules and a stop board.

### KavUi: Kav's own components

Each one is an ordinary `View`, drawn by Kav, that follows Kav's theme, dark mode, right-to-left and text size by
itself. Put them in any layout.

```kotlin
val ui = kav.ui()
ui.button(c, "Save", true) { save() }                    // primary: the screen's one main action
ui.card(c, myContent)                                     // Kav's panel around your own view
ui.row(c, "info", "Where it comes from", "Your GPS", null) // icon (Kav's name, or null), title, subtitle, onClick
ui.switchRow(c, "Miles", "Instead of km/h", on) { now -> setMiles(now) }
ui.textField(c, "Haifa", "Your town") { text -> town = text }
ui.textField(c, note, "Your note", multiline = true, action = null) { text -> note = text }   // API 3: Enter is a new line
ui.textField(c, title, "Title", multiline = false, action = "next") { text -> title = text } // API 3: the keyboard's key
ui.iconButton(c, "edit", "Edit") { edit() }               // API 3: a round button with one of Kav's icons
val pages = ui.pages(c, listView)                         // API 3: one view at a time, in Kav's own transition
pages.forward(noteView)                                   // slides the next view in; pages.back(listView) the one before
ui.chip(c, "Buses", selected = true) { toggle() }
ui.header(c, "Units")
ui.sheet(c, "Details", detailsView)                       // a sheet over the screen
ui.dialog(c, "Hello", "Saved.", "OK") { }                 // with Cancel beside OK
ui.blocks(c, pageJson)                                    // a whole screen from a block tree (screens.md)
val text = ui.color("text")                               // ARGB, 0 for a name Kav doesn't know
val gap = ui.dp("gap3")                                   // dp
val size = ui.textSize("body")                            // sp
```

- Icons for `row` and `iconButton`: `back`, `star`, `heart`, `bus`, `train`, `walk`, `bike`, `clock`, `map`, `pin`, `info`, `warning`,
  `check`, `plus`, `edit`, `delete`, `search`, `list`, `home`, `calendar`, `settings`. Another name shows none.
- Colors: `bg`, `surface`, `surfaceRaised`, `fill`, `fillStrong`, `separator`, `text`, `textSecondary`,
  `textTertiary`, `surface2`, `surface3`, `surface4`, `borderStrong`, `sunken`, `routeIdle`, `problem`, `critical`,
  `realtime`, `accent`.
- Sizes (`dp`): `rCard`, `rSheet`, `rTile`, `rBadge`, `gap1` to `gap8`. Text styles (`textSize`): `largeTitle`,
  `title`, `headline`, `body`, `subhead`, `caption`.
- The `Changed<T>` callbacks (`switchRow`, `textField`) are told each new value.
- A multi-line `textField` grows as it fills and Enter makes a new line. On a one-line one, `action` is the keyboard's
  key: `next` (on to the next field), `done`, `search` or `go`.
- `pages(c, first)` gives a frame (`view()`) that shows one view at a time: `forward(next)` and `back(previous)` move
  with Kav's own transition, the way its Settings pages move. Build each view fresh when you move to it.
- The API 3 calls (the multi-line `textField`, `iconButton`, `pages`) need a Kav whose `apiVersion()` is 3 or more:
  set `"api": 3` in the manifest when you use them.
- `blocks` throws when Kav can't read the tree ([screens.md](/docs/extensions/screens#blocks) says what that means). Inside a
  card's or screen's `create`, an uncaught throw counts as a crash.

### KavMap: Kav's maps

Places are latitude and longitude in degrees, colors ARGB, sizes in dp, icons `Bitmap`s you draw. `on` is the map a
shape shows on: `"live"` (the Live tab), `"trip"` (a trip's map), or `null` for both. `id` is your own name for the
shape; adding one with an id you used before replaces it.

```kotlin
val map = kav.map()
map.marker("star", null, 32.0725, 34.7795, Color.RED, starBitmap)       // icon centered on the place; null: a dot
map.line("route", "live", doubleArrayOf(32.06, 34.77, 32.07, 34.78), 0xFF7B1FA2.toInt(), 6f)
map.area("park", null, doubleArrayOf(32.070, 34.771, 32.072, 34.772, 32.070, 34.774), 0x5500C853, 0xFF00C853.toInt())
map.remove("route")
map.clear()                                                             // every shape of yours, off every map
map.onTap { id, context -> kav.ui().dialog(context, "Map", "You tapped $id", "OK") {} }   // null stops it
map.stopLook { stop, name, lat, lon ->                                  // your look for Kav's stop marks on Live
    if (stop.endsWith("0")) KavMap.Look(0, 0, squareBitmap) else KavMap.Look(0xFFC8E6C9.toInt(), 0xFF1B5E20.toInt(), null)
}
```

`stopLook` is asked once per stop (its reference is `"il.mot/gtfs/stop/<code>"`) and the answer is kept until you
set a look again, so keep it quick. Return `null` for Kav's own look for that stop; `stopLook(null)` gives every stop
back Kav's look. When two extensions set a stop look, the last one set wins. `samples/native/map-pins` uses all of
it.

### Kav's timetable, live vehicles and ride history

API 5. What Kav already has, without reaching into its code: JSON text in and out. Stops and departures are public
transit data, and a ride you add is your own, so none of these needs a contribution.

```kotlin
// Stops: any of near, name, mode (the GTFS route type of its lines, a train station 2), code; limit 20 by default.
val stations = kav.stops("""{"near": [32.08, 34.78], "mode": 2, "limit": 5}""")
// [{"stop": "il.mot/gtfs/stop/37292", "code": 37292, "name": "...", "nameHe": "...", "city": "...", "lat", "lon", "mode": 2, "platform": "", "metres": 840}]

// Off the main thread: these two ask the network.
val buses = kav.vehicles("""{"line": "37"}""")            // or {"near": [lat, lon], "km": 1}, or both
// [{"key", "plate": "12-345-67", "route", "line": "37", "to", "agency", "mode", "lat", "lon", "bearing", "speed", "at"}]
val board = kav.departures("""{"stop": "il.mot/gtfs/stop/21345", "limit": 10}""")
// [{"line": "37", "to": "...", "mode": 3, "platform": "", "at": 1795421100, "live": 1795421160, "cancelled": false}]

// Rides already over, into Kav's ride history; the same key again updates one, and "removed" takes one out.
kav.addRides("""[{"key": "r1", "kind": "BUS", "line": "37", "from": "...", "to": "...", "at": 1795421100000, "riders": 1, "band": 15, "price": 600}]""")
kav.addRides("""[{"key": "r1", "removed": true}]""")
```

- `stops` answers `null` until Kav's timetable has loaded; nearest first with `near`, else by name. At most `limit`,
  200 at most; with a `mode` other than bus (3) there is no cap, so a list of every station of one is whole. `name` is
  the stop's name as Kav shows it, `nameHe` the timetable's own Hebrew name. From any thread.
- `vehicles` and `departures` throw on the main thread. `vehicles` answers `null` when Kav's live source can't be
  reached; for `near`, Kav's server is told only the box around the place, in whole hundredths of a degree, as for the
  Live tab. With both `line` and `near`, the line's vehicles come nearest the place first, each with `metres`. `at` is
  when the vehicle last said where it was, `bearing` and `speed` are `-1` when it didn't say.
- `departures` is the stop's board as Kav shows it now: the timetable's provider where Kav asks one, else the week on
  the phone, each with its live time where there is one. `at` is the timetable's time, `live` the live one or `null`,
  both in seconds. `null` for a stop Kav doesn't know.
- `addRides` takes `kind` `BUS`, `TRAIN` or `LIGHT_RAIL`, `at` in milliseconds, `band` the fare's reach in km and
  `price` in agorot, the same fields its ride history shows; Kav keeps each
  key as `"ext:<your id()>:<key>"`, as with tickets, so you never touch another's rides or the ones Kav read.
  `{"key", "removed": true}` takes your ride with that key out, for one your pay service cancelled. An extension
  installed from a bundle file gets a new `id()` with each file, so for it a key also covers the rides an earlier bundle
  file of the same name left under that key: adding replaces them, removing takes them out. A ride that isn't right (no
  key, another kind, no `at`) throws, and none of the list is added.

### Alarms and files

API 5. Kav wakes your extension at a time you set, with Kav closed too, and opens Android's own pickers for you.

```kotlin
// Told on a thread of Kav's, once your extension has started; the same id again moves an alarm, 0 takes it out.
kav.onAlarm { id -> if (id == "check") checkTheTrip() }
kav.alarm("check", System.currentTimeMillis() + 3 * 60_000)

// Images only: Android's photo picker. Else its file picker for those MIME types (all files for null).
kav.pickFile(arrayOf("image/*")) { uri -> if (uri != null) hero = Uri.parse(uri) }
```

- `alarm`: Android may hold an alarm a few minutes while the phone is idle, as with any app's alarm that needs no
  special permission, and a restart of the phone drops them. At most 20 at a time; more throws. Set `onAlarm` in
  `start`: when an alarm wakes Kav, Kav starts your extension first and then tells it, so the listener must be set by
  then. From any thread.
- `pickFile`: told the file chosen as a `content://` address you read with the `Context`'s `ContentResolver` while Kav
  runs, or `null` when the rider closed the picker. Called from any thread; told on the main thread.

## API 4: taking over Kav

API 4 lets an extension change anything in Kav, in two layers:

- **Named takeover points**, which keep working across Kav's updates: every main action of Kav's (Pay, opening a
  stop, starting a trip...) and every main part of its screens (Home's pay card, each ride of a trip...) has a name
  you take over, add to or replace.
- **Hooks on Kav's own code**, as an Xposed module has them: any of Kav's methods, or Android's, by name. They reach
  everything else, and may break when Kav's code changes.

Each needs `"api": 4` and a contribution with the matching capability in your manifest; without them the call throws.
Riders see it under "It can": "Takes over: paying", "Changes parts of Kav's screens: Home's pay card", and for hooks
"Changes how Kav itself works". Everything API 3 had works as before, so an extension built for API 3 needs no change.

### Actions

```java
// args: JSON text. Return true when you handled it, false to let the next extension, then Kav, do its own.
kav.action("pay", (activity, args) -> { showMyPay(activity, new JSONObject(args)); return true; });
// Later, from your own screen: Kav's own handling after all, with no extension asked.
kav.kavAction("pay", activity, args);
```

Kav calls your action on the main thread, with the screen's `Activity`, instead of doing its own. With several
extensions on one action, the one the rider switched on last goes first; one that returns `false`, or throws, passes
it to the next, then to Kav. A throw is counted by the crash guard. `null` removes your action. Kav's own handling of
`trip.open`, `trip.start`, `trip.end`, `share`, `plan`, `report`, `alert.open`, `street.open` and `place.save` is
for the screen it was asked from, so `kavAction` on one of those works while that screen is open, on what it shows.

| Name | Args | Where Kav handles it | Kav source |
|---|---|---|---|
| `pay` | a ride (below), `{}` from Home's "Pay for a ride" and the shortcuts, `{"section": "train"}` for a train's exit ticket | every Pay button, the trip's notification, the Pay shortcut and tile: `ui/RavPass.kt` `RavPass.open` | `ui/RavPass.kt:159` |
| `ticket.open` | `{"key"}` | a ticket opened from Home, a ride or the ride history: `ui/ExtActions.kt` `openTicket` | `ui/ExtActions.kt:54` |
| `stop.open` | `{"stop": "il.mot/gtfs/stop/<code>", "name"}` | a stop's board opened (Stops tab, a trip, an alert): `KavModel.openStation`, `ui/Stations.kt` | `ui/ExtActions.kt:26` |
| `line.open` | `{"number", "stop"}` (`stop` the board it was opened from, or `null`) | a line's page opened: `ui/Lines.kt` `openTimetableLine`, `KavModel.openSchedule` | `ui/ExtActions.kt:48` |
| `trip.open` | `{"trip": <the route>}` | a trip's page opened from results, Home's latest route, the trip under way, a train: `ui/DirectionsOnline.kt` `openTrip` | `ui/DirectionsOnline.kt:166` |
| `trip.start` | `{"trip"}` | a trip's Start: `ui/TripDetail.kt` | `ui/TripDetail.kt:203` |
| `trip.end` | `{"trip"}` | End trip in navigation: `ui/TripDetail.kt` (the trip notification's own End always ends it) | `ui/TripDetail.kt:226` |
| `share` | `{"trip"}` | a trip's Share: `ui/TripDetail.kt` | `ui/TripDetail.kt:205` |
| `plan` | `{"from": {"name", "lat", "lon"}, "to": {...}, "at": <seconds, 0 for now>, "way": "depart" \| "arrive" \| "last"}` | a search started: `ui/DirectionsOnline.kt`. Taken over, Kav plans nothing and closes the results | `ui/DirectionsOnline.kt:410` |
| `report` | `{"line", "agency", "origin", "destination", "at", "plate", "stops": [names]}` | a bus reported, from a trip, navigation, Live or a line: `KavModel.openReport` | `MainActivity.kt:426` |
| `alert.open` | `{"label", "ids"}` | a service alert opened: `ui/Common.kt` `ServiceAlerts` | `ui/Common.kt:580` |
| `taxi` | `{"link": "gett://..."}` | a taxi ordered: `ui/Gett.kt` | `ui/Gett.kt:56` |
| `street.open` | `{"name", "lat", "lon"}` | a stop's Street View: `ui/StreetView.kt` `openStreet` | `ui/StreetView.kt:68` |
| `place.save` | `{"places": [{"id", "name", "lat", "lon"}]}`, the whole list as it would be kept | Home's places added, edited, moved or removed: `KavModel.saveFavourites` | `MainActivity.kt:456` |
| `stop.save` | `{"stops": [keys]}` | a stop saved or removed: `KavModel.saveStops` | `MainActivity.kt:470` |
| `line.save` | `{"lines": [keys]}` | a line saved or removed: `KavModel.saveLines` | `MainActivity.kt:476` |
| `settings.open` | `{}` | Home's Settings button: `ui/Home.kt` | `ui/Home.kt:108` |
| `tab.open` | `{"tab": "directions" \| "stations" \| "lines" \| "live" \| ...}` | a tap on the tab bar: `MainActivity.kt` `TabBar` | `MainActivity.kt:1548` |
| `settings.page.open` | `{"page"}` | Opens a Settings page by ID. See the Settings parts below. | `ui/Settings.kt:266` |
| `pay.history.open` | `{"source": "home" \| "settings"}` | Opens ride history. Both entry points use the same default action. | `ui/PayHistory.kt:43` |
| `plan.stop` | `{"end": "from" \| "to", "stop", "name", "place": {"name", "detail", "lat", "lon", "type", "mode"}}` | Sets the start or destination from a ministry or extension stop board and opens the planner. The default uses the coordinates in `place`. | `ui/ExtActions.kt:29` |
| `live.show` | `{"show": "all" \| "vehicles" \| "stops"}` | Changes the Live map filter. | `ui/LiveScreen.kt:605` |
| `search.open` | `{}` | Opens Home's destination search. | `ui/DirectionsOnline.kt:682` |
| `results.place.pick` | `{"end": "from" \| "to" \| "stop", "index"}` | Opens the place picker. Omit the index to add an intermediate stop. Indices start at zero. | `ui/DirectionsOnline.kt:227` |
| `results.endpoints.swap` | `{}` | Swaps the start and destination. | `ui/DirectionsOnline.kt:784` |
| `results.stop.remove` | `{"index"}` | Removes one intermediate stop by zero-based index. | `ui/DirectionsOnline.kt:770` |
| `results.stop.stay` | `{"index", "minutes"}` | Sets a stay from 0 to 60 minutes at a zero-based intermediate stop. | `ui/DirectionsOnline.kt:766` |
| `results.time.select` | `{"at", "way": "depart" \| "arrive" \| "last"}` | Sets the requested time from the time sheet or the Earlier/Later row. Time is Unix seconds. Use 0 for now or last departure. | `ui/DirectionsOnline.kt:232` |
| `results.order.select` | `{"order"}` | Sorts the current search. See order values below. | `ui/DirectionsOnline.kt:1140` |
| `results.planner.select` | `{"source": "kav" \| "own" \| "ext:<publisher>/<dataset>", "default"}` | Selects one of the available planner tabs. With `"default": true`, results open on that tab from then on ([hooks.md](/docs/extensions/hooks#planner-tabs-and-display-settings)). | `ui/DirectionsOnline.kt:843` |
| `trip.track` | `{"trip", "leg", "tripId", "line"}` | Live location on a ride's card follows its bus. `leg` is the ride's place in the trip's `legs`, `tripId` the bus Kav found for it. | `ui/TripDetail.kt:201` |
| `nav.step` | `{"trip", "step", "current", "steps"}` | Shows one step of a trip under way: the step dots, their arrows and Current step. Steps count from zero. | `ui/Navigate.kt:406` |
| `nav.board.select` | `{"trip", "leg", "option"}` | Picks which bus to take on a wait card. Option 0 is the planned bus. | `ui/Navigate.kt:409` |
| `nav.replan` | `{"trip", "reason", "key"}` | New route from here, when the plan stopped working. | `ui/Navigate.kt:417` |
| `nav.replan.keep` | `{"trip", "reason", "key"}` | Keeps the route and closes that warning. | `ui/Navigate.kt:418` |
| `nav.plan` | `{"trip"}` | Shows the trip's plan over navigation. | `ui/TripDetail.kt:228` |
| `nav.exit` | `{"trip"}` | Leaves navigation for Home, by its back button or Back. The trip goes on. | `ui/TripDetail.kt:138` |
| `share.send` | `{"target": "copy" \| "more" \| <app package>, "name", "link"}` | A target in a trip's share sheet. `link` is `null` until Kav has made it. | `ui/ShareSheet.kt:583` |
| `pay.mode` | `{"on": <boolean>, "from": "home" \| "settings" \| "setup"}` | Paying turned on (at once, or through setup) or off (Kav asks first), from Home's offer, the Paying switch or setup. | `ui/RavPass.kt:472` |
| `pay.offer.hide` | `{}` | Hides Home's offer to set up paying, its pay button with a yellow dot. From Kav 5.2 the rider has no way to hide it, so Kav never asks an extension for this one; `kavAction` still hides the offer. | `ui/ExtActions.kt:104` |
| `pay.place.open` | `{"place"}` | Opens one of Rav-Pass's pages from Settings' Paying. See the place names below. | `ui/RavPass.kt:604` |
| `pay.history.clear` | `{"rides"}` | Clear history in Settings' Paying. Kav's own asks first. | `ui/RavPass.kt:548` |
| `ticket.ravpass.open` | Same as `ticket.header`. | Open in Rav-Pass on a Rav-Pass ticket. | `ui/TicketCard.kt:343` |
| `live.vehicle.open` | `{"key", "plate", "line", "to", "mode", "lat", "lon", "at"}` | A vehicle opened on Live: tapped on the map, in the list or on a stop's card. `key` is the one `vehicles` gives, `at` when it last said where it was (seconds). Kav's own opens its card, for a vehicle of those around. Needs `"api": 5`. | `ui/LiveScreen.kt:363` |
| `live.stop.open` | `{"stop", "name"}` | A stop tapped on Live's map. `stop` is `null` for one that isn't the timetable's. Kav's own opens its card, for a stop of those around. Needs `"api": 5`. | `ui/LiveScreen.kt:366` |
| `stop.alerts.open` | `{"stop", "name"}` | The Alerts button on a stop's board. Kav's own opens its line alerts while that board is open. Needs `"api": 5`. | `ui/Stations.kt:405` |
| `line.alerts.save` | `{"alerts": [{"id", "stop", "name", "lines", "days", "from", "to", "toward"}]}`, the whole list as it would be kept | A line alert added or deleted. `days` has bit 0 for Sunday, `from` and `to` are minutes of the day, `toward` (a train station's) the places the trains end. Kav's own keeps the list given. Needs `"api": 5`. | `MainActivity.kt:484` |
| `line.getoff` | `{"number", "operator", "stop", "name", "index"}` | "Get off here" on a line's page: the trip on board to that stop. `index` is the stop's place on the way shown, from zero. Kav's own works while that page is open. Needs `"api": 5`. | `ui/Lines.kt:1638` |
| `report.send` | `report`'s args, with `"about"`, `"issue"`, `"line"` and `"words"` | "Fill in the ministry's form" at the end of a report. Never told who reports it: the rider's name, phone, ID and photos stay in Kav's form. Kav's own opens the form while that step is open. Needs `"api": 5`. | `ui/Report.kt:939` |
| `place.pick` | `{"place": {"name", "detail", "lat", "lon", "type", "mode"}, "title"}` | A place chosen in Kav's place search (a result, a saved place, a recent one, a stop), for any end of a trip. `title` is the search's heading. Kav's own uses the place given, so `kavAction` can give another; while the search is open. Needs `"api": 5`. | `ui/Pickers.kt:223` |
| `link.open` | `{"from", "to", "toName", "at", "shared"}` | A trip link Kav was opened with (`kav://trip`, a shared trip, a `geo:` place). Each place is `{"name", "lat", "lon"}`, or `null` when the link leaves it to the rider; `at` in seconds, 0 for now; `shared` for a whole trip someone shared. Kav's own opens it in the planner. Needs `"api": 5`. | `MainActivity.kt:1065` |

The sort orders are `recommended`, `fastest`, `earliest_departure`, `earliest_arrival`, `least_transfers`,
`least_walking`, `cheapest` and `lowest_co2` (when emissions are shown). Call the default `results.*` actions
while the planner results screen is in front. `search.open` opens the destination picker from Home.

Kav's own `trip.track`, `nav.*`, `share.send`, `pay.history.clear` and `ticket.ravpass.open` act on the screen
they were asked from, while it is open. A `nav.replan` reason is `cancelled`, `gone`, `late_change` or `off_route`.
Rav-Pass's pages for `pay.place.open` are `payment_methods`, `personal_profile`, `discount_profile`,
`choose_account`, `extra_passengers`, `coupons`, `ravkav_topup` and `customer_service`. Kav's controls with no name
here are reached with hooks ([below](#hooks-into-kav-itself)).

The ride `pay` is told, from a ride's own Pay button and the trip's notification:

```json
{"section": "bus", "line": "37", "mode": 3,
 "from": {"stop": "il.mot/gtfs/stop/35368", "name": "...", "lat": 32.1, "lon": 34.9},
 "to":   {"stop": "il.mot/gtfs/stop/32653", "name": "...", "lat": 32.1, "lon": 34.9},
 "dep": 1795421100, "trip": "il.mot/gtfs/trip/..."}
```

`section` is `bus`, `train`, `lightrail` or `any`; `mode` the GTFS route type; `dep` in seconds. With an extension
on `pay`, Kav's Pay buttons show without Rav-Pass, its permissions or the ticket reader (the trip notification's Pay,
the Pay tile and the reminder to make a train's exit ticket too), and Settings' Paying says "With &lt;your name>".

### Screen parts

```java
// about: what the part shows, as JSON text. A replace that returns null shows Kav's own.
kav.part("home.pay", "after", (context, about) -> kav.ui().card(context, kav.ui().row(context, "info", "Hi", null, null)));
kav.part("trip.ride", "replace", (context, about) -> myRide(context, new JSONObject(about).getJSONObject("leg")));
```

Kav asks for the view each time the part shows, on the main thread. Views before and after a part go in the order
their extensions were switched on, first on first; of several replacements, the last switched on is shown, and when
it returns `null`, the next one's, then Kav's own. A part no extension adds to is drawn as it always is. `null` as
the card removes yours. Kav keeps the space it has between its own cards around your views, and lines them up with
its cards.

When what your view shows changes while its part is on screen, call `kav.part` again with the same name and place,
with the same card or another: Kav asks for the view again wherever that part shows. Kav 5.1 and older ask again only
when the part next shows.

| Name | About | What it is | Kav source |
|---|---|---|---|
| `home.places` | `{}` | Home's row of saved places (`ui/Home.kt`) | `ui/Home.kt:124` |
| `home.journey` | `{"trip"}` | Home's card of the trip under way | `ui/Home.kt:138` |
| `home.suggestions` | `{}` | Home's suggestions card (latest route, nearest station) | `ui/Home.kt:143` |
| `home.latest` | `{"trip"}` | the latest route inside it (`ui/HomeSuggestions.kt`) | `ui/HomeSuggestions.kt:207` |
| `home.pay` | `{}` | Home's pay card, when paying, tickets or ride history are shown | `ui/Home.kt:151` |
| `home.recent` | `{}` | Home's recent trips | `ui/Home.kt:154` |
| `trip.header` | `{"trip"}` | a trip page's top row: Back, its title, Share, Start (`ui/TripDetail.kt`) | `ui/TripDetail.kt:272` |
| `trip.map` | `{"trip"}` | its map | `ui/TripDetail.kt:294` |
| `trip.summary` | `{"trip"}` | its summary under the map | `ui/TripDetail.kt:298` |
| `trip.timeline` | `{"trip"}` | all its steps | `ui/TripDetail.kt:300` |
| `trip.ride` | `{"trip", "leg", "index"}` | each ride's card under its boarding stop, with its Pay button | `ui/TripDetail.kt:475` |
| `trip.walk` | `{"trip", "leg", "index", "metres", "minutes"}` | each walk's line | `ui/TripDetail.kt:446` |
| `results.route` | `{"trip", "index"}` | each route's card in a search's results (`ui/DirectionsOnline.kt`) | `ui/DirectionsOnline.kt:1037` |
| `stop.header` | `{"stop", "name"}` | a stop board's name, picture and buttons (`ui/Stations.kt`) | `ui/Stations.kt:363` |
| `stop.board` | `{"stop", "name"}` | its departures, as rows of their own | `ui/Stations.kt:449` |
| `line.header` | `{"number", "operator"}` | a line page's name and star (`ui/Lines.kt`) | `ui/Lines.kt:1658` |
| `settings.root` | `{}` | Settings' list of pages (`ui/Settings.kt`) | `ui/Settings.kt:263` |
| `tabbar` | `{"tab"}` | the tab bar (`MainActivity.kt`) | `MainActivity.kt:1241` |
| `settings.page.header` | `{"page", "title"}` | A Settings subpage's heading and navigation controls. | `ui/Settings.kt:277` |
| `stops.header` | `{}` | The Stops tab heading. | `ui/Stations.kt:218` |
| `stops.search` | `{"query"}` | Search field and Select on map on the Stops tab. | `ui/Stations.kt:225` |
| `lines.header` | `{}` | The Lines tab heading in both timetable modes. | `ui/Lines.kt:204` |
| `lines.search` | `{"query"}` | The line search field in both timetable modes. | `ui/Lines.kt:245` |
| `lines.filters` | `{"modes": [<GTFS route type>]}` | The mode filters in the Lines tab. An empty list selects all modes. | `ui/Lines.kt:251` |
| `live.header` | `{"showingInfo": <boolean>}` | The Live tab heading and its info button. | `ui/LiveScreen.kt:545` |
| `live.filters` | `{"show"}` | The Live map's All, Vehicles and Stops picker. Values match `live.show`. | `ui/LiveScreen.kt:599` |
| `ticket.header` | `{"key", "kind", "line", "from", "to", "validUntil"}` | The ticket heading. Optional fields are omitted, and times are in milliseconds. | `ui/TicketCard.kt:297` |
| `ticket.validity` | Same as `ticket.header`. | The expiry, fare range and payment time. | `ui/TicketCard.kt:339` |
| `ticket.ride` | Same as `ticket.header`. | The ticket's line and boarding and exit stops, when shown. | `ui/TicketCard.kt:396` |
| `pay.history.header` | `{}` | The ride history title and Back button. | `ui/PayHistory.kt:85` |
| `pay.history.summary` | `{"thisMonth", "lastMonth"}` | Monthly totals. The JSON fields count rides. | `ui/PayHistory.kt:93` |
| `pay.history.empty` | `{}` | The notice when the ride history is empty. | `ui/PayHistory.kt:110` |
| `pay.history.month` | `{"year", "month", "rides"}` | One month heading. January is 1. | `ui/PayHistory.kt:116` |
| `pay.history.ride` | `{"key", "kind", "line", "from", "to", "at", "riders", "band", "price"}` | One ride. Optional fields are omitted. Time is in milliseconds, fare range in kilometres, and price in agorot. | `ui/PayHistory.kt:127` |
| `settings.page.trips` | `{"page", "title"}` | The Trips Settings page body. | `ui/Settings.kt:284` |
| `settings.page.paying` | `{"page", "title"}` | The Paying Settings page body. | `ui/Settings.kt:284` |
| `settings.page.live` | `{"page", "title"}` | Live times and trips, on the Privacy Settings page (once its own page, Providers; opening `live` opens Privacy). | `ui/Settings.kt:191` |
| `settings.page.look` | `{"page", "title"}` | The Look Settings page body. | `ui/Settings.kt:284` |
| `settings.page.language` | `{"page", "title"}` | The Language and time body, which opens in a sheet over Settings. | `ui/Settings.kt:284` |
| `settings.page.map` | `{"page", "title"}` | The Map Settings page body. | `ui/Settings.kt:284` |
| `settings.page.privacy` | `{"page", "title"}` | The Privacy Settings page body. | `ui/Settings.kt:284` |
| `settings.page.extensions` | `{"page", "title"}` | The Extensions Settings page body. | `ui/Settings.kt:284` |
| `settings.page.kav` | `{"page", "title"}` | The Kav Settings page body. | `ui/Settings.kt:284` |
| `results.endpoints` | `{"from", "to", "stops": [{"name", "minutes"}]}` | The endpoint picker, stopovers, swap, and Back. Destination is omitted when unset. | `ui/DirectionsOnline.kt:751` |
| `results.options` | `{"at", "way", "order"}` | The time and sort controls above results. Values match the corresponding actions. | `ui/DirectionsOnline.kt:790` |
| `results.time.shift` | `{"firstDeparture"}` | The Earlier/Later row and the displayed time of the first departure. | `ui/DirectionsOnline.kt:808` |
| `results.planners` | `{"source", "sources"}` | The current and available planner tabs, by key: `kav`, `own`, or an extension's `ext:<publisher>/<dataset>`. | `ui/DirectionsOnline.kt:841` |
| `nav.header` | `{"trip"}` | Navigation's top bar: Back to Home, the time left, and Show trip plan. | `ui/Navigate.kt:440` |
| `nav.problem` | `{"trip", "reason", "key"}` | The warning when the plan stopped working, with New route from here. | `ui/Navigate.kt:468` |
| `nav.card` | `{"trip", "step", "current"}` | Each step's card under way. | `ui/Navigate.kt:520` |
| `nav.wait.details` | `{"trip", "leg", "index", "option"}` | In a step's Wait for card, after how busy the line usually is, once for each line the rider can take there. `leg` is that line's ride, `index` its place in `trip.legs`, `option` the line's place among them (0 the planned one). Kav draws nothing here itself. Needs `"api": 5`: a Kav with API 4 has no such part, and `kav.part` throws there. | `ui/Navigate.kt:876` |
| `nav.ride.details` | `{"trip", "leg", "index"}` | In a step's Ride card, after the line's row and above paying. `leg` is the ride the rider chose, `index` its place in `trip.legs`. Kav draws nothing here itself. Needs `"api": 5`: a Kav with API 4 has no such part, and `kav.part` throws there. | `ui/Navigate.kt:922` |
| `nav.banner` | `{"trip", "step", "kind", "leg", "index", "shown"}` | Under navigation's row of step dots, as wide as the row. `step` is the step the trip is on (from zero) and `kind` its kind (as in [trip events](/docs/extensions/hooks#the-riders-trips-tripshistory-and-tripsfollow-api-5)); `leg` is that step's leg, for a wait or a ride the line the rider chose, and `index` its place in `trip.legs` (`null` and `-1` for a step with no leg of its own, as a walk, the start and the end); `shown` is the step whose card the rider swiped to (Kav 5.2). Made again as the trip moves on to another step, the rider picks another line or swipes to another card. Kav draws nothing here itself: each view sits on a glass card as wide as the row of dots, since it's over the map, and with no view navigation looks as it always did. Needs `"api": 5`: a Kav with API 4 has no such part, and `kav.part` throws there. | `ui/Navigate.kt:464` |
| `ride.pay` | `{"trip", "leg", "index"}` | The pay row of a ride's card, on a trip's page, in navigation's cards and on Home's current trip card: "Pay for this ride" (or a station's) and what to do, under which Kav shows the ride's ticket instead once it has one (and then no part). `leg` is the ride, `index` its place in `trip.legs`. Replaced, your view sits in Kav's own rounded row and is all of it, so its taps are yours. Kav 5.1 and older have no such part, and `kav.part` throws there. | `ui/RavPass.kt:337` |
| `share.targets` | `{"link"}` | The share sheet's row of targets. | `ui/ShareSheet.kt:588` |
| `ticket.codes` | `ticket.header`'s, with `"code"` and `"codes"` | The ticket's codes and the line under them. `code` is the one shown, from zero. | `ui/TicketCard.kt:311` |
| `pay.setup` | `{"page": "get" \| "allow" \| "reader" \| "done"}` | The paying setup popup's page. | `ui/PaySetup.kt:137` |
| `stops.row` | `{"stop", "name", "list", "metres"}` | Each stop in the Stops tab. `list` is `saved`, `nearby` or `search`; `metres` only for `nearby`. Needs `"api": 5`. | `ui/Stations.kt:240` |
| `stop.departure` | `{"stop", "name", "departure"}` | Each departure on a stop's board, as its own row. `departure` is as `departures` gives it. Rows sit with no space between them. Needs `"api": 5`. | `ui/Stations.kt:486` |
| `stop.alerts` | `{"stop", "name"}` | Everything under the heading of a stop's line alerts. Needs `"api": 5`. | `ui/AlertSheet.kt:106` |
| `lines.row` | `{"number", "operator", "mode", "from", "to"}` | Each line in the Lines tab, in both timetable modes. Needs `"api": 5`. | `ui/Lines.kt:226` |
| `line.live` | `{"number", "operator"}` | A line page's next departures and how busy it usually is, under its name. Needs `"api": 5`. | `ui/Lines.kt:1759` |
| `line.map` | `{"number", "operator"}` | A line page's map. Needs `"api": 5`. | `ui/Lines.kt:1788` |
| `line.stop` | `{"number", "operator", "stop", "name", "index", "count"}` | Each stop on a line's page, with its card when open. `index` is its place on the way shown, from zero. Needs `"api": 5`. | `ui/Lines.kt:1942` |
| `live.list` | `{"vehicles"}` | The list under Live's map, or its note when there are none. Needs `"api": 5`. | `ui/LiveScreen.kt:822` |
| `live.vehicle` | `live.vehicle.open`'s, `{}` once it stops reporting | A vehicle's card on Live. Needs `"api": 5`. | `ui/LiveScreen.kt:761` |
| `live.stop` | `{"stop", "name"}` | A stop's card on Live. Needs `"api": 5`. | `ui/LiveScreen.kt:751` |
| `home.nearest` | `{"stop", "name", "metres"}` | The nearest station in Home's suggestions. Needs `"api": 5`. | `ui/HomeSuggestions.kt:233` |
| `results.empty` | `{"reason"}` | The note when a search shows no routes. `reason` is `none`, `filtered` (the rider's filters hide them all), `shabbat`, `error` or `too_close`. Needs `"api": 5`. | `ui/DirectionsOnline.kt:854` |
| `results.hidden` | `{"count", "reasons"}` | The row of routes the rider's rules hid, and why. Needs `"api": 5`. | `ui/DirectionsOnline.kt:927` |
| `alert.sheet` | `{"label", "ids"}` | A service alert's notices, under its heading. Needs `"api": 5`. | `ui/Common.kt:611` |
| `report.step` | `report`'s args, with `"step"` | Each page of the report form: `about`, `what`, `ride`, `more`, `you` or `check`. Needs `"api": 5`. | `ui/Report.kt:713` |
| `search.result` | `{"place", "section", "index"}` | Each result of Kav's place search. `place` is as `place.pick` gives it; `section` is `frequent` (places the rider often goes to), `recent`, `around` (busy stations around the rider's town, while nothing is typed), `places` or `stops`. Needs `"api": 5`. | `ui/Pickers.kt:443` |
| `screen.overlay` | `{"tab"}` | Over the whole of Kav's screen, under its dialogs and sheets: `"after"` only ([below](#over-the-whole-screen)). Needs `"api": 5`. | `MainActivity.kt:939` |

`{"trip"}` is the route as route rules get it ([hooks.md](/docs/extensions/hooks)); `leg` one of its `legs`, and `index` its place
in them. API 3's cards still sit where they always did, beside these parts: `home` after `home.suggestions`, `trip`
after `trip.timeline`, `stop` at the top of `stop.board`, `results` inside each `results.route` card.

The Settings page IDs are `trips`, `paying`, `live`, `look`, `language`, `map`, `privacy`, `extensions`, and `kav`.
Ticket parts supply display metadata. The passenger names and ticket codes stay in Kav's ticket view.

### Over the whole screen

The part `screen.overlay` puts your view over the whole of Kav's screen, as big as the screen, under Kav's dialogs
and sheets (they are windows of their own). Only `"after"` is taken: `"before"` and `"replace"` throw. Your view is
see-through where it draws nothing, and a touch it doesn't take (`false` from its first `onTouchEvent`, the default
of a plain `View`) goes on to Kav as if your view weren't there; one it takes is yours to the end. It isn't shown in
picture in picture, nor in safe mode. Kav makes your view again when the tab changes, with the tab shown in
`about` (`{"tab"}`, as the `tabbar` part has it). A view that wants Back while it shows takes focus (`setFocusable(true)`,
`requestFocus()`) and handles `KEYCODE_BACK` in `onKeyDown`; let go of focus when you're done, or Back stays yours.

`kav.capture(captured)`, with a contribution `screen.capture`, tells you what Kav's screen shows now, on the main
thread: a `Bitmap` the size of your overlay view, with Kav's maps in it and what your own view draws (draw nothing
while you ask, for Kav's screen alone), and its pieces as JSON text:

```json
[{"x": 48, "y": 312, "w": 984, "h": 168, "kind": "button"}, {"x": 64, "y": 560, "w": 420, "h": 52, "kind": "text"}]
```

Each box is in the picture's pixels, which are your overlay view's. `kind` is `button` (it takes a tap, a long press
or a toggle; what it holds is part of it), `field` (text can be typed in it), `card` (a surface that draws its own
background, like a card, the tab bar or a sheet; what it holds is part of it, so no empty background is left behind),
`text` or `image`, in the order the screen has them, at most 300. A piece smaller than 4 px isn't given, nor a button
or field taking more than half of the screen, or a card more than nine tenths of it (those are looked into for their
own pieces), nor one all covered by what is drawn over it, like a page open over another. The picture
is `null` and the pieces `[]` when Kav isn't in front or the picture couldn't be taken. The picture is yours:
`recycle()` it when you're done.

What you are never given: a piece's text, description or value, and what is typed. Each field's inside is painted
over in its own color in the picture, so a search, a name or a password typed in Kav isn't in it. Everything else
Kav shows is in the picture as the rider sees it. Riders are told on your extension's page that it "Sees a picture of
Kav's screen, without what you type"; say why in a reason (`"reasons": {"screen.capture": ...}`).

`kav.captureBehind(captured)` tells you the same, and with it `behind`: Kav's screen without its pieces, a second
`Bitmap` the same size, made off the screen, so nothing on Kav's screen changes while it's made. Where each piece is,
it has what Kav draws behind the piece: its background, a theme's background picture, a skin's top bar or card, and
its maps. Where that isn't what shows just around the piece (a page of one color), the piece's place is that color
instead, the one seen most around it. Everything else is the picture as it is, fields painted over too. Both are
`null` when the picture is; `behind` is yours to `recycle()` as well. It costs a little more than `capture`, so ask
for it only when you need the places pieces leave.

With `captureBehind`, the picture has only the pieces, each with only what is its own: what of a piece's box is what is
behind it (the map under a button, the background around a place's tile) is clear, and so is everything in no piece.
Kav's background, a theme's background picture and its maps are never in it, also inside a piece's outline: a
see-through tile carries nothing of what shows inside it. What is a piece's own is as Kav draws it, as clear as it is,
so a glass pane stays see-through. Drawn over `behind`, it is the screen again. Each piece's box is tight to what is its
own, and a piece with nothing of its own (a box that only lies over the map) isn't in `pieces`. So a piece you cut from
the picture by its box carries its own face and nothing of what was around it.

To move Kav's pieces about, draw `behind` over the whole of your view (that hides Kav's own screen, which stays as it
was under it), and draw each piece from the picture where it moves to. Take every touch while you do, so none reaches
Kav's screen you hide; draw nothing and take no touch when you're done.

```java
kav.part("screen.overlay", "after", (context, about) -> overlay = new MyOverlay(context));
// Later, on a shake:
kav.captureBehind((picture, behind, pieces) -> { if (picture != null) overlay.start(picture, behind, new JSONArray(pieces)); });
```

### The trip notification's buttons

```java
// manifest: a contribution with capability "trip.notification"
kav.provide("trip.notification", input -> {
    JSONObject in = new JSONObject(input);
    if (in.getString("event").equals("tap")) { quickPay(); return "null"; }   // {"event": "tap", "id"}
    // {"event": "buttons", "buttons": [{"id", "label"}], "ride": <the ride Kav's Pay would pay, or null>}
    return "{\"buttons\": [{\"id\": \"quick\", \"label\": \"QUICK PAY\"}, {\"id\": \"end\"}]}";
});
```

The buttons of the notification Kav shows while a trip is under way, on Android's live progress notification and the
older one alike. Kav asks the extension switched on last that provides `trip.notification`, each time it posts the
notification, with its own buttons in order: `pay` ("Pay", for the ride in `ride`) or `ticket` ("Show ticket"), when
paying is on, then `end` ("End trip"). The answer is the buttons to show, in order, at most three: one of Kav's by its
`id` (it keeps Kav's own words and what it does), or one of yours with an `id` and a `label`, whose tap Kav tells you as
`{"event": "tap", "id"}`. A button of Kav's you leave out isn't shown. Answer within 300 ms; with no answer, a late
one or one Kav can't read, Kav shows its own. When your buttons change (a ride kept, then paid), call
`kav.provide("trip.notification", ...)` again: Kav posts the notification again at once. Kav 5.1 and older never ask,
and show their own buttons.

### Tickets

```java
kav.addTicket(new JSONObject()
    .put("key", "t1").put("kind", "BUS").put("line", "37").put("from", "boarding stop").put("to", "destination")
    .put("boughtAt", now).put("validUntil", now + 90 * 60_000L).put("riders", 1).put("price", 800)
    .put("number", "UTN reference").put("codes", new JSONArray().put("<QR text per passenger>")).put("needsExit", false)
    .toString());
```

The ticket goes into Kav's own tickets, as one Kav read from Rav-Pass does: on Home's pay card, on its ride, as the
live ticket notification, in Settings › Paying › Ride history and Home's "This month". From any thread. `key` is
your own id for it; Kav keeps it as `"ext:<your id()>:<key>"`, the key `ticket.open` names, and the same key again
updates it. `kind` is `BUS`, `TRAIN` or `LIGHT_RAIL`; a train ticket with `needsExit` is an entrance, which the next
bus's Pay asks about. Times are in milliseconds; `price` in agorot. Each code is a passenger's QR code, drawn by
Kav. It ends at `validUntil`, and stays when your extension is switched off or removed. A ticket that isn't right
(no key, another kind, `validUntil` not after `boughtAt`) throws.

### Hooks into Kav itself

List each function you hook in the manifest, and hook it by that name:

```json
"contributions": [{ "id": "minutes", "capability": "hooks" }],
"hooks": [{ "class": "uk.noammm.kav.ui.CommonKt", "method": "durMinutes", "params": ["int"] }]
```

```java
Kav.Unhook unhook = kav.hook("uk.noammm.kav.ui.CommonKt", "durMinutes", new String[] {"int"},
    call -> { /* before: may change call.args(), or call.setResult(...) to skip the original */ },
    call -> call.setResult(call.result() + " (hooked)"));
if (!kav.hooked("uk.noammm.kav.ui.CommonKt", "durMinutes", "int")) kav.log("not in this Kav");
```

`method` is `"<init>"` for a constructor; `params` are Java's names for the types (`"int"`, `"java.lang.String"`,
`"int[]"`). Declared, your hooks can't break your extension when Kav changes:

- Kav looks up every declared function as your code starts. One this Kav doesn't have is left out: nothing is
  thrown, nothing counts against your extension, and its row in Settings › Extensions says "Needs an update for
  this Kav". `hook` by name does the same for one it can't find or hook. `hooked` tells you whether it took.
- `kavx check` holds your declared hooks against the functions a Kav release has (`api/hookable/<versionCode>.txt.gz`,
  the newest by default, `--kav <versionCode>` for another), and warns about each one it doesn't have.
- Kav's own release gate keeps every function that extensions on its community list and the kit's samples declare:
  a Kav that loses one fails its tests, and keeps the old function or offers a named action or part in its place.
  `kavx hooks <file.kavb>` prints yours for that list. When building a release list, pass the current community
  bundles and the kit's sample bundles after the version code to `node tools/hookable.js <apk> <versionCode>`.
  Missing methods in the release APK fail the command before it writes the version's list.

`kav.hook(member, before, after)` takes a `Member` you found yourself (with `kavClassLoader()`), for Android's own
methods, and throws when it can't be hooked; your own reflection that fails throws in your code too, which the crash
guard counts. Prefer the name.

`before` runs before the method and may set a result or an error, which skips the method; `after` runs after it and
may change its result or error. Either may be `null`. `call.thisObject()` is the object it was called on (`null`
for a static method), `call.args()` its arguments, `call.callOriginal(args...)` the method itself without the hooks.
A constructor can be hooked too. With several hooks on one method, befores go in the order they hooked and afters
the other way round. Each call of yours goes through the crash guard: one that throws is counted, and the method
carries on without it. `hook` throws when the method can't be hooked.

- Kav starts the hook engine the first time an extension hooks, so a Kav without such an extension pays nothing for
  it. It is LSPlant (LGPL-3.0) with Dobby (Apache-2.0), for Android 8 to 17, on arm64 and x86_64 phones; Kav's
  `android/hook/build.sh` builds it.
- Kav keeps its class and method names in its release builds, so you can find them by name. R8 still inlines small
  methods into their callers, so those calls may be absent from the APK. `kav.deoptimize(caller)` handles inlining
  by ART at runtime. To hook a call R8 already inlined, choose a method whose call remains in the release build.
- Kav's code changes from release to release. A hook is the right tool for what no action or part reaches, and the
  wrong one for what they do: check it on every new Kav, and give your rider a way back (switch it off).
- Your hooks go when your extension is switched off, blocked or removed, and none load in safe mode.

## The crash guard and safe mode

Your code runs inside Kav, so a crash of yours is Kav's crash. Kav counts each one against the extension that
caused it:

- **A crash in a call Kav makes** (`start`, a card's or screen's `create`, a hook, a map tap, a stop look, a settings
  or place callback) is caught. Kav carries on: the card or screen shows nothing, the hook is skipped. It is counted.
- **Any other crash** (a tap on one of your own views, a thread of your own) closes Kav. Before it closes, Kav
  writes it down against the extension whose classes are in the crash's stack.
- **A hang counts too.** A call Kav makes on the main thread (a card's or screen's `create`, a map tap, a stop look)
  that is still running after 3 seconds is counted as a crash, because Android's "not responding" would close Kav with
  nothing to catch. `start` runs on a thread of its own and Kav waits 5 seconds for it before going on without it;
  a start that never finished (Kav closed during it) is counted at the next start.
- **2 crashes within 5 minutes, and at the next start your extension is switched off** before any of its code
  loads. Kav says which extension it turned off and why ("It made Kav close twice"). The rider turns it on again on
  its page in Settings › Extensions; its count starts again.
- **Switched off, removed or blocked** while Kav runs, your extension stops being called at once: its cards,
  screens, hooks and map shapes go. What your code started on its own (a thread, a listener) runs until Kav closes,
  so stop your own work when your views go away.
- **Safe mode** starts Kav with every extension off, so a crash can never leave Kav unable to start. The rider turns
  it on under Settings › Extensions › Options ("Safe mode", from the next time Kav opens), and Kav turns it on by
  itself after closing with an error 3 times within 10 minutes. Your code never loads in safe mode.

`samples/native/crash-test` has a Home card whose button throws, to see the guard work: tap it twice, open Kav
again, and Kav says it turned the extension off.

## Seeing logs

- **`kav.log(text)`** keeps a line (at most 4,000 characters) in your extension's log, with the failures of your
  code (a hook that threw or was late, and why). Kav keeps the newest 200. The rider sees them under "Log" on your
  extension's page in Settings › Extensions.
- **logcat.** Kav writes to these tags: `KavNative` (your extension started, and how many cards and screens it
  registered), `KavNativeGuard` (a crash of yours, and an extension switched off), `KavExtHooks` (a hook that failed),
  `KavExt` (loading). Your own `android.util.Log` lines show there too:

```
adb logcat -s KavNative KavNativeGuard KavExtHooks KavExt MyTag
```

A crash's stack trace is in logcat under `AndroidRuntime`.

## Trying it

Try it in Kav itself, on a phone or an emulator with Kav 5 or newer ([testing.md](/docs/extensions/testing)):

1. `kavx bundle <folder>`.
2. Copy the `.kavb` to the device (`adb push hello-kotlin.kavb /sdcard/Download/`).
3. In Kav: Settings › Extensions › Sources › + › Add a bundle file, and pick it. The sheet lists your extension and what
   it can do, first "Runs its own Android code: full access". Add.
4. Switch your extension on in Settings › Extensions. It starts at once. Your Home card shows on Home, with your
   extension's name as its title; your screens open from your extension's page. logcat says
   `KavNative: <id>: started, 1 cards, 1 screens`.

A bundle file never updates, and each build is a different file, so each is a different source to Kav. Remove the
old source before you add the new build.

## The samples

In `samples/native/`, each built with `kavx bundle samples/native/<name>`:

| Sample | Shows |
|---|---|
| `speed-card` | a Home card made of Kav's components and a view of its own, the GPS, a notification, a settings screen |
| `map-pins` | a marker, a line and an area on Kav's maps, a tap, and its own look for Kav's stops |
| `native-rules` | a trip planner, three route rules and a stop board |
| `my-home` | a Home body and a tab of its own |
| `crash-test` | a button that crashes Kav, to see the crash guard switch it off |
| `pay-sample` | API 4: takes over Kav's Pay buttons with a pretend ticket, which it puts in Kav's own tickets and opens there |
| `parts-sample` | API 4: a row after Home's pay card, and each ride of a trip drawn its own way |
| `hook-sample` | API 4: a hook on one of Kav's own methods, found by name |
| `planner-tab` | API 5: a planner in a results tab of its own, with live times, a bus to follow, a ride's alert, stop photos and the rider's display settings |
| `trip-companion` | API 5: follows your trips and reads your trip history once you allow each, adds a line to the wait and ride cards, and a banner under navigation's step dots |
| `shuttle-widgets` | API 5: two home screen widgets, the next departures of an invented shuttle from the stop chosen in its settings, and the rider's recent trips, a tap from planning one again |
| `spots-sample` | API 5: parts on a line's page, a stop's board, Home and Live, an action of Live's taken over, and Kav's timetable and live vehicles read with `stops`, `vehicles` and `departures` |

The starter, `starter-kotlin/`, is a Home card with a button and a dialog, and a screen of its own.

</div>
