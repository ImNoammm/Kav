"""The training table for a model of your own for Kav's predictor, from a server's recorded passings (data/history:
when each bus passed each stop) and the ministry's timetables of those days. The questions are the ones Kav's own model
was trained on, and every input is made by the predictor's own code (eta.py, inputs.py), so the model sees exactly
what the predictor will give it.

    python3 server/eta/train/examples.py --history data/history --work ~/kav-model

One file a day in <work>/examples. A day already made is kept, so it can be stopped and run again. Each week's
timetable comes from Hasadna's open archive of the ministry's (about 150 MB) into <work>/timetables, unless one from
that week is there already.
"""
import argparse
import datetime
import gzip
import os
import shutil
import statistics
import sys
import time
import urllib.request

import polars as pl

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import eta  # noqa: E402
import inputs as I  # noqa: E402

ARCHIVE = "https://openbus-stride-public.s3.eu-west-1.amazonaws.com/gtfs_archive/%Y/%m/%d/israel-public-transportation.zip"
AHEAD = (1, 2, 3, 5, 8, 12, 17, 24, 32, 42)  # the stops after the last one reached that a moment asks about
EVERY = 3  # moments after every third stop of a run
LOW = 0.6  # a day with fewer passings than this share of its weekday's usual (a strike, the server down) gives none


def moments(rows, keep, every=EVERY):
    """The questions: a bus between stop k (every `every`-th) and the next stop it reached, at a point in between
    picked by a hash, about the stops AHEAD of k. `keep`: the share of such moments kept, by a hash."""
    r = rows.sort(I.RUN + ["order"]).with_columns(pl.col("at").shift(-1).over(I.RUN).alias("at_next"))
    r = r.filter(((pl.col("order") - 1) % every == 0) & pl.col("at_next").is_not_null()
                 & (pl.struct(I.RUN + ["order"]).hash(11) % 1_000_000 < keep * 1_000_000))
    u = (pl.struct(I.RUN + ["order"]).hash(7) % 1000).cast(pl.Float64) / 1000
    r = r.with_columns((pl.col("at") + u * (pl.col("at_next") - pl.col("at"))).floor().cast(pl.Int64).alias("t"))
    return (r.select(I.RUN + ["t", "order"]).join(pl.DataFrame({"d": AHEAD}), how="cross")
            .select(I.RUN + ["t", (pl.col("order") + pl.col("d")).alias("order_j")]))


def make(day, files, gtfs, wanted):
    """A service day's examples: about `wanted` moments, their inputs as the predictor makes them live (eta.Day: the
    timetable and the history medians; inputs.examples with the day's plan for the stops ahead), and the target, the
    seconds from the moment until the bus reached stop j. The day's later passings are there too, but inputs.examples
    only looks back from each moment."""
    d = eta.Day(day, files, gtfs)
    if d.plan.height == 0:
        raise ValueError("timetable %s has no runs that day" % os.path.basename(gtfs))
    names = [(day + datetime.timedelta(days=k)).isoformat() for k in (0, 1)]  # a day's last buses are in the next day's file
    rows = I.load_history([files[n] for n in names if n in files]).filter(pl.col("service") == day)
    # A run with a stop twice is two buses on one trip, which can't be told apart: inputs.examples leaves it out too.
    # Here it's left out all day, where live it counts until it happens; measured on one day's buses, that moves Kav's
    # own model's times by under a second.
    rows = d.with_history(rows).filter(pl.len().over(I.RUN) == pl.col("order").n_unique().over(I.RUN))
    starts = rows.filter((pl.col("order") - 1) % EVERY == 0).height
    q = moments(rows, min(1.0, wanted / max(starts, 1)))
    ex = I.base(I.examples(rows, q, d.plan)).filter(pl.col("base").is_not_null())
    reached = rows.select(I.RUN + [pl.col("order").alias("order_j"), pl.col("at").alias("reached")])
    return (ex.join(reached, on=I.RUN + ["order_j"]).with_columns((pl.col("reached") - pl.col("t")).alias("target"))
            .filter(pl.col("target").is_between(1, 3 * 3600)))


def passings(path):
    """How many passings a history file holds: its lines."""
    with (gzip.open if path.endswith(".gz") else open)(path, "rb") as f:
        return sum(chunk.count(b"\n") for chunk in iter(lambda: f.read(1 << 20), b""))


