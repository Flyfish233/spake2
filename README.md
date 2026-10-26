# spake2

Pure Kotlin Multiplatform implementations of **RFC 9382 SPAKE2**, with an explicitly
selected, separate **Legacy BoringSSL SPAKE25519** artifact.

**Experimental cryptographic software. Not externally audited for production use.**
Original implementation code is **AGPL-3.0-only**, without a linking exception.
Third-party reference data and build tooling retain their own licenses and notices
in [THIRD_PARTY.md](THIRD_PARTY.md).

## Implemented protocols

| API | Suite / protocol | Output |
| --- | --- | --- |
| `Rfc9382Session` | `SPAKE2-P256-SHA256-HKDF-HMAC` | 16-byte key, released only after peer confirmation succeeds |
| `Rfc9382Session` | `SPAKE2-edwards25519-SHA256-HKDF-HMAC` | Same lifecycle and mutual confirmation |
| `LegacySpake25519` | BoringSSL SPAKE25519 at `9e04aed2d1652441d2c84e85ed21acc05e053260` | 64-byte **unconfirmed** key, requiring confirmation in the surrounding protocol |

No additional suites, implicit negotiation, or automatic Legacy fallback are
implemented. The project has no Kadb-specific interfaces, identities or defaults.

## Module structure

| Gradle project | Contents | Production dependencies |
| --- | --- | --- |
| root `spake2` | RFC protocol, canonical scalar boundary, mutual confirmation | `:primitives`, Kotlin standard library |
| `:legacy` | Explicit historical protocol and separate session state | `:primitives`, Kotlin standard library |
| `:primitives` | Shared SHA-256/SHA-512, HMAC/HKDF, modular arithmetic, P-256, Edwards25519, entropy interface | Kotlin standard library |

Every cryptographic calculation lives in `commonMain`. No JCA/JCE, JNI, OpenSSL,
BoringSSL, libsodium, CryptoKit or precompiled cryptographic library performs a
core cryptographic operation. Shared implementation symbols require `InternalSpake2Api`
opt-in; they are not supported application APIs. The RFC artifact has no production
dependency on the Legacy artifact.

## RFC API

The application must define password normalization, a suitable memory-hard
preprocessing function, its salt/work parameters, and the conversion to `w`.
The RFC API takes an already derived, canonical **32-byte big-endian** scalar.
It deliberately provides no default password hash.

The following helper shows the complete two-party sequence. A transport normally
runs the Alice and Bob halves at different endpoints. Its callers supply two
independent CSPRNG sources and the application-derived `w`; fixture entropy must
never be used by an application.

```kotlin
import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.rfc.Rfc9382Session
import io.github.flyfish233.spake2.rfc.Rfc9382Suite

fun exchange(
    suite: Rfc9382Suite,
    w: ByteArray,
    aliceIdentity: ByteArray,
    bobIdentity: ByteArray,
    aad: ByteArray,
    aliceEntropy: EntropySource,
    bobEntropy: EntropySource,
): Pair<ByteArray, ByteArray> {
    val alice = Rfc9382Session(
        suite, Role.ALICE, w, aliceIdentity, bobIdentity, aad, aliceEntropy
    )
    try {
        val bob = Rfc9382Session(
            suite, Role.BOB, w, aliceIdentity, bobIdentity, aad, bobEntropy
        )
        try {
            val pA = alice.generateMessage() // transmit to Bob
            val pB = bob.generateMessage()   // transmit to Alice
            val cA = alice.processPeerMessage(pB) // transmit to Bob
            val cB = bob.processPeerMessage(pA)   // transmit to Alice
            val keyB = bob.confirm(cA)
            try {
                val keyA = alice.confirm(cB)
                return keyA to keyB
            } catch (failure: Throwable) {
                keyB.fill(0)
                throw failure
            }
        } finally { bob.destroy() }
    } finally { alice.destroy() }
}
```

`processPeerMessage` returns a confirmation MAC. It does not release Ke, K, Ka,
KcA, or KcB. Only `confirm` can release the authenticated key, once. Wrong password,
identity, AAD, or confirmation consumes the session. The caller owns each returned
key and must clear it after use. Exact transcript and intermediate values are
accessible only to internal tests, not through the public protocol API.

