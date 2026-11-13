# Running your own Kav server

This folder is everything needed to run your own copy of Kav's server on a machine of yours: live buses and stop
boards, the trip planner, Kav's predicted arrival times, the week's timetable the app downloads, and trip links. It is
a normal Docker Compose app. Its parts are prebuilt images on GitHub's registry, and a setup page in the browser sets
them up for you.

Run all of it or only the parts you want, and replace any part with one of your own.

## Contents

1. [What it is](#what-it-is)
   - [The parts](#the-parts)
   - [What your phone gets from your server](#what-your-phone-gets-from-your-server)
2. [What you need](#what-you-need)
   - [Minimum and recommended](#minimum-and-recommended)
   - [Machine and Docker](#machine-and-docker)
   - [Memory](#memory)
   - [Disk](#disk)
   - [Network and ports](#network-and-ports)
   - [A key from the Ministry of Transport](#a-key-from-the-ministry-of-transport)
3. [Installing](#installing)
   - [The images](#the-images)
   - [With the install script](#with-the-install-script)
   - [By hand](#by-hand)
   - [From source](#from-source)
   - [With Portainer, Dockge or another Compose UI](#with-portainer-dockge-or-another-compose-ui)
   - [Opening the setup page](#opening-the-setup-page)
   - [After installing](#after-installing)
   - [Uninstalling](#uninstalling)
4. [The setup page](#the-setup-page)
   - [Your account](#your-account)
   - [The first setup, step by step](#the-first-setup-step-by-step)
   - [The dashboard](#the-dashboard)
5. [Connecting a phone](#connecting-a-phone)
6. [Address and HTTPS](#address-and-https)
7. [Who may use it](#who-may-use-it)
8. [The parts, and swapping each one](#the-parts-and-swapping-each-one)
   - [Live buses](#live-buses)
   - [The trip planner](#the-trip-planner)
   - [One area, for a smaller machine](#one-area-for-a-smaller-machine)
   - [Arrival times](#arrival-times)
   - [Timetable and map](#timetable-and-map)
   - [Line alerts](#line-alerts)
9. [The nightly build](#the-nightly-build)
   - [Build steps](#build-steps)
10. [Updates](#updates)
11. [Storage and backups](#storage-and-backups)
12. [Every setting in .env](#every-setting-in-env)
13. [Running it day to day](#running-it-day-to-day)
    - [Locking the setup page](#locking-the-setup-page)
    - [A lost admin password](#a-lost-admin-password)
14. [When something's wrong](#when-somethings-wrong)
15. [Security](#security)
16. [For developers](#for-developers)

## What it is

### The parts

Each part is a service in `docker-compose.yml`. Some run only when their Compose profile is on (`COMPOSE_PROFILES` in
`.env`, which the setup page sets).

| Service | Image | Profile | What it does |
|---|---|---|---|
| `live` | `kav-live` | always | The server phones talk to (`server/live.py`). Reads the live buses every 15 seconds, answers the stop boards, the map, the lines, trip planning, the week's timetable, phone sign-in and trip links. Listens on port 8080. |
| `build` | `kav-build` | always | Fetches the ministry's timetable and OpenStreetMap every night and builds the planner from them, and the timetable the app downloads. Also fetches the arrival model's files. |
| `otp` | `kav-otp` | `planner` | The trip planner: OpenTripPlanner 2.10, serving the graph `build` made. |
| `otp-official` | `kav-otp` | `official` | An optional second planner on the ministry's own times, whose routes are added after Kav's own. |
| `eta` | `kav-eta` | `predictor` | Kav's predicted arrival times for buses, trains and the Tel Aviv light rail, every 30 seconds. |
| `translate` | `kav-translate` | `translate` | The line alerts in English (`server/translate.py`, OPUS-MT's Hebrew to English model). |
| `https` | `caddy:2` | `https` | HTTPS for your domain, with a certificate from Let's Encrypt. Ports 80 and 443. |
| `setup` | `kav-setup` | `setup`, started by name | The setup page and dashboard on port 8090. Writes `.env` and starts the rest. A plain `docker compose up -d` leaves it out; `docker compose up -d setup` starts it. |
| `init` | `busybox:1.37` | always | Runs once at each start: makes the folders in `data/` and gives them to user 1000, which every Kav service runs as. |

The images are `ghcr.io/imnoammm/kav-live`, `kav-otp`, `kav-build`, `kav-eta`, `kav-translate` and `kav-setup`, tagged with the release
(`v4.0`, say). `KAV_VERSION` in `.env` says which release runs.

### What your phone gets from your server

In Kav, **Settings › Live times and planners** has a choice for each thing the app gets from a server: live times, trip
planning, the timetable and where share links are made. Each choice is its own: after you connect your own server, you
can set any of them to **My own** and leave the rest on Kav's server, Bus Nearby or EGG, in any mix. Set all of
them to your own and the app needs nothing from Kav's server. Everything Kav's server does, your server can do:

| What the app asks for | From your server | Address on your server |
|---|---|---|
| Live buses on the map, the lines, a trip under way | Yes, when it has a live source | `/live/v1/area/...`, `line/...`, `follow/...` |
| Stop boards (arrivals at a stop) | Yes, when it has a live source | `/live/v1/stops/...` |
| A route's road on the map | Yes | `/live/v1/shape/...` |
| Trip planning, and your server's tab when the app shows a tab for each planner (each tab, Kav's too, can be hidden) | Yes, when it has a planner | `/live/v1/plan/...` |
| Predicted arrival times | Yes, when it runs a predictor | in the answers above |
| The week's timetable | Yes, built here every night and signed with this server's own key | `/live/v1/bundle/...` |
| Whether the server works, and how to sign in | Yes | `/live/v1/status` |
| Signing a phone in and out | Yes, on a server with accounts | `/live/v1/auth/login`, `auth/logout` |
| Trip links you share (the "Share links made on" choice; until you pick, on your server when Live or Planning is My own) | Yes, made and kept here, on your server's address | `/api/share`, `/<code>` |
| Line alerts (detours, delays, moved stops) on your rides, the "Alerts from" choice | Yes, when it has an [alerts source](#line-alerts) | `/live/v1/alerts/<he or en>` |

Two things never came from Kav's server, so your server doesn't take them over:

- **The maps.** Kav's own map and the OpenFreeMap maps download from GitHub, from the `map-1` and `maps-1` releases.
  Satellite photos load from Esri, and the OpenFreeMap maps load from OpenFreeMap until they're downloaded.
- **The app's own update check** asks GitHub.

And a link a friend shares from Kav's own server (`share.kavapp.link/...`) still opens from Kav's server, because that
trip is kept there.

Under each "My own" choice, Settings says what, as set now, still goes to Kav's server, or that nothing does. To stop
using your server, choose **Forget this server** in the same place.

> This needs the Kav app release that adds hiding a planner tab and the share-links choice; with older releases, a
> planner tab for Kav's server is always asked and share links are made on Kav's server unless Live or Planning is
> your own.

## What you need

### Minimum and recommended

One row for each way of running it, smallest first. Each row includes the ones above it, except where it says
otherwise. The app's timetable is built on every server (the `build` service always runs), so "live buses only" and
"live buses and the timetable" are the same machine.

| What runs | Cores, at the least | Cores for every part at its full limit | Memory, at the least (at night) | Memory while running | Disk, at the least |
|---|---|---|---|---|---|
| Live buses and the app's timetable | 2 | 4 | 2 GB | 1 GB | 4 GB |
| Kav's planner, one area | 2 | 6 | 7 GB | 3 GB | 7 GB |
| Kav's planner, one area, with Kav's arrival times | 2 | 10 | 9.5 GB | 5.5 GB | 8 GB |
| Kav's planner, all of Israel | 2 | 6 | 22 GB | 6 GB | 7 GB |
| Kav's planner, all of Israel, with Kav's arrival times | 2 | 10 | 24.5 GB | 8.5 GB | 8 GB |
| The above, with the second planner on the ministry's times | 2 | 12 | 29.5 GB | 13.5 GB | 9 GB |

What each column means, and where its numbers come from:

- **Cores, at the least.** Two. Nothing refuses to run on one, but the setup page warns that the nightly build will be
  slow there.
- **Cores for every part at its full limit.** The `cpus:` limits in `docker-compose.yml` added up for what runs at
  the same time at night: the nightly build 4, each planner 2, the predictor 4. More cores than that don't make
  anything faster. The build runs at low priority (`cpu_shares: 128`), so with fewer cores the live buses and the
  planner still come first and the build takes longer. These are the limits, not a measurement.
- **Memory, at the least.** The night total from `needs()` in `setup/server.py`, measured on 7 November 2026: the
  nightly build runs beside everything else, and the planner keeps answering from its old graph while the new one is
  built. Plan for this number, not the one while running. More in [Memory](#memory).
- **Memory while running.** The same, without the nightly build. It's what the setup page's memory card compares with
  the machine.
- **Disk, at the least.** The images, the data after the 70 days the recorded passings are kept, the nightly build's
  working space, and an update's new images beside the old ones, added up from the sizes measured below and rounded
  up. One area's rows use all of Israel's planner data, so they're on the safe side. More in [Disk](#disk). The setup
  page warns below 10 GB free.

**Recommended:** the night total plus what the system and anything else on the machine use, the cores in the second
column, and 10 GB of free disk for any of these. For comparison, Kav's own server runs the last row on 12 cores and
31 GB of memory.

**Below the minimum:**

- **Too little memory at night**: the nightly build is killed (exit code 137, or `OutOfMemoryError` in its log). A
  first build tries again an hour later, so the planner never starts; a later one leaves the planner answering from
  the graph it had. Live buses and the timetable go on. The Overview shows the build's error.
- **Too little memory while running**: each service is capped (`mem_limit`), so a part that needs more than its cap is
  stopped and started again by Docker, and the machine itself may swap or stop processes when the caps add up to more
  than it has.
- **One core**: everything runs, and the nightly build is slow. While it runs, the live buses and the planner share
  that core with it, ahead of it.
- **Too little disk**: an update can't download its images, so nothing changes and the Updates page says so; a build
  that runs out of space fails and the planner keeps its graph.

### Machine and Docker

- **Linux on amd64 or arm64.** Every image is built for both.
- **Docker Engine with the Compose plugin** (the `docker compose` command, not the old `docker-compose`). Install Docker
  from [its own instructions](https://docs.docker.com/engine/install/). Versions:
  - Kav's own server runs Docker Engine 29.6.1 with Compose 5.3.1.
  - The setup page drives the host's Docker from its own container, with Docker 27's client and Compose 2.33.0
    (`docker:27-cli`).
  - Older versions weren't tested. The compose file needs Compose's profiles and `depends_on` with
    `condition: service_completed_successfully`.
- **Docker's socket at `/var/run/docker.sock`**, which the setup service mounts. Rootless Docker keeps its socket
  elsewhere, so the setup page can't reach it as shipped; [without the setup page](#by-hand) everything else runs.
- **Docker Desktop** (macOS, Windows) wasn't tested.
- **A user that can run Docker** (in the `docker` group), to run the install script as.
- **Two processor cores at the least.** The planner may use 2, the nightly build 4 and the predictor 4 (their
  `cpus:` limits); the build runs at low priority (`cpu_shares: 128`) so the live buses and the planner come first.
- **Israel's time doesn't need to be set on the machine.** The build and the setup page work in Israel time by
  themselves.

### Memory

Memory depends on what you run. These are the numbers the setup page shows (`needs()` in `setup/server.py`, measured
on 7 November 2026). At night the nightly build runs beside everything else, and the planner keeps answering from its
old graph while the new one is built, so the night total is the one to plan for.

| What runs | While running | At night, while it builds |
|---|---|---|
| Live buses and the timetable only | 1 GB | 2 GB |
| The same, with Kav's arrival times | 3.5 GB | 4.5 GB |
| Kav's planner, all of Israel | 6 GB | 22 GB |
| Kav's planner, all of Israel, with Kav's arrival times | 8.5 GB | 24.5 GB |
| The above, with the second planner on the ministry's times | 13.5 GB | 29.5 GB |
| Kav's planner, one area | 3 GB | 7 GB |
| Kav's planner, one area, with Kav's arrival times | 5.5 GB | 9.5 GB |
| The above, with the second planner | 7.5 GB | 11.5 GB |

[Line alerts](#line-alerts) in English add about 0.5 GB.

Where the numbers come from:

- `live`: about 1 GB in all with the timetable's build (its container is capped at 512 MB).
- Kav's planner for all of Israel: 5 GB while it answers (`PLANNER_MEMORY`, Java's heap is 80 percent of it) and up to
  16 GB while it builds (`BUILD_MEMORY=15` for the container, with a 14 GB Java heap, plus the rest).
- One area: 2 GB while it answers and about 4 GB while it builds (`PLANNER_MEMORY=2`, `BUILD_MEMORY=4`, which the
  setup page sets by itself). A smaller heap than 3 GB for the build runs out of memory.
- The second planner takes as much memory again as the first while it answers. Its graph is built after the first one
  each night, inside the same build container, so the night peak doesn't grow.
- Kav's arrival times: about 2.5 GB. Its first day's history medians take a few GB more for a minute or two; its
  container is capped at 8 GB, and spills to `data/eta/spill` when memory runs short.

Without enough memory the build fails and the planner keeps the graph it had (see
[When something's wrong](#when-somethings-wrong)).

### Disk

The images, measured on amd64, counting each layer once:

| Image | Size |
|---|---|
| `kav-otp` | 862 MB (the same as OpenTripPlanner's own image) |
| `kav-build` | 683 MB |
| `kav-eta` | 572 MB |
| `kav-setup` | 348 MB |
| `kav-live` | under 1 MB on top of `python:3.12-slim` (177 MB) |
| `caddy:2`, with the `https` profile | 25 MB, as `docker image inspect` reports it |
| `busybox:1.37` (`init`) | 2 MB, the same way |

That's about 1.2 GB for live buses and the timetable alone and about 2.7 GB for everything. During an update the new
release's images are downloaded beside the old ones before the old ones are removed, so leave as much again free.

What it keeps in `data/`, measured on Kav's own server on 7 November 2026:

| What | Size |
|---|---|
| The planner's data, all of Israel (timetable, map, built graph) | about 1.4 GB |
| The recorded passings (when each bus passed each stop), kept 70 days | about 0.8 GB |
| The arrival model's files | about 62 MB |

The planner's data on Kav's own server, folder by folder (both planners, all of Israel):

| Folder | Size | What |
|---|---|---|
| `data/otp/serve` | 391 MB | the graph Kav's planner answers from (`graph.obj`, 409 MB as a file) |
| `data/otp/build` | 295 MB | the nightly build's working folder: the night's timetable and map, and the new graph until it's moved in |
| `data/otp/official` | 149 MB | the ministry's timetable as fetched |
| `data/otp/serve-official`, `build-official` | 380 MB, 149 MB | the same for the second planner |

While the night's build runs, the new graph is written in `data/otp/build` beside the one the planner answers from,
so leave room for one graph more (about 0.4 GB for all of Israel). One area's planner data is smaller. The setup page
warns when less than 10 GB is free; 10 GB is enough for everything for all of Israel with room for an update.

### Network and ports

| Port | Who listens | Open to | Setting |
|---|---|---|---|
| 8090 | The setup page | your own network only (it refuses everyone else) | `SETUP_LISTEN`, `SETUP_PORT` |
| 8080 | `live`, the server phones talk to | this machine only (`127.0.0.1`), for a proxy here | `LISTEN`, `PORT` |
| 80, 443 | `https` (Caddy), with the `https` profile | the internet | |

The machine needs to reach these, all over HTTPS (port 443 out):

| Address | For what | Without it |
|---|---|---|
| `ghcr.io` (and the storage it sends downloads to) | Kav's images, at install and each update | nothing installs or updates |
| Docker Hub (`registry-1.docker.io`) | `caddy:2` and `busybox:1.37` | nothing installs |
| `api.github.com` | the latest release (install.sh, the Updates page) | install.sh needs `KAV_VERSION` set; the page can't offer updates |
| `raw.githubusercontent.com` | the release's `docker-compose.yml`, `.env.example` and `Caddyfile` | nothing installs or updates |
| `github.com` (and its release downloads) | the arrival model's files (`MODELS_URL`) | Kav's arrival times don't start |
| `gtfs.mot.gov.il` | the ministry's timetable, every night (`GTFS_URL`) | no planner build, no app timetable; the last ones stay |
| `moran.mot.gov.il`, `moran-t.mot.gov.il` | live buses (`MOT_URL`, `MOT_URL_BACKUP`), with your key | no live buses |
| `download.geofabrik.de` | OpenStreetMap's Israel, once a week (`OSM_URL`) | no planner build |
| `rail-api.rail.co.il` | Israel Railways' own timetable, for the trains in the planner and their platforms | trains as the ministry's timetable has them |
| `opendatasource.tel-aviv.gov.il` | Tel Aviv's weekend lines (Na'im BeSofash), which no ministry feed has | the planner is built with the last copy it fetched, or without them |
| Let's Encrypt (`acme-v02.api.letsencrypt.org`) | the certificate, with the `https` profile | no HTTPS at your domain |

Tel Aviv's site answers addresses outside Israel with a maintenance page, so a server abroad goes without the weekend
lines.

The planner and the predictor are on an internal Docker network (`inside`) that reaches nothing but `live`.

### A key from the Ministry of Transport

Live buses from the ministry need your own key for its real-time service (SIRI). Apply on
[its page](https://www.gov.il/he/departments/general/real_time_information_siri). The ministry approves each key for
**one IP address**, so apply with the public address of the machine you'll run this on, and the key won't work from
anywhere else. Kav's own key can't be shared.

Without a key, everything else still works: the planner, the timetable and trip links. Phones can take live buses from
Kav's server or another provider meanwhile, or from [another live source](#live-buses).

## Installing

There are five ways in, all ending in the same Compose app in a folder of its own (`~/kav-server` unless you choose
another):

1. [The install script](#with-the-install-script): one command, then the setup page. The easy way.
2. [By hand](#by-hand): the same steps the script takes, typed yourself, then the setup page.
3. [With .env alone](#setting-it-up-with-env-alone): no setup page at all. You write `.env`, make accounts on the
   command line, and update by changing one line.
4. [From source](#from-source): the images built from a checkout of the repository, for working on Kav.
5. [A Compose UI](#with-portainer-dockge-or-another-compose-ui) such as Portainer or Dockge, with what works there.

### The images

Every Kav part is an image on GitHub's registry. `docker-compose.yml` runs them as
`${KAV_IMAGES}/kav-<part>:${KAV_VERSION}`, so `.env` decides which registry and which release.

| Image | What it does | Built from | Size (amd64) | Runs |
|---|---|---|---|---|
| `ghcr.io/imnoammm/kav-live` | The server phones talk to (`server/live.py`) | `python:3.12-slim` | under 1 MB on its base (177 MB) | always (`live`) |
| `ghcr.io/imnoammm/kav-otp` | The trip planner: OpenTripPlanner 2.10 with Kav's start script | `opentripplanner/opentripplanner:2.10.0` | 862 MB | the `planner` profile (`otp`), and `official` (`otp-official`) |
| `ghcr.io/imnoammm/kav-build` | The nightly build: timetable, map, the planner's graph, the app's timetable | `python:3.12-slim`, with OpenTripPlanner's Java and jar | 683 MB | always (`build`) |
| `ghcr.io/imnoammm/kav-eta` | Kav's predicted arrival times | `python:3.12-slim` | 572 MB | the `predictor` profile (`eta`) |
| `ghcr.io/imnoammm/kav-setup` | The setup page and dashboard, with Docker's client and Compose | `docker:27-cli` | 348 MB | the `setup` profile, started by name (`setup`) |
| `ghcr.io/imnoammm/kav-translate` | The line alerts in English (`server/translate.py`), with OPUS-MT's model | `python:3.12-slim`, with CTranslate2 | 935 MB | the `translate` profile (`translate`) |

Two more come from Docker Hub as they are: `caddy:2` (the `https` profile) and `busybox:1.37` (`init`, which makes
the folders in `data/` at each start).

- **Tags.** Each release's tag (`v4.0`, say) on all six, from the same commit. `latest` moves with every release
  whose tag has no `-` in it, so pre-releases and nightly builds (`v4.0-rc1`, `v4.0-nightly`) never move it.
  `KAV_VERSION` missing from `.env` means `latest`.
- **Architectures.** Every image is built for `linux/amd64` and `linux/arm64`; Docker picks the machine's own.
- **Another registry.** A fork that publishes its own images sets `KAV_IMAGES=ghcr.io/<you>` (and `KAV_REPO=<you>/kav`
  for its releases); see [Releasing](#releasing).

### With the install script

On the machine it's for, as a user that can run Docker (it needs `sh` and `curl`, and no `sudo`):

```sh
curl -fsSL https://raw.githubusercontent.com/ImNoammm/kav/main/server/selfhost/install.sh | sh
```

What it does:

1. Checks that `docker` and `docker compose` are there.
2. Asks GitHub for Kav's latest release (not a pre-release).
3. Makes `~/kav-server` and downloads that release's `docker-compose.yml`, `.env.example` and `Caddyfile` into it, and
   makes an empty `stages` folder for build steps of your own.
4. Writes `.env` with `KAV_VERSION=<the release>` and `KAV_APP_KEY=<a random key>` (and `KAV_REPO`, `KAV_IMAGES` when
   you set them), readable by you alone.
5. Starts the setup page (`docker compose up -d setup`), which pulls its image.
6. Prints the setup page's address with a one-time code, and the SSH command for reaching it from elsewhere.

It downloads nothing else and doesn't clone the repository. Run again on a folder that has a `docker-compose.yml`
already, it changes nothing and only starts the setup page.

You can set these before `sh`, as in `curl ... | KAV_DIR=/srv/kav sh`:

| Variable | What it does | Default |
|---|---|---|
| `KAV_DIR` | Where it installs | `~/kav-server` |
| `KAV_VERSION` | A release's tag instead of the latest, say `v4.0` | the latest release |
| `KAV_REPO` | A fork of yours on GitHub, `you/kav` (written to `.env`, so updates come from it too) | `ImNoammm/kav` |
| `KAV_IMAGES` | The registry your fork's images are in, `ghcr.io/you` (written to `.env`) | `ghcr.io/imnoammm` |

### By hand

The same as any Compose app. Pick a release from [Releases](https://github.com/ImNoammm/kav/releases) and use its tag
in place of `<release>`:

```sh
mkdir -p ~/kav-server/stages && cd ~/kav-server
for f in docker-compose.yml .env.example Caddyfile; do
  curl -fsSLO https://raw.githubusercontent.com/ImNoammm/kav/<release>/server/selfhost/$f
done
echo KAV_VERSION=<release> > .env
chmod 600 .env
docker compose up -d setup
cat data/setup-code
```

Then open `http://<this machine>:8090/?code=<the code>` (more in [Opening the setup page](#opening-the-setup-page)).

What the three files are:

- `docker-compose.yml`: every service. Read the one you install at its release:
  `https://github.com/ImNoammm/kav/blob/<release>/server/selfhost/docker-compose.yml`. That address always shows the
  file that release runs, where a copy here would only be right for one release.
- `.env.example`: every `.env` line, with what it does.
- `Caddyfile`: the `https` service's settings, used with a domain of your own.

Always run `docker compose` from inside the folder: the setup service mounts the folder by its path (`${PWD}`).

### Setting it up with .env alone

Everything the setup page does can be done with `.env` and two commands, and the server never needs the page. The
`setup` service is in its own Compose profile, so a plain `docker compose up -d` runs the server from `.env` without
it.

**1. The files.** Pick a release from [Releases](https://github.com/ImNoammm/kav/releases) and use its tag in place of
`<release>` (the `Caddyfile` only matters with the `https` profile, but it's small):

```sh
mkdir -p ~/kav-server/stages && cd ~/kav-server
for f in docker-compose.yml .env.example Caddyfile; do
  curl -fsSLO https://raw.githubusercontent.com/ImNoammm/kav/<release>/server/selfhost/$f
done
cp .env.example .env
chmod 600 .env
```

**2. `.env`.** Open it in an editor. Every line is explained in it and in [Every setting in .env](#every-setting-in-env).
These are the ones to set:

- `KAV_VERSION=<release>`: add this line. Without it Compose runs the `latest` images, which change under you.
- `KAV_APP_KEY`: **required**, unless `ACCESS=open`. The server's own services sign their requests to `live` with it
  (and phones too, with `ACCESS=key`). The setup page and install.sh make one; here you do. With it empty, `live`
  doesn't start: `docker compose logs live` says `KAV_APP_KEY is empty`. Any text of 16 characters or more, made once
  and kept. This makes one the way install.sh does and puts it in the empty line `.env.example` has:

  ```sh
  sed -i "s/^KAV_APP_KEY=.*/KAV_APP_KEY=$(head -c 18 /dev/urandom | base64 | tr '+/' '-_')/" .env
  ```

- `COMPOSE_PROFILES`: the parts, comma-separated. Empty, only `live` and `build` run: live buses and the app's
  timetable.

  | Profile | Starts | Also set |
  |---|---|---|
  | `planner` | Kav's planner (`otp`), built every night | `PLANNER=on` (the default) |
  | `official` | the second planner, on the ministry's own times (`otp-official`) | `OFFICIAL_URL=http://otp-official:8080/otp/gtfs/v1`, and `planner` |
  | `predictor` | Kav's arrival times (`eta`) | nothing; `MODELS_URL` and `MODELS_SHA256` for a model of your own |
  | `https` | Caddy, with a Let's Encrypt certificate, on ports 80 and 443 | `DOMAIN` |
  | `setup` | the setup page, on every `up` | leave it out here |

- **Live buses**: `MOT_KEY=<the ministry's key>` ([A key from the Ministry of Transport](#a-key-from-the-ministry-of-transport)),
  or `LIVE_URL=<another source>` with its key, if any, in `MOT_KEY` ([Live buses](#live-buses)). Neither: no live buses.
- **The address phones use**, which has to be HTTPS: `DOMAIN=kav.example.com` with `https` in `COMPOSE_PROFILES`, or
  `PUBLIC_URL=https://kav.example.com` behind a proxy or tunnel of your own that sends everything to port 8080
  ([Address and HTTPS](#address-and-https)).
- **Who may use it**: `ACCESS=accounts` (the default; accounts in step 4) or `ACCESS=open`
  ([Who may use it](#who-may-use-it)).
- **Planning**: Kav's planner is `planner` in `COMPOSE_PROFILES` with `PLANNER=on`. A planner of your own:
  `PLANNER=own` with `OTP_URL=<your OpenTripPlanner>` or `PLAN_URL=<a planner you wrote>`. None: `PLANNER=off`
  ([The trip planner](#the-trip-planner)).
- **Arrival times**: Kav's own is `predictor` in `COMPOSE_PROFILES`. A predictor of your own elsewhere:
  `PREDICT_URL=<its address>` and `MODELS_URL=` (empty). The ministry's own times: `ETA=` and `MODELS_URL=`, both
  empty ([Arrival times](#arrival-times)).
- **One area, for a smaller machine**: `BOUNDS=south,west,north,east` (say `31.95,34.70,32.20,34.95` for Gush Dan),
  with `BUILD_MEMORY=4` and `PLANNER_MEMORY=2`, which the setup page would set for you
  ([One area](#one-area-for-a-smaller-machine)).

The rest works as it is.

**3. Start it.**

```sh
docker compose up -d
docker compose ps
```

Live buses come within a minute; Kav's planner builds first (about half an hour for all of Israel, `docker compose
logs -f build` shows it). After changing `.env` later, `docker compose up -d` again.

**4. Accounts** (with `ACCESS=accounts`). `accounts.py` in the `live` container does what People does on the page. It
asks for passwords, so run it with `-it`:

```sh
docker compose exec -it live python3 accounts.py add <name>             # asks for their password
docker compose exec -it live python3 accounts.py add <name> --invite    # no password: prints an invite
docker compose exec -it live python3 accounts.py list
```

| Command | What it does |
|---|---|
| `add NAME [--admin] [--invite]` | Adds someone, asking for their password. `--invite`: no password; it prints an invite code instead. `--admin`: may also sign in to the setup page, if you ever start it |
| `list` | Everyone, with admin or turned off, and how many phones |
| `password NAME` | Sets someone's password (asks for it). Their phones stay signed in |
| `invite NAME` | A new invite code, good once for 7 days, and the `kav://server?address=...&invite=...` link to make a QR code from |
| `remove NAME` | Removes someone and signs out their phones |
| `admin NAME on` or `off` | Makes someone an admin, or not |
| `disable NAME on` or `off` | Turns someone off (signing out their phones), or on again |

Passwords follow the page's rules: at least 8 characters, not a common one.

**5. Connect phones.** In Kav, **Settings › Live times and planners › My own**: type the server's address and press
**Connect**, then sign in with the username and password; or scan a QR code made from the `kav://` link that
`invite` prints (any QR code maker will do), which signs the phone in with no password. More in
[Connecting a phone](#connecting-a-phone).

**6. Updating.** Set `KAV_VERSION` to the new release, fetch that release's three files over the old ones (keep
`.env`), and pull:

```sh
v=<release>
for f in docker-compose.yml .env.example Caddyfile; do
  curl -fsSLO https://raw.githubusercontent.com/ImNoammm/kav/$v/server/selfhost/$f
done
sed -i "s/^KAV_VERSION=.*/KAV_VERSION=$v/" .env
docker compose pull && docker compose up -d
```

Read the release's notes first: a release that adds a `.env` line says so, and `.env.example` explains it.

**If you start the setup page later** (`docker compose up -d setup`, then `cat data/setup-code` for its code): it
doesn't read your `.env` into its choices. Its wizard starts from its own defaults, and when you finish it, it writes
every line it manages ([the list](#every-setting-in-env)) from what you chose there, keeping the rest of `.env` as it
is. Choose the same things, or check them after. From then on, a managed line you change by hand is named on the
Overview and on Parts ("`.env` was changed by hand"), before **Save** puts back what the page has. The accounts you
made with `accounts.py` are the page's own, as they're the same file.

### From source

For working on Kav: the images built from a checkout of the repository instead of pulled from the registry.

```sh
git clone https://github.com/ImNoammm/kav.git
cd kav/server/selfhost
docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile '*' build
docker compose up -d setup
cat data/setup-code
```

`docker-compose.dev.yml` adds a `build:` to each Kav service, and the built images take the names
`docker-compose.yml` runs, so the setup page starts these. Leave `KAV_VERSION` out of `.env`: the page then offers no
updates, which would pull the published images over yours. More in [For developers](#for-developers).

### With Portainer, Dockge or another Compose UI

What `docker-compose.yml` needs, and so what a Compose UI has to give it:

- **The setup service mounts Docker's socket** (`/var/run/docker.sock`) and **this folder at the same path as on the
  host**, found through `${PWD}`. Compose takes `${PWD}` from the environment of whatever runs `docker compose`. A shell
  sets it to the folder you're in. A program that runs Compose for you passes its own, which isn't this folder unless
  it sets it.
- **Every other service mounts folders next to the file**: `./data/...`, `./stages`, `./Caddyfile`. They have to
  resolve to a real folder on the host.

So:

- **Works:** a server set up [with .env alone](#setting-it-up-with-env-alone), deployed from the UI. A plain `up`
  leaves the setup service out (it's in its own `setup` profile), so `${PWD}` doesn't matter, as long as the UI keeps
  the files in a folder on the host so that `./data` lands there. Put `.env` beside `docker-compose.yml` as the UI
  allows, and run `accounts.py` from the UI's console for the `live` container.
- **Works:** installing with the script or by hand from a shell, then using the UI to watch it. The containers are the
  Compose project `kav`, and the UI can show their state and logs, and stop and start them. The setup page runs
  Compose itself with `PWD` set to the folder (`setup/server.py`), so its saves and updates are right whatever
  started it.
- **Doesn't work as is:** starting the setup service from a UI that doesn't set `PWD` to the stack's folder on the
  host (by naming it, or with `setup` in `COMPOSE_PROFILES`). It then mounts the wrong folder, and can't find `.env` or
  `data/`. Start it from a shell in the folder instead: `docker compose up -d --force-recreate setup`.

### Opening the setup page

The setup page answers only requests from this machine itself, its local network (private addresses such as
`192.168.x.x` and `10.x.x.x`) and Tailscale (`100.64.0.0/10`). Anyone else gets "Only from nearby".

- **On the same network:** open the address the script printed, `http://<this machine's address>:8090/?code=...`.
- **A machine you reach over the internet (a VPS):** tunnel to it, then open `http://localhost:8090/?code=...` on your
  own computer:

  ```sh
  ssh -L 8090:localhost:8090 <you>@<the machine>
  ```

- **Over Tailscale:** open `http://<its Tailscale address>:8090/`.

The one-time code is in `data/setup-code` until your account is made, and then it's deleted. Without it, the page
opens nothing.

### After installing

- **First**, make your account and go through [the first setup](#the-first-setup-step-by-step), then
  [connect a phone](#connecting-a-phone). Live buses come at once; Kav's planner builds first (about half an hour
  for all of Israel).
- **Then lock the setup page** when you're done with it: [Locking the setup page](#locking-the-setup-page).
- **Updating**: Updates on the setup page, by hand or automatically: [Updates](#updates).
- **Staying on a release, or going back to an older one**: [Updates](#updates) has both, with the commands.
- **Day to day**: logs, health, stopping and starting: [Running it day to day](#running-it-day-to-day).
- **Backups**: [Storage and backups](#storage-and-backups).

### Uninstalling

In the install folder:

```sh
cd ~/kav-server
docker compose --profile '*' down
```

This stops and deletes Kav's containers (every profile's) and its two networks. The images, `.env` and everything in
`data/` stay, so `docker compose up -d` brings it all back as it was (and `docker compose up -d setup` the setup page).

```sh
docker image rm $(docker image ls --format '{{.Repository}}:{{.Tag}}' | grep '/kav-')
```

This deletes Kav's images: every `kav-` image on the machine, whatever release. `caddy:2` and `busybox:1.37` stay, as
other apps may use them; `docker image rm caddy:2 busybox:1.37` deletes them too.

```sh
cd ~ && sudo rm -rf ~/kav-server
```

This deletes the folder: `.env` with the ministry's key, and in `data/` the accounts, the trip links, the recorded
history, the timetable's signing key and the certificates. It can't be undone, so [back up](#storage-and-backups) first
if you might want any of it. `sudo` is needed because some files in `data/` belong to user 1000 or to root.

## The setup page

### Your account

The first visit, with the code, asks you to make the owner's account: a username (letters, numbers, dots, dashes and
underscores) and a password of at least 8 characters that isn't a common one. It's an admin account. From then on only
the server's **admins** can sign in to the setup page. Everyone else's accounts are for the Kav app only.

### The first setup, step by step

A short wizard of five steps, one card at a time, with **Back** and **Next** and the progress along its foot. It saves
your choices as you go, so you can close the page and come back to the same step. Everything it asks can be changed
later on the dashboard's Parts page.

Before the first step's choices, it warns about this machine when it needs to: Docker not answering (nothing can
start), less than 10 GB of disk free, or a single processor (the nightly build will be slow).

**1. Live buses.** Where the buses' live positions come from.

| Option | What it means | `.env` |
|---|---|---|
| The Ministry of Transport | Live buses for all of Israel, with your own key. **Test** asks the ministry once, as the server will, and says how many vehicles it answered with or what went wrong. | `MOT_KEY` |
| Another source | Anything that answers SIRI StopMonitoring as the ministry does, at an address (see [Live buses](#live-buses)). Its key, if it has one, goes in the same Key field. | `LIVE_URL`, and `MOT_KEY` if given |
| None | No live buses here. Phones take them from elsewhere; the server does the rest. | neither |

**2. Address.** How phones reach the server. The app talks to servers over HTTPS only, so this step can't be left empty.

| Option | What it means | `.env` |
|---|---|---|
| A domain for this machine | A domain name pointing at this machine, with ports 80 and 443 open to it. The certificate comes by itself. | `DOMAIN`, and `https` in `COMPOSE_PROFILES` |
| My own proxy or tunnel | The address your proxy or tunnel gives the server. Send all of `https://<address>/` to port 8080 on this machine. | `PUBLIC_URL=https://<address>` |

Type the address without `https://`. More in [Address and HTTPS](#address-and-https).

**3. Who may use it.**

| Option | What it means | `.env` |
|---|---|---|
| People with an account | Each person signs in once on each phone, or scans an invite you give them. The default. | `ACCESS=accounts` |
| Anyone | No signing in. Anyone who knows the address can use the server, up to 120 requests a minute each. | `ACCESS=open` |

More in [Who may use it](#who-may-use-it).

**4. Parts.** What runs here. A card at the top says what this machine has (memory, processors, free disk, Docker's
version), and a memory card at the foot shows, as you choose, how much the choices take while running and at night
against what this machine has.

*Trip planner:*

| Option | What it means | `.env` |
|---|---|---|
| Kav's planner | Built here every night. **Where**: all of Israel, Gush Dan, Jerusalem, Haifa and the Krayot, Beersheba, or another area as `south,west,north,east`. The page warns when the machine has too little memory for all of Israel. | `planner` in `COMPOSE_PROFILES`, `PLANNER=on`; for an area `BOUNDS`, `BUILD_MEMORY=4`, `PLANNER_MEMORY=2` |
| ... and a second planner on the ministry's own times | A checkbox under Kav's planner. Its routes come after Kav's own. As much memory again. | `official` in `COMPOSE_PROFILES`, `OFFICIAL_URL=http://otp-official:8080/otp/gtfs/v1` |
| My own planner | Your OpenTripPlanner's address, or a planner you wrote (Kav's routes format). | `PLANNER=own`, `OTP_URL` or `PLAN_URL` |
| No planner | Phones plan trips elsewhere. | `PLANNER=off` |

*Arrival times:*

| Option | What it means | `.env` |
|---|---|---|
| Kav's arrival times | Kav's predictor with Kav's model, about 2.5 GB. | `predictor` in `COMPOSE_PROFILES` |
| Kav's predictor with my own model | The address of a model pack of yours. The page downloads it and checks it before saving. | `predictor` in `COMPOSE_PROFILES`, `MODELS_URL`, `MODELS_SHA256` |
| My own predictor, at an address | Yours, running anywhere, answering the predictions file. | `PREDICT_URL`, `MODELS_URL=` (empty) |
| My own predictor, on this machine | Yours, writing `data/eta/out/now.json`. | `MODELS_URL=` (empty) |
| The ministry's times | No predictor: the times the live source gives. | `ETA=` (empty), `MODELS_URL=` (empty) |

*Timetable, map and build steps* (folded away): a GTFS zip and an OpenStreetMap `.osm.pbf` of your own instead of the
ministry's and Israel's (`GTFS_URL`, `OSM_URL`), and a field for each [build step](#build-steps): empty for Kav's own,
`skip` to leave it out, or a command of your own (`STAGE_<NAME>`).

**5. Ready.** A summary of the choices and the memory they take. **Start** writes `.env` and starts everything. Live
buses come at once. Kav's planner builds first: about half an hour for all of Israel, about 15 minutes for one area.

Then the dashboard opens with a **Connect your phone** popup: a QR code for your own phone, and the same as a link to
copy (see [Connecting a phone](#connecting-a-phone)).

Whatever you choose, the page also writes `KAV_APP_KEY` the first time, a random key the server's own services sign
with, and keeps it from then on.

### The dashboard

A bar along the top with the sections, your account and a sign-out button.

- **Overview**: the server's own numbers (live vehicles, whether the planner answers, whose arrival times, the
  timetable's first day), each service and its state, whether phones can reach the server at its address (the page
  asks `https://<address>/live/v1/status` as the app would), the last nightly build and the next, **Rebuild now**,
  **Show my phone's code** (the Connect your phone popup again, for another phone of yours), and **Lock setup**. It
  refreshes by itself. When starting failed, it shows what Docker said, with **Try again**.
- **Parts**: every choice of the wizard on one page. **Save and apply** rewrites `.env` and starts what's new and
  stops what you turned off. A container is made again only when its settings or image changed, so a nightly build
  half way through goes on.
- **People**: who may use the server ([Who may use it](#who-may-use-it)).
- **Updates**: the release this runs, the latest one, and automatic updates ([Updates](#updates)).
- **Storage**: what each folder in `data/` takes, and the recorded passings to download
  ([Storage and backups](#storage-and-backups)).

The footer links to this guide, the repository and its issues, and shows the release.

## Connecting a phone

In Kav: **Settings › Live times and planners › My own › Scan the server's QR code**, and scan a code from the setup
page: your own from **Show my phone's code** on the Overview (or the popup after the first setup), or an invite from
People for someone else. Each popup also has the code as a `kav://` link with a **Copy** button, to send to a phone.

Or type the server's address there and press **Connect**. A server with accounts then asks for a username and password.
The app shows what the server has: "Connected · trip planner ready", "Connected, but its trip planner isn't ready yet",
"Connected, but no live buses yet", and so on.

What the QR code holds: `kav://server?address=<address>&invite=<code>` on a server with accounts, and only the address
on an open one.

When connected, the phone also takes the public half of this server's timetable signing key, and from then on takes
only timetables signed with it (see `data/signing` in [Storage and backups](#storage-and-backups)).

## Address and HTTPS

Kav talks to servers over HTTPS only. Three ways to give yours an HTTPS address:

**A domain, with the certificate made here.** Point a domain's DNS (an `A` record) at this machine's public address
and open ports 80 and 443 to it (forward them on your router at home). Choose "A domain for this machine". The `https`
service is Caddy, which gets a certificate from Let's Encrypt for `DOMAIN`, renews it by itself, and sends everything
to `live`. Its settings are `Caddyfile`, and its certificates are kept in `data/caddy`. Caddy logs no requests.

**A proxy or tunnel of your own.** Choose "My own proxy or tunnel" and give the address it serves. It must send all of
`https://<address>/` to `http://127.0.0.1:8080` on this machine, not only `/live/v1/`: trip links are at
`https://<address>/<code>` and `/api/share`. Port 8080 listens on this machine only; for a proxy on another machine, set
`LISTEN=0.0.0.0` in `.env` (and keep port 8080 closed to the internet with a firewall). For example:

- **Cloudflare Tunnel**, free and with no open ports: run `cloudflared` on this machine with a public hostname whose
  service is `http://localhost:8080`.
- **Nginx Proxy Manager, Nginx, Traefik or another Caddy**: a proxy host for your domain to `http://<this machine>:8080`.

**Tailscale.** Phones on your tailnet can reach the server through Tailscale's own HTTPS: `tailscale serve` pointed
at port 8080 gives it a `https://<machine>.<tailnet>.ts.net` address, which you give as "My own proxy or tunnel". Only phones on your tailnet reach it then, and trip links you share open only for them.

The address you give is where the server makes trip links (`PUBLIC_URL`, made from `DOMAIN` when you use one).
The dashboard's Overview says whether phones reach it.

## Who may use it

### Accounts or anyone

| `ACCESS` | Who gets in |
|---|---|
| `accounts` (the default) | People with an account on this server. Each phone signs in once and gets a token of its own, which signs every request after that. The password never stays on the phone. |
| `open` | Anyone who knows the address. Each address may make 120 requests a minute, and gets "too many requests" past that. |

The server's status (`/live/v1/status`) answers anyone either way, so the app can tell how to sign in. A refused
request gets "not found", as if the address weren't there.

With `open` behind a proxy, each phone still gets its own 120 a minute: when a request comes from this machine's own
network (as from the `https` service, Caddy), the server counts the address in its `X-Forwarded-For` header, which
Caddy sets. A proxy of your own on this machine's network must set that header too, or all its phones share one
120. The header is believed only from a private-network address, so nobody on the internet can claim someone else's.

### People

On the dashboard's **People**: everyone with an account, with a search. Each row has the person's name, their role
(Admin, Member or Turned off), how many phones they have signed in and when one was last seen.

- **Add someone** opens a popup: a username, and a password or none. Without a password they can sign in only with an
  invite, and their invite popup opens at once.
- The **QR code button** on a person's row opens their **invite**: a QR code and the same as a link to copy, for one
  phone to sign in with. It works **once, within 7 days**. Make a new one for each phone.
- The **...** menu on a person's row:
  - **Set a password...**: a new password, in a popup. Their phones stay signed in.
  - **Sign out** each of their phones by its model, one at a time.
  - **Make admin** / **Remove admin**: admins can sign in to the setup page and do everything you can.
  - **Turn off** / **Turn on**: a turned-off person can't sign in, and all their phones are signed out at once.
  - **Remove...**: after you confirm, the account, its phones and its invites are deleted. It can't be undone.

Your own row has no admin, turn off or remove items, so you can't lock yourself out. An admin who is turned off,
removed or made a plain user is signed out of the setup page at once.

On a server open to anyone, the QR button shows the code to connect a phone (the address alone), the accounts are kept
for if you switch back, and admins still sign in to the setup page with theirs.

### Passwords and limits

- A password needs at least 8 characters, at least 3 different ones, and can't be one of the most common ones.
- Five wrong passwords in a row pause that username for 15 minutes, in the app and on the setup page alike.
- Passwords are kept as scrypt hashes. A phone's token can be revoked alone; signing out on the phone revokes it too.
- Nothing about trips, searches or places is kept per person: only the accounts, their phones (the model name the
  phone gives, and when it was last seen), invites and the wrong-password pauses.

The accounts are in `data/history/accounts.sqlite3`, a SQLite file readable by its owner alone, which `live` and the
setup page share.

## The parts, and swapping each one

### Live buses

On the setup page: **Parts › Live buses**.

**The Ministry of Transport** (`MOT_KEY`). The server takes the ministry's national snapshot of every active trip every
15 seconds while phones are looking, and every 30 seconds otherwise, and asks it directly for the stops someone is
looking at and the routes someone follows. When the ministry's main address fails it tries its second one
(`MOT_URL_BACKUP`). The key works only from the IP address it was approved for.

**Another source** (`LIVE_URL`). Anything that answers SIRI StopMonitoring 2.8 in JSON the way the ministry does: the
contract, every field and the ready-made sources are in [server/LIVE.md](../LIVE.md). Kav ships two, in the repository
(the server's own folder has only its images, so run them from a clone, on this machine or another):

- `server/examples/gtfsrt_siri.py`: a GTFS-Realtime feed (vehicle positions and trip updates) turned into SIRI
  answers. It suits a feed that uses the ministry's ids.
- `server/examples/live_replay.py`: a recorded answer replayed with its times moved to now, for trying the server
  without a key.

Kav ships adapters only for open, permitted sources. Whatever you connect is your own responsibility. Check yours with
`python3 server/conformance.py live <its address>`.

**`BOUNDS`** keeps the live buses to an area (`south,west,north,east`). It's all of Israel by default
(`29,34,34,36`), and the area of Kav's planner when you choose one.

**None**: nothing is asked of anyone, and the app's live times come from elsewhere.

### The trip planner

On the setup page: **Parts › Trip planner**.

**Kav's planner.** OpenTripPlanner on the ministry's timetable and OpenStreetMap, built here every night (see
[The nightly build](#the-nightly-build)). `live` picks the routes worth showing from what it answers, adds the other
lines from the same stop, the timetable and taxi cards, and the predicted times.

**A second planner on the ministry's own times** (the checkbox). Kav's planner moves each bus to when it really reaches
each stop, from the recorded passings. The second planner plans on the ministry's timetable as published, and its
routes are added after Kav's own. It takes as much memory again while it answers. Turned on, the build service starts
again and builds both graphs at once (about three quarters of an hour), then both every night.

**My own planner.** On the setup page, give one of:

- **Your OpenTripPlanner** (`OTP_URL`, say `http://my-otp:8080/otp/gtfs/v1`), or anything answering its GTFS GraphQL
  API. Kav runs version 2.10. `live` still picks and orders the routes, so the app looks the same.
- **A planner you wrote** (`PLAN_URL`), in any language, answering Kav's routes format.

The contract for both, and an example planner to start from: [server/PLANNER.md](../PLANNER.md). With your own planner
(`PLANNER=own`) the nightly build makes no planner, only the app's timetable.

**No planner** (`PLANNER=off`): no routes from this server, and the app offers none from it. The nightly build makes
only the app's timetable.

### One area, for a smaller machine

Under Kav's planner, **Where**: all of Israel, Gush Dan, Jerusalem, Haifa and the Krayot, Beersheba, or another area as
`south,west,north,east`, which must be inside Israel (29.4 to 33.4 north, 34.2 to 35.9 east).

| Area | `BOUNDS` |
|---|---|
| Gush Dan | `31.95,34.70,32.20,34.95` |
| Jerusalem | `31.70,35.10,31.88,35.30` |
| Haifa and the Krayot | `32.73,34.95,32.90,35.15` |
| Beersheba | `31.18,34.70,31.32,34.88` |

The nightly build then cuts the timetable and the map to that box before it builds the planner (`server/otp/area.py`):

- the map keeps every street that reaches into the box, whole
- the timetable keeps the trips that stop in it at least twice, with only their stops in it
- the live buses keep to the same box, and trips from or to a place outside it are refused

Measured on Gush Dan, the busiest of them: the whole nightly build in about 15 minutes within 4 GB, and the planner
answering within 2 GB. The app's timetable stays all of Israel's.

### Arrival times

On the setup page: **Parts › Arrival times**. The predicted times replace the ministry's in the stop boards, the
planner's live updates and the app. If a predictor stops or breaks, the ministry's times take over within two minutes
and nothing else breaks.

- **Kav's arrival times**: `eta` with Kav's model. The nightly build fetches the model's files from Kav's release
  (`MODELS_URL`) and takes them only when they match `MODELS_SHA256`; `eta` waits until they're there and the timetable
  is built.
- **Kav's predictor with my own model**: a model pack of yours, from an `https://` address, 500 MB at most. The setup
  page downloads it, reads its manifest and refuses one Kav's predictor couldn't run, then saves its address and
  sha256. To train one from this server's own history: [server/eta/train](../eta/train/README.md).
- **My own predictor, at an address** (`PREDICT_URL`): asked every 15 seconds for the predictions file.
- **My own predictor, on this machine**: it writes `data/eta/out/now.json` and `status.json` (the folder belongs to
  user 1000).
- **The ministry's times** (`ETA=` empty): nothing predicted.

The predictions file, the model pack's format and how to check yours: [server/PREDICTOR.md](../PREDICTOR.md).

### Timetable and map

On the setup page: **Parts › Timetable, map and build steps**.

- `GTFS_URL`: any GTFS zip instead of the ministry's
  (`https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip`). The planner, the lines and the stops are
  built from it.
- `OSM_URL`: any OpenStreetMap `.osm.pbf` instead of Geofabrik's Israel
  (`https://download.geofabrik.de/asia/israel-and-palestine-latest.osm.pbf`).

Route ids and stop codes have to be numbers, as the ministry's are, for the live buses and the app to match them
([server/LIVE.md](../LIVE.md#ids)).

### Line alerts

On the setup page: **Parts › Line alerts**. Detours, delays and moved stops for the app's **Alerts from › My own**
(Settings › Live times and planners, under Detours and alerts). The server gives every phone Israel's whole list with
every notice in it (`/live/v1/alerts/<he or en>`, [server/API.md](../API.md#alerts)); the phone finds its own lines in it,
so this server never learns anyone's trip.

- **A GTFS-Realtime alerts feed** (`ALERTS_SOURCE=gtfsrt`, `ALERTS_URL`, and `ALERTS_HEADERS` as a JSON object if it
  wants any): any GTFS-Realtime `ServiceAlerts` feed whose route ids are the timetable's. The Ministry of Transport has
  one for all of Israel: it sends its address on request to `ptsupport@mot.gov.il`. It's asked every five minutes and
  never more than 12 times an hour, as the ministry's rules say. An alert on a route is listed under the route; a
  route's cancelled stops and the ministry's extension commands (stops added before or after another) come with the
  notice as its detour. An alert for a whole operator, a region or stops alone names no line, and isn't listed.
- **None** (`ALERTS_SOURCE=off`, the default): `/live/v1/alerts` answers 404, and the app says this server doesn't
  serve alerts.

**And in English** (the `translate` profile, `TRANSLATE_URL=http://translate:5000`): the notices are Hebrew, as the
operators write them, and `translate` (`server/translate.py`) turns each text into English once; `live` keeps the
translations in `data/history/translations.sqlite3`. It runs Helsinki-NLP's
[opus-mt-tc-big-he-en](https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-he-en) (OPUS-MT, CC-BY 4.0) with CTranslate2
on the CPU, about 0.3 GB, the model inside the image, so it needs no network. A line whose numbers would come back
changed keeps its Hebrew, so line numbers, stop codes and times are never the translator's guess. Until a text is
translated, the English list has its Hebrew. The kinds of alert ("Detour", "Delays") are Kav's own words. Street and
place names are sometimes translated word by word ("יוחנן הסנדלר" as "John the cobbler"). `TRANSLATE_URL` can point at
any service that answers LibreTranslate's `/translate` instead, with `TRANSLATE_KEY` if it wants a key.

## The nightly build

The `build` service builds on its first start and then every night at `REBUILD_AT` (`00:30`, Israel time, by default).
Each night it:

1. fetches the arrival model's files if they changed (`MODELS_URL`, `MODELS_SHA256`),
2. fetches the night's timetable, and the map once a week, and readies them for OpenTripPlanner,
3. makes the app's timetable for the seven days from today and signs it with this server's own key,
4. with Kav's planner: cuts to the area if there is one, mends some sidewalks, moves the buses to when they really
   reach each stop, handles the night the clocks change, and builds OpenTripPlanner's graph,
5. moves the new graph in. The planner starts again on it at once, and answers from the old graph until then,
6. builds the second planner's graph after it, when that planner is on.

| What's built | Time | Memory |
|---|---|---|
| All of Israel | about 30 minutes | up to 16 GB |
| One area | about 15 minutes | about 4 GB |
| All of Israel with the second planner | about 45 minutes the first time | the same, one after the other |
| Without a planner (the app's timetable only) | a few minutes | under 1 GB |

A first build that fails is tried again an hour later. A nightly build that fails leaves the planner answering from the
graph it had, and the Overview says why.

- **Rebuild now** on the Overview starts a build within a minute (it leaves `data/otp/rebuild-now` for `build` to find).
- **The log**: `docker compose logs -f build`. OpenTripPlanner's own log of the last build is
  `data/otp/build/build.log`, and the app timetable's is `data/otp/build/bundle.log`.
- **The last build's result**: `data/otp/last-build.json` (when, whether it worked, how many minutes, why it failed),
  and the next build's time in `data/otp/last-build.json.next`.

### Build steps

The nightly build's steps, in the order they run:

| Step | What it does | When it fails |
|---|---|---|
| `prepare` | Fetches the timetable (every night) and the map (weekly) and fixes the ministry's timetable where OpenTripPlanner reads it strictly (`server/otp/prepare.py`) | the build stops |
| `bundle` | The app's timetable for the seven days from today, signed with this server's key | the build goes on; phones keep the timetable they have |
| `area` | Cuts the timetable and the map to the area, when there is one (`server/otp/area.py`) | the build stops |
| `walks` | Joins some bus stops' sidewalks to the street they're on (`server/otp/walks.py`) | the build goes on without it |
| `predict` | Moves the buses to when they really reach each stop, from the recorded passings (`server/otp/predict.py`) | the build stops |
| `clocks` | The night the clocks change, puts the trips before it where the ministry runs them (`server/otp/clocks.py`) | the build stops |
| `graph` | OpenTripPlanner builds the planner, which takes over as soon as it's done | the build stops |

Without Kav's planner, only `prepare` and `bundle` run. When the build stops, the planner from before keeps answering.

Any step can be your own command, or left out. On the setup page: Parts, "Timetable, map and build steps". By hand,
`STAGE_<NAME>` in `.env`:

```sh
STAGE_PREDICT='python3 /stages/my_predict.py $BUILD'
STAGE_AREA='sh /stages/my_cut.sh'
STAGE_WALKS=skip
```

Put your scripts in `./stages` beside `docker-compose.yml`, which the build container sees as `/stages`, read-only.
A command runs there in a shell, as user 1000, with Python 3.12, pyosmium, OpenSSL and Java, and with:

| Variable | What it is |
|---|---|
| `BUILD` | The folder every step reads and writes (`/data/otp/build`): `israel-gtfs.zip`, `israel.osm.pbf`, and `graph.obj` after the last step |
| `OFFICIAL` | The ministry's own timetable as fetched (`mot-gtfs.zip`), before any step changed it |
| `HISTORY` | The recorded passings, read-only |
| `REALTIME` | The planner's live updates and the app's timetable |
| `MODELS` | The arrival model's files |
| `STAGES` | `/stages` |
| `AREA` | The area, `south,west,north,east`, or empty for all of Israel |

A command that exits with an error counts as the step failing. Put a value with a `$`, a space or a `#` in single
quotes in `.env`, as above (the setup page does that for you).

## Updates

A release is its `docker-compose.yml` (with `.env.example` and `Caddyfile`) and its five images, all tagged with the
release's tag. `KAV_VERSION` in `.env` says which one runs.

**On the setup page, Updates** shows the release this runs and the latest one on GitHub with what's new.
**Update to ...** appears only for a newer release (by version number; pre-releases aren't offered). It:

1. downloads the new release's three files and pulls all its images, while everything keeps running. If anything
   can't be downloaded, nothing changes and the page says so,
2. writes the new files, sets `KAV_VERSION`, and starts again what changed,
3. waits up to four minutes for the server to answer. If it doesn't, it goes back to the release it ran before, its
   files and its images, and the page shows what happened,
4. when healthy, starts the setup page on its new image and removes the old release's Kav images.

Live buses are back within a minute; the planner takes a few minutes more to load its graph. Sign in to the setup page
again afterwards: its sign-ins don't survive a restart.

**Update automatically** (off by default) checks once a day and does the same when there's a newer release. It doesn't
run while the setup page is locked.

**What an update keeps**: `.env` (all but `KAV_VERSION`), everything in `data/`, and `stages/`. What it replaces:
`docker-compose.yml`, `.env.example` and `Caddyfile`. Changes of your own to the services go in a
`docker-compose.override.yml` beside them, which Compose reads by itself and updates leave alone.

**Staying on a release**: leave automatic updates off and don't press Update. `KAV_VERSION` keeps it there.

**By hand**, to any release, newer or older (the page only goes forward):

```sh
cd ~/kav-server
v=<release>
for f in docker-compose.yml .env.example Caddyfile; do
  curl -fsSLO https://raw.githubusercontent.com/ImNoammm/kav/$v/server/selfhost/$f
done
sed -i "s/^KAV_VERSION=.*/KAV_VERSION=$v/" .env
docker compose pull
docker compose up -d
docker compose up -d setup    # only if you use the setup page
```

Going back to an older release this way works as long as the older release reads the files in `data/` the newer one
left. The release notes say when one doesn't.

## Storage and backups

Everything the server keeps is in `data/` beside `docker-compose.yml`, plus `.env`.

| Folder or file | What it holds | Can it be deleted? |
|---|---|---|
| `data/history/` | The recorded passings (one file a day, kept 70 days), `accounts.sqlite3` (the accounts and phones), `shared-trips.sqlite3` (trip links) | **No.** The passings make Kav's planner and predictor better; the accounts and trip links can't be made again |
| `data/history-backfill/` | Older passings you added by hand (one file a day, as `live` writes them), for the predictor's six weeks of medians | Yes |
| `data/signing/` | `bundle.key`, the key the app's timetable is signed with | **No.** Phones that connected keep its public half and take no timetable signed with another key until they connect again |
| `data/caddy/` | The HTTPS certificates | Yes, a new one is fetched (Let's Encrypt limits how often) |
| `data/otp/` | The planner's timetable, map and built graphs, the last build's result | Yes, with the services stopped; the next build makes it again |
| `data/realtime/` | The planner's live updates, the app's timetable as served, what the build leaves for `live` | Yes, with the services stopped; the next build makes it again |
| `data/models/` | The arrival model's files | Yes; fetched again at the next build |
| `data/eta/` | The predicted times now (`out/`) and the predictor's spill space (`spill/`) | Yes |
| `data/setup-state.json` | The setup page's settings as chosen, from which it writes `.env` | Only to start the setup over (the wizard comes back) |
| `data/setup-code` | The one-time code, until the owner's account exists | It's made again when needed |

The dashboard's **Storage** shows what each folder takes.

**Backing up.** `.env` and `data/` hold everything, with `docker-compose.yml` beside them. The rest of `data/` comes
back by itself, so a small backup is `.env`, `data/history`, `data/signing`, `data/setup-state.json` and
`data/caddy`. Copy the SQLite files with the services stopped, or with `sqlite3 <file> ".backup <copy>"`, to be sure of
a whole copy. The files belong to user 1000 (and `data/caddy` to root), readable by their owner alone, so back up as
root:

```sh
cd ~/kav-server
docker compose stop
sudo tar czf ~/kav-backup.tar.gz .env docker-compose.yml data/history data/signing data/setup-state.json data/caddy
docker compose start
```

**Restoring, or moving to another machine.** Install Docker on the new machine, unpack the backup in the folder
(keeping its owners: `sudo tar xzf` does), download the release's three files as in [By hand](#by-hand) if they aren't
in the backup, and run `docker compose up -d` (and `docker compose up -d setup` if you use the setup page). The
planner builds again first. Then:

- **The ministry's key** works only from the IP address it was approved for. A new address needs a new approval.
- **The address**: point the domain at the new machine, or move the tunnel.

**Recorded history for training.** **Storage › Download the recorded passings** streams every day file in
`data/history` as `kav-history.tar` (the passings only, not the accounts or trip links), which unpacks to a `history`
folder. What to do with it: [server/eta/train](../eta/train/README.md).

## Every setting in .env

The setup page writes `.env`. Each time it saves, it writes the lines it manages from its own settings
(`data/setup-state.json`), so a managed line changed by hand is put back the next time you save on the page. Lines it
doesn't manage are kept as you set them. `.env.example` explains every line too. After editing `.env` by hand, run
`docker compose up -d`.

Values with a `$`, a space or a `#` go in single quotes, or Compose changes them as it reads the file.

"Read by" says which service takes the line (through `docker-compose.yml`), or Compose itself.

**Managed by the setup page** (`MANAGED` in `setup/server.py`). When the page saves, it writes these from its own
choices. Without the page, set them yourself:

| Variable | What it does | Default | Read by |
|---|---|---|---|
| `COMPOSE_PROFILES` | The parts that run, comma-separated: `planner`, `official`, `predictor`, `alerts`, `translate`, `https` (and `setup`, to start the page on every `up`; the page itself never writes it) | none: `live` and `build` only (`.env.example` has `planner,predictor`) | Compose |
| `MOT_KEY` | The ministry's key, or your live source's (sent as `Key=`) | none: no live buses | `live` |
| `LIVE_URL` | Another live source instead of the ministry | none | `live` |
| `BOUNDS` | The area, `south,west,north,east`: the live buses keep to it, and Kav's planner is built for it | all of Israel (`29,34,34,36` for `live`) | `live`, `build` (as `AREA`) |
| `ACCESS` | `accounts`, `open` or `key`. `key` is for Kav's own server: every phone signs with `KAV_APP_KEY`, the key official builds of the app carry. The setup page doesn't offer it, and puts back its own choice when you save | `accounts` | `live` |
| `DOMAIN` | Your domain, for the `https` profile's certificate | none | `https`, and `live` through `PUBLIC_URL` |
| `PUBLIC_URL` | The `https://` address phones use; trip links are made on it | `https://<DOMAIN>` | `live` |
| `PLANNER` | `on` (Kav's), `own` or `off` | `on` | `live`, `build` |
| `OTP_URL` | Your own OpenTripPlanner | `http://otp:8080/otp/gtfs/v1`, Kav's | `live` |
| `PLAN_URL` | A planner you wrote, asked instead of any OpenTripPlanner | none | `live` |
| `OFFICIAL_URL` | The second planner, when it's on: `http://otp-official:8080/otp/gtfs/v1` | none: not asked, not built | `live`, `build` |
| `PREDICT_URL` | A predictor of your own at an address | none | `live` |
| `ETA` | Where the predicted times are read from; empty for none (the ministry's own times) | `/data/eta/out/now.json` | `live` |
| `MODELS_URL`, `MODELS_SHA256` | The model pack Kav's predictor runs, and its sha256; `MODELS_URL` empty for none | Kav's own (the `eta-models-2026-10-27` release) | `build` (fetches it), `eta` |
| `GTFS_URL` | The timetable | the ministry's | `live`, `build` |
| `OSM_URL` | The map | Geofabrik's Israel and Palestine | `build` |
| `BUILD_MEMORY` | GB for the nightly build's container (Java gets one less) | `15`; the page writes `4` for one area | `build` (its `mem_limit` too) |
| `PLANNER_MEMORY` | GB for each planner while it answers | `5`; the page writes `2` for one area | Compose: `otp`, `otp-official` `mem_limit` |
| `STAGE_PREPARE`, `STAGE_AREA`, `STAGE_WALKS`, `STAGE_PREDICT`, `STAGE_CLOCKS`, `STAGE_GRAPH`, `STAGE_BUNDLE` | A [build step](#build-steps) of your own, or `skip` | Kav's own | `build` |
| `ALERTS_SOURCE` | [Line alerts](#line-alerts) from `gtfsrt`, or `off` | `off` | `live` |
| `ALERTS_URL`, `ALERTS_HEADERS` | The GTFS-Realtime alerts feed, and the headers it wants as a JSON object | none | `live` |
| `TRANSLATE_URL` | The translator for the alerts in English: `http://translate:5000` with the `translate` profile | none: Hebrew only | `live` |

Because these are managed, once you use the page, a value you set by hand lasts only until the next save there. The
page names any of them that differ from its own choices, on the Overview and on Parts ("`.env` was changed by hand"),
before **Save** replaces them. A page you start later on a server set up by hand doesn't read them into its choices
([more](#setting-it-up-with-env-alone)).

**Kept as you set them** (the page writes none of these, except making `KAV_APP_KEY` once and `KAV_VERSION` on
updates):

| Variable | What it does | Default | Read by |
|---|---|---|---|
| `KAV_VERSION` | The release that runs: the tag of every Kav image. install.sh and updates set it | `latest` if missing | Compose; the setup page's Updates |
| `KAV_REPO` | Where releases come from, `you/kav` | `ImNoammm/kav` | the setup page's Updates (install.sh writes it) |
| `KAV_IMAGES` | Where the images come from, `ghcr.io/you` | `ghcr.io/imnoammm` | Compose |
| `KAV_APP_KEY` | The server's own key, which its services sign with (and phones, with `ACCESS=key`). Required unless `ACCESS=open`: `live` doesn't start without it | made by install.sh and the setup page | `live`, `eta` |
| `LISTEN`, `PORT` | Where `live` listens on this machine | `127.0.0.1`, `8080` | Compose: `live`'s port |
| `SETUP_LISTEN`, `SETUP_PORT` | Where the setup page listens | `0.0.0.0`, `8090` | Compose: `setup`'s port |
| `REBUILD_AT` | When the nightly build runs, Israel time | `00:30` | `build` |
| `TRANSLATE_KEY` | The API key of a translator elsewhere that wants one | none | `live` |
| `MOT_URL`, `MOT_URL_BACKUP` | The ministry's two addresses, the second tried when the first fails; `MOT_URL_BACKUP=` (empty) for none | the ministry's | `live` |

After changing `.env` by hand, run `docker compose up -d`: Compose makes again only the containers whose settings
changed.

## Running it day to day

Everything below runs in the folder, `cd ~/kav-server` first.

**What's running:** `docker compose ps`. Each service restarts by itself if it stops, and everything starts again with
Docker after a reboot.

**Logs:** `docker compose logs -f <service>`, for `live`, `otp`, `otp-official`, `build`, `eta`, `https` or `setup`.
Each service keeps 2 MB of its log. `live` doesn't log requests.

**Health:** `live` has a health check (its status, every minute), shown by `docker compose ps`. From the machine:
`curl -s http://127.0.0.1:8080/live/v1/status`, which says whether the live snapshot is fresh, how many vehicles it has,
whether the planner answers, whether the predicted times are fresh and the timetable's first day.

**Stopping and starting:** `docker compose stop` and `docker compose start`, or `docker compose up -d` after a change.

**Starting the setup over:** stop the setup page, delete `data/setup-state.json`, start it, and the wizard comes back.
`.env` lines the page doesn't manage, the accounts and everything else in `data/` stay.

### Locking the setup page

The setup page holds Docker's socket, so whoever controls it can start anything on this machine. When you're done
setting up, **Lock setup** on the Overview stops the setup page's own container (by its id, the way `docker stop`
would). Then:

- **Everything else keeps running**: live buses, the planner, the predictor, the nightly build, HTTPS and the phones'
  sign-ins. Nothing about the server changes.
- **Automatic updates stop**, because the setup page is what checks for them.
- **It stays stopped through reboots and Docker restarts**: Docker doesn't start a container that was stopped by hand,
  even with `restart: unless-stopped`.
- **Port 8090 answers nothing** until you open it again.

To open it again, in the install folder:

```sh
cd ~/kav-server
docker compose start setup
```

`docker compose up -d setup` starts it too, also after `docker compose down` removed it. A plain `docker compose up -d`
doesn't: the setup service is in its own profile. Sign in again afterwards: the page's sign-ins don't survive its
restart.

### A lost admin password

**Another admin can set it**: on People, the **More** menu beside that person, **Set a password...**. Their phones
stay signed in.

**Without another admin**, set it on the machine, in the install folder. It asks for the new password twice:

```sh
cd ~/kav-server
docker compose exec -it live python3 accounts.py password <username>
```

This runs in the `live` container, so it works with the setup page locked too. The new password follows the same
rules as on the page (at least 8 characters, not a common one); a weak one is refused and nothing changes. It also
lifts a wrong-password pause on that username, and phones signed in as that person stay signed in.
`docker compose exec -it live python3 accounts.py list` shows who is an admin, and `admin <username> on` makes
someone one.

**If no admin is left at all** (every admin turned off or removed), the setup page asks for a one-time code again, as
on the first visit, and lets you make a new owner's account:

```sh
cd ~/kav-server
docker compose restart setup
cat data/setup-code
```

Then open `http://<this machine>:8090/?code=<the code>`. The other accounts, their phones and everything else stay as
they were.

## When something's wrong

**The setup page says "Only from nearby".** It answers this machine's own network and Tailscale only. Tunnel to it
(see [Opening the setup page](#opening-the-setup-page)). Opening it at the machine's public address won't work, by
design.

**The setup page asks for the address install.sh showed.** Until the owner's account exists, it opens only with the
one-time code: `cat data/setup-code`.

**Nothing at port 8090.** `docker compose ps setup`; if it's not running (locked, or removed by `docker compose down`),
`docker compose up -d setup`.
If the port is taken, set `SETUP_PORT=8091` in `.env` and `docker compose up -d setup`.

**No live buses.** **Test** on Parts says what the source answered. An error or no answer from the ministry usually
means the key is wrong or the request comes from an address the key wasn't approved for: check this machine's public
address (`curl -s https://ifconfig.me`) against the one you applied with. `docker compose logs live` shows what it
got. The Overview's "Live vehicles" says Off when no live source is set.

**The planner doesn't answer yet.** The first build takes about half an hour for all of Israel and about 15 minutes
for one area, and the planner then takes a few minutes to load. `docker compose logs -f build` shows where it is, and
`docker compose logs otp` says "waiting for kav-build's first planner" until then.

**The build failed.** The Overview shows the last build's error. The whole story: `docker compose logs build`, and
OpenTripPlanner's own log in `data/otp/build/build.log`.
- *Out of memory* (`OutOfMemoryError`, or the container killed with exit code 137): the machine has too little memory
  for all of Israel. Choose one area, run fewer parts, or add memory or swap.
- *A download failed*: the ministry's or Geofabrik's server didn't answer. A first build tries again in an hour, and a
  nightly one the next night (or press Rebuild now).

**Starting failed.** The Overview shows what Docker said, with **Try again**. A port already in use (`address already
in use`) is the most common: something else listens on 80, 443, 8080 or 8090. Stop it, or move Kav: `PORT` for 8080,
`SETUP_PORT` for 8090. Ports 80 and 443 have to be free for the `https` profile; behind a proxy of your own, use "My
own proxy or tunnel" instead.

**The certificate doesn't come.** `docker compose logs https`. The domain must point at this machine's public
address, and ports 80 and 443 must reach it from the internet (forwarded on the router, open in the firewall). A
domain on Cloudflare can skip all of that: use Cloudflare Tunnel with "My own proxy or tunnel", and no certificate is
needed here.

**Phones can't connect.** The Overview says whether the server is reachable at its address. Check that the address
opens `https://<address>/live/v1/status` from a phone's browser (outside your home Wi-Fi, too), that the proxy sends
everything to port 8080, and, on a server with accounts, that the person has an account that isn't turned off. An
invite that says it expired was used already or is more than 7 days old: make a new one.

**A phone was signed out.** Its token was revoked (signed out on People, the person turned off or removed). Sign in
again on the phone.

**An update went back.** Updates shows "didn't come up healthy, so it went back" and, under "What happened", what
Docker said. The server is on the release it ran before. Check `docker compose logs live`, and report it with that
log on [the issues](https://github.com/ImNoammm/kav/issues). "Couldn't download" means nothing changed: try again
later.

**Anything else:** `docker compose logs <service>` for each service in turn, and the Overview.

## Security

**Who can reach what:**

- **Phones** reach `live` only, through the HTTPS address. With accounts, every request but the status is signed with
  the phone's own token, and an unsigned one gets "not found". Trip links and their pages answer anyone with the link.
- **The planner and the predictor** sit on an internal Docker network and reach nothing but `live`. Neither has a
  published port.
- **The setup page** answers only this machine, its local network and Tailscale.

**The setup page's protections:**

- Requests from any other address are refused before anything else is read.
- Only admins can sign in. Before the first account exists, only someone with the one-time code from `data/setup-code`
  can make one.
- A sign-in lasts 12 hours, in a cookie scripts can't read (`HttpOnly`, `SameSite=Strict`). It ends at sign-out, when
  the setup page restarts, or when that person stops being an admin.
- Every change carries a token of the sign-in (CSRF), and a change sent from another site's page (another `Origin`) is
  refused. Pages can't be shown inside another site's frame.
- Wrong passwords pause the username, as in the app.
- The page loads nothing from the internet. Its two scripts, htmx (in-place page updates) and the QR code drawer,
  are served by the page itself, and its content security policy allows nothing else.

**The Docker socket.** The setup page mounts `/var/run/docker.sock` to start and update the other services. Whoever
controls the setup page controls this machine, as with any app that mounts the socket. So: use a strong admin
password, keep port 8090 closed to the internet (the page refuses outside addresses anyway), make only people you
trust admins, and [lock setup](#locking-the-setup-page) when you're done. Nothing else on the server needs the socket.

**Secrets.** `.env` holds the ministry's key and `KAV_APP_KEY`, and `data/setup-state.json` holds the same settings.
Both are written readable by their owner alone; keep it that way and keep them out of any public place. The accounts
file is readable by its owner alone and keeps scrypt hashes only. `data/signing/bundle.key` signs the timetable your
phones trust.

**Privacy.** `live` and Caddy don't log requests. The accounts keep no trips, searches or places. Trip links are kept
until they expire (3, 7 or 30 days, as the sharer chose).

**What else to do:** keep the machine and Docker updated, firewall everything but 80 and 443 (or nothing at all, with a
tunnel), and, on a server open to anyone, remember that anyone with the address can use it.

## For developers

### Building the images from source

In a checkout of the repository, in `server/selfhost`:

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile '*' build
docker compose up -d setup
```

`docker-compose.dev.yml` adds a `build:` to each Kav service, and the images get the names `docker-compose.yml` runs
(`ghcr.io/imnoammm/kav-<part>:latest`), so the setup page and a plain `docker compose up -d` start these. Leave
`KAV_VERSION` out of `.env` there: the setup page then offers no updates, which would pull the published images over
yours. `data/` and `.env` in `server/selfhost` are ignored by git.

Each image can be built alone, from the repository's root:

```sh
docker build -f server/selfhost/live/Dockerfile -t kav-live .
docker build -f server/selfhost/otp/Dockerfile -t kav-otp .
docker build -f server/selfhost/build/Dockerfile -t kav-build .
docker build -t kav-eta server/eta
docker build -f server/selfhost/setup/Dockerfile -t kav-setup .
```

The setup page is `setup/server.py` (the setup service: `.env`, Docker, updates, storage) and `setup/pages.py` (its
pages), with Python's own library only; `setup/static/` holds htmx and the QR code drawer (Kazuhiko Arase's
qrcode-generator). To see it without Docker, with a made-up server: `python3 server/selfhost/setup/preview.py`, then
open `http://localhost:8091`.

### Releasing

`.github/workflows/selfhost.yml` runs on every tag that starts with `v`. For each of the five parts it builds the image
from that commit for `linux/amd64` and `linux/arm64` and pushes it to `ghcr.io/imnoammm/kav-<part>:<tag>`. A tag with no
`-` in it (`v4.0`, not `v4.0-rc1`) moves `latest` too.

To release:

1. Push the tag: `git tag v4.0 && git push origin v4.0`.
2. Wait for the "Server images" workflow to finish, all five parts green.
3. The first time, make each package public on GitHub (the package's settings, visibility), or installs can't pull
   them without signing in to the registry.
4. Publish the GitHub release for that tag, not as a pre-release. install.sh and the setup page's updates take the
   latest release from GitHub's API, and only once it's published do servers see it.

A fork publishes to its own registry by changing `images:` in that workflow to `ghcr.io/<you>/kav-${{ matrix.part }}`,
and installs with `KAV_REPO=<you>/kav KAV_IMAGES=ghcr.io/<you>`.

### Tests

Python's own library only, except the predictor's, which needs `polars`, `numpy` and `lightgbm`
(`server/eta/Dockerfile` has the versions). From the repository's root:

```sh
python3 server/selfhost/setup/test_setup.py     # the setup page, over HTTP, Docker stubbed
python3 server/selfhost/build/test_build.py     # the nightly build's steps
python3 server/test_accounts.py                  # accounts, invites, pauses
python3 server/test_live.py                      # kav-live
python3 server/test_shares.py                    # trip links
python3 server/test_otp.py                       # the planner's build steps
python3 server/eta/test_eta.py                   # the predictor
```

### Contracts

Each part a server can swap has a contract, and `server/conformance.py` checks an implementation against it:

| Contract | What it covers | Check |
|---|---|---|
| [server/API.md](../API.md) | Everything the app asks a Kav server: status, live buses, stop boards, plan, the timetable, sign-in, trip links | |
| [server/PLANNER.md](../PLANNER.md) | A planner of your own: OpenTripPlanner's API or Kav's `PLAN_URL` | `python3 server/conformance.py plan <address>` |
| [server/PREDICTOR.md](../PREDICTOR.md) | The predictions file and the model pack | `python3 server/conformance.py predictions <file or address>` |
| [server/LIVE.md](../LIVE.md) | A live source answering SIRI StopMonitoring | `python3 server/conformance.py live <address>` |

`conformance.py` exits with 0 when everything conforms, and lists each problem otherwise. Examples to start from are
in `server/examples/`: `planner.py`, `gtfsrt_siri.py` and `live_replay.py`.
