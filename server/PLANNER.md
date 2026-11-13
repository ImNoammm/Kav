# A planner of your own

Kav's server plans trips with OpenTripPlanner, built every night from the ministry's timetable and OpenStreetMap
([server/selfhost](selfhost/README.md)). Any other planner works in its place, in one of two ways. On the setup page:
Parts, Trip planner, My own planner. By hand, in `.env`, with `PLANNER=own` so the nightly build doesn't make a planner
you won't use.

## Your own OpenTripPlanner

```
OTP_URL=http://my-otp:8080/otp/gtfs/v1
```

An OpenTripPlanner of your own, or anything that answers its GTFS GraphQL API the same way (`planConnection`). Kav runs
version 2.10, and asks it what it asks its own. Kav's server still picks the routes, orders them and adds the rest (the
other lines from the same stop, the taxi card, and so on), so the app looks the same.

The server counts it as answering when `{ serviceTimeRange { end } }` comes back with a timetable that hasn't run out.

## A planner you wrote

```
PLAN_URL=http://my-planner:9000/plan
```

Any language, any method. Every trip the app asks for goes to it as

```
GET <PLAN_URL>?from=<lat>,<lon>&to=<lat>,<lon>&time=<unix seconds>&way=<d|a|n|l>[&modes=0,3][&via=<lat>,<lon>;<lat>,<lon>]
```

- `way`: `d` leave at the time, `a` arrive by it, `n` leave now, `l` the last way there that night.
- `modes`: the GTFS route types the rider allows (`0` light rail, `2` train, `3` bus, ...). All when left out.
- `via`: up to three places to pass on the way. The minutes a rider asks to stay at a place aren't passed on.

It answers with the routes, in the format the app reads: [Plan in server/API.md](API.md#plan). `it` is required;
`stops`, `lines`, `agencies` and `errors` are filled in empty when left out. Kav's server passes the answer on as it is,
so the order, the tags and the sections are yours.

A plain `GET <PLAN_URL>` answering under 400 tells the app the planner is up. The server waits 30 seconds for an answer.

Route ids and stop codes are the ministry's (GTFS `route_id` and `stop_code`), so the app can match your rides to the
live buses and the stop boards.

## Start from the example

`server/examples/planner.py` is a planner as small as one can be: it answers with one route, walking straight there.

```sh
python3 server/examples/planner.py                                 # serves on :9000
python3 server/conformance.py plan http://127.0.0.1:9000/plan      # checks it
```

## Checking yours

```sh
python3 server/conformance.py plan http://my-planner:9000/plan
```

It asks for a trip from central Tel Aviv to Tel Aviv University, leaving in ten minutes, and lists everything in the
answer the app couldn't read: missing fields, times that aren't unix seconds, rides whose line or stops aren't in `lines`
and `stops`. Exit code 0 when it conforms.
