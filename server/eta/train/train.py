"""Train a model of your own for Kav's predictor on the examples (examples.py), and write it as the arrays eta.py
loads (<work>/live3.npz). The network, its loss and its settings are the ones Kav's own model was trained with, here on
this computer's processor. The last days are held out: the ones before them show how it's doing after each pass, the
last ones are for score.py.

    python3 server/eta/train/train.py --work ~/kav-model

Kav's own model learnt from 60 million examples of a year, on a graphics card. The defaults here take 3 million at
most: minutes on a home computer's processor, in a few GB of memory.
"""
import argparse
import json
import math
import os
import time

import numpy as np
import polars as pl
import torch
import torch.nn as nn

import examples as E  # puts eta.py's folder on the path
import eta
import inputs as I

BATCH = 8192
VALID_MOST = 300_000  # validation examples scored after each pass


def make_net(n_in, n_routes, n_codes):
    """A line embedding, one for both stops, and three hidden layers, giving (time - base) / 600. eta.py's Model runs
    it as numpy arrays by these names."""
    class Net(nn.Module):
        def __init__(self):
            super().__init__()
            self.route = nn.Embedding(n_routes + 1, 16)
            self.code = nn.Embedding(n_codes + 1, 16)
            self.mlp = nn.Sequential(nn.Linear(n_in + 48, 256), nn.ReLU(), nn.Linear(256, 256), nn.ReLU(),
                                     nn.Linear(256, 128), nn.ReLU(), nn.Linear(128, 1))

        def forward(self, x, r, c1, c2):
            return self.mlp(torch.cat([x, self.route(r), self.code(c1), self.code(c2)], 1)).squeeze(1)

    return Net()


def split(days, test, valid):
    """The days to train on, to watch it by, and to score it on: the last `test` days, the `valid` before them."""
    if len(days) < test + valid + 1:
        raise SystemExit("%d days of examples; it takes at least %d" % (len(days), test + valid + 1))
    return days[:-test - valid], days[-test - valid:-test], days[-test:]


def sampled(lf, keys, sample, seed):
    """About `sample` examples, picked by a hash of their keys, so the rest is never loaded."""
    n = lf.select(pl.len()).collect().item()
    if n > sample:
        lf = lf.filter(pl.struct(keys).hash(seed) % n < sample)
    return lf.collect()


def metrics(err):
    a = np.abs(err)
    return dict(n=int(a.size), median_s=float(np.median(a)), bias_s=float(np.median(err)),
                within_1min=float((a <= 60).mean() * 100), within_2min=float((a <= 120).mean() * 100),
                within_5min=float((a <= 300).mean() * 100))


def estimate(net, ex, routes, codes):
    """Seconds from the moment asked until the bus reaches stop j."""
    parts = I.matrix(ex, routes, codes)
    net.eval()
    out = []
    with torch.no_grad():
        for i in range(0, ex.height, 65536):
            out.append(net(*(torch.from_numpy(z[i:i + 65536]) for z in parts)).numpy())
    net.train()
    return np.concatenate(out) * 600 + ex["base"].to_numpy()


def export(net, routes, codes, path):
    """The network as the arrays eta.py's Model loads: its weights by their names, and in meta the lines' and stops'
    numbers and the input groups."""
    state = {k: v.detach().numpy().astype(np.float32) for k, v in net.state_dict().items()}
    meta = {"routes": routes, "codes": {str(k): v for k, v in codes.items()}, "groups": list(I.GROUPS)}
    np.savez(path, meta=np.array(json.dumps(meta)), **state)


