# Local Maven publications and independent consumers

The build can stage an experimental candidate in an isolated Maven repository.
It does not configure a remote publishing repository, signing service, release
task or credentials. A successful local publication is not a release approval.

## Coordinates and module boundary

All coordinates use the group `io.github.flyfish233` and one explicit version.

| Umbrella artifact | Supported application API | Production dependencies |
| --- | --- | --- |
| `spake2` | RFC 9382 sessions and suites | `spake2-primitives`, Kotlin standard library |
| `spake2-legacy` | Explicit Legacy SPAKE25519 sessions | `spake2-primitives`, Kotlin standard library |
| `spake2-primitives` | Entropy, role and error types; implementation symbols require opt-in | Kotlin standard library |

Consumers normally declare the umbrella artifact in `commonMain`. Gradle Module
Metadata selects the target publication. Neither RFC nor Legacy has a production
dependency on the other protocol module. Application consumers do not need the
internal-API opt-in.

With Android enabled and pure-Kotlin Native cross-compilation enabled, each
umbrella has ten target publications: `-android`, `-jvm`, `-js`, `-wasmjs`,
`-linuxx64`, `-macosarm64`, `-macosx64`, `-iosarm64`,
`-iossimulatorarm64`, and `-iosx64`. Together these are **33 publications**.
Android publishes the release library variant. A downstream Android debug build
must be able to select this single published variant; the consumers test that
selection. Native KLIB publication does not prove that an Apple executable links
or runs. Those steps belong to the matching macOS/iOS consumer jobs.

The producer remains pinned to Kotlin 2.2.21 / Gradle 8.14.3 / AGP 8.11.1 / JDK 17.
The separate Kadb profile exercises Kotlin 2.4.20 / Gradle 9.6.0 / AGP 9.4.0 /
JDK 25 / compile SDK 37. This tests consumption of older compiled KLIBs and Maven
metadata by the newer toolchain; it does not rebuild the producer with that
compiler or assert compatibility with all compiler versions.

## Reproduce a local candidate

Use a clean, committed checkout and an empty task-specific repository directory.
The version must remain tied to that exact source revision. Do not reuse a
version for different source trees.

```sh
revision="$(git rev-parse HEAD)"
version="0.1.0-dev.$(git rev-parse --short=12 HEAD)"
repository="$PWD/build/candidate-maven"

./gradlew --no-daemon --console=plain \
  -PenableAndroid=true -Pkotlin.native.enableKlibsCrossCompilation=true \
  -Pspake2Version="$version" -Pspake2Revision="$revision" \
  -Dmaven.repo.local="$repository" publishToMavenLocal

python3 tools/check_maven_repository.py \
  --repository "$repository" --version "$version" --revision "$revision" \
  --output build/candidate-publication-report.json
```

An Android SDK is required. The checker requires the full 33-publication graph,
correct umbrella/target redirects and dependency coordinates, expected revision,
referenced artifact sizes/digests, target attributes, source archives,
documentation/license companions and the complete build source ZIP. Its explicit
`--without-android` mode checks a partial 30-publication graph and labels that
scope; it must not be used to claim Android publication acceptance.

The first round-2 packaging probe reproduced a real defect: configuring a target
artifact ID through an early publication callback allowed the Kotlin plugin to
overwrite it. An Android POM then referenced `primitives-android` instead of
`spake2-primitives-android`. The build uses the target's `mavenPublication`
callback, after Kotlin's default assignment. The repository checker validates
POM dependencies as well as GMM, so a working Gradle-metadata path cannot hide a
broken Maven/POM path. The original POM and expected gate rejection are retained
in [round2/evidence/maven-gate](round2/evidence/maven-gate/).

The same gate rejected Kotlin's automatically added `kotlin-dom-api-compat`
dependency. This library uses no DOM API, so the producer explicitly sets the
supported `kotlin.js.stdlib.dom.api.included=false` property. The baseline
consumer does the same. The checker keeps its original stdlib-only dependency
allow-list instead of silently admitting an unused runtime dependency.

Apple target publications may also expose Kotlin's host-specific
`kotlin-metadata` variant as a `-metadata.jar`, in addition to their primary
KLIB. The checker follows the pinned KGP 2.2.21 schema for that exact variant,
including original Gradle display names and Maven URLs. Such metadata cannot
replace the main KLIB. Real generated metadata fixtures and negative cases
cover this distinction; arbitrary extra JARs are not accepted.

## Separate build, published dependencies only

