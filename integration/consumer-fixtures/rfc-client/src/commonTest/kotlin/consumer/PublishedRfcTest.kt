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
    Case(Rfc9382Suite.P256_SHA256, "736572766572", "636c69656e74", "", "2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f", "43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729", "dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be", "04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c", "0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7", "58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0", "d3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b", "0e0672dc86f8e45565d338b0540abe69"),
    Case(Rfc9382Suite.P256_SHA256, "", "636c69656e74", "", "0548d8729f730589e579b0475a582c1608138ddf7054b73b5381c7e883e2efae", "403abbe3b1b4b9ba17e3032849759d723939a27a27b9d921c500edde18ed654b", "903023b6598908936ea7c929bd761af6039577a9c3f9581064187c3049d87065", "04a897b769e681c62ac1c2357319a3d363f610839c4477720d24cbe32f5fd85f44fb92ba966578c1b712be6962498834078262caa5b441ecfa9d4a9485720e918a", "04e0f816fd1c35e22065d5556215c097e799390d16661c386e0ecc84593974a61b881a8c82327687d0501862970c64565560cb5671f696048050ca66ca5f8cc7fc", "47d29e6666af1b7dd450d571233085d7a9866e4d49d2645e2df975489521232b", "3313c5cefc361d27fb16847a91c2a73b766ffa90a4839122a9b70a2f6bd1d6df", "642f05c473c2cd79909f9a841e2f30a7"),
    Case(Rfc9382Suite.P256_SHA256, "736572766572", "", "", "626e0cdc7b14c9db3e52a0b1b3a768c98e37852d5db30febe0497b14eae8c254", "07adb3db6bc623d3399726bfdbfd3d15a58ea776ab8a308b00392621291f9633", "b6a4fc8dbb629d4ba51d6f91ed1532cf87adec98f25dd153a75accafafedec16", "04f88fb71c99bfffaea370966b7eb99cd4be0ff1a7d335caac4211c4afd855e2e15a873b298503ad8ba1d9cbb9a392d2ba309b48bfd7879aefd0f2cea6009763b0", "040c269d6be017dccb15182ac6bfcd9e2a14de019dd587eaf4bdfd353f031101e7cca177f8eb362a6e83e7d5e729c0732e1b528879c086f39ba0f31a9661bd34db", "bc9f9bbe99f26d0b2260e6456e05a86196a3307ec6663a18bf6ac825736533b2", "c2370e1bf813b086dff0d834e74425a06e6390f48f5411900276dcccc5a297ec", "005184ff460da2ce59062c87733c299c"),
    Case(Rfc9382Suite.P256_SHA256, "", "", "", "7bf46c454b4c1b25799527d896508afd5fc62ef4ec59db1efb49113063d70cca", "8cef65df64bb2d0f83540c53632de911b5b24b3eab6cc74a97609fd659e95473", "d7a66f64074a84652d8d623a92e20c9675c61cb5b4f6a0063e4648a2fdc02d53", "04a65b367a3f613cf9f0654b1b28a1e3a8a40387956c8ba6063e8658563890f46ca1ef6a676598889fc28de2950ab8120b79a5ef1ea4c9f44bc98f585634b46d66", "04589f13218822710d98d8b2123a079041052d9941b9cf88c6617ddb2fcc0494662eea8ba6b64692dc318250030c6af045cb738bc81ba35b043c3dcb46adf6f58d", "dfb4db8d48ae5a675963ea5e6c19d98d4ea028d8e898dad96ea19a80ade95dca", "d0f0609d1613138d354f7e95f19fb556bf52d751947241e8c7118df5ef0ae175", "fc6374762ba5cf11f4b2caa08b2cd1b9"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "736572766572", "636c69656e74", "", "0d5422b77a367252d4611a3ce8a1357a6a3389b28f128a42aa27280bd6b052db", "034289404e248ad42dd8a1cca68b643c7ab9c285b85bdb83dd42ae409451d07a", "01338b8c144114a1ce20d0d78ffb4fa9fcde438f7b472ea5a1e39895131f4e1f", "bb594d6994da6b964197fc2323b4aa7d1c9131a3a0bcbb3656f1ae9171401a73", "14a2a5779481b656529b8685d1287d17823dc7866ccbe0b13227a156d7bde25f", "cea4dc72575dd39da55d16ba54616df2da3be5da12b76ad3d6f7dc4747148011", "66902b9eaa09d450a73bfb4bb8ba8e08d298af0d244cc5ee6d6798842a483ffb", "6f0cf7cdea34153aec926dbd3183c2b7"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "", "636c69656e74", "", "029247048f829fa87591d70efca2706cade0672d6c5e8c633dfd42e3b5f08cad", "08059993736a6d15a65fc40ebbae7c1458ba68daa9f5e8934b771abee9ae1535", "0a6e62d37626dbd1f9b5793d83ac0cd056f7eed6f235c4c00b2c308ec8a13415", "e58681365e9fd7f466880a7a396a3935203f2b65b01078a49ebc8e15a829101e", "7699a7ad5b82f43ce1d287ea08c15c2519b16cb9444a7bebe9c467827628a71f", "259d2bc8c7e5876384b235461581064c21fdf52f63b1411b279f1b82e435695f", "5ab81fceef5c9570d3861a85fd00bc8decd6cf280e161dd1838c70ec3429776c", "c8c21726aa710d937ee2d51322c050d1"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "736572766572", "", "", "0081b1196fc4389e5f57c6b6ff54f68550a9edb57109105573e4a3869f33ecd4", "08dc372d4032725345d33e0d169e8970f37749fcdcc3c638e167b9bc037e10c6", "0f623955b363048cca032ada0ecf09ba8f73356fdea23faa4b4698754e661180", "e6d788c9fcc01845264cbe45b4c6f6a7ffd3a5b60fe364306fa9a379dbf53f82", "b0465ef15bb052ba6e22f0f42003953bd1cff42b1493227c8b556b932acc875a", "4252b571c97d2308ce72d418a2e3266b936907e3282e441efe1c4517d093553b", "8deec7a966e1a86052bf8db45543d5855f0561a0341ba30b16b22092f7137adf", "c3bbebc5027327a3ebb51a00e37d329c"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "", "", "", "01d766f7a4152817bf39ea0b97ce874d134815a6221c53f4df8373ba33d5f50d", "0665ff2f933e4cdbc7f158ac219fd259bccc34629708fd2caf9ff37ed91d9cdc", "0e3a4e4cb08716daa92846d47db4be111643dcdf238e8ec6d26c4b0f609f1ad1", "1d0b37279e70c42211923d785c0d7a4d5913ce1ef57bc9e2c7d88c1912b86bbb", "09b6bbc3e35fc49d89142e96bffd407146ac3da89ee685f14027fb422c457e7b", "f226c92ef6e8bc12a04585d32202fcab864def6f9bd5d2cbb0a6c45663a645d2", "61311ab4b5c750e4e89068b964ec5c5c963909a8edf792aae8d8d9ba4fc39d70", "ea1c1fe7f1d0690d2b7174059e5d375f"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "736572766572", "636c69656e74", "0001020304", "0cc95b0839e77e6dfcb3ecef24dc1b17c8bbaf96d1b5ccb728ac016870460687", "00b6ad183f2e9cfad1b9031b454d68cbdf412a7744e4c6669fe7d05df2ec0606", "01257f78b87688a132540fc701a2fdc8243c9c5f3cafa8d740d258b24840b16c", "542d0a134398549eed0fc1e213a6b1763440b1580194d1307f46847544682b58", "e36df73bda503a216f0677b7a3c2552d21a945f5f584d56c085d7e83dfbebe42", "f75eef35eefb0a1fd4bed356144ec493f75ae1937223a34734969f63e65cabcb", "56d850a7e963c6818797473abe896c5b4b0be2beccf51f05a68bbb8c30a1bdce", "9fb9e8bbaec3efe71d1b5aef2442279e"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "", "636c69656e74", "000102030405", "0a4f8770fd968b084132cbc08f1690d0a7ac60ad8ab9ffbfbb065b25f03537c7", "03674a52b6368110e34950f55d75c5cd457ec2bf6c36c270f88303033aaeceab", "0b046abaac92e30b016668e61a86df59929f26091c524eb387d1b1603fa21935", "1d4393319dd001eb7429d46de32ff6f59c1b9c84d73145de59a89959f908fa9a", "d11d2e289a36fb464c06dc21b8c2eb2202950ff1ae35895381d6a515f0c1b502", "55040ed74324ffe2f2a427e93f901e0641ebfa551068048286f8a41df35c1c4e", "0f4d75c7997aeac8701bf5bd10c8e3a35e427a97df22c1ed39bd4a90abad01c1", "cccb9c2eac5ebaca9f639764dc4e2134"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "736572766572", "", "00010203040506", "03a2e02cfe9a7b7ac16749b9b812fbdf1760cd12150fa8633b2bd15c7cc6237e", "088c531617b501afa76c51167e48b116be965b0f0c355e65f474446f0c6995be", "0e404bfec15a7b9b45e4cd498621c70dd7f1e03b419c2ab4531515f36b24bc44", "b9fd5f65f99a9d39565fc0ffbccdb9e9ac0fda4a1e0b8110272cad409fca74ab", "e19b21bb93c11e68fb6624ef098efe754b3585705a59d2567fa67190bc170c3b", "c3912695c48f8fb1c42f1273f101c4bd3a9e172212a4571ee85897b3cba72e70", "c7106f80dbc0524b84c9492815710fd7f8d89fe634337adf8784bd8811f7cace", "badd8028fa12441466694f9e7cd90767"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "", "", "0001020304050607", "05adc4a79d19d4db0041721065ddbd912d6adda2df0b9e2ad636223fbb7f43ee", "0ece6f10dfd54279b4f846e42fd6b11b5052541f5fb582b505719a4c7331de22", "0ab14cc99cf1ef51be7a358ed54454b466891685ca1f115b075000e0c2fa0a3d", "b6a9edc9e5d5d39bb7ce2385428479d2c07ab9eeb142ac0891f3f6d8294d05fb", "2a410bf7f7703c743835ead503c3b0e64022f1b2e1b16430c6d93eabb745cc5e", "0b4a9ed1d92e0a5680d7c14f15a0ba46f5f742ec130e191fd2d69a0466e55d7f", "20f9ea3b45fb0399114ffe51566d816a98462f244a247407be052d511511aabe", "be911cd50fea328ec073cdf541a35cc1"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "736572766572", "636c69656e74", "000102030405060708", "0000000000000000000000000000000000000000000000000000000000000000", "0a867d901869035fd97aac99cf93eee739e86a26126c2d7f46c8676fba49ca09", "03dd51c8ecbe426af565363b360fcab914528121bc37a0c16eaf9c598b97a381", "bebe46f54728e784f1172877bc616db970efca35f1e24cd376dd989eca74aa01", "c3591ccb2d98d6409b2ccbc6f16e7838c016ff38b0bd8ebe1f6a39985c33f205", "c1f6f5632db0dd80274543cff47825877b32be34bbb7df75c3ed3c3feff47fb4", "5134386cb7b9b78d5ba8654c0ab130f41904f952dbea84f9aedbeea9f3e161a0", "c6672a8da9a29970e46a16439d01efdb"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "", "636c69656e74", "00010203040506070809", "0000000000000000000000000000000000000000000000000000000000000001", "048202321a0596bff8684faa2f0bea832d5617b5f6d5a4e447dc069c88b442aa", "02ba6b0196cfdd9793b3b29ebc7fabccb5c56df0ea8a2f636f5b840c62be1aaf", "cc6fc6767fbe564581f0e0d0158e6da6538d358b8ca58e637b81392e258f3552", "ba5db6e36bf47bcfa2284ed2377bdabab799ea0502f63482ee3b611c84c9f395", "c34ca9b4de9cc5711923c079935ac15eb3645e2e730bae753851285c4ebfcc73", "e5e8144b99410f2cc4570e7c2a1a75456681deffb3f4bf9fa5b93a6c0ff3fe6d", "f1cfa0ac86bd8597609b9a610e5e3fd3"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "736572766572", "", "000102030405060708090a", "1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ec", "0b928a8dd8433f39a911c231ab97331bdd2c0642b4d0bdfd21e077d3846efed2", "094faf1417ad2f39e5bbc4843fd8d814797b01aa08f6fe971903cf69ed2b369f", "e695400cf8004a1e356a6c1d562635c15eda620fb632c9952a3f6f56725de899", "52a3651447d1557b5c2c79bdba0f953383c01d7bc71225a688828b03ac3f773f", "b5fe52fe310a633a99394457c10c59fe218dcbb9c2588cce4923794d50d83ae6", "bc12beec41719838885e14acb9e4c581ba1ef531e883c5b150cdb5c2b1f2ed18", "c0f7ab722b815384bef08c6dcd5b6ece"),
    Case(Rfc9382Suite.EDWARDS25519_SHA256, "", "", "000102030405060708090a0b", "0a706d19d0bdaf7c27a35d237b39ae8571a991cf2fb0d44fe8571b44a247b710", "0000000000000000000000000000000000000000000000000000000000000001", "1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ec", "5acfd159181aa31db494ab00d229b6f3916edf8759c2be552a7218066bf09b93", "1c7bbdd25df1c46c21074220107c7b0b2fbb278412218eea097cbb8abbd7d300", "716ecaef02be75043e93443c137b84d414693567d25b9ba344c97a9d803db819", "819b7b01a0980cd52cb98195cc17883e9a418177674f7c8a4dba3c4bf86ba42b", "ebcca25aeddfc7421239f082b3f774bc")
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
