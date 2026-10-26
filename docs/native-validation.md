# Linux Native validation

Validation date: **2026-10-26**. These are executed Linux x86-64 tests, not just
successful compilation or target configuration.

## Initial complete execution

The initial implementation snapshot completed all three `linuxX64Test` tasks:

| Module | Test suites | Test methods | Failures / errors / skipped | Aggregate test time |
| --- | ---: | ---: | --- | ---: |
| RFC and protocol integration | 3 | 21 | 0 / 0 / 0 | 88.533 seconds |
| Shared primitives | 6 | 18 | 0 / 0 / 0 | 17.284 seconds |
| Legacy | 20 | 30 | 0 / 0 / 0 | 508.586 seconds |
| **Total** | **29** | **69** | **0 / 0 / 0** | **614.403 seconds** |

Gradle exited with status **0** and reported **BUILD SUCCESSFUL in 12m 36s**,
with all 18 actionable tasks executed. Compilation and linking time are
included in Gradle's wall time but not in the sum of test-suite durations.

**Snapshot boundary:** this complete run predates the SR-09 correction to the
RFC ephemeral scalar sampler. Its source hashes were recorded before execution
and all 51 recorded source/build inputs were checked again, unchanged, after
execution. These results validate that initial snapshot; they do not by
themselves validate a subsequent sampler change. Affected RFC tests must be
rerun after that correction. The current [validation ledger](validation.md)
records the follow-up separately. Unchanged primitives and Legacy results
remain tied to their recorded inputs.

## Evidence retained

- [Machine-readable initial results](test-results/m5-native-initial.json)
  contain every suite and test-method result, the exact command, and SHA-256
  identities of all three generated ELF test programs.
- [Completed Gradle log](test-logs/native/initial-run.txt) preserves the task
  sequence and successful process result.
- [Original JUnit XML](test-logs/native/initial-junit/) preserves all 29 reports,
  prefixed by module to avoid filename collisions.
- [Source and build input hashes](test-logs/native/source-sha256.json) identify
  the code actually used for the run.
- [Toolchain metadata](test-logs/native/toolchain.json) identifies compiler
  versions, downloaded archive hashes, and extraction verification.

The programs under each module's `build/bin/linuxX64/debugTest/test.kexe` were
actual x86-64 ELF executables. The RFC executable's dynamic dependency table
listed the normal Linux runtime libraries (`libdl`, `libm`, `libpthread`,
`libgcc_s`, `libc`, and the dynamic loader); no platform cryptography library
was listed. Source/dependency review remains necessary to establish that the
core has no such backend: a dynamic-library list alone cannot exclude static
code.

## Executed command and reproduction

The exact environment-local invocation was:

```text
python3 /workspace/scratch/a46e59dd0882/run_gradle.py --no-daemon --console=plain -Pkotlin.native.home=/workspace/scratch/a46e59dd0882/toolchains/konan/kotlin-native-prebuilt-linux-x86_64-2.2.21 linuxX64Test
```

The local runner supplied JDK 17.0.21+9, Gradle 8.14.3, the prepared
`KONAN_DATA_DIR`, and environment-specific proxy/trust-store configuration.
Those settings are build-environment details, not runtime dependencies of the
library. Kotlin/Native reported version **2.2.21**; its LLVM toolchain reported
Clang **19.1.4**, and its GCC sysroot tools reported **8.3.0**.

For an ordinary Linux environment, the checked-in build can reproduce the
same task selection with:

```sh
./gradlew --no-daemon --console=plain linuxX64Test
```

An already-installed matching compiler can be selected with
`-Pkotlin.native.home=/absolute/path/to/kotlin-native-prebuilt-linux-x86_64-2.2.21`.
No Android SDK is required for this task selection.

## Toolchain preparation and issues resolved

The compiler and its public build dependencies were installed outside the
repository. Two extracted dependency directories inherited restrictive
owner/mode values and could not be traversed by the normal workspace tools.
Read/traverse permissions were repaired only on those generated LLVM/GCC
toolchain directories.

Before registering the manually prepared dependency cache, every regular file
and symlink target was compared against its downloaded archive: **4,073
regular files** across LLVM, the GCC sysroot, libffi, and LLDB matched. The
compiler, Clang and GCC version commands also executed successfully. The
recorded archive SHA-256 values are locally observed identities; they are not
represented as verification against vendor-published checksums.

No production source, protocol semantics, tests, or Gradle build settings were
changed to make the Native run pass. No compiler, linker or test failure
occurred during the complete run.

## Coverage and limits

The Native targets execute the common tests, including all four official
P-256 vectors, the independent RFC Edwards fixtures, torsion/encoding cases,
public-API failure paths, primitive known-answer cases, and all 512 historical
Legacy exchanges. The seven deterministic Legacy exchanges, four historical
peer cases, 24 fresh exchanges, 108 fresh peer cases, and explicit zero-derived
ephemeral regression also ran in those common suites. Fixture counts describe
loops within methods and must not be added to the 69 method count.

The JVM-only BigInteger differential tests, selected Wycheproof tests, and
real-thread concurrency tests are not included in this Native count. Common
reentrancy and lifecycle checks do execute here; they do not replace a Native
thread-scheduling stress test.

These are **debug** Native binaries. Their observed execution times do not
establish release-build performance, a memory upper bound, or constant-time
behavior. This run validates Linux x86-64 only; iOS, Android, JS and Wasm have
separate execution records.
