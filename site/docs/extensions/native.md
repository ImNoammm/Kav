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
- `minKav: 45` is Kav 5. `api: 2` is this API.
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
| `kav.provide("plan.provide", ...)` | a contribution `plan.provide` |
| `kav.provide("walk.time", ...)` and the other route rules | a contribution with that capability |
| `kav.provide("board.stops", ...)`, `"board.departures"` | a contribution `board.provide` |
| `kav.settings()`, `kav.onSettings(...)` | `settings` ([screens.md](/docs/extensions/screens)) |
| `kav.map()`, `kav.settingsScreen(...)`, notifications, location | nothing, but say so in `adds` so riders are told |

## The API

Everything is in `kav.kavapp.api`, and its sources are in `api/src/`. A newer Kav keeps every method of an older
one; `kav.apiVersion()` says which level the Kav in front of you has (2 is Kav 5). Plain types only: text, numbers,
JSON as text, and Android's own `Context`, `View` and `Bitmap`.

**When it runs.** Kav calls `start` once each time Kav starts, and at once when the rider switches the extension on
or an update of it arrives: no restart is needed. Never in safe mode.

**Threads.** `start` runs on a background thread. Kav asks a card or screen for its view on the main thread, each
time the screen shows it. Every `KavUi` method must be called on the main thread. Hooks and `onSettings` are called
on a thread of Kav's, never the main one. `KavMap` may be called from any thread; its tap and stop-look calls come on
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
| `Kav.API` | the API level this jar was built for (2) |
| `apiVersion()` | the API level of the Kav that loaded you |
| `context()` | the application's `Context` |
| `id()` | your identity: your source and your name, `"github.<owner>.<repo>/<name>"`, or `"file.<digest>/<name>"` for a bundle added from a file |
| `locale()` | Kav's language, `"en"` or `"he"` |
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

**Cards.** Kav puts each card under "by &lt;your extension's name>", where its slot is ([screens.md](/docs/extensions/screens)).
Kav calls `create` each time the screen shows the card, on the main thread, so make the view quickly and load
anything slow on a thread of your own.

**Settings** are declared in the manifest: `text`, `number`, `toggle`, `choice`, and the ones Kav picks with its own
pickers, `place`, `stop`, `time`, `date` and `color`, drawn by Kav on your extension's page
([screens.md](/docs/extensions/screens#settings)). Kav's own Map style in github.com/ImNoammm/kav-extensions uses a `place` setting
and its own color picker as `settingsScreen`.

**Hooks** (`provide`) take JSON text and answer JSON text. [hooks.md](/docs/extensions/hooks) has every field:

| Name | Input | Answer |
|---|---|---|
| `walk.time`, `transfer.policy`, `results.filter`, `results.rank`, `results.annotate` | the search: `{"query", "routes", "walks", "transfers"}` | an array, one entry per walk, change or route |
| `plan.provide` | `{"from": [lat, lon], "to": [lat, lon], "at", "way", "modes", "settings"}` | a plan, or `null` for no routes |
| `board.stops` | `{}` | `{"stops": [...], "lines": {...}}` |
| `board.departures` | `{"stop", "from", "limit"}` | an array of departures |

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
ui.chip(c, "Buses", selected = true) { toggle() }
ui.header(c, "Units")
ui.sheet(c, "Details", detailsView)                       // a sheet over the screen
ui.dialog(c, "Hello", "Saved.", "OK") { }                 // with Cancel beside OK
ui.blocks(c, pageJson)                                    // a whole screen from a block tree (screens.md)
val text = ui.color("text")                               // ARGB, 0 for a name Kav doesn't know
val gap = ui.dp("gap3")                                   // dp
val size = ui.textSize("body")                            // sp
```

- Icons for `row`: `star`, `heart`, `bus`, `train`, `walk`, `bike`, `clock`, `map`, `pin`, `info`, `warning`,
  `check`, `plus`, `edit`, `delete`, `search`, `list`, `home`, `calendar`, `settings`. Another name shows none.
- Colors: `bg`, `surface`, `surfaceRaised`, `fill`, `fillStrong`, `separator`, `text`, `textSecondary`,
  `textTertiary`, `surface2`, `surface3`, `surface4`, `borderStrong`, `sunken`, `routeIdle`, `problem`, `critical`,
  `realtime`, `accent`.
- Sizes (`dp`): `rCard`, `rSheet`, `rTile`, `rBadge`, `gap1` to `gap8`. Text styles (`textSize`): `largeTitle`,
  `title`, `headline`, `body`, `subhead`, `caption`.
- The `Changed<T>` callbacks (`switchRow`, `textField`) are told each new value.
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
3. In Kav: Settings › Extensions › Sources › Add a bundle file, and pick it. The sheet lists your extension and what
   it can do, first "Runs its own Android code: full access". Add.
4. Switch your extension on in Settings › Extensions. It starts at once. Your Home card shows on Home, under
   "by &lt;your extension's name>"; your screens open from your extension's page. logcat says
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

The starter, `starter-kotlin/`, is a Home card with a button and a dialog, and a screen of its own.

</div>
