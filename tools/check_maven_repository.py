#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Validate the actual isolated Maven publications, without invoking Gradle.

This checks publication structure and bytes. Independent consumer builds still
have to establish resolution, compilation, linking and execution.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import sys
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET
import zipfile


GROUP = "io.github.flyfish233"
MODULES = ("spake2", "spake2-primitives", "spake2-legacy")
TARGETS = ("jvm", "js", "wasmjs", "linuxx64", "macosarm64", "macosx64",
           "iosarm64", "iossimulatorarm64", "iosx64", "android")
NATIVE_TARGETS = {
    "linuxx64": "linux_x64", "macosarm64": "macos_arm64", "macosx64": "macos_x64",
    "iosarm64": "ios_arm64", "iossimulatorarm64": "ios_simulator_arm64", "iosx64": "ios_x64",
}
STDLIB_MODULES = {
    "kotlin-stdlib", "kotlin-stdlib-common", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8",
    "kotlin-stdlib-js", "kotlin-stdlib-wasm-js",
}
KGP_ANDROID_EXCLUSIONS = {"kotlin-stdlib-common", "kotlin-test-common", "kotlin-test-annotations-common"}
SOURCE_NAMES = {
    "spake2": {"Rfc9382Core.kt", "Rfc9382Session.kt", "Rfc9382Suite.kt"},
    "spake2-primitives": {"EntropySource.kt", "InternalSpake2Api.kt", "HkdfSha256.kt",
                          "HmacSha256.kt", "Sha256.kt", "Sha512.kt", "Edwards25519.kt",
                          "ModMath.kt", "P256.kt"},
    "spake2-legacy": {"LegacySpake25519.kt"},
}
NOTICES = {
    "third-party/boringssl/LICENSE", "third-party/spake2-java/LICENSE",
    "third-party/gradle/LICENSE", "third-party/WYCHEPROOF-LICENSE.txt",
    "third-party/WYCHEPROOF-NOTICE.txt", "third-party/ietf/LICENSE-BSD",
    "third-party/ietf/NOTICE", "third-party/ietf/RFC4231-NOTICE",
    "third-party/ietf/RFC9382-IRTF-NOTICE", "third-party/ietf/TLP-2015-REFERENCE.txt",
}
SOURCE_DISTRIBUTION_FILES = {
    "build.gradle.kts", "primitives/build.gradle.kts", "legacy/build.gradle.kts",
    "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
    "kotlin-js-store/yarn.lock", "LICENSE", "THIRD_PARTY.md", "README.md", "SECURITY.md",
    "docs/API.md",
} | NOTICES
POM_NS = {"p": "http://maven.apache.org/POM/4.0.0"}
HASH_NAMES = ("sha512", "sha256", "sha1", "md5")
AGPL_LICENSE_SHA256 = "0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0"
CHECKOUT_THIRD_PARTY = Path(__file__).resolve().parents[1] / "THIRD_PARTY.md"


class VerificationError(ValueError):
    """A published repository cannot satisfy the declared contract."""


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def digest(data, algorithm="sha256"):
    return hashlib.new(algorithm, data).hexdigest()


def read_file(path):
    require(path.is_file(), f"Missing published file: {path}")
    return path.read_bytes()


def relative_file(repository, owner, url):
    require(isinstance(url, str) and url, f"Missing relative artifact URL in {owner}")
    parts = urlsplit(url)
    require(not (parts.scheme or parts.netloc or parts.query or parts.fragment),
            f"Non-local artifact URL in {owner}: {url}")
    decoded = unquote(parts.path)
    require(not decoded.startswith("/") and "\\" not in decoded,
            f"Invalid artifact URL in {owner}: {url}")
    resolved = (owner.parent / decoded).resolve()
    require(resolved.is_relative_to(repository), f"Artifact URL escapes repository: {url}")
    return resolved


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json(path):
    try:
        value = json.loads(read_file(path), object_pairs_hook=unique_object)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise VerificationError(f"Invalid JSON metadata: {path}: {error}") from error
    require(isinstance(value, dict), f"Metadata is not an object: {path}")
    return value