`EntropySource.nextBytes(size)` must return exactly `size` fresh, independent,
uniform CSPRNG bytes. Ownership of that array transfers to the library, which
wipes it. A source must throw on failure and must never substitute predictable
bytes. The library cannot certify a source's entropy quality. No platform random
backend is selected automatically, and `kotlin.random.Random` is not used.

`Rfc9382Suite.reduceWideScalar` is available for mathematical reduction of
application-derived material (40–1024 big-endian bytes). It is not a password
hash. `scalarOrder()` returns the subgroup order. Empty identities require an
explicit `implicitIdentities=true` setting and corresponding application semantics.

See [API.md](docs/API.md) for method/state contracts and [conformance.md](docs/conformance.md)
for canonical scalar sampling, degenerate-point rejection, input bounds, and Legacy
differences. A valid zero ephemeral draw is not resampled; the resulting degenerate
exchange terminates before any key is released. Predictable entropy is insecure
even when an exchange ultimately fails.

## Legacy API

Use the `:legacy` artifact and import `io.github.flyfish233.spake2.legacy.LegacySpake25519`.
Construct it with explicit role, own identity, peer identity, and entropy source;
then call `generateMessage(passwordBytes)` and `deriveUnconfirmedKey(peerMessage)`.
Call `destroy()` in a `finally` block. This mode retains the pinned protocol's
SHA-512 password/transcript handling and historical point decoding.

`LegacyPasswordScalarMode.HARDENED` selects BoringSSL's historical scalar correction.
`PRE_HARDENING_UNSAFE` exists only for deliberately selected old-peer compatibility;
it exposes the historical leakage and is never selected implicitly. The result
still needs mutual confirmation supplied by the surrounding application protocol.

[LEGACY_COMPATIBILITY.md](docs/LEGACY_COMPATIBILITY.md) records the exact version,
512-case historical corpus, independently rebuilt oracle, fresh adversarial
fixtures, zero-scalar behavior, reproduction commands and stricter Kotlin lifecycle.

## Build and verify

Pinned build tools: **JDK 17**, **Gradle 8.14.3**, **Kotlin 2.2.21**. The Gradle wrapper
has a pinned distribution SHA-256. Android uses AGP 8.11.1 and compile SDK 35.

```sh
./gradlew clean jvmTest
./gradlew linuxX64Test
./gradlew jsNodeTest wasmJsNodeTest
./gradlew -PenableAndroid=true assembleDebug testDebugUnitTest
# macOS, select the simulator architecture matching the host:
./gradlew iosSimulatorArm64Test   # Apple Silicon
./gradlew iosX64Test              # Intel
```

Android requires an SDK configured by `ANDROID_HOME` or gitignored `local.properties`.
Native/Node toolchains are downloaded by the Kotlin Gradle plugin as needed.
The project does not configure a package publication or release task.

Actual commands, results, environment failures and platform limitations are in
[validation.md](docs/validation.md); target configuration alone is never called a
passing test. The [verification workflow](.github/workflows/verify.yml) is configured
to run Linux, Android host tests and an iOS simulator job and preserve test reports.

## Independent evidence and limitations

- All four official RFC P-256 vectors are checked field by field through both MACs.
- Edwards vectors use pinned independent Python references, including separate
  torsion-aware affine arithmetic where the reference library shortcuts low-order points.
- Legacy fixtures are verified against a freshly built, fixed-version BoringSSL C oracle.
- Additional checks include standard SHA/HMAC/HKDF vectors, BigInteger arithmetic
  differentials, selected pinned Wycheproof point cases, malicious encodings,
  lifecycle/aliasing/fault injection, and JVM concurrent misuse.

Source-level arithmetic schedules avoid obvious secret-dependent loops and table
indices. This is not a strict constant-time guarantee for JVM/ART, Native, JS or
Wasm. Buffer overwrites are best effort; managed runtime copies and physical
zeroization remain unverified. Current arithmetic allocates substantial temporary
memory; [performance.md](docs/performance.md) contains measured timings and
allocation counts, not a side-channel claim.

Review findings and fixes are recorded in [security-review.md](docs/security-review.md)
and [math-review.md](docs/math-review.md). These are separate-author engineering
reviews within this project, not an external professional security audit.

Development remains on `work/spake2-implementation` in [Draft PR#1](https://github.com/Flyfish233/spake2/pull/1).
