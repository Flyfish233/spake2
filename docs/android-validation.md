# Android build validation

## Toolchain prepared

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

## Build result

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
