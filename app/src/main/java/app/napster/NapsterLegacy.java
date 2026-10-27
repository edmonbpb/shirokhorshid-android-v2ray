package app.napster;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure Java 8 decryptor for Napster Legacy (.npvt) format.
 * Ported from slipnet-research/pantegnos/npvt.go.
 * No android.* or org.json dependencies.
 */
public final class NapsterLegacy {

    public static final String MARKER_NPVT1 = "NPVT1";
    public static final String MARKER_NPVTSUB1 = "NPVTSUB1";

    private static final int[] SHIFT_ORDER = {0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11};
    private static final int NR = 2;

    private NapsterLegacy() {
    }

    public static boolean isLegacy(byte[] data) {
        if (data == null || data.length < 5) {
            return false;
        }
        String head = new String(data, 0, Math.min(data.length, 64), Charset.forName("US-ASCII")).trim();
        return head.startsWith(MARKER_NPVT1) || head.startsWith(MARKER_NPVTSUB1);
    }

    /**
     * Decrypts all comma-separated tokens in the legacy payload.
     * Returns a list of decrypted plaintext blobs (byte arrays).
     */
    public static List<byte[]> decryptPayload(byte[] data, byte[] legacyTables) throws IOException {
        if (legacyTables == null || legacyTables.length != NapsterTables.LEGACY_TABLES_SIZE) {
            throw new IOException("Invalid legacy tables");
        }

        String text = new String(data, Charset.forName("UTF-8")).trim();
        if (text.startsWith(MARKER_NPVTSUB1)) {
            text = text.substring(MARKER_NPVTSUB1.length()).trim();
        } else if (text.startsWith(MARKER_NPVT1)) {
            text = text.substring(MARKER_NPVT1.length()).trim();
        }

        String[] tokens = text.split(",");
        List<byte[]> decryptedBlobs = new ArrayList<>();

        for (String tok : tokens) {
            tok = tok.trim();
            if (tok.isEmpty()) {
                continue;
            }
            byte[] ciphertextWithNonce = decodeToken(tok);
            if (ciphertextWithNonce == null || ciphertextWithNonce.length < 16) {
                continue;
            }

            byte[] pt = decryptBlob(ciphertextWithNonce, legacyTables);
            if (pt != null && pt.length > 0) {
                decryptedBlobs.add(pt);
            }
        }

        return decryptedBlobs;
    }

    public static byte[] decryptBlob(byte[] ciphertextWithNonce, byte[] legacyTables) {
        if (ciphertextWithNonce == null || ciphertextWithNonce.length < 16) {
            return null;
        }
        byte[] nonce = new byte[16];
        System.arraycopy(ciphertextWithNonce, 0, nonce, 0, 16);
        int ctLen = ciphertextWithNonce.length - 16;
        byte[] ct = new byte[ctLen];
        System.arraycopy(ciphertextWithNonce, 16, ct, 0, ctLen);

        return ctrCrypt(nonce, ct, legacyTables);
    }

    private static byte[] ctrCrypt(byte[] nonce, byte[] data, byte[] tables) {
        byte[] counter = new byte[16];
        System.arraycopy(nonce, 0, counter, 0, 16);
        byte[] out = new byte[data.length];

        for (int off = 0; off < data.length; off += 16) {
            byte[] keystream = coreTransform(counter, tables);
            ctrIncrement(counter);
            int blockLen = Math.min(data.length - off, 16);
            for (int i = 0; i < blockLen; i++) {
                out[off + i] = (byte) (data[off + i] ^ keystream[i]);
            }
        }
        return out;
    }

    private static void ctrIncrement(byte[] counter) {
        for (int i = 15; i >= 0; i--) {
            int val = (counter[i] & 0xFF) + 1;
            counter[i] = (byte) val;
            if ((val & 0xFF) != 0) {
                break;
            }
        }
    }

