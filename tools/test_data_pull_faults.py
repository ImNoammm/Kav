"""The pull (site/scripts/data-pull.sh) with one external tool broken at a time: absent, exiting 127, printing nothing,
printing garbage, or failing at each of its calls (from that call on, or at that call only), in three scenarios (a new
night, a night put right, nothing to do), under sh and, where there is one, dash. A run passes when it exits non-zero
with the data tree byte for byte as it was, or exits 0 with exactly the tree a run with every tool working leaves. Every failure must leave the exact original tree; every success must leave the exact clean result.
    cd tools && python3 -m unittest test_data_pull_faults      (KAV_TEST_PULL=<script> tests another copy of it)"""
import os
import shutil
import subprocess
import tempfile
import unittest

import test_data_pull as pull
from test_data_pull import SCRIPT, manifest, night, payload

TOOLS = ("tr", "mv", "flock", "rm")
# Every command the script runs, to make a PATH of them with one replaced or left out.
COMMANDS = ("sh", "dash", "jq", "date", "stat", "mkdir", "id", "rm", "mktemp", "ls", "base64", "openssl", "flock", "curl",
            "dd", "wc", "cmp", "cut", "sha256sum", "touch", "ln", "chmod", "mv", "du", "tr", "grep", "cat")
WRAPPER = """#!%(sh)s
# Stands in for %(tool)s: counts its calls; before call %(at)s runs the real one, then breaks as %(mode)s says.
n=$(( $(%(wc)s -l < "%(count)s") + 1 ))
echo x >> "%(count)s"
case %(mode)s in
  count) exec %(real)s "$@" ;;
  once-*) [ "$n" -eq %(at)s ] || exec %(real)s "$@" ;;
  *) [ "$n" -ge %(at)s ] || exec %(real)s "$@" ;;
esac
case %(mode)s in
  fail|once-fail) exit 1 ;;
  e127) exit 127 ;;
  nothing|once-nothing) exit 0 ;;
  garbage) echo garbage; exit 0 ;;
esac
"""


def tree(root):
    """Every path under root: its kind, mode and bytes or link target (a folder's link count left out)."""
    out = {}
    for d, dirs, files in os.walk(root):
        for n in sorted(dirs + files):
            p = os.path.join(d, n)
            st = os.lstat(p)
            rel = os.path.relpath(p, root)
            if os.path.islink(p):
                out[rel] = ("link", os.readlink(p))
            elif os.path.isdir(p):
                out[rel] = ("dir", oct(st.st_mode & 0o7777))
            else:
                with open(p, "rb") as f:
                    out[rel] = ("file", oct(st.st_mode & 0o7777), st.st_nlink, f.read())
    return out




class Faults(pull.PullTest):
    def scenario(self, name):
        """A data tree the real script made, and a release: "new" (nights 12 and 13 served, the release names 14),
        "repair" (13 served with a byte spoiled in place, the release names 13), "same" (13 served and named)."""
        self.setUp()
        if name == "new":
            self.install(12)
            self.install(13)
            files = payload("14")
            self.release.publish(manifest(files, night(14)), files, self.key)
        else:
            files = self.install(13)
            if name == "repair":
                self.spoil("stops.csv.gz", files["stops.csv.gz"][:4], b"XXXX")
            self.release.publish(manifest(files, night(13)), files, self.key)
        saved = os.path.join(self.dir, "saved")
        subprocess.run(["rm", "-rf", saved], check=True)
        subprocess.run(["cp", "-a", self.data, saved], check=True)  # cp -a keeps latest's hard links to its night
        return saved

    def run_with(self, shell, path):
        return subprocess.run([shell, SCRIPT, self.data], capture_output=True, text=True, timeout=120, stdin=subprocess.DEVNULL,
                              env=dict(os.environ, PATH=path, KAV_DATA_RELEASE=self.release.url,
                                       KAV_DATA_PUBLIC_KEY=self.public))

    def path_with(self, tool, mode, at=1):
        """A PATH of every command, but tool absent (mode "absent") or a wrapper breaking it."""
        bin_ = tempfile.mkdtemp(dir=self.dir, prefix="bin-")
        for c in COMMANDS:
            real = shutil.which(c)
            if real and not (c == tool and mode == "absent"):
                os.symlink(real, os.path.join(bin_, c))
        if mode != "absent":
            os.remove(os.path.join(bin_, tool))
            self.count = os.path.join(bin_, ".count")
            open(self.count, "w").close()
            with open(os.path.join(bin_, tool), "w") as f:
                f.write(WRAPPER % {"sh": shutil.which("sh"), "tool": tool, "at": at, "mode": mode, "real": shutil.which(tool),
                                   "wc": shutil.which("wc"), "count": self.count})
            os.chmod(os.path.join(bin_, tool), 0o755)
        return bin_

    def restore(self, saved):
        subprocess.run(["rm", "-rf", self.data], check=True)
        subprocess.run(["cp", "-a", saved, self.data], check=True)

    def test_each_tool_broken_loses_nothing_served(self):
        self.maxDiff = None
        shells = ["sh"] + (["dash"] if shutil.which("dash") else [])
        bad = []
        runs = 0
        for scenario in ("new", "repair", "same"):
            saved = self.scenario(scenario)
            self.restore(saved)
            before = tree(self.data)
            clean_run = self.run_with("sh", os.environ["PATH"])
            self.assertEqual(clean_run.returncode, 0, clean_run.stderr)
            clean = tree(self.data)
            for tool in TOOLS:
                self.restore(saved)
                counted = self.run_with("sh", self.path_with(tool, "count"))
                self.assertEqual(counted.returncode, 0, counted.stderr)
                with open(self.count) as f:
                    calls = len(f.read().split())
                modes = [("absent", 1), ("e127", 1), ("nothing", 1), ("garbage", 1)]
                modes += [(m, k) for k in range(1, calls + 1) for m in ("fail", "once-fail", "once-nothing")]
                for shell in shells:
                    for mode, at in modes:
                        self.restore(saved)
                        run = self.run_with(shell, self.path_with(tool, mode, at))
                        after = tree(self.data)
                        runs += 1
                        ok = (run.returncode != 0 and after == before) or (run.returncode == 0 and after == clean)
                        if not ok:
                            bad.append("%s, %s %s at call %d, %s: exit %d: %s" % (
                                scenario, tool, mode, at, shell, run.returncode, run.stderr.strip()[-160:]))
        self.assertEqual(bad, [], "%d of %d runs" % (len(bad), runs))


# Faults borrows PullTest's release, key and helpers, not its tests (test_data_pull runs those).
for _name in dir(pull.PullTest):
    if _name.startswith("test_"):
        setattr(Faults, _name, None)

if __name__ == "__main__":
    unittest.main()
