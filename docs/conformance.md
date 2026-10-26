# Protocol scope, conformance evidence, and deliberate restrictions

The implementation is experimental. Matching the recorded vectors establishes
the tested protocol outputs; it is not a certificate of complete RFC
conformance, an external security audit, or a guarantee about every target
runtime. Platform execution status is recorded separately in
[validation.md](validation.md).

## Supported protocol choices

| Explicit choice | Implemented behavior | Confirmation/output |
| --- | --- | --- |
| `Rfc9382Suite.P256_SHA256` | Fixed RFC M/N, uncompressed P-256, SHA-256, HKDF-SHA256, HMAC-SHA256 | Both parties must verify the peer confirmation; the public API then releases a 16-byte session key once. |
| `Rfc9382Suite.EDWARDS25519_SHA256` | Fixed RFC M/N, compressed Edwards25519, cofactor-aware shared-point computation, same hash/KDF/MAC schedule | The same confirmation and key-release state semantics. |
| `legacy.LegacySpake25519` | BoringSSL SPAKE25519 at `9e04aed2d1652441d2c84e85ed21acc05e053260`, with its different masks, scalars, decoder and SHA-512 transcript | A 64-byte **unconfirmed** key. Application traffic requires confirmation supplied by the surrounding protocol. |

No other curve/hash/MAC combination, per-user M/N variant, or symmetric M=N
variant is advertised as implemented. Roles are explicit. There is no suite
negotiation, automatic Legacy fallback, or shared protocol session state.

## RFC mathematical and wire evidence

The target specification is [RFC 9382 sections 3, 4, 6 and
7](https://www.rfc-editor.org/rfc/rfc9382.html). The common test suite compares
all four official P-256 vectors field by field: pA, pB, K, TT, Hash(TT), Ke,
Ka, KcA, KcB, and both confirmation messages. Public-API tests repeat those
exchanges through successful confirmation. Edwards fixtures use independent
test-only curve arithmetic and hash/KDF references, with versions and commands
recorded in [reference-vectors.md](reference-vectors.md).

The implementation binds the exact peer message bytes and both identity byte
strings into the transcript. Its password scalar is always represented by 32
big-endian bytes there, including leading zeroes. Edwards group encoding is
distinct from scalar encoding: compressed points use the curve's little-endian
format, while the transcript's scalar remains big endian.

Edwards shared-point computation clears the cofactor after subtracting the
password mask. The implementation accepts canonical mixed-order points when
their prime-order component is nonzero. Independent pure-torsion and
mixed-order fixtures distinguish this behavior from simply requiring every
peer point to have prime order.

AAD is included in derivation of the confirmation keys. It is not another
field of TT and does not directly change Ke. Different AAD therefore causes
confirmation failure, which prevents the public API from releasing Ke. This
ordering is covered by the wrong-AAD regression.

## Deliberate RFC API restrictions

These restrictions are part of this implementation's contract and must remain
visible when describing support:

| Area | Current policy | Consequence |
| --- | --- | --- |
| Ephemeral scalar zero | Sample uniformly from **`[1, q)`**, rather than the RFC text's `[0, q)` range, where q is the large prime subgroup order. | Zero is rejected and resampled. This is a deliberate sample-space restriction, not a claim of an identical distribution. |
| Password scalar zero | Accept canonical w in `[0, q)`, including zero. | Zero is tested as mathematical core input. Application password preprocessing is responsible for its security properties. |
| Degenerate points | Reject identity public/shared values and pure low-order Edwards peer points. | This API rejects some mathematically valid full-group elements; it does not silently reinterpret or normalize them. |
| Point encoding | P-256 wire messages must be uncompressed 65-byte SEC1 values; Edwards messages must be canonical 32-byte encodings, including the zero-x sign rule. | Compressed P-256, hybrid encodings, out-of-range coordinates, and non-canonical Edwards representations are rejected. |
| Identity sizes | Each identity is limited to 65,535 bytes. | A local resource bound; not a new identity format or application default. |
| Absent identities | Empty identities require `implicitIdentities=true`. | The caller must establish implicit identity semantics in its application protocol. |
| AAD size | At most 8,176 bytes. | Inputs above the supported protocol limit fail before copying. |
| Entropy retries | At most 128 candidate draws per ephemeral scalar. | A broken source eventually fails; successful draws remain uniform over the accepted range if source bytes are uniform. |
| Wide scalar reduction | Accept 40 through 1,024 big-endian input bytes. | Mathematical reduction only. This is neither a password hash nor a memory-hard function. |
| Object lifecycle | Generate once, process once, verify confirmation once. Any ordinary operation failure consumes the session. | Callers start a fresh object after failure; keys cannot be obtained from an unconfirmed session. |

The constructor accepts an application-derived scalar, not a raw password.
The application protocol must specify password normalization, MHF selection,
salt, work factors, output distribution, and scalar derivation. No unsafe
default preprocessing scheme is substituted for that policy.

## Legacy wire compatibility and stricter API policy

The pinned C oracle and historical `spake2-java` corpus are test-only
references. Compatibility includes the old M/N points, little-endian scalar
reduction, ephemeral multiplication by eight while retaining bit 255,
password-scalar correction, accepted historical point representations, exact
wire bytes in the transcript, and the full SHA-512 result. See
[LEGACY_COMPATIBILITY.md](LEGACY_COMPATIBILITY.md) for source hashes,
regeneration commands and observed counts.

`HARDENED` names the historical correction that clears the password scalar's
lowest three bits through subgroup-order additions. It does not supply an MHF
or authenticate the resulting key. `PRE_HARDENING_UNSAFE` deliberately
preserves the old leakage behavior and is never an implicit fallback.

The Legacy API retains SHA-512(password bytes), non-canonical point acceptance,
identity-after-unmasking behavior, and zero-derived ephemeral behavior where
required by the pinned reference. The caller must supply a functioning CSPRNG;
matching a broken source's byte count does not make its output secure.

Several C API conventions are intentionally tightened:

- The Kotlin object permanently fails after an invalid operation. The pinned
  C context can return a malformed-message error without consuming its state.
- Names are capped at 65,535 bytes and password material at 1 MiB.
- Key derivation always returns all 64 bytes, rather than taking a C-style
  caller output-buffer limit.
- Concurrent or reentrant misuse poisons the exchange and prevents a pending
  result from being released. Owned buffers are erased before terminal cleanup
  completion is published.

These policies are separate from the byte-for-byte wire compatibility being
tested. No application-specific identities, Kadb interfaces, or application
transport behavior are part of the production API.

## Common code and runtime assurances

Both modes use the same common Kotlin arithmetic and hashes. Platform
`expect/actual` functions, JCA/JCE, native crypto libraries and precompiled
cryptographic binaries do not perform core operations. Entropy comes through
the caller-provided interface; atomic gates only provide synchronization.

Secret arithmetic uses fixed source-level schedules and masked selection. The
library does not claim strictly constant-time generated machine code for JVM,
ART, Kotlin/Native, JavaScript, or Wasm. The confirmation comparison has no
content-dependent early exit, but that source property is also subject to
runtime/compiler behavior.

Owned secret arrays are overwritten on ordinary success, failure and destroy
paths. Register values, runtime copies, optimized-away stores, abrupt process
termination and allocation failures outside cleanup scopes are not covered by
a physical-erasure guarantee. Caller-owned input buffers and a returned key
remain the caller's responsibility. Detailed review findings and their
disposition are in [security-review.md](security-review.md).
