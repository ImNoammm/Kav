---
title: For developers
description: Israel's stops, lines, roads and fares as free files to download.
---

<script setup lang="ts">
import { onMounted, ref } from 'vue'

type Manifest = {
  built?: unknown
  files?: { name?: string; sources?: unknown }[]
  sources?: Record<string, { published?: unknown }>
}
const manifest = ref<Manifest>({})
const lastBuilt = ref('')
const timetablePublished = ref('')

function date(value: unknown, time = false) {
  if (typeof value !== 'string') return ''
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(Z|[+-]\d{2}:\d{2})?$/.exec(value)
  if (!match || (time && !match[7])) return ''
  const parsed = new Date(value.slice(0, 19) + 'Z')
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 19) !== value.slice(0, 19)) return ''
  const instant = time ? new Date(value) : parsed
  if (!Number.isFinite(instant.getTime())) return ''
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC',
    ...(time ? { hour: '2-digit', minute: '2-digit', second: '2-digit', timeZoneName: 'short' } as const : {}),
  }).format(instant)
}

function freshness(name: string) {
  const fallback = 'Every night'
  const entry = manifest.value.files?.find((file) => file?.name === name)
  if (!Array.isArray(entry?.sources)) return fallback
  // Track data needs the extract's own date, not the timetable's.
  const source = entry.sources.includes('osm') ? 'osm' : entry.sources.includes('mot-busy') ? 'mot-busy' : entry.sources.includes('mot-fleet') ? 'mot-fleet' : entry.sources.includes('mot-gtfs') ? 'mot-gtfs' : ''
  return date(manifest.value.sources?.[source]?.published) || fallback
}

onMounted(async () => {
  try {
    const response = await fetch('/data/v1/manifest.json')
    if (!response.ok) return
    const data = await response.json()
    if (!data || !Array.isArray(data.files)) return
    manifest.value = data
    lastBuilt.value = date(data.built, true)
    timetablePublished.value = date(data.sources?.['mot-gtfs']?.published)
  } catch {}
})
</script>

# For developers

Kav reads Israel's public transport data every night and puts right what it can. You can build on that too:

- **Everything that can be a file is a free download**: stops, lines, their roads, operators, the official fares, how busy lines usually are and bus types.
  You can fetch these files straight from the addresses below, from your app or your server.
  No API key or account required.
- **Live data** (arrivals at stops, bus positions, alerts) isn't open as an API: see [Live data](#live-data).

## The files

From the Ministry of Transport's timetable, Tel Aviv's weekend lines and OpenStreetMap, at:

- `https://kavapp.link/data/v1/<file>`, on Kav's site
- `https://github.com/ImNoammm/kav/releases/download/data/manifest.json` on GitHub, which names the night: each of its
  files is beside it as `<night>.<file>`, for example `20261113T235802Z.stops.csv.gz`

<p>
  Every file is rebuilt every night.
  <template v-if="timetablePublished">Files built from the Ministry of Transport's timetable come from the one published {{ timetablePublished }}.</template>
  <span v-if="lastBuilt">{{ ' ' }}Last built: {{ lastBuilt }}</span>
</p>

