---
title: "For your agent"
editLink: false
---

<div v-pre>

> This page as plain text, to give an AI coding agent: <a href="/docs/extensions/agent.txt">https://kavapp.link/docs/extensions/agent.txt</a>

# For your agent

Everything an AI coding agent needs to build an extension for Kav, the Android transit app for Israel, in one file. Nothing else is needed. The same file as a page: https://kavapp.link/docs/extensions/agent. For Kav 5 and newer, API level 3.

## What a Kav extension is

- Android code, in Kotlin or Java, compiled into one `classes.dex` that Kav loads and runs inside itself, with full access: it can do anything Kav can. There is no other kind.
- A folder with a `manifest.json` (what it is, what it adds, its settings and the words riders see) and that dex. Its entry class implements `kav.kavapp.api.KavExtension`.
- Published as a source: a public GitHub repository whose latest GitHub Release carries one bundle file (`.kavb`) with all its extensions. Riders add the repository in Kav, under Settings, Extensions, Sources, or with a link: `https://kavapp.link/add-source?github=OWNER/REPO`. A rider can also add a `.kavb` file from the phone.
- Each extension comes in off. It runs once the rider switches it on, and then each time Kav starts.

## Rules

- Declare what the extension does in its manifest, and nothing more. Riders see it on the extension's page.
- You are the sole author and publisher of your extension and fully responsible for it.
- You must have the right to use every service, API and piece of data your extension touches, and follow their terms.
- Don't present the extension as made, endorsed or approved by Kav, or use Kav's name or logo as your branding. "for Kav" in a description is fine.
- Kav can block an extension found stealing, spreading malware, impersonating someone or abusing a bug in Kav.

## What you need

- Kav's API jar: https://kavapp.link/docs/extensions/kav-api.jar (a few KB). Or compile the four Java files under "The API" below yourself.
- A JDK, the Android SDK (its `android.jar` and `d8`) and, for Kotlin, the Kotlin compiler.
- The developer kit's `kavx` command line does every step (check, bundle, verify): `git clone https://github.com/ImNoammm/kav-extension-kit`, then `npm link` in its folder puts `kavx` on your PATH. Without it, follow "Kotlin and Java" and "The bundle format" below by hand.
- A phone or an emulator with Kav 5 or newer to try it.

## Kotlin and Java extensions

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

### What you need

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

### From nothing to a bundle

```
cp -r starter-kotlin ~/hello-kotlin
kavx check ~/hello-kotlin
kavx bundle ~/hello-kotlin
```

