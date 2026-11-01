// SPDX-License-Identifier: AGPL-3.0-only
// Test-only protocol model. It executes published SPAKE2 candidate JVM bytecode,
// but uses JCE and synthetic exporter bytes instead of Kadb/TLS providers.
import io.github.flyfish233.spake2.EntropySource;
import io.github.flyfish233.spake2.Role;
import io.github.flyfish233.spake2.legacy.LegacyPasswordScalarMode;
import io.github.flyfish233.spake2.legacy.LegacySpake25519;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class KadbBindingProbe {
    private static final byte[] CLIENT = utf8("adb pair client\u0000");
    private static final byte[] SERVER = utf8("adb pair server\u0000");
    private static final byte[] INFO = utf8("adb pairing_auth aes-128-gcm key");
    private static final byte[] NONCE_ZERO = new byte[12];
    private static final int PEER_INFO_BYTES = 8192;
    private static int passed;

    private record Keys(byte[] alice, byte[] bob) implements AutoCloseable {
        public void close() { Arrays.fill(alice, (byte) 0); Arrays.fill(bob, (byte) 0); }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static void require(boolean success, String description) {
        if (!success) throw new AssertionError(description);
    }

    private static EntropySource testEntropy(String label) throws Exception {
        byte[] fixed = MessageDigest.getInstance("SHA-512").digest(utf8(label));
        return new EntropySource() {
            private boolean consumed;
            public byte[] nextBytes(int size) {
                require(size == 64 && !consumed, "entropy requested once with exact length");
                consumed = true;
                byte[] out = fixed.clone();
                Arrays.fill(fixed, (byte) 0);
                return out;
            }
        };
    }

    private static byte[] hmac(byte[] key, byte[] message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    // HKDF-SHA256: absent salt becomes 32 zero bytes. Exactly one expand block
    // is needed for the ADB 16-byte AES key; INFO excludes the terminating NUL.
    private static byte[] deriveAesKey(byte[] unconfirmedKey) throws Exception {
        byte[] prk = hmac(new byte[32], unconfirmedKey);
        byte[] block = hmac(prk, concat(INFO, new byte[] {1}));
        try {
            return Arrays.copyOf(block, 16);
        } finally {
            Arrays.fill(prk, (byte) 0);
            Arrays.fill(block, (byte) 0);
        }
    }

    private static Keys exchange(String label, String aliceCode, byte[] aliceExporter,
                                 String bobCode, byte[] bobExporter,
                                 byte[] bobOwnName, byte[] bobPeerName,
                                 boolean expectSameUnconfirmedKey) throws Exception {
        require(aliceExporter.length == 64 && bobExporter.length == 64, "exporter length");
        LegacySpake25519 alice = new LegacySpake25519(
            Role.ALICE, CLIENT, SERVER, testEntropy(label + "/alice"), LegacyPasswordScalarMode.HARDENED);
        LegacySpake25519 bob = new LegacySpake25519(
            Role.BOB, bobOwnName, bobPeerName, testEntropy(label + "/bob"), LegacyPasswordScalarMode.HARDENED);
        byte[] pwA = concat(utf8(aliceCode), aliceExporter);
        byte[] pwB = concat(utf8(bobCode), bobExporter);
        byte[] materialA = null;
        byte[] materialB = null;
        try {
            byte[] msgA = alice.generateMessage(pwA);
            byte[] msgB = bob.generateMessage(pwB);
            require(msgA.length == 32 && msgB.length == 32, "Legacy wire length");
            materialA = alice.deriveUnconfirmedKey(msgB);
            materialB = bob.deriveUnconfirmedKey(msgA);
            require(materialA.length == 64 && materialB.length == 64, "Legacy output length");
            require(MessageDigest.isEqual(materialA, materialB) == expectSameUnconfirmedKey,
                    "unconfirmed-key equality must match expected binding");
            return new Keys(deriveAesKey(materialA), deriveAesKey(materialB));
        } finally {
            Arrays.fill(pwA, (byte) 0);
            Arrays.fill(pwB, (byte) 0);
            if (materialA != null) Arrays.fill(materialA, (byte) 0);
            if (materialB != null) Arrays.fill(materialB, (byte) 0);
            alice.destroy();
            bob.destroy();
        }
    }

    private static byte[] crypt(int mode, byte[] key, byte[] input) throws Exception {
        // A new Cipher instance intentionally demonstrates the protocol's use
        // of the same key/nonce in two directions; this is not production advice.
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, NONCE_ZERO));
        return cipher.doFinal(input);
    }

    private static byte[] peerInfo(int type, String value) {
        byte[] out = new byte[PEER_INFO_BYTES];
        out[0] = (byte) type;
        byte[] data = utf8(value);
        require(data.length > 0 && data.length < PEER_INFO_BYTES - 1, "test PeerInfo bound");
        System.arraycopy(data, 0, out, 1, data.length);
        return out;
    }

    private static boolean validDeviceGuid(byte[] plain) {
        if (plain.length != PEER_INFO_BYTES || plain[0] != 1) return false;
        // Same acceptance rule as current Kadb: nonempty opaque C string.
        for (int i = 1; i < plain.length; i++) {
            if (plain[i] == 0) return i > 1;
        }
        return false;
    }

    private static void requireAuthenticationFailure(byte[] key, byte[] ciphertext) throws Exception {
        try {
            crypt(Cipher.DECRYPT_MODE, key, ciphertext);
            throw new AssertionError("GCM accepted a mismatched key or tampered ciphertext");
        } catch (AEADBadTagException expected) {
            // Exactly the required negative outcome. Other exceptions fail the probe.
        }
    }

    private static void requireMismatchedKeysReject(Keys keys) throws Exception {
        require(!MessageDigest.isEqual(keys.alice(), keys.bob()), "HKDF keys differ");
        byte[] serverCiphertext = crypt(Cipher.ENCRYPT_MODE, keys.bob(), peerInfo(1, "synthetic-guid"));
        requireAuthenticationFailure(keys.alice(), serverCiphertext);
    }

    private static void pass(String id, String explanation) {
        passed++;
        System.out.println("PASS " + id + " " + explanation);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("SCOPE actual candidate Legacy/Primitives JVM JARs + JCE protocol model; synthetic exporters; no Kadb TLS/provider execution");
        System.out.println("JAVA " + System.getProperty("java.runtime.version"));
        System.out.println("AES_PROVIDER " + Cipher.getInstance("AES/GCM/NoPadding").getProvider().getName());
        System.out.println("HMAC_PROVIDER " + Mac.getInstance("HmacSHA256").getProvider().getName());
        System.out.println("LEGACY_ORIGIN " + LegacySpake25519.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("PRIMITIVES_ORIGIN " + EntropySource.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("STDLIB_ORIGIN " + kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation());
        byte[] exporterA = MessageDigest.getInstance("SHA-512").digest(utf8("synthetic-exporter-A"));
        byte[] exporterB = MessageDigest.getInstance("SHA-512").digest(utf8("synthetic-exporter-B"));

        try (Keys keys = exchange("same", "515109", exporterA, "515109", exporterA, SERVER, CLIENT, true)) {
            require(MessageDigest.isEqual(keys.alice(), keys.bob()), "matching HKDF keys");
            byte[] received = crypt(Cipher.DECRYPT_MODE, keys.alice(),
                crypt(Cipher.ENCRYPT_MODE, keys.bob(), peerInfo(1, "synthetic-guid")));
            require(validDeviceGuid(received), "authenticated PeerInfo succeeds");
            byte[] client = peerInfo(0, "synthetic-rsa-public-key");
            require(Arrays.equals(client, crypt(Cipher.DECRYPT_MODE, keys.bob(),
                crypt(Cipher.ENCRYPT_MODE, keys.alice(), client))), "reverse direction succeeds");
        }
        pass("same_code_same_exporter", "64-byte materials match; both GCM directions decrypt; device PeerInfo valid");

        try (Keys keys = exchange("exporter", "515109", exporterA, "515109", exporterB, SERVER, CLIENT, false)) {
            requireMismatchedKeysReject(keys);
        }
        pass("different_exporter", "same code; different material/key; GCM rejects");

        try (Keys keys = exchange("code", "515109", exporterA, "515110", exporterA, SERVER, CLIENT, false)) {
            requireMismatchedKeysReject(keys);
        }
        pass("different_code", "same exporter; different material/key; GCM rejects");

        try (Keys keys = exchange("missing-nul", "515109", exporterA, "515109", exporterA,
                                  utf8("adb pair server"), CLIENT, false)) {
            requireMismatchedKeysReject(keys);
        }
        try (Keys keys = exchange("wrong-identity", "515109", exporterA, "515109", exporterA,
                                  utf8("adb pair other\u0000"), CLIENT, false)) {
            requireMismatchedKeysReject(keys);
        }
        pass("identity_and_nul_binding", "explicit Alice/Bob ordering succeeds above; missing NUL and wrong server identity both fail GCM");

        try (Keys keys = exchange("nonce", "515109", exporterA, "515109", exporterA, SERVER, CLIENT, true)) {
            byte[] client = peerInfo(0, "synthetic-rsa-public-key");
            byte[] server = peerInfo(1, "synthetic-guid");
            byte[] clientCiphertext = crypt(Cipher.ENCRYPT_MODE, keys.alice(), client);
            byte[] serverCiphertext = crypt(Cipher.ENCRYPT_MODE, keys.bob(), server);
            require(clientCiphertext.length == PEER_INFO_BYTES + 16 && serverCiphertext.length == PEER_INFO_BYTES + 16,
                    "GCM ciphertext plus 128-bit tag");
            for (int i = 0; i < PEER_INFO_BYTES; i++) {
                require((byte)(clientCiphertext[i] ^ serverCiphertext[i]) == (byte)(client[i] ^ server[i]),
                        "same nonce exposes plaintext XOR at byte " + i);
            }
            byte[] reflectedPlaintext = crypt(Cipher.DECRYPT_MODE, keys.alice(), clientCiphertext);
            require(reflectedPlaintext[0] == 0 && !validDeviceGuid(reflectedPlaintext),
                    "reflected GCM-valid client ciphertext fails PeerInfo role validation");
            byte[] tampered = clientCiphertext.clone();
            tampered[0] ^= 1;
            requireAuthenticationFailure(keys.alice(), tampered);
        }
        pass("same_key_nonce_reuse", "8192-byte plaintext XOR revealed in inner ciphertexts; reflection type0 rejected; changed type breaks tag");
        require(passed == 5, "exactly the five requested bounded case groups");
        System.out.println("RESULT PASS groups=" + passed);
    }
}
