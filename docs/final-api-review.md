# Final API and documentation consistency review

Review date: **2026-10-26**. This is a separate-author engineering review inside
the implementation team, not an external security audit. The reviewer previously
implemented the hash functions and reference-vector tooling, but did not implement
the public RFC/Legacy session classes reviewed here.

This pass compares `README.md`, `API.md`, and `conformance.md` with the actual
public API, failure paths, and the scope of the existing tests. It did not invoke
Gradle. Later authorized isolated JVM checks are recorded separately below;
they do not replace the coordinated multiplatform build/test execution.
Executed builds and their exact revisions belong to [validation.md](validation.md).

The focused SR-09 correction and documentation corrections have been inspected
in source. The subsequent JavaScript runtime-helper hardening source was also
reviewed. Production source digests are now frozen in
`test-logs/final-api-review-source-sha256.json`; documentation revisions remain
separate. Kotlin regression execution and emitted-JavaScript inspection remain
pending at this source-review checkpoint.

## Findings and disposition

| ID | Finding | Required disposition at this checkpoint |
| --- | --- | --- |
| SR-09 | The initial RFC sampler rejected zero and therefore chose from `[1,q)`, whereas RFC 9382 section 3.3 specifies `[0,q)`. Documenting the difference did not remove it. | **Fixed in source; source and regression code reviewed.** The zero exclusion is removed; out-of-range rejection and fail-closed degenerate-point checks remain. Kotlin execution of the new regression is pending at this checkpoint. |
| API-01 | The initial exception KDoc said secret values were never included, and API documentation made an unqualified statement about error contents. Both sessions preserve an entropy source's thrown exception as the cause. Its message is application-controlled and is not sanitized. | **Corrected and inspected.** KDoc/API now distinguish library-generated messages from unsanitized caller-originating causes. |
| API-02 | The initial conformance document implied a canonical mixed-order Edwards point was accepted whenever its prime-order component was nonzero. A point of the form `wN + T`, where T is torsion, can have a nonzero prime-order component yet become identity after unmasking and cofactor clearing. | **Corrected and inspected.** API/conformance text now makes acceptance subject to a nonidentity unmasked shared value. |
| API-03 | The README described the verification workflow as running its platform jobs. Configuration alone is not evidence that each job has executed successfully. | **Corrected and inspected.** It now says “is configured to run”; actual outcomes remain separate in the validation ledger. |

### SR-09: range correction and zero behavior

