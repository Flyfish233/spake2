# Public API and lifecycle

## Entropy and ownership

`EntropySource` has one method: `nextBytes(size: Int): ByteArray`. Implementations
must return a fresh array of exactly the requested size containing CSPRNG output,
transfer ownership, and throw on failure. Library code wipes the transferred array.
A retained or concurrently mutated alias violates this contract. Standard-library
atomics synchronize session access; platform cryptographic backends are not used.

RFC requests 32 bytes for each rejection-sampling candidate, with a maximum of 128
attempts. Edwards unused high bits are masked before rejection. Values at or above
the subgroup order are rejected; accepted values are uniform in `[0,q)` when source
bytes are uniform. A canonical zero draw is consumed once, not resampled. It cannot
complete this API's exchange because its shared point is the identity, which is
rejected before confirmation or key release. Legacy requests 64 bytes and preserves the pinned
reduce-mod-L/multiply-eight behavior, including zero-derived results.

Constructors and message-processing functions defensively copy caller data. The
caller remains responsible for its original password/scalar input and for erasing
the authenticated RFC key or unconfirmed Legacy result after use. The entropy
source itself remains an application-owned object; do not capture unnecessary
password/session material in its implementation.

## RFC9382Session

Construction requires a suite, `Role.ALICE` or `Role.BOB`, canonical 32-byte BE `w`,
ordered Alice/Bob identity bytes, optional AAD and entropy source. Both roles must
use the same Alice/Bob identity order. The constructor never hashes a password.

| Method | Required state | Successful state | Return value |
|---|---|---|---|
| `generateMessage()` | `NEW` | `MESSAGE_SENT` | Own 65-byte P-256 or 32-byte Edwards message |
| `processPeerMessage(peer)` | `MESSAGE_SENT` | `AWAITING_CONFIRMATION` | Own 32-byte HMAC confirmation |
| `confirm(peerConfirmation)` | `AWAITING_CONFIRMATION` | `COMPLETE` | Authenticated 16-byte Ke, exactly once |
| `destroy()` | Any state without another active operation | `DESTROYED` | No key; idempotent cleanup |

An ordinary operation failure erases owned buffers and consumes the session.
Constructor bounds fail with `IllegalArgumentException`; protocol operations wrap
ordinary failures in `Spake2Exception`. Library-generated messages omit secret
bytes. Caller-provided entropy exceptions are retained as causes and are not
sanitized; an application must avoid including secrets in its own exception text.
A destroyed, complete or failed instance cannot start another exchange. Both
parties must complete their own confirmation check before treating their local
exchange as authenticated. This does not provide transport-level proof that the
other party received the final network message.

A concurrent/reentrant attempt poisons an active operation. The owner checks the
atomic gate before publishing its result; a poisoned result is erased and rejected.
If the owner already released the gate, the rejected caller claims cleanup and
terminates the object. Terminal cleanup is published after buffers are overwritten.
Separate JVM tests exercise real threads; JS/Wasm atomics follow Kotlin's documented
single-thread implementation. Arbitrarily mutating input arrays across threads is
not supported.

P-256 messages must be canonical uncompressed SEC1. Edwards messages use canonical
compressed RFC 8032 encoding. Low-order/identity outcomes are rejected in this API;
canonical mixed-order Edwards inputs are processed with explicit cofactor clearing,
subject to a nonidentity unmasked shared point.
A peer's original valid wire bytes, not a re-encoding, enter the transcript.

## Rfc9382Suite

Only `P256_SHA256` and `EDWARDS25519_SHA256` are implemented. The enum exposes the
standard protocol name and wire-message size. `scalarOrder()` returns an owned
32-byte big-endian copy. `reduceWideScalar(bytes)` mathematically reduces a 40–1024
byte big-endian integer; its caller must already have specified an appropriate
application password-preprocessing protocol. The function leaves its input owned
by the caller and returns a canonical 32-byte result, including zero when applicable.

## LegacySpake25519

Construction requires role, **own** identity, peer identity and entropy source.
Unlike the RFC constructor's ordered A/B identity parameters, each Legacy endpoint
supplies its local/remote identities; the implementation orders them in its transcript.

1. `generateMessage(password: ByteArray)` returns one 32-byte legacy point.
2. `deriveUnconfirmedKey(peerMessage: ByteArray)` returns one 64-byte SHA-512 result.
3. `destroy()` overwrites owned buffers and is idempotent without concurrent use.

The Legacy API has no built-in key-confirmation message. A successful return does
not authenticate password, identity or role. Its method name deliberately carries
this limitation. A higher-level protocol must define confirmation before using it
for authenticated application traffic. Wrong-password/identity behavior remains
historical compatibility behavior rather than an implicit RFC fallback.

`LegacyPasswordScalarMode.HARDENED` is the historical BoringSSL correction.
`PRE_HARDENING_UNSAFE` must be explicitly selected to preserve old leakage behavior.
Both variants keep the old masks, SHA-512 transcript and decoder. See the separate
compatibility document for exact byte semantics and reference fixtures.

## Unsupported or deliberately absent APIs

There are no MHF implementations or default password salts/work factors, transport
adapters, Kadb bindings, implicit negotiation, user-customizable M/N, RFC symmetric
M=N variants, other curves/hash/MAC suites, automatic entropy backend, publication
configuration, or authenticated-key getter before confirmation.

Shared `InternalSpake2Api` symbols are an implementation bridge between artifacts.
Their raw arithmetic methods intentionally permit values needed for mathematical
validation, and they do not establish protocol-level input or state guarantees.
They are not a supported application surface.