`integration/consumer` and `integration/kadb-consumer` are independent Gradle
builds. They contain no `includeBuild`, producer project dependency, source-set
injection from this library, or dependency-substitution rule. Both share only
the small public API adapters and independent expected values in
`integration/consumer-fixtures`. They resolve this Maven group exclusively from
the explicitly selected repository. This prevents an old Maven Local copy or
unrelated network repository from silently satisfying the test.

Copy the whole `integration` directory outside this checkout before running:

```sh
consumer_root="$(mktemp -d)"
cp -R integration "$consumer_root/integration"

./gradlew --no-daemon -p "$consumer_root/integration/consumer" \
  -PenableAndroid=true -Pspake2Repository="$repository" \
  -Pspake2Version="$version" \
  jvmTest linuxX64Test jsNodeTest wasmJsNodeTest \
  assembleDebug testDebugUnitTest verifyPublishedDependencies
```

The wrapper selects the Gradle distribution, while `-p` selects the independent
build. On a matching Mac host use `macosArm64Test iosSimulatorArm64Test`, or
`macosX64Test iosX64Test`. The new Kadb profile must use **Gradle 9.6.0 and JDK 25**;
see its [README](../integration/kadb-consumer/README.md) and the publication
workflow for its Android/Native commands.

Every consumer checks fixed expected values through public APIs: all four
official P-256 cases, twelve independent Edwards cases and five hardened Legacy
cases, including the high scalar bit and AOSP-style identities. Negative cases
check failure termination and key-confirmation rejection. They do not merely
compare two instances of this library with each other.

`verifyPublishedDependencies` runs in the same Gradle invocation after actual
compilation/tests. It writes resolved coordinates, variants and artifact names
to each consumer's `build/reports/published-dependencies.tsv`, rejects unexpected
versions/protocol edges and forbids substitution of the producer's sources.
It validates every resolved SPAKE2 dependency edge before selecting files with
a strict Gradle artifact view. Android's tested component and Kotlin's SwiftPM
metadata may legitimately refer to the fixture projects; those unrelated files
are not SPAKE2 Maven artifacts. The checker does not use lenient resolution.

The publication workflow stages one repository on Linux, transfers that exact
artifact to independent consumer jobs, and records their results. The Android
ART workflow stages only the subset needed by its device tests. Its partial
repository is not the full-publication acceptance artifact. CI artifacts expire;
retain the candidate repository, verification report and source ZIP before using
one as a durable downstream dependency. A transient CI download is not a public
package distribution service.

## Source, documentation and license companions

Each coordinate has a `-sources.jar` and `-javadoc.jar`. The latter contains the
maintained Markdown API, state/lifecycle, security and protocol documentation,
plus complete license/notice files; it is **not generated Javadoc or Dokka HTML**.
Each umbrella also has a `-source-distribution.zip` containing all three modules,
wrapper, build settings, source/tests, reference tools, fixed vectors, notices,
dependency lock files, consumer fixtures and retained evidence.

Source archives and JVM/Android runtime resources carry the license materials.
Native KLIB resource embedding is not promised. Keep the license/documentation
and complete-source companions with Native redistribution; POM metadata alone
does not automatically propagate those files into a downstream executable.
The publication report records which archives actually embed notices. It must
be consulted rather than assuming all archive types behave like a JVM JAR.

Original runtime code remains **AGPL-3.0-only, without a linking exception**.
Reference data and third-party material retain the permissions and notices
listed in `THIRD_PARTY.md`. A source JAR alone is not the complete build source.
The library's source ZIP also does not cover the whole Corresponding Source of
a downstream Kadb APK/framework. See the
[licensing review](round2/licensing-and-kadb.md) before distribution.

## Remote distribution and JitPack

This work validates a standard Maven layout, local publication and transferable
CI candidate artifacts. No release, Maven Central/GitHub Packages upload, signing
or JitPack candidate build is authorized or performed by these scripts.

JitPack can invoke Maven publication, but its repository/module coordinate rules
and target availability need their own consumer test. This repository does not
claim that a Git commit URL or JitPack build badge makes all KMP variants usable.
A normal Maven repository preserving the complete `.module` graph is the
recommended next distribution path if the owner later approves publication.
The choice of repository, retention/signing policy, version and licensing policy
remains an explicit release decision.

Execution status belongs to [the round-2 acceptance report](round2/DELIVERY.zh-CN.md)
and its linked CI records, not to the existence of a publication task.