    private static byte[] shiftRowsLike(byte[] b) {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = b[SHIFT_ORDER[i]];
        }
        return out;
    }

    private static int getBE32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) |
                ((b[off + 1] & 0xFF) << 16) |
                ((b[off + 2] & 0xFF) << 8) |
                (b[off + 3] & 0xFF);
    }

    private static byte coreXor(byte[] tables, int t, int a, int b) {
        // xorTable offset: 16384 + 16384 + 4096 = 36864
        // Index: 36864 + (t * 256) + (a * 16) + b
        int idx = 36864 + (t << 8) + (a << 4) + b;
        return tables[idx];
    }

    private static byte[] coreTransform(byte[] block, byte[] tables) {
        byte[] buf = new byte[16];
        System.arraycopy(block, 0, buf, 0, 16);

        int iB = NR - 1; // 9 rounds
        for (int round = 0; round < iB; round++) {
            buf = shiftRowsLike(buf);

            for (int i11 = 0; i11 < 4; i11++) {
                int i13 = i11 * 4;
                int i14 = i13 + 1;
                int i15 = i13 + 2;
                int i16 = i13 + 3;

                // tyBoxes offset = 0..16383: 16 rows of 256 uint32
                int iC = getBE32(tables, (i13 * 256 + (buf[i13] & 0xFF)) * 4);
                int iC2 = getBE32(tables, (i14 * 256 + (buf[i14] & 0xFF)) * 4);
                int iC3 = getBE32(tables, (i15 * 256 + (buf[i15] & 0xFF)) * 4);
                int iC4 = getBE32(tables, (i16 * 256 + (buf[i16] & 0xFF)) * 4);

                for (int i17 = 0; i17 < 4; i17++) {
                    int i18 = (i11 * 24) + (i17 * 6);
                    int i19 = i17 * 8;
                    int i20 = 28 - i19;
                    int i22 = 24 - i19;

                    int n1 = (iC >>> i20) & 15;
                    int n2 = (iC2 >>> i20) & 15;
                    int n3 = (iC3 >>> i20) & 15;
                    int n4 = (iC4 >>> i20) & 15;
                    byte b10 = coreXor(tables, i18, n1, n2);
                    byte b11 = coreXor(tables, i18 + 1, n3, n4);

                    int m1 = (iC >>> i22) & 15;
                    int m2 = (iC2 >>> i22) & 15;
                    int m3 = (iC3 >>> i22) & 15;
                    int m4 = (iC4 >>> i22) & 15;
                    int p3 = coreXor(tables, i18 + 2, m1, m2) & 0xFF;
                    int p4 = coreXor(tables, i18 + 3, m3, m4) & 0xFF;
                    int lo = coreXor(tables, i18 + 5, p3, p4) & 0xFF;
                    int hi = coreXor(tables, i18 + 4, b10 & 0xFF, b11 & 0xFF) & 0xFF;

                    buf[i13 + i17] = (byte) (lo | (hi << 4));
                }

                // mbl offset = 16384..32767: 16 rows of 256 uint32
                int iC5 = getBE32(tables, 16384 + (i13 * 256 + (buf[i13] & 0xFF)) * 4);
                int iC6 = getBE32(tables, 16384 + (i14 * 256 + (buf[i14] & 0xFF)) * 4);
                int iC7 = getBE32(tables, 16384 + (i15 * 256 + (buf[i15] & 0xFF)) * 4);
                int iC8 = getBE32(tables, 16384 + (i16 * 256 + (buf[i16] & 0xFF)) * 4);

                for (int i27 = 0; i27 < 4; i27++) {
                    int i28 = (i11 * 24) + (i27 * 6);
                    int i29 = i27 * 8;
                    int i30 = 28 - i29;
                    int i31 = 24 - i29;

                    int n1 = (iC5 >>> i30) & 15;
                    int n2 = (iC6 >>> i30) & 15;
                    int n3 = (iC7 >>> i30) & 15;
                    int n4 = (iC8 >>> i30) & 15;
                    byte a1 = coreXor(tables, i28, n1, n2);
                    byte a2 = coreXor(tables, i28 + 1, n3, n4);
                    int hi = coreXor(tables, i28 + 4, a1 & 0xFF, a2 & 0xFF) & 0xFF;

                    int m1 = (iC5 >>> i31) & 15;
                    int m2 = (iC6 >>> i31) & 15;
                    int m3 = (iC7 >>> i31) & 15;
                    int m4 = (iC8 >>> i31) & 15;
                    byte b1 = coreXor(tables, i28 + 2, m1, m2);
                    byte b2 = coreXor(tables, i28 + 3, m3, m4);
                    int lo = coreXor(tables, i28 + 5, b1 & 0xFF, b2 & 0xFF) & 0xFF;

                    buf[i13 + i27] = (byte) ((hi << 4) | lo);
                }
            }
        }

        buf = shiftRowsLike(buf);

        // tboxesLast offset = 32768..36863: 16 rows of 256 uint8
        for (int i = 0; i < 16; i++) {
            int idx = 32768 + i * 256 + (buf[i] & 0xFF);
            buf[i] = tables[idx];
        }

        return buf;
    }

    /**
     * Decodes a token that may be Base64 (standard, URL-safe, padded or unpadded) or hex.
     */
    public static byte[] decodeToken(String text) {
        if (text == null) {
            return null;
        }
        text = text.trim();
        if (text.startsWith(MARKER_NPVTSUB1)) {
            text = text.substring(MARKER_NPVTSUB1.length()).trim();
        } else if (text.startsWith(MARKER_NPVT1)) {
            text = text.substring(MARKER_NPVT1.length()).trim();
        }

        // Try Base64 first
        byte[] b64 = decodeBase64(text);
        if (b64 != null && b64.length >= 16) {
            return b64;
        }

        // Try Hex
        byte[] hex = decodeHex(text);
        if (hex != null && hex.length >= 16) {
            return hex;
        }

        return b64; // Fallback if b64 was valid but < 16 bytes
    }

    public static byte[] decodeBase64(String s) {
        if (s == null) {
            return null;
        }
        s = s.replace('-', '+').replace('_', '/').trim();
        int pad = (4 - (s.length() % 4)) % 4;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < pad; i++) {
            sb.append('=');
        }
        s = sb.toString();

        int len = s.length();
        if (len % 4 != 0) {
            return null;
        }

        int paddingCount = 0;
        if (len > 0 && s.charAt(len - 1) == '=') paddingCount++;
        if (len > 1 && s.charAt(len - 2) == '=') paddingCount++;

        int outLen = (len * 3) / 4 - paddingCount;
        byte[] out = new byte[outLen];
        int outIdx = 0;

        for (int i = 0; i < len; i += 4) {
            int b0 = b64Val(s.charAt(i));
            int b1 = b64Val(s.charAt(i + 1));
            int b2 = s.charAt(i + 2) == '=' ? 0 : b64Val(s.charAt(i + 2));
            int b3 = s.charAt(i + 3) == '=' ? 0 : b64Val(s.charAt(i + 3));

            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
                return null;
            }

            int triple = (b0 << 18) | (b1 << 12) | (b2 << 6) | b3;

            if (outIdx < outLen) out[outIdx++] = (byte) (triple >>> 16);
            if (outIdx < outLen) out[outIdx++] = (byte) (triple >>> 8);
            if (outIdx < outLen) out[outIdx++] = (byte) triple;
        }
        return out;
    }

    private static int b64Val(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    }

    public static byte[] decodeHex(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2) {
            int h1 = Character.digit(s.charAt(i), 16);
            int h2 = Character.digit(s.charAt(i + 1), 16);
            if (h1 < 0 || h2 < 0) {
                return null;
            }
            out[i / 2] = (byte) ((h1 << 4) | h2);
        }
        return out;
    }
}
