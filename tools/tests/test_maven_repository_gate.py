#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Fault injection for the publication acceptance gate; no Gradle/network use."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/check_maven_repository.py"
spec = importlib.util.spec_from_file_location("check_maven_repository", SCRIPT)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
VERSION = "0.1.0-dev.gate-test"
REVISION = "1234567890abcdef1234567890abcdef12345678"
MODULES = ("spake2", "spake2-primitives", "spake2-legacy")
TARGETS = ("jvm", "js", "wasmjs", "linuxx64", "macosarm64", "macosx64",
           "iosarm64", "iossimulatorarm64", "iosx64", "android")


def packed(files):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, content in sorted(files.items()):
            archive.writestr(name, content)
    return buffer.getvalue()


def attributes(target, documentation=False):
    platforms = {None: "common", "jvm": "jvm", "js": "js", "wasmjs": "wasm", "android": "androidJvm"}
    native = {"linuxx64": "linux_x64", "macosarm64": "macos_arm64", "macosx64": "macos_x64",
              "iosarm64": "ios_arm64", "iossimulatorarm64": "ios_simulator_arm64", "iosx64": "ios_x64"}
    result = {"org.jetbrains.kotlin.platform.type": platforms.get(target, "native"),
              "org.gradle.category": "documentation" if documentation else "library",
              "org.gradle.usage": "kotlin-runtime" if documentation else "kotlin-api"}
    if target in native:
        result["org.jetbrains.kotlin.native.target"] = native[target]
    if target == "wasmjs":
        result["org.jetbrains.kotlin.wasm.target"] = "js"
    if documentation:
        result["org.gradle.docstype"] = "sources"
    return result


class MavenRepositoryGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory()
        cls.baseline = Path(cls.fixture.name)
        notices = {str(path.relative_to(ROOT)): path.read_bytes()
                   for path in (ROOT / "third-party").rglob("*") if path.is_file()}
        docs = {name: (ROOT / name).read_bytes() for name in ("LICENSE", "THIRD_PARTY.md", "README.md", "SECURITY.md")}
        docs.update({name: (ROOT / "docs" / name).read_bytes()
                     for name in ("API.md", "conformance.md", "LEGACY_COMPATIBILITY.md")})
        docs.update(notices)
        docs_jar = packed(docs)
        sources = {}
        distribution = {name: (ROOT / name).read_bytes() for name in gate.SOURCE_DISTRIBUTION_FILES}
        for module, prefix in (("spake2", ""), ("spake2-primitives", "primitives/"), ("spake2-legacy", "legacy/")):
            files = sorted((ROOT / prefix / "src/commonMain").rglob("*.kt"))
            sources[module] = packed({"commonMain/" + str(path.relative_to(ROOT / prefix / "src/commonMain/kotlin")):
                                      path.read_bytes() for path in files})
            distribution.update({str(path.relative_to(ROOT)): path.read_bytes() for path in files})
        for name in ("src/commonTest/Example.kt", "primitives/src/commonTest/Example.kt",
                     "legacy/src/commonTest/Example.kt", "test-vectors/example.txt", "tools/example.py",
                     "docs/round2/example.md", "integration/example.txt"):
            distribution[name] = b"test-only source distribution fixture\n"
        source_distribution = packed(distribution)
        cls.source_distribution = distribution
        for module in MODULES:
            for target in (None,) + TARGETS:
                artifact = module + (f"-{target}" if target else "")
                directory = cls.baseline / "io/github/flyfish233" / artifact / VERSION
                directory.mkdir(parents=True)
                prefix = f"{artifact}-{VERSION}"
                extension = "aar" if target == "android" else "jar" if target in (None, "jvm") else "klib"
                if target == "android":
                    binary = packed({"classes.jar": packed({"Example.class": b"test-only fixture"}),
                                     "AndroidManifest.xml": b"test-only fixture"})
                elif extension == "klib":
                    binary = packed({"default/manifest": b"unique_name=fixture\n", "default/linkdata/module": b"fixture"})
                else:
                    binary = packed({"META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n"})
                binaries = {f"{prefix}.{extension}": binary, f"{prefix}-sources.jar": sources[module],
                            f"{prefix}-javadoc.jar": docs_jar}
                if target is None:
                    binaries[f"{prefix}-source-distribution.zip"] = source_distribution
                for name, raw in binaries.items():
                    (directory / name).write_bytes(raw)
                def file_record(name, alternate_name=None):
                    raw = binaries[name]
                    return {"name": alternate_name or name, "url": name, "size": len(raw),
                            "sha256": hashlib.sha256(raw).hexdigest(), "sha512": hashlib.sha512(raw).hexdigest()}
                dependencies = [{"group": "org.jetbrains.kotlin", "module": "kotlin-stdlib", "version": {"requires": "2.2.21"}}]
                if module != "spake2-primitives":
                    dependencies.append({"group": "io.github.flyfish233", "module": "spake2-primitives", "version": {"requires": VERSION}})
                component = {"group": "io.github.flyfish233", "module": module, "version": VERSION}
                if target:
                    component["url"] = f"../../{module}/{VERSION}/{module}-{VERSION}.module"
                variants = [{"name": "api", "attributes": attributes(target), "dependencies": dependencies,
                             "files": [file_record(f"{prefix}.{extension}", f"{module}-release.aar" if target == "android" else None)]},
                            {"name": "sources", "attributes": attributes(target, True),
                             "files": [file_record(f"{prefix}-sources.jar")]}]
                if target == "android":
                    runtime = json.loads(json.dumps(variants[0]))
                    runtime["name"] = "runtime"
                    for dependency in runtime["dependencies"]:
                        dependency["excludes"] = [{"group": "org.jetbrains.kotlin", "module": excluded}
                                                  for excluded in ("kotlin-stdlib-common", "kotlin-test-common", "kotlin-test-annotations-common")]
                    variants.append(runtime)
                if target is None:
                    for linked in TARGETS:
                        linked_artifact = f"{module}-{linked}"
                        variants.append({"name": linked, "attributes": attributes(linked), "available-at": {
                            "group": "io.github.flyfish233", "module": linked_artifact, "version": VERSION,
                            "url": f"../../{linked_artifact}/{VERSION}/{linked_artifact}-{VERSION}.module"}})
                (directory / f"{prefix}.module").write_text(json.dumps({"formatVersion": "1.1", "component": component, "variants": variants}))
                dep_xml = ""
                if module != "spake2-primitives":
                    dependency_artifact = "spake2-primitives" + (f"-{target}" if target else "")
                    dep_xml += f"<dependency><groupId>io.github.flyfish233</groupId><artifactId>{dependency_artifact}</artifactId><version>{VERSION}</version><scope>compile</scope></dependency>"
                dep_xml += "<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>2.2.21</version><scope>compile</scope></dependency>"
                pom = f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
