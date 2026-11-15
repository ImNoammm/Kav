"""A verification command that reports success without verifying must fail closed."""
import base64
import os
import shutil
import unittest
from unittest.mock import patch

import test_data_pull as pull
from test_data_pull import manifest, night, payload


class SignatureTool(pull.PullTest):
    def test_empty_success_from_verifier_cannot_admit_a_bad_signature(self):
        old = self.install(13)
        bindir = os.path.join(self.dir, "bin")
        os.mkdir(bindir)
        verifier = os.path.join(bindir, "openssl")
        with open(verifier, "w") as stream:
            stream.write('#!/bin/sh\ncase "$*" in *-verify*) exit 0 ;; esac\nexec ' + shutil.which("openssl") + ' "$@"\n')
        os.chmod(verifier, 0o755)
        with patch.dict(os.environ, PATH=bindir + os.pathsep + os.environ["PATH"]):
            run = self.pull(manifest(payload("new"), night(14)), payload("new"), signature=base64.b64encode(b"invalid"))
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual(self.latest_night(), night(13))
        self.assertEqual({name: self.served(name) for name in old}, old)


for name in dir(pull.PullTest):
    if name.startswith("test_"):
        setattr(SignatureTool, name, None)