| File | What's in it | Size | Licence | Data from |
| --- | --- | --- | --- | --- |
| [`stops.csv.gz`](https://kavapp.link/data/v1/stops.csv.gz) | Every stop and station: code, names in Hebrew, English and Arabic, city, platform, position, the routes that call there | 2.1 MB | CC BY 4.0 | {{ freshness('stops.csv.gz') }} |
| [`routes.csv.gz`](https://kavapp.link/data/v1/routes.csv.gz) | Every route (a line one way): operator, number, where it goes from and to, direction, kind, colour | 0.4 MB | CC BY 4.0, `color` GPL-3.0 | {{ freshness('routes.csv.gz') }} |
| [`operators.csv.gz`](https://kavapp.link/data/v1/operators.csv.gz) | Every operator, with its English name and colour | 1 KB | CC BY 4.0, `color` GPL-3.0 | {{ freshness('operators.csv.gz') }} |
| [`roads.geojson.gz`](https://kavapp.link/data/v1/roads.geojson.gz) | The road each bus, light rail and cable car route takes | 28 MB | CC BY 4.0 | {{ freshness('roads.geojson.gz') }} |
| [`rail-roads.geojson.gz`](https://kavapp.link/data/v1/rail-roads.geojson.gz) | The trains' roads, along OpenStreetMap's tracks | 0.4 MB | ODbL 1.0 | {{ freshness('rail-roads.geojson.gz') }} |
| [`fares.csv.gz`](https://kavapp.link/data/v1/fares.csv.gz) | The official fare between every pair of stops the ministry prices | 2.9 MB | CC BY 4.0 | {{ freshness('fares.csv.gz') }} |
| [`fares.json.gz`](https://kavapp.link/data/v1/fares.json.gz) | What each fare is for, whole-line fares, day passes, and the rules | 13 KB | CC BY 4.0 | {{ freshness('fares.json.gz') }} |
| [`busy.csv.gz`](https://kavapp.link/data/v1/busy.csv.gz) | Kav's usual busyness level per route, direction and hour of the week | Varies | Data.gov.il terms | {{ freshness('busy.csv.gz') }} |
| [`fleet.csv.gz`](https://kavapp.link/data/v1/fleet.csv.gz) | Bus size, seats, year and power, keyed by number plate | Varies | Data.gov.il terms | {{ freshness('fleet.csv.gz') }} |
| [`manifest.json`](https://kavapp.link/data/v1/manifest.json) | When the files were built, from what, and each one's size and sha256 | 5 KB | | {{ freshness('manifest.json') }} |

A file's sha256 in the manifest changes only when its content does, so the manifest tells you whether there is anything new to download.

### Staying up to date

1. Fetch [`manifest.json`](https://kavapp.link/data/v1/manifest.json) at most once a day.
2. Compare each file's sha256 with the one you have.
3. Download only the files that changed.
4. For a matching set, use the night's own addresses: `https://kavapp.link/data/v1/<folder>/<file>`.
   The manifest's `folder` names the night; the last two nights stay available.
5. The same manifest is mirrored on [GitHub](https://github.com/ImNoammm/kav/releases/download/data/manifest.json).

If your app has many users, fetch from your own server and pass the files on, rather than fetching from every user's phone.
For heavy use, take the same files from the [GitHub copy](https://github.com/ImNoammm/kav/releases/download/data/manifest.json).

Only `rail-roads.geojson.gz` holds OpenStreetMap data. The stops are where the ministry's timetable puts them (Tel
Aviv's, for its weekend lines' own stops).

Every data file is gzipped. CSV files are UTF-8 with a header row; read columns by name, not by their position.
GeoJSON follows RFC 7946, so coordinates are longitude first. Codes and ids are the ministry's own, the same ones its
live feed and [Kav's API](/docs/api) use.

The files are under `/data/v1/`, and the manifest's `format` and `version` (`kav-open-data`, `1`) say which layout they
are in.

### manifest.json

```json
{"format": "kav-open-data", "version": 1, "built": "2026-11-13T23:58:02+00:00", "folder": "20261113T235802Z",
 "files": [{"name": "stops.csv.gz", "size": 2057183, "sha256": "5b74b8b1...", "rows": 35422, "licence": "CC-BY-4.0",
            "credit": "Israel Ministry of Transport; Tel Aviv-Yafo Municipality; Kav (kavapp.link)",
            "sources": ["mot-gtfs", "tel-aviv-weekend", "kav"], "changed": "English and Arabic names from ..."}],
 "sources": {"mot-gtfs": {"name": "Israel Ministry of Transport, GTFS timetable", "sha256": "3477...",
                          "published": "2026-11-12T19:44:16", "from": "20261113", "to": "20261213"},
             "osm": {"name": "OpenStreetMap, railway tracks (Geofabrik's israel-and-palestine extract)", "sha256": "..."}},
 "licences": {"CC-BY-4.0": {"name": "Creative Commons Attribution 4.0", "url": "https://creativecommons.org/licenses/by/4.0/"}}}
```

- `files`: each file with its size, sha256, rows (features for GeoJSON), licence, the credit that licence asks for
  (its own sources'), where it comes from and what Kav changed.
- `sources`: the ministry's timetable as it was that night (`published` is the time inside its zip, `from` and `to` the
  days its calendars cover), the OpenStreetMap extract the trains were matched on (its sha256), and the others used.
- `folder`: the night. Its files are also at `https://kavapp.link/data/v1/<folder>/<file>`, the night's own address,
  cached for a year; the plain addresses give the latest night, its files cached for an hour and its `manifest.json`
  and signature for five minutes. The last two nights are kept, on Kav's site and on GitHub.

`manifest.json.sig` beside it is the manifest's ECDSA P-256 SHA-256 signature, in base64. To check a download:

```sh
curl -sO https://kavapp.link/data/v1/manifest.json -sO https://kavapp.link/data/v1/manifest.json.sig
curl -fsS https://kavapp.link/data/public-key.b64 | base64 -d | openssl pkey -pubin -inform DER -out kav-data.pem
base64 -d manifest.json.sig > manifest.sig
openssl dgst -sha256 -verify kav-data.pem -signature manifest.sig manifest.json
jq -r '.files[] | "\(.sha256)  \(.name)"' manifest.json | sha256sum --check
```

On GitHub the signature is `<folder>.manifest.json.sig`, beside `<folder>.manifest.json`, the same manifest under
the night's own name. While `manifest.json` is being switched to a new night it may be missing for a moment: ask again.

The data key's [public half](https://kavapp.link/data/public-key.b64) is also installed with the pull job. Pin a copy from a trusted installation before verifying future downloads.

### How a night is checked

Before a night is signed and published, the gate (`tools/open_data.py check`) checks that:

- the manifest has every field of its type, in its range and of its shape, by the same list of rules Kav's site
  applies before it takes a night, and names the nine files;
- each file matches its size and sha256, has the columns this version writes, and its measured row count matches
  the manifest and has at least about seven tenths of the rows of 13 November 2026's (15 November for busy and fleet);
- both GeoJSON files parse as FeatureCollections of LineStrings with finite coordinate pairs;
- no file has under 90% of last night's rows or bytes;
- every route, operator, road and fare a file names is in the others, the roads cover 95% of the ministry's lines and
  of its trains, and 75% of the stops have a line calling at them;
- every stop is in Israel or the West Bank: within 2 km of [Natural Earth](https://www.naturalearthdata.com)'s
  outline, whose lines are good to about a kilometre and a half;
- against last night's stops, none moved more than 1 km, and no more than 0.1% of them more than 50 m. The
  ministry's own feed, night to night from 16 July to 13 November 2026, moved one stop 751 m at most, and never more
  than 0.026% of the stops over 50 m in a night.

Without last night's files to compare with, nothing is published but the very first night. A real drop or move goes
through only when a person runs it by hand.

The gate catches Kav's own pipeline going wrong, or a broken feed. It doesn't check that the ministry's data is right:
the files keep the ministry's own stop positions, fares and timetable, so a stop it puts in the wrong place within
those bounds, a wrong fare or a wrong trip passes, as does any change smaller than the bounds.

### stops.csv.gz

One row for each of the ministry's stops and stations, and the few stops of Tel Aviv's weekend lines that aren't the
ministry's.

| Column | What it is |
| --- | --- |
| `stop_id` | The ministry's GTFS `stop_id`. The weekend lines' own stops are 9,000,000 and up |
| `code` | The number on the stop's sign, which the live feed and Kav's API use. A bus station's platforms share their station's |
| `name_he`, `name_en`, `name_ar` | The stop's name, and the ministry's English and Arabic for it (empty where it has none) |
| `street_he`, `city_he`, `city_en` | Taken out of the ministry's `stop_desc` |
| `platform` | A bus station platform's number |
| `lat`, `lon` | Where the ministry's timetable puts the stop (Tel Aviv's, for its weekend lines' own stops) |
| `location_type`, `parent_station` | As GTFS has them: `1` is a station |
| `route_ids` | Every route that calls at the stop, space separated (`routes.csv.gz`) |

```csv
stop_id,code,name_he,name_en,name_ar,street_he,city_he,city_en,platform,lat,lon,location_type,parent_station,route_ids
1,38831,בי''ס בר לב/בן יהודה,Bar Lev School/Ben Yehuda,مدرسة بار ليڤ/بن يهودا,בן יהודה 74,כפר סבא,Kfar Saba,,32.183985,34.917554,0,,26993
```

### routes.csv.gz

One row for each route: a line, one way, one variant, as the ministry's timetable has them, and Tel Aviv's weekend lines
(9,000,000 and up).

| Column | What it is |
| --- | --- |
| `route_id` | The ministry's GTFS `route_id`, the route in its live feed and in Kav's API |
| `agency_id`, `operator_he` | Who runs it (`operators.csv.gz`) |
| `number` | The line's number on the bus. Trains have none |
| `name_he` | The ministry's own name for the route |
| `from_he`, `from_city_he`, `to_he`, `to_city_he` | Where it starts and ends, read out of that name as Kav shows it |
| `from_en`, `from_city_en`, `to_en`, `to_city_en` | The same in the ministry's English, where it has it |
| `headsign_he` | The destination its trips most often show |
| `mot_line_id`, `direction`, `variant` | The ministry's catalogue number for the line (shared by both ways and every variant), the direction and the variant, from its `route_desc`. A train route has only the first |
| `type` | The GTFS route type as Kav reads it: `3` bus, `0` light rail, `2` train, `5` cable car, `7` the Carmelit, `711` shuttle, `715` on demand bus, `8` share taxi |
| `kind` | The same in a word: `bus`, `light_rail`, `train`, `cable_car`, `funicular`, `shuttle`, `on_demand_bus`, `share_taxi` |
| `mot_color` | The ministry's own `route_color` (hex, no `#`), where it gives one |
| `color` | The colour Kav draws the line in (hex, no `#`): the mode's for trains, light rail and the rest, else the operator's |

```csv
route_id,agency_id,operator_he,number,name_he,from_he,from_city_he,to_he,to_city_he,from_en,from_city_en,to_en,to_city_en,headsign_he,mot_line_id,direction,variant,type,kind,mot_color,color
6768,3,אגד,550,מלון יפה נוף-ערד<->קניון איילון-בני ברק-2#,מלון יפה נוף,ערד,קניון איילון,בני ברק,Yefe Nof Hotel,Arad,Ayalon Mall,Bnei Brak,בני ברק_קניון איילון,10550,2,#,3,bus,,018369
```

### operators.csv.gz

`agency_id`, `name_he`, `name_en`, `url`, `phone`, `color`: the ministry's operators, with the English names and colours
Kav uses. The operators' colours are Sammy Zaionz's.

### roads.geojson.gz and rail-roads.geojson.gz

A FeatureCollection with one LineString for each road, to five decimals (about a metre). Each feature lists every route
whose trips most often take that road.

```json
{"type": "Feature", "properties": {"route_ids": ["317"], "number": "24", "agency_id": 14, "type": 3},
 "geometry": {"type": "LineString", "coordinates": [[35.229, 33.09026], [35.22905, 33.09015], ...]}}
```

The ministry's timetable has no roads for trains. `rail-roads.geojson.gz` has them, made by matching its trains to
OpenStreetMap's tracks with [pfaedle](https://github.com/ad-freiburg/pfaedle). That makes it OpenStreetMap data, under
its own licence.

### fares.csv.gz and fares.json.gz

The fare files are the ministry's published prices, as data; Kav's files and API do not sell tickets or take payment.

`fares.csv.gz` is the ministry's fare table as Kav reads it: `from_code`, `to_code` (stop codes), `fare_id` and
`price_agorot` (100 agorot to a shekel).

```csv
from_code,to_code,fare_id,price_agorot
140,60270,202,1450
```

`fares.json.gz` says what to do with it:

- `products`: each fare id with its price, `kind` (`bus` covers buses, light rail and cable cars), the distance it is
  for (`up_to_km`), `includes_changes_minutes` (90 for the fare up to 15 km, which includes more rides) and whether it
  is Eilat's, without VAT.
- `route_fares`: lines with one fare for the whole line, by route id.
- `day_passes`: the day passes by distance, for buses alone (`bus`) or with trains (`rail`).
- `app_day_starts`: the time the app starts a new fare day, at the top level.
- `unsure_stops`: stops that have moved since their fares were set, whose distances Kav doesn't trust.
- `rules`: the rules, in words, from the price control order. The prices are the regular full fares: discounts by
  the rider's profile (students, senior citizens and others) are not in these files.

### busy.csv.gz

`route_id`, `direction`, `day`, `hour`, `level`: one row per route and hour Kav can describe, in Israel local time.
`route_id` and `direction` match `routes.csv.gz`. `day` is Sunday `0` through Saturday `6`; `hour` is `0` through `23`.
Weekdays share the server's weekday profile. `level` is exactly the server's value: `3` one of its busiest hours,
`2` about average, `1` one of its quieter hours, `q` a quiet day, `0` too little to say. A missing kind of day has no rows.
These compare a line with its own hours, using boardings per scheduled trip; they do not say how full a bus is.

The source is the ministry's [monthly boarding counts](https://data.gov.il/dataset/tikufim-2021), combined with the
saved daily timetables Kav's server uses. Fridays and Saturdays currently have no levels: October has too few days
on the winter clock. November's counts, expected in early December, may fill them once the server has enough data.
The manifest records the count month's dates and a fingerprint and date range for the saved timetables.

### fleet.csv.gz

`plate`, `size`, `seats`, `year`, `power`: one row per number plate in the ministry's
[bus fleet dataset](https://data.gov.il/dataset/bus_fleet), cleaned by Kav's server.
`size` is `bus`, `midibus`, `minibus` or `articulated`; `power` is `diesel`, `electric` or `gas`.
Unknown size and power are empty; unknown or invalid seats and year are `0`. Buses with no known attributes are omitted.
Plates are included as digit strings. There is no make, model or accessibility field.

### Line punctuality

Kav's app shows how late a line usually runs. There is no punctuality file.

### What isn't here

- **Kav's planner's own corrections to stop positions.** The one it makes puts a stop where OpenStreetMap has it, so
  these files keep to the ministry's positions.
- **Israel Railways' own timetable**, which Kav's planner uses to put the next days' trains right. Kav reads it from the
  service behind the railway's website, which isn't published as open data.

## Licences

- **Stops, routes, operators, roads and fares**: Kav publishes them under
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), but for one column: the `color` column in `routes.csv.gz`
  and `operators.csv.gz` is the operator and line colours from Kav's source code, contributed by Sammy Zaionz, and is
  under that code's licence, GPL-3.0; everything else in those two files is under CC BY 4.0. That is Kav's licence for its own work on the data; it
  doesn't speak for the ministry. Credit each file as its `credit` in the manifest says: the ministry, Tel Aviv-Yafo
  Municipality for the files with its weekend lines (stops, routes, operators, roads), Sammy Zaionz for the operators'
  colours (routes and operators), and Kav. The ministry
  publishes its timetable under [terms of use](https://www.gov.il/he/pages/gtfs_general_transit_feed_specifications) of
  its own, which also apply to what comes from it: it takes no responsibility for the data, and may change the terms.
  Tel Aviv's open data may be shared and adapted for any purpose, with credit
  ([its terms](https://opendatasource.tel-aviv.gov.il/en/Pages/FAQ.aspx)).
- **`rail-roads.geojson.gz`** holds OpenStreetMap data, the tracks the trains were matched to, so it is under the
  [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/), © OpenStreetMap contributors
  ([copyright](https://www.openstreetmap.org/copyright)), with the ministry and Kav credited too. What follows depends on
  what you make with it:
  - A database made from it (changed, extended, or a substantial part of it taken into another) that you share or use
    in public must be under the ODbL too (section 4.4), and you must offer it, or the changes you made, in machine
    readable form (section 4.6). Using such a database for something public counts as using it in public (4.4 c).
  - Something produced from it, such as a map image or the lines an app draws, needs a notice that it contains
    information from OpenStreetMap, available under the ODbL (section 4.3), and isn't itself bound to the ODbL
    (section 4.5 b). OSMF's [produced work guideline](https://osmfoundation.org/wiki/Licence/Community_Guidelines/Produced_Work_-_Guideline)
    draws the line: a result meant for taking the data back out is a database.
- **`busy.csv.gz` and `fleet.csv.gz`**: the ministry's data is under the
  [Data.gov.il database use licence](https://data.gov.il/terms-of-use), listed as `data-gov-il` in the manifest.
  It permits copying, redistribution, technical changes and derived works for commercial and non-commercial use,
  with source credit. Credit: Israel Ministry of Transport, data.gov.il; Kav (kavapp.link).
  The busy file also uses the ministry's timetable under its [GTFS terms](https://www.gov.il/he/pages/gtfs_general_transit_feed_specifications).
  These are Kav's processed files, supplied as is, without state endorsement. The portal prohibits misleading use
  and privacy infringement; its full terms and exclusions apply. Neither file is labelled CC BY by Kav.
- **Kav's code**, including the generator (`tools/open_data.py`): [GPL-3.0](/docs/license).

The files are made as they are. Check anything that matters against the ministry's own.

## Live data

A live-data API from Kav's server, with arrivals at stops, bus positions and line alerts, is not open. If you would use
it, write to [noam@kavapp.link](mailto:noam@kavapp.link) and say what you're building and roughly how many requests
you'd need.

## Your own server, for heavy use

Everything Kav's server does, trip planning included, runs on a machine of yours, with no limits but the machine's: see
[Self-hosting](/docs/self-hosting). Its planner for all of Israel needs about 6 GB of memory running and 22 GB while it
rebuilds at night ([what you need](/docs/self-hosting#what-you-need)), and live buses need
[your own key from the ministry](/docs/self-hosting#a-key-from-the-ministry-of-transport). Your server answers the same
[API](/docs/api), so code written against Kav's works against yours.
