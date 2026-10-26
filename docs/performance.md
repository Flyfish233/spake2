# JVM performance and allocation check

## Corrected-source measurements

After the SR-09 sampling correction and SR-10 arithmetic/byte-loader hardening,
the probe completed **39 independently known-answer-checked two-party exchanges**:
three warmups and ten measured exchanges for each of the three modes. Every RFC
exchange includes both public messages, both confirmation messages, both peer
confirmation checks, authenticated key release and session cleanup. The Legacy
exchange ends at the two matching unconfirmed keys and cleanup; application-level
authentication is outside that legacy protocol.

| Full two-party operation | Median elapsed time | Observed range, 10 samples | Median bytes allocated on calling thread | GC collections / GC time during the 10 measured operations |
|---|---:|---:|---:|---:|
| RFC 9382 P-256, both confirmations | 182.76 ms | 99.61–315.30 ms | 28.24 MiB | 4 / 66 ms |
| RFC 9382 Edwards25519, both confirmations | 84.07 ms | 50.58–127.38 ms | 12.98 MiB | 2 / 19 ms |
| Legacy SPAKE25519 HARDENED, unconfirmed keys | 78.48 ms | 55.49–88.53 ms | 12.81 MiB | 2 / 19 ms |

All 39 exchanges matched an expected key from an independent fixture, including
the warmups. This is a small implementation performance check. Three warmups do
not establish a fully stabilized JIT benchmark, and the timings reflect a shared,
unpinned execution environment. Other project validation was scheduled alongside
this run, so it is not a controlled timing comparison with the historical run
below. These results are not service-level throughput guarantees.

## Environment

- Executed at UTC: `2026-10-26T08:33:08.180516+00:00`.
- CPU reported by the host: `INTEL(R) XEON(R) PLATINUM 8573C`.
- JVM-visible processors: **8**.
- Runtime: `OpenJDK 64-Bit Server VM`, **17.0.21+9**.
- Compiler: **Kotlin 2.2.21**, JVM target 1.8.
- OS: `Linux-6.18.55-x86_64-with-glibc2.39`.
- Fixed JVM options: `-Xms128m -Xmx512m -XX:+UseG1GC`.
- Collection counters: `G1 Young Generation,G1 Old Generation`.
- Per-thread allocated-byte measurement: available through
  `com.sun.management.ThreadMXBean`.

The Python runner compiled snapshots of the actual three modules' `commonMain`
source files. No alternative cryptographic implementation was used for the
measured exchanges. `source-sha256.json` records every source file in that run.
The Java management imports and deterministic entropy live only in `tools/`.

The corrected run has its own records in `test-logs/jvm-performance-final/`.
Its manifest contains all 13 production sources plus the probe. All production
digests match the coordinated source freeze in
`test-logs/final-api-review-source-sha256.json`; they were also checked against
the worktree after execution. These digests identify the measured corrections
without attributing uncommitted source to an earlier Git HEAD.

## Allocation and heap behavior

The allocation numbers count the total bytes newly allocated by the calling
thread during one full two-party operation. They include the protocol work,
fixture entropy copies, known-answer comparison and cleanup. They quantify
allocation churn rather than the amount of memory retained after collection.
The current arithmetic creates many temporary arrays, so the measured allocation
cost remains substantial: about **28.2 MiB per P-256 pair** and **12.8–13.0 MiB
per Edwards or Legacy pair** in this run. Exact median allocation counts are
29,610,176, 13,606,976 and 13,432,176 bytes respectively. These counters are lower
than in the historical Long-accumulator snapshot, but neither run includes an
allocation stack profiler to assign the difference to individual methods.

The following heap-used snapshots bracket the ten measured operations in each
mode. No forced collection was inserted between samples; G1's collections are
reported in the table above.

| Mode | Heap used before measurements | Heap used after measurements |
|---|---:|---:|
| RFC 9382 P-256, both confirmations | 22.40 MiB | 5.36 MiB |
| RFC 9382 Edwards25519, both confirmations | 45.36 MiB | 25.39 MiB |
| Legacy SPAKE25519 HARDENED, unconfirmed keys | 64.39 MiB | 43.37 MiB |

