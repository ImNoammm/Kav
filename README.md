<p align="center">
  <img src=".github/icon.png" width="112" alt="Kav">
</p>

<h1 align="center">Kav</h1>

<p align="center">
  Public transport in Israel, without the ads, the account or the tracking.
</p>

<p align="center">
  <a href="https://github.com/ImNoammm/kav/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/ImNoammm/kav?label=release&color=9ABEFF"></a>
  <img alt="Android 8.0 or newer" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <a href="LICENSE"><img alt="GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-lightgrey"></a>
</p>

<p align="center">
  <a href="https://www.buymeacoffee.com/Noamm"><img alt="Buy Me A Coffee" src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png"></a>
</p>

https://github.com/user-attachments/assets/e24440ee-d630-4a5f-b054-bbb5be0134e7

An Android app for getting around on public transport in Israel. No ads, no
account, no analytics, nothing phoning home about where you go. Hebrew and
English, and it lays itself out right to left when you pick Hebrew.

I built it because the app everyone uses for Israeli transit has become
unusable: full-screen ads, a subscription nag, and a permissions list that has
nothing to do with catching a bus. Kav is the same job done
plainly: it plans a trip, tells you which bus, and walks you through it while
you're on the way.

## Documentation

Installing, using Kav and running your own server are all covered on
[kavapp.link](https://kavapp.link/docs/).

## What it does

- Plans a trip, gives you a few ways there, and walks you through the one you
  pick. The step moves on by itself as you walk and ride, the map turns with
  you, and if you leave the app the current step follows you in a small
  floating window.
- Up to three stops on the way, each one passed by or stayed at for as long
  as you say.
- Shows where your bus actually is. The trip stays in the notification shade
  while you're on the way, and on Android 16 it shows up as a live update.
- Live bus locations on the map, free. With Kav selected, they come from its server
  ([server/](server)), which takes them straight from the Ministry of
  Transport's real-time feed, and it plans your trips too, on the ministry's
  timetable with OpenTripPlanner. It only learns the lines, stops or rough area
  you're looking at and the start and end of a trip you plan, through
  Cloudflare, and keeps no logs.
- A live screen with every stop around you and the buses reporting their
  position.
- Departure boards for every station, and a page for every line with its route
  on a map, its stops and its buses on the road.
- Separate choices for trip planning, live locations and timetables. Pick
  Kav, Bus Nearby, EGG or your own server for each. External providers receive
  the requests you make to them and your IP address. The timetable kept on the
  phone remains available without signal.
- OLED black, light and dark, with liquid glass or solid bars.
- Kav's own map, kept on the phone, with its places and streets named in Hebrew
  or English, and road numbers drawn as Israel's route markers. Or satellite
  photos, or one of OpenFreeMap's four maps, loaded online or downloaded to the
  phone too. Standard can show 3D buildings when tilted, if you turn them on. GovMap also offers streets
  and aerial photos from the Survey of Israel.
- Detours, delays and moved stops on your rides, from Kav's server, Google or
  your own server, in Hebrew or English, off until you turn them on. Kav's server
  sends the whole country's list, so it never learns your lines.
- How busy your next bus usually is, as Google Maps predicts it, on unless you turn it off in setup or Settings.
- Your own HTTP or SOCKS5 proxy for everything Kav asks for, so the services it
  talks to see the proxy instead of you.
- Favourite places on the home screen, and backups of your places and settings
  to a `.kav` file.
- Private search, on by default, keeps your exact location out of searches:
  Google or OpenStreetMap's Photon search sees only the centre of the town
  you're in, or a place you pick. Choose the search provider in setup or Settings.
- Pays for bus, train, light rail and Carmelit rides, if you want it to, with a
  Rav-Pass account you sign up or sign in to right in Kav. Only payments use
  it, and Rav-Pass gets your location only at the moment you scan or pay, as in
  its own app, never in the background. Everything else stays anonymous.
- Reports a driver, a bus, an inspector or a train to the Ministry of Transport
  on its own form. Kav fills it in from your ride, and you check it and send it
  yourself. Your details stay on the phone and only go into that form.

## Getting it

Grab the APK from [kavapp.link](https://kavapp.link/download) or
[Releases](https://github.com/ImNoammm/kav/releases) and open it. It updates
itself from GitHub's releases. No store, no update service.

GitHub builds every APK there from the code in this repo, and you can check the
one you downloaded with the [GitHub CLI](https://cli.github.com):

```sh
gh attestation verify kav-4.0.apk --repo ImNoammm/kav
```

Setup downloads the map (about 176 MB once) and timetable (about 6 MB) from
GitHub. Both stay on the phone for offline use. GitHub builds the timetable
nightly, and Kav checks for it in the background. Daily updates can be turned
off in Settings. Until the map is downloaded, the preview of it in setup and in
Settings is read from GitHub for one spot in Tel Aviv. Every download is checked
against the SHA-256 the app was built with before it's used.

The satellite map loads its photos from Esri as you look, so Esri sees your IP
address and the area on screen. The OpenFreeMap maps load from OpenFreeMap the
same way, so OpenFreeMap sees them too, until you download them in Settings
(about 102 MB once, from the `maps-1` release here; GitHub sees only your IP
address). Settings shows each map kept on the phone, with its size, and deletes it.
GovMap's streets and aerial photos load from the Survey of Israel as you look;
they receive your IP address and the map area and aren't downloaded for offline use.

## Building it

You need a JDK and the Android SDK. There's a script that fetches both into
`~/Android` without touching your system packages:

```sh
tools/android_toolchain.sh
tools/android_build.sh :app:assembleRelease
```

The APK contains no timetable. The nightly [workflow](.github/workflows/timetable.yml)
compiles the Ministry of Transport's feed and Tel Aviv's weekend lines, signs
the manifest with `KAV_TIMETABLE_KEY`, and publishes the download to the
`timetable` release. To compile a local copy:

```sh
tools/fetch.sh
KAV_FROM=today KAV_REGION=il KAV_BBOX=national KAV_MANIFEST=1 python3 tools/export_web_bundle.py
```

To use Kav as the live or planning provider in a build of your own, you need
your own server (below): Kav's server only answers the APKs from Releases.

## Running your own server

[Read the guide](server/selfhost/README.md).

| What runs | Minimum CPU cores | Minimum RAM at night | RAM while running | Minimum disk |
|---|---|---|---|---|
| Live buses and the app's timetable | 2 | 2 GB | 1 GB | 4 GB |
| Kav's planner, one area | 2 | 7 GB | 3 GB | 7 GB |
| Kav's planner, one area, with Kav's arrival times | 2 | 9.5 GB | 5.5 GB | 8 GB |
| Kav's planner, all of Israel | 2 | 22 GB | 6 GB | 7 GB |
| Kav's planner, all of Israel, with Kav's arrival times | 2 | 24.5 GB | 8.5 GB | 8 GB |
| The above, with the second planner on the ministry's times | 2 | 29.5 GB | 13.5 GB | 9 GB |

## Credits

- Map data © OpenStreetMap contributors, from the Protomaps build, drawn with
  MapLibre. Satellite photos from Esri.
- The OpenFreeMap maps: tiles, fonts and sprite from
  [OpenFreeMap](https://openfreemap.org) (© OpenMapTiles, © OpenStreetMap
  contributors). Their styles come from OSM Bright (© MapTiler, OpenMapTiles
  contributors and Mapbox), OSM Liberty (Maputnik), Dark Matter and Fiord Color
  (© MapTiler, OpenMapTiles contributors and CARTO): code under the BSD 3-Clause
  License, design under CC BY. Noto fonts under the SIL Open Font License.
  [@szaionz](https://github.com/szaionz) brought them to Kav in
  [#71](https://github.com/ImNoammm/kav/pull/71), with the map names language.
  `tools/map_packs.py` builds the downloadable pack.
- Road numbers are drawn as the Ministry of Transport's route marker (sign 611):
  blue for freeways, red for main roads, green for regional and black for local
  roads, by each stretch's class in OpenStreetMap.
- The liquid glass shaders come from Kyant0's
  [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Apache
  License 2.0).
- Timetables and live buses from the Israel Ministry of Transport. Trips are
  planned on that timetable and OpenStreetMap by
  [OpenTripPlanner](https://github.com/opentripplanner/OpenTripPlanner).
- Places and exact addresses from Google Maps' public web search, asked the way
  a logged-out browser asks it: no key, no account, no cookies. It gets the typed
  text and the area above. The request recipe is
  [Vela Maps](https://github.com/PimpinPumpkin/Vela)' (GPL-3.0). Once a day Kav
  reads Vela's signed recipe from GitHub, so a fix for Google reaches it without
  an update. A file without the right signature, or pointing anywhere but
  Google, is ignored.
- Addresses Google doesn't have from GovMap, the Survey of Israel's map, sent
  just the typed text.
- Google alerts and How busy ask Google Maps as one logged-out browser does, with
  the cookies Google sets and Chrome's headers, the way Vela Maps' GoogleSession
  and BrowserHeaders do (GPL-3.0).
- English line alerts are translated on Kav's server by Helsinki-NLP's
  [opus-mt-tc-big-he-en](https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-he-en)
  (OPUS-MT, CC-BY 4.0), run with [CTranslate2](https://github.com/OpenNMT/CTranslate2)
  (MIT), with the Ministry of Transport's English stop names.
- Street View from Google: each stop's photo is its panorama facing the stop,
  fetched when the stop is shown unless photos are off in Settings, and the
  viewer opens when you tap it. Google then sees that stop and your IP
  address, and its viewer keeps no cookies.
- Photos of places from Google Maps: the one it shows first for the place,
  fetched when the place is shown and kept on the phone. Google then sees the
  place's name and where it is, and your IP address.
- Emissions are Kav's estimate from each ride's distance and mode, with the UK
  government's 2026 conversion factors per passenger.
- Train times between two stations from Israel Railways' own timetable, asked
  only when you open that card, and the platforms of a train another planner
  planned, asked when you open that trip. It learns the two stations and the day.
- Sharing a trip Bus Nearby or EGG planned also makes their own short links to
  it, the way their apps' Share does: HopOn, which runs both, gets that trip and
  a random ID Kav keeps for this.
- Kav trip links save the selected itinerary, page choices and the picture
  chats show with the link on Kav's server (or your own) behind a random
  seven-character code. Links expire after 7 days by default;
  Link customization also offers 3 or 30 days. Expired trips are deleted
  automatically. The preview loads Leaflet from unpkg and map tiles from Esri.
  Both receive the viewer's IP, and Esri sees the map area.
- The operator colours come from [@szaionz](https://github.com/szaionz)'s
  route_colors branch.
- The self-hosted server's setup page uses [htmx](https://htmx.org) (0BSD) and
  Kazuhiko Arase's [QR Code Generator](https://github.com/kazuhikoarase/qrcode-generator)
  (MIT).
- Code from Kav's contributors: [@szaionz](https://github.com/szaionz) wrote the
  OpenTripPlanner routing backend, the line badge styles, the arrow on the stop
  rail and the switch that turns off update prompts;
  [@Kido130](https://github.com/Kido130) kept last night's buses on the departure
  board after midnight; [@donno2048](https://github.com/donno2048) made the
  release workflow build the APK on GitHub from the tagged code; and
  [@GbaGuy](https://github.com/GbaGuy) added clear buttons to the search fields
  and a check before closing quick pay.
- Thanks to the amazing team at [HTTP Toolkit](https://httptoolkit.com), who
  kindly gave Kav a free Pro licence.
- A huge thanks to [@yair-654](https://github.com/yair-654) for all the support
  for Kav, and for the bug reports and ideas behind a lot of it.

## License

GPL-3.0. See [LICENSE](LICENSE).
