// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2

import io.github.flyfish233.spake2.internal.crypto.HkdfSha256
import io.github.flyfish233.spake2.internal.crypto.HmacSha256
import io.github.flyfish233.spake2.internal.crypto.Sha256
import io.github.flyfish233.spake2.internal.crypto.Sha512
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HashTest {
    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun text(value: String): ByteArray = value.encodeToByteArray()

    @Test fun sha256StandardVectors() {
        // FIPS 180-4 / NIST SHA examples and RFC 6234 examples.
        val vectors = listOf(
            "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        )
        for ((input, expected) in vectors) assertContentEquals(hex(expected), Sha256.digest(text(input)))
        assertContentEquals(hex("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"), Sha256.digest(ByteArray(1_000_000) { 0x61 }))
    }

    @Test fun sha512StandardVectors() {
        val vectors = listOf(
            "" to "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            "abc" to "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu" to "8e959b75dae313da8cf4f72814fc143f8f7779c6eb9f7fa17299aeadb6889018501d289e4900f7e4331b99dec4b5433ac7d329eeb6dd26545e96e55b874be909",
        )
        for ((input, expected) in vectors) assertContentEquals(hex(expected), Sha512.digest(text(input)))
        assertContentEquals(hex("e718483d0ce769644e2e42c7bc15b4638e1f98b13b2044285632a803afa973ebde0ff244877ea60a4cb0432ce577c31beb009c5c2c49aa2e4eadb217ad8cc09b"), Sha512.digest(ByteArray(1_000_000) { 0x61 }))
    }

    @Test fun hmacRfc4231() {
        // RFC 4231 test-vector data, Copyright (C) The Internet Society (2005).
        val cases = listOf(
            Triple(ByteArray(20) { 0x0b }, text("Hi There"), "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            Triple(text("Jefe"), text("what do ya want for nothing?"), "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
            Triple(ByteArray(20) { 0xaa.toByte() }, ByteArray(50) { 0xdd.toByte() }, "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"),
            Triple(ByteArray(25) { (it + 1).toByte() }, ByteArray(50) { 0xcd.toByte() }, "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b"),
            Triple(ByteArray(131) { 0xaa.toByte() }, text("Test Using Larger Than Block-Size Key - Hash Key First"), "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"),
            Triple(ByteArray(131) { 0xaa.toByte() }, text("This is a test using a larger than block-size key and a larger than block-size data. The key needs to be hashed before being used by the HMAC algorithm."), "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2"),
        )
        for ((key, input, expected) in cases) assertContentEquals(hex(expected), HmacSha256.mac(key, input))
        assertContentEquals(hex("a3b6167473100ee06e0c796c2955552b"), HmacSha256.mac(ByteArray(20) { 0x0c }, text("Test With Truncation")).copyOf(16))
    }

    @Test fun hkdfRfc5869() {
        // RFC 5869 Appendix A.1-A.3; see THIRD_PARTY.md.
        val ikm1 = ByteArray(22) { 0x0b }
        val salt1 = hex("000102030405060708090a0b0c")
        val info1 = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk1 = hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val okm1 = hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
        assertContentEquals(prk1, HkdfSha256.extract(ikm1, salt1))
        assertContentEquals(okm1, HkdfSha256.derive(ikm1, salt1, info1, 42))
        assertContentEquals(okm1, HkdfSha256.expand(prk1, info1, 42))
        val ikm2 = ByteArray(80) { it.toByte() }
        val salt2 = ByteArray(80) { (0x60 + it).toByte() }
        val info2 = ByteArray(80) { (0xb0 + it).toByte() }
        assertContentEquals(hex("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244"), HkdfSha256.extract(ikm2, salt2))
        assertContentEquals(hex("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87"), HkdfSha256.derive(ikm2, salt2, info2, 82))
        assertContentEquals(hex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"), HkdfSha256.extract(ikm1, byteArrayOf()))
        assertContentEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), HkdfSha256.derive(ikm1, byteArrayOf(), byteArrayOf(), 42))
    }

    @Test fun hkdfBoundsAndInputOwnership() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(32) { (255 - it).toByte() }
        val info = text("test")
        val copies = listOf(ikm.copyOf(), salt.copyOf(), info.copyOf())
        val output = HkdfSha256.derive(ikm, salt, info, 8160)
        assertEquals(8160, output.size)
        assertEquals(0, HkdfSha256.derive(ikm, salt, info, 0).size)
        assertFailsWith<IllegalArgumentException> { HkdfSha256.derive(ikm, salt, info, -1) }
        assertFailsWith<IllegalArgumentException> { HkdfSha256.derive(ikm, salt, info, 8161) }
        assertFailsWith<IllegalArgumentException> { HkdfSha256.expand(ByteArray(31), info, 1) }
        assertContentEquals(copies[0], ikm)
        assertContentEquals(copies[1], salt)
        assertContentEquals(copies[2], info)
    }
}
