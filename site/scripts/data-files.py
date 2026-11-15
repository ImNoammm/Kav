#!/usr/bin/python3
"""Filesystem operations for the data pull, with syscall errors propagated to the caller.

Requires Linux renameat2 for an atomic directory exchange. No shell utilities perform
these mutations or hashes, including cleanup and rollback after a failed validation.
"""
import ctypes
import hashlib
import os
from os import link
import shutil
import sys
import stat


def digest(stream):
    h = hashlib.sha256()
    for block in iter(lambda: stream.read(1 << 20), b""):
        h.update(block)
    return h.hexdigest()


def main(args):
    op, *args = args
    if op in ("lock", "check-lock"):
        path = args[0]
        fd = os.open(path, os.O_RDONLY | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o644) if op == "lock" else 9
        info = os.fstat(fd)
        named = os.stat(path, follow_symlinks=False)
        if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != os.getuid() or
                (info.st_dev, info.st_ino) != (named.st_dev, named.st_ino)):
            raise ValueError("lock is not an owned regular single-link file")
        if op == "lock":
            os.dup2(fd, 9, inheritable=True)
            os.set_inheritable(9, True)
            if fd != 9:
                os.close(fd)
            shell = os.readlink("/proc/%d/exe" % os.getppid())
            os.execv(shell, [shell, args[1], args[2], "--lock-open"])
    elif op == "remove":
        for path in args:
            if os.path.islink(path) or os.path.isfile(path):
                os.unlink(path)
            elif os.path.exists(path):
                shutil.rmtree(path)
            if os.path.lexists(path):
                raise OSError("removal incomplete: " + path)
    elif op == "link":
        *sources, dest = args
        for source in sources:
            target = os.path.join(dest, os.path.basename(source)) if os.path.isdir(dest) else dest
            link(source, target, follow_symlinks=False)
            if not os.path.samefile(source, target):
                raise OSError("link incomplete: " + target)
    elif op == "mode":
        mode, path = args
        os.chmod(path, int(mode, 8), follow_symlinks=False)
        if os.stat(path).st_mode & 0o7777 != int(mode, 8):
            raise OSError("mode unchanged: " + path)
    elif op == "move":
        source, dest = args
        os.rename(source, dest)
    elif op == "exchange":
        source, dest = args
        libc = ctypes.CDLL(None, use_errno=True)
        call = libc.renameat2
        call.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        call.restype = ctypes.c_int
        if call(-100, os.fsencode(source), -100, os.fsencode(dest), 2):
            code = ctypes.get_errno()
            raise OSError(code, os.strerror(code))
    elif op == "hash":
        print(digest(sys.stdin.buffer) + "  -")
    elif op == "old-assets":
        import json
        import re
        with open(args[0]) as stream:
            assets = json.load(stream)["assets"]
        names = "stops.csv.gz routes.csv.gz operators.csv.gz roads.geojson.gz rail-roads.geojson.gz fares.csv.gz fares.json.gz busy.csv.gz fleet.csv.gz manifest.json manifest.json.sig".split()
        for asset in assets:
            name = asset["name"]
            if isinstance(name, str) and any(re.fullmatch(r"[0-9]{8}T[0-9]{6}Z\." + re.escape(n), name) for n in names):
                if name.split(".", 1)[0] not in args[1:]:
                    print(name)
    elif op == "compare":
        with open(args[0], "rb") as left, open(args[1], "rb") as right:
            if digest(left) != digest(right):
                raise ValueError("files differ")
    elif op == "check":
        with open(args[0]) as sums:
            rows = sums.read().splitlines()
        if not rows:
            raise ValueError("empty checksum list")
        for row in rows:
            expected, name = row.split("  ", 1)
            with open(name, "rb") as stream:
                if digest(stream) != expected:
                    raise ValueError("checksum mismatch: " + name)
    else:
        raise ValueError("unknown operation: " + op)


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (OSError, ValueError) as error:
        sys.exit(str(error))