def exact_version(value, context):
    if isinstance(value, dict):
        require(bool(value) and set(value) <= {"requires", "strictly", "prefers"},
                f"Unsupported dependency version in {context}: {value}")
        require("requires" in value or "strictly" in value,
                f"Unbound dependency version in {context}: {value}")
        require(len(set(value.values())) == 1, f"Conflicting dependency version in {context}")
        value = next(iter(value.values()))
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", value),
            f"Non-exact dependency version in {context}: {value}")
    require(not value.upper().endswith("-SNAPSHOT") and value.upper() not in {"LATEST", "RELEASE"},
            f"Mutable dependency version in {context}: {value}")
    return value


def check_dependency(dependency, module, target, version, context, pom=False):
    require(isinstance(dependency, dict), f"Malformed dependency in {context}")
    group, name = dependency.get("group"), dependency.get("module")
    wanted = "spake2-primitives" + (f"-{target}" if target and pom else "")
    allowed_primitives = {wanted}
    if target and not pom:
        allowed_primitives.add(f"spake2-primitives-{target}")
    resolved_version = exact_version(dependency.get("version"), context)
    if group == "org.jetbrains.kotlin" and name in STDLIB_MODULES:
        return {"group": group, "module": name, "version": resolved_version}
    require(module != "spake2-primitives" and group == GROUP and name in allowed_primitives,
            f"Unsafe or incorrect production dependency in {context}: {group}:{name}")
    require(resolved_version == version, f"Wrong primitives version in {context}: {resolved_version}")
    return {"group": group, "module": name, "version": resolved_version}


def check_pom(path, module, target, version, revision):
    raw = read_file(path)
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as error:
        raise VerificationError(f"Invalid POM: {path}: {error}") from error
    require(root.tag == "{http://maven.apache.org/POM/4.0.0}project", f"Invalid POM namespace: {path}")
    text = lambda name: root.findtext(name, namespaces=POM_NS)
    coordinate = module + (f"-{target}" if target else "")
    require((text("p:groupId"), text("p:artifactId"), text("p:version")) ==
            (GROUP, coordinate, version), f"Wrong POM coordinates: {path}")
    require(text("p:scm/p:tag") == revision and
            text("p:properties/p:spake2.sourceRevision") == revision,
            f"Wrong source revision in POM: {path}")
    require(text("p:properties/p:spake2.securityStatus") == "experimental-not-externally-audited",
            f"Missing experimental security status: {path}")
    require(text("p:url") == "https://github.com/Flyfish233/spake2", f"Wrong project URL: {path}")
    packaging = text("p:packaging") or "jar"
    expected_packaging = {"pom", "jar"} if target is None else {
        "aar" if target == "android" else "jar" if target == "jvm" else "klib"
    }
    require(packaging in expected_packaging, f"Wrong POM packaging: {path}: {packaging}")
    licenses = root.findall("p:licenses/p:license", POM_NS)
    require(len(licenses) == 1 and
            "AGPL-3.0-only" in (licenses[0].findtext("p:name", namespaces=POM_NS) or "") and
            licenses[0].findtext("p:url", namespaces=POM_NS) == "https://www.gnu.org/licenses/agpl-3.0.html",
            f"Missing or incorrect AGPL-only license metadata: {path}")
    require(b"published-with-gradle-metadata" in raw, f"Missing Gradle metadata marker: {path}")
    for unexpected in ("parent", "profiles", "dependencyManagement", "repositories", "build"):
        require(root.find(f"p:{unexpected}", POM_NS) is None, f"Unexpected POM {unexpected}: {path}")
    dependencies = []
    for item in root.findall("p:dependencies/p:dependency", POM_NS):
        require(item.findtext("p:scope", default="compile", namespaces=POM_NS) in {"compile", "runtime"},
                f"Unexpected published dependency scope: {path}")
        require(item.find("p:optional", POM_NS) is None and item.find("p:exclusions", POM_NS) is None and
                item.find("p:classifier", POM_NS) is None and item.find("p:type", POM_NS) is None,
                f"Unexpected dependency modifier: {path}")
        dependencies.append(check_dependency({
            "group": item.findtext("p:groupId", namespaces=POM_NS),
            "module": item.findtext("p:artifactId", namespaces=POM_NS),
            "version": item.findtext("p:version", namespaces=POM_NS),
        }, module, target, version, str(path), pom=True))
    if module != "spake2-primitives":
        require(any(item["group"] == GROUP for item in dependencies), f"Missing primitives POM dependency: {path}")
    return {"sha256": digest(raw), "packaging": packaging, "dependencies": dependencies}


