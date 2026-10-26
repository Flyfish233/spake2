# Legacy BoringSSL SPAKE25519

The explicit `legacy.LegacySpake25519` API targets BoringSSL commit
[`9e04aed2d1652441d2c84e85ed21acc05e053260`](https://github.com/google/boringssl/tree/9e04aed2d1652441d2c84e85ed21acc05e053260).
It is separate from `rfc.Rfc9382Session`: there is no negotiation, automatic
fallback, shared protocol state, shared transcript format, or shared key schedule.
Both implementations use the same internal Kotlin mathematics.

## Wire behavior

| Item | Legacy behavior |
| --- | --- |
| Group | Edwards25519, with its full cofactor-eight group |
| Alice mask | `5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e` |
| Bob mask | `10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778` |
| Ephemeral scalar | Interpret 64 CSPRNG bytes little endian; reduce modulo L; multiply by eight without dropping bit 255 |
| Password scalar | SHA-512(password bytes), reduce little endian modulo L, then add multiples of L to clear the lowest three bits |
| Old mode | Explicit `PRE_HARDENING_UNSAFE` disables the last correction; never selected automatically |
| Message | Compressed Edwards encoding, 32 bytes |
| Transcript | Eight-byte little-endian length prefixes over Alice name, Bob name, Alice message, Bob message, encoded shared point, and the full SHA-512 password hash |
| Key | SHA-512(transcript), 64 bytes, **unconfirmed** |

The pre-hardening mode exposes three bits of the password hash and should only
be used when an application deliberately requires old-peer wire compatibility.
It is tested because the historical corpus mixes corrected and old endpoints.

The legacy decoder deliberately mirrors the pinned historical implementation.
It accepts some non-canonical point encodings, including all-`ff` bytes and
negative-zero encodings. It accepts identity after unmasking. The original peer
bytes enter the transcript even when their point decodes to a canonical value.
The RFC decoder is distinct and rejects non-canonical encodings. These legacy
behaviors do not establish RFC 9382 compliance.

`deriveUnconfirmedKey` is intentionally named for the guarantee it provides.
It cannot detect a wrong password, wrong identity, or wrong role by itself.
The surrounding protocol must perform mutual key confirmation before treating
the exchange as authenticated or sending application secrets.

## Independent reference corpus

The unmodified historical fixtures came from
[`Flyfish233/spake2-java` at `809ad7aa731f475c07763b1ad2c165ae192dfb5f`](https://github.com/Flyfish233/spake2-java/tree/809ad7aa731f475c07763b1ad2c165ae192dfb5f/src/test/resources/oracle-vectors).
They contain 512 valid fuzz exchanges, seven deterministic exchanges, and four
historical peer-processing cases. Source files and SHA-256 digests are retained
under `test-vectors/legacy/`; the original repository GPLv3 license is retained
at `third-party/spake2-java/LICENSE`. Copied fixture data retains its upstream
licensing; it is not represented as newly authored AGPL code.

The historical case labelled `invalid-encoding-all-ff` has `success: true`.
The label is misleading; the expected value and the pinned C implementation
both accept that encoding. The Kotlin test checks actual behavior.

`tools/legacy_oracle.cc` includes the unmodified pinned BoringSSL source in a
test-only translation unit. Only its `RAND_bytes` call is redirected to supplied
test entropy. BoringSSL is compiled outside the project and is never linked
into the Kotlin library or any shipped target.

The test harness was built and executed during this implementation. All 512
historical fuzz exchanges (1,024 endpoint results), seven deterministic
exchanges, and four peer cases matched the freshly executed C oracle. A further
24 exchanges and 108 malformed/boundary peer encodings were generated; 52 of
those peer encodings were accepted by the reference. The machine-readable
regeneration report records executable, harness, and input hashes. Kotlin test
execution status is tracked separately in the main verification report.

## Reproduction

From a separately downloaded checkout of the pinned BoringSSL commit:

```sh
cmake -S "$BORINGSSL_SOURCE" -B "$BORINGSSL_BUILD" \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF -DOPENSSL_NO_ASM=ON
cmake --build "$BORINGSSL_BUILD" --target crypto -j4
g++ -std=c++17 -O2 -DOPENSSL_NO_ASM \
  -I"$BORINGSSL_SOURCE" -I"$BORINGSSL_SOURCE/include" \
  tools/legacy_oracle.cc "$BORINGSSL_BUILD/libcrypto.a" -lpthread \
  -o /tmp/spake2-legacy-oracle
python3 tools/legacy_regenerate_oracle.py --oracle /tmp/spake2-legacy-oracle
python3 tools/legacy_generate_tests.py
./gradlew jvmTest --tests '*Legacy*'
```

The generated Kotlin fixtures allow the same independent expected values to
run from `commonTest`, including Kotlin/Native and JavaScript, without a JSON
library, a filesystem resource API, or a C reference dependency at runtime.

## Lifecycle and implementation limits

Inputs are copied; entropy ownership transfers to the library and returned
entropy buffers are wiped. Core secrets and temporary point coordinates are
overwritten on success, ordinary failure, and destruction. A poisoned gate
prevents a reentrant or concurrent call from releasing the in-flight result.
The session is permanently consumed after any failed operation.

The source scans all 256 scalar bits with a fixed window schedule and scans
every table entry under masks. This does not imply a verified constant-time
machine-code implementation on JVM, Android, Native, JS, or Wasm. Managed
runtime copies, optimizer behavior, and physical zeroization remain unverified.
The implementation is experimental and has not received an external security
audit. The regression corpus establishes tested compatibility, not general
cryptographic security.
