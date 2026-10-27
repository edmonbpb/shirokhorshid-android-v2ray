package app.napster;

import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Pure Java 8 ChaCha20-Poly1305 AEAD cipher per RFC 8439.
 * API 14+ safe, does not depend on javax.crypto ChaCha20-Poly1305 (which requires Java 11 / API 28).
 */
public final class ChaCha20Poly1305 {

    private static final int KEY_LEN = 32;
    private static final int NONCE_LEN = 12;
    private static final int TAG_LEN = 16;

    private ChaCha20Poly1305() {
    }

    public static byte[] open(byte[] key, byte[] nonce, byte[] ciphertextWithTag, byte[] aad)
            throws GeneralSecurityException {
        if (key == null || key.length != KEY_LEN) {
            throw new IllegalArgumentException("key must be 32 bytes");
        }
        if (nonce == null || nonce.length != NONCE_LEN) {
            throw new IllegalArgumentException("nonce must be 12 bytes");
        }
        if (ciphertextWithTag == null || ciphertextWithTag.length < TAG_LEN) {
            throw new GeneralSecurityException("ciphertext too short");
        }
        if (aad == null) {
            aad = new byte[0];
        }

        int ctLen = ciphertextWithTag.length - TAG_LEN;
        byte[] expectedTag = new byte[TAG_LEN];
        System.arraycopy(ciphertextWithTag, ctLen, expectedTag, 0, TAG_LEN);

        // Generate Poly1305 key from chacha20 block 0
        byte[] polyKey = generatePolyKey(key, nonce);

        // Compute tag
        byte[] computedTag = computeTag(polyKey, aad, ciphertextWithTag, 0, ctLen);

        // Constant time comparison
        if (!constantTimeEquals(expectedTag, computedTag)) {
            throw new GeneralSecurityException("tag mismatch");
        }

        // Decrypt ciphertext using ChaCha20 starting at counter = 1
        byte[] plaintext = new byte[ctLen];
        chacha20Xor(key, nonce, 1, ciphertextWithTag, 0, plaintext, 0, ctLen);
        return plaintext;
    }

    public static byte[] seal(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        if (key == null || key.length != KEY_LEN) {
            throw new IllegalArgumentException("key must be 32 bytes");
        }
        if (nonce == null || nonce.length != NONCE_LEN) {
            throw new IllegalArgumentException("nonce must be 12 bytes");
        }
        if (plaintext == null) {
            plaintext = new byte[0];
        }
        if (aad == null) {
            aad = new byte[0];
        }

        int ptLen = plaintext.length;
        byte[] ciphertextWithTag = new byte[ptLen + TAG_LEN];

        // Encrypt plaintext using ChaCha20 starting at counter = 1
        chacha20Xor(key, nonce, 1, plaintext, 0, ciphertextWithTag, 0, ptLen);

        // Generate Poly1305 key from chacha20 block 0
        byte[] polyKey = generatePolyKey(key, nonce);

        // Compute tag
        byte[] computedTag = computeTag(polyKey, aad, ciphertextWithTag, 0, ptLen);
        System.arraycopy(computedTag, 0, ciphertextWithTag, ptLen, TAG_LEN);

        return ciphertextWithTag;
    }

    private static byte[] generatePolyKey(byte[] key, byte[] nonce) {
        byte[] block0 = new byte[64];
        chacha20Xor(key, nonce, 0, new byte[64], 0, block0, 0, 64);
        byte[] polyKey = new byte[32];
        System.arraycopy(block0, 0, polyKey, 0, 32);
        return polyKey;
    }

    private static byte[] computeTag(byte[] polyKey, byte[] aad, byte[] ct, int ctOff, int ctLen) {
        Poly1305 poly = new Poly1305(polyKey);
        poly.update(aad, 0, aad.length);
        int aadPad = (16 - (aad.length % 16)) % 16;
        if (aadPad > 0) {
            poly.update(new byte[aadPad], 0, aadPad);
        }

        poly.update(ct, ctOff, ctLen);
        int ctPad = (16 - (ctLen % 16)) % 16;
        if (ctPad > 0) {
            poly.update(new byte[ctPad], 0, ctPad);
        }

        byte[] lenBuf = new byte[16];
        putLongLittleEndian(lenBuf, 0, aad.length);
        putLongLittleEndian(lenBuf, 8, ctLen);
        poly.update(lenBuf, 0, 16);

        return poly.digest();
    }

