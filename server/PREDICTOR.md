# A predictor of your own

Kav's server gives the app, and its planner, its own predicted arrival times in place of the ministry's: when each bus,
train and light rail is due at each stop still ahead of it. Kav's predictor (`server/eta/eta.py`) makes them every 30
seconds. You can replace it two ways: keep Kav's predictor and give it a model of your own, or replace the predictor
entirely. Either way, if yours stops or breaks, the ministry's own times take over within two minutes, and nothing else
breaks.

On the setup page: Parts, Arrival times.

| Choice | What runs | `.env` |
|---|---|---|
| Kav's arrival times | Kav's predictor with Kav's model | `COMPOSE_PROFILES` has `predictor` |
| Kav's predictor with my own model | Kav's predictor with your model pack | and `MODELS_URL`, `MODELS_SHA256` |
| My own predictor, at an address | yours, anywhere | `PREDICT_URL=http://my-predictor:8000/now.json` |
| My own predictor, on this machine | yours, writing a file here | no `predictor` profile; it writes `data/eta/out/now.json` |
| The ministry's times | nothing | `ETA=` (empty) |

## The predictions file, version 1

```json
{"version": 1, "at": 1793900000,
 "runs": {"10966_1793899800": [[12, "21345", 1793900330], [13, "21360", 1793900420]]}}
```

- `at`: when it was made, unix seconds. Older than 120 seconds, it's ignored, and the ministry's times stand.
- `runs`: each trip under way, by `<route id>_<the trip's first departure, unix seconds>`. Route ids are the ministry's
  (GTFS `route_id`).
- Each trip's stops still ahead of it, as `[the stop's place on the trip, stop code, arrival in unix seconds]`. Stop
  codes are the ministry's (GTFS `stop_code`), as text. A stop a trip passes twice: the next time counts.
- `version`: 1. A file of another version is refused. One without `version` is read as 1.
- `error` (optional): what's wrong, in words. While it's there, the app shows Kav's times as off.

A trip you don't list keeps the ministry's times. Predict only what you trust.

### As a file

Write `now.json` to a temporary name in the same folder, then rename it over the old one, so it's never read half
written. The server reads it again whenever it changes. Write `status.json` beside it every round too, even with no
buses on the road:

```json
{"version": 1, "at": 1793900000}
```

with `"error": "..."` when something's wrong. Without a fresh `status.json`, the app shows Kav's times as off. The folder,
`data/eta/out`, belongs to user 1000, as the server's services run.

### At an address

`PREDICT_URL` answers `GET` with the predictions file. The server asks every 15 seconds, waits 5 seconds for an answer,
and keeps the last good one until it's 120 seconds old.

## What a predictor can read

The same as Kav's:

- the buses on the road: this server's own `/live/v1/` addresses ([server/API.md](API.md)), signed with its internal
  key, `KAV_APP_KEY` in `.env`
- the recorded passings, when each bus passed each stop: `data/history/`, one file a day, kept ten weeks
- the timetable: `data/otp/official/mot-gtfs.zip`, the ministry's own, fetched every night
- the ministry itself, with a key of your own

## Checking yours

```sh
python3 server/conformance.py predictions data/eta/out/now.json
```

For one at an address, give it the address instead: `python3 server/conformance.py predictions
http://my-predictor:8000/now.json`. It lists everything the server would refuse or ignore. Exit code 0 when it conforms.

## A model of your own for Kav's predictor

Kav's predictor runs any model given as a **model pack**: a `.tar.gz` with `manifest.json` at its top and the model's
files beside it.

```json
{"pack": 1, "name": "my-model", "version": "2026-11-07", "inputs": "live5",
 "files": {"bus": "live5_motion.npz", "bus_fallback": "live5_control.npz", "rail": "rail"}}
```

- `pack`: the format, 1.
- `inputs`: the features the model was trained on, as Kav's predictor makes them: `live3` (`server/eta/inputs.py`) or
  `live5` (the same with `server/eta/motion.py`).
- `files`: `bus` is required. `bus_fallback` is the model for a bus with too little to go on. `rail` is the folder of
  the train and light rail models (`rail` when left out). The files are the same kinds as in Kav's own model files
  (`kav-eta-models.tar.gz` in the `eta-models-2026-10-27` release on GitHub), which is the place to start.
- `name` and `version` are shown on the dashboard.
- Other fields are ignored. The training scripts' packs add the days trained on and the score.

Put it somewhere it can be downloaded over HTTPS (a release of your fork, say), and paste its address on the setup page.
The page downloads it (500 MB at most), reads its manifest, and refuses a pack Kav's predictor couldn't run. The nightly
build fetches it again, takes it only when its sha256 matches, and unpacks it into `data/models`. A pack the predictor
still can't run (a file it names isn't there) is written, with why, to `data/eta/out/status.json` and the predictor's
log. The app shows Kav's times as off, and the ministry's times stand meanwhile.

To train one from your server's own history, step by step from the history to the pack:
[server/eta/train](eta/train/README.md). It trains `live3` models, Kav's network on your passings and the ministry's
timetables. `live5` also takes where each bus was every minute, which a server doesn't keep.
