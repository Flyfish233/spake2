#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Reject incomplete or malformed independent-oracle evidence before comparison."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import shutil
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "legacy_regenerate_oracle.py"
spec = importlib.util.spec_from_file_location("legacy_regenerate_oracle", SCRIPT)
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


class OracleProtocolTest(unittest.TestCase):
    request = ["alice", "01", "02", "03", "00" * 64, "0", "04" * 32]
    successful = ["11" * 32, "22" * 32, "33" * 32, "44" * 64, "1", "55" * 64]

    def parse(self, replies, requests=None):
        stdout = "\n".join("|".join(row) for row in replies) + "\n"
        completed = subprocess.CompletedProcess(["test-oracle"], 0, stdout, "")
        with patch.object(oracle.subprocess, "run", return_value=completed):
            return oracle.run(Path("test-oracle"), requests or [self.request])

    def test_rejects_every_truncated_record(self):
        # The old zip-based comparator could check a correct prefix and omit
        # the missing key/status/scalar assertions without an exception.
        for fields in range(6):
            with self.subTest(fields=fields):
                with self.assertRaises(ValueError):
                    self.parse([self.successful[:fields]])

    def test_rejects_extra_columns(self):
        with self.assertRaises(ValueError):
            self.parse([self.successful + ["unexpected"]])

    def test_rejects_missing_or_extra_records(self):
        for replies in [[], [self.successful, self.successful]]:
            with self.subTest(count=len(replies)):
                with self.assertRaises(ValueError):
                    self.parse(replies)

    def test_rejects_invalid_field_encodings_and_status(self):
        for index, value in [
            (0, "11" * 31), (1, "22" * 33), (2, "gg" * 32),
            (3, "44" * 63), (0, "AA" * 32), (4, "2"),
            (4, "01"), (5, ""), (5, "55" * 63),
        ]:
            invalid = self.successful.copy()
            invalid[index] = value
            with self.subTest(field=index, length=len(value)):
                with self.assertRaises(ValueError):
                    self.parse([invalid])
        failed_with_key = self.successful.copy()
        failed_with_key[4] = "0"
        with self.assertRaises(ValueError):
            self.parse([failed_with_key])

    def test_preserves_complete_success_and_failure_records(self):
        failed = self.successful.copy()
        failed[4:] = ["0", ""]
        replies = [self.successful, failed]
        self.assertEqual(replies, self.parse(replies, [self.request, self.request]))

    def test_optimized_python_cannot_suppress_verification_assertions(self):
        # A structurally valid oracle that reports wrong bytes must fail under
        # -O too. Work only on temporary fixture copies: the old verifier
        # incorrectly wrote a success report when all its asserts disappeared.
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            vectors = directory / "vectors"
            vectors.mkdir()
            for name in ("boringssl-spake2-vectors.json", "boringssl-spake2-fuzz-vectors.jsonl"):
                shutil.copyfile(oracle.VECTORS / name, vectors / name)
            executable = directory / "oracle-identity"
            executable.write_bytes(b"test-only oracle substitute")
            program = "\n".join([
                "import importlib.util, pathlib, sys",
                "spec = importlib.util.spec_from_file_location('verifier', sys.argv[1])",
                "module = importlib.util.module_from_spec(spec)",
                "spec.loader.exec_module(module)",
                "module.VECTORS = pathlib.Path(sys.argv[2])",
                "module.run = lambda executable, rows: [['11'*32, '22'*32, '33'*32, '44'*64, '1', '55'*64] for row in rows]",
                "sys.argv = [sys.argv[1], '--oracle', sys.argv[3]]",
                "module.main()",
            ])
            result = subprocess.run(
                [sys.executable, "-O", "-c", program, str(SCRIPT), str(vectors), str(executable)],
                text=True, capture_output=True,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Oracle mismatch", result.stderr)
            self.assertFalse((vectors / "oracle-regeneration-report.json").exists())


if __name__ == "__main__":
    unittest.main()
