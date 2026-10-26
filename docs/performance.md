# JVM performance and allocation check

## Actual measurements

The probe completed **39 independently known-answer-checked two-party exchanges**:
three warmups and ten measured exchanges for each of the three modes. Every RFC
exchange includes both public messages, both confirmation messages, both peer
confirmation checks, authenticated key release and session cleanup. The Legacy
exchange ends at the two matching unconfirmed keys and cleanup; application-level
authentication is outside that legacy protocol.

| Full two-party operation | Median elapsed time | Observed range, 10 samples | Median bytes allocated on calling thread | GC collections / GC time during the 10 measured operations |
|---|---:|---:|---:|---:|
| RFC 9382 P-256, both confirmations | 154.48 ms | 107.70–208.53 ms | 36.50 MiB | 5 / 32 ms |
| RFC 9382 Edwards25519, both confirmations | 77.09 ms | 43.03–91.36 ms | 16.69 MiB | 2 / 13 ms |
| Legacy SPAKE25519 HARDENED, unconfirmed keys | 42.74 ms | 34.66–74.49 ms | 16.00 MiB | 3 / 16 ms |

All 39 exchanges matched an expected key from an independent fixture, including
the warmups. This is a small implementation performance check. Three warmups do
not establish a fully stabilized JIT benchmark, and the timings reflect a shared,
unpinned execution environment. They are not service-level throughput guarantees.

## Environment

- Executed at UTC: `2026-10-26T08:02:21.404237+00:00`.
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

## Allocation and heap behavior

The allocation numbers count the total bytes newly allocated by the calling
thread during one full two-party operation. They include the protocol work,
fixture entropy copies, known-answer comparison and cleanup. They quantify
allocation churn rather than the amount of memory retained after collection.
The current arithmetic creates many temporary arrays, so the measured allocation
cost is substantial: about **36.5 MiB per P-256 pair** and **16–17 MiB per Edwards
or Legacy pair** in this run.

The following heap-used snapshots bracket the ten measured operations in each
mode. No forced collection was inserted between samples; G1's collections are
reported in the table above.

| Mode | Heap used before measurements | Heap used after measurements |
|---|---:|---:|
| RFC 9382 P-256, both confirmations | 39.38 MiB | 30.39 MiB |
| RFC 9382 Edwards25519, both confirmations | 7.37 MiB | 24.40 MiB |
| Legacy SPAKE25519 HARDENED, unconfirmed keys | 73.40 MiB | 8.37 MiB |

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

The recorded run used:

```text
python3 tools/run_jvm_performance.py --gradle-cache /workspace/scratch/a46e59dd0882/toolchains/gradle-home --java /workspace/scratch/a46e59dd0882/toolchains/jdk-17.0.21+9/bin/java --output docs/test-logs/jvm-performance
```

Its complete records are stored alongside this document:

- [Per-sample JSON Lines](test-logs/jvm-performance/probe.jsonl)
- [Environment and measurements](test-logs/jvm-performance/report.json)
- [Source SHA-256 manifest](test-logs/jvm-performance/source-sha256.json)
- [Compiler output](test-logs/jvm-performance/compiler.log)
- [Runtime standard error](test-logs/jvm-performance/runtime-stderr.log)
