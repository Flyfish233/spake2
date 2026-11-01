# Security review of protocol, lifecycle, hashes, and KDFs

Review date: **2026-10-26**. Status: **experimental; not approved for production**.

This is an author-separated review within the same AI-assisted engineering
team. The reviewer did not implement the SHA/HMAC/HKDF functions, the RFC
protocol, or the Legacy protocol reviewed here. The reviewer did implement
`ModMath` and `P256`; their tests and arithmetic analysis are supporting
evidence, **not an independent audit of those two implementations**. This
document does not represent an external professional audit, formal proof, or
runtime side-channel certification.

The final implementation corrections are at
[`b837aba27bebc1050965fa124c2efa54b4e4544a`](https://github.com/Flyfish233/spake2/commit/b837aba27bebc1050965fa124c2efa54b4e4544a).
The [validation ledger](validation.md) records the completed cross-platform
run and links its per-method evidence. The [final API review](final-api-review.md),
[carry-bound review](math-int-carry-review.md) and
[emitted-JS review](js-runtime-review.md) separate later source findings from
the historical observations below.

## Scope and result

The review inspected the actual common Kotlin implementations, public API
state transitions, copied-input ownership, failure cleanup, the test runners
and their expected-value provenance, and build/runtime dependency declarations.
It compared Legacy operations with the pinned BoringSSL implementation and
independently re-executed its deterministic cases.

Four concrete lifecycle/input-handling defects and a cleanup-allocation issue
were reported during implementation. Their fixes were inspected in the current
source. Documentation, provenance, and coverage findings were also corrected as
recorded below. The reviewed hash and KDF code showed no additional algorithm
defect in this pass. Passing vectors and this review do not establish absence
of other defects. Residual assurance limits and compatibility hazards remain.

## Findings and disposition

Severity here describes the observed impact, not a claim of an exploitable
remote attack. Source-code fixes, executed regressions, and untested runtime
claims are deliberately distinguished.

| ID | Severity | Finding | Current disposition |
| --- | --- | --- | --- |
| SR-01 | Medium | A failed concurrent acquisition could miss the previous operation releasing a three-state gate, leaving the session usable after the rejected call. | **Fixed, source reviewed.** Terminal gate state and an exclusive cleanup-owner state prevent reopening. Existing blocked-owner and reentrant regressions pass; every scheduler interleaving has not been formally verified. |
| SR-02 | Low | RFC `confirm()` copied a peer-supplied confirmation before checking its length. A malformed large input caused avoidable allocation. | **Fixed.** The 32-byte bound is checked before copying. `malformedConfirmationLengthAndContentAreTerminal` covers short, exact-but-incorrect, and oversized inputs. |
| SR-03 | Low | The RFC constructor validated the caller's `w` before taking its owned copy. A caller racing a mutation could invalidate that check. | **Fixed, source reviewed.** The owned 32-byte copy is validated and erased if construction fails. This is not a promise of an atomic snapshot of arbitrarily concurrently mutated caller arrays. |
| SR-04 | Low | The Legacy idle-cleanup path initially published the terminal gate before erasing secrets, while `destroy()` treated that gate as already erased. Its white-box cleanup assertion also omitted identities. | **Fixed, source reviewed.** Gate 4 owns cleanup, gate 3 is published afterward, and both identities are included in the assertion. |
| SR-05 | Low / coverage | The initial bounded RFC fuzz test skipped correctly sized point messages; it exercised length handling, not the complete decoder. | **Coverage claim corrected and supplemented.** Length fuzzing remains described narrowly. Independent Wycheproof cases and explicit torsion/hostile-point tests exercise decoder behavior separately. The two Wycheproof JVM methods passed with all 87 selected cases; this is still finite corpus coverage. |
| SR-06 | Low / documentation | Initial provenance/security text omitted completed Legacy material and made the unqualified claim that there was no fast password hash. Legacy necessarily performs the pinned SHA-512 password operation. | **Corrected, text inspected.** `SECURITY.md` now scopes the no-default-hash statement to RFC and explicitly describes Legacy SHA-512. The provenance table has been expanded; a separate RFC stream-label issue is SR-08. |
| SR-07 | Low / cleanup hardening | RFC `erase()` and `TranscriptSecrets.destroy()` allocated an `arrayOf` before wiping their buffers. Allocation failure during cleanup could prevent those stores and terminal-state publication. | **Fixed, source reviewed.** Both cleanup functions now use direct `fill(0)` calls with no temporary reference-array allocation. This removes the identified allocation site; no general allocation-failure or physical-erasure guarantee is asserted. |
| SR-08 | Medium / provenance | The initial combined IETF notice attributed the section-4 BSD code-component license to RFC 9382. That RFC is in the IRTF stream, for which TLP section 8.g excludes section 4. | **Corrected against primary terms.** RFC 9382's original notice, the applicable alternate-stream rules, and the complete TLP reference are retained. RFC fixture/generator headers and `THIRD_PARTY.md` reflect those terms. The separate BSD notice is explicitly scoped to RFC 5869. |
| SR-09 | Medium / conformance | The initial RFC rejection sampler excluded zero, choosing from `[1,q)` instead of the section-3.3 interval `[0,q)`. Documentation alone did not resolve that mismatch. | **Source corrected and separately reviewed.** Canonical zero is consumed once, while degenerate public/shared points still fail before key release. Four independent M/N message expectations, zero-password cases, cleanup and terminal reuse form the regression; final execution evidence is recorded in the validation ledger. |
| SR-10 | Medium / runtime side channel | Kotlin/JS 2.2.21 boxed `Long` multiplication has value-dependent helper branches, reached by secret field limbs. Signed-byte widening before masking in SHA-512 can also take the helper's sign branch based on message data. | **Fixed and separately reviewed; cross-platform regressions passed.** Montgomery inner sums use wrapping 32-bit words with an exact carry bound; SHA-512 masks bytes before widening. The actual emitted Kotlin/JS paths were inspected and matched to the executed runtime packages. Independent arithmetic checks and the final CI run passed. These observations remove the identified helper paths; they do not establish strict constant-time execution. |

### SR-01/SR-04: concurrency reasoning

The rejected caller must account for the owner releasing its gate between the
failed acquisition and the poisoning attempt. A single best-effort `1 -> 2`
compare-and-set is insufficient. The repaired implementation rereads the gate:
it poisons a live owner, or atomically claims idle cleanup itself. The cleanup
owner erases buffers before publishing the permanent terminal state. Rejected
callers do not concurrently overwrite the active owner's secrets. An owner
whose gate was poisoned wipes its prospective return buffer and fails.

Successful gate release is the operation's publication boundary. A later
misuse cannot revoke a key already returned to the application. Applications
must still serialize each session's use. The deterministic concurrency tests
exercise a live owner blocked inside the entropy callback and reentrancy even
when the callback catches the inner exception; they do not exhaust all timing
interleavings of native or managed runtimes.

## Protocol and implementation checks

### RFC API and key release

`Rfc9382Session` was checked for these properties:

- The constructor accepts a canonical 32-byte big-endian password scalar and
  performs no password hashing. Application preprocessing/MHF policy remains
  outside the core. Empty identities require an explicit opt-in.
- Generated and received messages are copied, and the role and suite remain
  fixed for the lifetime of the object. There is no negotiation or automatic
  Legacy fallback.
- Public processing produces only the local confirmation message. The
  pending session key is private and is copied to the caller only after
  successful peer confirmation. Repeated release and out-of-order calls fail.
- Confirmation comparison scans every byte for equal-length inputs. A length
  mismatch is public and is rejected before the copy. This source-level
  comparison is not a timing guarantee for compiled runtimes.
- Ordinary invalid-message, wrong-password, wrong-identity, wrong-AAD,
  wrong-confirmation, entropy-failure, reuse, and destroy paths erase owned
  protocol buffers. Constructor failure erases its owned password scalar.

The transcript ordering, eight-byte little-endian length prefixes,
fixed-width big-endian `w`, Edwards scalar endian conversion, cofactor
placement after unmasking, AAD inclusion in confirmation-key derivation, and
key/confirmation lengths were checked against [RFC 9382 sections
3, 4, 6 and 7](https://www.rfc-editor.org/rfc/rfc9382.html). The four official
P-256 vectors check every requested intermediate. Edwards expected values come
from independent test-only arithmetic references documented in
[reference-vectors.md](reference-vectors.md).

Following SR-09, the application-facing API samples the full `[0,p)` interval.
A canonical zero candidate is consumed once rather than resampled. With w=1,
the public message is the published role-specific M or N, and the subsequent
identity shared point is rejected. With w=0 and zero ephemeral, the public point
itself is rejected. These are explicit degenerate-exchange failures, not a change
to the sampling interval. An attacker who can force or predict a zero draw can
test password guesses against the published mask; failing later cannot undo
that disclosure. Byte count and bounded range rejection cannot certify entropy
quality. Password scalar zero remains a supported canonical core input.

### SHA-256 and SHA-512

The round functions, initial values, schedule, rotations, block parsing, digest
serialization, and padding were inspected against [FIPS
180-4](https://csrc.nist.gov/pubs/fips/180-4/upd1/final). Integer overflow in
round additions is the intended word arithmetic. Input length arithmetic is
performed in `Long` before deriving block offsets, avoiding a signed `Int`
overflow near the maximum possible `ByteArray` length. For SHA-512 the high
64 bits of its 128-bit length field are zero for every supported `ByteArray`.

Both functions process a reusable block buffer rather than allocating a padded
copy of the entire message. They erase state, schedule, and block arrays in
`finally`. Working registers, runtime copies, and compiler-eliminated stores
are not covered by a physical-erasure claim. Control flow depends on input
length and block position, not message byte values at source level.

### HMAC-SHA256 and HKDF-SHA256

HMAC handles keys shorter than, equal to, and longer than its 64-byte block,
including an empty key. Hashing long keys, both pads, inner/outer digest
construction, output ownership, and buffer erasure were inspected. Its
message-length check bounds the `64 + message.size` allocation.

HKDF follows extract then expand, with the previous block, information bytes,
and a one-byte counter in each expansion step. It enforces the 8,160-byte
SHA-256 output limit and the minimum PRK length for direct expansion. Empty
salt is equivalent to the specified zero salt because HMAC pads both to the
same block. The information-length bound accounts for both concatenation and
the HMAC prefix. Returned output does not alias PRK or temporary blocks. These
checks were compared with [RFC 5869 section
2](https://www.rfc-editor.org/rfc/rfc5869.html#section-2), while [RFC 4231
vectors](https://www.rfc-editor.org/rfc/rfc4231.html) independently exercise
HMAC behavior.

### Legacy protocol

The reviewer inspected the pinned
[BoringSSL SPAKE25519 source](https://github.com/google/boringssl/blob/9e04aed2d1652441d2c84e85ed21acc05e053260/crypto/curve25519/spake25519.cc)
alongside `LegacySpake25519`. Checked details include role-dependent M/N
selection, 64-byte little-endian entropy reduction, multiplication by eight
without dropping bit 255, SHA-512 password hashing, the three conditional
subgroup-order additions, exact peer wire bytes in the length-prefixed
transcript, and the 64-byte SHA-512 result.

`PRE_HARDENING_UNSAFE` is an explicit choice and preserves the historical
small-subgroup leakage of password-hash information. It is never selected
automatically. Non-canonical point acceptance, low-order cases, and identity
after unmasking intentionally preserve the specified Legacy behavior. These
are compatibility constraints, not evidence of RFC conformity.

The API name `deriveUnconfirmedKey` accurately describes the output. Correct
point processing alone does not authenticate the password, identities, or
roles. A surrounding protocol must provide mutual confirmation before using
the key for application traffic. In particular, the word `HARDENED` refers to
the historical scalar correction; it does not make the returned key
authenticated or convert the Legacy SHA-512 password operation into an MHF.

The pinned reduction also permits a zero ephemeral scalar if the supplied
entropy reduces to zero. The library cannot make a faulty or predictable
caller-supplied entropy source secure. Strict Legacy wire compatibility must
not be advertised as entropy-health testing. Ordinary wrong-sized entropy and
source exceptions are rejected and clean up state.

This review independently generated a pinned-C boundary case with Alice's
entropy equal to 64 zero bytes and Bob's equal to little-endian one. The
reference accepted both endpoints, with private scalars zero and eight.
The resulting fixed expected outputs were supplied for an additional Kotlin
regression. The follow-up JVM XML for `LegacyZeroEntropyTest` records that
regression passing, separately from the reference generation.

The Kotlin state machine is intentionally stricter than the C API: malformed
processing permanently consumes the session, while the pinned C context can
return failure without changing its state. Identity/password resource caps and
the always-64-byte Legacy return are also local API policy. Compatibility
claims concern the demonstrated wire and derivation behavior, not every
context-retry or output-buffer convention of the C API.

## Independent evidence inspected or executed

| Evidence | What was actually observed | What it does not prove |
| --- | --- | --- |
| Initial complete Gradle JVM test XML | 68 test methods in 28 suites, zero failures, errors, or skips | This historical checkpoint predates module separation; it is not the final aggregate and does not report other target execution. |
| Follow-up module JVM XML inspected directly | `WycheproofP256Test`: 2 methods, 87 selected cases; `LegacyZeroEntropyTest`: 1 method; zero failures, errors, or skips in both XML files | These specific results do not imply a fresh aggregate count. Final module totals and platform results belong to the validation ledger. |
| SHA/HMAC/HKDF tests and bootstrap report | Standard known-answer cases, million-`a` cases, 25 padding-boundary pairs, and recorded 671 independent Python comparisons | No compiled-runtime side-channel analysis. |
| RFC test code and expected-value generation | Every official P-256 field, independent Edwards cases, all eight pure torsion encodings, seven mixed-order encodings, and public-API failure/lifecycle tests | Not a formal PAKE proof or exhaustive hostile-input search. |
| Legacy regeneration manifest | Harness, oracle executable, and both historical input-file SHA-256 digests independently matched the files on disk | A digest records identity, not trustworthiness by itself. |
| Independent C-oracle re-execution during this review | All seven deterministic exchanges, both endpoints: 14 cases matched all six returned fields, including message, private scalar, password scalar, password hash, status, and key | This independent reviewer did not rerun all 512 historical exchanges; the separate regeneration report records that larger run. |
| Common Legacy test runners | Historical and fresh expected outputs are asserted byte-for-byte, rather than merely comparing local Alice and Bob | Passing compatibility tests is not general security certification. |
| Runtime source/dependency scan | No core JCA/JCE, JNI, OpenSSL, BoringSSL, libsodium, CryptoKit, native crypto binding, or platform `expect/actual` crypto backend; only Gradle's build-bootstrap JAR found outside generated build output | Must be repeated when dependency declarations or artifact packaging changes. |

The C source independently inspected in this review has SHA-256:

```text
4600515e96aa209e27174cc17595b7202ef1ede97685b87a289cac827b348a6a
```

The full commands, historical replay counts, and platform-specific execution
results belong to [validation.md](validation.md) and
[LEGACY_COMPATIBILITY.md](LEGACY_COMPATIBILITY.md). A later build result must
be recorded there rather than retroactively attributed to this checkpoint.

## Dependencies, ownership, and provenance

The production implementation uses common Kotlin and the standard-library
atomic gate. Platform atomics are synchronization, not delegated cryptography.
The caller supplies entropy explicitly. Test-only references use Python
`hashlib`/`hmac`, python-ecdsa, cryptography/OpenSSL, JVM BigInteger, and compiled
BoringSSL outside the library. They are not runtime backends.

Copied historical Kotlin fixture headers correctly distinguish the original
AGPL test harness from GPLv3-licensed fixture data and identify the source
commit. The original fixture files and upstream license are retained. The
reference BoringSSL license is retained separately. RFC fixture headers retain
their IETF attribution and distinguish the IRTF stream from the IETF stream.
[THIRD_PARTY.md](../THIRD_PARTY.md) identifies these materials and the retained
license/notice files. The selected Wycheproof data has separate Apache-2.0
attribution and the unmodified upstream license.

RFC stream attribution matters here: the [IETF Trust legal provisions,
sections 4.c and 8](https://trustee.ietf.org/documents/trust-legal-provisions/tlp-5/)
distinguish the section-4 code-component license from alternate-stream terms.
RFC 9382's IRTF header and copyright notice do not supply the same BSD legend
as RFC 5869. The corrected `RFC9382-IRTF-NOTICE` preserves the original notice
and sections 8.c/8.g. `TLP-2015-REFERENCE.txt` retains the complete official
policy body with a source URL and retrieval digest. That policy contains rules
for several streams; its inclusion is not a grant of inapplicable section-4
terms. The BSD file is scoped to RFC 5869, and official RFC 9382 data is not
relabeled as original AGPL code. Fixture contents were unchanged by the notice
correction.

## Remaining assurance limits

1. **No strict constant-time guarantee.** Source masks and fixed schedules do
   not establish JVM/ART, Native, JS, or Wasm machine-code behavior. No timing,
   cache, JIT, power, or fault-resistance certification was performed.
2. **Best-effort erasure.** Owned arrays are overwritten on ordinary terminal
   paths. Registers, moved heap copies, debugger snapshots, process crashes,
   allocation failures before cleanup scopes, and eliminated stores remain
   outside the guarantee.
3. **Independent vectors are finite.** The arithmetic, protocol, and decoder
   corpora target concrete error classes; they cannot establish exhaustive
   correctness. Concurrent scheduling has not been model checked.
4. **Entropy and password policy are application obligations.** Correct
   byte counts and range rejection do not detect a malicious or predictable
   entropy source. RFC `reduceWideScalar` performs mathematical reduction,
   not password hardening. Legacy semantics require separate treatment.
5. **Platform evidence is explicit.** Compilation must not be represented as
   executed device tests. Consult the current validation ledger for each
   target; this review itself does not assert an iOS or Android device run.

The library should remain experimental until a separate cryptographic review,
broader platform testing, and a deliberate assessment of the target runtimes'
side channels have been completed.
