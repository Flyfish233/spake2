// Test harness: AGPL-3.0-only.
// Embedded fixture data retains GPLv3 upstream terms from Flyfish233/spake2-java
// 809ad7aa731f475c07763b1ad2c165ae192dfb5f. Source/license retained in producer.
// Rebuilt BoringSSL oracle: 9e04aed2d1652441d2c84e85ed21acc05e053260.
package consumer
import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.Spake2Exception
import kotlin.test.*

private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
    s.substring(2 * it, 2 * it + 2).toInt(16).toByte()
}
private data class Case(val name: String, val a: String, val b: String,
    val password: String, val x: String, val y: String, val pA: String,
    val pB: String, val kA: String, val kB: String)
private val cases = listOf(
    Case("normal", "616c696365", "626f62", "353135313039", "01000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000000000000000000000000000000000000000000000000000000000000", "1876811ed2c78beb885abcfeee9174e822e056d67474d7f924e5c019d087dc71", "e814a51e5e1032ce32bd9e47f74fb58a42f02b61b355e062a36836dbd8de35c5", "f1e5cb026ca1dc0780d629cd1747b834bceed4b05417b80f2133a2bd0a0e8b21c1467782f8cab09ba0a20c4a785029dc95c028e20deff35812c01a3a0d8e9cb2", "f1e5cb026ca1dc0780d629cd1747b834bceed4b05417b80f2133a2bd0a0e8b21c1467782f8cab09ba0a20c4a785029dc95c028e20deff35812c01a3a0d8e9cb2"),
    Case("high-bit-carry-2^252", "616c696365", "626f62", "353135313039", "00000000000000000000000000000000000000000000000000000000000000100000000000000000000000000000000000000000000000000000000000000000", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000000000000000000000000000000000000000000000000000000000000", "ae3b60414ba76934aa1f268bfc23d343a0fcc004f1da1f4ab0f0cf191af6fa40", "e814a51e5e1032ce32bd9e47f74fb58a42f02b61b355e062a36836dbd8de35c5", "94a8e9b7620d03d981cacb1fb8b75337551d07d595cc4fe427e77c3478830a89d7832622732aa35be50b946586e0376b08ce00593cbb973c239747de688e8a97", "94a8e9b7620d03d981cacb1fb8b75337551d07d595cc4fe427e77c3478830a89d7832622732aa35be50b946586e0376b08ce00593cbb973c239747de688e8a97"),
    Case("high-bit-carry-2^252-plus-5", "616c696365", "626f62", "353135313039", "05000000000000000000000000000000000000000000000000000000000000100000000000000000000000000000000000000000000000000000000000000000", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000000000000000000000000000000000000000000000000000000000000", "fa76a5e293956ffd1e7782ead10efb67e5564de8a0ff5c8dfb1a96d9e4eee476", "e814a51e5e1032ce32bd9e47f74fb58a42f02b61b355e062a36836dbd8de35c5", "d9397e9aa2c7ea8a681206cf7744325d91324086d92561b2e7519d32b5a1035d8899c849c8e96b3d0348ae3798517fc33358be1333a5a219a9e90f2259c8f3bc", "d9397e9aa2c7ea8a681206cf7744325d91324086d92561b2e7519d32b5a1035d8899c849c8e96b3d0348ae3798517fc33358be1333a5a219a9e90f2259c8f3bc"),
    Case("order-minus-one", "616c696365", "626f62", "353135313039", "ecd3f55c1a631258d69cf7a2def9de14000000000000000000000000000000100000000000000000000000000000000000000000000000000000000000000000", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000000000000000000000000000000000000000000000000000000000000", "58e879f7c7c7dee563d11a0764f8e323dd0c2f46799b69de637409343c9094c5", "e814a51e5e1032ce32bd9e47f74fb58a42f02b61b355e062a36836dbd8de35c5", "4cda88ce6753f4dc818b81411b8b12400ac6d038deb1b18c96c4b6e20da80e7b2ceae5eba89db3b9b3de52d9222e2d1fb0049b4c474c3d55d5e2fc9f40666419", "4cda88ce6753f4dc818b81411b8b12400ac6d038deb1b18c96c4b6e20da80e7b2ceae5eba89db3b9b3de52d9222e2d1fb0049b4c474c3d55d5e2fc9f40666419"),
    Case("aosp-adb-names", "616462207061697220636c69656e7400", "61646220706169722073657276657200", "353135313039", "01000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000000000000000000000000000000000000000000000000000000000000", "1876811ed2c78beb885abcfeee9174e822e056d67474d7f924e5c019d087dc71", "e814a51e5e1032ce32bd9e47f74fb58a42f02b61b355e062a36836dbd8de35c5", "5c1a3ba6d4176e4f1cce1b33d22d10ea5c94078c35d6adde6b01365f0df35d1f50b7a5ad6910bfc17295e5d1969e502a6141cdc5c7e2cc99b238d786c9275865", "5c1a3ba6d4176e4f1cce1b33d22d10ea5c94078c35d6adde6b01365f0df35d1f50b7a5ad6910bfc17295e5d1969e502a6141cdc5c7e2cc99b238d786c9275865")
)
class PublishedLegacyTest {
    private fun endpoint(v: Case, role: Role): LegacyConsumer =
        LegacyConsumer(role, hex(if (role == Role.ALICE) v.a else v.b),
            hex(if (role == Role.ALICE) v.b else v.a), EntropySource { size ->
                assertEquals(64, size)
                hex(if (role == Role.ALICE) v.x else v.y)
            })
    @Test fun hardenedPinnedOracleIncludingHighBitAndNulNames() {
        assertEquals(5, cases.size)
        for (v in cases) {
            val a = endpoint(v, Role.ALICE)
            val b = endpoint(v, Role.BOB)
            try {
                val pA = a.generate(hex(v.password))
                val pB = b.generate(hex(v.password))
                assertContentEquals(hex(v.pA), pA, v.name + " Alice")
                assertContentEquals(hex(v.pB), pB, v.name + " Bob")
                val ka = a.process(pB)
                val kb = b.process(pA)
                try {
                    assertContentEquals(hex(v.kA), ka, v.name)
                    assertContentEquals(hex(v.kB), kb, v.name)
                    assertFailsWith<Spake2Exception> { a.process(pB) }
                } finally { ka.fill(0); kb.fill(0) }
            } finally { a.destroy(); b.destroy() }
        }
    }
    @Test fun malformedInputAndDestroyConsumePublishedSession() {
        val v = cases.first()
        val a = endpoint(v, Role.ALICE)
        try {
            a.generate(hex(v.password))
            assertFailsWith<Spake2Exception> { a.process(ByteArray(31)) }
            assertFailsWith<Spake2Exception> { a.process(hex(v.pB)) }
        } finally { a.destroy() }
        val b = endpoint(v, Role.BOB)
        b.generate(hex(v.password))
        b.destroy()
        assertFailsWith<Spake2Exception> { b.process(hex(v.pA)) }
    }
}