    private static void putLongLittleEndian(byte[] b, int off, long val) {
        b[off] = (byte) val;
        b[off + 1] = (byte) (val >>> 8);
        b[off + 2] = (byte) (val >>> 16);
        b[off + 3] = (byte) (val >>> 24);
        b[off + 4] = (byte) (val >>> 32);
        b[off + 5] = (byte) (val >>> 40);
        b[off + 6] = (byte) (val >>> 48);
        b[off + 7] = (byte) (val >>> 56);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int res = 0;
        for (int i = 0; i < a.length; i++) {
            res |= (a[i] ^ b[i]);
        }
        return res == 0;
    }

    /**
     * ChaCha20 block and stream cipher per RFC 8439.
     */
    public static void chacha20Xor(byte[] key, byte[] nonce, int initialCounter,
                                   byte[] in, int inOff, byte[] out, int outOff, int len) {
        int[] keyWords = new int[8];
        for (int i = 0; i < 8; i++) {
            keyWords[i] = getIntLittleEndian(key, i * 4);
        }
        int[] nonceWords = new int[3];
        for (int i = 0; i < 3; i++) {
            nonceWords[i] = getIntLittleEndian(nonce, i * 4);
        }

        int counter = initialCounter;
        byte[] blockBuf = new byte[64];

        int processed = 0;
        while (processed < len) {
            chacha20Block(keyWords, counter, nonceWords, blockBuf);
            counter++;

            int toXor = Math.min(64, len - processed);
            for (int i = 0; i < toXor; i++) {
                out[outOff + processed + i] = (byte) (in[inOff + processed + i] ^ blockBuf[i]);
            }
            processed += toXor;
        }
    }

    private static void chacha20Block(int[] key, int counter, int[] nonce, byte[] out) {
        int[] state = new int[16];
        // Constants "expand 32-byte k"
        state[0] = 0x61707865;
        state[1] = 0x3320646e;
        state[2] = 0x79622d32;
        state[3] = 0x6b206574;
        System.arraycopy(key, 0, state, 4, 8);
        state[12] = counter;
        System.arraycopy(nonce, 0, state, 13, 3);

        int[] working = new int[16];
        System.arraycopy(state, 0, working, 0, 16);

        for (int i = 0; i < 10; i++) {
            // Inner block / column round
            quarterRound(working, 0, 4, 8, 12);
            quarterRound(working, 1, 5, 9, 13);
            quarterRound(working, 2, 6, 10, 14);
            quarterRound(working, 3, 7, 11, 15);
            // Diagonal round
            quarterRound(working, 0, 5, 10, 15);
            quarterRound(working, 1, 6, 11, 12);
            quarterRound(working, 2, 7, 8, 13);
            quarterRound(working, 3, 4, 9, 14);
        }

        for (int i = 0; i < 16; i++) {
            putIntLittleEndian(out, i * 4, working[i] + state[i]);
        }
    }

    private static void quarterRound(int[] s, int a, int b, int c, int d) {
        s[a] += s[b]; s[d] = rotateLeft(s[d] ^ s[a], 16);
        s[c] += s[d]; s[b] = rotateLeft(s[b] ^ s[c], 12);
        s[a] += s[b]; s[d] = rotateLeft(s[d] ^ s[a], 8);
        s[c] += s[d]; s[b] = rotateLeft(s[b] ^ s[c], 7);
    }

    private static int rotateLeft(int v, int bits) {
        return (v << bits) | (v >>> (32 - bits));
    }

    private static int getIntLittleEndian(byte[] b, int off) {
        return (b[off] & 0xFF) |
                ((b[off + 1] & 0xFF) << 8) |
                ((b[off + 2] & 0xFF) << 16) |
                ((b[off + 3] & 0xFF) << 24);
    }

    private static void putIntLittleEndian(byte[] b, int off, int val) {
        b[off] = (byte) val;
        b[off + 1] = (byte) (val >>> 8);
        b[off + 2] = (byte) (val >>> 16);
        b[off + 3] = (byte) (val >>> 24);
    }

    /**
     * Poly1305 one-time authenticator per RFC 8439.
     */
    private static final class Poly1305 {
        // r clamping and key parts
        private final long r0, r1, r2, r3, r4;
        private final long s1, s2, s3, s4;
        private final long pad0, pad1;

