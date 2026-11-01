#!/usr/bin/env python3
"""Compile/run only the bounded Java protocol model, never Gradle or Kadb.

Example:
python3 run_binding_probe.py --jdk /path/to/jdk17 --maven /path/to/candidate/maven
An exact kotlin-stdlib 2.2.21 JAR may be supplied with --stdlib; otherwise the
fixed version is fetched from Maven Central into the temporary build directory.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
VERSION = "0.1.0-dev.59105290820b"
STDLIB_VERSION = "2.2.21"
STDLIB_URL = f"https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/{STDLIB_VERSION}/kotlin-stdlib-{STDLIB_VERSION}.jar"


def identity(path):
    return {"path": str(path), "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--maven", type=Path, required=True)
    parser.add_argument("--stdlib", type=Path)
    args = parser.parse_args()
    jars = [args.maven/"io/github/flyfish233"/module/VERSION/f"{module}-{VERSION}.jar"
            for module in ["spake2-legacy-jvm", "spake2-primitives-jvm"]]
    for jar in jars:
        if not jar.is_file():
            raise ValueError(f"Missing candidate JAR: {jar}")
    record = {"startedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "sourceCandidate": "59105290820bcbfd4e0336a2e7713006a461527f", "version": VERSION,
              "scope": "Actual published Legacy/Primitives candidate JVM bytecode plus JCE protocol model; synthetic 64-byte TLS exporters; no Kadb/TLS/platform provider executed",
              "platform": platform.platform(), "candidateJars": [identity(jar) for jar in jars],
              "source": identity(HERE/"KadbBindingProbe.java"), "runner": identity(Path(__file__).resolve())}
    with tempfile.TemporaryDirectory(prefix="spake2-r3-kadb-binding-") as directory:
        temporary = Path(directory)
        stdlib = args.stdlib
        if stdlib is None:
            stdlib = temporary/f"kotlin-stdlib-{STDLIB_VERSION}.jar"
            stdlib.write_bytes(urllib.request.urlopen(STDLIB_URL, timeout=30).read())
        record["stdlib"] = identity(stdlib)
        record["stdlib"]["version"] = STDLIB_VERSION
        record["stdlib"]["downloadUrl"] = STDLIB_URL if args.stdlib is None else None
        classpath = os.pathsep.join(map(str, jars + [stdlib]))
        compile_cmd = [str(args.jdk/"bin/javac"), "--release", "17", "-cp", classpath,
                       "-d", str(temporary), str(HERE/"KadbBindingProbe.java")]
        compiled = subprocess.run(compile_cmd, capture_output=True, text=True, timeout=60)
        (HERE/"binding-javac.log").write_text(compiled.stdout + compiled.stderr)
        record["compileCommand"] = compile_cmd
        record["compileExitCode"] = compiled.returncode
        if compiled.returncode:
            record["result"] = "FAIL_COMPILE"
        else:
            run_cmd = [str(args.jdk/"bin/java"), "-cp", str(temporary)+os.pathsep+classpath, "KadbBindingProbe"]
            executed = subprocess.run(run_cmd, capture_output=True, text=True, timeout=60)
            (HERE/"binding-jvm.log").write_text(executed.stdout + executed.stderr)
            record["runCommand"] = run_cmd
            record["runExitCode"] = executed.returncode
            record["groups"] = [line for line in executed.stdout.splitlines() if line.startswith("PASS ")]
            record["result"] = "PASS" if executed.returncode == 0 and len(record["groups"]) == 5 and "RESULT PASS groups=5" in executed.stdout else "FAIL_EXECUTION"
        record["finishedAtUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (HERE/"binding-result.json").write_text(json.dumps(record, indent=2)+"\n")
        print(json.dumps({"result":record["result"],"groups":record.get("groups",[])},indent=2))
        if record["result"] != "PASS":
            raise SystemExit(1)


if __name__ == "__main__":
    main()
