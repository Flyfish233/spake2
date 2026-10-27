#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Require all five independent Maven-consumer methods on every requested task."""

import argparse
import json
from pathlib import Path
import re

from check_android_art_results import read_reports, require


EXPECTED_CASES = {
    "rfc-client": {
        ("consumer.PublishedRfcTest", "officialP256PublicMessagesConfirmationsAndKey"),
        ("consumer.PublishedRfcTest", "independentEdwardsPublicMessagesConfirmationsAndKey"),
        ("consumer.PublishedRfcTest", "incorrectConfirmationIsTerminalInPublishedLibrary"),
    },
    "legacy-client": {
        ("consumer.PublishedLegacyTest", "hardenedPinnedOracleIncludingHighBitAndNulNames"),
        ("consumer.PublishedLegacyTest", "malformedInputAndDestroyConsumePublishedSession"),
    },
}


def verify_case_identities(module, cases, task):
    normalized = set()
    for classname, name in cases:
        # Gradle 9.6 native reports include the owning test task in the class.
        # Accept that exact prefix only; other task/class prefixes remain errors.
        if classname.startswith(task + "."):
            classname = classname[len(task) + 1:]
        match = re.fullmatch(r"([^\[\]]+)(?:\[[^\[\]]+\])?", name)
        require(match is not None, f"Unexpected test name: {classname}.{name}")
        key = (classname, match.group(1))
        require(key not in normalized, f"Duplicate normalized test identity: {key}")
        normalized.add(key)
    require(normalized == EXPECTED_CASES[module],
            f"Incomplete or unexpected {module} methods: {sorted(normalized)}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--tasks", nargs="+", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    require(len(set(args.tasks)) == len(args.tasks), "Duplicate task in test result request")
    require(all(re.fullmatch(r"[A-Za-z][A-Za-z0-9]+", task) for task in args.tasks), "Invalid task name")
    results = {}
    for task in args.tasks:
        modules = {}
        for module in EXPECTED_CASES:
            cases, reports = read_reports((root / module / "build/test-results" / task).glob("**/TEST-*.xml"), root)
            verify_case_identities(module, cases, task)
            modules[module] = {"methods": len(cases), "reports": reports}
        results[task] = {"methods": sum(m["methods"] for m in modules.values()), "modules": modules}
    summary = {
        "version": args.version,
        "source_revision": args.revision,
        "consumer_root": str(root),
        "tasks": results,
        "total_method_executions": sum(task["methods"] for task in results.values()),
        "failures": 0,
        "errors": 0,
        "skipped": 0,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({"tasks": args.tasks, "methods_per_task": 5, "output": str(args.output)}, indent=2))


if __name__ == "__main__":
    main()
