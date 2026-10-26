#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Compile actual commonMain Kotlin sources and measure complete JVM exchanges.

Requires project dependencies already downloaded by Gradle. This tool and its
Java management imports are not production sources or runtime dependencies.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
import pathlib
import platform
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gradle-cache", type=pathlib.Path, default=pathlib.Path(os.environ.get("GRADLE_USER_HOME", pathlib.Path.home() / ".gradle")))
    parser.add_argument("--java", default="java")
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("build/performance"))
    parser.add_argument("--warmups", type=int, default=3)
    parser.add_argument("--measurements", type=int, default=10)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    cache = args.gradle_cache / "caches/modules-2/files-2.1"
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    def jar(group, artifact, version):
        matches = list((cache / group / artifact / version).glob("*/*.jar"))
        if not matches:
            raise SystemExit(f"Missing cached {artifact}:{version}; run the project's jvmTest with Gradle first")
        return matches[0]

    compiler_jars = [jar("org.jetbrains.kotlin", artifact, "2.2.21") for artifact in
                     ["kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime", "kotlin-daemon-embeddable"]]
    compiler_jars += [jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                      jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")]
    annotations = list((cache / "org.jetbrains" / "annotations").glob("*/*/*.jar"))
    if not annotations:
        raise SystemExit("Missing cached JetBrains annotations; run Gradle jvmTest first")
    compiler_jars.append(sorted(annotations)[-1])
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    source_roots = [root / relative for relative in
                    ["primitives/src/commonMain", "src/commonMain", "legacy/src/commonMain"]]
    source_files = sorted(p for source_root in source_roots for p in source_root.rglob("*.kt"))
    source_files.append(root / "tools/JvmPerformanceProbe.kt")
    if len(source_files) < 10:
        raise SystemExit("Expected split commonMain source modules were not found")
    manifest = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files}
    (output / "source-sha256.json").write_text(json.dumps(manifest, indent=2) + "\n")

    with tempfile.TemporaryDirectory(prefix="spake2-perf-") as temporary:
        work = pathlib.Path(temporary)
        sources = []
        for i, original in enumerate(source_files):
            copied = work / f"{i}-{original.name}"
            copied.write_bytes(original.read_bytes())
            if hashlib.sha256(copied.read_bytes()).hexdigest() != manifest[str(original.relative_to(root))]:
                raise SystemExit("Source changed while taking the performance snapshot; retry")
            sources.append(copied)
        artifact = work / "performance.jar"
        classpath = lambda paths: os.pathsep.join(map(str, paths))
        command = [args.java, "-cp", classpath(compiler_jars), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                   "-no-stdlib", "-no-reflect", "-jvm-target", "1.8",
                   "-opt-in=io.github.flyfish233.spake2.InternalSpake2Api",
                   "-classpath", str(stdlib), "-d", str(artifact)] + list(map(str, sources))
        compiled = subprocess.run(command, text=True, capture_output=True)
        (output / "compiler.log").write_text(compiled.stdout + compiled.stderr)
        if compiled.returncode:
            print(compiled.stdout + compiled.stderr)
            raise SystemExit(compiled.returncode)
        # Fixed heap/collector, single calling thread. No platform crypto providers.
        # The tool's @file:JvmName keeps the entry point stable for copied snapshot filenames.
        run_command = [args.java, "-Xms128m", "-Xmx512m", "-XX:+UseG1GC", "-cp", classpath([artifact, stdlib]),
                       "io.github.flyfish233.spake2.tools.JvmPerformanceProbeKt", str(args.warmups), str(args.measurements)]
        result = subprocess.run(run_command, text=True, capture_output=True)
        (output / "probe.jsonl").write_text(result.stdout)
        (output / "runtime-stderr.log").write_text(result.stderr)
        if result.returncode:
            print(result.stdout + result.stderr)
            raise SystemExit(result.returncode)
        records = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
        cpu = "unavailable"
        cpuinfo = pathlib.Path("/proc/cpuinfo")
        if cpuinfo.exists():
            cpu = next((line.split(":", 1)[1].strip() for line in cpuinfo.read_text().splitlines()
                        if line.startswith("model name")), cpu)
        report = {"executedAtUtc": datetime.now(timezone.utc).isoformat(), "compiler": "Kotlin 2.2.21",
                  "heapArguments": ["-Xms128m", "-Xmx512m", "-XX:+UseG1GC"], "cpu": cpu,
                  "platform": platform.platform(), "python": platform.python_version(),
                  "warmups": args.warmups, "measurements": args.measurements,
                  "fixtureOnly": True, "records": records}
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        for record in records:
            if record["kind"] in ["environment", "summary", "completion"]:
                print(json.dumps(record))
        print(f"Saved actual measurements and source checksums to {output}")


if __name__ == "__main__":
    main()
