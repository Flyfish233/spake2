# Review of the Montgomery Int carry correction

Review date: **2026-10-26**. Scope: `ModMath.multiply`, its accumulator types,
and targeted regression evidence. This is an engineering-team review, not an
external audit or constant-time certification.

## Problem and correction

The Kotlin/JS 2.2.21 standard-library source implements boxed `Long`
multiplication with checks for zero, sign, and small values. The original
Montgomery loops called `Long` multiplication on secret-dependent limbs.
That made the runtime's value-dependent branches relevant even though the
Kotlin source had fixed loop bounds. The exact official source artifact and
digests are recorded in
[js-runtime-review-source.json](test-logs/js-runtime-review-source.json).
This finding originated in runtime-source inspection; it must not be described
as an emitted-JavaScript inspection that had already happened at that stage.
For normalized 16-bit limb operands, the concretely varying branch is zero
handling; the sign and small-value comparisons have fixed outcomes. No timing
attack or password recovery was demonstrated by this source inspection.

The correction keeps the same radix, limb count, Montgomery algorithm, point
arithmetic, and protocol bytes. Only the secret multiplication storage and
accumulators change from `Long` to `Int`; `n0Prime` is converted to an `Int`
after its public initialization. Products and sums retain their low 32 bits,
and `ushr 16` extracts their unsigned upper half. No new secret branch or
secret-indexed lookup was added.

## Arithmetic proof

Let `B = 2^16`, `n = 16`, `R = B^n = 2^256`, and let the public odd modulus
be `p < R`. The input arrays represent canonical Montgomery residues in
`[0,p)`. Consequently every input and stored product limb is in `[0,B)`.
These are internal arithmetic invariants; direct callers of the restricted
implementation API are not given a general malformed-limb contract.

### Inner multiplication and reduction

Each schoolbook inner step adds one existing limb, one limb product, and one
carry. Given a carry in `[0,B)`, its mathematical value `v` satisfies:

```text
0 <= v <= (B-1)*(B-1) + (B-1) + (B-1)
       = B*B - 1
       = 4294967295
```

Therefore no information above bit 31 is needed. Under the supported
backends' modulo-`2^32` `Int` arithmetic, intermediate signed overflow changes
only the interpretation of the bit pattern. Addition and multiplication still
produce `v mod 2^32`. Since `v < 2^32`, the operations
`v and 65535` and `v ushr 16` recover the exact low limb and carry. The latter
is again in `[0,B)`, completing the induction. Arithmetic `shr` would be wrong
when bit 31 is set; the implementation deliberately retains `ushr` here.

The Montgomery factor is the low limb of `product[i] * n0Prime`. Both operands
are in `[0,B)`. Discarding bits above bit 15 after a wrapping 32-bit product
gives the required multiplication modulo `B`. The reduction inner step has
the same bound as the schoolbook step because modulus limbs and the factor
are also normalized.

The schoolbook assignment `product[i+n] = carry` does not overwrite a previous
row's contribution. Earlier rows can reach at most position `i+n-1`; the
destination is a previously untouched slot.

### Carry propagation and the extra word

After a reduction row, the first propagation sum is at most
`(B-1)+(B-1) = 2B-2`. Its outgoing carry is at most one. All subsequent sums
are at most `B`; this propagation is well inside signed `Int` range.

If `T = a*b` and `Q` is the combined Montgomery adjustment, then `T < p^2`
and `0 <= Q < R`. Thus:

```text
T + Q*p < p^2 + R*p < 2*R^2
U = (T + Q*p)/R < 2*p < 2*R
```

The 33-word product buffer therefore has enough capacity, and its final extra
word is at most one. The fixed propagation loop includes that word. No carry
beyond it is needed.

### Final subtraction

Every per-limb subtraction is in `[-B,B-1]`, which fits signed `Int`. Its
arithmetic right shift still gives the correct borrow. The final
`extraWord - borrow` is in `[-1,1]`. Since `U < 2p`, a single masked subtraction
of `p` produces the canonical residue. None of these bounds or selections was
changed by replacing the accumulator representation.

## Compiler assumption and assurance boundary

