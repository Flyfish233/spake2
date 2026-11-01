#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Require every Android host common-test identity to execute on ART as well.

The Android host and instrumented compilations consume the same commonTest
sources. This guard catches accidentally disconnected source sets or test
filters. Instrumentation adds one explicit runtime-attestation method.
"""

import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_reports(paths, root):
    cases = {}
    reports = []
    for path in sorted(paths):
        data = path.read_bytes()
        suite = ET.fromstring(data)
        require(suite.tag == "testsuite", (path, suite.tag))
        children = suite.findall("testcase")
        require(len(children) == int(suite.get("tests", "-1")), (path, "Inconsistent test count"))
        for category in ("failures", "errors", "skipped"):
            require(int(suite.get(category, "0")) == 0, (path, category))
        report = {
            "path": path.relative_to(root).as_posix(),
            "sha256": hashlib.sha256(data).hexdigest(),
            "suite": suite.attrib,
            "cases": [],
        }
        for case in children:
            key = (case.get("classname"), case.get("name"))
            require(all(key) and key not in cases, (path, key, "Missing or duplicate test identity"))
            require(not any(case.find(tag) is not None for tag in ("failure", "error", "skipped")), (path, key))
            entry = {"classname": key[0], "name": key[1], "time": case.get("time"), "outcome": "passed"}
            cases[key] = entry
            report["cases"].append(entry)
        reports.append(report)
    require(bool(cases), "No test cases found")
    return cases, reports


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--api", type=int, required=True)
    parser.add_argument("--consumer-root", type=Path)
    parser.add_argument("--consumer-version")
    parser.add_argument("--output", type=Path, default=Path("build/art-evidence/test-summary.json"))
    args = parser.parse_args()
    root = args.root.resolve()
    modules = {}
    for name, relative in (("rfc", "."), ("primitives", "primitives"), ("legacy", "legacy")):
        module = root / relative
        host, host_reports = read_reports((module / "build/test-results/testDebugUnitTest").glob("**/TEST-*.xml"), root)
        art, art_reports = read_reports((module / "build/outputs/androidTest-results/connected").glob("**/TEST-*.xml"), root)
        missing = sorted(set(host) - set(art))
        require(not missing, (name, "Common tests missing from ART", missing))
        extras = sorted(set(art) - set(host))
        expected_extra = [("io.github.flyfish233.spake2.AndroidArtRuntimeTest", "runsInsideTheRequestedAndroidRuntime")] if name == "primitives" else []
        require(extras == expected_extra, (name, "Unexpected instrumentation-only tests", extras))
        modules[name] = {
            "host_common_methods": len(host),
            "art_methods": len(art),
            "all_host_common_cases_executed_on_art": True,
            "instrumentation_only": [list(item) for item in extras],
            "host_reports": host_reports,
            "art_reports": art_reports,
        }
    output = args.output if args.output.is_absolute() else root / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    summary = {
        "expected_api": args.api,
        "execution_scope": "Android emulator instrumentation on ART; not host JVM or physical device",
        "modules": modules,
        "totals": {
            "common_methods": sum(m["host_common_methods"] for m in modules.values()),
            "art_methods": sum(m["art_methods"] for m in modules.values()),
            "failures": 0,
            "errors": 0,
            "skipped": 0,
        },
    }
    if args.consumer_root is not None:
        consumer_root = args.consumer_root if args.consumer_root.is_absolute() else root / args.consumer_root
        consumers = {}
        for name in ("rfc-client", "legacy-client"):
            module = consumer_root / name
            host, host_reports = read_reports((module / "build/test-results/testDebugUnitTest").glob("**/TEST-*.xml"), root)
            art, art_reports = read_reports((module / "build/outputs/androidTest-results/connected").glob("**/TEST-*.xml"), root)
            require(set(art) == set(host), (name, "Standalone consumer host/ART test identities differ"))
            consumers[name] = {"art_methods": len(art), "host_reports": host_reports, "art_reports": art_reports}
        summary["standalone_consumer"] = {
            "requested_version": args.consumer_version,
            "modules": consumers,
            "art_methods": sum(m["art_methods"] for m in consumers.values()),
            "failures": 0,
            "errors": 0,
            "skipped": 0,
        }
    output.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({"api": args.api, "totals": summary["totals"], "output": str(output)}, indent=2))


if __name__ == "__main__":
    main()
