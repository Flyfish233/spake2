# Independent mathematical implementation review

## Scope and status

This review was performed by a separate engineering agent from the authors of
`ModMath`, `P256`, and `Edwards25519`. It combines source inspection, independent
arithmetic oracles, adversarial encoding cases and pinned external test data.
It is an engineering review within the implementation team, **not an external
cryptographic audit or a proof of constant-time execution**.

Reviewed production files are in
`primitives/src/commonMain/kotlin/io/github/flyfish233/spake2/internal/math/`.
The added tests are JVM-only under `primitives/src/jvmTest`. Their use of
`java.math.BigInteger` and deterministic `java.util.Random` is confined to test
code; neither becomes a library runtime dependency or an entropy provider.

## Arithmetic bounds and invariants

`ModMath` represents a value with sixteen base-2^16 limbs in Montgomery form.
For the four instantiated prime moduli, each operation consumes canonical
residues. The internal generic constructor assumes a prime modulus; it does not
certify an arbitrary caller-supplied modulus as prime.

For multiplication, a limb product is at most `(65535 * 65535)`. Including the
existing limb and carry gives an exact upper bound of `2^32 - 1` for each
schoolbook/REDC inner accumulator. The initial implementation used `Long` for
this sum. A later runtime-helper review found avoidable value-dependent branches
in Kotlin/JS boxed `Long.multiply`; the current source uses wrapping `Int`
arithmetic on every target. The correction relies on the pinned target backends
providing modulo-2^32 `Int` arithmetic; it is not a promise about every future
Kotlin backend. Under that representation, the low 32 bits retain the entire unsigned sum,
`and 65535` extracts the low limb, and `ushr 16` extracts the exact unsigned
carry even when the signed representation is negative. Montgomery reduction
retains an explicit extra limb and propagates carry through a loop whose bounds
depend on the public limb index.

Writing `R = 2^256`, canonical operands give `t < m^2 < mR`. The ordinary REDC
bound gives a result below `2m`, so one final masked subtraction suffices. The
implementation includes the extra high limb when selecting that subtraction.
Modular addition similarly tracks the carry beyond 256 bits; subtraction adds
the modulus under a borrow mask. These are source-level bounds, corroborated by
the independent integer tests, rather than a machine-checked proof.

The code uses four Newton steps from an inverse modulo two to obtain the
16-bit Montgomery coefficient. Field inverses use exponentiation by `m - 2`
for these known prime moduli. Zero inversion is rejected.

### Follow-up review of wrapping-Int multiplication

The separate reviewer inspected the actual `LongArray` to `IntArray` change,
including both multiplication loops, the 16-bit reduction factor, the extra
limb and final subtraction. Let `B = 65536`. Each inner sum is bounded by
`(B-1)^2 + (B-1) + (B-1) = B^2-1`; reducing intermediate `Int` operations
modulo `2^32` therefore loses no information needed by the limb/carry split.
Each factor is explicitly masked back into `[0,B)`. The first carry-propagation
sum is at most `2B-2`, and following propagation sums are at most B.

The schoolbook assignment at index `i+16` still writes a previously untouched
slot, so replacing the array element type does not discard an older carry.
For canonical input values, the unchanged REDC bound is below `2m`, with the
extra word at most one. Final limb subtraction values lie in `[-B,B-1]` and
the existing signed borrow extraction remains valid. No arithmetic defect was
identified in this source diff. Public-modulus Newton initialization remains in
`Long`; it does not process secret operands.

This is a source review of the correction, not an execution result. The
additional direct raw-Montgomery BigInteger and portable maximal-carry tests,
their execution results, and the emitted JavaScript inspection are recorded
separately in [math-int-carry-review.md](math-int-carry-review.md), the validation
ledger and runtime-helper review. The earlier six-method standalone result
below belongs to its recorded pre-correction snapshot and is not retroactively
claimed as verification of this change.

## Curve operations and encodings