        // Accumulator
        private long h0 = 0, h1 = 0, h2 = 0, h3 = 0, h4 = 0;

        private final byte[] buf = new byte[16];
        private int bufLen = 0;

        Poly1305(byte[] key) {
            // r = key[0..15] clamped
            long t0 = getIntLittleEndian(key, 0) & 0xFFFFFFFFL;
            long t1 = getIntLittleEndian(key, 4) & 0xFFFFFFFFL;
            long t2 = getIntLittleEndian(key, 8) & 0xFFFFFFFFL;
            long t3 = getIntLittleEndian(key, 12) & 0xFFFFFFFFL;

            // Clamping r
            t0 &= 0x0FFFFFFFL;
            t1 &= 0x0FFFFFFCL;
            t2 &= 0x0FFFFFFCL;
            t3 &= 0x0FFFFFFCL;

            // 26-bit limbs for r
            r0 = t0 & 0x3FFFFFFL;
            r1 = ((t0 >>> 26) | (t1 << 6)) & 0x3FFFFFFL;
            r2 = ((t1 >>> 20) | (t2 << 12)) & 0x3FFFFFFL;
            r3 = ((t2 >>> 14) | (t3 << 18)) & 0x3FFFFFFL;
            r4 = (t3 >>> 8) & 0x03FFFFFFL;

            s1 = r1 * 5;
            s2 = r2 * 5;
            s3 = r3 * 5;
            s4 = r4 * 5;

            // s = key[16..31] (pad)
            pad0 = (getIntLittleEndian(key, 16) & 0xFFFFFFFFL)
                    | (((long) getIntLittleEndian(key, 20) & 0xFFFFFFFFL) << 32);
            pad1 = (getIntLittleEndian(key, 24) & 0xFFFFFFFFL)
                    | (((long) getIntLittleEndian(key, 28) & 0xFFFFFFFFL) << 32);
        }

        void update(byte[] m, int off, int len) {
            if (bufLen > 0) {
                int toCopy = Math.min(16 - bufLen, len);
                System.arraycopy(m, off, buf, bufLen, toCopy);
                bufLen += toCopy;
                off += toCopy;
                len -= toCopy;
                if (bufLen == 16) {
                    processBlock(buf, 0, 16, false);
                    bufLen = 0;
                }
            }

            while (len >= 16) {
                processBlock(m, off, 16, false);
                off += 16;
                len -= 16;
            }

            if (len > 0) {
                System.arraycopy(m, off, buf, 0, len);
                bufLen = len;
            }
        }

        private void processBlock(byte[] block, int off, int len, boolean isFinal) {
            byte[] b = block;
            if (off != 0 || len != 16) {
                b = new byte[16];
                System.arraycopy(block, off, b, 0, len);
            }

            long t0 = getIntLittleEndian(b, 0) & 0xFFFFFFFFL;
            long t1 = getIntLittleEndian(b, 4) & 0xFFFFFFFFL;
            long t2 = getIntLittleEndian(b, 8) & 0xFFFFFFFFL;
            long t3 = getIntLittleEndian(b, 12) & 0xFFFFFFFFL;

            long w0 = t0 & 0x3FFFFFFL;
            long w1 = ((t0 >>> 26) | (t1 << 6)) & 0x3FFFFFFL;
            long w2 = ((t1 >>> 20) | (t2 << 12)) & 0x3FFFFFFL;
            long w3 = ((t2 >>> 14) | (t3 << 18)) & 0x3FFFFFFL;
            long w4 = (t3 >>> 8) | (1L << 24); // 2^128 bit is bit 24 of limb 4

            h0 += w0;
            h1 += w1;
            h2 += w2;
            h3 += w3;
            h4 += w4;

            // Multiply h by r mod (2^130 - 5)
            long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
            long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
            long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
            long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
            long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;

            long c;
            c = d0 >>> 26; h0 = d0 & 0x3FFFFFFL; d1 += c;
            c = d1 >>> 26; h1 = d1 & 0x3FFFFFFL; d2 += c;
            c = d2 >>> 26; h2 = d2 & 0x3FFFFFFL; d3 += c;
            c = d3 >>> 26; h3 = d3 & 0x3FFFFFFL; d4 += c;
            c = d4 >>> 26; h4 = d4 & 0x3FFFFFFL; h0 += c * 5;
            c = h0 >>> 26; h0 = h0 & 0x3FFFFFFL; h1 += c;
        }

