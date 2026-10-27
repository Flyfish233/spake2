# Security status

Experimental cryptographic software, not independently audited for production use.

The project aims for fixed-schedule secret arithmetic and safe protocol state
transitions. No strict constant-time guarantee is made for JVM/ART, Kotlin/Native,
JavaScript or Wasm runtimes. Managed-memory copying, garbage collection, compiler
optimization and caches limit both timing assurances and guaranteed erasure.

Applications must supply a cryptographically secure random source. Applications
using the RFC API must also define password normalization, memory-hard
preprocessing and canonical password-scalar derivation. The RFC API provides no
default password hash. The explicitly selected Legacy API retains
SHA-512(password bytes), because it is required by the historical wire protocol.

RFC session keys must remain unavailable until peer confirmation succeeds. Legacy
BoringSSL compatibility has different semantics and requires explicit selection.
