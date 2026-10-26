# spake2

Experimental, independently versioned, pure Kotlin Multiplatform implementations of
RFC 9382 SPAKE2 and the explicitly selected historical BoringSSL SPAKE25519 protocol.

**Under development. Not audited for production use.** Original code is licensed
**AGPL-3.0-only**, without a linking exception. Third-party material retains its
original notices; see `THIRD_PARTY.md`.

## Scope

* RFC 9382: P-256 / Edwards25519, SHA-256, HKDF-SHA256, HMAC-SHA256, mutual confirmation.
* Legacy: BoringSSL commit `9e04aed2d1652441d2c84e85ed21acc05e053260` compatibility.
* Cryptographic computation is Kotlin in `commonMain`; no platform crypto backend.
* Entropy is provided by the application through an explicit interface.
* Password preprocessing and memory-hard hashing belong to the application protocol.

Implementation, validation evidence, limitations, and API examples will be added on
`work/spake2-implementation`. The draft PR is the delivery and review entry point.

## Build

JDK 17, Gradle 8.14.3, and Kotlin 2.2.21 are pinned. Run `./gradlew jvmTest`.
Native, JavaScript, Wasm and Apple targets are configured. Android is enabled with
`-PenableAndroid=true` and requires an installed Android SDK.

No release or publication is configured.
