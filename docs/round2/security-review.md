# Round 2 security and verification review

Review date: **2026-10-27, Asia/Shanghai**. Status: **experimental; no external
professional security audit or constant-time certification**.

## Result and scope

This pass found and fixed a reproducible weakness in the **independent Legacy
oracle verifier**. It did not find a new confirmed functional defect in the
production Kotlin cryptography. The 13 production `commonMain` files were
reviewed and compared byte-for-byte with
[`b837aba27bebc1050965fa124c2efa54b4e4544a`](https://github.com/Flyfish233/spake2/commit/b837aba27bebc1050965fa124c2efa54b4e4544a).
Their content is unchanged. The comparison, hashes and retained-test recount
are in [baseline-evidence-review.json](evidence/security/baseline-evidence-review.json).

The review covers the RFC and Legacy state machines, transcript/KDF ordering,
scalar and point handling, both curves, Montgomery arithmetic, SHA-256,
SHA-512, HMAC, HKDF, entropy ownership and the evidence used by the earlier
review. It was a fresh pass within the same AI-assisted engineering team. It
is not an independent external audit or a proof that no other defects exist.

No speculative curve rewrite was made. Source equality preserves the already
tested Legacy message and key semantics while the publication and platform
work is validated separately. New round-2 producer and consumer builds must
be supported by their own CI records; the historical records below are not
reported as new Kotlin executions.

## R2-S01: incomplete oracle records and optimized Python could suppress checks

**Severity: low, verification reliability. Fixed.** This is a development-tool
defect, not a change to the library's runtime attack surface.

Affected code: [`tools/legacy_regenerate_oracle.py`](../../tools/legacy_regenerate_oracle.py),
`run()` and the historical/fresh comparison loops in `main()`.

Previously `run()` asserted only the number of returned lines. The historical
comparison used `zip(observed, wanted)` without validating six fields. An
oracle returning a correct prefix of a historical record could omit the
remaining scalar, status or key checks. Later fresh-case processing does
index fields directly, so a verifier-wide truncation usually fails later;
the demonstrated weakness is still real for truncated historical batches.
Separately, `python -O` removed the Python `assert` statements used for all
output equality checks. Structurally valid, deliberately incorrect output
then produced a false success report.

The regression was added before changing the verifier. It mocks the external
process response, not the validation implementation. It tests every shortened
record, extra fields, missing/extra records, invalid widths/hex/status, and
the consistency of successful and failed key fields. A subprocess runs the
actual verifier entry point under `python -O` with deliberately wrong output
and temporary fixture copies. The control case preserves valid success and
failure records.

The fix requires exactly six fields, exact byte widths, canonical lowercase
hex, status `0` or `1`, and a 64-byte key only for success. Output equality
checks now raise explicit `ValueError` exceptions. There are no remaining
verification assertions in this tool that optimized Python can remove.

Reproduction command:

```sh
python3 -m unittest discover -s tools/tests -p test_legacy_oracle_protocol.py
```

| Check | Result | Evidence |
| --- | --- | --- |
| Same regression source against baseline verifier | 6 test methods, 20 failing assertions/subtests, exit 1; the valid-record control passed | [Before log](evidence/security/oracle-verifier-before.txt), [command and hashes](evidence/security/oracle-verifier-before.json) |
| Same regression source after correction | 6 methods passed, exit 0, including the optimized-Python subprocess | [After log](evidence/security/oracle-verifier-after.txt), [command and hashes](evidence/security/oracle-verifier-after.json) |
| Corrected verifier against pinned C executable | All historical expected bytes matched; regenerated fresh fixtures exactly matched the checked-in fixtures | [Replay log](evidence/security/oracle-replay.txt), [identity and result manifest](evidence/security/oracle-replay.json) |

The C replay was actually executed during this pass. It covered **512
historical fuzz exchanges, seven historical deterministic exchanges, four
historical peer cases, 24 fresh exchanges, and 108 fresh peer cases** (52
accepted by the compatibility oracle). The executable SHA-256 matches the
previously recorded executable built from BoringSSL commit
`9e04aed2d1652441d2c84e85ed21acc05e053260`. It is a test-only reference.
The run used a temporary copy of the verifier and input files; it did not
overwrite checked-in vectors. No Kotlin or Gradle invocation is included in
that replay claim.

This defect does not invalidate the original complete C outputs. The new
strict replay provides evidence that all existing historical expectations
still agree with the pinned executable. The regression covers faulty oracle
output handling, while actual protocol compatibility remains established by
the Kotlin fixed-vector suites and their execution records.

Other older reference generators still use assertions and must be run with
their documented ordinary `python3` commands, without `-O`. The fix above is
scoped to the Legacy verifier; it is not a claim that every development script
has been hardened against optimized Python.

## Production checks and their supporting tests

The normative protocol comparison used [RFC 9382 sections 3, 4, 6 and
7](https://www.rfc-editor.org/rfc/rfc9382.html). In particular, both roles,
uniform scalar selection, group membership, the cofactor operation,
length-prefixed transcript, suite encoding and confirmation before completion
were checked. The stated AAD bound is in **bits**, so 8,176 bytes is the correct
API limit. RFC 9382's timing requirement must not be confused with evidence
that a managed or native compiled runtime meets it. [RFC 5869 section
2](https://www.rfc-editor.org/rfc/rfc5869.html#section-2) supplies the HKDF
extract/expand reference.

The following entries describe observations of this implementation and the
independent expectations in its tests. They are not new platform pass claims.

| Area and source | Review conclusion | Independent or targeted evidence inspected |
| --- | --- | --- |
| `Rfc9382Session.sampleScalar`, constructor | Owned 32-byte big-endian `w` is validated before use. Ephemeral selection includes canonical zero, rejects order/larger candidates, and consumes fresh caller entropy. Edwards candidates mask the unused high bits before rejection. | `RfcFailureTest.rejectionSamplingRejectsOrderAndLargerValuesAndConsumesFreshEntropy`, `zeroEphemeralMatchesPublishedMasksAndCannotReleaseAKey`, invalid scalar and entropy cases. The zero-ephemeral expected messages come from published M/N constants. |
| `P256.decode`, `add`, `multiply` | Strict uncompressed 65-byte input, canonical coordinates and curve equation validation; identity is not a wire encoding. Full-width scalar processing and exceptional addition selections remain intact. | Python/OpenSSL product fixtures, JVM affine/BigInteger differential tests, carry cases and 87 selected Wycheproof cases. |
| `Edwards25519.decodeStrict`, `decodeLegacy`, `multiply` | Strict and historical decoding remain separate. Strict decoding checks canonical y and the zero-x sign rule. Multiplication preserves all 256 scalar bits. | RFC 8032 base point/public key case, independent affine/BigInteger tests, full-width high-bit test and strict/historical decoder separation test. |
| `Rfc9382Core.sharedPoint` | Pure low-order Edwards inputs and degenerate shared points fail; mixed-order inputs use cofactor clearing after unmasking. Prime-order-only decoding was not substituted for the intended full curve handling. | All eight pure torsion encodings, seven mixed-order cases and fixed independent intermediate fields in `RfcVectorsTest`. The torsion reference uses explicit affine arithmetic rather than a library shortcut that assumes prime order. |
| `Rfc9382Core.derive`, `transcript` | Encodes identities, exact public messages, K and canonical w in the intended order. Splits Hash(TT) into Ke/Ka; derives KcA/KcB with the AAD-bound info and computes both confirmations. | Four official P-256 vectors compare every intermediate; separate Edwards reference vectors do the same. Wrong-password, identity, AAD, role and confirmation tests exercise public failure behavior. |
| `Rfc9382Session.processPeerMessage`, `confirm` | Message processing returns only local confirmation. The private pending session key is returned once after peer confirmation succeeds. Malformed confirmation lengths fail before copying. | `cannotReleaseAKeyBeforeConfirmationOrBeforePeerMessage`, `successfulKeyCanOnlyBeReleasedOnce`, malformed-confirmation and terminal-state cases. |
| `LegacySpake25519.generateMessage`, `shiftThree`, `hardenPasswordScalar` | Historical masks, SHA-512 password operation, reduction and three conditional subgroup-order additions are preserved. The temporary private scalar is reduced then multiplied by eight without discarding bit 255. | 512 historical fixed exchanges, seven deterministic cases, the high-bit arithmetic test, the zero-entropy oracle regression, and the strict C replay above. |
| `LegacySpake25519.deriveUnconfirmedKey`, `encodeTranscript` | Preserves role ordering, exact received wire bytes and the 64-byte SHA-512-derived result. Historical relaxed decoding remains confined to the explicit Legacy API. | Fixed oracle messages/keys, 108 fresh malformed/boundary peer cases and `ProtocolIsolationTest`; no self-agreement-only compatibility claim. |
| Both session guards and cleanup functions | A rejected overlap poisons the active operation or claims idle cleanup. The cleanup-owner state precedes permanent terminal publication. The owner wipes prospective output when poisoned. Owned protocol buffers are overwritten on ordinary failure and terminal paths. | Common lifecycle/reentrancy/failure tests plus deterministic JVM concurrency tests. Existing SR-01/SR-04/SR-07 corrections remain present. |
| `ModMath.multiply`, reduction and exponentiation | With 16-bit limbs, each inner sum is at most `2^32 - 1`; wrapping `Int` plus unsigned shifts retains the intended carry. The extra high word participates in the final subtraction. No secret multiplication was moved back to the previously identified Kotlin/JS boxed-Long helper. | `ModMathIntCarryTest`, JVM carry-boundary/BigInteger tests, prior 4,000 field and 60 P-256 independent probe results with matching source hashes. |
| SHA-256/SHA-512, HMAC and HKDF | Padding uses widened length arithmetic. SHA-512 masks bytes before Long conversion. HMAC ownership, expansion counter, output/PRK bounds and temporary-buffer cleanup remain coherent. | Standard known-answer vectors, independent padding-boundary cases and retained hash/HMAC/HKDF differential evidence. |

`ModMath` accepts an odd modulus but is not a general primality-testing API;
the protocols instantiate it with their fixed known primes. Its public
visibility is protected by the internal-API opt-in and is not an assurance for
arbitrary downstream mathematical use.

## Reassessment of earlier evidence

The retained XML files were parsed again, and suite test counts were checked
against their actual `<testcase>` entries. The following are **historical
executions**, not newly executed tests during this review:

| Historical evidence | Methods | Failures / errors / skipped | Scope |
| --- | ---: | --- | --- |
| Final local JVM XML | 80 | 0 / 0 / 0 | Common tests plus JVM-specific arithmetic and concurrency tests |
| Final local Linux Native XML | 71 | 0 / 0 / 0 | Common regression suite |
| Final local JS Node XML | 71 | 0 / 0 / 0 | Common regression suite |
| Final local Wasm Node XML | 71 | 0 / 0 / 0 | Common regression suite |
| CI `40294194097`, Linux retained summary | 293 | 0 / 0 / 0 | JVM + Linux Native + JS + Wasm aggregate |
| Same CI, Android retained summary/log | 71 | 0 / 0 / 0 | AAR build and **host JVM** unit tests; not ART |
| Same CI, iOS retained summary/log | 71 | 0 / 0 / 0 | `iosSimulatorArm64Test`; iOS device compilation was separate |

Source identity and aggregate checks are recorded in
[baseline-evidence-review.json](evidence/security/baseline-evidence-review.json).
The original [CI ledger](../validation.md) links raw logs and artifact
identities. Its distinction between PR-head and GitHub-generated test-merge
commits is material: the stored comparison demonstrates equal source trees.
This review did not download new copies of the old GitHub artifact ZIPs.

Earlier SR-09 and SR-10 are backed by more than prose: the zero-scalar fixed
expectations and carry tests exist in common tests, and the recorded final
platform suite includes them. The previous emitted-JS inspection is scoped
to the actual Kotlin 2.2.21 development-test output matched to executed
packages; it is not an analysis of arbitrary future compiler versions,
minified production bundles, native assembly, or every JavaScript engine.

## Remaining limits and downstream implications

1. **Runtime timing remains unproven.** Source-level masks, full-width loops
   and removal of identified boxed-Long helper paths reduce obvious problems.
   They do not establish strict constant-time execution on JVM, ART, Native,
   JS or Wasm. There is no measured timing/cache/power/fault-resistance
   certification or complete compiler-output review.
2. **Erasure remains best effort.** The tests inspect owned arrays after
   ordinary terminal paths. They cannot establish erasure of registers,
   compiler/runtime copies, collected heap pages, or stores removed by an
   optimizer. Allocation failure before a temporary enters its cleanup scope
   and catastrophic VM failures are not exhaustively injected or covered.
3. **Entropy quality is the caller's responsibility.** Width checks, rejected
   out-of-range candidates and a finite retry limit cannot detect a predictable
   but in-range source. A forced zero draw can expose the password mask before
   later failure. Historical Legacy zero handling is compatibility behavior,
   not an entropy-health test.
4. **Concurrency tests are bounded.** Deterministic JVM tests and common
   reentrancy tests establish particular overlap paths. They do not enumerate
   every memory-model or scheduler interleaving. Each session must be used
   serially; concurrent misuse is terminal.
5. **Allocations are material.** The retained JVM probe reports approximately
   28.24 MiB, 12.98 MiB and 12.81 MiB of calling-thread allocations per complete
   two-party P-256, Edwards and Legacy fixture exchange respectively. These
   are small-sample JVM observations, not retained-secret measurements,
   Android/Native predictions or side-channel results. Repeated array and
   point temporaries are visible in source, but allocation-site attribution
   and a controlled cross-runtime performance baseline are still absent.
   See [performance.md](../performance.md) and its raw records. No performance
   improvement is claimed from this tool-only change.
6. **Legacy HARDENED is still an unconfirmed protocol API.** The mode selects
   the pinned scalar correction; it does not add mutual confirmation, an MHF,
   or RFC semantics. Kadb must retain its surrounding authentication and use
   the exact identities/roles and key processing required by that protocol.
   Historical acceptance of noncanonical/low-order inputs must not be
   described as security hardening. The Kotlin API intentionally makes
   failure terminal even where the original C context could be retried.
7. **Independent references are finite and test-only.** Fixed vectors,
   differential arithmetic and bounded malformed-input cases give substantial
   functional evidence. They do not constitute a PAKE proof, comprehensive
   fuzzer campaign, or validation of every possible input. Legacy's 512-case
   corpus is not a security certification.

From this security-review scope, the unchanged HARDENED Legacy core has
preserved the evidence needed for a **pinned experimental downstream
dependency**. Actual Maven/variant resolution, linked consumer execution,
Android ART coverage and the final source/CI binding are separate acceptance
gates. Public distribution policy and AGPL obligations also remain separate.
Nothing in this report elevates the project to production-audited
cryptographic software.
