"""A model pack (server/PREDICTOR.md) of the model train.py made: manifest.json and live3.npz in a .tar.gz, and its
sha256, for the setup page's "Kav's predictor with my own model". Before saying it's ready, it reads the pack back as
the server does: the manifest as the setup page reads it, unpacked as the nightly build unpacks it, its files found by
eta.py's pack_paths() and its model run by eta.py's Model on held-out questions.

    python3 server/eta/train/pack.py --work ~/kav-model --name my-model

--rail carries train and light-rail models too: a folder like the rail folder in Kav's own pack.
"""
import argparse
import datetime
import hashlib
import io
import json
import os
import tarfile
import tempfile
import time

import numpy as np

import examples as E  # puts eta.py's folder on the path
import eta

PACK_MOST = 500 << 20  # the largest pack the setup page takes


def read_back(path, work, days):
    """The pack as the server reads it: its name and version, the model, its errors on 2000 held-out questions and the
    operators it has train or light-rail models for. Stops, saying why, at one the server would refuse."""
    if os.path.getsize(path) > PACK_MOST:
        raise SystemExit("the pack is over %d MB, the most the setup page takes" % (PACK_MOST >> 20))
    with tempfile.TemporaryDirectory() as folder:
        with tarfile.open(path) as t:
            json.load(t.extractfile("manifest.json"))
            t.extractall(folder, filter="data")
        try:
            bus, fallback, rail, name = eta.pack_paths(folder)
        except eta.PackRefused as e:
            raise SystemExit("the predictor would refuse it: %s" % e)
        model = eta.Model(bus, eta.Model(fallback) if fallback else None)
        rails = [op for op in eta.OPERATORS if os.path.exists(os.path.join(rail, "gbdt-%s.txt" % op))]
    ex = E.load(work, days).head(2000).collect()
    return name, model, model.estimate(ex) - ex["target"].to_numpy(), rails


def main():
    p = argparse.ArgumentParser(description="Make a model pack of the trained model for Kav's predictor.")
    p.add_argument("--work", required=True, help="the folder train.py and score.py wrote to")
    p.add_argument("--name", required=True, help="the model's name, shown on the dashboard")
    p.add_argument("--version", default=datetime.date.today().isoformat(), help="shown beside it (today's date)")
    p.add_argument("--rail", help="a folder of train and light-rail models to carry (gbdt-<operator>.txt and theirs)")
    a = p.parse_args()
    with open(os.path.join(a.work, "train.json")) as f:
        trained = json.load(f)
    try:
        with open(os.path.join(a.work, "score.json")) as f:
            scored = json.load(f)
    except OSError:
        raise SystemExit("no score yet: run score.py first")
    files = {"bus": "live3.npz"}
    if a.rail:
        files["rail"] = "rail"
    manifest = {"pack": eta.PACK_VERSION, "name": a.name, "version": a.version, "inputs": "live3", "files": files,
                "trained_on": trained["days"]["train"], "examples": trained["examples"], "held_out": scored["held_out"],
                "score": scored["score"]}
    path = os.path.join(a.work, "%s-%s.tar.gz" % (a.name, a.version))
    with tarfile.open(path + ".part", "w:gz") as t:
        data = json.dumps(manifest, indent=1).encode()
        info = tarfile.TarInfo("manifest.json")
        info.size, info.mtime = len(data), int(time.time())
        t.addfile(info, io.BytesIO(data))
        t.add(os.path.join(a.work, "live3.npz"), arcname="live3.npz")
        if a.rail:
            t.add(a.rail, arcname="rail")
    os.replace(path + ".part", path)
    name, model, err, rails = read_back(path, a.work, scored["held_out"])
    with open(path, "rb") as f:
        sha = hashlib.file_digest(f, "sha256").hexdigest()
    print("%s, %.1f MB\nsha256 %s" % (path, os.path.getsize(path) / 1e6, sha))
    print("Read back as the server reads it: %s, %d lines and %d stops; %.1f%% of %d held-out questions within 2 minutes."
          % (name, len(model.routes), len(model.codes), (np.abs(err) <= 120).mean() * 100, err.size))
    print("Train and light-rail models in it: %s." % (", ".join(eta.OPERATORS[op] for op in rails) if rails else "none"))
    print("\nPut it where it can be downloaded over HTTPS (a release of your fork, say), and give the setup page its\n"
          "address: Parts, Arrival times, Kav's predictor with my own model. Or in .env:\n\n"
          "MODELS_URL=https://.../%s\nMODELS_SHA256=%s" % (os.path.basename(path), sha))


if __name__ == "__main__":
    main()
