# Android build and host-test validation

## Final corrected-source CI result

The corrected implementation passed the Android build and host unit tests in
[GitHub Actions run 40294194097, Android job 121476259359](https://github.com/Flyfish233/spake2/actions/runs/40294194097/job/121476259359)
on **2026-10-26**. This is **71 executed host JVM test methods** and three
generated debug AARs; it is not Android device or ART execution.

The source head is
[`b837aba27bebc1050965fa124c2efa54b4e4544a`](https://github.com/Flyfish233/spake2/commit/b837aba27bebc1050965fa124c2efa54b4e4544a).
Actions checked out GitHub's pull-request test merge
`ca8fab7b03d54e60e10c90505a25ad7efd08e4fb`. Both commit records were fetched
independently through the GitHub API and have the identical tree
`9144c7480ec823844a590bcb304d0d8f5b553128`. The archived job log also confirms
the checked-out commit.

The job ran:

```sh
./gradlew --no-daemon -PenableAndroid=true assembleDebug testDebugUnitTest
```

The log reports **BUILD SUCCESSFUL in 58s**, with **93 actionable tasks, all
93 executed**. The complete job, including setup and artifact upload, ran
from 08:43:20 through 08:44:38 UTC: **78 seconds**. Its hosted environment was
Ubuntu 24.04.5 with Temurin Java 17; the local toolchain table below describes
the earlier local run, not this hosted environment.

| Module | Test suites | Host test methods | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| RFC (`spake2`) | 3 | 22 | 0 | 0 | 0 |
| Legacy (`legacy`) | 20 | 30 | 0 | 0 | 0 |
| Shared primitives (`primitives`) | 7 | 19 | 0 | 0 | 0 |
| **Total** | **30** | **71** | **0** | **0** | **0** |

These totals were reconstructed from every test case in the downloaded JUnit
XML, checked against each suite's declared counts, and checked for duplicate
case identities. The recorded cases include
`zeroEphemeralMatchesPublishedMasksAndCannotReleaseAKey`, the corrected RFC
rejection-sampling test, and `maximumUnsignedInnerSumAndCarryChains`. The
aggregate XML test time is **13.998 seconds**, distinct from build and job wall
time. A test method can exercise many independent vectors; method and vector
counts must remain separate.

### Corrected artifacts

The downloaded
[`android-build-and-unit-tests` artifact 12636953677](https://github.com/Flyfish233/spake2/actions/runs/40294194097/artifacts/12636953677)
is **94,510 bytes**. Its ZIP SHA-256 was independently checked:

```text
2f63edbe0d4d186a92551c3e5e6349d4676e86610b371e0dd24fed54da347999
```

| AAR | Bytes | SHA-256 |
| --- | ---: | --- |
| `spake2-debug.aar` | 19,062 | `0a9cff648a6a016fc893570a32e476157bd10db5bf0ad007ac750e2a3a6ea62d` |
| `legacy-debug.aar` | 12,669 | `6e3527d589b9cfc00e32f3899fdb15fba1b958f2499a6984d0f2bbcf38327f92` |
| `primitives-debug.aar` | 39,038 | `d625924e6a10a9259e5f6484a890fef2e98d8f309df9a2441e60f0e5485b2af2` |

Every AAR contains `R.txt`, `AndroidManifest.xml`, `classes.jar`, and Android
Gradle AAR metadata. Their `classes.jar` files contain respectively 7, 4, and
15 classes, all under the project's package. Neither the AAR entries nor their
embedded class archives contain `.so`, `.dll`, `.dylib`, or `.a` entries. The
case-level summary records every AAR entry's size and hash and the class
archive inventories. This packaging inspection supplements source/dependency
review; it does not by itself establish the semantics of every bytecode call.

The full evidence is retained as:

- [Case-level CI summary, source identity, and artifact inventories](test-logs/github-actions/40294194097-android-summary.json)
- [Original Android CI job log](test-logs/github-actions/40294194097-android.txt)

The ZIP and binary build products were inspected outside the repository. The
repository retains their hashes and textual verification records.

## Historical M5 CI checkpoint

[Run 40289938442](https://github.com/Flyfish233/spake2/actions/runs/40289938442)
tested the earlier `9ce242f80f61ab3bd9b822394ba101de5dbf3588` implementation.
It recorded **69 host test methods**, zero failures/errors/skips, three AARs,
and **BUILD SUCCESSFUL in 2m 28s**. Its artifact was `12636591223`, ZIP SHA-256
`050d7e114624156e129506b4ccc8d0de0d1db1aa2a12bae76d26e1de3bb5698f`.

That checkpoint predates the final RFC scalar and shared-arithmetic
corrections. Its [case-level summary](test-logs/github-actions/40289938442-android-summary.json)
and [original log](test-logs/github-actions/40289938442-android.txt) remain as
historical evidence; their counts are not added to the final 71-method result.

## Historical M5 local execution

The following local record is retained for the earlier implementation
snapshot. Its artifact hashes and test count are historical, distinct from the
corrected-source CI result above.

### Toolchain prepared

| Component | Observed version |
| --- | --- |
| Java | Eclipse Temurin OpenJDK 17.0.21+9 |
| Gradle | 8.14.3 |
| Kotlin | 2.2.21 |
| Android Gradle Plugin | 8.11.1 |
| Android SDK platform | API 35, revision 2, extension level 13 |
| Android SDK build tools | 35.0.0 |
| `aapt2 version` | 2.19-11948202 |
| Platform tools | 37.0.1-15733141 |

The SDK was downloaded using Google's command-line tools. The installer
returned exit code zero but nested the platform and build-tool archive roots
inside their package directories. The original extracted directories were
preserved outside the repository; the correct package contents were placed
at their standard SDK paths. The `source.properties` metadata, `android.jar`,
and executable `aapt2`/`adb` were then verified. This was an environment setup
repair; no project source or SDK executable was changed.

The platform `android.jar` SHA-256 is
`4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b`.
The build-tools `aapt2` SHA-256 is
`d1096e11aba9c974644369ee3c50d239acac3f3428ffa928e5b9c14dfb7a57de`.

### Build result

The three-module build completed successfully:

```sh
./gradlew --no-daemon -PenableAndroid=true assembleDebug testDebugUnitTest --stacktrace
```

```text
BUILD SUCCESSFUL in 3m 27s
93 actionable tasks: 93 executed
```

The execution environment invoked the same Gradle arguments through a local
launcher that selects the downloaded JDK/Gradle installations and configures
the environment's HTTPS proxy and trust store. It made no project changes.

| Module | Android host unit tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| RFC (`spake2`) | 21 | 0 | 0 | 0 |
| Legacy (`legacy`) | 30 | 0 | 0 | 0 |
| Shared primitives (`primitives`) | 18 | 0 | 0 | 0 |
| **Total** | **69** | **0** | **0** | **0** |

These are test-method counts from the Android test tasks' JUnit XML files.
Several methods replay many independent vectors, including all 512 historical
Legacy exchanges, seven deterministic exchanges, 24 newly generated exchanges,
four historical peer cases, 108 additional peer encodings, and the zero/L/2L
ephemeral-scalar boundary cases. RFC vectors, hash vectors, mathematical
boundaries, and state-machine failure tests also executed.

All three debug AARs were generated and inspected. Each contains its compiled
`classes.jar`; none contains `.so`, `.dll`, `.dylib`, or `.a` entries.

| AAR | Bytes | SHA-256 |
| --- | ---: | --- |
| `spake2-debug.aar` | 19,070 | `fad3051a3a3a32ba78b21a1ea0eb7e50e1fee3bdbaf71f7bdd791af4caddfd44` |
| `legacy-debug.aar` | 12,669 | `6e3527d589b9cfc00e32f3899fdb15fba1b958f2499a6984d0f2bbcf38327f92` |
| `primitives-debug.aar` | 39,138 | `ead64a6f33f3363b66dc2915cd95dd805f42792ffab38d2bbf0afc699695ad8e` |

The complete task output is retained in
[`test-logs/android-gradle.txt`](test-logs/android-gradle.txt). Exact module
counts, artifact paths and hashes, command, and execution time are retained in
[`test-logs/android-validation.json`](test-logs/android-validation.json).

## Device coverage

No Android device or emulator has been used in this environment. An AAR build
does not establish ART runtime behavior, device performance, timing properties,
or physical memory erasure. `testDebugUnitTest` runs on the host JVM; it is not
an instrumented Android test.
