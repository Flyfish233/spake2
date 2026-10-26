# Final API and documentation consistency review

Review date: **2026-10-26**. This is a separate-author engineering review inside
the implementation team, not an external security audit. The reviewer previously
implemented the hash functions and reference-vector tooling, but did not implement
the public RFC/Legacy session classes reviewed here.

This pass compares `README.md`, `API.md`, and `conformance.md` with the actual
public API, failure paths, and the scope of the existing tests. It did not invoke
Gradle; it inspected the coordinated JVM/JS result records and independently
read the emitted JavaScript. Authorized isolated JVM checks are recorded
separately below. Executed builds and their exact revisions belong to
[validation.md](validation.md).

The focused SR-09 correction and documentation corrections have been inspected
in source. The subsequent JavaScript runtime-helper hardening source was also
reviewed. The corrected source is remote commit
[`b837aba27bebc1050965fa124c2efa54b4e4544a`](https://github.com/Flyfish233/spake2/commit/b837aba27bebc1050965fa124c2efa54b4e4544a).
Its 13 production source digests match
`test-logs/final-api-review-source-sha256.json`. The recorded Kotlin 2.2.21
runs passed **80/80 JVM methods** and **71/71 JS common methods**, each with zero
failures, errors or skips. The actual corrected development-test JavaScript
has also been independently inspected. Documentation revisions remain separate
from these source and artifact snapshots.

## Findings and disposition

| ID | Finding | Current disposition |
| --- | --- | --- |
| SR-09 | The initial RFC sampler rejected zero and therefore chose from `[1,q)`, whereas RFC 9382 section 3.3 specifies `[0,q)`. Documenting the difference did not remove it. | **Fixed and regression verified on JVM/JS.** The zero exclusion is removed; out-of-range rejection and fail-closed degenerate-point checks remain. The new zero-ephemeral regression and existing vector suites passed in the corrected-source runs. |
| API-01 | The initial exception KDoc said secret values were never included, and API documentation made an unqualified statement about error contents. Both sessions preserve an entropy source's thrown exception as the cause. Its message is application-controlled and is not sanitized. | **Corrected and inspected.** KDoc/API now distinguish library-generated messages from unsanitized caller-originating causes. |
| API-02 | The initial conformance document implied a canonical mixed-order Edwards point was accepted whenever its prime-order component was nonzero. A point of the form `wN + T`, where T is torsion, can have a nonzero prime-order component yet become identity after unmasking and cofactor clearing. | **Corrected and inspected.** API/conformance text now makes acceptance subject to a nonidentity unmasked shared value. |
| API-03 | The README described the verification workflow as running its platform jobs. Configuration alone is not evidence that each job has executed successfully. | **Corrected and inspected.** It now says “is configured to run”; actual outcomes remain separate in the validation ledger. |

### SR-09: range correction and zero behavior

[RFC 9382 section 3.3](https://www.rfc-editor.org/rfc/rfc9382.html#section-3.3)
includes zero in the ephemeral sampling interval; section 7 requires uniform
random choices. The source correction removes only the zero rejection. This
preserves the nonzero path and corrects the sampling interval. The established
vectors and new zero-ephemeral regression were re-executed successfully on
JVM and JS. A canonical zero candidate now consumes one entropy draw rather
than being silently resampled.

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
the successful Kotlin regression execution recorded below.

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
| Cross-platform execution | Corrected-source JVM 80/80 and JS 71/71 result records inspected; other platform commands and outcomes remain in the validation ledger | A configured target, compiled binary, host-side Android test and physical device run are distinct claims. This reviewer did not run the coordinated Gradle jobs and does not extend these results to Native, Wasm, Android or iOS. |

The library should remain experimental. Neither this documentation review nor
the separate mathematical/security reviews establish strict constant-time
generated code, verified physical erasure, or an external production audit.

## JavaScript runtime-helper follow-up

A separate review of the pinned Kotlin 2.2.21 standard-library source identified
value-dependent branches in the boxed `Long.multiply` helper and a sign branch
in `fromInt`. The former affected the original Montgomery limb multiplication;
the latter affected SHA-512 input loading when a signed byte was widened before
masking. That initial evidence concerned the runtime source; its pinned artifact
identity is recorded in `test-logs/js-runtime-review-source.json`. The corrected
build's actual emitted JavaScript was subsequently inspected independently.

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

The actual Kotlin 2.2.21 JS IR development-test artifacts use boxed Long values.
In all three module variants, SHA-512 emits the `& 255` mask inside the argument
to `fromInt`. That helper always constructs a Long and contains no lookup cache.
Its sign condition therefore has a fixed outcome for the masked byte domain.
The inspected Long constructor and getters use plain fields. Secret compression
paths use fixed chunk additions, direct bitwise operations, and logical shifts
whose branches depend on fixed/public counts. The signed `Long.shiftRight`
helper contains a sign-dependent branch but is not called by this SHA-512 path.
Output byte conversion reads the low word and performs integer shifts.

Generated lazy-initialization guards depend on module/singleton state and
initialize public constants; they do not choose a path from a secret word.
Generated Montgomery multiplication uses `Int32Array(33)`, `Math.imul`, `| 0`
sum wrapping and `>>> 16` carry extraction, with public loop-control branches
and no boxed-Long multiplication. The independent read-only check matched all
seven primary artifact hashes/lengths and both primitives/runtime artifact
hashes in each of the three module variants against
[`test-logs/js-runtime-emitted.json`](test-logs/js-runtime-emitted.json).

The inspected artifact hashes, helper details and compiler settings are recorded
in [js-runtime-review.md](js-runtime-review.md). This conclusion applies to the
specified development-test JavaScript, not to an uninspected minified production
build, another compiler or another Long representation. Removing these
identified helper branches does not establish strict constant-time behavior
of the compiler, JIT, allocator or processor.

## Coordinated JVM/JS execution records inspected

The result records identify corrected remote commit
`b837aba27bebc1050965fa124c2efa54b4e4544a` and the frozen build-input manifest.
They include the original suite-report paths and hashes, so method totals can
be checked independently of fixture loops.

| Target | Recorded module method counts | Result record |
| --- | --- | --- |
| JVM | RFC 23, Legacy 31, primitives 26: **80 passed**, zero failures/errors/skips | [final-platform-jvm.json](test-results/final-platform-jvm.json) |
| JS / Node | RFC 22, Legacy 30, primitives 19: **71 passed**, zero failures/errors/skips | [final-platform-js.json](test-results/final-platform-js.json) |

Both runs include the zero-ephemeral regression and portable maximal-carry
regression. JVM additionally executes the direct BigInteger carry-boundary
regression and the JVM-specific differential/concurrency tests; those extra
tests are not silently counted as JS executions. Native and Wasm final execution
status belongs to [validation.md](validation.md), separately from this review's
JVM/JS record inspection. Android and iOS outcomes must likewise retain their
own build/test evidence.

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
SR-09/SR-10 source freeze. At capture time, the corrections were in the worktree
and the file recorded the earlier Git HEAD for context; that earlier HEAD is
not presented as the revision containing the corrections. The 13 per-file
digests now match corrected remote commit
[`b837aba27bebc1050965fa124c2efa54b4e4544a`](https://github.com/Flyfish233/spake2/commit/b837aba27bebc1050965fa124c2efa54b4e4544a),
also identified in the coordinated JVM/JS result and emitted-artifact records.

Mutable documentation is excluded from this source manifest. Later build
results must identify their actual snapshots and should be recorded in the
validation ledger rather than retroactively assigned to this source review.

## Final documentation consistency pass

A final documentation-only pass on 2026-10-26 checked `README.md`, `API.md`,
`conformance.md`, `SECURITY.md`, `THIRD_PARTY.md`, the retained third-party
notices, and the mathematical/runtime/security review reports. The resulting
wording corrections were inspected after application:

- The 128-draw limit detects repeated out-of-range entropy candidates; it
  does not detect predictable in-range output or certify source quality.
- The application password-preprocessing obligation is explicitly scoped to
  the RFC API, while Legacy retains its pinned SHA-512 password handling.
- The backend exclusion describes core cryptographic operations and does not
  prohibit a caller's secure entropy provider from using its platform.
- Portable carry tests are shared with every configured target; actual target
  execution is established by the validation records, not configuration alone.
- The arithmetic review acknowledges the completed, specific emitted-JS
  inspection while retaining the limitations on other generated code, future
  compilers, optimization settings, and timing analysis.

The public examples and lifecycle descriptions remain consistent with the
reviewed API's confirmation boundary, caller-owned returned keys, copied
inputs, transferred entropy buffers, and explicitly unconfirmed Legacy output.
The provenance inventory agrees with the retained notices and does not label
imported RFC, GPLv3 fixture, or Apache-2.0 fixture data as original AGPL code.
This is an attribution consistency check, not a legal certification.

Historical test counts remain identified as earlier checkpoints. In
particular, Android host unit tests and desktop JVM tests have different
source sets and must retain their separate method totals; host-side Android
success does not establish ART or device execution. Current platform results
and their exact code snapshots remain in the validation ledger. This final
pass added no test executions and extends none of the runtime or security
claims above.
