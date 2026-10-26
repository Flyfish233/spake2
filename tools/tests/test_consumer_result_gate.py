#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
import sys
from pathlib import Path
import json
import shutil
import subprocess
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_consumer_test_results import EXPECTED_CASES, verify_case_identities


class ConsumerResultGateTest(unittest.TestCase):
    def test_actual_gradle_96_native_reports_pass(self):
        """Real KGP 2.4.20 reports prefix classes with their owning task."""
        fixture = Path(__file__).parent / "fixtures/gradle-9.6-kotlin-2.4.20"
        checker = Path(__file__).resolve().parents[1] / "check_consumer_test_results.py"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "summary.json"
            for task in ("macosArm64Test", "iosSimulatorArm64Test"):
                for module in EXPECTED_CASES:
                    target = root / module / "build/test-results" / task / ("TEST-" + module + ".xml")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(fixture / task / (module + ".xml"), target)
            command = [sys.executable] + ([] if __debug__ else ["-O"]) + [
                str(checker), "--root", str(root),
                "--tasks", "macosArm64Test", "iosSimulatorArm64Test",
                "--version", "0.1.0-dev.027d6dc00f2c",
                "--revision", "027d6dc00f2c92502ca98b878d1940a358590be5",
                "--output", str(output),
            ]
            result = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            summary = json.loads(output.read_text())
            self.assertEqual(10, summary["total_method_executions"])
            for task in summary["tasks"].values():
                self.assertEqual(5, task["methods"])
            for status in ("failures", "errors", "skipped"):
                self.assertEqual(0, summary[status])

    def test_complete_host_and_native_identities_pass(self):
        for module, expected in EXPECTED_CASES.items():
            verify_case_identities(module, expected, "jvmTest")
            verify_case_identities(module, {(c, n + "[linuxX64]") for c, n in expected}, "linuxX64Test")

    def test_exact_requested_task_prefix_passes(self):
        for module, expected in EXPECTED_CASES.items():
            verify_case_identities(module, {
                ("macosArm64Test." + c, n + "[macosArm64]") for c, n in expected
            }, "macosArm64Test")

    def test_wrong_or_repeated_task_prefix_fails(self):
        for prefix in ("iosSimulatorArm64Test.", "arbitrary.",
                       "macosArm64Test.macosArm64Test."):
            for module, expected in EXPECTED_CASES.items():
                with self.subTest(prefix=prefix, module=module), self.assertRaises(ValueError):
                    verify_case_identities(module, {
                        (prefix + c, n + "[macosArm64]") for c, n in expected
                    }, "macosArm64Test")

    def test_prefixed_and_unprefixed_duplicate_fails(self):
        for module, expected in EXPECTED_CASES.items():
            c, n = next(iter(expected))
            with self.assertRaises(ValueError):
                verify_case_identities(module, expected | {
                    ("macosArm64Test." + c, n + "[macosArm64]")
                }, "macosArm64Test")

    def test_missing_method_fails(self):
        for module, expected in EXPECTED_CASES.items():
            with self.assertRaises(ValueError):
                verify_case_identities(module, set(list(expected)[1:]), "jvmTest")

    def test_duplicate_normalized_method_fails(self):
        expected = EXPECTED_CASES["legacy-client"]
        c, n = next(iter(expected))
        with self.assertRaises(ValueError):
            verify_case_identities("legacy-client", expected | {(c, n + "[linuxX64]")}, "linuxX64Test")

    def test_wrong_class_or_malformed_name_fails(self):
        expected = EXPECTED_CASES["rfc-client"]
        with self.assertRaises(ValueError):
            verify_case_identities("rfc-client", {("wrong.Class", n) for c, n in expected}, "jvmTest")
        with self.assertRaises(ValueError):
            verify_case_identities("rfc-client", {(c, n + "[linuxX64][extra]") for c, n in expected}, "linuxX64Test")


if __name__ == "__main__":
    unittest.main()