def check_platform(attributes, target, context):
    require(isinstance(attributes, dict), f"Missing variant attributes: {context}")
    platform = "native" if target in NATIVE_TARGETS else {
        None: "common", "jvm": "jvm", "js": "js", "wasmjs": "wasm", "android": "androidJvm",
    }.get(target)
    require(attributes.get("org.jetbrains.kotlin.platform.type") == platform,
            f"Wrong target platform attributes: {context}")
    if target in NATIVE_TARGETS:
        require(attributes.get("org.jetbrains.kotlin.native.target") == NATIVE_TARGETS[target],
                f"Wrong Native target attribute: {context}")
    if target == "wasmjs":
        require(attributes.get("org.jetbrains.kotlin.wasm.target") == "js", f"Wrong Wasm target: {context}")


def check_sidecars(path, data):
    checked = []
    for algorithm in HASH_NAMES:
        sidecar = Path(str(path) + "." + algorithm)
        if sidecar.exists():
            expected = sidecar.read_text().strip().split()[0]
            require(expected == digest(data, algorithm), f"Incorrect {algorithm} sidecar: {path}")
            checked.append(algorithm)
    return checked


def check_metadata(path, repository, module, target, version, targets):
    value = load_json(path)
    require(value.get("formatVersion") == "1.1", f"Unsupported Gradle metadata version: {path}")
    component = value.get("component")
    require(isinstance(component, dict), f"Missing component: {path}")
    coordinate = module + (f"-{target}" if target else "")
    owner = module if target and component.get("url") else coordinate
    require((component.get("group"), component.get("module"), component.get("version")) ==
            (GROUP, owner, version), f"Wrong Gradle module coordinates: {path}")
    if component.get("url"):
        require(bool(target), f"Umbrella metadata unexpectedly redirects ownership: {path}")
        expected = repository / GROUP.replace(".", "/") / module / version / f"{module}-{version}.module"
        require(relative_file(repository, path, component["url"]) == expected,
                f"Wrong component owner URL: {path}")
        read_file(expected)
    variants = value.get("variants")
    require(isinstance(variants, list) and variants, f"Missing variants: {path}")
    names, links, artifacts, dependencies, primary_library_variants = set(), set(), {}, [], 0
    for variant in variants:
        require(isinstance(variant, dict), f"Malformed variant: {path}")
        name = variant.get("name")
        require(isinstance(name, str) and name and name not in names, f"Invalid or duplicate variant name: {path}")
        names.add(name)
        context = f"{path.name}:{name}"
        attributes = variant.get("attributes", {})
        available = variant.get("available-at")
        if available is not None:
            require(target is None and isinstance(available, dict), f"Unexpected target redirection: {context}")
            linked = available.get("module")
            linked_target = next((item for item in targets if linked == f"{module}-{item}"), None)
            require(linked_target is not None and available.get("group") == GROUP and available.get("version") == version,
                    f"Wrong available-at coordinates: {context}")
            expected = repository / GROUP.replace(".", "/") / linked / version / f"{linked}-{version}.module"
            require(relative_file(repository, path, available.get("url")) == expected,
                    f"Wrong available-at URL: {context}")
            read_file(expected)
            check_platform(attributes, linked_target, context)
            require(not variant.get("files") and not variant.get("dependencies"),
                    f"Redirected variant also contains local files/dependencies: {context}")
            links.add(linked_target)
            continue
        check_platform(attributes, target, context)
        require(not variant.get("dependencyConstraints") and not variant.get("capabilities"),
                f"Unexpected constraints/capabilities: {context}")
        current_dependencies = []
        for dependency in variant.get("dependencies", []):
            require(set(dependency) <= {"group", "module", "version", "excludes"}, f"Unexpected dependency modifier: {context}")
            exclusions = dependency.get("excludes", [])
            require(isinstance(exclusions, list), f"Malformed dependency exclusions: {context}")
            for exclusion in exclusions:
                require(target == "android" and isinstance(exclusion, dict) and
                        set(exclusion) == {"group", "module"} and
                        exclusion["group"] == "org.jetbrains.kotlin" and
                        exclusion["module"] in KGP_ANDROID_EXCLUSIONS,
                        f"Unexpected dependency exclusion: {context}")
            current_dependencies.append(check_dependency(dependency, module, target, version, context))
        dependencies.extend(current_dependencies)
        files = variant.get("files", [])
        require(isinstance(files, list) and files, f"Variant has no published files: {context}")
        category = attributes.get("org.gradle.category")
        require(category in {"library", "documentation"}, f"Unexpected variant category: {context}")
        native_metadata = target in NATIVE_TARGETS and attributes.get("org.gradle.usage") == "kotlin-metadata"
        if native_metadata:
            # KGP 2.2.21 KotlinNativeHostSpecificMetadataArtifact inherits Native
            # API attributes, changes usage, and publishes classifier "metadata".
            require(category == "library" and attributes.get("artifactType") == "org.jetbrains.kotlin.klib" and
                    attributes.get("org.gradle.jvm.environment") == "non-jvm",
                    f"Incorrect Native metadata variant attributes: {context}")
        if category == "library":
            if not native_metadata:
                primary_library_variants += 1
            if module != "spake2-primitives":
                require(any(item["group"] == GROUP for item in current_dependencies),
                        f"Missing primitives Gradle dependency: {context}")
        else:
            require(attributes.get("org.gradle.docstype") == "sources", f"Unexpected documentation variant: {context}")
        extension = "aar" if target == "android" else "jar" if target in (None, "jvm") else "klib"
        expected_file = f"{coordinate}-{version}." + extension if category == "library" else f"{coordinate}-{version}-sources.jar"
        native_metadata_names = set()
        if native_metadata:
            expected_file = f"{coordinate}-{version}-metadata.jar"
            # GMM can retain the original Jar task name after artifactId is
            # customized: primitives-... versus spake2-primitives-... .
            original_module_name = module.removeprefix("spake2-")
            native_metadata_names = {expected_file, f"{original_module_name}-{target}-{version}-metadata.jar"}
        require(len(files) == 1, f"Unexpected number of variant files: {context}")
        for artifact in files:
            require(isinstance(artifact, dict), f"Malformed file descriptor: {context}")
            location = relative_file(repository, path, artifact.get("url"))
            name = artifact.get("name")
            # Android metadata keeps the task's original name (e.g.
            # spake2-release.aar) while its URL uses Maven coordinates.
            require(location.parent == path.parent and location.name == expected_file and isinstance(name, str) and name and
                    PurePosixPath(name).name == name and "\\" not in name and
                    PurePosixPath(name).suffix == location.suffix and (not native_metadata or name in native_metadata_names),
                    f"Wrong artifact location/name: {context}")
            raw = read_file(location)
            require(type(artifact.get("size")) is int and artifact["size"] == len(raw),
                    f"Incorrect artifact size: {location}")
            require("sha256" in artifact, f"Missing artifact SHA-256: {location}")
            for algorithm in HASH_NAMES:
                if algorithm in artifact:
                    require(artifact[algorithm] == digest(raw, algorithm), f"Incorrect artifact {algorithm}: {location}")
            if native_metadata:
                archive_entries(raw, location.name)
            artifacts[location.name] = {"size": len(raw), "sha256": digest(raw),
                                        "sidecarsChecked": check_sidecars(location, raw)}
    require(primary_library_variants > 0, f"Missing primary library variant: {path}")
    if target is None:
        require(links == set(targets), f"Missing target links in {path}: {sorted(set(targets) - links)}")
    return {"sha256": digest(read_file(path)), "variants": sorted(names),
            "availableTargets": sorted(links), "dependencies": dependencies, "files": artifacts}


