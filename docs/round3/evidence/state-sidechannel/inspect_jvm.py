#!/usr/bin/env python3
"""Retain javap output for actual JVM classes or CI Maven JARs; no timing claim."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
CLASSES = {
    "io.github.flyfish233.spake2.internal.math.ModMath": "primitives",
    "io.github.flyfish233.spake2.internal.math.P256": "primitives",
    "io.github.flyfish233.spake2.internal.math.Edwards25519": "primitives",
    "io.github.flyfish233.spake2.internal.crypto.Sha256": "primitives",
    "io.github.flyfish233.spake2.internal.crypto.Sha512": "primitives",
    "io.github.flyfish233.spake2.internal.crypto.HmacSha256": "primitives",
    "io.github.flyfish233.spake2.internal.crypto.HkdfSha256": "primitives",
    "io.github.flyfish233.spake2.rfc.Rfc9382Core": "",
    "io.github.flyfish233.spake2.rfc.Rfc9382Session": "",
    "io.github.flyfish233.spake2.legacy.LegacySpake25519": "legacy",
    "io.github.flyfish233.spake2.legacy.LegacySpake25519$Companion": "legacy",
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--maven-root", type=Path)
    parser.add_argument("--version")
    parser.add_argument("--artifact-source-commit")
    parser.add_argument("--jdk-home", type=Path)
    args = parser.parse_args()
    javap = str(args.jdk_home / "bin/javap") if args.jdk_home else "javap"
    java = str(args.jdk_home / "bin/java") if args.jdk_home else "java"
    jars = {}
    if args.maven_root:
        if not args.version or not args.artifact_source_commit:
            parser.error("Maven inspection requires --version and --artifact-source-commit")
        for module, artifact in {"": "spake2-jvm", "primitives": "spake2-primitives-jvm", "legacy": "spake2-legacy-jvm"}.items():
            jars[module] = args.maven_root / "io/github/flyfish233" / artifact / args.version / f"{artifact}-{args.version}.jar"
        classpath = os.pathsep.join(str(p) for p in jars.values())
    else:
        classpath = os.pathsep.join(str(ROOT / m / "build/classes/kotlin/jvm/main") for m in ("", "primitives", "legacy"))
    records = []
    (OUT / "bytecode").mkdir(exist_ok=True)
    for name, module in CLASSES.items():
        relative_class = name.replace(".", "/") + ".class"
        if jars:
            with zipfile.ZipFile(jars[module]) as archive:
                class_bytes = archive.read(relative_class)
            class_file = f"{jars[module]}!/{relative_class}"
        else:
            cls = ROOT / module / "build/classes/kotlin/jvm/main" / relative_class
            if not cls.is_file():
                raise FileNotFoundError(f"Expected current Gradle output: {cls}")
            class_bytes = cls.read_bytes()
            class_file = str(cls.relative_to(ROOT))
        command = [javap, "-c", "-p", "-classpath", classpath, name]
        result = subprocess.run(command, check=True, text=True, capture_output=True)
        dest = OUT / "bytecode" / (name.rsplit(".", 1)[1].replace("$", "_") + ".txt")
        dest.write_text(result.stdout)
        records.append({"class": name, "class_file": class_file,
                        "class_sha256": hashlib.sha256(class_bytes).hexdigest(),
                        "javap_file": str(dest.relative_to(ROOT)),
                        "javap_sha256": hashlib.sha256(dest.read_bytes()).hexdigest(),
                        "command": command, "stderr": result.stderr})
    sources = {}
    for src in ROOT.rglob("*.kt"):
        if "commonMain" in src.parts and "build" not in src.parts:
            sources[str(src.relative_to(ROOT))] = hashlib.sha256(src.read_bytes()).hexdigest()
    report = {"kind": "JVM bytecode inspection input; not JIT/ART/native machine code or timing measurement",
              "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "artifact_source_commit": args.artifact_source_commit,
              "artifact_provenance": "See ../ci/candidate-download.json and ../ci/source-archive-vs-git.json; hashes alone do not authenticate a producer.",
              "jars": [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in jars.values()],
              "java": subprocess.run([java, "-version"], capture_output=True, text=True, check=True).stderr,
              "javap": subprocess.check_output([javap, "-version"], text=True).strip(),
              "platform": platform.platform(), "source_sha256": sources, "classes": records}
    (OUT / "jvm-bytecode-manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"classes": len(records), "manifest": str(OUT / "jvm-bytecode-manifest.json")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
