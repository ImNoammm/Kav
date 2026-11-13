---
title: Docs
description: How Kav works, how to build it, and how to run your own server.
---

# Docs

Kav is an Android app for public transport in Israel, and a server that gives it live buses, trip plans and arrival
times. Both are open source under the GPL-3.0, in [one repository](https://github.com/ImNoammm/kav). The server's pages
are its own Markdown from beside the code, copied here on every build, so they always match it.

## The app

- [How Kav works](/docs/how-it-works): the app, Kav's server, and where every piece of data comes from.
- [Building from source](/docs/building): the APK and the timetable, and how releases are made.
- [Download](/download) the latest version, or see every release so far.
- [Privacy policy](/privacy-policy): what the app sends, and to whom.
- [License](/docs/license): GPL-3.0, and the work Kav builds on.
- Found a bug or have an idea? [Open an issue](https://github.com/ImNoammm/kav/issues).

## Your own server

Everything Kav's server does can run on a machine of yours, and the app can use it in place of Kav's.

- [Self-hosting](/docs/self-hosting): requirements, installing, every setting, updates, backups and troubleshooting.
- [Server API](/docs/api): everything the app asks a server, for anyone writing their own.
- [Live sources](/docs/live-sources): where live buses come from, and plugging in a source of your own.
- [A planner of your own](/docs/planner): swap Kav's OpenTripPlanner for yours.
- [A predictor of your own](/docs/predictor): Kav's own arrival times, and running a model of yours.
- [Training a model](/docs/training): building a model pack for the predictor.
