#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Negative evidence tests: these checks must also fail closed under python -O."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "check_android_art_results", Path(__file__).resolve().parents[1] / "check_android_art_results.py"
)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class AndroidResultGateTest(unittest.TestCase):
    def check_xml(self, content):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / "TEST-case.xml"
            path.write_text(content)
            return gate.read_reports([path], root)

    def test_valid_case_is_retained(self):
        cases, reports = self.check_xml(
            '<testsuite tests="1"><testcase classname="Example" name="knownAnswer" time="0.2"/></testsuite>'
        )
        self.assertEqual(set(cases), {("Example", "knownAnswer")})
        self.assertEqual(len(reports), 1)

    def test_bad_case_child_fails_even_when_suite_totals_claim_success(self):
        for outcome in ("failure", "error", "skipped"):
            with self.subTest(outcome=outcome), self.assertRaises(ValueError):
                self.check_xml(
                    '<testsuite tests="1" failures="0" errors="0" skipped="0">'
                    '<testcase classname="Example" name="knownAnswer"><' + outcome + '/></testcase></testsuite>'
                )

    def test_failure_in_suite_totals_is_not_ignored(self):
        with self.assertRaises(ValueError):
            self.check_xml('<testsuite tests="1" failures="1"><testcase classname="Example" name="knownAnswer"/></testsuite>')

    def test_duplicate_test_identity_fails(self):
        with self.assertRaises(ValueError):
            self.check_xml(
                '<testsuite tests="2"><testcase classname="Example" name="knownAnswer"/>'
                '<testcase classname="Example" name="knownAnswer"/></testsuite>'
            )

    def test_declared_count_must_match_cases(self):
        with self.assertRaises(ValueError):
            self.check_xml('<testsuite tests="2"><testcase classname="Example" name="knownAnswer"/></testsuite>')

    def test_missing_identity_and_empty_results_fail(self):
        with self.assertRaises(ValueError):
            self.check_xml('<testsuite tests="1"><testcase name="knownAnswer"/></testsuite>')
        with self.assertRaises(ValueError):
            gate.read_reports([], Path("."))


if __name__ == "__main__":
    unittest.main()
