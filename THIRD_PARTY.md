# Source and license provenance

Original implementation and original tooling are **AGPL-3.0-only**, with no linking exception. Third-party source/data keeps its original copyright and license. No third-party cryptographic implementation source is incorporated into the runtime.

| Material | Exact source/version | Use | License and retained notice |
|---|---|---|---|
| RFC 9382 | W. Ladd, September 2023, https://www.rfc-editor.org/rfc/rfc9382; IRTF document stream | Protocol, constants and official Appendix B vector data | Original IETF Trust 2023 notice and applicable alternate-stream terms retained in `third-party/ietf/RFC9382-IRTF-NOTICE` and `TLP-2015-REFERENCE.txt`; no section-4 BSD grant is asserted |
| RFC 5869 | H. Krawczyk/P. Eronen, May2010, https://www.rfc-editor.org/rfc/rfc5869 | Official HKDF vector data | IETF Trust2010; `third-party/ietf/NOTICE` and `LICENSE-BSD` |
| RFC 4231 | M. Nystrom, December2005, https://www.rfc-editor.org/rfc/rfc4231 | Official HMAC vector data | Internet Society2005/BCP78; `third-party/ietf/RFC4231-NOTICE` |
| FIPS180-4, RFC2104, RFC6234, RFC8032 | Published algorithm specifications | Original hash/curve implementations from mathematical definitions; no implementation code copied | Standards cited in source/tests |
| Explicit-Formulas Database | https://www.hyperelliptic.org/EFD/g1p/auto-shortw-jacobian-3.html and https://www.hyperelliptic.org/EFD/g1p/auto-twisted-extended-1.html | Mathematical formulas, independently coded | No third-party implementation source copied |
| Gradle wrapper | Gradle8.14.3, https://github.com/gradle/gradle/tree/v8.14.3 | Build bootstrap only, not crypto runtime | Apache2.0; original wrapper headers and upstream full `third-party/gradle/LICENSE` retained |
| Historical Legacy fixtures | Flyfish233/spake2-java `809ad7aa731f475c07763b1ad2c165ae192dfb5f` | Unmodified JSON/JSONL test data and equivalent generated test literals | GPLv3 upstream license retained at `third-party/spake2-java/LICENSE`; data not relabeled AGPL |
| BoringSSL oracle | google/boringssl `9e04aed2d1652441d2c84e85ed21acc05e053260` | External, separately compiled test reference only; original test harness includes upstream source outside repository | Upstream complete composite license retained at `third-party/boringssl/LICENSE`; SPAKE source header Apache2.0 |
| python-ecdsa | 0.19.1, https://github.com/tlsfuzzer/python-ecdsa/tree/python-ecdsa-0.19.1 | Test-only independent reference for RFC curves | MIT; installed externally; no library source redistributed |
| cryptography / OpenSSL | cryptography50.0.2, OpenSSL4.0.3 | Test-only independent P256 reference | Installed externally; no implementation or binaries redistributed |
| Project Wycheproof | C2SP/wycheproof `d17cc89fa5c8f4f4d378b5385ebf75da5d0926c9`, `testvectors_v1/ecdh_secp256r1_ecpoint_test.json` | Selected independent P-256 valid, invalid, and encoding-policy test data | Apache-2.0; upstream license retained in `third-party/WYCHEPROOF-LICENSE.txt`, source and transformation details in `third-party/WYCHEPROOF-NOTICE.txt`; data not relabeled AGPL |

The two historical fixture files retain their exact upstream bytes and are tracked by SHA-256 in `test-vectors/legacy/oracle-regeneration-report.json`. GPL fixture data is confined to tests; the original runtime remains AGPL-3.0-only. The generated Kotlin fixture headers distinguish harness code from original data.

The Gradle wrapper JAR is a build tool. No precompiled cryptographic library is shipped or used by any production target. Android/Gradle compiler tooling and independent verification environments may have their own dependencies; these are separate from the library runtime dependency graph.

RFC 9382 and RFC 5869 have different stream-specific licensing treatment. RFC 9382 is an IRTF-stream document: the IETF Trust Legal Provisions (TLP) section 8.g excludes the section-4 code-component BSD license, while section 8.c specifies alternate-stream treatment of the section-3 grants. Its original attribution and applicable TLP terms remain attached to the numeric constants and official vector data. The new implementation and test harness are independently written AGPL-3.0-only code; the official data is not declared original AGPL or BSD material.

The separate `third-party/ietf/LICENSE-BSD` applies to RFC 5869's IETF-stream code-component material. The IETF Trust corrected the old name “Simplified BSD” to “Revised BSD” in 2021 without changing that license text. The retained three-clause text is not a grant for RFC 9382. `TLP-2015-REFERENCE.txt` is a complete reference copy of the official policy, including its stream-specific applicability rules, not a new license grant.