These boundary snapshots and collection counts do not measure retained-object
lifetimes or prove absence of a memory leak. No retained-heap dump was taken.
Source inspection identifies the per-operation field and point temporary arrays
as candidates for allocation reduction; no allocation stack profiler was used
to assign costs to individual methods. See
[`ModMath.kt`](../primitives/src/commonMain/kotlin/io/github/flyfish233/spake2/internal/math/ModMath.kt),
[`P256.kt`](../primitives/src/commonMain/kotlin/io/github/flyfish233/spake2/internal/math/P256.kt), and
[`Edwards25519.kt`](../primitives/src/commonMain/kotlin/io/github/flyfish233/spake2/internal/math/Edwards25519.kt).

## Fixture and measurement boundaries

The RFC P-256 operation uses the first official RFC 9382 Appendix B fixture. The
Edwards operation uses fixture zero from the python-ecdsa 0.19.1 reference set.
The Legacy operation uses the `normal` fixture from the pinned BoringSSL oracle
with `LegacyPasswordScalarMode.HARDENED`. The expected output keys are embedded
in the tool and checked on every operation.

Entropy is deliberately copied from deterministic test fixtures. Those repeated
values must never be used for real sessions. RFC timing starts with a prederived
canonical w; application password preprocessing/MHF, a real CSPRNG, network
round trips and application traffic are outside this probe. The library's own
SHA-256, SHA-512, HMAC, HKDF and curve operations are included where their selected
protocol uses them.

The probe measures one fixed input set per mode. It supplies **no side-channel
assessment** and no evidence of strict constant-time execution. JVM measurements
also do not establish Android, Native, iOS, JS or Wasm performance.

## Historical M5 snapshot

The earlier measurement was executed at `2026-10-26T08:02:21.404237+00:00`, on
source snapshot `9ce242f80f61ab3bd9b822394ba101de5dbf3588`, before SR-09/SR-10.
It also checked all 39 exchanges against independent expected keys. Its original
raw files remain unchanged under `test-logs/jvm-performance/`.

| Historical full two-party operation | Median elapsed time | Observed range, 10 samples | Median calling-thread allocation |
|---|---:|---:|---:|
| RFC 9382 P-256, both confirmations | 154.48 ms | 107.70–208.53 ms | 36.50 MiB |
| RFC 9382 Edwards25519, both confirmations | 77.09 ms | 43.03–91.36 ms | 16.69 MiB |
| Legacy SPAKE25519 HARDENED, unconfirmed keys | 42.74 ms | 34.66–74.49 ms | 16.00 MiB |

The environment and fixed heap/collector settings were the same, but these two
small runs had no controlled scheduling or equal background load. Their timing
differences must not be described as a demonstrated speedup or performance
regression caused by the source change. The historical data describes its own
source snapshot only.

## Reproduction and records

After the project's Gradle dependencies have been downloaded:

```sh
python3 tools/run_jvm_performance.py \
  --gradle-cache "$GRADLE_USER_HOME" \
  --java "$JAVA_HOME/bin/java" \
  --warmups 3 --measurements 10 \
  --output build/performance
```

If `GRADLE_USER_HOME` is unset, omit `--gradle-cache`; the runner defaults to
`~/.gradle`. The tool uses the cached Kotlin 2.2.21 compiler and creates its
compiled probe in a temporary directory. It saves the raw sample log,
environment, compiler output and exact source hashes to the chosen output path.

The corrected-source run used:

```text
python3 tools/run_jvm_performance.py --gradle-cache /workspace/scratch/a46e59dd0882/toolchains/gradle-home --java /workspace/scratch/a46e59dd0882/toolchains/jdk-17.0.21+9/bin/java --warmups 3 --measurements 10 --output docs/test-logs/jvm-performance-final
```

Its complete records are:

- [Corrected per-sample JSON Lines](test-logs/jvm-performance-final/probe.jsonl)
- [Corrected environment and measurements](test-logs/jvm-performance-final/report.json)
- [Corrected source SHA-256 manifest](test-logs/jvm-performance-final/source-sha256.json)
- [Corrected compiler output](test-logs/jvm-performance-final/compiler.log)
- [Corrected runtime standard error](test-logs/jvm-performance-final/runtime-stderr.log)

Historical records retained without modification:

- [Per-sample JSON Lines](test-logs/jvm-performance/probe.jsonl)
- [Environment and measurements](test-logs/jvm-performance/report.json)
- [Source SHA-256 manifest](test-logs/jvm-performance/source-sha256.json)
- [Compiler output](test-logs/jvm-performance/compiler.log)
- [Runtime standard error](test-logs/jvm-performance/runtime-stderr.log)
