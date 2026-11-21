---
title: Extensions
description: What an extension for Kav is, how to make one in Android code and share it, the developer terms, and the security block list.
---

# Extensions

An extension is a package that adds to Kav or changes how it behaves for the rider who installs it: a trip planner,
rules for walk times, changes and which routes show, stop boards and timetables for a service Kav has no data for,
cards on Kav's screens, screens, a tab or a Home of its own, things on Kav's maps, or a new look. You publish it from
your own GitHub repository, as one bundle file in its latest release, and riders add your repository to Kav as a
**source**. Kav does not review, host or approve extensions: each one is its publisher's own work, and Kav tells riders
so.

When a rider adds your source, Kav shows its picture, name and address, marked Third-party, and adds nothing until they
tap Add. Each extension then shows with its own switch, off, and runs as soon as the rider switches it on. What your
manifest declares shows on your extension's page, under Details. Declare what your extension does, and nothing more.
Your code has full access whatever it declares, and riders are told so.

## Android code (Kotlin or Java)

Your extension carries a compiled `classes.dex` that Kav loads and runs inside itself, with full
access: your code can do anything Kav can. The manifest names it:

```json
"native": { "dex": "classes.dex", "entry": "org.example.speed.Speed" }
```

The entry class implements `kav.kavapp.api.KavExtension`. Kav calls its `start(kav)` when the rider switches your
extension on and then once each time Kav starts, on a background thread, and through the `Kav` interface you add cards
to Kav's Home, trip and stop screens, a line under each search result, screens of your own, a Home and a tab, as Android
views; markers, lines and areas on Kav's maps; and hooks (route rules, a trip planner, stop boards). `kav.ui()` gives
you Kav's own components and colors. Anything else (location, notifications, the camera, files, the network) you do with
Android itself, with Kav's permissions. Kav keeps this API stable: nothing in it is renamed in Kav's releases, and it
only grows. The kit's <a href="/docs/extensions/native">native guide</a> has every method, with an example.

Settings are declared in your manifest, and Kav draws them on your extension's page: text, numbers with a stepper,
switches, choices, and Kav's own pickers for a place, a stop, a time, a date and a color. A theme is colors in the
manifest. The extension still needs Android code, even if its `start` does nothing.

Your code runs in Kav's own process, so its crash is Kav's crash: if it makes Kav close twice within five minutes, Kav
turns your extension off the next time it starts, and tells the rider which one. Riders can always start Kav with every
extension off (safe mode). What you write with `kav.log`, and your code's failures, show in the log on your extension's
page in Settings › Extensions. An extension without Android code shows as made for an older version of Kav: Kav lists it
and never runs it.

## For your agent {#for-your-agent}

Building with an AI coding agent? Give it this line. The file it names holds everything it needs: the API, the formats and the rules.

<AgentPrompt />

## The developer kit

The developer kit builds Android extensions in Kotlin or Java. Its `kavx` command line compiles an extension against
Kav's own API, turns it into a dex and checks it the way Kav does (`kavx check`), builds every extension in your
repository into one bundle file (`kavx bundle`), reads a bundle the way Kav does (`kavx verify`), and makes your
source's Add to Kav link (`kavx link`). It has a Kotlin starter, five Android samples (a speed card with the GPS and a
notification, map pins, a planner with route rules and a stop board, a Home and a tab, and a crash test), and a template
for your source with a workflow that builds the bundle and attaches it to a GitHub Release. Extensions can only be tried
on a phone or an emulator with Kav 5 or newer. The kit needs Node.js 22.2 or newer, a JDK, the Android SDK and, for
Kotlin, the Kotlin compiler. It makes no network calls.

Get it from <a href="https://github.com/ImNoammm/kav-extension-kit">github.com/ImNoammm/kav-extension-kit</a>:

```sh
git clone https://github.com/ImNoammm/kav-extension-kit
cd kav-extension-kit
npm link        # puts kavx on your PATH
```

- <a href="/docs/extensions/native">Kotlin and Java</a>: building, every API method, the crash guard, logs, trying it
  on a phone
