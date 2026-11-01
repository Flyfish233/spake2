# Validation ledger

All entries distinguish execution from configuration, compilation and intended work.

## Final verified implementation

The final code, tests, build configuration and dependency lock are at
[`b837aba27bebc1050965fa124c2efa54b4e4544a`](https://github.com/Flyfish233/spake2/commit/b837aba27bebc1050965fa124c2efa54b4e4544a),
Git tree `9144c7480ec823844a590bcb304d0d8f5b553128`. The final delivery commit adds
reports, logs and documentation corrections only. Its implementation inputs are
identical to that tested snapshot; it does not silently attribute old tests to
new code.

All three jobs in [GitHub Actions run 40294194097](https://github.com/Flyfish233/spake2/actions/runs/40294194097)
completed successfully. All three artifacts were downloaded and checked against
GitHub's recorded SHA-256 digests. The JUnit files were parsed to verify suite
counts, individual method outcomes and zero failures, errors or skipped tests.

| Target | Executed methods | Actual execution scope | Command / evidence |
| --- | ---: | --- | --- |
| JVM | **80 / 80** | Three modules on JDK 17, including JVM-only arithmetic and real-thread misuse tests | `jvmTest`; [Linux CI record](test-logs/github-actions/40294194097-linux-summary.json) |
| Linux Native x86-64 | **71 / 71** | Three compiled native test executables | `linuxX64Test`; same Linux CI record |
| JavaScript / Node | **71 / 71** | Actual Node execution of the JS target | `jsNodeTest`; same Linux CI record |
| Wasm / Node | **71 / 71** | Actual KotlinWasmNode execution, including the full Legacy corpus | `wasmJsNodeTest`; same Linux CI record |
| Android | **71 / 71**, plus **3 AARs** | Android host JVM unit tests and AAR compilation; no device, emulator or ART execution | `-PenableAndroid=true assembleDebug testDebugUnitTest`; [Android record](test-logs/github-actions/40294194097-android-summary.json) |
| iOS Simulator Arm64 | **71 / 71** | Actual Apple Silicon simulator execution on macOS 26.6.2 | `iosSimulatorArm64Test`; [iOS record](test-logs/github-actions/40294194097-ios-summary.json) |
| iOS device Arm64 | Compilation only | `compileKotlinIosArm64` completed in all three modules; no physical-device execution | Same iOS job and record |
| iOS Simulator x86-64 | Configured only | The available macOS runner was Arm64; this simulator architecture was not executed | No passing-test claim |

JVM has 23 RFC/integration, 31 Legacy and 26 primitive methods. Each executed
common-test target has 22 RFC/integration, 30 Legacy and 19 primitive methods.
Fixture loops include 512 historical Legacy exchanges and are not counted as
512 extra test methods. Repeating the same common suite on several targets is
cross-platform evidence, not hundreds of independent cryptographic test designs.
JS and Wasm were executed in Node, not browser engines. The local run records
Node 22.0.0; the CI log does not print the test-runtime Node version, so that
CI-specific version remains unasserted.

The Linux job ran
`./gradlew --no-daemon jvmTest linuxX64Test jsNodeTest wasmJsNodeTest`, reporting
**BUILD SUCCESSFUL in 10m 3s**, with 102 actionable tasks executed. The Android
command reported **58s**, with 93 tasks executed. The iOS job ran
`./gradlew --no-daemon compileKotlinIosArm64 iosSimulatorArm64Test`, reporting
**5m 37s**. These are Gradle wall times, not isolated protocol benchmarks.

The workflow checked out GitHub's temporary PR test merge
`ca8fab7b03d54e60e10c90505a25ad7efd08e4fb`. Its tree equals the source commit's
tree above; the [independent comparison](test-logs/github-actions/40294194097-source-comparison.json)
contains no changed files. This test merge is not a merge of the development
branch into `main`: PR #1 remains draft and unmerged, and `main` remains the
initial commit. Raw job logs and per-case summaries are retained in the
repository, independently of the 14-day Actions artifact retention.
Raw console logs retain their original trailing spaces and final blank lines;
the repository-wide whitespace checker reports these intentional evidence
bytes. The staged prose, JSON and XML whitespace check passed. Logs were not
normalized after recording their digests.

The final report-only commit deliberately uses `[skip ci]` after verifying that
no source, test, build, workflow or lock input changed. The green run is linked
to the exact tested code commit above, rather than described as a new run on
that documentation commit. The workflow remains available for future changes
and manual dispatch.

See [local platform execution](final-platform-validation.md),
[Android validation](android-validation.md), [Native validation](native-validation.md),
[independent vector provenance](reference-vectors.md) and
[Chinese delivery report](DELIVERY.zh-CN.md) for the complete scope and limits.

## Initialization

* Repository verified private and empty, with write permission on 2026-10-26.
* Initial `main` commit: `783299c166ed9613bb4e37d39fd7924205cf7c25`.
* Development branch: `work/spake2-implementation`.
* Git CLI has no credential helper in this environment; authenticated GitHub file
  and Git database APIs are used for remote commits. No credentials are exported.
* Build tools installed separately from repository runtime sources.

## Execution results

Configuration of a target is not evidence of a passing test.

### M1 independent bootstrap checks

- Kotlin/JVM 2.0.21 compiler bundled with Gradle 8.14.3; JDK 17.0.21+9.
- `python3 tools/hash_differential.py --gradle-home <gradle-8.14.3> --java <jdk>/bin/java`: **671/671** comparisons against Python hashlib/hmac. Tests cover SHA-256/SHA-512 padding boundaries, HMAC block boundaries and HKDF output lengths through 8160 bytes.
- `tools/jvm_math_probe.kt`: **4000/4000** field cases against test-only JVM BigInteger, plus **60/60** P-256 products against independently generated Python/OpenSSL fixtures. Production code contains no BigInteger/JCA implementation.
- `python3 tools/generate_math_vectors.py`: Python 3.12.14, cryptography 50.0.2, OpenSSL 4.0.3; generated 80 portable field fixtures and 60 portable curve fixtures.
- Gradle 8.14.3 distribution and generated wrapper JAR SHA-256 match official published checksums.
- Initial Gradle attempt failed at dependency resolution before compilation. An environment-local proxy/system trust-store runner resolved this; the retry reached Kotlin 2.2.21 compilation and JVM test execution. No TLS verification was disabled.

The bootstrap compiler checks supplement, and do not replace, the pinned Kotlin 2.2.21 multiplatform tests.

### First complete JVM integration execution

`gradle --no-daemon --info wrapper --gradle-version 8.14.3 --distribution-type bin jvmTest` completed successfully with Kotlin 2.2.21: **68 test methods, zero failures/errors/skips**, across 28 suites. Aggregate test time 49.568 seconds; full first dependency/bootstrap build 4m52s. Machine-readable suite totals: `docs/test-results/m1-jvm-summary.json`. This was an integration worktree run before final module separation and the last concurrency regressions; subsequent runs supersede it.

Covered all four official RFC P-256 vectors, twelve independently generated RFC Edwards vectors, eight pure torsion encodings and seven mixed-order encodings, all512 historical Legacy exchanges, seven deterministic exchanges, four historical peer cases, 24fresh exchanges and108fresh peer inputs. Fixture loops are counted separately from test-method counts.

### M5 module-separated JVM execution

`gradle --no-daemon jvmTest` after separating root RFC, `:primitives` and `:legacy`: **77 methods passed, no failures/errors/skips**, 15 tasks executed, 2m56s including compilation. Suites: 22 RFC/integration, 24 primitives (including JVM BigInteger/Wycheproof), 31 Legacy. The log contains 77 PASSED entries. Old XML files from pre-move suites remained in the root result directory; they were excluded explicitly rather than double-counted.

`docs/test-results/m5-jvm-modules.json` and `docs/test-logs/jvm-modules.txt` record the checkpoint.

### M5 clean build and initial platform executions

These are historical, fully executed checkpoints before the SR-09/SR-10 corrections.
Their common source tree was `e6c87147db06971cd52763b79c8a22f39904d3ca`, published as
GitHub commit `9ce242f80f61ab3bd9b822394ba101de5dbf3588`. Local commit
`2284fd077b0c67d1b2bdad42f5d691197ef1757e` has that identical tree; its different
commit identifier reflects use of authenticated GitHub Git APIs for delivery.

| Platform / command | Executed result | Recorded scope |
| --- | --- | --- |
| `clean jvmTest` | 77 methods, zero failures/errors/skips; 2m36s | Fresh result directories; `test-results/final-jvm-clean.json` and `test-logs/jvm-clean.txt` |
| `linuxX64Test` | 69 methods, zero failures/errors/skips; 12m36s | All three native ELF executables, including the entire Legacy corpus; [native-validation.md](native-validation.md) |
| `-PenableAndroid=true assembleDebug testDebugUnitTest` | Three AARs; 69 host tests passed; 3m27s | Android compilation and host JVM execution, no Android device/ART run; [android-validation.md](android-validation.md) |
| GitHub Android job | Three AARs; 69 host tests passed; 2m28s | Each AAR SHA-256 matches the corresponding local output; no native crypto entries |
| GitHub `iosSimulatorArm64Test` | 69 methods, zero failures/errors/skips; 8m5s | Actual Apple Silicon iOS simulator execution on macOS 26.6.2; no physical-device claim |

The initial GitHub run is
[40289938442](https://github.com/Flyfish233/spake2/actions/runs/40289938442).
Its Android and iOS artifacts were downloaded, SHA-256 checked, and parsed as
JUnit XML. Per-method records and raw job logs are retained under
`test-logs/github-actions/40289938442-*`, so the recorded evidence does not rely
only on GitHub's 14-day artifact retention. Its Linux job was cancelled by
the workflow's concurrency rule when the corrected-source commit started a new
run. Its partial artifact contains JVM 77 and Native 69 passing methods, plus
21 root-module JS methods; it does not establish complete JS or Wasm success.
The cancellation log and partial per-method summary are retained as
`40289938442-linux-cancelled.txt` and `40289938442-linux-partial-summary.json`.
The complete corrected-source run is reported separately below.

### Corrections after the initial platform checkpoint

- **SR-09:** RFC sampling now accepts canonical zero in the specified `[0,q)`
  interval. Four independent M/N expectations check zero-ephemeral public
  messages; degenerate shared values fail before key release, and w=0 with a
  zero ephemeral fails at public-message generation.
- **SR-10:** Field multiplication uses wrapping 32-bit word accumulators with
  an explicit `2^32-1` unsigned-sum bound, eliminating secret boxed-Long
  multiplication on the reviewed Kotlin/JS path. SHA-512 masks bytes before
  widening. The same code is used by every target.
- A new common test checks 12 independent raw-Montgomery carry cases. A new
  JVM test checks 40 raw products with BigInteger, including the extra high
  word and final subtraction. These expectations do not use production
  encoding/decoding to construct the expected values.

The source reasoning and residual compiler/runtime limits are in
[math-int-carry-review.md](math-int-carry-review.md) and
[js-runtime-review.md](js-runtime-review.md). These fixes prompted renewed
cross-platform validation rather than relabeling the earlier results.

### Corrected-source independent rechecks

| Check actually executed | Result | Compiler/reference and record |
| --- | --- | --- |
| SHA/HMAC/HKDF differential | 671/671 passed | Kotlin bootstrap 2.0.21, JDK 17.0.21+9, Python hashlib/hmac; `test-logs/hash-differential-final/` |
| Field and P-256 differential | 4000 field cases and 60 point products passed | Kotlin 2.2.21, JVM BigInteger and Python/OpenSSL fixtures; `test-logs/math-int-final/` |
| Full-pair performance probe with independent expected keys | 39/39 exchanges passed | Kotlin 2.2.21; [performance.md](performance.md) and `test-logs/jvm-performance-final/` |

Input manifests were checked before and after each run. The bootstrap compiler
result supplements the pinned project compiler tests; it is not substituted
for them. Performance measurements do not establish strict constant time or
absence of retained-memory leaks.

### Corrected-source Gradle checkpoint

The all-module command is:

```sh
./gradlew --no-daemon clean jvmTest jsNodeTest wasmJsNodeTest linuxX64Test
```

On the development host the installed Native toolchain was supplied with
`-Pkotlin.native.home=/workspace/scratch/a46e59dd0882/toolchains/konan/kotlin-native-prebuilt-linux-x86_64-2.2.21`.
An environment-local runner provides the JDK, dependency cache, proxy and system
trust store; these settings are not library dependencies or protocol changes.

**JVM phase completed: 80 methods passed, zero failures/errors/skips**, across
35 suites: 23 RFC/integration, 26 primitives and 31 Legacy. Both new arithmetic
tests and the zero-ephemeral test passed. Fresh XML, per-method results and the
phase log are retained at `test-results/final-platform-jvm.json` and
`test-logs/final-platforms/jvm-junit/`. The source/build manifest is
`test-logs/final-platform-source.json`.

The first incremental attempt reported duplicate declarations before any tests
ran. A search found one source declaration per symbol. A clean retry with the
same source/configuration compiled successfully and reached the 80 passing JVM
tests. The failed log remains in `test-logs/final-platforms/attempt-1-*`.
The exact internal cause of that one-off compiler-state failure has not been
established; no duplicate source or protocol workaround was introduced.

The complete combined command subsequently exited **0**, reporting
**BUILD SUCCESSFUL in 33m 4s**: 122 actionable tasks, 106 executed and 16 up to
date. All four target suites actually ran and passed:

| Local target | RFC/integration | Primitives | Legacy | Methods | Failures / errors / skipped |
| --- | ---: | ---: | ---: | ---: | --- |
| JVM | 23 | 26 | 31 | 80 | 0 / 0 / 0 |
| JS / Node 22.0.0 | 22 | 19 | 30 | 71 | 0 / 0 / 0 |
| Wasm / Node 22.0.0 | 22 | 19 | 30 | 71 | 0 / 0 / 0 |
| Linux Native x86-64 | 22 | 19 | 30 | 71 | 0 / 0 / 0 |

All 58 frozen source/build files matched both the final worktree and the Git
blobs of the tested code commit. Source and executable hashes, archived JUnit
XML, actual Node/Wasm package identities, the complete log and exit metadata
are described in [final-platform-validation.md](final-platform-validation.md).
The local Native suite times sum to 732.997 seconds; this shared host's runtime
differs from CI and is not an isolated performance benchmark. The source
manifest and individual target records preserve that distinction.