def archive_entries(raw, context):
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            entries = archive.infolist()
            require(len({entry.filename for entry in entries}) == len(entries), f"Duplicate archive entry: {context}")
            require(sum(entry.file_size for entry in entries) <= 256 * 1024 * 1024,
                    f"Archive exceeds inspection bound: {context}")
            for entry in entries:
                name = entry.filename
                require(not name.startswith("/") and ".." not in name.split("/") and "\\" not in name,
                        f"Unsafe archive path: {context}:{name}")
                require((entry.external_attr >> 16) & 0o170000 != 0o120000,
                        f"Archive contains symlink: {context}:{name}")
            return {entry.filename: archive.read(entry) for entry in entries if not entry.is_dir()}
    except (zipfile.BadZipFile, RuntimeError) as error:
        raise VerificationError(f"Invalid archive: {context}: {error}") from error


def check_license(entries, prefix, context):
    license_file, notice_file = prefix + "LICENSE", prefix + "THIRD_PARTY.md"
    require(license_file in entries and notice_file in entries, f"Missing license companion: {context}")
    text = entries[license_file]
    require(digest(text) == AGPL_LICENSE_SHA256,
            f"Incorrect AGPL license text: {context}")
    require(b"AGPL-3.0-only" in entries[notice_file], f"Missing provenance/AGPL declaration: {context}")
    # The CLI is run from the candidate source checkout, so compare the entire
    # provenance document with that candidate, not merely its AGPL marker.
    require(entries[notice_file] == read_file(CHECKOUT_THIRD_PARTY),
            f"Incorrect THIRD_PARTY notice text: {context}")