- <a href="/docs/extensions/starter">The Kotlin starter</a>
- <a href="/docs/extensions/hooks">Hooks</a>: every hook, what it's given and what it may answer
- <a href="/docs/extensions/screens">Screens</a>: cards on Kav's screens, your own pages, settings, a Home, themes
- <a href="/docs/extensions/format">The bundle format</a>
- <a href="/docs/extensions/what-riders-see">What riders see</a> about your extension
- <a href="/docs/extensions/riders-wording">The words a rider meets</a> in Kav, in English and Hebrew
- <a href="/docs/extensions/testing">Testing</a>, and checking a bundle
- <a href="/docs/extensions/template">The source template</a>

## Sharing your source

Your source is your GitHub repository. Each of its GitHub Releases carries one bundle file (`.kavb`, made with
`kavx bundle`) with all your extensions, and Kav reads the latest release. Anyone can add your source in Kav from
Settings › Extensions › Sources, by typing `owner/repo` or `github.com/owner/repo`. To make it one tap, share a link:

```
https://kavapp.link/add-source?github=owner/repo
```

The same query after `kav://add-source?` opens Kav directly. The kavapp.link link opens [a page on this
site](/add-source?github=owner/repo), with an Open in Kav button on a phone and a QR code on a computer. The link works
whether or not your source is on the community list, and Kav checks the source itself whichever way it arrives.

Kav checks your repository for a new release in its background check and when a rider taps Check now. A newer bundle
replaces the old one: each extension keeps its switch and settings, and one your new bundle no longer has is removed
with its settings. A rider can also add a bundle file from the phone (Add a bundle file); a source added that way never
updates.

### Add to Kav badge {#badge}

For your README. The badge says only that your source can be added to Kav; it doesn't mean Kav made, reviewed or
endorses it, so keep it next to your own name, not in place of it.

<BadgeMaker />

The badge is served from kavapp.link; you may also copy `add-to-kav.svg` into your own repository.

## The community list

[Community sources](/community) lists every source that passes Kav's automatic checks, by a script with no person at
Kav in the loop. To be on it, give your public GitHub repository the `kav-extension` topic, or submit it on that page.

## Developer terms {#developer-terms}

By publishing an extension for Kav you agree to these.

- You are the sole author and publisher of your extension and fully responsible for it, including any claim made
  about it.
- You must have the right to use every service, API and piece of data your extension touches, and must follow their
  terms. Kav gives you no rights to any third-party service.
- You may not present your extension as made, endorsed or approved by Kav, or use Kav's name or logo as your own
  branding. "for Kav" in a description is fine.
- You agree to hold Kav and its maintainer harmless for claims arising from your extension.
- Kav can block an extension on its security list, remove a directory listing, and change or drop extension points at
  any time.

What Kav itself takes on, and doesn't, is on [Third-party extensions](/third-party-extensions).

## The security block list

Kav has a switch, on by default, for a signed list of GitHub repositories and bundle files found stealing, spreading
malware, impersonating someone, or abusing a bug in Kav. It is for those four reasons only, never a legal complaint or a
dispute; Kav ignores any entry with another reason. Anything on it can't be added; anything already added shows as
Blocked and stops being used, and Kav says so when it next opens. Kav reads the list from GitHub in its background check
and at Check now, while the rider has at least one source or extension added. It is a file signed with Kav's key; a list
that fails to download or to verify leaves the last one in place, and an older list never replaces a newer one. Riders
can turn it off, and then it isn't applied. The list is published in Kav's own repository on GitHub, and is empty today.

## Reporting a malicious extension

Open an issue on [Kav's GitHub](https://github.com/ImNoammm/kav/issues/new) with the source's address, the extension's
name and what it does, and report it to GitHub too (GitHub has a Report abuse link on the repository). If it is
dangerous for one of the four reasons above, it goes on the block list.

A problem with how an extension works, or a request about a service it uses, goes to its publisher: every source in Kav
has **Report an issue**, which opens the publisher's own tracker. Kav closes issues about an extension's behavior with a
pointer there.