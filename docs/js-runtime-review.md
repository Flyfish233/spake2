# Kotlin/JS runtime review

This review follows secret arithmetic into the Kotlin/JS runtime, rather than
assuming that a fixed loop schedule in Kotlin implies fixed timing in emitted
JavaScript. The project remains experimental; this review is not a proof of
constant-time execution or an externally performed security audit.

## Exact reference material

The inspected runtime is Kotlin **2.2.21**, matching this project's compiler and
standard-library version. The primary reference is the official
[kotlin-stdlib-js 2.2.21 source artifact](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-js/2.2.21/kotlin-stdlib-js-2.2.21-sources.jar).

| Material | SHA-256 |
| --- | --- |
| Official source JAR | `bd50a7194b6059c69d8d8cb14a0ea2b1b0ebea52ccf3c52119e65500cad11200` |
| `jsMain/runtime/boxedLong.kt` | `398b3600b40a49c8ff39c19af6e624599f7d02fae8a3a56346559ff1701b65e9` |
| `jsMain/runtime/longAsBigInt.kt` | `9eb23b04d5d90fbd7c4e56f0d1e88c80dce35abb4c08185cd8392d868aa4873a` |

The reviewed pre-correction source is available at remote commit
[`9ce242f80f61ab3bd9b822394ba101de5dbf3588`](https://github.com/Flyfish233/spake2/commit/9ce242f80f61ab3bd9b822394ba101de5dbf3588).
Its Git tree is `e6c87147db06971cd52763b79c8a22f39904d3ca`. The local review
snapshot `2284fd077b0c67d1b2bdad42f5d691197ef1757e` has that identical tree;
the local snapshot identifier is not presented as a remote GitHub commit.
Exact source hashes and reference identifiers are recorded in
[`test-logs/js-runtime-review-source.json`](test-logs/js-runtime-review-source.json).
The downloaded upstream source is used for inspection only and is not vendored
into the library.

## Findings and corrections

### 1. Secret field limbs reached operand-dependent boxed multiplication

The runtime's `Long.multiply` function checks whether either operand is zero,
then distinguishes signs, special values, and small operands. The original
`ModMath` implementation stored its product and carry accumulators in `Long`
values and multiplied secret field limbs through that type. Under boxed-Long
lowering this creates operand-dependent runtime control flow despite the fixed
Kotlin loop bounds. Zero operands are relevant even though individual limbs
are small and nonnegative.

The correction keeps the existing sixteen radix-2^16 limbs and uses wrapping
`Int` arithmetic with unsigned right shifts for product/carry accumulation on
**every platform**. For each inner product, the maximum exact unsigned sum is

```text
(65535 * 65535) + 65535 + 65535 = 4294967295 = 2^32 - 1
```

Its low 32-bit representation therefore preserves the entire sum. The low
16 bits and the unsigned high 16 bits recover the correct output limb and
carry. This removes secret field multiplication through the boxed-Long helper
without introducing a platform-specific cryptographic backend or changing
the protocol arithmetic. Independent arithmetic and protocol regression
validation accompanies the changed implementation; the corrected-source JVM
run passed all 80 methods, including both new carry-boundary regression tests.

### 2. SHA-512 converted signed secret bytes before masking

The boxed runtime's `fromInt` helper selects a sign-extension value according
to the input's sign. The original SHA-512 byte-loading expression converted a
signed secret `Byte` to `Long`, then masked it with 255. That order permits a
sign-dependent path before the mask.

The correction masks the byte as an `Int` first, producing a value in
`0..255`, and only then converts to `Long`. The conversion's sign condition
has the same outcome for every permitted byte. Both expressions compute the
same unsigned byte; the correction applies to the shared `commonMain` code.

## Remaining Long operations

| Operation | Data reaching it | Inspected runtime behavior |
| --- | --- | --- |
| SHA-512 additions | Secret compression words | Fixed four-chunk addition in `Long.add`, without operand-dependent source branches |
| SHA-512 AND, OR, XOR, inversion | Secret compression words | Direct operations on the two stored 32-bit halves |
| SHA-512 logical shifts and rotations | Secret words; fixed/public shift amounts | Helper branches select the shift-width case; the controlling shift amounts are public |
| SHA-512 byte-to-Long conversion after correction | Unsigned byte in `0..255` | Sign-extension choice has a fixed outcome in this domain |
| SHA-256/SHA-512 block counts and offsets | Input lengths and loop counters | Comparisons, division, multiplication and range choices depend on lengths/counters |
| Transcript lengths | Byte-array lengths | Length conversion and encoding operate on public framing metadata |

No source-level claim is made for arbitrary external uses of these runtime
helpers. The table describes the inputs used by this library's reviewed paths.
Kotlin/JS also has a BigInt-backed Long implementation; changing compiler modes
would require reviewing the selected lowering again.

## Emitted-code verification

The corrected **Kotlin 2.2.21 JS IR development-test executable** was built and
its actual JavaScript inspected. It uses the boxed-Long implementation. The
execution environment is managed **Node 22.0.0** with **Yarn 1.22.17**. The
frozen source/build-input manifest is
`f23aa680b7028768a15363f8f550e3e2d33e3b9ba8111ed834e0c7affe03c4f6`;
the corrected source is remote commit
[`b837aba27bebc1050965fa124c2efa54b4e4544a`](https://github.com/Flyfish233/spake2/commit/b837aba27bebc1050965fa124c2efa54b4e4544a),
Git tree `9144c7480ec823844a590bcb304d0d8f5b553128`. The per-file source hashes are in
[`test-logs/final-platform-source.json`](test-logs/final-platform-source.json).

| Generated artifact | SHA-256 |
| --- | --- |
| `spake2-primitives.js` | `a3f297ce813722813cbf758816c4337ca3f142b2cd16a256e63f7a2c84fc07e4` |
| `kotlin-kotlin-stdlib.js` | `bcfb6d4e927490abea9fd45949d5136619312d8ae86837b874c2eea3159cc763` |

Both files are generated under
`build/compileSync/js/test/testDevelopmentExecutable/kotlin/`. Exact function
ranges, function-body hashes, all sibling artifact hashes, the Node executable
hash, and the dependency-lock hash are recorded in
[`test-logs/js-runtime-emitted.json`](test-logs/js-runtime-emitted.json).
The corresponding primitive/runtime files in all three module test executables
were checked as well; the JSON record includes their separate artifact hashes.
All six inspected primitive/runtime files were byte-identical to the copies
in the package directories actually used by the Node test runners.
Generated third-party runtime code is not copied into the source repository.

The reviewed Montgomery multiplication uses a 33-element `Int32Array`,
`Math.imul` for all three multiplication sites, `| 0` wrapping for sums, and
`>>> 16` for carry extraction. Its branches control public loop counters; its
body contains no boxed-Long conversion, multiplication, or allocation. The
emitted `Math.imul` compatibility fallback is a fixed arithmetic expression;
the decision to install it depends on engine feature availability. The actual
Node runtime provides native `Math.imul`, so this fallback was inspected but
was not the path executed by the tests.

The SHA-512 byte load emits the unsigned `& 255` mask inside the argument to
`fromInt`. The emitted conversion allocates a `Long` with a sign-extension
choice, whose condition is always false for the now-unsigned input domain.
It has no lookup cache or secret-indexed table. The emitted Long addition
uses fixed chunk additions, and the inspected logical-shift and bitwise
helpers match the source review above. Their lazy initialization condition
depends on module initialization state, not on the secret word.

Separate internal inspections by the math and hash engineers confirmed these
observations against the recorded artifact hashes. These reviews found no
additional issue in the inspected helper paths; they are internal project
reviews, not an independent external security audit.

This inspection concerns the exact development-test artifacts and compiler
configuration above. A production/minified build, a different compiler
version, or a different Long lowering requires fresh emitted-code review.
Execution evidence is tracked separately from inspection evidence: the
corrected JVM run is recorded in
[`test-results/final-platform-jvm.json`](test-results/final-platform-jvm.json);
all three JavaScript module suites passed **71/71** common methods with no
failures, errors or skipped tests, recorded in
[`test-results/final-platform-js.json`](test-results/final-platform-js.json).
Wasm and Native verification are recorded separately from this JS inspection.

## Residual limits

Removing these two avoidable paths does not certify constant-time machine code.
JavaScript engine optimizations, deoptimization, representation choices,
garbage collection, and the generated code on different runtimes still require
separate analysis. No timing attack or timing-independence measurement has
been performed in this review.

The routines do not hide input lengths. In particular, the number of SHA-512
blocks processed for a Legacy password depends on its byte length. The
fixed-domain byte-conversion finding above concerns byte values; it does not
establish that password length is concealed.

SHA-512 uses Long values, whose JavaScript representation may involve immutable
boxed objects and intermediate allocations. Clearing owned arrays does not
guarantee erasure of every previous object or engine copy. The implementation
provides best-effort cleanup of its owned buffers, with no physical-zeroization
claim. Native Wasm `i64` operations have a different execution representation;
JavaScript boxed-Long findings should not be silently attributed to Wasm.
