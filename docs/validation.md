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
- Initial Gradle attempt failed at dependency resolution before compilation. An environment-local proxy/system trust-store runner resolved this; the retry reached Kotlin 2.2.21 compilation and JVM test execution. Final results are recorded below when complete.

The bootstrap compiler checks supplement, and do not replace, the pinned Kotlin 2.2.21 multiplatform tests.

### First complete JVM integration execution

`gradle --no-daemon --info wrapper --gradle-version 8.14.3 --distribution-type bin jvmTest` completed successfully with Kotlin 2.2.21: **68 test methods, zero failures/errors/skips**, across 28 suites. Aggregate test time 49.568 seconds; full first dependency/bootstrap build 4m52s. Machine-readable suite totals: `docs/test-results/m1-jvm-summary.json`. This was an integration worktree run before final module separation and the last concurrency regressions; subsequent runs supersede it.

Covered all four official RFC P-256 vectors, twelve independently generated RFC Edwards vectors, eight pure torsion encodings and seven mixed-order encodings, all512 historical Legacy exchanges, seven deterministic exchanges, four historical peer cases, 24fresh exchanges and108fresh peer inputs. Fixture loops are counted separately from test-method counts.

### M5 module-separated JVM execution

`gradle --no-daemon jvmTest` after separating root RFC, `:primitives` and `:legacy`: **77 methods passed, no failures/errors/skips**, 15 tasks executed, 2m56s including compilation. Current suites:22 RFC/integration,24 primitives (including JVM BigInteger/Wycheproof),31 Legacy. The log contains77 PASSED entries. Old XML files from pre-move suites remained in the root result directory; they were excluded explicitly rather than double-counted. A subsequent clean build will serve as final verification.

`docs/test-results/m5-jvm-modules.json` and `docs/test-logs/jvm-modules.txt` record the checkpoint.
