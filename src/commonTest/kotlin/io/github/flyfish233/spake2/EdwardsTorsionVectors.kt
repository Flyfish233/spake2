// SPDX-License-Identifier: AGPL-3.0-only
// Independent affine Python reference: tools/rfc9382_torsion.py.
package io.github.flyfish233.spake2

internal object EdwardsTorsionVectors {
    val pure: List<String> = listOf(
        "0100000000000000000000000000000000000000000000000000000000000000",
        "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
        "0000000000000000000000000000000000000000000000000000000000000080",
        "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85",
        "0000000000000000000000000000000000000000000000000000000000000000",
        "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa",
    )
    val mixed: List<String> = listOf(
        "977bcae176e5b1a259a7e445d69a940bfd57cbf77ca3445dba6b4ef5c7f76487",
        "ca4acb4acc21cc47dff0cda78c49bc66f5afb2c2e03d6da1e315ad5156437221",
        "ed5e0470f3dc20223be431f1e81fe35f7ac67356aa7f20a79745a562534ba80e",
        "d95d5a886b7e49a9ad64797a2ed782e87dc2387993341f4ecdd85ea928421da0",
        "5684351e891a4e5da6581bba29656bf402a83408835cbba24594b10a38089b78",
        "23b534b533de33b8200f325873b643990a504d3d1fc2925e1cea52aea9bc8dde",
        "00a1fb8f0c23dfddc41bce0e17e01ca085398ca95580df5868ba5a9dacb457f1",
    )
}
