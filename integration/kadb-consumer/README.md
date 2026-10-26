# Kadb toolchain Maven consumer fixture

This independent Gradle build exercises the published SPAKE2 artifacts with the
Kotlin 2.4.20, AGP 9.4.0, Gradle 9.6.0 and JDK 25 toolchain used by the current
Kadb experiment. Its Android target uses `com.android.kotlin.multiplatform.library`,
compile SDK 37 and min SDK 23. The test adapters and independent expected outputs
come from `../consumer-fixtures`; no SPAKE2 implementation source is included.

`rfc-client` declares only the RFC artifact, and `legacy-client` declares only the
Legacy artifact. Their primitive dependency must resolve transitively. SPAKE2
coordinates are restricted to the explicitly supplied local Maven repository.
An absent version or remote repository URL fails configuration.

## Run

First publish the intended source revision with the producer's Gradle wrapper
into a new, isolated local Maven directory, as documented by the parent
integration guide. Then use **Gradle 9.6.0 and JDK 25** for this consumer build;
the producer wrapper uses a different Gradle version.

```sh
gradle --no-daemon -p integration/kadb-consumer \
  -Pspake2Version="$SPAKE2_CONSUMER_VERSION" \
  -Pspake2Repository="$SPAKE2_MAVEN_REPO" \
  linuxX64Test
```

For Android, install SDK 37 and add `-PenableAndroid=true`. The new Android
plugin's task names differ from the classic Android library plugin:

```sh
gradle --no-daemon -p integration/kadb-consumer \
  -PenableAndroid=true \
  -Pspake2Version="$SPAKE2_CONSUMER_VERSION" \
  -Pspake2Repository="$SPAKE2_MAVEN_REPO" \
  testAndroidHostTest assembleAndroidMain assembleAndroidDeviceTest
```

`connectedAndroidTest` runs the device test compilation on an attached Android
runtime. Both the host and device test compilations include the complete shared
consumer `commonTest` sources. Building a test APK alone does not count as ART
execution.

On compatible macOS hosts, execute `macosArm64Test` or `macosX64Test`, and execute
`iosSimulatorArm64Test` with an available arm64 simulator. `linkDebugTestIosArm64`
checks physical-device linking only; it does not execute tests on an iPhone.

The evidence reports state which of these tasks actually ran for each candidate.
The existence of this fixture alone is not a passing build or runtime result.

## Toolchain compatibility and task provenance

Kotlin documents backward compatibility for ordinary Kotlin `.klib` files from
1.9.20 onward. Consuming the producer's Kotlin 2.2.21 KLIBs with Kotlin 2.4.20 is
in that supported direction. This project has no cinterop KLIBs, for which the
same guarantee is not provided. The internal use of experimental atomic APIs
and the concrete target link/runtime behavior still require the candidate's
actual regression tests; the format guarantee does not establish those results.
See [Kotlin evolution principles](https://kotlinlang.org/docs/kotlin-evolution-principles.html#kotlin-klib-binaries).

The selected tools also fall within their documented build-tool ranges:
[Kotlin 2.4.20 supports Gradle 7.6.3–9.7.0](https://kotlinlang.org/docs/whatsnew2420.html#gradle),
[AGP 9.4 requires Gradle 9.6.0 and supports SDK 37](https://developer.android.com/build/releases/agp-9-4-0-release-notes),
and [Gradle supports a Java 25 daemon from 9.1 onward](https://docs.gradle.org/9.1.0/release-notes.html#support-for-java-25).

The Android task names above were derived from the official
[AGP 9.4.0 source artifact](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.0/gradle-9.4.0-sources.jar):
`KmpAndroidCompilationType` names the compilations `main`, `hostTest`, and
`deviceTest`; `getNamePrefixedWithAndroidTarget` produces `androidMain`,
`androidHostTest`, and `androidDeviceTest`; `KmpTaskManager`,
`AndroidUnitTest.CreationAction`, and `AndroidTestTaskManager` register the
corresponding tasks. The [official Android KMP guide](https://developer.android.com/kotlin/multiplatform/plugin)
documents enabling the host/device compilations and attaching the device
compilation to the `test` source-set tree.

| Task | What it verifies when executed |
| --- | --- |
| `testAndroidHostTest` | Runs the complete consumer common test suite on the host JVM. |
| `assembleAndroidMain` | Builds the consumer Android AAR. |
| `assembleAndroidDeviceTest` | Builds the consumer instrumentation APK; no device execution. |
| `connectedAndroidDeviceTest` | Runs the instrumentation compilation on a connected device/emulator. |
| `connectedAndroidTest` | Aggregate connected-device task including the instrumentation compilation. |

At fixture introduction, these task names and build API calls were checked
against the official sources and API artifact; the new Gradle profile itself
had not yet been executed. The final candidate's recorded CI results supersede
that initial status. No physical Android or iOS device result is implied.