The proof above is conditional on modulo-`2^32` machine arithmetic for `Int`.
The [Kotlin number guide](https://kotlinlang.org/docs/numbers.html#data-overflow)
describes wrapping integer overflow. The broader
[language specification](https://kotlinlang.org/spec/built-in-types-and-their-semantics.html#built-in-integer-types)
leaves overflow behavior unspecified while allowing platform implementations
to specify it. Accordingly, this implementation's assurance concerns the
pinned, tested backends; it is not a proof about every possible Kotlin
implementation or future compiler.

Portable boundary vectors exercise the sign bit, maximum unsigned sum and
extra word, and are shared through `commonTest` with every configured target.
Actual target executions and emitted-JavaScript inspection are recorded in the
[validation ledger](validation.md). Compiler upgrades require renewed boundary
testing and review of the relevant lowering. Passing arithmetic tests or
observing a fixed source schedule does not prove strict constant-time code
generation.

## Independent regressions

`ModMathCarryBoundaryTest` compares **40 raw Montgomery products** across all
four field/order moduli to JVM `BigInteger` results:

```text
expected = a * b * inverse(2^256, p) mod p
```

It constructs and checks raw limb arrays directly. Neither production
`fromBytes` nor `toBytes` supplies an expected value or mediates the result
comparison. Cases include products just below and above `2^31`, dense carry
chains, modulus boundaries, zero and one. A separate whole-integer Montgomery
formula verifies that the chosen corpus actually includes final subtraction
and an intermediate 257th bit. Input ownership and output-limb normalization
are also checked.

`ModMathIntCarryTest` supplies **12 fixed raw products** in `commonTest`:
the squares of `2^48-1`, `2^240-1`, and `p-1` under each of the four moduli.
The first input is deliberately three all-one limbs. At schoolbook row 1,
column 1, the existing limb and incoming carry are both `65535`, so the inner
sum is exactly `0xffffffff`. This makes the unsigned carry boundary explicit
on JVM, Native, JS and Wasm rather than relying on a random case hitting it.

The fixed expected values were generated and independently rechecked with
Python's whole-integer arithmetic:

```python
R = 1 << 256
for p in moduli:
    inverse_r = pow(R, -1, p)
    for a in ((1 << 48) - 1, (1 << 240) - 1, p - 1):
        print(f"{(a * a * inverse_r) % p:064x}")
```

Another implementation author reviewed the actual production diff, the carry
and extra-word proof, and both test files; that reviewer independently
recomputed all 12 fixed outputs. This is separation within the same team, not
an external cryptographic review.

## Remaining Long operations and limits

- `ModMath` still uses `Long` in the four-step Newton initialization of
  `n0Prime`. Its only input is the fixed public modulus. The secret-dependent
  multiplication function contains no `Long` accumulator, conversion, or
  multiplication.
- SHA-256 padding offsets and transcript length encodings use `Long` on
  public message lengths.
- SHA-512 retains 64-bit words for its specified addition, bitwise and rotation
  operations. The limb change does not constitute a complete review of every
  runtime operation used by that hash.
- JIT behavior, JavaScript engine internals, machine multiplication timing,
  allocation, runtime copies and best-effort array erasure remain assurance
  limits. The library remains experimental.

## Isolated differential execution

The separately compiled probe completed successfully against the frozen
corrected source with **Kotlin/JVM 2.2.21** and **JDK 17.0.21+9**, exit code 0:

```text
PASS: 4000 field cases against JVM BigInteger (4 primes; add/subtract/multiply/square/reduction, sampled inversion)
PASS: 60 P-256 products against independently generated Python/OpenSSL fixtures
```

The [execution metadata](test-logs/math-int-final/run-metadata.json) retains
the exact compiler and Java argument arrays, dependency archive hashes,
original source hashes, copied compilation-input hashes, and snapshot status.
The [unaltered output log](test-logs/math-int-final/run.log) includes the actual
compiler version and results. The version query emitted missing-default-home
warnings; actual compilation explicitly supplied the standard-library
classpath with `-no-stdlib -no-reflect` and succeeded.

The compiler dependencies were read from the existing Gradle dependency cache.
No Gradle task ran for this probe, and no shared Gradle build output was
modified. Source was copied into an isolated temporary directory before
compilation. The temporary harness differed from the original
`tools/jvm_math_probe.kt` only in the P-256 fixture's location after module
separation. Original inputs and the three copied production files were
verified unchanged at completion. The harness's BigInteger and JCA hash calls
are test-only oracle/seed operations, not production dependencies.

This isolated JVM execution supplements the complete Kotlin 2.2.21 Gradle
regression. It does not substitute for executing the new portable carry test
on JS, Wasm, Native and the other supported targets, nor for inspecting the
emitted JavaScript. Those results remain separately attributable in the
validation ledger.