def inspect_archives(directory, module, target, version, metadata):
    coordinate = module + (f"-{target}" if target else "")
    prefix = f"{coordinate}-{version}"
    reports = {}
    for classifier in ("sources", "javadoc"):
        path = directory / f"{prefix}-{classifier}.jar"
        raw = read_file(path)
        entries = archive_entries(raw, path.name)
        if classifier == "sources":
            names = {PurePosixPath(name).name for name in entries}
            require(SOURCE_NAMES[module] <= names, f"Incomplete module sources: {path}")
            check_license(entries, "META-INF/spake2/", path.name)
        else:
            check_license(entries, "", path.name)
            require({"API.md", "conformance.md", "LEGACY_COMPATIBILITY.md", "README.md", "SECURITY.md"} <= set(entries),
                    f"Incomplete API documentation: {path}")
            require(NOTICES <= set(entries) and all(entries[name] for name in NOTICES),
                    f"Incomplete third-party notices: {path}")
        if classifier != "sources" and ("META-INF/spake2/LICENSE" in entries or "META-INF/spake2/THIRD_PARTY.md" in entries):
            check_license(entries, "META-INF/spake2/", path.name)
        reports[path.name] = {"sha256": digest(raw), "size": len(raw), "entries": len(entries),
                              "sidecarsChecked": check_sidecars(path, raw),
                              "embeddedLicense": "META-INF/spake2/LICENSE" in entries and
                              "META-INF/spake2/THIRD_PARTY.md" in entries}
    binary_extension = "aar" if target == "android" else "jar" if target in (None, "jvm") else "klib"
    binary = directory / f"{prefix}.{binary_extension}"
    require(binary.name in metadata["files"], f"Primary binary is not referenced by metadata: {binary}")
    entries = archive_entries(read_file(binary), binary.name)
    if "META-INF/spake2/LICENSE" in entries or "META-INF/spake2/THIRD_PARTY.md" in entries:
        check_license(entries, "META-INF/spake2/", binary.name)
    runtime_notice_prefix = f"META-INF/spake2/{module}/"
    required_embedded_location = None
    if target == "jvm":
        check_license(entries, runtime_notice_prefix, binary.name)
        required_embedded_location = runtime_notice_prefix
    embedded = {name for name in entries if name.endswith(("/LICENSE", "/THIRD_PARTY.md")) or name in {"LICENSE", "THIRD_PARTY.md"}}
    nested_embedded = []
    if target == "android":
        require("classes.jar" in entries and "AndroidManifest.xml" in entries, f"Incomplete Android AAR: {binary}")
        nested = archive_entries(entries["classes.jar"], f"{binary.name}!classes.jar")
        check_license(nested, runtime_notice_prefix, f"{binary.name}!classes.jar")
        required_embedded_location = "classes.jar!" + runtime_notice_prefix
        if "META-INF/spake2/LICENSE" in nested or "META-INF/spake2/THIRD_PARTY.md" in nested:
            check_license(nested, "META-INF/spake2/", f"{binary.name}!classes.jar")
        nested_embedded = sorted(name for name in nested if name.endswith(("/LICENSE", "/THIRD_PARTY.md")))
    if binary_extension == "klib":
        require(any(name.endswith("/manifest") or name == "manifest" for name in entries), f"Missing KLIB manifest: {binary}")
    reports[binary.name] = {"sha256": metadata["files"][binary.name]["sha256"], "entries": len(entries),
                            "embeddedLicenseFiles": sorted(embedded), "aarClassesJarLicenseFiles": nested_embedded,
                            "requiredEmbeddedLicenseLocation": required_embedded_location,
                            "licenseCompanion": f"{prefix}-javadoc.jar",
                            "note": "Sources, JVM runtime JARs and Android classes.jar require full embedded notices. "
                                    "Other binary targets report actual embedding and require complete license companions."}
    if target is None:
        source = directory / f"{prefix}-source-distribution.zip"
        raw = read_file(source)
        entries = archive_entries(raw, source.name)
        missing = SOURCE_DISTRIBUTION_FILES - set(entries)
        require(not missing, f"Incomplete source distribution {source}: {sorted(missing)}")
        require(all(entries[name] for name in SOURCE_DISTRIBUTION_FILES), f"Empty required source-distribution file: {source}")
        check_license(entries, "", source.name)
        for scope, names in SOURCE_NAMES.items():
            source_prefix = "src/commonMain/" if scope == "spake2" else f"{scope.removeprefix('spake2-')}/src/commonMain/"
            found = {PurePosixPath(name).name for name in entries if name.startswith(source_prefix)}
            require(names <= found, f"Missing production sources in full distribution: {scope}")
        for required_prefix in ("src/commonTest/", "primitives/src/commonTest/", "legacy/src/commonTest/",
                                "test-vectors/", "tools/", "docs/round2/", "integration/"):
            require(any(name.startswith(required_prefix) for name in entries),
                    f"Missing source-distribution tree: {required_prefix}")
        reports[source.name] = {"sha256": digest(raw), "size": len(raw), "entries": len(entries),
                                "requiredBuildFilesWrapperLockAndNotices": True,
                                "sidecarsChecked": check_sidecars(source, raw)}
    return reports


