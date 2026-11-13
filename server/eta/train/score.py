"""How a model does on the days train.py held out, against two rules it should beat: keep the delay (each bus stays as
late as it was at its last stop) and the timetable. The model runs in eta.py's own Model, as the predictor runs it.
Prints a short table and writes it to <work>/score.json, which pack.py puts in the pack's manifest.

    python3 server/eta/train/score.py --work ~/kav-model
"""
import argparse
import json
import os

import numpy as np
import polars as pl

import examples as E  # puts eta.py's folder on the path
import eta

HORIZONS = (("0-10 min", 0, 600), ("10-20 min", 600, 1200), ("20-40 min", 1200, 2400), ("40-60 min", 2400, 3600))


def within(err, seconds=120):
    return round(float((np.abs(err) <= seconds).mean() * 100), 1) if err.size else None


def score(ex, model):
    """For the model and each rule: the share within 2 minutes by how long until the bus came and of all the
    questions, and of all of them within 1 and 5 minutes and the median error."""
    y = ex["target"].to_numpy().astype(np.float64)
    ahead, elapsed = ex["ahead_sched"].cast(pl.Float64).to_numpy(), ex["elapsed"].cast(pl.Float64).to_numpy()
    delay = ex["delay"].cast(pl.Float64).to_numpy()
    ways = {"your model": model.estimate(ex), "keep the delay": np.maximum(ahead - elapsed, 0),
            "the timetable": np.maximum(ahead - delay - elapsed, 0)}
    out = {}
    for name, p in ways.items():
        err = p - y
        out[name] = {h: within(err[(y >= lo) & (y < hi)]) for h, lo, hi in HORIZONS}
        out[name].update({"all": within(err), "within 1 min": within(err, 60), "within 5 min": within(err, 300),
                          "median error s": round(float(np.median(np.abs(err))))})
    return out


def main():
    p = argparse.ArgumentParser(description="Score a model on the held-out days against keeping the delay and the timetable.")
    p.add_argument("--work", required=True, help="the folder train.py wrote to")
    p.add_argument("--model", help="the model to score (<work>/live3.npz)")
    a = p.parse_args()
    with open(os.path.join(a.work, "train.json")) as f:
        days = json.load(f)["days"]["test"]
    ex = E.load(a.work, days).collect()
    rows = score(ex, eta.Model(a.model or os.path.join(a.work, "live3.npz")))
    print("Held out: %s, %d questions." % (", ".join(days), ex.height))
    print("Buses that came within 2 minutes of the time given, by how long until they came:\n")
    names = [h for h, _, _ in HORIZONS] + ["all"]
    print("%-16s" % "" + "".join("%11s" % n for n in names) + "%15s" % "median error")
    for name, got in rows.items():
        print("%-16s" % name + "".join("%11s" % ("-" if got[n] is None else "%.1f%%" % got[n]) for n in names)
              + "%13d s" % got["median error s"])
    with open(os.path.join(a.work, "score.json"), "w") as f:
        json.dump({"held_out": days, "questions": ex.height, "score": rows}, f, indent=1)


if __name__ == "__main__":
    main()
