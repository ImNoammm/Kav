---
title: Third-party extensions
description: Extensions for Kav are other people's code. What that means for you, and what Kav does and doesn't do.
editLink: false
---

# Third-party extensions

Kav can run extensions: packages that other people make and publish, and that you choose to add. An extension is
someone else's code. This page says what that means.

## Not part of Kav

Extensions are not part of Kav, and are the responsibility of their publishers. Kav does not make, review, test, approve
or endorse them, and is not affiliated with their publishers or with any company or service an extension connects to.
Each publisher alone is responsible for what their extension does, for its content, and for having the right to use any
service it connects to. You add a source and switch on an extension at your own choice and risk. Only add what you
trust. Kav's own source, github.com/ImNoammm/kav-extensions, is the one exception: Kav makes those extensions, and marks
them Made by Kav.

## What an extension can do

When you add a source, Kav shows its picture, name and address, marked Third-party unless it's Kav's own, and adds
nothing until you tap Add. Each extension then shows in Settings › Extensions with its own switch, off until you switch
it on. Once you do, it runs at once, whenever it likes, and Kav doesn't ask again. Its page says, under Details, what
its publisher declares it does: for example that it sees your trip searches, or that it adds to Kav's screens. When the
source publishes a new version, Kav replaces its extensions by itself, and each one keeps its switch and settings. While
a trip is under way, Kav waits to replace them.

Every extension runs its own Android code, with full access: it can do anything Kav can, whatever its page says, and Kav
tells you so the first time you open Settings › Extensions. It runs inside Kav, and can use your location, the camera,
notifications and the internet, and read everything Kav keeps on your phone, tickets from Rav-Pass included. Kav can't
limit or see what it does.

What an extension sends, and to whom, is between you and its publisher, not Kav. Kav's [privacy policy](/privacy-policy)
doesn't cover extensions; it does say what Kav itself sends for them, and what they can reach
([Extensions](/privacy-policy#extensions)).

## What Kav does

- The first time you open Settings › Extensions or add a source, Kav adds its own source, Kav extensions, with every
  extension in it off. You can remove it. Kav suggests no other source or extension.
- Before you add a source, Kav shows its address and whether it's Kav's own, and adds nothing until you confirm. Every
  extension comes in off, until you switch it on.
- If an extension's Android code makes Kav close twice within five minutes, Kav turns that extension off the next time
  it starts, and says which one.
- Safe mode, in Settings › Extensions, starts Kav with every extension off. Kav turns it on by itself if it closes with
  an error three times within ten minutes.
- Kav keeps a [security block list](/docs/extensions#the-security-block-list) of dangerous GitHub sources and bundle
  files, which you can turn off. It's empty today.
- The [community list](/community) is made by a script, without anyone at Kav looking at what's on it, and Kav may remove
  any listing at any time.

## A problem with an extension

Every source in Kav has **Report an issue**, which goes to its publisher: they made it, and only they can fix it. Kav
closes issues about an extension's behavior with a pointer there. If an extension is malicious, report it to Kav as well
([how](/docs/extensions#reporting-a-malicious-extension)).

Making an extension? The [developer terms](/docs/extensions#developer-terms) apply to everyone who publishes one.
