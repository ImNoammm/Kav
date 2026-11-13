---
title: Privacy policy
description: What Kav sends, to whom, and what it keeps.
editLink: false
---

# Privacy policy

Kav has no ads, no account, no analytics and no crash reporting. Nothing in it reports on how you use it. This page
lists every place the app talks to, and what each one gets. It applies to the Kav app from
[GitHub](https://github.com/ImNoammm/kav/releases), Kav's server and this website.

## Kav's server

When you pick Kav for live buses, planning or timetables, the app asks Kav's server for:

- live buses on the lines, stops or rough area you're looking at;
- trips between the start and end you plan, and the time;
- the server's status.

Requests go through Cloudflare, so the server sees Cloudflare, not you: your IP address and your browser's name are
taken out before a request reaches it, and it keeps no log of requests. It doesn't know who asked, and has no account to
tie requests together. Cloudflare, like any network provider, handles the connection itself.

If you set up [a server of your own](/docs/self-hosting), each choice you point at it (live buses, planning, the
timetable, share links) asks that one instead. Settings says what, if anything, still goes to Kav's server, and a Kav link
made there is always read from there.

## Shared trips

Kav makes a trip's link only when you tap Share on it. A Kav link stores the trip you picked and the look you chose for
its page (and the picture chats show with the link) on Kav's server, behind a random seven-character code. Nothing about
you is stored with it. Links last 7 days unless you choose 3 or 30, and expired trips are deleted.

Someone opening the link sees a page that loads the map library Leaflet from unpkg and map pictures from Esri; both
receive their IP address, and Esri sees the map area.

## Services you choose

Some features ask a service directly. They receive what's needed for the request, and your IP address, like any website
you visit:

- **Trip planning, live buses and timetables from another provider**, when you pick one in Settings: Bus Nearby or
  EGG (both run by HopOn). Only the providers you picked are
  asked. Sharing a trip one of them planned also asks it for its own link to that trip.
- **Line alerts**, only once you turn them on in Settings, from the source you pick: Kav's server (or your own)
  sends Israel's whole list, and your lines are found in it on your phone, so it learns nothing of your trip. Google Maps is asked about each ride of a trip you open, at most every five minutes: it
  gets the stop you board at and the stop you leave at, without a key or an account. For these requests only, Kav keeps
  the cookies Google sets (as a browser would), on your phone.
- **How busy**, unless you turn it off in setup or Settings: Google Maps is asked about the stop you board at while a trip,
  navigation or a line's page is open, at most every ten minutes, with the same cookies. The first time for a stop, it's
  also asked for directions from that stop to one further along the line, which is how Kav finds Google's own id for it.
- **Searching for a place**: Google or OpenStreetMap's Photon search, whichever you choose in setup or Settings,
  without a key, an account or cookies. It gets what you typed and the area around you: with private search
  (on by default) only the centre of your town, or a place you pick. House numbers neither finds are looked up on
  GovMap, the Survey of Israel's map, with just the typed text.
- **Photos of stops and places, and Street View**: Google, for the stop or place being shown (a place's name and where it
  is), unless photos are off in Settings. Each photo is kept on your phone once it's fetched.
- **Train times and platforms**: Israel Railways, with the two stations and the day, when you open that card or trip.
- **Satellite map**: Esri, for the area on screen. Its names come from Kav's own map on your phone.
- **GovMap streets and aerial maps**: the Survey of Israel, for the area on screen. These maps load online,
  and are not saved as offline maps.
- **OpenFreeMap maps** (Liberty, Bright, Dark and Fiord): OpenFreeMap, for the area on screen, until you download them in
  Settings; after that they're drawn from your phone and nothing is asked. OpenFreeMap's own policy says its access logs
  keep no IP addresses.
- **Paying for a ride**, if you want to: Rav-Pass, the Ministry of Transport's payment service run by HopOn, with your
  own Rav-Pass account. It gets your location only at the moment you scan or pay, never in the background. Nothing else
  in Kav uses that account.
- **Reporting a driver, bus, inspector or train**: the Ministry of Transport's own form, which you check and send
  yourself. Your details stay on the phone until then.

With a proxy of your own set in Settings (HTTP or SOCKS5), all of these, and Kav's server, see the proxy's address
instead of yours, and the proxy sees which services Kav connects to. Its address and login stay on your phone.

## GitHub

Kav downloads its offline map, the OpenFreeMap maps if you ask for them, the timetable and its own updates from GitHub,
and checks GitHub for a new version each time it opens, unless both "Tell me about new versions" and "Download updates
automatically" are off in Settings; daily timetable updates can be turned off there too. Once a day it also reads the
signed search recipe Vela Maps publishes on GitHub. GitHub sees your IP address for these downloads, not where you look.
Until the offline map is downloaded, the preview of it in setup and in Settings is read from GitHub for one spot in Tel
Aviv.

## On your phone

Your places, favourites, recent trips and settings stay on your phone. Backups are a `.kav` file you save where you
choose. Location is used on the phone to plan from where you are and to follow your trip; it isn't sent anywhere except
as described above.

## This website

kavapp.link uses no cookies, no analytics and no fonts or scripts from anyone else, and keeps no access logs. The
Download button and the version history ask GitHub's API for the latest release from your browser, so GitHub sees that
request. The APK itself downloads from GitHub.

## Questions

Kav is open source, so all of this can be checked in [the code](https://github.com/ImNoammm/kav). Questions and
corrections are welcome as [an issue on GitHub](https://github.com/ImNoammm/kav/issues).
