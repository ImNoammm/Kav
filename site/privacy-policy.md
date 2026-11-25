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
- the English of a line alert your phone got in Hebrew;
- the server's status.

Requests go through Cloudflare, so the server sees Cloudflare, not you: your IP address and your browser's name are
taken out before a request reaches it, and it keeps no log of requests. It doesn't know who asked, and has no account to
tie requests together. Cloudflare, like any network provider, handles the connection itself.

The server keeps an alert's English once it has translated it, so the next phone gets it without waiting. The alert's
text is kept, not who asked for it.

When you open a bus's card, or follow it on a trip, the app asks the same server what kind of bus it is (what it runs on,
its year, size and seats), by the bus's number plate, which came with the live buses. Nothing about you goes with it.

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

- **Line alerts**, only once you turn them on in Settings, from the source you pick: Kav's server (or your own)
  sends Israel's whole list, and your lines are found in it on your phone, so it learns nothing of your trip. Google Maps is asked about each ride of a trip you open, at most every five minutes: it
  gets the stop you board at and the stop you leave at, without a key or an account. For these requests only, Kav keeps
  the cookies Google sets (as a browser would), on your phone.
- **How busy**, from the source you pick in setup or Settings (new installs start on Kav's; an install from before Kav 5
  keeps the choice it had). Kav's: whichever source your live buses come from, Kav's server (or your own, when your live
  buses come from it) is asked how busy the lines you look at usually are, so it learns which lines; the levels come from
  the Ministry of Transport's count of Rav-Kav taps, and nothing goes to Google. Google's: Google Maps is asked about the
  stop you board at while a trip, navigation or a line's page is open, at most every ten minutes, with the same cookies.
  The first time for a stop, it's also asked for directions from that stop to one further along the line, which is how
  Kav finds Google's own id for it.
- **Searching for a place**: Google or OpenStreetMap's Photon search, whichever you choose in setup or Settings,
  without a key, an account or cookies. It gets what you typed and the area around you: with private search
  (on by default) only the center of your town, or a place you pick. House numbers neither finds are looked up on
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
- **Reporting a driver, bus, inspector or train**: the Ministry of Transport's own form, which you check and send
  yourself. Your details stay on the phone until then.

With a proxy of your own set in Settings (HTTP or SOCKS5), all of these, and Kav's server, see the proxy's address
instead of yours, and the proxy sees which services Kav connects to. Its address and login stay on your phone.

## GitHub

Kav downloads its offline map, the OpenFreeMap maps if you ask for them, the timetable and its own updates from GitHub,
and checks GitHub for a new version each time it opens, unless both "New version alerts" and "Automatic updates" are off in Settings; daily timetable updates can be turned off there too. Once a day it also reads the
signed search recipe Vela Maps publishes on GitHub. GitHub sees your IP address for these downloads, not where you look.
Until the offline map is downloaded, the preview of it in setup and in Settings is read from GitHub for one spot in Tel
Aviv.

