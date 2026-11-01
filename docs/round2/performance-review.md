# Round 2 JVM allocation and timing recheck

Date: **2026-10-27, Asia/Shanghai**. This is a bounded observation of an
experimental implementation, not a throughput guarantee or side-channel test.

## Result

The corrected measurement launcher completed **39 two-party exchanges**:
three warmups and ten measured operations for each of the two RFC suites and
Legacy HARDENED. Both endpoints' keys were checked against independent fixed
expected values on every operation. The completion record is present in the
[raw probe output](evidence/performance/probe.jsonl).

Both RFC median calling-thread allocations exactly match the previous final
probe. The Legacy median is 240 bytes higher, approximately 0.00179%, and
remains within the previous observed allocation range. The source and small
sample do not support attributing this difference to a code change.

| Complete two-party operation | Median time | Observed time range, 10 samples | Median allocated bytes | Median MiB | Previous median allocated bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| RFC P-256, both confirmations | 111.94 ms | 70.41–349.90 ms | 29,610,176 | 28.2385 | 29,610,176 |
| RFC Edwards25519, both confirmations | 55.02 ms | 48.68–107.82 ms | 13,606,976 | 12.9766 | 13,606,976 |
| Legacy SPAKE25519 HARDENED, unconfirmed keys | 50.14 ms | 26.43–133.17 ms | 13,432,416 | 12.8102 | 13,432,176 |

MiB means 1,048,576 bytes. The Legacy allocation range was
13,432,176–13,432,432 bytes in both runs. The two RFC allocation counts were
identical across their ten samples in each run. Exact measurements and
comparison calculations are in [comparison.json](evidence/performance/comparison.json).

The previous timing medians were 182.76 ms, 84.07 ms and 78.48 ms respectively.
**These timing differences do not establish a speedup.** A Gradle publication
daemon was present at the start and end of this run, and host scheduling,
CPU, I/O and memory contention were uncontrolled. The measurements use one
fixed input per mode and only three warmups; they are not a controlled
benchmark comparison.

## Source identity and environment

The 13 compiled production `commonMain` files and the Kotlin probe match
both `b837aba27bebc1050965fa124c2efa54b4e4544a` and
`c539ae6bb6eddd026aa9a3c884bd78f328eebc0d`, and match the previous final
performance source manifest. The Python launcher contains the separately
recorded cache-selection fix below. This is not a claim that the entire
working tree remained identical to either commit.

[source-comparison.json](evidence/performance/source-comparison.json) records
each compiled source hash, both Git blob comparisons and the seven compiler
classpath artifact hashes. The probe used Kotlin 2.2.21, OpenJDK 17.0.21+9,
G1 GC, and `-Xms128m -Xmx512m` on Linux x86-64. Thread allocation accounting
was supported. No Gradle task was invoked by this probe; it read the installed
compiler cache and compiled a temporary snapshot of the common sources.

The complete compile-and-run command took 116.85 seconds and exited zero.
There was no new cgroup OOM event or guard termination. The maximum observed
memory of the **whole shared cgroup** was 6,983,376,896 bytes under an 8 GiB
limit. That number includes concurrent work and must not be described as the
library's memory requirement. The actual execution context, load readings,
memory counters and guard policy are in
[execution-context.json](evidence/performance/execution-context.json).

## Tooling defect found and fixed

The first attempt failed before measurement with:

```text
NoClassDefFoundError: kotlin/jvm/internal/markers/KMappedMarker
```

The Gradle cache contained both the normal JVM standard-library JAR and
`kotlin-stdlib-2.2.21-all.jar`, which contains common metadata. The launcher
selected the first wildcard JAR and therefore attempted to start the JVM
compiler without the required bytecode. The original failure and actual
cache contents are retained under
[initial-attempt](evidence/performance/initial-attempt/run.log), including its
[cache diagnosis](evidence/performance/initial-attempt/cache-diagnosis.json).
No protocol exchange was measured in that failed attempt.

Five regression tests were added before changing the launcher. They exercise
its real entry point while intercepting the compiler command, so no fake
performance results are generated. The tests cover a complete canonical
cache, metadata/source classifier files, annotations source files, missing
JVM bytecode and multiple canonical candidates. Four tests failed against the
old launcher; the valid-cache control passed.

The corrected launcher selects the exact `artifact-version.jar` filename,
requires a file, and refuses ambiguous candidates. JetBrains annotations are
fixed to the previously used 23.0.0 canonical JAR through the same resolver.
The same five tests then passed. [Before](evidence/performance/cache-selector-before.json)
and [after](evidence/performance/cache-selector-after.json) manifests bind the
logs to identical test source and the respective launcher hashes.

The successful measurement reran the corrected launcher against the original
real Gradle cache. No filtered cache, substituted standard library, or changes
to the core or Kotlin probe were used.

## Reproduction and interpretation limits

```sh
python3 -m unittest discover -s tools/tests -p test_performance_cache.py
python3 tools/run_jvm_performance.py \
  --gradle-cache /workspace/scratch/a46e59dd0882/toolchains/gradle-home \
  --java /workspace/scratch/a46e59dd0882/toolchains/jdk-17.0.21+9/bin/java \
  --output docs/round2/evidence/performance \
  --warmups 3 --measurements 10
```

The probe uses deterministic test entropy, a prederived RFC password scalar
and fixed identities. It excludes application MHF policy, real CSPRNG cost,
network exchange, application traffic and artifact resolution. It measures
directly compiled common sources, not an Android, Native, JS or Wasm runtime,
and not the independent Maven consumer's startup or packaging cost.

The allocation totals are cumulative calling-thread allocations per operation,
not retained live heap or a leak assessment. They do not show whether erased
secrets survive in runtime copies, heap pages or registers. No allocation-site
profiler, timing-distribution side-channel test, physical-device measurement,
or additional benchmark campaign was run. The substantial temporary allocation
remains a documented engineering cost; this pass made no speculative
cryptographic optimization.
