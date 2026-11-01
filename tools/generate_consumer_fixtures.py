from pathlib import Path
import json

root = Path(__file__).resolve().parents[1]
base = root / "integration/consumer-fixtures"
def write(path, value):
    p = base / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(value.strip() + "\n")
def quoted(value):
    return json.dumps(value)

write("rfc-client/src/commonMain/kotlin/consumer/RfcConsumer.kt", """
// SPDX-License-Identifier: AGPL-3.0-only
package consumer
import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.rfc.Rfc9382Session
import io.github.flyfish233.spake2.rfc.Rfc9382Suite

// This separately compiled consumer uses only public API from Maven artifacts.
fun rfcSession(suite: Rfc9382Suite, role: Role, w: ByteArray,
    alice: ByteArray, bob: ByteArray, aad: ByteArray, entropy: EntropySource,
    implicit: Boolean = false): Rfc9382Session =
    Rfc9382Session(suite, role, w, alice, bob, aad, entropy, implicit)
""")
write("legacy-client/src/commonMain/kotlin/consumer/LegacyConsumer.kt", """
// SPDX-License-Identifier: AGPL-3.0-only
package consumer
import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.legacy.LegacyPasswordScalarMode
import io.github.flyfish233.spake2.legacy.LegacySpake25519

// Same public call sequence required by generic Legacy clients. No ADB defaults.
class LegacyConsumer(role: Role, ownIdentity: ByteArray, peerIdentity: ByteArray,
    entropy: EntropySource) {
    private val session = LegacySpake25519(role, ownIdentity, peerIdentity, entropy,
        LegacyPasswordScalarMode.HARDENED)
    fun generate(password: ByteArray): ByteArray = session.generateMessage(password)
    fun process(peerMessage: ByteArray): ByteArray = session.deriveUnconfirmedKey(peerMessage)
    fun destroy() { session.destroy() }
}
""")
rfcs = []
for suite, name in [("P256_SHA256", "rfc9382-p256.json"),
                    ("EDWARDS25519_SHA256", "rfc9382-edwards25519.json")]:
    for v in json.loads((root / "test-vectors" / name).read_text())["vectors"]:
        rfcs.append("    Case(Rfc9382Suite." + suite + ", " +
                    ", ".join(quoted(v[k]) for k in ["a","b","aad","w","x","y","pA","pB","cA","cB","ke"]) + ")")
rfc_tests = """
// Test harness: AGPL-3.0-only.
// Official RFC 9382 data retains IETF Trust / IRTF notice; see
// third-party/ietf/RFC9382-IRTF-NOTICE in the producer source distribution.
// Edwards data: tools/rfc9382_vectors.py, python-ecdsa 0.19.1, independent hashlib/hmac.
package consumer
import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.Spake2Exception
import io.github.flyfish233.spake2.rfc.Rfc9382Session
import io.github.flyfish233.spake2.rfc.Rfc9382Suite
import kotlin.test.*

private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
    s.substring(2 * it, 2 * it + 2).toInt(16).toByte()
}
private data class Case(val suite: Rfc9382Suite, val a: String, val b: String,
    val aad: String, val w: String, val x: String, val y: String,
    val pA: String, val pB: String, val cA: String, val cB: String, val key: String)
private val cases = listOf(
@@CASES@@
)
class PublishedRfcTest {
    private fun endpoint(v: Case, role: Role): Rfc9382Session =
        rfcSession(v.suite, role, hex(v.w), hex(v.a), hex(v.b), hex(v.aad),
            EntropySource { size ->
                assertEquals(32, size)
                hex(if (role == Role.ALICE) v.x else v.y)
            }, v.a.isEmpty() || v.b.isEmpty())

    @Test fun officialP256PublicMessagesConfirmationsAndKey() {
        val selected = cases.filter { it.suite == Rfc9382Suite.P256_SHA256 }
        assertEquals(4, selected.size)
        selected.forEach(::exchange)
    }
    @Test fun independentEdwardsPublicMessagesConfirmationsAndKey() {
        val selected = cases.filter { it.suite == Rfc9382Suite.EDWARDS25519_SHA256 }
        assertEquals(12, selected.size)
        selected.forEach(::exchange)
    }
    private fun exchange(v: Case) {
        val a = endpoint(v, Role.ALICE)
        val b = endpoint(v, Role.BOB)
        try {
            assertEquals(Rfc9382Session.State.NEW, a.state)
            val pA = a.generateMessage()
            val pB = b.generateMessage()
            assertContentEquals(hex(v.pA), pA)
            assertContentEquals(hex(v.pB), pB)
            val cA = a.processPeerMessage(pB)
            val cB = b.processPeerMessage(pA)
            assertContentEquals(hex(v.cA), cA)
            assertContentEquals(hex(v.cB), cB)
            assertEquals(Rfc9382Session.State.AWAITING_CONFIRMATION, a.state)
            val ka = a.confirm(cB)
            val kb = b.confirm(cA)
            try {
                assertContentEquals(hex(v.key), ka)
                assertContentEquals(hex(v.key), kb)
                assertEquals(Rfc9382Session.State.COMPLETE, a.state)
                assertFailsWith<Spake2Exception> { a.confirm(cB) }
            } finally { ka.fill(0); kb.fill(0) }
        } finally { a.destroy(); b.destroy() }
    }
    @Test fun incorrectConfirmationIsTerminalInPublishedLibrary() {
        for (suite in Rfc9382Suite.entries) {
            val v = cases.first { it.suite == suite }
            val a = endpoint(v, Role.ALICE)
            try {
                a.generateMessage()
                a.processPeerMessage(hex(v.pB))
                val invalid = hex(v.cB)
                invalid[0] = (invalid[0].toInt() xor 1).toByte()
                assertFailsWith<Spake2Exception> { a.confirm(invalid) }
                assertEquals(Rfc9382Session.State.FAILED, a.state)
                assertFailsWith<Spake2Exception> { a.confirm(hex(v.cB)) }
            } finally { a.destroy() }
            assertEquals(Rfc9382Session.State.DESTROYED, a.state)
        }
    }
}
""".replace("@@CASES@@", ",\n".join(rfcs))
write("rfc-client/src/commonTest/kotlin/consumer/PublishedRfcTest.kt", rfc_tests)

legacy = json.loads((root / "test-vectors/legacy/boringssl-spake2-vectors.json").read_text())
rows = []
for v in legacy["exchanges"]:
    if v["alice"]["disablePasswordScalarHack"] or v["bob"]["disablePasswordScalarHack"]:
        continue
    a,b = v["alice"],v["bob"]
    fields = [v["id"],a["myNameHex"],a["theirNameHex"],a["passwordHex"],
              a["generate"]["privateInput64Hex"],b["generate"]["privateInput64Hex"],
              a["generate"]["messageHex"],b["generate"]["messageHex"],
              v["aliceProcess"]["keyHex"],v["bobProcess"]["keyHex"]]
    rows.append("    Case(" + ", ".join(map(quoted,fields)) + ")")
legacy_tests = """
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
@@CASES@@
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
""".replace("@@CASES@@", ",\n".join(rows))
write("legacy-client/src/commonTest/kotlin/consumer/PublishedLegacyTest.kt", legacy_tests)
print("Generated public-API-only consumers: 4 P-256, 12 Edwards, 5 HARDENED Legacy fixtures.")
