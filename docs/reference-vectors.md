# Independent reference vectors

The production library implements all hashes and group operations in Kotlin
`commonMain`. The tools in this document are **test-only** and are not library
runtime dependencies.

## RFC 9382 P-256

Source: [RFC 9382, Appendix B](https://www.rfc-editor.org/rfc/rfc9382.html#appendix-B),
September 2023. All four official vectors are recorded, including cases where
one or both identities are empty. Empty identities require an application with
implicit identities; their appearance in vectors is not an application default.

`test-vectors/rfc9382-p256.json` and `RfcOfficialVectors.kt` preserve the exact
official pA, pB, K, TT, Hash(TT), Ke, Ka, KcA, KcB, cA, and cB values.
The generating tool independently recomputes every field using python-ecdsa and
Python's hashlib/hmac before emitting the fixtures. If any field differs, the
tool fails. These expected values are never computed with the Kotlin code.

RFC vector data retains its original notice: Copyright (c) 2023 IETF Trust and
the persons identified as the document authors. All rights reserved.
See [IETF Trust legal provisions](https://trustee.ietf.org/license-info) and
the repository's third-party notices. The surrounding original generator and
test harness are AGPL-3.0-only.

## RFC 9382 Edwards25519

Reference implementation: [python-ecdsa](https://github.com/tlsfuzzer/python-ecdsa),
release **0.19.1**, Python package `ecdsa==0.19.1` (MIT licensed).
Its `PointEdwards` arithmetic and point serialization are independent of this
project's Kotlin modular arithmetic and Edwards formulas. Hashing/HMAC use
Python's standard `hashlib` and `hmac`, also independent of the Kotlin code.

The test-only script implements the RFC transcript and key schedule, invokes the
reference library for all curve operations, and cross-checks its key schedule
against the four official P-256 vectors. Twelve Edwards fixtures cover empty
and nonempty identities, empty and nonempty AAD, and w = 0, 1, and L - 1.
The script applies cofactor 8 to the shared group element. Scalar and w encodings
in fixtures are 32-byte big-endian; Edwards point encoding is compressed RFC
8032. No library-private protocol state is imported into this reference.

Reproduce from the repository root:

```sh
python3 -m venv .reference-venv
.reference-venv/bin/pip install ecdsa==0.19.1
curl --fail https://www.rfc-editor.org/rfc/rfc9382.txt -o /tmp/rfc9382.txt
.reference-venv/bin/python tools/rfc9382_vectors.py --rfc-text /tmp/rfc9382.txt
```

The generator reports:

```text
Verified all 4 official RFC P256 vectors in all 17 fields; generated 12 Edwards25519 vectors with python-ecdsa 0.19.1.
```

This is an independent arithmetic reference with an original RFC protocol
adapter. It is not a claim that python-ecdsa implements or audits SPAKE2 itself.

### Full torsion-group checks

The python-ecdsa `PointEdwards` convenience implementation identifies points
with zero X or T with its infinity sentinel, so it is not used as the oracle for
pure torsion behavior. `tools/rfc9382_torsion.py` is a separate affine reference
using Python arbitrary-precision integers and modular inverses. It preserves
all eight torsion elements and is independent of the production Montgomery and
projective arithmetic. It verifies that both RFC M and N have order L, enumerates
all eight pure torsion encodings, and produces seven mixed-order peer messages.
All seven preserve the independently known shared K after cofactor clearing;
their altered wire encoding changes the authenticated transcript.

```sh
python3 tools/rfc9382_torsion.py
```

The common test suite checks rejection of every pure torsion point and checks
mixed-order handling against these fixed external expectations.

## Hash, HMAC, and HKDF

`HashTest.kt` uses SHA-256/SHA-512 known-answer examples from FIPS 180-4 / RFC
6234, all seven RFC 4231 HMAC-SHA256 cases (including the published truncated
case), and RFC 5869 SHA256 cases A.1, A.2, and A.3. SHA tests include the million
`a` example. Hash and key-derivation output sizes, input ownership, and invalid
HKDF output lengths are tested.

`HashBoundaryTest.kt` includes 25 independent pairs of SHA256/SHA512 digests for
lengths on either side of 56/64-byte and 112/128-byte padding boundaries and
larger blocks. Inputs are byte i = (131 * i + 17) mod 256. Reproduce with:

```sh
python3 tools/rfc_hash_boundaries.py
```

The script uses Python hashlib, never the implementation under test. Fixed
vectors can detect transcription, byte order, padding, and arithmetic errors;
they do not establish absence of side channels or a completed security audit.

An additional executable differential check can bootstrap independently of the
Gradle project configuration:

```sh
python3 tools/hash_differential.py --gradle-home /path/to/gradle-8.14.3
```

It compiles only the common hash sources with Gradle's bundled Kotlin compiler
and compares 671 SHA256, SHA512, HMAC and HKDF cases with Python hashlib/hmac.
On the development host this passed with kotlinc-jvm 2.0.21 and OpenJDK 17.0.21+9.
That bootstrap result is separate from the project's Kotlin 2.2.21 Gradle tests.
