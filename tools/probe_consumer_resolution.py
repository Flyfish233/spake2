#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Run four real Gradle dependency-graph probes against the production gate.

Put --gradle-command last, for example:
  --gradle-command python3 /path/to/run_gradle_round2.py
No Kotlin plugin or production source is copied into the Java-library fixture.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
GATE = ROOT / "integration/consumer-fixtures/verify-resolution.gradle.kts"
CASES = (
    ("valid-maven-with-plugin-project", None),
    ("project-substitution", "Published SPAKE2 dependency was substituted with a project"),
    ("wrong-protocol-edge", "Unexpected published dependency or protocol edge"),
    ("missing-published-module", "Published SPAKE2 dependency is unresolved"),
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def kotlin_string(value):
    return json.dumps(str(value)).replace("$", "\\$")


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.strip() + "\n")


def create_fixture(directory, repository):
    write(directory / "settings.gradle.kts", '''
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    url = uri(@@REPOSITORY@@)
                    metadataSources { gradleMetadata(); mavenPom() }
                }
            }
            filter { includeGroup("io.github.flyfish233") }
        }
        mavenCentral()
    }
}
rootProject.name = "spake2-resolution-functional-probe"
include(":legacy-client", ":plugin-metadata", ":substitute")
'''.replace("@@REPOSITORY@@", kotlin_string(repository.as_uri())))
    write(directory / "build.gradle.kts", '''
subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
''')
    write(directory / "gradle.properties", "org.gradle.jvmargs=-Xmx512m\norg.gradle.workers.max=2")
    write(directory / "plugin-metadata/build.gradle.kts", '''
val metadata = configurations.create("metadata") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(metadata.name, file("metadata.txt"))
''')
    write(directory / "plugin-metadata/metadata.txt", "Unrelated plugin project metadata; no SPAKE2 classes.")
    write(directory / "substitute/build.gradle.kts", "// Empty Java library: deliberately not the published SPAKE2 artifact.")
    write(directory / "legacy-client/build.gradle.kts", '''
import org.gradle.api.artifacts.result.ResolvedDependencyResult

val candidate = providers.gradleProperty("spake2Version").get()
val probeCase = providers.gradleProperty("probeCase").get()
val requestedModule = when (probeCase) {
    "valid-maven-with-plugin-project", "project-substitution" -> "spake2-legacy-jvm"
    "wrong-protocol-edge" -> "spake2-jvm"
    "missing-published-module" -> "spake2-legacy-jvm-missing-probe"
    else -> error("Unknown probe case: $probeCase")
}
dependencies {
    // Keep a real allowed artifact in every graph. A broken checker that hides
    // substituted/failed edges must not pass on its selected > 0 condition.
    add("implementation", "io.github.flyfish233:spake2-primitives-jvm:$candidate")
    add("implementation", "io.github.flyfish233:$requestedModule:$candidate")
}
if (probeCase == "project-substitution") {
    configurations.named("compileClasspath") {
        resolutionStrategy.dependencySubstitution {
            substitute(module("io.github.flyfish233:spake2-legacy-jvm"))
                .using(project(":substitute"))
        }
    }
}
val pluginMetadataProbe = configurations.create("pluginMetadataProbe") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies.add(pluginMetadataProbe.name,
    dependencies.project(mapOf("path" to ":plugin-metadata", "configuration" to "metadata")))

apply(from = @@GATE@@)
val resolveProbeGraphs = tasks.register("resolveProbeGraphs") {
    doLast {
        for (name in listOf("compileClasspath", "pluginMetadataProbe")) {
            val configuration = configurations.getByName(name)
            // Graph access retains UnresolvedDependencyResult entries; do not
            // resolve files or throw before the production checker sees them.
            val edges = configuration.incoming.resolutionResult.allDependencies
            logger.lifecycle("PROBE_GRAPH $name ${configuration.state} ${edges.size}")
            edges.forEach { edge ->
                val selected = (edge as? ResolvedDependencyResult)?.selected?.id?.displayName ?: "UNRESOLVED"
                logger.lifecycle("PROBE_EDGE $name ${edge.requested.displayName} -> $selected")
            }
        }
    }
}
tasks.named("verifyPublishedDependencies") { dependsOn(resolveProbeGraphs) }
'''.replace("@@GATE@@", kotlin_string(GATE)))


