# Training a model of your own

Kav's predictor (`server/eta/eta.py`) runs any model given as a model pack ([server/PREDICTOR.md](../../PREDICTOR.md)).
These scripts make one from your server's own history: when each bus passed each stop, which it keeps for ten weeks
in `data/history`. The network is the one Kav's own model is, trained the same way, and every input it learns from is
made by the predictor's own code, so it sees exactly what the predictor will give it.

They make `live3` models: buses, from the passings and the timetable. Kav's `live5` also takes where each bus was every
minute, which a server doesn't keep, so it can't be trained from a server's history.

## What it takes

- Four weeks of history at the least, and the more the better, up to the ten weeks a server keeps. The first two
  weeks only give the history medians (how long each stretch usually takes); the days after them are the examples.
- A computer with 8 GB of memory and Python 3.12. No graphics card.
- About 150 MB of downloads a week of history: the ministry's timetable of each week, from Hasadna's open archive of
  them.

## Step by step

1. **The history.** On the setup page, Storage, download the passings and unpack them: `tar xf kav-history.tar`
   makes a `history` folder. To train on the server itself instead, use its own folders in step 3:
   `--history ~/kav-server/data/history ~/kav-server/data/history-backfill` (the second holds older passings added by
   hand, if any). They belong to user 1000, so run the scripts as that user or as root.

2. **Install**, once:

   ```sh
   python3 -m venv ~/kav-train
   ~/kav-train/bin/pip install polars==2.0.0 numpy==2.5.4
   ~/kav-train/bin/pip install torch --index-url https://download.pytorch.org/whl/cpu
   ```

   Polars and numpy at the predictor's own versions, so the inputs come out the same.

3. **The examples**, from a clone of the repository (`git clone https://github.com/ImNoammm/kav`; the server's own
   folder has no code in it, only its images):

   ```sh
   ~/kav-train/bin/python server/eta/train/examples.py --history history --work ~/kav-model
   ```

   About a minute and 5 GB of memory a day. Stop it whenever you like: run again, it goes on from where it stopped.

4. **Train:**

   ```sh
   ~/kav-train/bin/python server/eta/train/train.py --work ~/kav-model
   ```

   The last 3 days are held out for the score, and the 2 before them show how it's doing after each pass. It writes
   `~/kav-model/live3.npz` and checks that eta.py's own `Model` gives the same times as the network it trained.
   Minutes: 8 days' examples took one on 4 processor threads. Run again on the same computer with the same `--seed`,
   it makes the same model.

5. **Score:**

   ```sh
   ~/kav-train/bin/python server/eta/train/score.py --work ~/kav-model
   ```

   ```
   Held out: 2026-11-04, 2026-11-05, 2026-11-06, 345386 questions.
   Buses that came within 2 minutes of the time given, by how long until they came:

                      0-10 min  10-20 min  20-40 min  40-60 min        all   median error
   your model            93.5%      66.0%      46.7%      31.6%      80.1%           45 s
   keep the delay        82.0%      30.3%      13.9%       4.7%      61.2%           78 s
   the timetable         48.2%      15.6%       9.4%       3.7%      35.8%          198 s
   ```

   That's a model trained on 8 days of Kav's own server (25 October to 1 November 2026, `--warmup 7`).

   "Keep the delay" has each bus stay as late as it was at its last stop. If your model isn't ahead of it, don't use
   it: train on more days.

6. **Pack:**

   ```sh
   ~/kav-train/bin/python server/eta/train/pack.py --work ~/kav-model --name my-model
   ```

   It writes `~/kav-model/my-model-<date>.tar.gz` and prints its sha256, after reading it back the way the server will.

7. **Use it.** Put the `.tar.gz` where it can be downloaded over HTTPS (a release of your fork, say). On the setup page:
   Parts, Arrival times, **Kav's predictor with my own model**, and give it the address. The nightly build fetches it,
   and the predictor runs it from then on. The dashboard shows its name and version.

## Trains and light rail

These scripts train the bus model only. To keep Kav's train and light-rail models, put them in your pack: unpack
`kav-eta-models.tar.gz` from the `eta-models-2026-10-27` release on GitHub and add `--rail <its rail folder>` to step 6.

## Settings

| Script | Setting | Default |
|---|---|---|
| `examples.py` | `--warmup`: days of history that only give the medians | 14 |
| | `--moments`: moments a day asked about (each asks about up to 10 stops ahead) | 20000 |
| `train.py` | `--sample`: training examples at most | 3000000 |
| | `--epochs`, then `--window`: passes, then passes aimed at the 2-minute measure itself | 3, 1 |
| | `--test-days`, `--valid-days`: the last days held out | 3, 2 |
| | `--seed`, `--threads` | 0, all |
| `pack.py` | `--version`, shown beside the name | today |

Holidays are left out of the examples, as Kav's own model left them out, and so is a day with far fewer passings than
its weekday's usual (a strike, or the server was down).
