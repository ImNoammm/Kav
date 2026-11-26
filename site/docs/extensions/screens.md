---
title: "Screens: cards, pages, settings, the Home body, themes and home screen widgets"
editLink: false
---

<div v-pre>

# Screens: cards, pages, settings, the Home body, themes and home screen widgets

What an extension can show on Kav's screens. Taken from Kav 5's code: `ext/Slots.kt`, `ext/Pages.kt`,
`ext/Blocks.kt`, `ext/Looks.kt`, `ext/Widgets.kt` (paths under `android/app/src/main/java/uk/noammm/kav/`). If this page and the code
disagree, the code is right; please report it.

Every card and screen is an Android view your code makes ([native.md](/docs/extensions/native)). To look like the rest of Kav, build
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

## Cards on Kav's screens

| Slot | Capability | `kav.card(on, ...)` | Where Kav puts it | `about` |
|---|---|---|---|---|
| Home card | `slot.home` | `"home"` | On Home, after Kav's own suggestions; three extensions at most | `{}` |
| Results line | `slot.results` | `"results"` | In the footer of each route's card in a search's results | `{"trip": <the route>}` |
| Trip page | `slot.trip` | `"trip"` | After Kav's timeline on a trip's page; three extensions at most | `{"trip": <the route>}` |
| Stop board | `slot.stop` | `"stop"` | After Kav's own rows on a stop board; two extensions at most | `{"stop": "il.mot/gtfs/stop/<code>", "name": "..."}` |

A route is in the shape route rules get it ([hooks.md](/docs/extensions/hooks), "A route"): times, fare, tags, and its legs with
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
  slow on a thread of your own. A call that runs past 3 seconds counts as a crash ([native.md](/docs/extensions/native)).
- Return `null` to show nothing. A results line then takes no space.
- A Home card isn't told where the rider is. Use the phone's GPS from your code (`samples/native/speed-card`), or ask
  for a place in a setting.

## Pages: screens of your own

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

## The Home body

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

## Settings

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
`kav.settingsScreen(...)` ([native.md](/docs/extensions/native)): Kav shows it under the declared settings, or alone. Say
`"settings"` in `adds` so riders are told.

`kav.settings()` gives every declared setting as JSON text: what the rider set, or its default where they set
nothing or the value no longer fits the declaration. An update keeps the values. To know at once when the rider
changes one:

```kotlin
kav.onSettings { all -> pins.redraw(JSONObject(all)) }   // all of them, as kav.settings() gives them
```

## Blocks

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

## Themes

A theme is a package that says `"kind": "theme"` in its manifest. Kav calls it a theme everywhere, lists it apart
from extensions (Settings › Look, once a theme is installed, and Settings › Extensions › Themes), and
applies one at a time: applying one takes any other off, and "Kav" goes back to Kav's own look. Sources host themes
as they host extensions, in the same bundle or a bundle of their own, and the community list takes them the same way.

```json
{
  "format": 1, "kind": "theme", "minKav": 47, "api": 5,
  "name": "night-sea", "displayName": "Night sea", "version": "1.0.0", "release": 1,
  "description": "Deep blue pages and a sea-green accent.",
  "contributions": [],
  "theme": {
    "looks": { "dark": { "bg": "#0B1A2A", "accent": "#7FD1C7" }, "light": { "accent": "#2E7D72" } },
    "lineBadges": "strip",
    "corners": { "card": 16, "sheet": 24, "tile": 10, "badge": 6 }
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
| `corners` | whole dp: `card` 0 to 32 (Kav's 22), `sheet` 0 to 40 (28), `tile` 0 to 24 (12), `badge` 0 to 16 (8) | Kav's own corner |

A color is `#RRGGBB` or `#AARRGGBB`. A value that isn't a color, a role or a look Kav doesn't know, a badge style it
doesn't know and a corner out of its range are left out. Only a `"theme"` that isn't an object is refused.

Not themeable: type, icons, spacing, Kav's own words, and the map type. The map stays the rider's choice, since
every map but Kav's own loads from someone else while the rider looks; a theme with code can still draw Kav's stop
marks its own way with `kav.map()` ([native.md](/docs/extensions/native)).

**Readability is yours to check.** Kav refuses nothing for it. `kavx check` warns when `text` or `textSecondary` is
below 4.5:1, or `realtime`, `problem` or `critical` below 3:1, on `bg`, `surface` or `surfaceRaised`, where your
theme sets both colors. Look at it in Kav, in each look you set, before you publish.

Kav keeps its own look on its protected screens: Settings (with extension management, sources, safe mode and the
themes list itself), its notices, the report form and navigation. So a theme shows once the rider leaves Settings.

An extension (no `kind`) may still bring a look with a `theme` contribution and the same `"theme"` field, as before.
The rider chooses it with "Use this look" on its page. It is the same single choice as a theme: choosing it takes the
applied theme off.

Samples: [themes/night-sea](../samples/themes/night-sea/manifest.json), colors only, and
[native/quiet-home](../samples/native/quiet-home/src/org/example/quiet/QuietHome.java), a theme with a setting that
hides Home's suggestions card.

## Home screen widgets

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

## When your code fails

A card or screen whose `create` throws or hangs shows nothing, and counts as a crash: 2 crashes in 5 minutes switch
the extension off at the next start ([native.md](/docs/extensions/native), "The crash guard and safe mode"). A Home body that
fails gives the rider Kav's Home, with a line saying so.

</div>
