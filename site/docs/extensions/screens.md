---
title: "Screens: cards, pages, settings, the Home body and themes"
editLink: false
---

<div v-pre>

# Screens: cards, pages, settings, the Home body and themes

What an extension can show on Kav's screens. Taken from Kav 5's code: `ext/Slots.kt`, `ext/Pages.kt`,
`ext/Blocks.kt`, `ext/Looks.kt` (paths under `android/app/src/main/java/uk/noammm/kav/`). If this page and the code
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
| Change Kav's colors | `theme` | nothing |

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

- A card shows once the extension is on, under "by &lt;its name>", in the order the extensions were switched on. The
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

A theme is colors in the manifest. Kav reads them; your code does nothing for it. The extension still needs
`"native"` like any other, even if its `start` does nothing.

```json
"contributions": [{ "id": "look", "capability": "theme" }],
"theme": { "looks": { "dark": { "bg": "#0B0F14", "realtime": "#3DDC84" }, "light": { "...": "..." } } }
```

- `looks` lists any of `dark`, `oled` and `light`. A look you leave out stays Kav's.
- Each look sets **any of the 18 roles**, as many as you like: `bg`, `surface`, `surfaceRaised`, `fill`,
  `fillStrong`, `separator`, `text`, `textSecondary`, `textTertiary`, `surface2`, `surface3`, `surface4`,
  `borderStrong`, `sunken`, `routeIdle`, `problem`, `critical`, `realtime`. A role you leave out stays Kav's.
- A color is `#RRGGBB` or `#AARRGGBB`. A value that isn't a color, a role or a look Kav doesn't know, and fields Kav
  doesn't take yet (shapes, type, a map style) are left out. The rider's own accent color stays.
- Only a `theme` contribution whose `"theme"` isn't an object is refused.
- The rider chooses it ("Use its look").

**Readability is yours to check.** Kav refuses nothing for it. `kavx check` warns when `text` or `textSecondary` is
below 4.5:1, or `realtime`, `problem` or `critical` below 3:1, on `bg`, `surface` or `surfaceRaised`, where your
theme sets both colors. Look at it in Kav, in each look you set, before you publish.

Kav keeps its own colors on its protected screens: Settings (with extension management, sources and safe mode), its
notices, the report form and navigation.

## When your code fails

A card or screen whose `create` throws or hangs shows nothing, and counts as a crash: 2 crashes in 5 minutes switch
the extension off at the next start ([native.md](/docs/extensions/native), "The crash guard and safe mode"). A Home body that
fails gives the rider Kav's Home, with a line saying so.

</div>