def verify_positive_report(path, version):
    require(path.is_file(), "Successful gate did not produce Maven selection evidence")
    lines = path.read_text().splitlines()
    require(lines and lines[0] == "configuration\tcoordinate\tvariant\tartifact", "Unexpected Maven selection report header")
    modules = set()
    for line in lines[1:]:
        fields = line.split("\t")
        require(len(fields) == 4, "Malformed Maven selection evidence row")
        coordinate = fields[1].split(":")
        require(len(coordinate) == 3 and coordinate[0] == "io.github.flyfish233" and coordinate[2] == version,
                "Wrong coordinate in positive Maven selection evidence")
        modules.add(coordinate[1])
    require(bool(modules & {"spake2-legacy", "spake2-legacy-jvm"}) and
            bool(modules & {"spake2-primitives", "spake2-primitives-jvm"}),
            "Positive control must observe both actual Legacy and Primitives artifacts")
    return sorted(modules)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--work-directory", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--gradle-command", nargs="+", required=True)
    args = parser.parse_args()
    repository, output = args.repository.resolve(), args.output_directory.resolve()
    require(repository.is_dir(), f"Missing Maven repository: {repository}")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", args.version) is not None, "An exact publication version is required")
    require(GATE.is_file(), f"Missing production resolution checker: {GATE}")
    inputs = {}
    for module in ("spake2-jvm", "spake2-primitives-jvm", "spake2-legacy-jvm"):
        for extension in ("pom", "module", "jar"):
            path = repository / "io/github/flyfish233" / module / args.version / f"{module}-{args.version}.{extension}"
            require(path.is_file(), f"Missing actual probe input: {path}")
            inputs[str(path.relative_to(repository))] = sha256(path)
    missing = repository / "io/github/flyfish233/spake2-legacy-jvm-missing-probe" / args.version
    require(not missing.exists(), f"The deliberate missing coordinate unexpectedly exists: {missing}")
    args.work_directory.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    require(not (output / "report.json").exists(), "Use a fresh output directory; an earlier report must not be overwritten")
    fixture = Path(tempfile.mkdtemp(prefix="consumer-resolution-", dir=args.work_directory.resolve()))
    create_fixture(fixture, repository)
    gate_before = sha256(GATE)
    report = {"startedAtUtc": datetime.now(timezone.utc).isoformat(), "repository": str(repository),
              "version": args.version, "fixtureDirectory": str(fixture), "gateScript": str(GATE),
              "gateSha256Before": gate_before, "gradleCommand": args.gradle_command,
              "publicationInputSha256": inputs, "cases": [],
              "scope": "Four actual Gradle graph/artifact-resolution runs using the exact production checker. "
                       "No KMP plugins, producer includeBuild, crypto source copy or cryptographic test execution."}
    selection = fixture / "legacy-client/build/reports/published-dependencies.tsv"
    for name, expected_diagnostic in CASES:
        if selection.exists():
            selection.unlink()  # This fixture's prior result was copied below.
        command = args.gradle_command + ["-p", str(fixture), "--no-daemon", "--no-configuration-cache", "--console=plain",
                                         f"-Pspake2Version={args.version}", f"-PprobeCase={name}",
                                         ":legacy-client:verifyPublishedDependencies"]
        started = time.monotonic()
        log = output / f"{name}.log"
        print(f"Running actual Gradle case: {name}", flush=True)
        with log.open("w") as stream:
            result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        text = log.read_text(errors="replace")
        case = {"name": name, "command": command, "exitCode": result.returncode,
                "elapsedSeconds": round(time.monotonic() - started, 3), "log": log.name,
                "expectedDiagnostic": expected_diagnostic}
        if expected_diagnostic is None:
            case["passed"] = result.returncode == 0
            if case["passed"]:
                try:
                    case["observedMavenModules"] = verify_positive_report(selection, args.version)
                    require("PROBE_EDGE pluginMetadataProbe project :plugin-metadata -> project :plugin-metadata" in text,
                            "The unrelated project component was not exercised by the positive control")
                    (output / "valid-maven-selections.tsv").write_bytes(selection.read_bytes())
                except ValueError as error:
                    case["passed"] = False
                    case["validationError"] = str(error)
        else:
            case["passed"] = result.returncode != 0 and expected_diagnostic in text
        report["cases"].append(case)
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"{name}: exit={result.returncode}, expected outcome verified={case['passed']}", flush=True)
    report["gateSha256After"] = sha256(GATE)
    report["gateUnchangedDuringProbe"] = report["gateSha256After"] == gate_before
    report["completedAtUtc"] = datetime.now(timezone.utc).isoformat()
    report["status"] = "passed" if report["gateUnchangedDuringProbe"] and all(case["passed"] for case in report["cases"]) else "failed"
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Four-case functional probe: {report['status']}; report: {output / 'report.json'}", flush=True)
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError) as error:
        print(f"Consumer resolution probe failed: {error}", file=sys.stderr)
        raise SystemExit(1)
