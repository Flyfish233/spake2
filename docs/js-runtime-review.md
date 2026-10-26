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
validation is required for the changed implementation.

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

Pending the corrected JavaScript build. Runtime-source inspection establishes
the hazards above, but does not by itself establish exactly which helpers a
particular generated artifact calls. The emitted JavaScript must be inspected
to confirm Int multiplication lowering, the absence of secret field Long
multiplication, and masking before SHA-512 byte conversion. Artifact hashes,
tool versions and actual test results will be recorded with that verification.

## Residual limits

Removing these two avoidable paths does not certify constant-time machine code.
JavaScript engine optimizations, deoptimization, representation choices,
garbage collection, and the generated code on different runtimes still require
separate analysis. No timing attack or timing-independence measurement has
been performed in this review.

SHA-512 uses Long values, whose JavaScript representation may involve immutable
boxed objects and intermediate allocations. Clearing owned arrays does not
guarantee erasure of every previous object or engine copy. The implementation
provides best-effort cleanup of its owned buffers, with no physical-zeroization
claim. Native Wasm `i64` operations have a different execution representation;
JavaScript boxed-Long findings should not be silently attributed to Wasm.