def main():
    p = argparse.ArgumentParser(description="Train a model of your own for Kav's predictor on the examples.")
    p.add_argument("--work", required=True, help="the folder examples.py made")
    p.add_argument("--sample", type=int, default=3_000_000, help="training examples at most (3000000)")
    p.add_argument("--epochs", type=int, default=3, help="passes over them (3)")
    p.add_argument("--window", type=int, default=1, help="passes after those aimed at the 2-minute measure itself (1)")
    p.add_argument("--test-days", type=int, default=3, help="the last days, held out for score.py (3)")
    p.add_argument("--valid-days", type=int, default=2, help="the days before those, to watch it by (2)")
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--threads", type=int, default=0, help="processor threads (all)")
    a = p.parse_args()
    torch.manual_seed(a.seed)
    if a.threads:
        torch.set_num_threads(a.threads)
    started = time.time()
    train_days, valid_days, test_days = split(E.made(a.work), a.test_days, a.valid_days)
    train = sampled(E.load(a.work, train_days), ["route", "service", "start_sec", "order", "stops_ahead"], a.sample, a.seed)
    valid = E.load(a.work, valid_days).collect()
    if valid.height > VALID_MOST:
        valid = valid.sample(VALID_MOST, seed=a.seed)
    print("training on %d examples of %d days (%s to %s), watching %d of %s" % (
        train.height, len(train_days), train_days[0], train_days[-1], valid.height, ", ".join(valid_days)), flush=True)
    # Lines and stops by number, 0 for one it never saw; sorted, so a seed gives the same model.
    routes = {v: i + 1 for i, v in enumerate(sorted(train["route"].unique().to_list()))}
    codes = {v: i + 1 for i, v in enumerate(sorted(pl.concat([train["code"], train["code_j"]]).unique().to_list()))}
    tensors = [torch.from_numpy(z) for z in I.matrix(train, routes, codes)]
    yt = torch.from_numpy(((train["target"].to_numpy() - train["base"].to_numpy()) / 600).astype(np.float32))
    examples = train.height
    del train
    net = make_net(tensors[0].shape[1], len(routes), len(codes))
    report = {"days": {"train": train_days, "valid": valid_days, "test": test_days}, "examples": examples,
              "routes": len(routes), "stops": len(codes), "seed": a.seed, "passes": []}

    def fit(n, loss_fn, lr, tag):
        opt = torch.optim.AdamW(net.parameters(), lr=lr, weight_decay=1e-5)
        sch = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=lr, total_steps=n * math.ceil(len(yt) / BATCH))
        for i in range(n):
            t = time.time()
            perm = torch.randperm(len(yt))
            for s in range(0, len(yt), BATCH):
                idx = perm[s:s + BATCH]
                loss = loss_fn(net(*(z[idx] for z in tensors)), yt[idx])
                opt.zero_grad()
                loss.backward()
                opt.step()
                sch.step()
            got = metrics(estimate(net, valid, routes, codes) - valid["target"].to_numpy())
            report["passes"].append(dict(got, kind=tag, seconds=round(time.time() - t)))
            print("%s pass %d: %.1f%% within 2 minutes, median error %.0f s (%.0f s)" % (
                tag, i + 1, got["within_2min"], got["median_s"], time.time() - t), flush=True)

    fit(a.epochs, nn.SmoothL1Loss(beta=0.05), 2e-3, "training")
    if a.window:
        # Then the measure itself, arriving within two minutes: a smooth step at 120 s (0.2 of 600 s).
        fit(a.window, lambda p, t: (1 - torch.sigmoid((0.2 - (p - t).abs()) / 0.025)).mean(), 2e-4, "2-minute")
    path = os.path.join(a.work, "live3.npz")
    export(net, routes, codes, path)
    # eta.py's own Model, as the predictor runs it, gives the times the network does.
    check = valid.head(20000)
    off = float(np.abs(eta.Model(path).estimate(check) - estimate(net, check, routes, codes)).max())
    if off > 1:
        raise SystemExit("eta.py's Model gives times up to %.1f s off the network's: the export is wrong" % off)
    report.update(seconds=round(time.time() - started), export_off_s=off)
    with open(os.path.join(a.work, "train.json"), "w") as f:
        json.dump(report, f, indent=1)
    print("%s: %d lines, %d stops; eta.py's Model gives the same times (at most %.3f s apart); %.0f minutes in all" % (
        path, len(routes), len(codes), off, (time.time() - started) / 60))


if __name__ == "__main__":
    main()
