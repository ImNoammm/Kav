---
title: "The bundle format"
editLink: false
---

<div v-pre>

# The bundle format

What `kavx bundle` writes and what Kav reads. Taken from Kav 5's code, files under
`android/app/src/main/java/uk/noammm/kav/ext/`: `Bundle.kt`, `Archive.kt`, `Package.kt`, `Native.kt`, `GitHub.kt`,
`Hub.kt` and `Registry.kt`. In the kit, every number and rule on this page lives in one file, `lib/format.js`. If
this page and Kav's code disagree, the code is right.

## A bundle

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

### The archive (`Archive.kt`)

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

### JSON

`bundle.json` and every `manifest.json` must be UTF-8, with no duplicate keys, at most 32 levels deep, and nothing
after the closing brace. Every number a rule reads (`format`, `release`, `minKav`, `api`) must be a JSON integer:
`1`, never `"1"`, `1.0` or `1e0`.

## `manifest.json` (`Package.kt`)

At most 64 KB.

```json
{
  "format": 1,
  "name": "speed-card",
  "displayName": "Speed card",
  "version": "1.0.0",
  "release": 1,
  "minKav": 45,
  "api": 2,
  "description": "Your speed on Home, from the phone's GPS.",
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
| `kind` | `"theme"` for a theme ([screens.md](/docs/extensions/screens#themes)): Kav calls it a theme, lists it with themes and applies one at a time. Anything else, or none, is an extension. API 5. |
| `version` | What riders see as the version. Any text up to 32 characters. Kav does not compare versions. |
| `release` | Needed: a whole number from 1. Riders see it beside the version. Raise it with each release. |
| `minKav` | The lowest Kav version code that can run it. Kav 5 is 45; set at least 45. An extension that needs a newer Kav is listed and can't be switched on. Default 0. |
| `api` | The API it was made for: `2` or `3` for Kav 5.0, `4` for actions, screen parts, tickets and hooks, with Kav 5.1 (`minKav` 47). Default 1. |
| `description` | At most 2000 characters. |
| `native` | Its Android code: `{ "dex": "<path in its folder>", "entry": "<full class name>" }`. Needed, except for a theme, which may bring none. See [native.md](/docs/extensions/native). |
| `permissions` | A list of plain words (the same rule as `name`). Kav shows them to riders as written. |
| `adds` | What it adds that Kav can't see in its code: any of `"map"` (draws on Kav's maps), `"notifications"`, `"location"` (uses the phone's location) and `"settings"` (a settings view of its own). Kav says these, with what the rest of the manifest shows (a Home card, a tab, route rules), on its row in Settings › Extensions and under "It can". Another word is ignored. |
| `contributions` | What it offers. Each: `id` (same rule as `name`, unique in the manifest), `capability` (a plain word; one from the table below does something, any other installs and does nothing), and optionally its own `permissions`. An old `domain` field is ignored. |
| `icon` | Optional: a picture in its folder (`.png`, `.jpg`, `.jpeg` or `.webp`), shown where it is listed. |
| `pages`, `settings` | Its own screens and what the rider can set. See [screens.md](/docs/extensions/screens). |
| `widgets` | Its home screen widgets, for a `widget.provide` contribution (API 5). See [screens.md](/docs/extensions/screens#home-screen-widgets). |
| `theme` | Its look: colors, line badges and corners, for a theme or a `theme` contribution. See [screens.md](/docs/extensions/screens#themes). |
| `hooks` | For a `hooks` contribution: each function of Kav's it hooks, `{ "class", "method", "params": [types] }`. See [native.md](/docs/extensions/native#hooks-into-kav-itself). |
| `tags` | For an annotator: words for up to 32 of its tags, `"id": { "en": "words", "he": "words" }`. `en` is needed, each at most 40 characters. |
| `reasons` | Your own short reason under a line Kav shows riders. See "Reasons" below. Its old name, `explanations`, is still read. |
| `issues`, `homepage` | `https://` links. Without `issues`, "Report an issue" goes to the source's GitHub issues. |
| anything else | Ignored. A misspelled field (`reason` for `reasons`) is silently lost. `kavx check` warns about every field it does not know. |

### Capabilities (`Contracts.kt`)

| Capability | What it is |
|---|---|
| `walk.time`, `transfer.policy`, `results.filter`, `results.rank`, `results.annotate` | the route rules ([hooks.md](/docs/extensions/hooks)) |
| `plan.provide` | a trip planner |
| `board.provide` | stops, timetables and stop boards |
| `page.provide`, `home.body` | its own pages, and the page `home` as a Home body |
| `theme` | an extension's look from the manifest (a theme, `"kind": "theme"`, needs no contribution for it) |
| `slot.home`, `slot.results`, `slot.trip`, `slot.stop` | a card on Home, a line under each search result, a card on a trip's page, a card on stop boards |
| `action.<name>` (API 4) | takes over one of Kav's actions, `action.pay` for paying ([native.md](/docs/extensions/native#actions) lists them) |
| `part.<name>` (API 4) | adds a view to one of Kav's screen parts, or replaces it, `part.home.pay` for Home's pay card ([native.md](/docs/extensions/native#screen-parts)) |
| `hooks` (API 4) | hooks on Kav's own code, each function listed under the manifest's `"hooks"` ([native.md](/docs/extensions/native#hooks-into-kav-itself)) |
| `widget.provide` (API 5) | home screen widgets, each listed under the manifest's `"widgets"` ([screens.md](/docs/extensions/screens#home-screen-widgets)) |

Each part of an extension has every power the manifest declares. Code you register only shows when the manifest
declares it too ([native.md](/docs/extensions/native) has the table).

### Reasons

Kav shows riders a few lines about every extension, in its own words ([what-riders-see.md](/docs/extensions/what-riders-see)).
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

## Identity

An extension's identity is its source plus its name: `github.<owner>.<repo>/<name>` for a GitHub source (in lower
case), `file.<16 hex digits of the bundle's SHA-256>/<name>` for a bundle file. `Kav.id()` gives it. The same name in
another source is another extension, with its own switch and data.

## A source on GitHub (`GitHub.kt`, `Hub.kt`)

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

## A bundle file

A rider can add a `.kavb` from the phone (Settings › Extensions › Sources › + › Add a bundle file). It becomes a source of
its own, named by its `bundle.json` or the file's name. A file source never updates: a new build is a different
file, so it is a different source with different identities. Remove the old one before you add the new one.

## Add-source links

```
https://kavapp.link/add-source?github=owner/repo
kav://add-source?github=owner/repo
```

Both open Kav's add-source sheet for that repository. `kavx link github owner/repo` prints them, with a Markdown
"Add to Kav" link. Kav always shows the sheet and asks before it adds anything.

## The block list

Kav's security block list is Kav's own signed file. An entry names a GitHub repository or a bundle's SHA-256, with a
security reason. A blocked source can't be added, and what was installed from it is switched off. The rider can turn
the list off under Settings › Extensions › Options.

## Names

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

A `displayName` that is empty after cleaning is the extension's `name` instead.

Nothing an extension draws itself is cleaned: its views, a filter's reasons and its tags are shown as they come.

</div>