def days_of(files, warmup):
    """The days to make examples of: after the first `warmup` days, which only give the history medians, up to the
    day before the newest file (its buses may still be on the road). Not holidays, as Kav's own model left them out,
    and not a day far below its weekday's usual. Returns them and the days left out as below usual."""
    dated = sorted(datetime.date.fromisoformat(n) for n in files)
    if len(dated) < 2:
        return [], []
    first, last = dated[0] + datetime.timedelta(days=warmup), dated[-1] - datetime.timedelta(days=1)
    counts = {d: passings(files[d.isoformat()]) for d in dated[:-1]}
    usual = {}
    for d, n in counts.items():
        if d not in I.HOLIDAYS:
            usual.setdefault(d.weekday(), []).append(n)
    days = [d for d in dated if first <= d <= last and d not in I.HOLIDAYS]
    low = [d for d in days if counts[d] < LOW * statistics.median(usual[d.weekday()])]
    return [d for d in days if d not in low], low


def timetable(day, folder):
    """The ministry's timetable for `day`: the newest in `folder` from that week (since the Sunday before), else the
    archive's of that Sunday, fetched. A timetable runs a month ahead, so one a week covers it."""
    sunday = day - datetime.timedelta(days=(day.weekday() + 1) % 7)
    have = sorted(n for n in os.listdir(folder) if n.endswith(".zip") and sunday.isoformat() <= n[:10] <= day.isoformat())
    if have:
        return os.path.join(folder, have[-1])
    path = os.path.join(folder, "%s.zip" % sunday)
    print("fetching the timetable of", sunday, "(about 150 MB)", flush=True)
    try:
        req = urllib.request.Request(sunday.strftime(ARCHIVE), headers={"User-Agent": "kav-train"})
        with urllib.request.urlopen(req, timeout=600) as r, open(path + ".part", "wb") as f:
            shutil.copyfileobj(r, f, 1 << 20)
    except OSError as e:
        raise ValueError("the archive has no timetable of %s (%s); put that week's in %s as %s.zip" % (sunday, e, folder, sunday))
    os.replace(path + ".part", path)
    return path


def load(work, days):
    """The examples of these days, unread until collected."""
    return pl.scan_parquet([os.path.join(work, "examples", "%s.parquet" % d) for d in days])


def made(work):
    """The days with examples, oldest first."""
    folder = os.path.join(work, "examples")
    return sorted(n[:10] for n in os.listdir(folder) if n.endswith(".parquet")) if os.path.isdir(folder) else []


def main():
    p = argparse.ArgumentParser(description="Make the training table for a model of your own from a server's history.")
    p.add_argument("--history", required=True, nargs="+",
                   help="the server's recorded passings: data/history or a copy, and data/history-backfill after it")
    p.add_argument("--work", required=True, help="a folder for the timetables, the examples and the model")
    p.add_argument("--warmup", type=int, default=14, help="days of history before the first day of examples (14)")
    p.add_argument("--moments", type=int, default=20000, help="moments asked about each day (20000)")
    a = p.parse_args()
    files = eta.history_files(a.history)  # as the predictor reads them: a day in both from the first
    days, low = days_of(files, a.warmup)
    if low:
        print("left out, far below their weekday's usual:", ", ".join(map(str, low)))
    if not days:
        sys.exit("no days to make examples of: %d days of history, and the first %d only give the medians" % (len(files), a.warmup))
    out, tables = os.path.join(a.work, "examples"), os.path.join(a.work, "timetables")
    os.makedirs(out, exist_ok=True)
    os.makedirs(tables, exist_ok=True)
    print(len(days), "days of examples,", days[0], "to", days[-1], flush=True)
    for day in days:
        path = os.path.join(out, "%s.parquet" % day)
        if os.path.exists(path):
            continue
        start = time.time()
        try:
            ex = make(day, files, timetable(day, tables), a.moments)
        except (OSError, ValueError) as e:
            print(day, "left out:", e, flush=True)
            continue
        ex.write_parquet(path + ".part")
        os.replace(path + ".part", path)
        print(day, "%d examples in %.0f s" % (ex.height, time.time() - start), flush=True)


if __name__ == "__main__":
    main()
