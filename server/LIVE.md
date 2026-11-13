# Live sources

Everything live in Kav comes from one place, the **live source**:
- vehicles on the map
- stop boards
- the planner's live updates
- the recorded passings the predictor learns from

By default that's the Israeli Ministry of Transport's real-time service, with your own key. Any other source works
too, as long as it answers the same questions the same way: **SIRI StopMonitoring** (CEN/TS 15531, the European
standard the ministry also speaks), in JSON, version 2.8. Point Kav's server at it with:

```
LIVE_URL=http://my-source:8100/siri
```

A key is optional. When `MOT_KEY` is set, it's sent as `Key=` on every question.

## The two questions

Kav's server asks your source exactly two things, as GET requests with these query parameters:

| Question | Query | When |
|---|---|---|
| **All active trips** | `MonitoringRef=AllActiveTripsFilter&StopVisitDetailLevel=normal` | Every 15 seconds while phones are looking, every 30 otherwise |
| **Arrivals at stops** | `MonitoringRef=<code>,<code>,...&StopVisitDetailLevel=normal&PreviewInterval=PT90M` | When a stop board opens, at most once every 15 seconds per stop |

The answer to both is the same shape:

```json
{"Siri": {"ServiceDelivery": {
  "ResponseTimestamp": "2026-11-07T09:00:05+02:00",
  "StopMonitoringDelivery": [{"MonitoredStopVisit": [ <visit>, ... ]}]
}}}
```

## A visit

```json
{"RecordedAtTime": "2026-11-07T09:00:01+02:00",
 "MonitoringRef": "21345",
 "MonitoredVehicleJourney": {
   "LineRef": "10966",
   "PublishedLineName": "8",
   "OriginAimedDepartureTime": "2026-11-07T08:40:00+02:00",
   "VehicleRef": "7712345",
   "FramedVehicleJourneyRef": {"DataFrameRef": "2026-11-07", "DatedVehicleJourneyRef": "30027349"},
   "VehicleLocation": {"Latitude": "32.0754", "Longitude": "34.7749"},
   "Bearing": "90", "Velocity": "23",
   "MonitoredCall": {"StopPointRef": "21345", "Order": "12",
                     "AimedArrivalTime": "2026-11-07T09:04:00+02:00", "ExpectedArrivalTime": "2026-11-07T09:05:30+02:00",
                     "ArrivalPlatformName": "2"}}}
```

| Field | Needed | What Kav uses it for |
|---|---|---|
| `LineRef` | yes | The route's id in the timetable (GTFS `route_id`). **A number** (see Ids below) |
| `OriginAimedDepartureTime` | yes | When the trip left its first stop: with `LineRef`, which trip this is |
| `RecordedAtTime` | yes | When the vehicle reported. A position more than about 2 minutes old isn't shown as live |
| `VehicleRef` | for the map | Which vehicle it is, so it glides from report to report instead of jumping |
| `VehicleLocation` | for the map | Where it is. Without it, the trip still shows on boards, just not on the map |
| `MonitoredCall.StopPointRef`, `MonitoringRef` | yes | The stop: the one the vehicle is at or just passed (all trips), or the one asked about (arrivals). The stop's **code** (GTFS `stop_code`) |
| `MonitoredCall.Order` | for history | The stop's place on the trip, for the recorded passings |
| `MonitoredCall.ExpectedArrivalTime` | for boards | When it's expected at the stop (`AimedArrivalTime`, the timetable's, as a fallback) |
| `PublishedLineName` | for boards | The line's number as riders know it |
| `FramedVehicleJourneyRef` | for the planner | The trip's own id and service day, for the planner's live updates |
| `Bearing`, `Velocity` | optional | Which way it faces (degrees), its speed (km/h) |
| `ArrivalPlatformName` / `DeparturePlatformName` | optional | A train's platform |

Times are ISO 8601 with their UTC offset. Anything outside the area in `BOUNDS` (`south,west,north,east`) is ignored.
It's Israel by default, and a server can narrow it to one city.

## Ids

Kav is for Israel: route ids (`LineRef`) and stop codes are the Ministry of Transport's own, as in its timetable, and they
are numbers. A live source has to name trips and stops by those same ids.

## Ready-made sources

- **The ministry** (`MOT_URL`, `MOT_KEY`): the default.
- **GTFS-Realtime**, the other open live format: `server/examples/gtfsrt_siri.py` converts a feed's vehicle
  positions and trip updates into these answers. It needs the ministry's ids, so it suits a feed built on the ministry's
  timetable.
- **A recording**: `server/examples/live_replay.py` serves a recorded answer with its times moved to now, for trying it
  with no key at all, or as a starting point for your own source.

Kav ships adapters only for open, permitted sources. Whatever you connect to your own server is up to you, and on you.

Line alerts (detours, delays, moved stops) aren't part of the live source: a server takes them from any
GTFS-Realtime alerts feed, the ministry's among them ([self-hosting](selfhost/README.md#line-alerts)).

## Saving a recording

A recording for `live_replay.py` is one answer to the all-active-trips question. With a ministry key of your own, on the
machine the ministry approved it for, ask it as Kav's server does:

```sh
curl -s --cacert server/selfhost/mot-ca.crt \
  "https://moran.mot.gov.il/Channels/HTTPChannel/SmQuery/2.8/json?Key=$MOT_KEY&MonitoringRef=AllActiveTripsFilter&StopVisitDetailLevel=normal" \
  > recorded.json
```

`mot-ca.crt` is the certificate chain the ministry's server needs, as `live.py` uses it. Any other source's answer to
the same question works too.

## Checking yours

```sh
python3 server/conformance.py live http://my-source:8100/siri
```

It asks both questions and lists exactly what's missing or wrong. If your source breaks later, Kav's server keeps the
last snapshot for a while, then shows nothing live. The app says so, and nothing else breaks.
