#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_consumer_test_results import EXPECTED_CASES, verify_case_identities


class ConsumerResultGateTest(unittest.TestCase):
    def test_complete_host_and_native_identities_pass(self):
        for module, expected in EXPECTED_CASES.items():
            verify_case_identities(module, expected)
            verify_case_identities(module, {(c, n + "[linuxX64]") for c, n in expected})

    def test_missing_method_fails(self):
        for module, expected in EXPECTED_CASES.items():
            with self.assertRaises(ValueError):
                verify_case_identities(module, set(list(expected)[1:]))

    def test_duplicate_normalized_method_fails(self):
        expected = EXPECTED_CASES["legacy-client"]
        c, n = next(iter(expected))
        with self.assertRaises(ValueError):
            verify_case_identities("legacy-client", expected | {(c, n + "[linuxX64]")})

    def test_wrong_class_or_malformed_name_fails(self):
        expected = EXPECTED_CASES["rfc-client"]
        with self.assertRaises(ValueError):
            verify_case_identities("rfc-client", {("wrong.Class", n) for c, n in expected})
        with self.assertRaises(ValueError):
            verify_case_identities("rfc-client", {(c, n + "[linuxX64][extra]") for c, n in expected})


if __name__ == "__main__":
    unittest.main()
