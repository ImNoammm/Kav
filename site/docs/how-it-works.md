---
title: How Kav works
description: The app, Kav's server, and where every piece of data comes from.
---

# How Kav works

Kav is an Android app and an optional server. The app does most of its work on the phone, and asks outside services only
for what it can't know itself: where the buses are now, and trips planned against live data.

## On the phone

- **The map** is a vector map of Israel from OpenStreetMap (the Protomaps build, about 176 MB), downloaded once from
  GitHub and drawn with MapLibre. It works without signal. The satellite view loads from Esri when you use it.
- **The timetable** for the coming week, compiled every night from the Ministry of Transport's GTFS feed by GitHub
  Actions and downloaded in the background. Departure boards, line pages and timetables read it, with or without
  signal.
- **Your places**, favourites, recent trips and settings never leave the phone, except in a `.kav` backup you save
  yourself.

## Sources you pick

Planning, live buses and timetables are three separate choices in Settings. Each can be Kav's server, Bus Nearby, EGG or
your own server. Only the providers you picked are ever asked. The [privacy policy](/privacy-policy) lists what each one gets.

Place search is a separate choice: Google or OpenStreetMap through Photon. Both respect private search.
GovMap provides house-number fallback and two online map choices. The standard offline map can show 3D buildings
when tilted.

## Line alerts

Detours, delays and moved stops on your rides are a choice of their own (Settings › Live times and planners › Detours
and alerts), off until you turn them on:

- **Google**: Google Maps, asked from your phone about each ride of a trip you open, at most every five minutes. Google
  sees the stops you board and leave each ride at, and your IP address. Kav shows only alerts that name your line.
- **Kav**: Kav's server sends Israel's whole list from the Ministry of Transport's own feed, with the notices in English
  too, translated on the server. Your lines are found in it on your phone, so the server never learns your trip.
- **My own**: your own server, from the source you set it up with ([Self-hosting](/docs/self-hosting#line-alerts)).

## How busy

How busy the next bus or train usually is at that hour, as Google Maps predicts it, on trips, in navigation and on a
line's page. It's on unless you turn it off, in setup or in Settings › Live times and planners › How busy. Kav asks Google from your
phone about the stop you board at, at most every ten minutes, so Google sees that stop and your IP address. It's Google's
prediction for the line at that hour, not a count of who's on board.

## Your own proxy

Settings › Privacy and sharing › Proxy sends everything Kav asks for through an HTTP or SOCKS5 proxy of yours: Kav's
server, Google, the planners, the maps, and the pages Kav shows (they can log in to an HTTP proxy, not to a SOCKS5 one).
Addresses on your own network are never sent to it. Save and try checks it carries a request before you rely on it.

## Kav's server

Kav's own server is a few Docker containers behind Cloudflare:

- **Live buses** (`server/live.py`, plain Python). It reads the Ministry of Transport's real-time feed (SIRI) for the
  whole country every 15 seconds while the app is in use (every minute otherwise), keeps it in memory, and answers the app by line, stop or area.
  It also records when each bus passed each stop, kept for ten weeks, for the predictor.
- **Trip planner**: OpenTripPlanner, rebuilt every night from the ministry's timetable and OpenStreetMap, on Kav's own
  predicted times, with a second one on the ministry's official times beside it.
- **Predictor**: Kav's own arrival times, every 30 seconds, from a model trained on that history. They replace the
  ministry's estimates in the app and the planner. See [A predictor of your own](/docs/predictor).
- **Shared trips**: links like `share.kavapp.link/Ab3dE7f`, kept until they expire.

The app signs every request to the server, so it answers official Kav builds only. Requests reach it without your IP
address, and it keeps no log of them.

## Addresses

| Address | What's there |
| --- | --- |
| `kavapp.link` | This website |
| `api.kavapp.link` | The server's API, for the app |
| `share.kavapp.link` | Shared trip links, and the page someone without Kav sees |
| `kav.noammm.uk` | The server's first address, kept working for every copy of Kav already installed |

## Running your own

Every part of the server runs on a machine of yours, from one install command, and the app can use it in place of
Kav's. Start with [Self-hosting](/docs/self-hosting), then [Server API](/docs/api) if you want to write a part
yourself.