P-256 uses Jacobian coordinates. The review compared the addition and doubling
structure with the [Explicit-Formulas Database for a = -3 Jacobian curves](https://www.hyperelliptic.org/EFD/g1p/auto-shortw-jacobian-3.html).
Equal-point, inverse-point and identity cases are handled by arithmetic masks.
Multiplication visits all 256 input bits. Decoding requires the 65-byte
uncompressed SEC1 form, canonical coordinates below the field modulus and
satisfaction of the curve equation. A wire encoding for infinity is rejected.

Edwards25519 uses the complete extended-coordinate formulas described in the
[Explicit-Formulas Database for a = -1 Edwards curves](https://www.hyperelliptic.org/EFD/g1p/auto-twisted-extended-1.html).
The four-bit multiplication schedule visits all 64 windows. Each window scans
all candidate table entries through masks rather than indexing by its secret
nibble. Bit 255 is retained. The raw internal multiplication deliberately does
not reduce its scalar modulo L: that would change multiplication on mixed-order
points and could break Legacy cofactor semantics.

Strict decoding checks `y < p`, existence of the recovered x-coordinate and the
canonical sign rule when x = 0. Historical noncanonical behavior is confined to
the explicitly named Legacy decoder. Identity and small-order detection are
separate from canonical point decoding. RFC protocol processing rejects pure
torsion, preserves mixed-order points and clears their torsion via cofactor 8.

## Additional independent tests

| Test | Independent expectation and coverage |
|---|---|
| Four modulus differential | 64 pairs for each of P-256 p/n and Edwards p/L: 256 pairs covering canonical encoding, add, subtract, multiply, square, negation, selected inversions, operand ownership and masks. Expected integers use JVM BigInteger. |
| Wide reduction | 36 cases across all four moduli and input lengths 0, 1, 31, 32, 33, 64, 65, 96 and 128. |
| P-256 affine reference | Nine scalar cases including zero, n, n+1, 2^255, 2^255+1 and 2^256−1; equal-point doubling, inverse-point addition and identity encoding. |
| Edwards affine reference | Seven scalar cases on both the generator and a mixed-order point. The oracle uses affine BigInteger equations and retains torsion. Cases explicitly detect truncation of bit 255 or incorrect reduction modulo L. |
| Edwards encoding boundaries | All 38 encodings with y in [p, p+18] and both sign bits, plus 128 small-y/sign combinations checked with an independent integer square-root oracle. |
| Pinned Wycheproof data | 62 valid raw P-256 point edge cases, all 24 invalid cases in the source dataset and the one compressed acceptable case, rejected under this library's explicit uncompressed-wire policy. |

The Wycheproof selection includes all source cases labeled `AdditionChain` and
`CVE-2017-8932`, plus deterministic samples across shared-secret, doubling and
ephemeral-point edge categories. The original source contains 355 cases; this
review imports 87 of them and does **not** claim execution of the whole dataset.

Source revision:
[`C2SP/wycheproof@d17cc89fa5c8f4f4d378b5385ebf75da5d0926c9`](https://github.com/C2SP/wycheproof/tree/d17cc89fa5c8f4f4d378b5385ebf75da5d0926c9).
Source file: `testvectors_v1/ecdh_secp256r1_ecpoint_test.json`.
SHA-256: `648f16d077caf2400d02331ca51f44744c72c799830c8d0595d0b18b6dd9f886`.
Its Apache-2.0 license and attribution are preserved under `third-party/`.

Reproduce selection from the pinned upstream file:

```sh
python3 tools/generate_wycheproof_subset.py \
  --root . --test-source primitives/src/jvmTest
```

Run the added tests:

```sh
./gradlew :primitives:jvmTest \
  --tests '*ArithmeticDifferentialTest' --tests '*WycheproofP256Test'
```

The six added test methods were actually compiled with Kotlin 2.2.21 and run
with JUnit 4.13.2 on OpenJDK 17.0.21+9 against a snapshot of the production math
sources before the module split. Result: **OK (6 tests)**, with the test runner
reporting 3.999 seconds. These methods contain the multiple cases listed above;
six is the method count, not the vector count. The source snapshot hashes and
actual runner output are recorded in `docs/test-logs/math-review-source-sha256.json`
and `docs/test-logs/math-review-junit.txt`.

This snapshot execution is separate from the final post-split Gradle command
above, which must also be recorded in the main validation report. Its compiler
warning about an unresolved future internal-API opt-in marker occurred before
that annotation was introduced during module splitting; it did not skip tests.

## Findings and limitations

No production arithmetic defect was identified in the inspected paths or in
the six additional executed test methods. That statement is limited to the
source review and the independent cases explicitly recorded here.

A limitation was found in one reference tool: python-ecdsa 0.19.1's
`PointEdwards` uses an infinity shortcut for points with zero X or T. It cannot
serve as the oracle for all pure torsion behavior. This was addressed by a
separate affine reference in `tools/rfc9382_torsion.py` and the JVM affine test
above. Both distinguish the actual identity `(0,1)` from `(0,-1)` and other
torsion points. The production code was checked against those references.

The source avoids secret-indexed tables and scalar-length-dependent loops in
the reviewed multiplications. It still needs compiler-output review and timing
analysis on JVM/ART, Native, JavaScript and Wasm before any stronger side-channel
claim. Managed allocation, garbage collection, JIT transformations, cache
effects and optimizer treatment of array clearing are not certified here.

Owned array clearing is best effort. Ordinary protocol error paths are tested
separately. Allocation failure, process termination, runtime copies, registers
and garbage-collected remnants cannot be guaranteed erased; some arithmetic
temporaries are cleared on the normal return path rather than under an
allocation-failure-safe cleanup mechanism. The implementation must remain
experimental until further independent security review.