<!-- do_not_remove: published-with-gradle-metadata -->
<modelVersion>4.0.0</modelVersion><groupId>io.github.flyfish233</groupId><artifactId>{artifact}</artifactId><version>{VERSION}</version><packaging>{extension}</packaging>
<url>https://github.com/Flyfish233/spake2</url>
<licenses><license><name>GNU Affero General Public License (AGPL-3.0-only)</name><url>https://www.gnu.org/licenses/agpl-3.0.html</url></license></licenses>
<scm><tag>{REVISION}</tag></scm><properties><spake2.sourceRevision>{REVISION}</spake2.sourceRevision><spake2.securityStatus>experimental-not-externally-audited</spake2.securityStatus></properties>
<dependencies>{dep_xml}</dependencies></project>'''
                (directory / f"{prefix}.pom").write_text(pom)

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repository = Path(self.temporary.name) / "maven"
        shutil.copytree(self.baseline, self.repository)

    def path(self, artifact="spake2", extension="module"):
        return self.repository / "io/github/flyfish233" / artifact / VERSION / f"{artifact}-{VERSION}.{extension}"

    def metadata(self, artifact="spake2"):
        return json.loads(self.path(artifact).read_text())

    def write_metadata(self, data, artifact="spake2"):
        self.path(artifact).write_text(json.dumps(data))

    def verify(self, **kwargs):
        return gate.check_repository(self.repository, VERSION, REVISION, **kwargs)

    def test_complete_33_publications_and_actual_android_schema_are_accepted(self):
        report = self.verify()
        self.assertEqual(33, report["publicationCount"])
        self.assertTrue(report["androidIncluded"])
        android = next(item for item in report["publications"] if item["artifact"] == "spake2-android")
        binary = android["archives"][f"spake2-android-{VERSION}.aar"]
        self.assertEqual([], binary["embeddedLicenseFiles"])
        self.assertEqual([], binary["aarClassesJarLicenseFiles"])
        self.assertTrue(binary["licenseCompanion"].endswith("-javadoc.jar"))

    def test_missing_module_and_missing_target_are_rejected(self):
        for artifact in ("spake2-primitives", "spake2-iossimulatorarm64"):
            with self.subTest(artifact=artifact):
                path = self.path(artifact)
                original = path.read_bytes()
                path.unlink()
                with self.assertRaisesRegex(gate.VerificationError, "Missing published file"):
                    self.verify()
                path.write_bytes(original)

    def test_umbrella_must_link_every_target(self):
        data = self.metadata()
        data["variants"] = [item for item in data["variants"] if item["name"] != "iosx64"]
        self.write_metadata(data)
        with self.assertRaisesRegex(gate.VerificationError, "Missing target links"):
            self.verify()

    def test_wrong_module_coordinates_and_available_at_version_are_rejected(self):
        data = self.metadata()
        data["component"]["module"] = "another-library"
        self.write_metadata(data)
        with self.assertRaisesRegex(gate.VerificationError, "Wrong Gradle module coordinates"):
            self.verify()
        data["component"]["module"] = "spake2"
        data["variants"][2]["available-at"]["version"] = "0.0.0"
        self.write_metadata(data)
        with self.assertRaisesRegex(gate.VerificationError, "Wrong available-at coordinates"):
            self.verify()

    def test_real_missing_prefix_pom_dependency_regression(self):
        path = self.path("spake2-android", "pom")
        path.write_text(path.read_text().replace("spake2-primitives-android", "primitives-android"))
        with self.assertRaisesRegex(gate.VerificationError, "Unsafe or incorrect production dependency"):
            self.verify()

    def test_rfc_legacy_edge_or_crypto_backend_dependency_is_rejected(self):
        for group, module in (("io.github.flyfish233", "spake2-legacy"), ("org.bouncycastle", "bcprov-jdk18on")):
            with self.subTest(module=module):
                data = self.metadata()
                data["variants"][0]["dependencies"].append({"group": group, "module": module, "version": {"requires": VERSION}})
                self.write_metadata(data)
                with self.assertRaisesRegex(gate.VerificationError, "Unsafe or incorrect production dependency"):
                    self.verify()
                data["variants"][0]["dependencies"].pop()
                self.write_metadata(data)

    def test_missing_binary_and_checksum_tampering_are_rejected(self):
        binary = self.path("spake2", "jar")
        original = binary.read_bytes()
        binary.unlink()
        with self.assertRaisesRegex(gate.VerificationError, "Missing published file"):
            self.verify()
        binary.write_bytes(bytes([original[0] ^ 1]) + original[1:])
        with self.assertRaisesRegex(gate.VerificationError, "Incorrect artifact"):
            self.verify()

    def test_missing_documentation_license_is_rejected(self):
        path = self.path("spake2", "jar").with_name(f"spake2-{VERSION}-javadoc.jar")
        with zipfile.ZipFile(path) as archive:
            entries = {name: archive.read(name) for name in archive.namelist() if name != "LICENSE"}
        path.write_bytes(packed(entries))
        with self.assertRaisesRegex(gate.VerificationError, "Missing license companion"):
            self.verify()
        entries["LICENSE"] = b"GNU AFFERO GENERAL PUBLIC LICENSE\nVersion 3, 19 November 2007\n"
        path.write_bytes(packed(entries))
        with self.assertRaisesRegex(gate.VerificationError, "Incorrect AGPL license text"):
            self.verify()

    def test_full_source_requires_all_module_builds_wrapper_and_lock(self):
        path = self.path("spake2", "jar").with_name(f"spake2-{VERSION}-source-distribution.zip")
        for removed in ("primitives/build.gradle.kts", "legacy/build.gradle.kts", "gradle/wrapper/gradle-wrapper.jar", "kotlin-js-store/yarn.lock"):
            with self.subTest(removed=removed):
                path.write_bytes(packed({name: value for name, value in self.source_distribution.items() if name != removed}))
                with self.assertRaisesRegex(gate.VerificationError, "Incomplete source distribution"):
                    self.verify()

    def test_path_escape_and_wrong_native_platform_are_rejected(self):
        data = self.metadata()
        original = data["variants"][0]["files"][0]["url"]
        data["variants"][0]["files"][0]["url"] = "../../../../../../../outside.jar"
        self.write_metadata(data)
        with self.assertRaisesRegex(gate.VerificationError, "escapes repository"):
            self.verify()
        data["variants"][0]["files"][0]["url"] = original
        self.write_metadata(data)
        target = self.metadata("spake2-linuxx64")
        target["variants"][0]["attributes"]["org.jetbrains.kotlin.native.target"] = "macos_x64"
        self.write_metadata(target, "spake2-linuxx64")
        with self.assertRaisesRegex(gate.VerificationError, "Wrong Native target"):
            self.verify()

    def test_partial_android_exclusion_is_explicit_and_counts_30(self):
        for module in MODULES:
            shutil.rmtree(self.path(f"{module}-android").parent)
            data = self.metadata(module)
            data["variants"] = [item for item in data["variants"] if item["name"] != "android"]
            self.write_metadata(data, module)
        with self.assertRaises(gate.VerificationError):
            self.verify()
        report = self.verify(without_android=True)
        self.assertEqual(30, report["publicationCount"])
        self.assertFalse(report["androidIncluded"])

    def test_optimized_cli_cannot_pass_bad_coordinates_or_keep_stale_success(self):
        data = self.metadata()
        data["component"]["version"] = "0.0.0"
        self.write_metadata(data)
        output = Path(self.temporary.name) / "report.json"
        output.write_text('{"status":"passed"}')
        result = subprocess.run([sys.executable, "-O", str(SCRIPT), "--repository", str(self.repository),
                                 "--version", VERSION, "--revision", REVISION, "--output", str(output)],
                                text=True, capture_output=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("Wrong Gradle module coordinates", result.stderr)
        self.assertEqual("failed", json.loads(output.read_text())["status"])


if __name__ == "__main__":
    unittest.main()