Report a bug and Ask for a feature in Settings open GitHub's forms blank. For a bug, Kav first shows what it can add
(its version, Android's, your phone's model and Kav's setup) and adds them only if you choose to.

## Rav-Pass

You can't pay inside Kav. Paying with Rav-Pass is one setting, Pay with Rav-Pass on the Paying page in Settings, off
until you turn it on. Turning it on asks Android for two permissions in a row: showing over other apps, used only for a
small button back to Kav over Rav-Pass while you pay, and Kav's ticket reader, an accessibility service that reads only
Rav-Pass's ticket screens and the few lines named below. The button needs Android 12L or newer and the reader Android 11
or newer; an older phone pays without them. It counts as on only once both are granted, and then everything that comes
with it is on, with no separate switches: the Pay buttons, the button back to Kav, the ticket read into Kav, Kav coming
back by itself after a bus ticket bought from its Pay button, and a quiet notification while a ticket is live. Turning
it off removes all of that, stops the reader, and deletes the tickets and the ride history Kav kept.

The Pay buttons, on the home screen and on each ride of a trip, open the Rav-Pass app on your phone so you can pay
there. Kav tells it only which of its screens to open (bus, train or light rail), nothing about you or your trip, and
asks no server anything for it. Settings opens places in your Rav-Pass account (your profile, payment cards, discount
profile and others) the same way, and tells Rav-Pass nothing else. What you do in Rav-Pass is between you and HopOn, the
company that makes it.

To set paying up, Kav checks on your phone whether Rav-Pass is installed. If it isn't, Kav's setup popup offers one way
to get it, by what the phone has: Rav-Pass's page in the Play Store, else in Aurora Store, else on APKMirror in your
browser. That store or website sees your IP address, as any would.

Kav draws the button back to Kav over Rav-Pass after you tap Pay, and when you open a Rav-Pass place from Kav's
Settings. For this button Kav reads nothing of Rav-Pass's screen but where its scanner, codes and top bar buttons are,
through the ticket reader. The button takes no touch but its own, and you can drag it anywhere except over those.

### Tickets from Rav-Pass

Kav's ticket reader is an Android accessibility service, and Android lets it watch Rav-Pass alone. It reads Rav-Pass's
ticket screens (the one that says your ride is paid, and the ticket for inspection when you open it) whenever one is
open: the line, the stations, the time, the ticket number, the passengers' names and the ticket's code. It reads the code
from a picture of the screen (from Android 14, of Rav-Pass's window only), taken each time a ticket screen changes until
every code is read, and let go at once.

Besides the ticket screens, it reads only these few lines, and nothing else on those screens:

- the bar on Rav-Pass's scan and train screens that says you have no active ticket, so Kav ends the tickets it kept for
  those rides;
- the fare's distance and the price Rav-Pass quotes on the screen where you approve a bus ride, kept with the ticket you
  then buy and in the ride history;
- your discount profile's name on Rav-Pass's Profile tab, only within two minutes of tapping Get it from Rav-Pass under
  Fare profile. Kav sets your fare profile from it and keeps it on this phone only.

On every other Rav-Pass screen, like signing in or your card, it reads no text and takes no picture: it only looks at
how the screen is built, to tell which screen it is and where its scanner, codes and top bar buttons are, so the button
back to Kav never sits on them. In every other app it reads nothing. It can tell which app is in front, so that it never
acts under another app or the notification shade.

It taps one thing, Rav-Pass's ticket drawer, which it opens once right after a purchase so the code can be read, and
once after you tap Open in Rav-Pass on a ticket in Kav. On a ticket for several passengers it also moves Rav-Pass's list
of tickets on to each passenger's card to read its code, then back to the first. After Kav opens one of Rav-Pass's
screens that sit over its Profile tab (Discount profile, Passengers, Coupons, Support, Payment cards), the reader
notices that tab coming up after a Back and brings you to Kav at once. It never buys, confirms or types.

When you turn the reader on, Android warns that it would get full control of your phone: that it could read everything
on the screen, show things over other apps, and act in apps for you. Android shows this warning for every accessibility
service, whatever it does. Kav's reader asks for less: Android tells it only about Rav-Pass, it can make no gestures or
key presses, and it draws nothing on the screen (the button back to Kav uses the other permission). Reading no more than
this page says is up to Kav's own code, which anyone can check.

What it reads stays on your phone, and isn't sent anywhere by Kav or put in backups. A ticket is kept until it ends (6
hours after a bus ticket's time, 3 after a light rail ticket's, 4 after a train entrance, or when its exit is read, and
an hour after a train exit is read), until Rav-Pass shows you have no active ticket of that kind, or until you turn off
Pay with Rav-Pass. After a ticket ends, its ride stays on your phone, in the ride history on the Paying page and in
Home's monthly summary: the line, the stations, the time, how many passengers, the fare's distance and the price
Rav-Pass quoted, never the ticket's code, its number or the passengers' names. The ride history is deleted only when you
tap Clear history there or turn off Pay with Rav-Pass. Turning the reader off in Android's accessibility settings stops
the reading; what was already read is deleted when the ticket ends, and its ride stays in the history. An extension you
switch on can reach your tickets, your ride history and the reader too ([Extensions](#extensions)).

After a bus ticket bought from Kav's Pay button, Kav comes back to the front by itself once the ticket's code is read, or
a few seconds after Rav-Pass says it's done, and opens on the ticket. While the reader is on, Android keeps Kav running
in the background.

## Extensions

An extension is someone else's code. Other people make and publish extensions; Kav doesn't make, review or approve
them, and this policy doesn't cover them. What an extension does with what it can reach, and what it sends anywhere, is
its publisher's business, not Kav's ([Third-party extensions](/third-party-extensions)). The exception is Kav's own
source, Kav extensions (github.com/ImNoammm/kav-extensions): Kav makes those extensions, and they send nothing anywhere.
Kav adds that source the first time you open Settings › Extensions or add a source. No extension runs until you switch
it on.

**What Kav asks for them.** For a source on GitHub, when you add it, and each time Kav's background check runs or you
tap Check now, Kav asks GitHub's public API for the repository's latest release, without signing in. It's a conditional
request, so GitHub sends the release again only when it has changed. When there's a newer bundle, Kav downloads it from
GitHub. For a source whose bundle has no picture of its own, Kav also asks GitHub for the picture of the repository's
owner. Nothing about you or your trips goes with these requests. GitHub sees your IP address and which repository is
asked for, as with any web request. Kav doesn't check a bundle file you added from your phone for updates. Kav reads its
own source from GitHub the same way. In the same check, and when you tap Check now, Kav asks GitHub for its security
block list, as it does for its own updates; nothing about you goes with it. With the list's switch off, Kav doesn't ask
for it or apply it. Today the list is empty. While Add a source is open, Kav asks this website, at most once a day, for
the [community list](/community) and its sources' pictures, to suggest a few sources there; nothing about you goes with
it, and this website keeps no access logs.

**What an extension can reach.** Every extension comes in off. Its page lists, under Details, what its publisher
declares it does. Once you switch one on, it runs at once, inside Kav, with full access, whatever its page says:
anything Kav can do, it can do. That includes your location, the camera, notifications and the internet, everything Kav
keeps on your phone (your places, favorites, recent trips and settings, and tickets and the ride history read from
Rav-Pass), and Kav's ticket reader while it's on. It can send any of this to any server, and that server gets your IP
address too. Kav can't limit or see what an extension does.

For each extension, Kav keeps a log on your phone, shown on its page: the lines the extension writes there itself, and
its failures. An extension connects to the internet by itself, not through Kav, so its requests aren't in that log.

The [add-source page](/add-source) on this website reads a shared link in your browser and sends it nowhere, and the
[community list](/community) shows sources' pictures from this site, so neither asks GitHub anything.

## On your phone

Your places, favorites, recent trips and settings stay on your phone. Backups are a `.kav` file you save where you
choose. Location is used on the phone to plan from where you are and to follow your trip; it isn't sent anywhere except
as described above.

Glyph alerts and trip progress on supported Nothing phones run on the phone and send nothing.

## This website

kavapp.link uses no cookies, no analytics and no fonts or scripts from anyone else, and keeps no access logs. The
Download button and the version history ask GitHub's API for the latest release from your browser, so GitHub sees that
request. The APK itself downloads from GitHub.

## Questions

Kav is open source, so all of this can be checked in [the code](https://github.com/ImNoammm/kav). Questions and
corrections are welcome as [an issue on GitHub](https://github.com/ImNoammm/kav/issues).
