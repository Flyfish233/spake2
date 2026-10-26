# Validation ledger

All entries distinguish execution from configuration, compilation and intended work.

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
only on GitHub's 14-day artifact retention. Linux/JS/Wasm status is recorded
separately when the relevant run completes; the two successful jobs above do
not imply success of that still-running job.

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

The combined run continues with JS/Wasm/Native. A passing JVM phase is not a
claim that the entire multi-target command has already succeeded. Updated
platform totals and the final GitHub run will be appended after actual completion.
