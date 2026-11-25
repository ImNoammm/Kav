#!/usr/bin/env python3
"""Named keys for Kav's server (live.py's APP_KEYS): each signs requests as the app does, with a key of its own that
can be limited and revoked without touching anyone else's. Kav's server reads the file again when it changes.

  appkeys.py FILE list
  appkeys.py FILE add NAME NOTE [PER_MINUTE PER_DAY]   prints the new key once
  appkeys.py FILE limit NAME PER_MINUTE PER_DAY
  appkeys.py FILE revoke NAME
"""
import fcntl
import json
import os
import re
import secrets
import sys
import tempfile
import time


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


def save(path, doc):
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(path)))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=1)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def limits(args):
    try:
        per_minute, per_day = map(int, args)
        if min(per_minute, per_day) > 0:
            return per_minute, per_day
    except ValueError:
        pass
    sys.exit("per-minute and per-day limits must be positive integers")


def main(argv):
    if len(argv) < 3:
        sys.exit(__doc__)
    path, what, rest = argv[1], argv[2], argv[3:]
    lock = os.open(path + ".lock", os.O_WRONLY | os.O_CREAT, 0o600)
    with os.fdopen(lock, "w") as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        change(path, what, rest)


def change(path, what, rest):
    doc = load(path)
    if what == "list":
        for name, k in doc.items():
            state = "revoked" if k.get("revoked") else "active"
            print(f"{name}\t{state}\t{k.get('per_minute')}/min {k.get('per_day')}/day\t{k.get('added', '')}\t{k.get('note', '')}")
    elif what == "add" and len(rest) in (2, 4):
        name, note = rest[0], rest[1]
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,31}", name) or name in doc:
            sys.exit("name: lower-case letters, digits and -, not one already there")
        per_minute, per_day = limits(rest[2:]) if len(rest) == 4 else (120, 20000)
        key = "kav_" + secrets.token_urlsafe(32)
        doc[name] = {"secret": key, "note": note, "per_minute": per_minute, "per_day": per_day,
                     "added": time.strftime("%Y-%m-%d"), "revoked": False}
        save(path, doc)
        print(key)
    elif what == "limit" and len(rest) == 3 and rest[0] in doc:
        per_minute, per_day = limits(rest[1:])
        doc[rest[0]].update(per_minute=per_minute, per_day=per_day)
        save(path, doc)
    elif what == "revoke" and len(rest) == 1 and rest[0] in doc:
        doc[rest[0]]["revoked"] = True
        save(path, doc)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