`kavx check` builds the folder and prints what Kav and riders would see, or why Kav would refuse it. `kavx bundle`
builds it the same way and writes `hello-kotlin.kavb`, a bundle with this one extension in it. For a source with
several extensions, put each in its own folder under `extensions/` and run `kavx bundle` on the folder above
([format.md](https://kavapp.link/docs/extensions/format)).

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

### The manifest's native part

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

The rest of the manifest is in [format.md](https://kavapp.link/docs/extensions/format). What you register in code only shows when the manifest
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
| `kav.provide("stop.photo", ...)` | a contribution `stop.photo` ([hooks.md](https://kavapp.link/docs/extensions/hooks#stop-photos-stopphoto)) |
| `kav.prefs()` | `"api": 5` |
| `kav.trips()` | `"api": 5` and a contribution `trips.history` ([hooks.md](https://kavapp.link/docs/extensions/hooks#the-riders-trips-tripshistory-and-tripsfollow-api-5)) |
| `kav.onTrip(...)` | `"api": 5` and a contribution `trips.follow` (the same) |
| `kav.widget("next", ...)`, `kav.refreshWidget("next")` | `"api": 5`, a contribution `widget.provide` and the widget `{"id": "next", ...}` under `widgets` ([screens.md](https://kavapp.link/docs/extensions/screens#home-screen-widgets)) |
| `kav.part("screen.overlay", "after", ...)` | `"api": 5` and a contribution `part.screen.overlay` ([below](#over-the-whole-screen)) |
| `kav.capture(...)`, `kav.captureBehind(...)` | `"api": 5` and a contribution `screen.capture` (the same) |
| `kav.settings()`, `kav.onSettings(...)` | `settings` ([screens.md](https://kavapp.link/docs/extensions/screens)) |
| `kav.map()`, `kav.settingsScreen(...)`, notifications, location | nothing, but say so in `adds` so riders are told |

### The API

Everything is in `kav.kavapp.api`, and its sources are in `api/src/`. A newer Kav keeps every method of an older
one; `kav.apiVersion()` says which level the Kav in front of you has (Kav 5.0 has 3, Kav 5.1 has 4, Kav 5.2 has 5). Plain
types only: text, numbers, JSON as text, and Android's own `Context`, `View` and `Bitmap`.

**When it runs.** Kav calls `start` once each time Kav starts, and at once when the rider switches the extension on
or an update of it arrives: no restart is needed. Never in safe mode.

**Threads.** `start` runs on a background thread. Kav asks a card or screen for its view on the main thread, each
time the screen shows it. Every `KavUi` method must be called on the main thread. Hooks, `onSettings` and a widget's
`content` and `tapped` are called on a thread of Kav's, never the main one. `KavMap` may be called from any thread; its tap and stop-look calls come on
the main thread.

#### KavExtension

```kotlin
class Hello : KavExtension {
    override fun start(kav: Kav) {
        // register everything here
    }
}
```

`start(kav)` may throw: Kav catches it, counts it as a crash (below) and carries on without your extension.

#### Kav

| Method | What it gives |
|---|---|
| `Kav.API` | the API level this jar was built for (5) |
| `apiVersion()` | the API level of the Kav that loaded you |
| `context()` | the application's `Context` |
| `id()` | your identity: your source and your name, `"github.<owner>.<repo>/<name>"`, or `"file.<digest>/<name>"` for a bundle added from a file |
| `locale()` | Kav's language, `"en"` or `"he"` |
| `prefs()` | API 5: the rider's display settings as JSON text, `{"stopNames": "en", "clock": 24, "locale": "en"}` ([hooks.md](https://kavapp.link/docs/extensions/hooks#planner-tabs-and-display-settings)) |
| `trips()` | API 5: the rider's recent trips as JSON text, `null` until the rider allows it ([hooks.md](https://kavapp.link/docs/extensions/hooks#the-riders-trips-tripshistory-and-tripsfollow-api-5)) |
| `onTrip(listener)` | API 5: told as the trip the rider follows starts, moves from step to step and ends, once the rider allows it (the same) |
| `widget(id, widget)` | API 5: a home screen widget's content, as JSON text Kav draws, and its taps ([screens.md](https://kavapp.link/docs/extensions/screens#home-screen-widgets)) |
| `refreshWidget(id)` | API 5: asks Kav to ask that widget for its content again now |
| `stops(query)` | API 5: stops from Kav's timetable on the phone, nearest first or by name ([below](#kavs-timetable-live-vehicles-and-ride-history)) |
| `vehicles(query)` | API 5: the live vehicles of a line, or around a place, as Kav's Live tab has them (the same) |
| `departures(query)` | API 5: a stop's next departures, as its board in Kav shows them (the same) |
| `addRides(rides)` | API 5: rides already over, into Kav's ride history (the same) |
| `plan(query)` | API 5: Kav's own planner, its routes each with a `ref` ([hooks.md](https://kavapp.link/docs/extensions/hooks#kavs-own-planner-kavplan-resultsadd-and-kavswitchtrip-api-5)) |
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

**Cards.** Kav puts each card where its slot is ([screens.md](https://kavapp.link/docs/extensions/screens)).
Kav calls `create` each time the screen shows the card, on the main thread, so make the view quickly and load
anything slow on a thread of your own.

**Settings** are declared in the manifest: `text`, `number`, `toggle`, `choice`, and the ones Kav picks with its own
pickers, `place`, `stop`, `time`, `date` and `color`, drawn by Kav on your extension's page
([screens.md](https://kavapp.link/docs/extensions/screens#settings)).

**Hooks** (`provide`) take JSON text and answer JSON text. [hooks.md](https://kavapp.link/docs/extensions/hooks) has every field:

| Name | Input | Answer |
|---|---|---|
| `walk.time`, `transfer.policy`, `results.filter`, `results.rank`, `results.annotate` | the search: `{"query", "routes", "walks", "transfers"}` | an array, one entry per walk, change or route |
| `plan.provide` | `{"from": [lat, lon], "to": [lat, lon], "at", "way", "modes", "settings"}` | a plan, or `null` for no routes |
| `board.stops` | `{}` | `{"stops": [...], "lines": {...}}` |
| `board.departures` | `{"stop", "from", "limit"}` | an array of departures |
| `results.add` | API 5: the search, `{"query", "routes", "trip"}` | `[{"ref", "start", "walks"}]`: routes from `plan`, first in the results ([hooks.md](https://kavapp.link/docs/extensions/hooks#kavs-own-planner-kavplan-resultsadd-and-kavswitchtrip-api-5)) |

A route rule is asked within the search's shared budget (400 ms for all rules together, at most 150 to 200 ms
each); a planner and a stop board within 30 seconds (`board.stops` 60). A hook that throws, answers late or answers
something Kav can't read is skipped and written to your log. A hook that throws also counts as a crash.
`samples/native/native-rules` has a planner, three route rules and a stop board.

#### KavUi: Kav's own components

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
- `blocks` throws when Kav can't read the tree ([screens.md](https://kavapp.link/docs/extensions/screens#blocks) says what that means). Inside a
  card's or screen's `create`, an uncaught throw counts as a crash.

#### KavMap: Kav's maps

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

#### Kav's timetable, live vehicles and ride history

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

#### Alarms and files

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

### API 4: taking over Kav

API 4 lets an extension change anything in Kav, in two layers:

- **Named takeover points**, which keep working across Kav's updates: every main action of Kav's (Pay, opening a
  stop, starting a trip...) and every main part of its screens (Home's pay card, each ride of a trip...) has a name
  you take over, add to or replace.
- **Hooks on Kav's own code**, as an Xposed module has them: any of Kav's methods, or Android's, by name. They reach
  everything else, and may break when Kav's code changes.

Each needs `"api": 4` and a contribution with the matching capability in your manifest; without them the call throws.
Riders see it under "It can": "Takes over: paying", "Changes parts of Kav's screens: Home's pay card", and for hooks
"Changes how Kav itself works". Everything API 3 had works as before, so an extension built for API 3 needs no change.

#### Actions

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
| `results.planner.select` | `{"source": "kav" \| "own" \| "ext:<publisher>/<dataset>", "default"}` | Selects one of the available planner tabs. With `"default": true`, results open on that tab from then on ([hooks.md](https://kavapp.link/docs/extensions/hooks#planner-tabs-and-display-settings)). | `ui/DirectionsOnline.kt:843` |
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

#### Screen parts

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
| `nav.banner` | `{"trip", "step", "kind", "leg", "index", "shown"}` | Under navigation's row of step dots, as wide as the row. `step` is the step the trip is on (from zero) and `kind` its kind (as in [trip events](https://kavapp.link/docs/extensions/hooks#the-riders-trips-tripshistory-and-tripsfollow-api-5)); `leg` is that step's leg, for a wait or a ride the line the rider chose, and `index` its place in `trip.legs` (`null` and `-1` for a step with no leg of its own, as a walk, the start and the end); `shown` is the step whose card the rider swiped to (Kav 5.2). Made again as the trip moves on to another step, the rider picks another line or swipes to another card. Kav draws nothing here itself: each view sits on a glass card as wide as the row of dots, since it's over the map, and with no view navigation looks as it always did. Needs `"api": 5`: a Kav with API 4 has no such part, and `kav.part` throws there. | `ui/Navigate.kt:464` |
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

`{"trip"}` is the route as route rules get it ([hooks.md](https://kavapp.link/docs/extensions/hooks)); `leg` one of its `legs`, and `index` its place
in them. API 3's cards still sit where they always did, beside these parts: `home` after `home.suggestions`, `trip`
after `trip.timeline`, `stop` at the top of `stop.board`, `results` inside each `results.route` card.

The Settings page IDs are `trips`, `paying`, `live`, `look`, `language`, `map`, `privacy`, `extensions`, and `kav`.
Ticket parts supply display metadata. The passenger names and ticket codes stay in Kav's ticket view.

#### Over the whole screen

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

#### The trip notification's buttons

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

#### Tickets

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

#### Hooks into Kav itself

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

### The crash guard and safe mode

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

### Seeing logs

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

### Trying it

Try it in Kav itself, on a phone or an emulator with Kav 5 or newer ([testing.md](https://kavapp.link/docs/extensions/testing)):

1. `kavx bundle <folder>`.
2. Copy the `.kavb` to the device (`adb push hello-kotlin.kavb /sdcard/Download/`).
3. In Kav: Settings › Extensions › Sources › + › Add a bundle file, and pick it. The sheet lists your extension and what
   it can do, first "Runs its own Android code: full access". Add.
4. Switch your extension on in Settings › Extensions. It starts at once. Your Home card shows on Home, with your
   extension's name as its title; your screens open from your extension's page. logcat says
   `KavNative: <id>: started, 1 cards, 1 screens`.

A bundle file never updates, and each build is a different file, so each is a different source to Kav. Remove the
old source before you add the new build.

### The samples

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


## The API

Kav's own sources for `kav.kavapp.api`, as they are in Kav 5. Every interface and method an extension can call.

### `kav/kavapp/api/Kav.java`

```java
package kav.kavapp.api;

import android.content.Context;

/**
 * What Kav gives a native extension. Plain types only: text, numbers, Android's own Context and View. Anything else
 * (location, notifications, the camera, files, the network) the extension does with Android itself: it runs inside
 * Kav, with Kav's permissions.
 */
public interface Kav {
    /** This API's version. A newer Kav keeps every method of an older one. 2: map, provide, home, onSettings, pickPlace, settingsScreen.
     *  3: KavUi's multi-line textField, iconButton and pages. 4: action, kavAction, part, addTicket, hook, hooked, deoptimize, kavClassLoader. 5: prefs, trips, onTrip, widget, refreshWidget, stops, vehicles, departures, addRides, capture, captureBehind,
     *  plan, switchTrip, alarm, onAlarm, pickFile. */
    int API = 5;

    int apiVersion();

    /** The application's Context. */
    Context context();

    /** This extension's identity ("publisher/name") and Kav's language ("en" or "he"). */
    String id();

    String locale();

    /** API 5. Rider display settings as JSON: stopNames ("en" or "he"), clock (12 or 24), and locale. */
    default String prefs() { return "{}"; }

    /** API 5. The rider's recent trips, newest first, as Kav's Recent trips list has them: a JSON array of {"from", "to",
     *  "stopovers", "at", "planner"}. Each place is {"name", "detail", "lat", "lon"}; from is null for a trip from where
     *  the rider was; at is when it was searched, in seconds since 1970; planner is the extension that planned it
     *  ("ext:<publisher>/<dataset>"), "" for Kav's. null until the rider allows it on the extension's page. Needs a
     *  contribution with capability "trips.history"; without it, it throws. */
    default String trips() { return null; }

    /** API 5. Told about the trip the rider follows (Start on a trip's page), as JSON text: {"event", "trip", "from",
     *  "to", "step", "eta", "reason"}. event: "start", "step" (the rider moved on to another step), "replan" (a new route
     *  on the way), "eta" (Kav's estimate of when the trip gets there moved to another minute, also with Kav in the
     *  background) or "end" (reason "arrived", or "ended" when the rider ended it before). eta: when the trip gets there
     *  as Kav estimates it now, in seconds since 1970, the trip notification's "Arrive". A trip already under way is told
     *  at once, as "start". Only while the rider allows it on the extension's page; on a thread of Kav's, never the main
     *  one. null stops it. Needs a contribution with capability "trips.follow"; without it, it throws. */
    default void onTrip(TripListener listener) {}

    /** API 5. A home screen widget's content, for a widget the manifest declares in "widgets" (with a contribution with
     *  capability "widget.provide"; without it, it throws). Kav owns the widgets on the home screen; the rider places one
     *  and chooses this one for it. Kav asks for its content when it shows, refreshes or the rider opens Kav, on a thread
     *  of Kav's, never the main one, and draws it in its own look. null stops it. */
    default void widget(String id, Widget widget) {}

    /** API 5. Asks Kav to ask a widget of this extension for its content again now (its data changed). From any thread. */
    default void refreshWidget(String id) {}

    /** API 5. Stops from Kav's timetable on the phone. query, as JSON text: {"near": [lat, lon], "name", "mode", "code",
     *  "limit"}, each optional; the stops that match all given, nearest first with near, else by name, at most limit (20
     *  by default, 200 at most; with a mode other than bus, no cap). mode is the GTFS route type of a stop's lines (a
     *  train station 2). Answers a JSON array of {"stop", "code", "name", "nameHe", "city", "lat", "lon", "mode",
     *  "platform"}, name as Kav shows it and nameHe the timetable's own, with "metres" when near is given; null until the
     *  timetable has loaded. From any thread. */
    default String stops(String query) { return null; }

    /** API 5. The live vehicles Kav has, as its Live tab and a line's page show them. query: {"line": "<number>"}, every
     *  vehicle on the routes with that number, or {"near": [lat, lon], "km"}, those around a place; both, the line's
     *  nearest the place first. Answers a JSON array of {"key", "plate", "route", "line", "to", "agency", "mode", "lat",
     *  "lon", "bearing", "speed", "at"}, with "metres" when near is given; null when
     *  Kav's live source can't be reached. It asks the network: never on the main thread, where it throws. */
    default String vehicles(String query) { return null; }

    /** API 5. A stop's next departures, as its board in Kav shows them now. query: {"stop": "il.mot/gtfs/stop/<code>",
     *  "limit"} (20 by default, 100 at most). Answers a JSON array of {"line", "to", "mode", "platform", "at", "live",
     *  "cancelled"}, times in seconds since 1970, live null for one with no live time; null when Kav doesn't know the
     *  stop or its timetable hasn't loaded. It asks the network: never on the main thread, where it throws. */
    default String departures(String query) { return null; }

    /** API 5. Rides already over, into Kav's ride history (Settings › Paying › Ride history, Home's "This month"): a JSON
     *  array of {"key", "kind", "line", "from", "to", "at", "riders", "band", "price"}, kind "BUS", "TRAIN" or
     *  "LIGHT_RAIL", at in milliseconds, band the fare's reach in km, price in agorot. The same key again updates that ride;
     *  {"key", "removed": true} takes that ride of this extension out (one its pay service cancelled). Kav keeps the key as
     *  "ext:<id()>:<key>", as with addTicket. An extension installed from a bundle file gets a new id() with each file, so
     *  for it a key also covers the rides an earlier bundle file of the same name left under that key. A ride that isn't
     *  right throws. From any thread. */
    default void addRides(String rides) {}

    /** API 5. A picture of Kav's screen as it shows now, and where its pieces are on it, for an extension that draws over
     *  the screen (the part "screen.overlay"). Needs a contribution with capability "screen.capture"; without it, it
     *  throws. Told on the main thread: the picture, the size of the screen.overlay part's views, with Kav's maps and
     *  what those views draw in it; and pieces, a JSON array of {"x", "y", "w", "h", "kind"}, the box of each piece in
     *  the picture's pixels, kind "button", "field", "card" (a surface with its own background, whole with what it holds),
     *  "text" or "image". Never a piece's text, and never what is typed:
     *  each field's inside is painted over in the picture. null and "[]" when Kav isn't in front or the picture couldn't be taken. The picture is yours: recycle it
     *  when you're done. From any thread. */
    default void capture(Captured captured) {}

    /** API 5. As capture, and with it behind: the same screen without its pieces, made off the screen, so nothing on it
     *  changes. Where a piece is, what Kav draws behind it there: its background, a theme's background picture, a skin's
     *  top bar or card, its maps; or, where that isn't what shows around the piece (a page of one color), the color seen
     *  most around it. Elsewhere it is the picture as it is. The same size as the picture, and also yours to recycle. The
     *  picture then has only the pieces, each with only what is its own: what of its box is what is behind it (the map,
     *  the background around a button) is clear, as is all that is in no piece. Kav's background, a theme's background
     *  picture and its maps are never in it, also inside a piece's outline (a see-through tile); what is a piece's own is
     *  as Kav draws it, as clear as it is. Drawn over behind, it is the screen. Each piece's box is tight to what is its
     *  own, and a piece with nothing of its own (a box only over the map) isn't given. null, null and "[]" as with
     *  capture. From any thread. */
    default void captureBehind(CapturedBehind captured) {}

    /** API 5. Kav's own trip planner, as Kav's search asks it. query, as JSON text: {"from": [lat, lon], "to": [lat, lon],
     *  "at", "way", "settings", "modes"}: at in seconds since 1970 (0 or none for now), way "d" to leave at it or "a" to
     *  get there by it, settings the planner settings' four letters (pace s/n/f, walking l/n/m, changes n/f, time at the
     *  stop t/n/r; the rider's own by default), modes the route types (all by default). Answers {"routes": [...]}, each
     *  route as route rules get it with a "ref" Kav keeps it by for 15 minutes (for "results.add" and switchTrip); no
     *  routes when it found none; null when the planner can't be reached. It asks the network: never on the main thread,
     *  where it throws. A query that isn't right throws. */
    default String plan(String query) { return null; }

    /** API 5. The trip under way goes on with a route plan() gave, from where one of its rides ends, as Kav's own New
     *  route from here does: change, as JSON text, {"ref", "after", "dep", "arr", "start", "walks"}, after the ride's
     *  index in the trip's legs, dep and arr the trip's own as onTrip gives them, start and walks (optional) the route
     *  laid out as for "results.add". True when it was switched; false for a ref it doesn't know, another trip, a ride
     *  the rider is past, walks that don't reach the ride in time, stops on the way still ahead, or Kav's screen not
     *  running. Only while the rider allows following trips; needs a contribution with capability "trips.follow",
     *  without it, it throws. From any thread. */
    default boolean switchTrip(String change) { return false; }

    /** API 5. Wakes Kav at atMillis (milliseconds since 1970), with Kav closed too, and tells onAlarm's listener id once
     *  this extension has started. The same id again moves it; atMillis of 0 or less takes it out. Android may hold it a
     *  few minutes while the phone is idle, and a restart of the phone drops them. At most 20 at a time; more throws. From
     *  any thread. */
    default void alarm(String id, long atMillis) {}

    /** API 5. Told each of its alarms by id, on a thread of Kav's, never the main one. null stops it. */
    default void onAlarm(Alarm listener) {}

    /** API 5. Android's picker over Kav's screen: its photo picker when types are all images ("image/*"), else its file
     *  picker for those MIME types (all files when types is null or empty). Told the file chosen as a content:// address
     *  this extension can read with the Context's ContentResolver while Kav runs, or null when the rider closed it. Called
     *  from any thread; told on the main thread. */
    default void pickFile(String[] types, Picked picked) {}

    /**
     * A card on one of Kav's screens: "home", "results" (a line under each route of a search's results), "trip" or
     * "stop" (the manifest declares it as a contribution "slot.home", "slot.results", "slot.trip" or "slot.stop"). Kav
     * asks the card for a view each time the screen shows it.
     */
    void card(String on, Card card);

    /** A screen of its own, for a page the manifest declares (opened from its page in Settings › Extensions). */
    void screen(String page, Screen screen);

    /** Its Home body, for a manifest that declares "home.body" and the page "home": shown when the rider chooses it as
     *  their Home. A tab is a screen whose page the manifest marks "tab": true. */
    void home(Screen screen);

    /** Kav's maps: its markers, lines and areas, and its look for Kav's stops. */
    KavMap map();

    /**
     * A hook Kav calls, for a contribution the manifest declares. Input and answer are JSON text:
     *   route rules ("walk.time", "transfer.policy", "results.filter", "results.rank", "results.annotate"): the search,
     *     {"query", "routes", "walks", "transfers"}; the answer is an array, one item for each walk, transfer or route.
     *   "plan.provide": {"from": [lat, lon], "to", "at", "way", "modes", "settings"}; the answer is a plan (Plan v2), or
     *     null for no routes.
     *   "board.stops": {} answered with {"stops", "lines"}; "board.departures": {"stop", "from", "limit"} answered with
     *     an array of departures.
     *   API 5, "results.add": once Kav's own planner's routes are on screen, {"query", "routes", "trip"} (query has
     *     "reason": "search", or "replan" for New route from here with the trip under way); answered with
     *     [{"ref", "start", "walks"}]: routes from plan() put first, their walks before the first ride from start (in
     *     seconds since 1970) taking the seconds walks gives each. Within 20 seconds.
     * A route's legs have, from API 5, "liveDep" and "liveArr" (a ride's live times at the stops it's boarded and left
     * at, null without them) and "turns" (a walk's turns: {"way", "street", "metres", "lat", "lon"}).
     * Called on a thread of Kav's, never the main one, within the hook's deadline.
     */
    void provide(String name, Hook hook);

    /** Its settings as the rider set them, as JSON text ({} when it declares none). */
    String settings();

    /** Told each time the rider changes one of its settings, with all of them as settings() gives them, on a thread of
     *  Kav's, never the main one. null stops it. */
    void onSettings(SettingsChanged changed);

    /** Kav's own place search over the screen, the one the rider plans trips with (suggestions, their saved places, a pick on
     *  the map): told the place chosen, as JSON text ({"name", "lat", "lon"}), or null when the rider closed it. Called
     *  from any thread; told on the main thread. */
    void pickPlace(Picked picked);

    /** Its own settings view, for pickers and looks Kav doesn't have: shown on its page in Settings › Extensions, under
     *  the settings its manifest declares (or alone, with none declared), each time the page shows. args: {}. */
    void settingsScreen(Screen screen);

    /** A line in its log (Settings › Extensions, its page). */
    void log(String text);

    /** Kav's own look: Kav's components as views, and its theme as values. */
    KavUi ui();

    // API 4. Each of these needs "api": 4 in the manifest, and the contribution it names; without them it throws.

    /** Take over an action (manifest: a contribution with capability "action.<name>"): Kav calls it instead of its own, on
     *  the main thread with the screen's Activity. args: JSON text. Return true when you handled it, false to let the next
     *  extension, then Kav, do its own. null removes it. With several on one action, the last switched on goes first. */
    void action(String name, Action action);

    /** Run Kav's own handling of an action yourself, from inside your own handler too: no extension is asked. On the main
     *  thread. */
    void kavAction(String name, Context activity, String args);

    /** Add a view before or after one of Kav's screen parts, or replace it (manifest: capability "part.<name>"). where:
     *  "before", "after" or "replace". The view is asked for each time the part shows, on the main thread; about: JSON
     *  text of what the part shows. Returning null from a replace shows Kav's own. null removes it. */
    void part(String name, String where, Card card);

    /** A ticket into Kav's own tickets and ride history, as JSON text (docs/native.md has its fields). From any thread. The
     *  same key again updates it. It stays when the extension is switched off or removed, until it ends. */
    void addTicket(String ticket);

    /** Hook one of Kav's (or Android's) methods (manifest: capability "hooks"). before runs first and may set a result to
     *  skip the original; after runs after it and may change the result. Either may be null. Returns an Unhook, or throws
     *  when the method can't be hooked. Every hook is removed when the extension is switched off or blocked. */
    Unhook hook(java.lang.reflect.Member method, MethodHook before, MethodHook after);

    /** The same, by the function's name, as the manifest's "hooks" lists it: className, method ("<init>" for a
     *  constructor) and its parameter types ("int", "java.lang.String", "int[]"). When this Kav has no such function,
     *  or can't hook it, nothing is hooked and nothing is thrown: Kav writes it in the extension's log and says on its
     *  row that it needs an update for this Kav. hooked tells whether it took. */
    Unhook hook(String className, String method, String[] params, MethodHook before, MethodHook after);

    /** Whether this extension has a hook on that function now. */
    boolean hooked(String className, String method, String... params);

    /** A method that may have inlined a small one you hooked (R8 inlines small methods in Kav's release builds), run as
     *  written again so your hook is reached. True when it was done. */
    boolean deoptimize(java.lang.reflect.Member method);

    /** Kav's own ClassLoader, to find its classes by name. */
    ClassLoader kavClassLoader();

    /** A card's view, made each time its screen shows it. about: what the screen shows, as JSON text: the trip, or the
     *  result's route ({"trip": the route as route rules get it}), or the stop ({"stop": "<ref>", "name": "..."}); {} on
     *  Home. A results card that has nothing to say for a route makes no view (null). */
    interface Card {
        android.view.View create(Context context, String about);
    }

    /** A screen's view. args: the page's arguments, as JSON text. */
    interface Screen {
        android.view.View create(Context context, String args);
    }

    /** API 5. A trip event (onTrip), as JSON text. */
    interface TripListener {
        void trip(String event);
    }

    /** API 5. A home screen widget. content: its content as JSON text (docs/native.md has its fields), asked with args,
     *  {"widget", "rows", "width", "height"}: rows is how many fit, width and height its size in dp. Answer within 3
     *  seconds; Kav shows the last content it showed until then, and when it fails. tapped: a tap on a part whose action
     *  is {"call": "<name>"}, told {"widget", "call", "row"}; Kav asks for the content again after it. */
    interface Widget {
        String content(String args) throws Exception;

        default void tapped(String tap) throws Exception {}
    }

    /** API 5. Kav's screen as capture took it: its picture, or null, and its pieces as JSON text. */
    interface Captured {
        void captured(android.graphics.Bitmap picture, String pieces);
    }

    /** API 5. Kav's screen as captureBehind took it: its picture and the same screen without its pieces, or null and null,
     *  and its pieces as JSON text. */
    interface CapturedBehind {
        void captured(android.graphics.Bitmap picture, android.graphics.Bitmap behind, String pieces);
    }

    /** API 5. One of its alarms (alarm), by its id. */
    interface Alarm {
        void ring(String id);
    }

    /** Its settings, all of them, as JSON text, after the rider changed one. */
    interface SettingsChanged {
        void changed(String settings);
    }

    /** The place the rider chose, as JSON text, or null. */
    interface Picked {
        void picked(String place);
    }

    /** A hook's answer to its input, both JSON text. */
    interface Hook {
        String call(String input) throws Exception;
    }

    /** An action taken over: true when it was handled. activity: the screen's. */
    interface Action {
        boolean run(Context activity, String args);
    }

    /** Told each call of a hooked method, before or after the original runs. */
    interface MethodHook {
        void call(Call call) throws Throwable;
    }

    /** One call of a hooked method. Setting a result or an error in before skips the original. */
    interface Call {
        /** The object the method was called on; null for a static method. */
        Object thisObject();

        /** Its arguments, which before may change. */
        Object[] args();

        Object result();

        void setResult(Object result);

        Throwable error();

        void setError(Throwable error);

        /** The original method, with these arguments, without the hooks. */
        Object callOriginal(Object... args) throws Throwable;
    }

    interface Unhook {
        void unhook();
    }
}
```

### `kav/kavapp/api/KavExtension.java`

```java
package kav.kavapp.api;

/**
 * The class a native extension names in its manifest ("native": {"dex": "classes.dex", "entry": "your.Entry"}).
 * Kav makes it with its public no-argument constructor and calls {@link #start} once each time Kav starts, on a
 * background thread. Everything here is kept un-renamed in Kav's release builds and only ever grows.
 */
public interface KavExtension {
    void start(Kav kav) throws Exception;
}
```

### `kav/kavapp/api/KavMap.java`

```java
package kav.kavapp.api;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Kav's maps for a native extension: markers, lines and areas it adds, a call when the rider taps one, and its own look
 * for Kav's stop marks. Plain types: places are latitude and longitude in degrees, colours ARGB, sizes in dp, icons
 * Android Bitmaps the extension draws. Callable from any thread.
 *
 * on: the map a shape shows on: "live" (the Live tab), "trip" (a trip's map), or null for both. id: the extension's own
 * name for it; adding with an id it used before replaces that shape.
 */
public interface KavMap {
    /** A marker: its icon, centred on the place, or a dot of the colour when icon is null. */
    void marker(String id, String on, double lat, double lon, int color, Bitmap icon);

    /** A line through points (lat, lon, lat, lon, ...). */
    void line(String id, String on, double[] points, int color, float widthDp);

    /** An area inside points (lat, lon, lat, lon, ...), filled, with an outline (0 for none). */
    void area(String id, String on, double[] points, int fill, int outline);

    void remove(String id);

    /** Every shape of this extension's, off every map. */
    void clear();

    /** Told when the rider taps one of its shapes. null stops it. */
    void onTap(Tapped tapped);

    /** Its look for Kav's own stop marks on the Live tab. null gives them back Kav's look. */
    void stopLook(StopLook look);

    interface Tapped {
        /** On the main thread. context: the screen's, for a sheet or dialog. */
        void tapped(String id, Context context);
    }

    interface StopLook {
        /**
         * A stop's look: stop is its reference ("il.mot/gtfs/stop/<code>"), as on a stop's card. null keeps Kav's look for
         * it. Asked once per stop and kept until stopLook is set again, on the main thread: keep it quick.
         */
        Look look(String stop, String name, double lat, double lon);
    }

    /** A stop mark: the dot's fill and ring, or an icon in its place when icon isn't null. */
    final class Look {
        public final int fill;
        public final int ring;
        public final Bitmap icon;

        public Look(int fill, int ring, Bitmap icon) {
            this.fill = fill;
            this.ring = ring;
            this.icon = icon;
        }
    }
}
```

### `kav/kavapp/api/KavUi.java`

```java
package kav.kavapp.api;

import android.content.Context;
import android.view.View;

/**
 * Kav's own components as ordinary views, drawn by Kav: they follow Kav's theme, dark mode, right-to-left and text
 * size by themselves. Put them in any layout. And Kav's theme as values, for views an extension draws itself.
 * Every method must be called on the main thread.
 */
public interface KavUi {
    /** A button: primary is the one main action of a screen; otherwise a quiet one. */
    View button(Context context, String label, boolean primary, Runnable onClick);

    /** A card: Kav's panel around the extension's own content. */
    View card(Context context, View content);

    /** A list row: icon (one of Kav's icon names, or null), title, subtitle (or null), and a chevron when onClick isn't null. */
    View row(Context context, String icon, String title, String subtitle, Runnable onClick);

    /** A row with a switch. */
    View switchRow(Context context, String title, String subtitle, boolean on, Changed<Boolean> onChange);

    /** A text field, with its placeholder. */
    View textField(Context context, String value, String placeholder, Changed<String> onChange);

    /** A text field of one line, or of several when multiline (Enter makes a new line and the field grows as it fills).
     *  action: the keyboard's key on a one-line field, "next" (on to the next field), "done", "search" or "go". API 3. */
    View textField(Context context, String value, String placeholder, boolean multiline, String action, Changed<String> onChange);

    /** A round button with one of Kav's icon names, and its label for screen readers. API 3. */
    View iconButton(Context context, String icon, String label, Runnable onClick);

    /** A frame showing one view at a time, moving to another with Kav's own transition: forward slides the next one in,
     *  back the one before. API 3. */
    Pages pages(Context context, View first);

    /** The views of pages(): view() is the frame to put in a layout. */
    interface Pages {
        View view();
        void forward(View next);
        void back(View previous);
    }

    /** A chip, lit when selected. */
    View chip(Context context, String label, boolean selected, Runnable onClick);

    /** A section header. */
    View header(Context context, String text);

    /** A sheet over the screen with a title and the extension's content. */
    void sheet(Context context, String title, View content);

    /** A dialog with a title, a text, and a confirm button (and Cancel). */
    void dialog(Context context, String title, String text, String confirm, Runnable onConfirm);

    /** A whole screen in Kav's look from a block tree, as JSON text ({"title", "sections": [{"blocks": [...]}]}). */
    View blocks(Context context, String page);

    /** A colour of Kav's theme now, as ARGB: bg, surface, surfaceRaised, fill, fillStrong, separator, text,
     *  textSecondary, textTertiary, surface2, surface3, surface4, borderStrong, sunken, routeIdle, problem, critical,
     *  realtime, accent. 0 for a name Kav doesn't know. */
    int color(String role);

    /** A size of Kav's in dp: rCard, rSheet, rTile, rBadge, gap1 to gap8. 0 for a name Kav doesn't know. */
    float dp(String name);

    /** A text style's size in sp: largeTitle, title, headline, body, subhead, caption. 0 for a name Kav doesn't know. */
    float textSize(String style);

    /** A value that changed. */
    interface Changed<T> {
        void changed(T value);
    }
}
```

## Hook reference

Everything your code can provide to Kav with `Kav.provide(name, hook)`, every field Kav hands it, and every answer
it may give. Input and answer are JSON text. Kav calls a hook on a thread of its own, never the main one, within the
hook's deadline. Taken from Kav 5's code, paths under `android/app/src/main/java/uk/noammm/kav/`: `ext/Hooks.kt`,
`ext/NativeRules.kt`, `ext/Pipeline.kt`, `ext/BoardAnswers.kt`, `data/KavPlan.kt`. If this page and the code
disagree, the code is right; please report it.

A hook only runs when the manifest declares its contribution ([native.md](https://kavapp.link/docs/extensions/native) has the table). Cards, pages,
settings and themes have a page of their own: [screens.md](https://kavapp.link/docs/extensions/screens).

```kotlin
kav.provide("results.annotate") { input ->
    val routes = JSONObject(input).getJSONArray("routes")
    val out = JSONArray()
    for (i in 0 until routes.length()) out.put(JSONArray().put("quiet"))
    out.toString()
}
```

### The five route rules

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

#### `query`

| Field | Type | What it is |
|---|---|---|
| `way` | string | `"d"` leaving at a time, `"a"` arriving by a time, `"l"` the last way there tonight. |
| `at` | number | The time asked about, in seconds since 1970 (UTC). When the rider asked to leave now, the time of the search. |
| `now` | number | When the search was made, same unit. |
| `settings` | string | The rider's own planner settings, four letters (`data/PlanSettings.kt`): pace `s` slow, `n` usual, `f` fast; walking `l` less, `n` usual, `m` more; changes `n` usual, `f` fewer; time to spare `t` tight, `n` usual, `r` relaxed. The usual is `"nnnn"`. |
| `locale` | string | `"he"` or `"en"`. |

#### A route

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
([screens.md](https://kavapp.link/docs/extensions/screens)).

#### A leg

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
| `liveDep`, `liveArr` | number or null | API 5. For a ride: when Kav's live times have it leave the stop it's boarded at and get to the one it's left at, in seconds since 1970. `null` without a live time, and for every other leg. |
| `turns` | object[] | API 5. For a walk: its turns as its planner gives them, each `{ way, street, metres, lat, lon }`: `way` OpenTripPlanner's relative direction (`DEPART`, `LEFT`, `SLIGHTLY_RIGHT`, `CONTINUE`...), `street` the street it goes onto (`""` for a path with no name), `metres` how far along it. Empty for a walk without them and for every other leg. |

A `wait` comes before each ride. It is at one stop, the one the ride leaves from: its `from` and `to` are both that
stop.

A **reference** names a thing in someone's data (`ext/Contracts.kt`): `publisher/dataset/kind/localId`, where kind
is `stop`, `route`, `trip` or `agency`. The Ministry of Transport's own are `il.mot/gtfs/stop/<stop code>`,
`il.mot/gtfs/route/<route id>` and `il.mot/gtfs/trip/<trip id>`. In the local id, `%`, `/` and `#` are written
`%25`, `%2F` and `%23`. The same local id from two publishers is two different things.

#### `walk.time`: a walking model

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

#### `transfer.policy`: how changes are judged

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

#### `results.filter`: hiding routes

Answers for `routes`: the routes left after the walking model and the transfer policy, or after the filter before
this one.

**Answer:** for each route, `false` or a string (the reason) to drop it. Anything else keeps it. Dropped routes are
counted as hidden, and a reason is shown to the rider with that count.

##### Reasons, and the row of hidden routes

Kav keeps every route a rule left out, with the rule and the reason it gave. Under the routes the rider then sees a
quiet row: how many were hidden and by which extension ("2 routes hidden by "Calm changes""), your reasons in a small
line under it, as you gave them, each once, and a Show button that shows the hidden routes this once, after the kept
ones (`ui/ExtHiddenRoutes.kt`). Nothing is judged again and nothing is remembered. Write a few plain words: the row
shows two lines of reasons at most.

Deadline: 150 ms.

#### `results.rank`: the order of Recommended

Called with the routes of Kav's main list only, and only when there are at least two.

**Answer:** the routes' `index` values in the order you want them: each a number, or an object with an `index`.
Every index must appear exactly once. Anything else is refused and Kav keeps its own order.

A ranker is never required: when it fails or is late the search is shown without it.

Deadline: 150 ms.

#### `results.annotate`: tags on routes

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

#### Time, failure, and "required"

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
  that throws is also counted as a crash ([native.md](https://kavapp.link/docs/extensions/native), "The crash guard and safe mode").
- A late hook is not stopped: Kav stops waiting and leaves it to finish. Keep rules quick.

### `plan.provide`: a planner

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
get no live times from the ministry. Their own board provider supplies live times as described below.

### Timetables and stop boards: `board.provide`

A contribution with capability `board.provide` gives Kav stops of its own and what leaves them. Riders find those
stops in the Stops tab, in search and nearby, after Kav's own, with no mark of yours; opening one shows
Kav's stop board filled with your times, refreshed every 30 seconds; and a rider can save the stop
like any other (`ext/Hooks.kt`, `NativeBoard`). Kav asks only the stop's own publisher about it, wherever it shows
departures.

The timetable may be inside your code, or read from a server with Android's own networking. You provide two hooks.

#### `board.stops`: the stops and lines

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

#### `board.departures`: one stop's board

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

### What is not here yet

- The full shape of a plan (alternatives, fares, tags, sections, turns): see `plan.provide` above.
- Saving one of your lines: Kav has no page for an extension's line yet, so only stops can be saved.

### Planner tabs and display settings

Each extension planner the rider switches on gets a tab of its own on the results, beside Kav's: its key is
`ext:<publisher>/<dataset>` and its label is your extension's name. A search asks every tab's planner at once, so a
tab opens on its routes as soon as they're in. In Settings › Privacy › Routes from every planner the
rider orders the tabs, hides one, and picks the tab results open on; Kav's own tab can be hidden while an extension's
is left. While your extension is switched off its tab stays and says it didn't answer, so the rider is never shown
another planner's routes in its place. A rider who had chosen one extension's planner before tabs keeps it: it gets its
tab, and results open on it.

The part `results.planners` gets `{"source": "<the tab shown>", "sources": ["kav", "ext:<publisher>/<dataset>", ...]}`.
The action `results.planner.select` shows a tab:

```kotlin
kav.kavAction("results.planner.select", kav.context(), """{"source": "ext:example/planner"}""")
```

With `"default": true` it is also the tab results open on from then, whether results are showing or not, as if the
rider picked it in Settings (where they can change it back).

**The rider's display settings.** Kav sends them as `prefs` in every `plan.provide` and `stop.photo` request, and
`kav.prefs()` (API 5) answers them at any time:

```json
{"stopNames": "en", "clock": 24, "locale": "en"}
```

`stopNames` is `"en"` or `"he"`, the language the rider reads stop names in; `clock` is `12` or `24`; `locale` is
Kav's language, as `kav.locale()`. Ask again when you need them: the rider can change them at any time. `kav.prefs()`
needs `"api": 5` in the manifest, or check `kav.apiVersion() >= 5` before calling it: an older Kav has no such method.

### Live times, vehicles and ride alerts (plan 2.1, board 1.1)

Each of these is optional: a plan 2.0 or board 1.0 answer stays as it was.

**Live times.** In a plan, a ride's `dep` is when it leaves and `sdep` its timetabled time (`dep` when left out).
`"live": true` says `dep` is a live estimate; without it, a `dep` that isn't `sdep` is shown as an expected time,
not a live one. `next` and `before` are the line's later and earlier departures from the boarding stop, each
`[unix seconds, trip id]`, with a third item `1` when that time is live: the ride's card lists them.

Once the results show, Kav keeps a ride's times fresh from its boarding stop's board: it asks the same extension's
`board.departures` for that stop, and matches each departure by its line and trip ids. Use the same stop, line and
trip ids in your plan and on your board, and give each departure an `estimated` time when you have one. Without
`board.provide`, the plan's own times stay.

**Vehicles.** A plan's ride and a board's departure can each say where the bus or train is:

```json
"vehicle": {"lat": 32.0853, "lon": 34.7818, "at": 1795421100, "bearing": 90}
```

`lat` and `lon` in degrees; `at` when it was there, in unix seconds (left out: when you answered); `bearing` its
heading in degrees from north (optional). A ride with a vehicle gets Kav's Live location button, on its trip page and
on its card. A board's position replaces the plan's, so a board that is asked again keeps the bus moving; a vehicle
with coordinates out of range is left out.

**Alerts.** A plan's ride can carry a service alert:

```json
"alert": {"text": "Board at the temporary stop on Herzl Street.", "severe": false}
```

Kav shows it in its own alert row, on the results, the trip page and while navigating, with the warning color when
`severe` is `true`. Empty text is no alert.

### Stop photos: `stop.photo`

A photo of a stop, in place of Street View's. Declare a contribution with the capability `stop.photo`, and answer it
in code:

```kotlin
kav.provide("stop.photo") { input ->
    val o = JSONObject(input)  // {"lat": 32.0853, "lon": 34.7818, "stop": {"code": "778", "id": "s778"}, "prefs": {...}}
    val mine = o.optJSONObject("stop")?.optString("id")?.takeIf { it.isNotEmpty() }
    val url = if (mine != null) photoOf(mine) else photoNear(o.getDouble("lat"), o.getDouble("lon"))
    if (url == null) "null" else JSONObject.quote(url)
}
```

Kav always sends the place the photo shows (`lat`, `lon`). When Kav knows which stop it is, it adds `stop`: its `code`
(the code riders see), and, for one of your own stops (one your plans or boards returned), `id`, your own id for it,
exactly as you sent it. Another extension's stop comes as the place only. A Kav from before stop ids sends the place
only, so fall back to it. Your code starts afresh each time Kav starts, so keep what you need (a file under
`kav.context()`, or your own stop list) rather than only what your last plan returned.

Answer an `https` image address as a JSON string, or `null` for none. Kav asks each extension with `stop.photo` in
turn, within 3 seconds each; the first image that loads is shown, small on the stop's row and large when the rider taps
it. With none, Kav shows Street View's, as before, and keeps the photo it showed for that place until Kav restarts. An
image is at most 5 MB, in a format Android reads (JPEG, PNG or WebP); Kav scales it down to what it shows. Kav asks
only while the rider has stop photos on, and logs why an answer wasn't used (tag `KavStopPhoto` in `adb logcat`). A
Kav from before `stop.photo` ignores the contribution.

### The rider's trips: `trips.history` and `trips.follow` (API 5)

Two capabilities give an extension the rider's trips. Each is a contribution in the manifest, and each has its own
line on your extension's page ("Reads your recent trips, if you allow it", "Follows the trips you take, if you allow
it") and its own switch there, **off until the rider turns it on**: "Read your trip history" and "Follow your trips".
Kav never turns them on for the rider. Turned off, Kav stops at once: `kav.trips()` gives `null` again, and no more
events come.

```json
"contributions": [
  {"id": "history", "capability": "trips.history"},
  {"id": "follow", "capability": "trips.follow"}
]
```

**Trip history: `kav.trips()`.** The trips of Kav's Recent trips list (Home › Recent trips), newest first, as JSON
text, or `null` while the rider hasn't allowed it:

```json
[{"from": {"name": "Home", "detail": "Herzl 1", "lat": 32.0853, "lon": 34.7818},
  "to": {"name": "Azrieli", "detail": "Tel Aviv", "lat": 32.0745, "lon": 34.7920},
  "stopovers": [], "at": 1795421100, "planner": ""}]
```

`from` is `null` for a trip from where the rider was. `at` is when the rider searched it, in Unix seconds. `planner`
is the extension that planned it (`"ext:<publisher>/<dataset>"`), or `""` for Kav's own planners. Ask again when you
need it: the list changes with every search. Without a `trips.history` contribution `kav.trips()` throws.

**Following trips: `kav.onTrip(listener)`.** Told about the trip the rider follows (Start on a trip's page) as it
changes, on a thread of Kav's, never the main one:

```kotlin
kav.onTrip { event ->
    val e = JSONObject(event)
    kav.log("${e.getString("event")}: step ${e.getJSONObject("step").getString("kind")}")
}
```

```json
{"event": "step", "from": "Home", "to": "Azrieli",
 "trip": {"provider": "", "dep": 1795421100, "arr": 1795422900, "legs": [...]},
 "step": {"index": 2, "count": 6, "kind": "wait", "legIndex": 1, "leg": {"kind": "ride", "lineNumber": "18", ...},
          "shown": 2},
 "steps": [{"kind": "start", "legIndex": -1, "line": null, "place": "Home"},
           {"kind": "walk", "legIndex": -1, "line": null, "place": "Allenby"},
           {"kind": "wait", "legIndex": 1, "line": "18", "place": "Allenby"},
           {"kind": "ride", "legIndex": 1, "line": "18", "place": "Azrieli Center"},
           {"kind": "walk", "legIndex": -1, "line": null, "place": "Azrieli"},
           {"kind": "arrive", "legIndex": -1, "line": null, "place": "Azrieli"}]}
```

- `event`: `"start"` (the rider started following it, or Kav started again with it under way), `"step"` (the rider
  moved on to another step: off a bus, the next walk or wait, and so on), `"replan"` (Kav planned a new route on the
  way; `trip` is the new one), `"shown"` (the rider swiped navigation's cards to another step, or opened or left
  navigation; only `step.shown` changed), `"eta"` (Kav's estimate of when the trip gets there moved to another
  minute; only `eta` changed) or `"end"`, with `"reason": "arrived"` when it ended at its last step, or `"ended"` when
  the rider ended it before. Ignore an event you don't know: a later Kav may add more.
- `trip`: the trip as slots, parts and route rules get it (its legs, stops, lines and times).
- `step`: the step the trip is on, the one navigation marks as current: its `index` and the `count` of steps, its
  `kind` (`start`, `walk`, `wait`, `ride`, `taxi`, `cycle` or `arrive`), its leg (`leg`, as a leg of `trip`; for a
  wait or a ride, the line the rider chose to take) and that leg's place in `trip.legs` (`legIndex`, `-1` for a walk
  or the start and end).
  Getting off a bus is the step after its ride. `shown` is the step whose card navigation shows: the rider can swipe
  the cards, or tap the step dots, to look at another step than the one the trip is on. It's `-1` while navigation
  isn't open (the rider went back to Home during the trip).
- `steps`: every step of the trip, in navigation's order, so `step.index` and `step.shown` are places in it. Each
  has its `kind` and `legIndex` as above, the `line` a wait or a ride is for (the line the rider chose there, `null`
  for other steps), and the `place` the step is about: where the trip starts, the stop a walk goes to, the stop a
  wait boards at, the stop a ride gets off at (a taxi's or a bike's too, when it ends at one), or where the trip ends.
  `null` when it has none.
- `eta`: when the trip gets there as Kav estimates it now, in seconds since 1970: the trip notification's "Arrive",
  worked out from where the bus is on a ride, otherwise from the plan and the live times Kav has. Kav works it out
  every few seconds in its trip service, so `"eta"` events also come with Kav in the background.
- Set while a trip is under way (from `start()`, or when the rider allows it during a trip), the listener is told that
  trip at once, as `"start"`. `null` stops it. Without a `trips.follow` contribution `kav.onTrip` throws.

Both need `"api": 5` in the manifest (an older Kav has neither method). A Kav without these capabilities installs your
extension and never calls you.

### Kav's own planner: `kav.plan`, `results.add` and `kav.switchTrip` (API 5)

**`kav.plan(query)`** asks Kav's own planner, as Kav's search does, and answers its routes. Off the main thread: it
asks the network, and throws on the main one. No contribution needed.

```kotlin
val answer = kav.plan("""{"from": [32.0853, 34.7818], "to": [32.0745, 34.7920], "at": 0, "way": "d", "settings": "fnnt"}""")
// {"routes": [{"ref": "r12", "dep": ..., "arr": ..., "legs": [...], ...}]}
```

- `from`, `to`: `[lat, lon]`, required. `at`: seconds since 1970, `0` or none for now. `way`: `"d"` to leave at `at`
  (the default) or `"a"` to get there by it. `settings`: the planner settings' four letters, in this order: pace
  (`s` slow, `n` normal, `f` fast), walking (`l` less, `n` normal, `m` more), changes (`n` normal, `f` fewer) and time
  at the stop (`t` tight, `n` normal, `r` relaxed); the rider's own by default. `modes`: the route types to take
  (all by default).
- Each route is in the shape route rules get it ([A route](#a-route)), with a `ref`. Kav keeps the route by its
  `ref` for 15 minutes (the last 60 of yours), for `results.add` and `kav.switchTrip`; another extension's `ref` is
  unknown to you. No routes when the planner found none, `null` when it can't be reached. A query that isn't right
  throws.

**`results.add`: your routes first in Kav's results.** Once Kav's own planner has answered a search and its routes
are on screen, Kav asks each extension that provides `results.add`, with a contribution of that capability. Its
routes go first. Kav asks only for its own planner (not an extension's planner, a recent trip an extension planned,
or a shared trip), and not for a search with stops on the way or for the last departures. The rider's route rules
judge your routes too.

```json
"contributions": [{"id": "runs", "capability": "results.add"}]
```

```kotlin
kav.provide("results.add") { input ->
    val q = JSONObject(input).getJSONObject("query")
    val faster = JSONObject(kav.plan(q.put("settings", "fnnt").toString()))
    // The first two of them at the fast pace, leaving 2 minutes later.
    JSONArray((0 until minOf(2, faster.getJSONArray("routes").length())).map {
        val r = faster.getJSONArray("routes").getJSONObject(it)
        JSONObject().put("ref", r.getString("ref")).put("start", r.getLong("dep") + 120)
    }).toString()
}
```

- Asked `{"query": {"from", "to", "at", "way", "now", "settings", "modes", "reason"}, "routes": [...], "trip"}`:
  `routes` are Kav's own, `reason` is `"search"`, or `"replan"` when Kav plans a New route from here for the trip under
  way, which also gives that `trip` (as `kav.onTrip` gives it). For `"replan"`, the first route you answer is the new
  route. `at` is as for `kav.plan`: `0` when the rider searched for now, `now` the time Kav asks, in seconds since 1970.
- Answered `[{"ref", "start", "walks"}]`: routes from `kav.plan`, at most 3. `start` is when the rider leaves, in
  seconds since 1970 (the route's own by default); `walks` how many seconds each walk before the route's first ride
  takes (the route's own by default; as many as those walks). Kav lays the walks and waits out from `start`, and takes
  the route only when they reach the first ride by when it leaves, by its live time when Kav has one. Its tag
  `likely_missed` goes, and Kav's own tags are worked out again over all the results.
- Within 20 seconds; Kav's own routes stay on screen meanwhile. A route Kav doesn't know or can't lay out so is left
  out, and an answer Kav can't read adds nothing.

**`kav.switchTrip(change)`: the trip under way goes on with one of your routes**, from where one of its rides ends,
as Kav's own New route from here does: the legs up to that ride stay, your route comes after them.

```kotlin
val switched = kav.switchTrip("""{"ref": "r12", "after": 1, "dep": 1795421100, "arr": 1795422900}""")
```

`after` is the ride's index in the trip's `legs`, `dep` and `arr` the trip's own as `kav.onTrip` gives them, so a
trip that changed since is left as it is. `start` and `walks`, both optional, lay the route out first as for
`results.add` (a run from the stop where the ride ends, say). True when it was switched; false for a `ref` Kav doesn't
know, another trip, a ride the rider is past, walks that don't reach the route's first ride in time, a trip with
stops on the way still ahead (a route of `kav.plan` goes through none), or Kav's screen not running. Only while the
rider allows following trips, with a `trips.follow` contribution (without it, it throws). From any thread. The
listener is then told `"replan"`.


## Screens: cards, pages, settings, the Home body, themes and home screen widgets

What an extension can show on Kav's screens. Taken from Kav 5's code: `ext/Slots.kt`, `ext/Pages.kt`,
`ext/Blocks.kt`, `ext/Looks.kt`, `ext/Widgets.kt` (paths under `android/app/src/main/java/uk/noammm/kav/`). If this page and the code
disagree, the code is right; please report it.

Every card and screen is an Android view your code makes ([native.md](https://kavapp.link/docs/extensions/native)). To look like the rest of Kav, build
it from Kav's own components (`kav.ui()`), or hand Kav a block tree and let it draw the whole view
(`kav.ui().blocks`, "Blocks" below).

| You want to | Declare | In code |
|---|---|---|
| Add a card to one of Kav's own screens | a slot: `slot.home`, `slot.results`, `slot.trip` or `slot.stop` | `kav.card(...)` |
| Have screens of your own | `page.provide` and `pages` | `kav.screen(...)` |
| Offer a Home screen of your own | `home.body` and a page called `home` | `kav.home(...)` |
| Let the rider set things | `settings` | `kav.settings()`, `kav.onSettings(...)` |
| Change how Kav looks | a theme (`"kind": "theme"`), or a `theme` contribution | nothing |
| Put a widget on the phone's home screen (API 5) | `widget.provide` and `widgets` | `kav.widget(...)` |

### Cards on Kav's screens

| Slot | Capability | `kav.card(on, ...)` | Where Kav puts it | `about` |
|---|---|---|---|---|
| Home card | `slot.home` | `"home"` | On Home, after Kav's own suggestions; three extensions at most | `{}` |
| Results line | `slot.results` | `"results"` | In the footer of each route's card in a search's results | `{"trip": <the route>}` |
| Trip page | `slot.trip` | `"trip"` | After Kav's timeline on a trip's page; three extensions at most | `{"trip": <the route>}` |
| Stop board | `slot.stop` | `"stop"` | After Kav's own rows on a stop board; two extensions at most | `{"stop": "il.mot/gtfs/stop/<code>", "name": "..."}` |

A route is in the shape route rules get it ([hooks.md](https://kavapp.link/docs/extensions/hooks), "A route"): times, fare, tags, and its legs with
their stops, coordinates, lines and trips.

```json
"contributions": [{ "id": "card", "capability": "slot.home" }]
```

```kotlin
kav.card("home") { context, _ -> kav.ui().card(context, kav.ui().row(context, "bus", "Next shuttle", "in 4 min", null)) }
kav.card("results") { context, about ->
    val walked = JSONObject(about).getJSONObject("trip").getJSONArray("legs").let { legs ->
        (0 until legs.length()).map { legs.getJSONObject(it) }.filter { it.getString("kind") == "walk" }.sumOf { it.getInt("metres") }
    }
    if (walked > 800) TextView(context).apply { text = "A long walk" } else null
}
```

- A card shows once the extension is on, in the order the extensions were switched on. The
  rider can turn each one off, and back on, at the end of its row under "It can" on the extension's page in
  Settings › Extensions.
- Kav calls `create` on the main thread each time the screen shows the card. Make the view quickly and load anything
  slow on a thread of your own. A call that runs past 3 seconds counts as a crash ([native.md](https://kavapp.link/docs/extensions/native)).
- Return `null` to show nothing. A results line then takes no space.
- A Home card isn't told where the rider is. Use the phone's GPS from your code (`samples/native/speed-card`), or ask
  for a place in a setting.

### Pages: screens of your own

```json
"contributions": [{ "id": "screens", "capability": "page.provide" }],
"pages": [
  { "id": "log", "title": { "en": "Rides", "he": "נסיעות" }, "tab": true },
  { "id": "about", "title": { "en": "About" } }
]
```

```kotlin
kav.screen("log") { context, args -> RidesView(context, kav) }
```

- Up to 64 pages. A page's `id` is lower-case letters, digits, `.`, `_` and `-`, up to 40. Its `title` needs English
  words (`en`); Hebrew (`he`) is optional.
- Kav shows your view under its own header, with the page's title.
- `"tab": true` lets the rider pin the page as a tab in Kav. You cannot pin it yourself.
- Kav offers "Open &lt;title>" for each page on your extension's page in Settings › Extensions. `"entry": false` leaves
  that row out, for a page that only makes sense opened another way. A tab can't be `"entry": false`.
- Pages without a `page.provide` contribution, or `page.provide` without pages, are refused.

### The Home body

An extension can offer a Home screen of its own. The rider chooses it ("Use it as my Home screen"); switching the
extension on does not replace anything.

```json
"contributions": [
  { "id": "screens", "capability": "page.provide" },
  { "id": "home", "capability": "home.body" }
],
"pages": [{ "id": "home", "title": { "en": "Ride log" } }]
```

```kotlin
kav.home { context, _ -> MyHomeView(context, kav) }
```

- Kav keeps its own search bar, the card of a trip under way and the way back to Kav's own Home above your body.
- Kav shows its own Home whenever yours isn't there: not chosen, safe mode, the extension off or gone, your code
  failed, or not ready within 100 ms.

`samples/native/my-home` has a Home body and a tab.

### Settings

Declare them and Kav draws the settings page, keeps the values with the extension's data, and hands them to your
code.

```json
"settings": [
  { "key": "town", "type": "text", "label": { "en": "Your town" }, "maxLength": 40, "default": "" },
  { "key": "walk", "type": "number", "label": { "en": "Longest walk" }, "min": 1, "max": 40, "default": 15, "unit": { "en": "min", "he": "דק׳" } },
  { "key": "quiet", "type": "toggle", "label": { "en": "Quiet stops only" } },
  { "key": "units", "type": "choice", "label": { "en": "Distances in" }, "default": "km",
    "options": [{ "value": "km", "label": { "en": "Kilometers" } }, { "value": "mi", "label": { "en": "Miles" } }] },
  { "key": "home", "type": "place", "label": { "en": "Your home" } },
  { "key": "stop", "type": "stop", "label": { "en": "Your stop" } },
  { "key": "leave", "type": "time", "label": { "en": "Leave at" }, "default": "08:00" },
  { "key": "trip", "type": "date", "label": { "en": "Trip day" } },
  { "key": "tint", "type": "color", "label": { "en": "Color" }, "default": "#00897B" }
]
```

| Type | Fields | Default when left out |
|---|---|---|
| `text` | `maxLength` (1 to 100,000, 10,000 if left out), `default` | `""` |
| `number` | `min`, `max` (both needed, `min` below `max`), `default` (needed, within them), `step` (above 0, 1 if left out), `unit` (optional words said after the value, `{ "en": "min", "he": "דק׳" }`) | none: give one |
| `toggle` | `default` | `false` |
| `choice` | `options`: one or more `{ value, label }`, each `value` different, `default` | the first option |
| `place` | none: the value is `{ "name", "lat", "lon" }`, chosen with Kav's own place search | `null` (none chosen yet) |
| `stop` | none: the value is `{ "code", "name", "lat", "lon" }`, chosen with Kav's own search (a stop, or the stop nearest the place picked) | `null` |
| `time` | `default` (`"HH:MM"`, 24-hour) | `""` |
| `date` | `default` (`"YYYY-MM-DD"`) | `""` |
| `color` | `default` (`"#RRGGBB"`) | `""` |

A `key` is lower-case letters, digits, `.`, `_` and `-`, up to 40. A `label` needs English words.

Kav draws them on your extension's page in Settings › Extensions, under "Its settings", always with Kav's own
controls: a switch for a `toggle`, buttons or chips for a `choice`, a field for `text`, a stepper for a `number`
(`step` at a time, never outside `min` and `max`), Kav's place search for a `place` and a `stop` (the same "Where
to?" search riders plan with, with their saved places and a pick on the map), Kav's time entry for a `time`, its day
stepper for a `date`, and a color picker with presets, a hue and a shade for a `color`.

For anything Kav doesn't draw (a picker of your own, your own look), give your own settings view with
`kav.settingsScreen(...)` ([native.md](https://kavapp.link/docs/extensions/native)): Kav shows it under the declared settings, or alone. Say
`"settings"` in `adds` so riders are told.

`kav.settings()` gives every declared setting as JSON text: what the rider set, or its default where they set
nothing or the value no longer fits the declaration. An update keeps the values. To know at once when the rider
changes one:

```kotlin
kav.onSettings { all -> pins.redraw(JSONObject(all)) }   // all of them, as kav.settings() gives them
```

### Blocks

`kav.ui().blocks(context, json)` turns a block tree into a view drawn with Kav's own components, in its own layout,
language direction and accessibility. Use it for a whole screen or inside a card.

```kotlin
kav.screen("about") { context, _ ->
    kav.ui().blocks(context, """{"title": "Ferry", "sections": [
        {"title": "Today", "blocks": [
            {"type": "notice", "level": "warning", "text": "The 18:00 sailing is canceled"},
            {"type": "row", "icon": "clock", "title": "Next sailing", "value": "17:30"},
            {"type": "button", "label": "Plan a trip to the pier", "style": "primary",
             "action": {"plan": {"lat": 32.8201, "lon": 34.9712, "name": "Old Quay"}}}]}]}""")
}
```

A tree is `{ "title", "sections" }`. A section is `{ "title", "blocks" }`, its title optional. Text is shown as you
give it; Kav's components cut what doesn't fit. A field not listed here is ignored, and a block type Kav doesn't know
is skipped.

| Block | Fields | Notes |
|---|---|---|
| `text` | `text` (needed, may have line breaks), `style`: `"title"`, `"body"` (the default) or `"caption"` | |
| `row` | `title` (needed), `subtitle`, `value`, `icon`, `action` | |
| `card` | `title` (needed), `subtitle`, `icon`, `badge`, `action` | |
| `notice` | `text` (needed), `level`: `"info"` (the default) or `"warning"` | There is no critical level: that is Kav's. |
| `metric` | `label`, `value` (both needed, text), `unit` | A big value with its label. |
| `button` | `label` and `action` (both needed), `icon`, `style`: `"primary"` or `"secondary"` (the default) | |
| `chips` | `items`: `{ label, action }` each, both needed | |
| `list` | `items`: rows (a row's fields, with or without `"type": "row"`) | |
| `trips` | `source`: `"recent"` or `"journey"` (needed), `limit` (1 to 50, 3 if left out) | Kav draws the rider's own trips; your code never sees them. |
| `image` | `src`: an `https://` address or a `data:image/...` address (needed); `alt`, words for TalkBack; `ratio` (width over height, 0.1 to 10, 1.5 if left out) | At most 8 MB. |
| `stop` | `ref`: `il.mot/gtfs/stop/<code>` | Kav's own row for a ministry stop, opening its board. |
| `departures` | `ref`, `limit` (1 to 50, 4 if left out) | The stop's next timetabled departures, from what is on the phone. |
| `line` | `number` (letters, digits, Hebrew letters and "-", at most 8) | Kav's plate for a line, opening Kav's search for it. |

Icons Kav has: `star`, `heart`, `bus`, `train`, `walk`, `bike`, `clock`, `map`, `pin`, `info`, `warning`, `check`,
`plus`, `edit`, `delete`, `search`, `list`, `home`, `calendar`, `settings`. Another name shows no icon.

An action is an object with one of these:

| Action | What it does |
|---|---|
| `{ "settings": true }` | Opens your settings page. |
| `{ "plan": { "lat", "lon", "name" } }` | Opens Kav's planner with that destination filled in. The rider sees it and it is planned only by their tap. |
| `{ "link": "<address>" }` or `{ "open": "<address>" }` | Opens any address or app link: `https:`, `geo:`, `tel:`, another app's. |

For anything else a tap should do, make that view yourself with `kav.ui()` and your own code.

`blocks` throws when it can't read the tree: it isn't JSON or is over 512 KB, a block has no `type`, a needed field
is missing or of the wrong kind, `sections` or `blocks` isn't a list, an action has nothing to do, an image isn't
`https:` or `data:`, or a `stop` names a stop that isn't the ministry's.

### Themes

A theme is a package that says `"kind": "theme"` in its manifest. Kav calls it a theme everywhere, lists it apart
from extensions (Settings › Look, once a theme is installed, and Settings › Extensions › Themes), and
applies one at a time: applying one takes any other off, and "Kav" goes back to Kav's own look. Sources host themes
as they host extensions, in the same bundle or a bundle of their own, and the community list takes them the same way.

```json
{
  "format": 1, "kind": "theme", "minKav": 48, "api": 5,
  "name": "night-sea", "displayName": "Night sea", "version": "1.0.0", "release": 1,
  "description": "Deep blue pages and a sea-green accent.",
  "contributions": [],
  "theme": {
    "looks": { "dark": { "bg": "#0B1A2A", "accent": "#7FD1C7" }, "light": { "accent": "#2E7D72" } },
    "lineBadges": "strip",
    "corners": { "card": 16, "sheet": 24, "tile": 10, "badge": 6, "control": 8 },
    "font": "fonts/Sea.ttf",
    "outline": "line",
    "map": { "dark": { "ground": "#08131F", "ink": "#9FD8E8", "water": "#0E2B44" } }
  }
}
```

A theme of colors only needs no code: leave out `"native"`, and `kavx bundle` packs its folder as it is. A theme may
also bring anything an extension can: `native` code, `settings`, `pages`, slots, screen parts, actions and hooks. It is
still shown as a theme. Its page in Settings says what it can do, as an extension's does, and one with code says up
front that it runs its own code, with full access. Its `settings` show on its page while it is applied; what it does
without a setting simply happens while it is applied, and stops when another theme or Kav's own look is.

| `theme` field | What Kav takes | Leave it out and |
|---|---|---|
| `looks` | any of `dark`, `oled` and `light`, each setting any of the 18 color roles and `accent` | that look, or that role, stays Kav's |
| the roles | `bg`, `surface`, `surfaceRaised`, `fill`, `fillStrong`, `separator`, `text`, `textSecondary`, `textTertiary`, `surface2`, `surface3`, `surface4`, `borderStrong`, `sunken`, `routeIdle`, `problem`, `critical`, `realtime` | Kav's own color |
| `accent` (in a look) | buttons, chosen chips, switches and links | the rider's own color |
| `lineBadges` | bus line badges: `"kav"` (Kav's plate in the operator's color) or `"strip"` (a gray plate over a strip of it) | the rider's own choice |
| `corners` | whole dp: `card` 0 to 32 (Kav's 22), `sheet` 0 to 40 (28), `tile` 0 to 24 (12), `badge` 0 to 16 (8), `control` 0 to 24 (Kav's are round at the ends: buttons, chips, segmented controls, switches, icon buttons, fields, and the buttons that float over the map) | Kav's own corner |
| `font` | the path of a `.ttf` or `.otf` file in the theme's own folder, at most three folders deep (`"fonts/Sea.ttf"`), for all of Kav's text | Kav's own type |
| `map` | for any of `dark`, `oled` and `light`: Kav's own map drawn in two colors, `ground` (the land) and `ink` (the town names), with every road, building and name in a shade between them, and `water` in its own color if you give one | that look's map stays Kav's |
| `outline` | how cards and controls are edged: `"kav"` (Kav's own hairline round cards), `"line"` (one solid line), `"double"` (two thin lines round cards, one round controls), `"sketch"` (drawn by hand) or `"bevel"` (a raised edge lit from above, and a gloss on controls) | Kav's own |
| `skin` | pictures Kav draws some of its parts with, each a PNG stretched by nine slices: see **Skins** below | Kav's own parts |

A color is `#RRGGBB` or `#AARRGGBB`. A value that isn't a color, a role or a look Kav doesn't know, a badge style or
an outline it doesn't know, a corner out of its range, a font that isn't a plain path to a `.ttf` or `.otf` and a look's
`map` without both `ground` and `ink` are left out, and so is a `skin` piece as **Skins** says. Only a `"theme"` that
isn't an object is refused.

**Fonts.** One file is all of Kav's text, every size and weight. A variable font (one with a weight axis) gives Kav's
regular, medium, semibold and bold their own weights; any other font is drawn heavier by the phone where Kav's text is
bold. A font Kav can't read, or one that isn't in the package, leaves Kav's own type, and `kavx check` refuses a font
that isn't in the folder. Kav's words are English and Hebrew, so pick a font with Hebrew letters, or Hebrew falls back
to the phone's own. Bring only a font whose license lets you share it (the SIL Open Font License does), with the
license beside it. Parts a theme's code draws with Android views (`KavUi`) keep the phone's type.

**Skins.** `skin` names a picture for any of Kav's parts, which Kav then draws in place of that part's own fill in
every look; a part left out is drawn as Kav's. Older Kav ignores it.

```json
"skin": {
  "topBar": { "image": "skin/topBar.png", "insets": [28, 9, 35, 17], "scale": 3, "ink": "#FFFFFF" },
  "button": { "image": "skin/button.png", "insets": [8, 9, 8, 9], "scale": 1.5, "ink": "#000000" },
  "background": "skin/wall.jpg"
}
```

| Piece | Where Kav draws it |
|---|---|
| `topBar` | the top of a screen with its title, and Home's search row, on up behind the status bar; `ink` is the title's color |
| `tabBar` | the tab bar, edge to edge and down behind the gesture area, 56 dp tall; `ink` is the other tabs' color |
| `tabSelected` | the chosen tab, which slides from tab to tab; `ink` is its icon's and name's color |
| `button`, `buttonPressed`, `buttonPrimary` | buttons (and icon buttons, chips and the chosen one of a segmented control); a pressed button and a chosen chip on `buttonPressed`, the main action's button on `buttonPrimary` |
| `card` | cards (anything at the `card` corner); Kav clips it to that corner |
| `cardTitle` | a strip across the top of each card, as tall as its picture at its scale, with the card's content below it |
| `field` | text fields, the search field, the track of a segmented control, and grouped lists (Live's vehicles, recent trips, stops, ride history) as one box round all their rows |
| `background` | not a piece: a PNG, JPEG or WebP behind every screen, cropped from its middle to fill it, seen where a screen leaves its background clear |

A piece is `image`, the path of a PNG in the theme's folder; `insets`, the edges kept as they are, `[left, top, right,
bottom]` in the picture's pixels (whole numbers from 0 to 512), with the middle stretched both ways and each edge along
its length; `scale`, dp for each of the picture's pixels (0.25 to 8, 1 if left out); and `ink`, the color of text and
icons on it (Kav's own if left out). Where a box is smaller than two edges, both shrink together. A picture drawn larger
than its pixels keeps them sharp, with no blur from one slice into the next. In a right-to-left language every piece is
mirrored, as right-to-left Windows mirrors its own, so draw each for left to right. A piece Kav doesn't know, or one
whose image isn't a PNG path or whose insets aren't four whole numbers, is left out, and `kavx check` warns. A piece is
at most 1024 pixels a side, its insets fit inside it, and the background is made smaller past 2048 pixels; `kavx check`
refuses a picture that isn't in the folder, isn't a PNG, is larger, or that its insets don't fit, where Kav would
quietly leave it out. Pick the looks' colors to go with the pictures, as the pictures don't change with the look.

**Outlines.** Cards, sheets, list groups and floating panels are outlined in `separator`, and buttons, chips,
segmented controls, icon buttons and switches in `borderStrong`, so set those two with the style you pick. The map,
icons and line badges aren't outlined.

**The map.** Kav's own map is drawn in grays, darker and lighter: `map` puts each gray between your `ground` and
`ink`, so roads, buildings and the more important names keep standing out as they do on Kav's map. Its routes, stops
and vehicles stay in Kav's and the lines' colors, and Israel's road number signs in their own. Pick a `ground` close to
the look's `bg` and an `ink` the town names read in. Only Kav's own map is toned: satellite photos and the other map
types stay as they are.

Not themeable: icons, spacing, shadows, the parts **Skins** doesn't list, Kav's own words, the map type, and the color
of text on the accent (it is `text` in the light look and `bg` in the dark ones). The map type stays the rider's choice,
since every map but Kav's own loads from someone else while the rider looks; a theme with code can still draw Kav's stop
marks its own way with `kav.map()` ([native.md](https://kavapp.link/docs/extensions/native)).

**Readability is yours to check.** Kav refuses nothing for it. `kavx check` warns when `text` or `textSecondary` is
below 4.5:1, or `realtime`, `problem` or `critical` below 3:1, on `bg`, `surface` or `surfaceRaised`, where your
theme sets both colors. Look at it in Kav, in each look you set, before you publish.

Kav keeps its own look on its protected screens: Settings (with extension management, sources, safe mode and the
themes list itself), its notices, the report form and navigation. So a theme shows once the rider leaves Settings.

An extension (no `kind`) may still bring a look with a `theme` contribution and the same `"theme"` field, as before.
The rider chooses it with "Use this look" on its page. It is the same single choice as a theme: choosing it takes the
applied theme off.

Samples: [themes/night-sea](../samples/themes/night-sea/manifest.json), colors, corners and outlines only, and
[native/quiet-home](../samples/native/quiet-home/src/org/example/quiet/QuietHome.java), a theme with a setting that
hides Home's suggestions card.

### Home screen widgets

API 5. Android only knows the widgets an app declares in its own manifest, and your code loads while Kav runs, so
Kav owns the widgets: two of its own in the launcher's list, "Kav extension" (2 × 2) and "Kav extension, wide"
(4 × 2), both resizable. As the rider places one, Kav asks which extension's widget it shows, and lists every widget
of the extensions that are on. Your page in Settings › Extensions also has "Add &lt;title> to the home screen" for each,
which places the one of your `size`. Riders see "Adds widgets to your home screen" under "It can".

```json
"contributions": [{ "id": "widgets", "capability": "widget.provide" }],
"widgets": [
  { "id": "next", "size": "wide",
    "title": { "en": "Next shuttles", "he": "ההסעות הבאות" },
    "description": { "en": "The next shuttles from the stop you choose", "he": "ההסעות הבאות מהתחנה שבחרתם" } }
]
```

| Field | Rule |
|---|---|
| `id` | the same rule as a page's, unique; at most 16 widgets |
| `title` | needs `en`; shown in Kav's list of widgets and on the Add row |
| `description` | optional, under the title in Kav's list |
| `size` | `"small"` (default) or `"wide"`: the one it looks best at, which Add to home screen places. The rider can resize it or place the other one |

Your code gives its content, as JSON text, and Kav draws it in its own look: the rider's light or dark look (both,
following the phone, for Settings' System look on Android 12 and newer), their accent color, Kav's corners and the
direction of Kav's language. No views of yours: a home screen can only show a few kinds of Android's views.

```kotlin
kav.widget("next", object : Kav.Widget {
    // args: {"widget", "rows", "width", "height"}: how many rows fit, and the widget's size in dp.
    override fun content(args: String): String {
        val rows = JSONObject(args).getInt("rows")
        return JSONObject()
            .put("title", "Shuttles from Harbor")
            .put("rows", JSONArray(next(rows).map { d ->
                JSONObject().put("line", d.line).put("title", "To ${d.to}").put("subtitle", "In ${d.wait} min").put("value", d.clock)
                    .put("action", JSONObject().put("page", "line").put("args", JSONObject().put("line", d.line)))
            }))
            .put("note", "From the shuttle's timetable")
            .put("empty", "No more shuttles today")
            .put("action", JSONObject().put("call", "next-stop"))
            .put("next", 600)
            .toString()
    }

    // A tap on a part whose action is {"call": ...}: {"widget", "call", "row"} (row -1: the widget itself).
    override fun tapped(tap: String) { stop = nextStop() }
})

// Your data changed: Kav asks content() again now.
kav.onSettings { kav.refreshWidget("next") }
```

| Content field | What it is |
|---|---|
| `title` | its first line, in bold; none with `""` |
| `rows` | a list of rows, as a page's rows ([Blocks](#blocks)): `title` (needed), `subtitle`, `value` (at the end, in the accent color: a time, minutes), `icon` (a page's icon names) or `line` (a line number on a plate in the accent color, instead of an icon), and `action`. Kav shows as many as fit, from the first |
| `note` | a short line at the bottom |
| `empty` | what it says when `rows` is empty |
| `action` | what a tap on the widget, outside a row with an action of its own, does. Without one, it opens Kav |
| `next` | in how many seconds the content changes by itself (the first shuttle leaves): Kav asks again then, at the soonest in 5 minutes, and only while the phone is awake |

| Action | What a tap does |
|---|---|
| `{"stop": "il.mot/gtfs/stop/<code>"}` | opens Kav at that stop's board |
| `{"line": "480"}` | opens Kav's Lines with that line searched |
| `{"plan": {"lat", "lon", "name"}}` | opens Kav's planner with that destination, as on a page |
| `{"page": "<id>", "args": {"key": "text"}}` | opens one of your pages, told the args |
| `{"settings": true}` | opens your page in Settings › Extensions, with your settings and the switches the rider gives you |
| `{"link": "https://..."}` (or `open`) | opens the link, straight from the home screen |
| `{"call": "<name>"}` | tells your `tapped` (Kav starts for it if it isn't running, within a few seconds), then asks `content()` again |

**When Kav asks.** When the rider places it or resizes it, when Kav opens, when you call `refreshWidget` (at most
every ten seconds; asks in between are done once, after), at the time `next` says, and when Android updates it, every
half hour at most. Your code runs only while Kav does: when Android asks and Kav isn't running, Android starts it, your
extension starts as it always does, and Kav asks then. Answer within 3 seconds. When you don't (or throw, or answer
something Kav can't read), the widget keeps what it last showed, and Kav writes why in your log; a throw also counts
as a crash.

**What it shows instead.** "This extension is off." with Open in Kav, while it's switched off, blocked or turned off
by Kav; "Kav started without extensions" in safe mode; that it was removed, with Choose another, once it's
uninstalled (what it showed goes with it); "Open Kav to load it." until your code first answers.

### When your code fails

A card or screen whose `create` throws or hangs shows nothing, and counts as a crash: 2 crashes in 5 minutes switch
the extension off at the next start ([native.md](https://kavapp.link/docs/extensions/native), "The crash guard and safe mode"). A Home body that
fails gives the rider Kav's Home, with a line saying so.


## The bundle format

What `kavx bundle` writes and what Kav reads. Taken from Kav 5's code, files under
`android/app/src/main/java/uk/noammm/kav/ext/`: `Bundle.kt`, `Archive.kt`, `Package.kt`, `Native.kt`, `GitHub.kt`,
`Hub.kt` and `Registry.kt`. In the kit, every number and rule on this page lives in one file, `lib/format.js`. If
this page and Kav's code disagree, the code is right.

### A bundle

A bundle (`.kavb`) is a ZIP archive with every extension of one source in it:

```
bundle.json                    {"format": 1, "name": "Dana's extensions", "icon": "icon.png"}
icon.png
speed-card/manifest.json
speed-card/classes.dex
speed-card/icon.png
map-pins/manifest.json
map-pins/classes.dex
```

- `bundle.json` has `format` (`1`), `name` and `icon`, all optional. `name` is the source's name as riders see it
  ("Names" below). Without one, Kav shows the repository's name, or the file's name for a bundle added from a file.
  `icon` names the source's picture, a file at the top beside `bundle.json`: a PNG or JPEG of at most 256 KB and 1024
  by 1024 pixels. Without one, Kav shows the GitHub owner's picture, or a letter. At most 16 KB.
- One folder per extension, named exactly as its manifest's `name`. It holds its `manifest.json`, its dex (where its
  manifest's `"native"` says) and its own files: an icon, pictures, data.
- Nothing else at the top.
- At least one extension and at most 64.

`kavx bundle <folder>` builds every extension under `<folder>/extensions/` (or `<folder>` itself, when it is one
extension) and writes the bundle. `--name` sets `bundle.json`'s name, `--icon` the source's picture, `--out` the file. `kavx verify <file.kavb>`
reads a bundle as Kav does.

Kav reads the whole bundle and checks it before it writes anything: the archive's rules, each manifest's rules, each
extension's dex and icon. One bad extension refuses the whole bundle. An extension whose manifest has `"runtime"`, or
has no `"native"`, is not refused: Kav lists it as "made for an older version of Kav" and never runs it.

#### The archive (`Archive.kt`)

| Rule | Limit |
|---|---|
| Archive size | 64 MB |
| Size once expanded | 256 MB |
| One file | 64 MB |
| Entries | 4096 |
| Compression | stored or deflate only; a file over 1 MB may not be more than 100 times its compressed size |
| Paths | ASCII letters, digits, `.`, `_` and `-`, in parts of 1 to 96 characters between single slashes; never `.` or `..`; at most 16 parts; at most 255 characters |
| Names | no two files whose names differ only in capitals |
| Never | links, encrypted entries, ZIP64, multi-part archives, files ending `.kavb`, `.jar`, `.apk`, `.so` or `.aab` |
| Code | a `.dex` file only as the one its extension's `"native"` names |
| Consistency | the central directory and each local header must agree on name and method; sizes and CRC-32 must be exact |

A `.zip` inside a bundle is fine (a timetable, say): it is a file like any other.

#### JSON

`bundle.json` and every `manifest.json` must be UTF-8, with no duplicate keys, at most 32 levels deep, and nothing
after the closing brace. Every number a rule reads (`format`, `release`, `minKav`, `api`) must be a JSON integer:
`1`, never `"1"`, `1.0` or `1e0`.

### `manifest.json` (`Package.kt`)

At most 64 KB.

```json
{
  "format": 1,
  "name": "speed-card",
  "displayName": "Speed card",
  "displayNameHe": "כרטיס מהירות",
  "version": "1.0.0",
  "release": 1,
  "minKav": 45,
  "api": 2,
  "description": "Your speed on Home, from the phone's GPS.",
  "descriptionHe": "המהירות שלך במסך הבית, לפי ה-GPS של הטלפון.",
  "native": { "dex": "classes.dex", "entry": "org.example.speed.Speed" },
  "adds": ["location", "notifications"],
  "contributions": [{ "id": "card", "capability": "slot.home" }]
}
```

| Field | Rule |
|---|---|
| `format` | `1`. |
| `name` | `a` to `z`, digits, `.`, `_`, `-`; starts with a letter or digit; at most 64 characters. Its folder in the bundle has this name. With the source it is the extension's identity, so it never changes. |
| `displayName` | What riders see. Any language and emoji. Kav cleans it ("Names" below) and cuts it at 64 characters; never refused. Defaults to `name`. |
| `displayNameHe` | Optional: its name for riders who have Kav in Hebrew, shown in place of `displayName` there and cleaned the same way. Without it, or empty after cleaning, they see `displayName`. Kav 5.2 and later read it, for any `api`; an older Kav ignores it. |
| `kind` | `"theme"` for a theme ([screens.md](https://kavapp.link/docs/extensions/screens#themes)): Kav calls it a theme, lists it with themes and applies one at a time. Anything else, or none, is an extension. API 5. |
| `version` | What riders see as the version. Any text up to 32 characters. Kav does not compare versions. |
| `release` | Needed: a whole number from 1. Riders see it beside the version. Raise it with each release. |
| `minKav` | The lowest Kav version code that can run it. Kav 5 is 45; set at least 45. An extension that needs a newer Kav is listed and can't be switched on. Default 0. |
| `api` | The API it was made for: `2` or `3` for Kav 5.0, `4` for actions, screen parts, tickets and hooks, with Kav 5.1 (`minKav` 47), `5` for planner tabs, the rider's trips, home screen widgets and Kav's timetable and live vehicles, with Kav 5.2 (`minKav` 48). Default 1. |
| `description` | At most 2000 characters. |
| `descriptionHe` | Optional: its description for riders who have Kav in Hebrew, in place of `description`; at most 2000 characters. Read as `displayNameHe` is. |
| `native` | Its Android code: `{ "dex": "<path in its folder>", "entry": "<full class name>" }`. Needed, except for a theme, which may bring none. See [native.md](https://kavapp.link/docs/extensions/native). |
| `permissions` | A list of plain words (the same rule as `name`). Kav shows them to riders as written. |
| `adds` | What it adds that Kav can't see in its code: any of `"map"` (draws on Kav's maps), `"notifications"`, `"location"` (uses the phone's location) and `"settings"` (a settings view of its own). Kav says these, with what the rest of the manifest shows (a Home card, a tab, route rules), on its row in Settings › Extensions and under "It can". Another word is ignored. |
| `contributions` | What it offers. Each: `id` (same rule as `name`, unique in the manifest), `capability` (a plain word; one from the table below does something, any other installs and does nothing), and optionally its own `permissions`. An old `domain` field is ignored. |
| `icon` | Optional: a picture in its folder (`.png`, `.jpg`, `.jpeg` or `.webp`), shown where it is listed. |
| `pages`, `settings` | Its own screens and what the rider can set. See [screens.md](https://kavapp.link/docs/extensions/screens). |
| `widgets` | Its home screen widgets, for a `widget.provide` contribution (API 5). See [screens.md](https://kavapp.link/docs/extensions/screens#home-screen-widgets). |
| `theme` | Its look: colors, line badges, corners, a font, outlines, the colors of Kav's map and pictures for some of its parts, for a theme or a `theme` contribution. See [screens.md](https://kavapp.link/docs/extensions/screens#themes). |
| `hooks` | For a `hooks` contribution: each function of Kav's it hooks, `{ "class", "method", "params": [types] }`. See [native.md](https://kavapp.link/docs/extensions/native#hooks-into-kav-itself). |
| `tags` | For an annotator: words for up to 32 of its tags, `"id": { "en": "words", "he": "words" }`. `en` is needed, each at most 40 characters. |
| `reasons` | Your own short reason under a line Kav shows riders. See "Reasons" below. Its old name, `explanations`, is still read. |
| `issues`, `homepage` | `https://` links. Without `issues`, "Report an issue" goes to the source's GitHub issues. |
| anything else | Ignored. A misspelled field (`reason` for `reasons`) is silently lost. `kavx check` warns about every field it does not know. |

#### Capabilities (`Contracts.kt`)

| Capability | What it is |
|---|---|
| `walk.time`, `transfer.policy`, `results.filter`, `results.rank`, `results.annotate` | the route rules ([hooks.md](https://kavapp.link/docs/extensions/hooks)) |
| `plan.provide` | a trip planner |
| `board.provide` | stops, timetables and stop boards |
| `page.provide`, `home.body` | its own pages, and the page `home` as a Home body |
| `theme` | an extension's look from the manifest (a theme, `"kind": "theme"`, needs no contribution for it) |
| `slot.home`, `slot.results`, `slot.trip`, `slot.stop` | a card on Home, a line under each search result, a card on a trip's page, a card on stop boards |
| `action.<name>` (API 4) | takes over one of Kav's actions, `action.pay` for paying ([native.md](https://kavapp.link/docs/extensions/native#actions) lists them) |
| `part.<name>` (API 4) | adds a view to one of Kav's screen parts, or replaces it, `part.home.pay` for Home's pay card ([native.md](https://kavapp.link/docs/extensions/native#screen-parts)) |
| `hooks` (API 4) | hooks on Kav's own code, each function listed under the manifest's `"hooks"` ([native.md](https://kavapp.link/docs/extensions/native#hooks-into-kav-itself)) |
| `widget.provide` (API 5) | home screen widgets, each listed under the manifest's `"widgets"` ([screens.md](https://kavapp.link/docs/extensions/screens#home-screen-widgets)) |
| `results.add` (API 5) | routes from Kav's own planner, first in Kav's results ([hooks.md](https://kavapp.link/docs/extensions/hooks#kavs-own-planner-kavplan-resultsadd-and-kavswitchtrip-api-5)) |

Each part of an extension has every power the manifest declares. Code you register only shows when the manifest
declares it too ([native.md](https://kavapp.link/docs/extensions/native) has the table).

#### Reasons

Kav shows riders a few lines about every extension, in its own words ([what-riders-see.md](https://kavapp.link/docs/extensions/what-riders-see)).
Under any of them you may add one short reason of your own:

```json
"reasons": {
  "slot.home": { "en": "Shows your speed while you ride", "he": "..." },
  "location": { "en": "Reads the GPS only while Home is open" }
}
```

| Key | The line it sits under |
|---|---|
| `plan.provide`, `board.provide`, `walk.time`, `transfer.policy`, `results.filter`, `results.rank`, `results.annotate` | what the extension is (one line per kind) |
| `page.provide` | it has its own screens |
| `tab` | it has a page the rider can pin as a tab |
| `home.body` | it can replace the Home screen |
| `theme` | it changes how Kav looks |
| `map`, `notifications`, `location`, `settings` | what it adds, from `adds` |
| `slot.home`, `slot.results`, `slot.trip`, `slot.stop` | it can add a card to Home, a line to results, a part to a trip's page, a row to stop boards |
| `actions` | the actions it takes over |
| `parts` | the parts of Kav's screens it changes |
| `hooks` | it changes how Kav itself works |
| `searches` | it sees the rider's trip searches |
| `results` | it changes the rider's results |
| `data` | it keeps its own data on the phone |
| `native` | it runs its own Android code, with full access |
| `permissions` | it asks for permissions |

Each value has `en`, `he` or both, each at most 140 characters; longer, or `reasons` that isn't an object of objects,
refuses the extension. A reason for a line the extension does not have, or for a key Kav does not know, is never
shown, and `kavx check` warns about it. A reason cannot add, hide or reorder a line.

### Identity

An extension's identity is its source plus its name: `github.<owner>.<repo>/<name>` for a GitHub source (in lower
case), `file.<16 hex digits of the bundle's SHA-256>/<name>` for a bundle file. `Kav.id()` gives it. The same name in
another source is another extension, with its own switch and data.

### A source on GitHub (`GitHub.kt`, `Hub.kt`)

A source is a GitHub repository whose latest GitHub Release carries exactly one `.kavb` asset. Kav asks GitHub's
public API for `repos/<owner>/<repo>/releases/latest`, with no sign-in and nothing about the rider, and downloads
that one asset. No release, no `.kavb`, or more than one `.kavb` on it, and the source can't be added (or its check
fails and the old bundle stays). GitHub never gives a draft or a pre-release as the latest release, so Kav doesn't
see those.

Riders add it by typing `owner/repo`, `github.com/owner/repo` or any GitHub address in the repository, or with an
add-source link (below).

**Updates.** Kav's background check (about every six hours, while Kav is closed) and Check now ask GitHub for each
source's latest release, with a conditional request, so an unchanged release costs nothing. When the bundle's SHA-256
differs from the one in use, Kav reads the new bundle and puts it in place of the old one:

- each extension keeps its switch and its settings;
- a new extension comes in off;
- one the new bundle no longer has is removed, with its data;
- one whose files changed loads its new code at once if it is on;
- while a trip is under way nothing is replaced; the next check asks again.

Kav does not compare `release` or `version`: a different bundle is the update. A source whose bundle fails the
checks keeps the one it had and shows the reason.

### A bundle file

A rider can add a `.kavb` from the phone (Settings › Extensions › Sources › + › Add a bundle file). It becomes a source of
its own, named by its `bundle.json` or the file's name. A file source never updates: a new build is a different
file, so it is a different source with different identities. Remove the old one before you add the new one.

### Add-source links

```
https://kavapp.link/add-source?github=owner/repo
kav://add-source?github=owner/repo
```

Both open Kav's add-source sheet for that repository. `kavx link github owner/repo` prints them, with a Markdown
"Add to Kav" link. Kav always shows the sheet and asks before it adds anything.

### The block list

Kav's security block list is Kav's own signed file. An entry names a GitHub repository or a bundle's SHA-256, with a
security reason. A blocked source can't be added, and what was installed from it is switched off. The rider can turn
the list off under Settings › Extensions › Options.

### Names

A source's name and an extension's `displayName` are shown inside Kav's own sentences, so Kav prints them as plain
text (`Package.kt`, `Names.clean`). Any language and any emoji is a name. Kav cleans each one before it shows it, and
never refuses one for it:

- Unicode's NFKC form;
- control and formatting characters removed: line breaks, text direction controls, zero-width characters (except a
  joiner between two emoji, which makes a family or a flag one picture), private-use and unassigned code points,
  half of a surrogate pair on its own, and the Hangul filler letters, which draw as nothing;
- double quotes of every kind removed, except a straight one between two Hebrew letters, which is how a Hebrew
  abbreviation is typed (ת"א, בע"מ);
- any run of spaces one space, none at the ends;
- at most 64 characters.

A `displayName` that is empty after cleaning is the extension's `name` instead, and a `displayNameHe` that is empty
after cleaning is no Hebrew name.

Kav lists extensions (in Settings › Extensions, a source's page, the themes and the home screen widget picker) by the
name riders see, alphabetically in Kav's language. In Hebrew, names whose first letter is Latin come after the rest.

Nothing an extension draws itself is cleaned: its views, a filter's reasons and its tags are shown as they come.


## What riders see about your extension

When a rider adds your source, and on each extension's page, Kav tells them in plain words what the extension is and
what it can do. This page says where each of those lines comes from, so that what a rider reads is what you meant.
Riders switch extensions on at their own risk, the way they would a browser add-on: these lines are how they decide.

### Adding a source

Adding a source (by typing the repository, from an add-source link, or with "Add a bundle file") shows one sheet:

- the source's name and picture (for a GitHub source, its owner's GitHub picture) and its address;
- "Third-party. Not made or reviewed by Kav.", or "Made by Kav." for Kav's own source, github.com/ImNoammm/kav-extensions;
- "Its extensions come in off: you switch on the ones you want.";
- each extension in the bundle with its icon, its name and its "It can" lines (below). One made for an older version
  of Kav says so instead.

The first time, before any source, Kav shows "Before you add extensions": they are made by other people, Kav doesn't
review them, they can do anything Kav can on the phone, and installing one is the rider's own call.

After Add, every extension of the source shows in Settings › Extensions with its own switch, off until the rider
switches it on. On, it runs at once. Each extension's page shows the same lines under "It can".

### Kav writes the lines, from your manifest

You do not write these lines and you cannot change them. One function in Kav (`ext/ExtUses.kt`) turns your
`manifest.json` into the lines, and Kav words each one itself. No line comes from your description or your README.

These are all the lines there are, in the order Kav lists them. The exact words, in English and Hebrew, are in
[riders-wording.md](https://kavapp.link/docs/extensions/riders-wording).

| Line | Your extension has it when | Key |
|---|---|---|
| **What it is**, one line per kind: a trip planner, a provider of timetables and stop boards, routes added to results (API 5), a walking model, a transfer rule, a route filter, a ranker, route tags | it has a contribution with capability `plan.provide`, `board.provide`, `results.add`, `walk.time`, `transfer.policy`, `results.filter`, `results.rank` or `results.annotate`. One line per capability, in that order | the capability |
| **It has its own screens** | it has a `page.provide` contribution | `page.provide` |
| **It has a page the rider can pin as a tab** | one of its `pages` has `"tab": true` | `tab` |
| **It can replace the Home screen** | it has a `home.body` contribution | `home.body` |
| **It changes how Kav looks** | it is a theme (`"kind": "theme"`) or has a `theme` contribution | `theme` |
| **It draws on Kav's maps**, **sends notifications**, **uses the rider's location**, **has settings of its own** | `adds` lists `map`, `notifications`, `location` or `settings`. One line each | the word |
| **It can add a card to the Home screen** | it has a `slot.home` contribution | `slot.home` |
| **It can add a line to trip results** | it has a `slot.results` contribution | `slot.results` |
| **It can add a part to a trip's page** | it has a `slot.trip` contribution | `slot.trip` |
| **It can add a row to stop boards** | it has a `slot.stop` contribution | `slot.stop` |
| **It adds widgets to the home screen** | it has a `widget.provide` contribution (API 5) | `widget.provide` |
| **It takes over**, naming the actions: paying, opening stops, trip searches... | it has an `action.<name>` contribution (API 4) | `actions` |
| **It changes parts of Kav's screens**, naming them: Home's pay card, a trip's rides... | it has a `part.<name>` contribution (API 4) | `parts` |
| **It changes how Kav itself works** | it has a `hooks` contribution (API 4) | `hooks` |
| **It sees the rider's trip searches** | it has any of the kinds above except `board.provide`: a stop board is not given trip searches | `searches` |
| **It changes the rider's results**, saying which: walk times, which changes can be made, which routes show, their order, tags on routes | it has any route rule. A planner alone does not count | `results` |
| **It keeps its own data on the phone** | it declares any `settings` | `data` |
| **It runs its own Android code: full access** | the manifest has `native`, which every extension has | `native` |
| **It asks for permissions**, naming them | `permissions`, or any contribution's `permissions`, lists anything | `permissions` |

Kav can't see what your Android code does by itself. `adds` is how you tell riders: draws on the map, posts
notifications, uses the location, has its own settings view. Say what your code does.

Beside the lines Kav always shows that the extension is a third party's (or Kav's own), the source it came from, and
a "Report an issue" link (your `issues`, or the source's GitHub issues). Your `description` is shown on the
extension's page.

### Your own reason under a line

The "Key" column is for `reasons`, an optional field of the manifest (its old name, `explanations`, is still read).
With it you put one short reason of your own under a line: why the extension needs the location, what the data it
keeps is.

```json
"reasons": {
  "location": { "en": "Reads the GPS only while the card is on screen" },
  "data": { "en": "Keeps one number: how much slower or faster than usual you walk" }
}
```

Kav shows the reason under its own line, after "From the publisher:", in the rider's language when you gave it
(`en`, `he`) and in the other one when you did not.

- It never replaces Kav's line, and it cannot add, hide or reorder one.
- A reason for a line your extension does not have is never shown. So is one for a key Kav does not know.
- At most 140 characters in each language, or Kav refuses the extension ([format.md](https://kavapp.link/docs/extensions/format), "Reasons").

### So declare only what the extension needs

Every entry in the manifest becomes something a rider reads before they switch it on.

- **A contribution you declare "for later" still shows as something the extension is.** Declare it in the release
  that uses it.
- **Settings read as "keeps its own data".** They are the rider's own values, kept on the phone. A reason under that
  line saying what they are ("Only the town you type") costs you one sentence.
- **A card shows once the extension is on.** The rider can turn it off on your extension's page.
- **Leave `permissions` empty** unless you mean something by it. Kav shows each one to the rider as it is written.
- **An update shows the new lines on the extension's page and runs at once.** Kav doesn't ask again, so tell riders
  in your release notes when a release starts doing something new.

### See it before a rider does

`kavx check <folder>` and `kavx verify <file.kavb>` print the lines your manifest produces, with your reasons, and
warn about a reason that would never be shown:

```
  Speed card (speed-card) 1.0.0, release 1
  does       slot.home as "card"
  native     org.example.speed.Speed, from classes.dex
  riders see adds: location
             from the publisher: Reads the GPS only while the card is on screen
  riders see adds: notifications
  riders see can add a card to the Home screen
  riders see runs its own Android code: full access
```

These are the kit's descriptions of the lines, not Kav's wording. If a line here surprises you, it will surprise a
rider.


## The words a rider meets

When you tell riders how to use your extension, use Kav's own names for things. These are the exact words, in English
and Hebrew, taken from Kav 5's code (`ui/ExtensionsScreen.kt`, `ui/ExtensionParts.kt`, `ui/ExtSlots.kt`,
`ui/ExtPages.kt`, `ui/ExtHiddenRoutes.kt`, `ui/DirectionsOnline.kt`). **If the app and this page ever differ, the app
is right**: wording changes between versions, so quote a switch's name, and do not build a manual on a whole
sentence.

### Where things are

A rider reaches everything by one path: **Settings**, then **Extensions**. That screen lists each source with its
extensions, each with its own switch. A tap on an extension opens its page.

| English | Hebrew | Where, or when |
|---|---|---|
| Extensions | תוספים | the entry in Kav's Settings, and the screen's title |
| Add a source | הוספת מקור | the plus at the top, and the sheet it opens: "A GitHub repository whose releases carry a Kav bundle." |
| Sources | מקורות | a row on the Extensions screen: every source; its header has Check now (a refresh icon) and the plus |
| Check now | בדיקה עכשיו | asks GitHub for every source's latest release now |
| Add a bundle file | הוספת קובץ חבילה | under the plus: adds a `.kavb` from the phone as a source of its own |
| From the community list | מרשימת הקהילה | under the plus: up to five sources from kavapp.link/community not added yet |
| Options | אפשרויות | on the Extensions screen: the security block list, safe mode, About extensions |
| Security block list | רשימת חסימה אבטחתית | the switch there, on by default |
| Safe mode | מצב בטוח | the switch there: Kav starts with every extension off |
| Its extensions come in off: you switch on the ones you want. | התוספים שלו נוספים כבויים: אתם מפעילים את אלה שאתם רוצים. | on the sheet that adds a source |
| What it does for you | מה הוא עושה בשבילכם | section of an extension's page: a planner and route rules, one switch each |
| Its screens and look | המסכים והמראה שלו | section: its pages, tab, Home screen and theme |
| Its settings | ההגדרות שלו | section: the settings your manifest declares, filled in right there |
| It can | התוסף יכול | section: what it is and does (the lines below); a card's row ends in its switch |
| Log | יומן | a row that opens its log: its `Kav.log` lines and its failures |
| Report an issue | דיווח על בעיה | on an extension's page and a source's page |
| Remove | הסרה | on a source's page: removes the source, its extensions and their settings |

### The switches

| English | Hebrew | For |
|---|---|---|
| Plan my trips with it | תכנון הנסיעות שלי איתו | `plan.provide` |
| Time my walks with it | חישוב זמני ההליכה שלי איתו | `walk.time`. Starts as Required |
| Judge my transfers with it | בדיקת ההחלפות שלי איתו | `transfer.policy`. Starts as Required |
| Filter my routes with it | סינון המסלולים שלי איתו | `results.filter`. Starts optional |
| Order Recommended with it | סידור "מומלץ" איתו | `results.rank` |
| Show its tags on routes | הצגת התגיות שלו על מסלולים | `results.annotate` |
| Required | חובה | under a walking model, a transfer rule or a filter that is on: "Without it, a search shows no routes" |
| Use it as my Home screen | שימוש בו כמסך הבית שלי | `home.body`, with "Kav's Home shows if it fails" |
| Use its look | שימוש במראה שלו | an extension's `theme` contribution, with "Settings keep Kav's own look" |
| Use this theme | שימוש בערכת הנושא | a theme (`"kind": "theme"`): its switch at the top of its page. Elsewhere each theme has Apply (החלה), and a tick once applied |
| Theme, Themes | ערכת נושא, ערכות נושא | what Kav calls a theme package everywhere |
| Kav's own look | המראה של Kav | the "Kav" row of the themes list, which takes every theme off |
| Runs its own code, with full access | מריצה קוד משלה, עם גישה מלאה | under a theme's name, when it has `native` |

A page marked `"tab": true` gets a switch named after the page: "Show &lt;page title> as a tab" ("הצגת &lt;page title>
כלשונית"). Every page that opens on its own gets a row "Open &lt;page title>" ("פתיחת &lt;page title>"). A stop board
(`board.provide`) has no switch of its own: its stops are there whenever the extension is on.

A card shows once the extension is on. Its switch is at the end of its row under "It can" (the row "Can add a card to
your Home screen", say), for the rider who wants it off.

### The lines under "It can"

These are the lines of [what-riders-see.md](https://kavapp.link/docs/extensions/what-riders-see), in Kav's words. Where a line ends with a colon, Kav
adds the permissions or what is changed.

| English | Hebrew | Line |
|---|---|---|
| Trip planner | מתכנן מסלולים | `plan.provide` |
| Provides timetables and stop boards | מספק לוחות זמנים ולוחות תחנה | `board.provide` |
| Adds routes to your results | מוסיף מסלולים לתוצאות | `results.add` (API 5) |
| Walking model | מודל הליכה | `walk.time` |
| Transfer rule | כלל החלפות | `transfer.policy` |
| Route filter | מסנן מסלולים | `results.filter` |
| Ranker | מדרג מסלולים | `results.rank` |
| Route tags | תגיות למסלולים | `results.annotate` |
| Adds its own screens | מוסיף מסכים משלו | key `page.provide` |
| Adds a tab you can pin | מוסיף לשונית שאפשר להצמיד | key `tab` |
| Can replace your Home screen | יכול להחליף את מסך הבית שלכם | key `home.body` |
| Changes how Kav looks | משנה את המראה של Kav | key `theme` |
| Draws on Kav's maps | מצייר על המפות של Kav | `adds`: `map` |
| Sends notifications | שולח התראות | `adds`: `notifications` |
| Uses your location | משתמש במיקום שלכם | `adds`: `location` |
| Has settings of its own | יש לו הגדרות משלו | `adds`: `settings` |
| Can add a card to your Home screen | יכול להוסיף כרטיס למסך הבית שלכם | key `slot.home` |
| Can add a line to your trip results | יכול להוסיף שורה לתוצאות החיפוש שלכם | key `slot.results` |
| Can add a part to a trip's page | יכול להוסיף חלק לעמוד של נסיעה | key `slot.trip` |
| Can add a row to stop boards | יכול להוסיף שורה ללוחות תחנה | key `slot.stop` |
| Adds widgets to your home screen | מוסיף ווידג׳טים למסך הבית שלכם | key `widget.provide` |
| Takes over: | לוקח על עצמו: | key `actions`, followed by the actions (paying: תשלום, opening stops: פתיחת תחנות...) |
| Changes parts of Kav's screens: | משנה חלקים במסכים של Kav: | key `parts`, followed by the parts (Home's pay card: כרטיס התשלום במסך הבית...) |
| Changes how Kav itself works | משנה את האופן שבו Kav עצמה פועלת | key `hooks` |
| Sees your trip searches | רואה את חיפושי המסלולים שלכם | key `searches` |
| Changes your results: | משנה את התוצאות שלכם: | key `results`, followed by what changes |
| Keeps its own data on your phone | שומר מידע משלו בטלפון שלכם | key `data` |
| Runs its own Android code: full access | מריץ קוד Android משלו: גישה מלאה | key `native` |
| Asks for: | מבקש הרשאות: | key `permissions` |

After "Changes your results:" Kav lists what changes: "walk times" (זמני הליכה), "which changes you can make" (אילו
החלפות אפשר לעשות), "which routes show" (אילו מסלולים מוצגים), "their order" (הסדר שלהם), "tags on routes" (תגיות על
מסלולים).

Kav also has shorter forms of some lines: "Its own screens" (מסכים משלו), "A tab you can pin" (לשונית שאפשר להצמיד),
"Home card" (כרטיס במסך הבית), "Line on trip results" (שורה בתוצאות החיפוש), "Part on a trip's page" (חלק בעמוד של
נסיעה), "Row on stop boards" (שורה בלוחות תחנה), "Home screen widgets" (ווידג׳טים למסך הבית), "Timetables and stop boards" (לוחות זמנים ולוחות תחנה), "Keeps data
on this phone" (שומר מידע בטלפון הזה).

Beside the lines: "Third-party. Not made or reviewed by Kav." ("צד שלישי. Kav לא יצרה ולא בחנה אותו."), or "Made by
Kav." ("נוצר על ידי Kav.") for Kav's own source. Your reason follows a line after "From the publisher:" ("מהמפרסם:").

A Home card of yours has your extension's name as its title. Nothing else of yours carries a label: not a route your planner gave, a line of yours under a search's route, a stop of yours in Kav's lists, nor a card on a trip's page or a stop board.

### When something of yours hides routes or does not run

| English | Hebrew | Where, or when |
|---|---|---|
| &lt;n> routes hidden by &lt;name> | &lt;n> מסלולים הוסתרו על ידי &lt;name> | under a search's routes, with a "Show" (הצגה) button; "1 route hidden by &lt;name>" for one |
| Showing &lt;n> hidden routes | מוצגים גם &lt;n> מסלולים שהוסתרו | after the rider tapped Show |
| Shown without &lt;name>: it didn't answer in time. | המסלולים מוצגים בלי &lt;name>, כי הוא לא ענה בזמן. | under a search, when an optional rule was skipped |
| Shown without &lt;name>: Kav couldn't ask it about these routes. | המסלולים מוצגים בלי &lt;name>, כי Kav לא הצליחה לשאול אותו על המסלולים האלה. | under a search, when what Kav would send an optional rule is more than one call carries |
| Can't use your walking rules (&lt;name>) right now, so no routes are shown. | אי אפשר להשתמש כרגע בכללי ההליכה שלכם (&lt;name>), ולכן לא מוצגים מסלולים. | a required rule failed; also "your transfer rules", "your route filter". With "Try again" and "Show routes without this check" |
| &lt;name> couldn't plan this trip. | &lt;name> לא הצליח לתכנן את הנסיעה הזו. | the planner failed; Kav's own planner isn't asked instead |
| &lt;name> answered something Kav can't read as routes. | &lt;name> החזיר תשובה ש-Kav לא יכולה לקרוא כמסלולים. | the planner's answer was refused |
| Kav turned it off | Kav כיבתה אותו | on the extension's page, with "It made Kav close twice" and a "Turn on" button |
| It made Kav close | הוא גרם ל-Kav להיסגר | on the Extensions screen, under that extension |

Under "&lt;n> routes hidden by &lt;name>" Kav puts the reasons your filter or transfer rule gave, in a small line, as you
wrote them. With two extensions it names both ("by &lt;one> and &lt;other>"); with more, "by &lt;n> extensions" ("על ידי &lt;n>
תוספים").

A card whose code fails shows nothing at all, not a message.


## Testing

An extension is Android code, so it runs only inside Kav. Test it in three steps.

### 1. Check it

```
kavx check <folder>
```

This builds the folder and checks it the way Kav does: the manifest, the dex, the icon, the pages, settings and
theme. It prints what riders will see and warns about what Kav accepts but you probably did not mean (an unknown
manifest field, a reason that is never shown, a theme color that is hard to read). `REFUSED:` means Kav would
refuse it, and says why.

### 2. Unit-test your hook code

Your hooks take JSON text and answer JSON text ([hooks.md](https://kavapp.link/docs/extensions/hooks)), so test them on your computer like any other
Kotlin or Java code: build an input shaped as hooks.md describes, call your hook, and check the answer. Keep the code
that works out the answer in a plain function with no Android types, and the test needs nothing from Kav.

```kotlin
// In your extension:
fun hideLongWalks(input: String): String {
    val routes = JSONObject(input).getJSONArray("routes")
    return JSONArray().apply {
        for (i in 0 until routes.length()) {
            val legs = routes.getJSONObject(i).getJSONArray("legs")
            val walked = (0 until legs.length()).map { legs.getJSONObject(it) }.filter { it.getString("kind") == "walk" }.sumOf { it.getInt("metres") }
            put(if (walked > 1500) "Over 1.5 km on foot" else true)
        }
    }.toString()
}

// In a test, with org.json on the classpath:
val search = """{"query": {"way": "d", "at": 1800000000, "now": 1800000000, "settings": "nnnn", "locale": "en"},
  "routes": [{"index": 0, "legs": [{"kind": "walk", "metres": 2000}]}, {"index": 1, "legs": [{"kind": "walk", "metres": 300}]}],
  "walks": [], "transfers": []}"""
check(hideLongWalks(search) == """["Over 1.5 km on foot",true]""")
```

`org.json` is part of Android, not of the JVM: add a copy of it (the `org.json:json` artifact) to your test's
classpath only. Your extension uses Android's own.

Mind the deadlines when you test: 200 ms for a walking model, 150 ms for the other rules, 400 ms for all of them
together, on a phone slower than your computer.

### 3. Try it in Kav

On a phone or an emulator with Kav 5 or newer:

1. `kavx bundle <folder>` writes a `.kavb`.
2. Copy it to the device: `adb push my-extension.kavb /sdcard/Download/`.
3. In Kav: Settings › Extensions › Sources › + › Add a bundle file, and pick it. Add.
4. Switch the extension on. It runs at once.
5. Watch `adb logcat -s KavNative KavNativeGuard KavExtHooks KavExt`, and the "Log" row on its page
   ([native.md](https://kavapp.link/docs/extensions/native), "Seeing logs").

A bundle file never updates. For the next build, remove the old source (its page, Remove) and add the new file.

To try the path your riders take, publish a GitHub Release with the `.kavb` on it and add the repository in Kav
(type `owner/repo`, or open the link `kavx link github owner/repo` prints). Then Check now picks up each new release.
`kavx verify <file.kavb>` reads a bundle as Kav will before you publish it.


## The Kotlin starter

One extension in one Kotlin file. Copy it, rename it, and build it.

A card on Kav's Home with a button and a dialog, and a screen of its own, in one Kotlin file. Copy this folder,
change the names, and build it:

```
kavx check <this folder>       # builds it and prints what Kav and riders would see
kavx bundle <this folder>      # writes <folder name>.kavb
```

That compiles `src/` against Kav's API, makes the dex and writes a bundle with this one extension in it. You need a
JDK, the Android SDK and the Kotlin compiler; the kit's `docs/native.md` says where to get them, and explains every
method used here.

- `manifest.json`: what Kav and riders are told. Change `name`, `displayName`, `description`, and `native.entry` if
  you rename the class or its package. A card needs its `slot.home` contribution, a screen its page under `pages`.
- `src/org/example/hello/Hello.kt`: the code. `start` registers the card and the screen; Kav asks each for a view
  when its screen shows.

To try it: copy the `.kavb` to a phone or emulator with Kav 5 or newer, open Settings › Extensions › Sources › Add a
bundle file, add it, and switch Hello Kotlin on. The card is on Home, and the screen opens from the extension's page
in Settings › Extensions.


### `manifest.json`

```json
{
  "format": 1,
  "name": "hello-kotlin",
  "displayName": "Hello Kotlin",
  "version": "1.0.0",
  "release": 1,
  "minKav": 45,
  "api": 3,
  "description": "A card on Kav's Home and a screen of its own, written in Kotlin.",
  "native": { "dex": "classes.dex", "entry": "org.example.hello.Hello" },
  "contributions": [
    { "id": "card", "capability": "slot.home" },
    { "id": "screens", "capability": "page.provide" }
  ],
  "pages": [
    { "id": "about", "title": { "en": "Hello Kotlin", "he": "שלום קוטלין" } }
  ]
}
```

### `src/org/example/hello/Hello.kt`

```kotlin
package org.example.hello

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension

// Kav makes this class (the manifest's "native" entry) and calls start once each time Kav starts, on a background
// thread. Everything the extension adds to Kav is registered here.
class Hello : KavExtension {
    override fun start(kav: Kav) {
        // The Home card: the manifest declares it as the contribution "slot.home". Kav asks for a view each time Home
        // shows it, on the main thread.
        kav.card("home") { context, _ -> card(kav, context) }
        // The screen for the page "about": Settings › Extensions › Hello Kotlin › About opens it.
        kav.screen("about") { context, _ -> about(kav, context) }
        // A line in its log, on its page in Settings › Extensions.
        kav.log("Hello Kotlin started, API ${kav.apiVersion()}, language ${kav.locale()}")
    }

    private var taps = 0

    // Kav's own card, button and dialog, so it looks like the rest of Kav in light, dark and Hebrew.
    private fun card(kav: Kav, c: Context): View {
        val ui = kav.ui()
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(kav, c, if (kav.locale() == "he") "שלום מקוטלין" else "Hello from Kotlin", "headline"))
        box.addView(ui.button(c, if (kav.locale() == "he") "לחצו כאן" else "Tap me", true) {
            taps++
            kav.log("tapped $taps times")
            ui.dialog(c, "Hello Kotlin", "You tapped $taps time${if (taps == 1) "" else "s"}.", "OK") {}
        })
        return ui.card(c, box)
    }

    private fun about(kav: Kav, c: Context): View {
        val ui = kav.ui()
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.header(c, "About"))
            addView(ui.row(c, "info", "Hello Kotlin", "A starter extension for Kav", null))
            addView(ui.row(c, "check", "Your extension's id", kav.id(), null))
        }
    }

    // A text in Kav's colours and type sizes, for a view the extension draws itself.
    private fun text(kav: Kav, c: Context, s: String, style: String) = TextView(c).apply {
        text = s
        setTextColor(kav.ui().color("text"))
        textSize = kav.ui().textSize(style)
        setPadding(0, 0, 0, (kav.ui().dp("gap3") * c.resources.displayMetrics.density).toInt())
    }
}
```

## Publishing: the source template

Extensions for [Kav](https://kavapp.link), the open-source transit app. Made and published by me, not by Kav.

[![Add to Kav](https://kavapp.link/img/add-to-kav.svg)](https://kavapp.link/add-source?github=OWNER/REPO)

To add this source by hand: in Kav open Settings, Extensions, Sources, the plus, and enter `OWNER/REPO`. Its
extensions come in off: switch on the ones you want.

### What is here

| Extension | What it does |
|---|---|
| My extension | Say in a sentence what it does for a rider. |

Problems with these extensions go to this repository's issues, not to Kav.

### For developers

This repository was started from the Kav extension kit's template. Replace `OWNER/REPO` and the table above, and
delete this section when you no longer need it.

Each extension is a folder under `extensions/`: its `manifest.json` and its Kotlin or Java under `src/`. The kit's
`kavx` builds them all into one bundle (`.kavb`), and each GitHub Release of this repository carries it. Kav reads the
latest release's bundle.

```
kavx check extensions/my-extension     builds one extension and checks it the way Kav does
kavx bundle . --out my.kavb            builds the bundle; add it in Kav with Settings, Extensions, Sources, +, Add a bundle file
```

#### Releasing

Raise `release` and `version` in the manifest of each extension that changed, commit, then tag and push
(`git tag v1 && git push origin v1`). `.github/workflows/release.yml` builds the bundle and attaches it to a release
for the tag. By hand: `kavx bundle . --out my.kavb`, then `gh release create v1 my.kavb`.

### License

GPL-3.0. See [LICENSE](https://kavapp.link/docs/license).


### `.github/workflows/release.yml`

```yaml
# Builds the bundle of every extension under extensions/ and attaches it to a GitHub Release when a tag is pushed
# (git tag v1 && git push origin v1). Kav reads the latest release's bundle at its next check.
#
# The extensions are Kotlin or Java: they need a JDK and the Android SDK, which GitHub's Ubuntu runner has, and the Kotlin
# compiler, which this downloads. It checks out the Kav extension kit from github.com/ImNoammm/kav-extension-kit beside
# this repository and runs its kavx (the kit has no dependencies). Until the kit is published there, this workflow
# fails and the bundle is built and released by hand (README, "Releasing").
#
# UNTESTED ON GITHUB: its commands were run by hand on a computer, but it has not run on GitHub yet. Read its first
# run's log.
name: Release

on:
  push:
    tags: ['v*']

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-24.04
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          path: source

      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          repository: ImNoammm/kav-extension-kit
          path: kav-extension-kit

      - uses: actions/setup-node@949feb2413d6458794dcd2491c4babbbce0c15c1 # v7.1.0
        with:
          node-version: 24

      - name: Get the Kotlin compiler
        run: |
          curl -fsSL -o "$RUNNER_TEMP/kotlin.zip" https://github.com/JetBrains/kotlin/releases/download/v2.2.10/kotlin-compiler-2.2.10.zip
          unzip -q "$RUNNER_TEMP/kotlin.zip" -d "$RUNNER_TEMP"
          echo "KOTLINC=$RUNNER_TEMP/kotlinc/bin/kotlinc" >> "$GITHUB_ENV"

      - name: Build the bundle
        working-directory: source
        run: |
          node ../kav-extension-kit/bin/kavx.js bundle . --name "${{ github.event.repository.name }}" --out "$RUNNER_TEMP/${{ github.event.repository.name }}.kavb"
          node ../kav-extension-kit/bin/kavx.js verify "$RUNNER_TEMP/${{ github.event.repository.name }}.kavb"

      - name: Release it
        working-directory: source
        env:
          GH_TOKEN: ${{ github.token }}
        run: gh release create "$GITHUB_REF_NAME" "$RUNNER_TEMP/${{ github.event.repository.name }}.kavb" --verify-tag --title "$GITHUB_REF_NAME" --generate-notes
```

</div>
