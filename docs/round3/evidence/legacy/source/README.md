# Fixed upstream source snapshots

The BoringSSL files are unmodified copies fetched at revision
`9e04aed2d1652441d2c84e85ed21acc05e053260`; exact source URLs and byte digests
are recorded in `../upstream-sources.json`.

The AOSP files are unmodified copies fetched at revision
`1cf2f017d312f73b3dc53bda85ef2610e35a80e9`; source URLs and byte digests are
recorded in `../aosp-cross-review-sources.json`. They support cross-review of
the pairing AEAD nonce design; no AOSP or BoringSSL code was linked into the
Kotlin library during this review.

Original copyright/license headers are retained. The Apache License 2.0 text
is retained at the repository's `third-party/boringssl/LICENSE`; other
attribution notices embedded in the downloaded files remain applicable.
These are review inputs, not a claim of verified compiler or release provenance.