[RFC 9382 section 3.3](https://www.rfc-editor.org/rfc/rfc9382.html#section-3.3)
includes zero in the ephemeral sampling interval; section 7 requires uniform
random choices. The source correction removes only the zero rejection. This
preserves the nonzero path and corrects the sampling interval; re-execution of
the established vectors remains required. A canonical zero candidate now
consumes one entropy draw rather than being silently resampled.

For `w = 1` and zero ephemeral scalar, the public message is exactly the
published role-specific M or N. Independent expected bytes can be obtained
directly from the Edwards constants and by decompressing the published P-256
constants with python-ecdsa 0.19.1. No production Kotlin encoding operation is
needed to construct these expectations. Shared-point processing then reaches
identity and the existing protocol boundary must reject it, erase state, and
release no session key. With both `w = 0` and zero ephemeral scalar, generation
already reaches the rejected identity public value.

The new `zeroEphemeralMatchesPublishedMasksAndCannotReleaseAKey` regression
checks those outputs and failure stages for both suites and roles, one entropy
draw, owned-state erasure, and terminal reuse failure. Existing rejection-limit
cases now use candidates at or above the order rather than treating a zero byte
string as intrinsically malformed. This reviewer independently checked all four
stored expectations against the test literals and recomputed both P-256
decompressions with python-ecdsa 0.19.1. That reference check is separate from
the pending Kotlin test execution.

This range correction cannot repair bad entropy. As an algebraic consequence,
an attacker who knows an ephemeral scalar is zero can compare the emitted
password mask against candidate passwords. Failing later at the shared-point
check does not erase that public message. A functioning, independent CSPRNG is
therefore a security requirement; byte-count and range checks cannot certify
it. A single zero result is also a possible output of a functioning CSPRNG and
does not by itself prove an entropy-source failure.

## Contracts checked against source

The RFC API takes a canonical 32-byte big-endian password scalar. It performs
no implicit password normalization, MHF, salt selection, or password hash.
Both roles supply identities in Alice/Bob order; the Legacy constructor instead
takes local/remote identities and orders them internally. Absent RFC identities
require the explicit implicit-identity setting. The 8,176-byte AAD bound equals
the section 3.2 limit of `(2^16 - 128)` bits; the separate 65,535-byte identity
limit is a local resource policy.

The public RFC processing method returns its own confirmation MAC. It does
not return the shared group element, transcript secrets, or pending session
key. `confirm` checks the fixed-length peer confirmation before copying out
the 16-byte key. Repeated, out-of-order, failed, or destroyed session use is
terminal. Separate public classes, states, and artifacts isolate Legacy; the
RFC production artifact has no dependency on the Legacy artifact.

The README's complete exchange example uses nested cleanup so that a failed
Bob construction still destroys Alice. It erases Bob's returned key if Alice's
subsequent confirmation fails. Returned successful keys remain caller-owned.
Constructor/message inputs are copied; callers keep responsibility for their
original password/scalar buffers. Entropy arrays have a different contract:
ownership transfers to the library, and providers must not retain mutable
aliases. Session cleanup cannot erase secrets captured by an application-owned
entropy-source closure or copies held by the application.

Ordinary failures wipe the owned protocol arrays. Public claims correctly
exclude guaranteed physical erasure of runtime copies, registers, interrupted
processes, and allocation failures outside cleanup scopes. The atomic gate is
a synchronization mechanism, not a platform cryptographic backend. Its release
is the operation's publication boundary; later misuse cannot revoke a key
already published to the caller.

Legacy returns a 64-byte unconfirmed result. Successful point processing does
not establish matching passwords, identities, or roles. The explicit unsafe
password-scalar mode, historical decoder behavior, zero-derived ephemeral
behavior, SHA-512 password operation, and stricter Kotlin lifecycle are
disclosed separately. The word `HARDENED` refers only to the historical scalar
correction and does not supply an MHF or mutual confirmation.

## Requirement coverage and limits

The entries below identify test code and recorded evidence. They are not new
test results from this documentation pass.

| Requirement | Existing evidence inspected | Limit that must remain explicit |
| --- | --- | --- |
| Independent RFC expected values | Four official P-256 vectors through all requested intermediates and both MACs; twelve Edwards vectors from independent Python arithmetic/hash references; public-API confirmation tests | Finite vectors establish the checked outputs, not exhaustive conformance or a new security proof. |
| Legacy compatibility | Historical 512-case corpus, deterministic exchanges, freshly rebuilt pinned C oracle and fresh adversarial peer fixtures | Wire compatibility does not certify the historical protocol's security or every C API retry/output-buffer convention. |
| Wrong password, identity, AAD, confirmation and role | `RfcFailureTest`, `LegacyLifecycleTest`, and `ProtocolIsolationTest` | RFC mismatches are detected through confirmation; Legacy intentionally lacks that authentication step. No implicit fallback is present. |
| Curve and scalar boundaries | Common math tests, pure-torsion/mixed-order tests, independent JVM BigInteger arithmetic and 87 selected Wycheproof cases | This is not the complete Wycheproof corpus. Canonical mixed-order input can still fail the unmasked shared-point check. SR-09 adds the missing public zero-ephemeral behavior. |
| Entropy faults, aliasing and ordinary cleanup | Wrong-sized and throwing sources, bounded range rejection, copied-input tests, returned-entropy erasure and white-box owned-array assertions | No test can establish entropy quality from returned bytes. These checks do not inspect physical remnants or every arithmetic temporary after allocation failure. |
| Reuse, destruction and concurrency | Repeated operation/destroy tests, reentrant callbacks, and one blocked-owner real-thread regression for each protocol API | The deterministic schedules are meaningful regressions, not a model check of every interleaving or proof across Native runtimes. |
| Fuzzing and differential tests | Bounded deterministic malformed-length RFC cases; explicit point cases; freshly generated Legacy peer corpus; independent integer and pinned oracle differentials | RFC length fuzzing skips correctly sized point messages and must not be advertised as a coverage-guided decoder fuzz campaign. Separate finite decoder corpora supply that additional evidence. |
| Performance and memory | Reproducible warmed JVM exchanges, independently checked expected keys, thread allocation counters and GC observations in `performance.md` | Allocation volume is not a retained-heap/leak result. Three warmup rounds and ten samples per mode are not a timing side-channel assessment or stabilized JMH campaign. |
| Cross-platform execution | Per-platform commands and results in the validation ledger, plus the configured CI jobs | A configured target, compiled binary, host-side Android test and physical device run are distinct claims. This review performs none of those runs. |

The library should remain experimental. Neither this documentation review nor
the separate mathematical/security reviews establish strict constant-time
generated code, verified physical erasure, or an external production audit.

## JavaScript runtime-helper follow-up

A separate review of the pinned Kotlin 2.2.21 standard-library source identified
value-dependent branches in the boxed `Long.multiply` helper and a sign branch
in `fromInt`. The former affects the original Montgomery limb multiplication;
the latter affects SHA-512 input loading when a signed byte is widened before
masking. This evidence concerns the runtime source, not yet inspected emitted
JavaScript for the corrected build. Its pinned artifact identity is recorded in
`test-logs/js-runtime-review-source.json`.

The SHA-512 load now masks the byte to an `Int` in `[0,255]` before converting
to `Long`, so the inspected conversion helper's sign decision has a fixed
outcome. The inspected helper contains no integer-value cache. Other widening
sites concern public constants, public lengths/offsets, or the separately
reviewed Montgomery multiplication. SHA-512 rounds use addition and bitwise
operations; their inspected boxed helpers have no operand-dependent branch.
Shift helper branches depend on the public shift count used by the algorithm.

The Montgomery implementation now uses fixed-width `Int` limbs with explicit
unsigned carry extraction and the same algorithm on every target. Its actual
source diff was independently inspected: the exact inner-sum bound is
`2^32-1`, so wrapping arithmetic followed by the low-16-bit mask and unsigned
right shift preserves the required limb and carry. Extra-limb propagation and
the final subtraction are retained; no source arithmetic defect was identified.
Details are in [math-review.md](math-review.md).

Regression execution and inspection of the emitted JavaScript remain pending
at this checkpoint. Removing these identified helper branches does not
establish strict constant-time behavior of the compiler, JIT, allocator or
processor.

## Authorized isolated verification after source freeze

The corrected SHA-512 sources passed a new run of all **671 independent
hashlib/hmac differential comparisons**. The script used the documented
Kotlin 2.0.21 bootstrap compiler from Gradle 8.14.3 and OpenJDK 17.0.21+9.
The exit code was zero and source digests were unchanged before and after the
run. Exact command, environment, output and digests are under
[`test-logs/hash-differential-final/`](test-logs/hash-differential-final/).
This bootstrap check is separate from the project's pinned Kotlin 2.2.21
Gradle verification.

The frozen corrected production sources were also compiled with Kotlin 2.2.21
for a new isolated JVM performance probe. All **39 complete two-party exchanges**
matched independent expected keys, including warmups. Its production-source
digests match this review's 13-file manifest. Timing, allocation, environment
and limitations are recorded in [performance.md](performance.md) and the new
`test-logs/jvm-performance-final/` directory. Historical measurements were
preserved. This fixed-input probe does not execute the zero-ephemeral failure
test or replace the full arithmetic and cross-platform regressions.

## Source snapshot

[`test-logs/final-api-review-source-sha256.json`](test-logs/final-api-review-source-sha256.json)
records every production `commonMain` Kotlin source after the coordinated
SR-09/SR-10 source freeze. It includes the recording time and the existing Git
HEAD; the reviewed worktree also contains the identified corrections awaiting
their next commit. The per-file digests identify the actual reviewed source
without falsely attributing uncommitted changes to that earlier HEAD.

Mutable documentation is excluded from this source manifest. Later build
results must identify their actual snapshots and should be recorded in the
validation ledger rather than retroactively assigned to this source review.
