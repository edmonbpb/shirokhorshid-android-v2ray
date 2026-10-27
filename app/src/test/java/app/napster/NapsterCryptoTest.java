package app.napster;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.Inflater;

/**
 * Pure JVM tests for Napster cryptographic primitives:
 * - RFC 8439 ChaCha20-Poly1305 AEAD
 * - RFC 5869 HKDF-SHA256
 * - Gen2 Whitebox A16 & KDK vectors from pantegnos/npvs_gen2_test.go
 * No Android classes or Context required.
 */
public class NapsterCryptoTest {

    @Test
    public void testRfc8439ChaCha20Poly1305Vector() throws Exception {
        // RFC 8439 Section 2.8.2 test vector
        byte[] key = hexToBytes("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        byte[] nonce = hexToBytes("070000004041424344454647");
        byte[] aad = hexToBytes("50515253c0c1c2c3c4c5c6c7");
        byte[] plaintext = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".getBytes("UTF-8");
        byte[] expectedCt = hexToBytes(
                "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b6116");
        byte[] expectedTag = hexToBytes("1ae10b594f09e26a7e902ecbd0600691");

        byte[] ctAndTag = new byte[expectedCt.length + expectedTag.length];
        System.arraycopy(expectedCt, 0, ctAndTag, 0, expectedCt.length);
        System.arraycopy(expectedTag, 0, ctAndTag, expectedCt.length, expectedTag.length);

        byte[] decrypted = ChaCha20Poly1305.open(key, nonce, ctAndTag, aad);
        Assert.assertNotNull("AEAD decrypt failed", decrypted);
        Assert.assertArrayEquals(plaintext, decrypted);

        byte[] sealed = ChaCha20Poly1305.seal(key, nonce, plaintext, aad);
        Assert.assertNotNull("AEAD seal failed", sealed);
        Assert.assertArrayEquals(ctAndTag, sealed);
    }

    @Test
    public void testRfc5869HkdfSha256Vector() throws Exception {
        // RFC 5869 Test Case 1
        byte[] ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = hexToBytes("000102030405060708090a0b0c");
        byte[] info = hexToBytes("f0f1f2f3f4f5f6f7f8f9");
        byte[] expectedOkm = hexToBytes("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");

        byte[] okm = HkdfSha256.deriveKey(ikm, salt, info, 42);
        Assert.assertArrayEquals(expectedOkm, okm);
    }

    @Test
    public void testGen2A16AndKdkVectors() throws Exception {
        // Decompress gen2_tables.bin.z from assets
        File assetFile = new File("app/src/main/assets/napster/gen2_tables.bin.z");
        if (!assetFile.exists()) {
            assetFile = new File("src/main/assets/napster/gen2_tables.bin.z");
        }
        Assert.assertTrue("gen2_tables.bin.z asset file must exist", assetFile.exists());

        byte[] compressed = readFully(new FileInputStream(assetFile));
        byte[] tables = decompressZlib(compressed, NapsterTables.GEN2_UNCOMPRESSED_SIZE);
        Assert.assertEquals(NapsterTables.GEN2_UNCOMPRESSED_SIZE, tables.length);

        // Vector 1 from npvs_gen2_test.go
        byte[] salt1 = hexToBytes("b3e16e38809576cbf6aaafd638b6b070");
        byte[] expectedA16 = hexToBytes("ffd6b86349231c14b37f6c929e24cd42");
        byte[] a16 = NapsterGen2.computeA16(salt1, tables);
        Assert.assertArrayEquals(expectedA16, a16);

        // Vector 2
        byte[] salt2 = hexToBytes("000102030405060708090a0b0c0d0e0f");
        byte[] expectedA16_2 = hexToBytes("f659c73d8c0fa5150e3254dfe0a1106d");
        Assert.assertArrayEquals(expectedA16_2, NapsterGen2.computeA16(salt2, tables));

        // KDK vector
        byte[] cfgID = hexToBytes("96b7d923079c7bc36d1310bd4d111a79");
        byte[] expectedKdk = hexToBytes("52043647a29853e765f8efe370fac47155616bfaf653d61b0075a2f183fb6b6b");
        byte[] kdk = NapsterGen2.computeKDK(a16, cfgID);
        Assert.assertArrayEquals(expectedKdk, kdk);
    }

    @Test
    public void testGen2AppKeyWrapVector() throws Exception {
        byte[] kdk = hexToBytes("52043647a29853e765f8efe370fac47155616bfaf653d61b0075a2f183fb6b6b");
        byte[] salt = hexToBytes("b3e16e38809576cbf6aaafd638b6b070");
        byte[] wrap = hexToBytes("3b4c1a5ce306badbfc754cd16f18298259ab4433148eeea0fea4a8eb7cf2fb0c"
                + "95a428db986d2d8ed5f96979f14d95ee76d6e73a94677806d666decc");
        byte[] dek = ChaCha20Poly1305.open(kdk, Arrays.copyOfRange(wrap, 0, 12),
                Arrays.copyOfRange(wrap, 12, wrap.length), salt);
        Assert.assertNotNull(dek);
        Assert.assertEquals(32, dek.length);
    }

    private static byte[] readFully(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        is.close();
        return baos.toByteArray();
    }

    private static byte[] decompressZlib(byte[] data, int expectedSize) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        byte[] out = new byte[expectedSize];
        int count = 0;
        while (!inflater.finished() && count < expectedSize) {
            int n = inflater.inflate(out, count, expectedSize - count);
            if (n == 0) break;
            count += n;
        }
        inflater.end();
        return out;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