        byte[] digest() {
            if (bufLen > 0) {
                byte[] padded = new byte[16];
                System.arraycopy(buf, 0, padded, 0, bufLen);
                padded[bufLen] = 1;
                // For partial block, the 1 bit is already at padded[bufLen]
                long t0 = getIntLittleEndian(padded, 0) & 0xFFFFFFFFL;
                long t1 = getIntLittleEndian(padded, 4) & 0xFFFFFFFFL;
                long t2 = getIntLittleEndian(padded, 8) & 0xFFFFFFFFL;
                long t3 = getIntLittleEndian(padded, 12) & 0xFFFFFFFFL;

                long w0 = t0 & 0x3FFFFFFL;
                long w1 = ((t0 >>> 26) | (t1 << 6)) & 0x3FFFFFFL;
                long w2 = ((t1 >>> 20) | (t2 << 12)) & 0x3FFFFFFL;
                long w3 = ((t2 >>> 14) | (t3 << 18)) & 0x3FFFFFFL;
                long w4 = (t3 >>> 8) & 0x3FFFFFFL;

                h0 += w0;
                h1 += w1;
                h2 += w2;
                h3 += w3;
                h4 += w4;

                long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
                long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
                long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
                long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
                long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;

                long c;
                c = d0 >>> 26; h0 = d0 & 0x3FFFFFFL; d1 += c;
                c = d1 >>> 26; h1 = d1 & 0x3FFFFFFL; d2 += c;
                c = d2 >>> 26; h2 = d2 & 0x3FFFFFFL; d3 += c;
                c = d3 >>> 26; h3 = d3 & 0x3FFFFFFL; d4 += c;
                c = d4 >>> 26; h4 = d4 & 0x3FFFFFFL; h0 += c * 5;
                c = h0 >>> 26; h0 = h0 & 0x3FFFFFFL; h1 += c;
                bufLen = 0;
            }

            // Full carry reduction
            long c;
            c = h1 >>> 26; h1 &= 0x3FFFFFFL; h2 += c;
            c = h2 >>> 26; h2 &= 0x3FFFFFFL; h3 += c;
            c = h3 >>> 26; h3 &= 0x3FFFFFFL; h4 += c;
            c = h4 >>> 26; h4 &= 0x3FFFFFFL; h0 += c * 5;
            c = h0 >>> 26; h0 &= 0x3FFFFFFL; h1 += c;

            // Compute h - p
            long g0 = h0 + 5;
            c = g0 >>> 26; g0 &= 0x3FFFFFFL;
            long g1 = h1 + c;
            c = g1 >>> 26; g1 &= 0x3FFFFFFL;
            long g2 = h2 + c;
            c = g2 >>> 26; g2 &= 0x3FFFFFFL;
            long g3 = h3 + c;
            c = g3 >>> 26; g3 &= 0x3FFFFFFL;
            long g4 = h4 + c - (1L << 26);

            // Select h if g4 < 0 else g
            long mask = (g4 >> 63); // if g4 < 0: mask = -1; if g4 >= 0: mask = 0
            long nmask = ~mask;

            h0 = (h0 & mask) | (g0 & nmask);
            h1 = (h1 & mask) | (g1 & nmask);
            h2 = (h2 & mask) | (g2 & nmask);
            h3 = (h3 & mask) | (g3 & nmask);
            h4 = (h4 & mask) | (g4 & nmask);

            // Reconstruct 128-bit integer
            long f0 = (h0) | (h1 << 26);
            long f1 = (h1 >>> 6) | (h2 << 20);
            long f2 = (h2 >>> 12) | (h3 << 14);
            long f3 = (h3 >>> 18) | (h4 << 8);

            long low = (f0 & 0xFFFFFFFFL) | ((f1 & 0xFFFFFFFFL) << 32);
            long high = (f2 & 0xFFFFFFFFL) | ((f3 & 0xFFFFFFFFL) << 32);

            // Add pad mod 2^128
            long lowBefore = low;
            low += pad0;
            boolean carry = (lowBefore < 0 && pad0 < 0)
                    || (lowBefore < 0 && low >= 0)
                    || (pad0 < 0 && low >= 0);
            high += pad1 + (carry ? 1 : 0);

            byte[] out = new byte[16];
            putLongLittleEndian(out, 0, low);
            putLongLittleEndian(out, 8, high);
            return out;
        }
    }
}
