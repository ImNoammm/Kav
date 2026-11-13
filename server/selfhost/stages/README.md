# Build steps of your own

Scripts for the nightly build go here. The build container sees this folder as `/stages`.

A step is replaced by `STAGE_<NAME>` in `.env` (or on the setup page: Parts, "Timetable, map and build steps"), a shell
command run in the build container, or `skip` to leave the step out:

```
STAGE_PREDICT=python3 /stages/my_predict.py
STAGE_AREA=sh /stages/my_cut.sh
STAGE_WALKS=skip
```

The steps are `prepare`, `bundle`, `area`, `walks`, `predict`, `clocks` and `graph`, in that order. `area` runs only
when there's an area (`BOUNDS`), and the steps after `bundle` only with Kav's planner. Your command gets
`BUILD` (the folder every step reads and writes: `israel-gtfs.zip`, `israel.osm.pbf`), `OFFICIAL`, `HISTORY`,
`REALTIME`, `MODELS`, `STAGES` and `AREA` (`south,west,north,east`, or empty for all of Israel) in its environment.

What each step does, and what happens when one fails: [server/selfhost/README.md](../README.md#build-steps).
