# Corrected-source local platform validation

This run verifies the RFC scalar-sampling correction, the shared Montgomery
`Int` accumulator implementation, and the SHA-512 unsigned-byte conversion
correction together. Its source is remote commit
[`b837aba27bebc1050965fa124c2efa54b4e4544a`](https://github.com/Flyfish233/spake2/commit/b837aba27bebc1050965fa124c2efa54b4e4544a),
Git tree `9144c7480ec823844a590bcb304d0d8f5b553128`.

The 58 source/build files were hashed before execution. Their manifest SHA-256
is `f23aa680b7028768a15363f8f550e3e2d33e3b9ba8111ed834e0c7affe03c4f6`.
The individual hashes and exact local command are recorded in
[`test-logs/final-platform-source.json`](test-logs/final-platform-source.json).
Source-hash checks between completed targets and after the final target found no changes.

The combined clean rebuild completed with **exit code 0**, reported by Gradle
as **33m 4s**. All four local targets passed. The complete log and
machine-readable summary are in
[`test-logs/final-platforms/complete-run.txt`](test-logs/final-platforms/complete-run.txt)
and [test-results/final-platforms.json](test-results/final-platforms.json).

## Command and environment

The reproducible Gradle task invocation is:

```sh
./gradlew --no-daemon --console=plain \
  clean jvmTest jsNodeTest wasmJsNodeTest linuxX64Test
```

The local execution used the same Gradle 8.14.3 distribution through an
environment wrapper that supplies the local JDK, dependency-cache paths,
network-proxy settings, and system trust store. It also supplied
`-Pkotlin.native.home` pointing to the already installed Kotlin/Native 2.2.21
distribution. These are build-environment settings; the library's algorithms
remain in `commonMain`.

| Component | Actual version |
| --- | --- |
| Kotlin compiler and standard library | 2.2.21 |
| Gradle | 8.14.3 |
| JDK | OpenJDK 17.0.21+9 |
| JavaScript/Wasm Node | 22.0.0 |
| Yarn | 1.22.17 |
| Kotlin/Native | 2.2.21, Linux x86-64 |

JavaScript uses the Mocha runner configured by the Kotlin plugin. The plugin
reports that Wasm uses **KotlinWasmNode**, so Wasm results are attributed to
that actual framework. Node runs the Wasm test runner directly, without an
extra experimental command-line flag in this environment.

## Results

Counts below are executed test methods. Several methods iterate over complete
independent vector corpora, including all 512 historical Legacy exchanges;
the method count is not the number of individual vector assertions.

| Target | RFC/integration | Legacy | Primitives | Total | Status |
| --- | ---: | ---: | ---: | ---: | --- |
| JVM | 23 | 31 | 26 | 80 | Passed; no failures, errors, or skips |
| JavaScript/Node | 22 | 30 | 19 | 71 | Passed; no failures, errors, or skips |
| Wasm/Node | 22 | 30 | 19 | 71 | Passed; no failures, errors, or skips |
| Linux Native | 22 | 30 | 19 | 71 | Passed; no failures, errors, or skips |

The JVM-specific additions include actual thread-overlap checks, BigInteger
and affine reference comparisons, carry-boundary differential checks, and
Wycheproof data. The same common test source runs on the other targets.

Per-module timings and SHA-256 hashes of the archived JUnit XML are in:

- [JVM result](test-results/final-platform-jvm.json).
- [JavaScript result](test-results/final-platform-js.json).
- [Wasm result](test-results/final-platform-wasm.json), with
  [executed-module hashes](test-logs/wasm-artifacts.json).
- [Linux Native result](test-results/final-platform-native.json), with
  [test-executable hashes and dynamic dependencies](test-logs/native-final-artifacts.json).

Test durations include this shared machine's scheduling and concurrent build
load; they are not isolated performance benchmarks. Performance measurements
and their limits are documented separately in [performance.md](performance.md).

## Initial failed attempt and correction

The first invocation failed in `:primitives:compileKotlinJvm` after 1 minute
24 seconds with duplicate declarations for every primitive class/function.
No tests had run. The source tree contained one declaration of each affected
symbol. The `clean` retry rebuilt the same frozen source and passed the JVM
suite. No production or build configuration edit was required. The exact
internal cause of the transient duplicate-declaration failure has not been
established.

The failed output and exit code are preserved in
[`test-logs/final-platforms/attempt-1-duplicate-declarations.txt`](test-logs/final-platforms/attempt-1-duplicate-declarations.txt)
and
[`test-logs/final-platforms/attempt-1-execution.json`](test-logs/final-platforms/attempt-1-execution.json).

## Scope and limits

The JavaScript review inspected the actual generated multiplication and hash
paths as well as the selected runtime helpers. Artifact hashes and the precise
findings are in [js-runtime-review.md](js-runtime-review.md). Passing tests and
removing avoidable runtime branches do not establish strict constant-time
execution or physical zeroization on any runtime.

This local invocation does not execute Android device/ART tests or iOS tests.
Those environments and the distinction between Android host unit tests and
device execution are recorded in their separate validation reports. The
initial platform records remain historical evidence for their original
source versions and are not silently relabeled as this corrected-source run.