def check_repository(repository, version, revision, without_android=False):
    repository = Path(repository).resolve()
    require(repository.is_dir(), f"Missing Maven repository: {repository}")
    require(exact_version(version, "requested publication") == version, "Invalid requested version")
    require(isinstance(revision, str) and bool(revision), "Missing requested revision")
    targets = tuple(target for target in TARGETS if not (without_android and target == "android"))
    publications = []
    for module in MODULES:
        for target in (None,) + targets:
            coordinate = module + (f"-{target}" if target else "")
            directory = repository / GROUP.replace(".", "/") / coordinate / version
            prefix = f"{coordinate}-{version}"
            pom = check_pom(directory / f"{prefix}.pom", module, target, version, revision)
            metadata = check_metadata(directory / f"{prefix}.module", repository, module, target, version, targets)
            archives = inspect_archives(directory, module, target, version, metadata)
            for extension in ("pom", "module"):
                path = directory / f"{prefix}.{extension}"
                check_sidecars(path, read_file(path))
            publications.append({"group": GROUP, "artifact": coordinate, "version": version,
                                 "target": target or "common", "pom": pom, "metadata": metadata, "archives": archives})
    return {"status": "passed", "executedAtUtc": datetime.now(timezone.utc).isoformat(),
            "repository": str(repository), "version": version, "revision": revision,
            "revisionIsFullGitCommit": re.fullmatch(r"[0-9a-f]{40}", revision) is not None,
            "androidIncluded": not without_android, "publicationCount": len(publications),
            "publications": publications,
            "scope": "Actual local POM/GMM dependency graph, artifact digests, archives, sources, full embedded source/JVM/Android "
                     "license notices and required license companions. THIRD_PARTY.md is compared with the candidate source checkout. "
                     "This does not execute consumers or certify cryptography. Maven Local checksum sidecars are checked when present; "
                     "GMM SHA-256 is mandatory for every referenced file."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--without-android", action="store_true",
                        help="Explicit partial gate: 30 publications instead of the full 33.")
    args = parser.parse_args()
    try:
        report = check_repository(args.repository, args.version, args.revision, args.without_android)
        result = 0
    except (VerificationError, OSError, TypeError, KeyError, IndexError, ET.ParseError) as error:
        report = {"status": "failed", "executedAtUtc": datetime.now(timezone.utc).isoformat(),
                  "repository": str(args.repository), "version": args.version, "revision": args.revision,
                  "error": str(error)}
        print(f"Maven publication validation failed: {error}", file=sys.stderr)
        result = 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    if result == 0:
        print(f"Validated {report['publicationCount']} publications; report: {args.output}")
    return result


if __name__ == "__main__":
    raise SystemExit(main())
