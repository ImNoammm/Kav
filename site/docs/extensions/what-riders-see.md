---
title: "What riders see about your extension"
editLink: false
---

<div v-pre>

# What riders see about your extension

When a rider adds your source, and on each extension's page, Kav tells them in plain words what the extension is and
what it can do. This page says where each of those lines comes from, so that what a rider reads is what you meant.
Riders switch extensions on at their own risk, the way they would a browser add-on: these lines are how they decide.

## Adding a source

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

## Kav writes the lines, from your manifest

You do not write these lines and you cannot change them. One function in Kav (`ext/ExtUses.kt`) turns your
`manifest.json` into the lines, and Kav words each one itself. No line comes from your description or your README.

These are all the lines there are, in the order Kav lists them. The exact words, in English and Hebrew, are in
[riders-wording.md](/docs/extensions/riders-wording).

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

## Your own reason under a line

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
- At most 140 characters in each language, or Kav refuses the extension ([format.md](/docs/extensions/format), "Reasons").

## So declare only what the extension needs

Every entry in the manifest becomes something a rider reads before they switch it on.

- **A contribution you declare "for later" still shows as something the extension is.** Declare it in the release
  that uses it.
- **Settings read as "keeps its own data".** They are the rider's own values, kept on the phone. A reason under that
  line saying what they are ("Only the town you type") costs you one sentence.
- **A card shows once the extension is on.** The rider can turn it off on your extension's page.
- **Leave `permissions` empty** unless you mean something by it. Kav shows each one to the rider as it is written.
- **An update shows the new lines on the extension's page and runs at once.** Kav doesn't ask again, so tell riders
  in your release notes when a release starts doing something new.

## See it before a rider does

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

</div>
