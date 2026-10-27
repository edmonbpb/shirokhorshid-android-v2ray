package app.napster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Napster .npvs v5 (Gen2) compact envelope parser, whitebox A16/KDK derivation,
 * and NPF field decryptor.
 * Pure Java 8, minSdk 14 compatible, zero android.* dependencies.
 */
public final class NapsterGen2 {

    public static final String MAGIC = "NPVS";
    public static final int VERSION = 5;

    private static final int HEADER_FIXED = 53;
    private static final int SENTINEL_SEQ = 0xFFFF;
    private static final int METHOD_RECIPIENT = 0;
    private static final int METHOD_PASS = 1;
    private static final int METHOD_APPKEY = 2;
    private static final int APPKEY_BLOCK_SIZE = 78;

    private static final String APPKEY_LABEL = "npvtunnel/appkey/v2 ";
    private static final String METADATA_INFO = "NPVS-v5/metadata";
    private static final String RECORD_AAD_INFO = "NPV-fields-v1/record/";
    private static final String FIELD_KEY_INFO = "NPV-fields-v1/field/";

    private static final String NPV_SENTINEL_PREFIX = "npvs1:";
    private static final String NPV_SENTINEL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=_-";

    private static final int BLOCK_SIZE = 57344;
    private static final int BLOCK_COUNT = 13;
    private static final int[] SHIFT_ROWS = {0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11};

    private NapsterGen2() {
    }

    public static boolean isGen2(byte[] data) {
        return data != null && data.length >= 9
                && data[0] == 'N' && data[1] == 'P' && data[2] == 'V' && data[3] == 'S'
                && (data[4] & 0xFF) == VERSION;
    }

    public static List<String> open(byte[] b, byte[] tables) throws Exception {
        if (!isGen2(b)) {
            throw new IllegalArgumentException("npvs gen2: not a v5 compact envelope");
        }
        if (tables == null || tables.length < NapsterTables.GEN2_UNCOMPRESSED_SIZE) {
            throw new IOException("npvs gen2: tables not loaded or wrong size");
        }

        int hdrLen = be32(b, 5);
        if (hdrLen < HEADER_FIXED || 9 + hdrLen > b.length) {
            throw new IllegalArgumentException("npvs gen2: bad header length " + hdrLen);
        }

        int hOff = 9;
        if (b[hOff] != 1) {
            throw new IllegalArgumentException("npvs gen2: compact header version " + (b[hOff] & 0xFF) + " is unsupported");
        }

        byte[] configID = Arrays.copyOfRange(b, hOff + 1, hOff + 17);
        byte[] creator = Arrays.copyOfRange(b, hOff + 17, hOff + 50);
        int method = b[hOff + 50] & 0xFF;

        int recipients = be16(b, hOff + 51);
        if (recipients > 0x400) {
            throw new IllegalArgumentException("npvs gen2: too many recipients");
        }

        if (method == METHOD_RECIPIENT) {
            throw new IllegalArgumentException("recipient key required");
        } else if (method == METHOD_PASS) {
            throw new IllegalArgumentException("passphrase required");
        } else if (method != METHOD_APPKEY) {
            throw new IllegalArgumentException("npvs gen2: unknown unlock method " + method);
        }

        int off = 53 + recipients * (32 + 0x5D);
        if (hOff + off + APPKEY_BLOCK_SIZE + 4 > hOff + hdrLen) {
            throw new IllegalArgumentException("npvs gen2: truncated app-key block");
        }
        if (be16(b, hOff + off) != METHOD_APPKEY) {
            throw new IllegalArgumentException("npvs gen2: missing app-key block");
        }

        byte[] salt = Arrays.copyOfRange(b, hOff + off + 2, hOff + off + 18);
        byte[] wrap = Arrays.copyOfRange(b, hOff + off + 18, hOff + off + APPKEY_BLOCK_SIZE);
        off += APPKEY_BLOCK_SIZE;

        if (hOff + off + 4 > hOff + hdrLen) {
            throw new IllegalArgumentException("npvs gen2: truncated sealed metadata length");
        }
        int metaLen = be32(b, hOff + off);
        if (metaLen < 16 || hOff + off + 4 + metaLen > hOff + hdrLen) {
            throw new IllegalArgumentException("npvs gen2: bad sealed metadata length");
        }

        byte[] prefix = Arrays.copyOfRange(b, hOff, hOff + off);
        byte[] metaBlob = Arrays.copyOfRange(b, hOff + off + 4, hOff + off + 4 + metaLen);

        int bodyHdrOff = 9 + hdrLen;
        if (bodyHdrOff + 16 > b.length) {
            throw new IllegalArgumentException("npvs gen2: truncated body header");
        }
        byte[] nonce = Arrays.copyOfRange(b, bodyHdrOff, bodyHdrOff + 12);
        int bodyLen = be32(b, bodyHdrOff + 12);
        int bodyStart = bodyHdrOff + 16;
        if (bodyLen < 32 || bodyStart + bodyLen + 64 > b.length) {
            throw new IllegalArgumentException("npvs gen2: bad body length");
        }
        byte[] body = Arrays.copyOfRange(b, bodyStart, bodyStart + bodyLen);

        byte[] a16 = computeA16(salt, tables);
        byte[] kdk = computeKDK(a16, configID);

        byte[] wrapNonce = Arrays.copyOfRange(wrap, 0, 12);
        byte[] wrapCt = Arrays.copyOfRange(wrap, 12, wrap.length);
        byte[] dek = ChaCha20Poly1305.open(kdk, wrapNonce, wrapCt, salt);
        if (dek == null) {
            throw new SecurityException("npvs gen2: app-key wrap did not open");
        }

        byte[] metaKey = HkdfSha256.deriveKey(dek, nonce, METADATA_INFO.getBytes("UTF-8"), 32);
        byte[] metadata = ChaCha20Poly1305.open(metaKey, nonce, metaBlob, prefix);

        if (body.length < 38 || body[0] != 'N' || body[1] != 'P' || body[2] != 'F' || body[3] != 1) {
            throw new IllegalArgumentException("npvs gen2: bad NPF body");
        }
        byte[] contentID = Arrays.copyOfRange(body, 4, 36);
        int fieldCount = be16(body, 36);
        int bOff = 38;

        Map<Integer, byte[]> fields = new HashMap<Integer, byte[]>();
        for (int i = 0; i < fieldCount; i++) {
            if (bOff + 6 > body.length) break;
            int seq = be16(body, bOff);
            int flags = be16(body, bOff + 2);
            int blobLen = be16(body, bOff + 4);
            bOff += 6;
            if (bOff + blobLen > body.length) break;
            byte[] blob = Arrays.copyOfRange(body, bOff, bOff + blobLen);
            bOff += blobLen;

            int ptLen = blobLen - 16;
            if (ptLen < 0) continue;

            byte[] seqBytes = be16Bytes(seq);
            byte[] fieldInfo = concat(FIELD_KEY_INFO.getBytes("UTF-8"), seqBytes);
            byte[] fieldKey = HkdfSha256.deriveKey(dek, contentID, fieldInfo, 32);

            byte[] aad = concat(RECORD_AAD_INFO.getBytes("UTF-8"), contentID, seqBytes, be32Bytes(ptLen));
            try {
                byte[] pt = ChaCha20Poly1305.open(fieldKey, new byte[12], blob, aad);
                if (pt != null) {
                    fields.put(seq, pt);
                }
            } catch (java.security.GeneralSecurityException ignored) {
                // A damaged record must not discard the remaining fields.
            }
        }

        return extractLinks(fields);
    }

    public static byte[] computeA16(byte[] salt, byte[] tables) throws IOException {
        if (salt == null || salt.length != 16) {
            throw new IllegalArgumentException("salt must be 16 bytes");
        }
        if (tables == null || tables.length < NapsterTables.GEN2_UNCOMPRESSED_SIZE) {
            throw new IOException("tables too small");
        }

        byte[] state = Arrays.copyOf(salt, 16);
        for (int b = 0; b < BLOCK_COUNT; b++) {
            int blockOffset = b * BLOCK_SIZE;
            shift(state);
            for (int j = 0; j < 4; j++) {
                group(state, tables, blockOffset, j);
            }
        }

        int pageOffset = BLOCK_COUNT * BLOCK_SIZE;
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int idx = state[SHIFT_ROWS[i]] & 0xFF;
            out[i] = tables[pageOffset + 0x100 * i + idx];
        }
        return out;
    }

    public static byte[] computeKDK(byte[] a16, byte[] configID) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(APPKEY_LABEL.getBytes("UTF-8"));
            md.update(a16);
            md.update(configID);
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void shift(byte[] s) {
        byte[] t = new byte[16];
        for (int i = 0; i < 16; i++) {
            t[i] = s[SHIFT_ROWS[i]];
        }
        System.arraycopy(t, 0, s, 0, 16);
    }

    private static void group(byte[] state, byte[] tables, int blockOffset, int j) {
        int[] args = groupArgs(tables, blockOffset, 0x6000, j, state, 4 * j);
        sub7DC0(tables, blockOffset, j, args[0], args[1], args[2], args[3], state, 4 * j);

        args = groupArgs(tables, blockOffset, 0xA000, j, state, 4 * j);
        sub7DC0(tables, blockOffset, j, args[0], args[1], args[2], args[3], state, 4 * j);
    }

    private static int[] groupArgs(byte[] tables, int blockOffset, int half, int j, byte[] state, int sOff) {
        int base = blockOffset + half + 0x1000 * j;
        int a0 = be32(tables, base + 4 * (state[sOff] & 0xFF));
        int a1 = be32(tables, base + 0x400 + 4 * (state[sOff + 1] & 0xFF));
        int a2 = be32(tables, base + 0x800 + 4 * (state[sOff + 2] & 0xFF));
        int a3 = be32(tables, base + 0xC00 + 4 * (state[sOff + 3] & 0xFF));
        return new int[]{a0, a1, a2, a3};
    }

    public static void sub7DC0(byte[] tables, int blockOffset, int i,
                               long x3, long x4, long x5, long x6,
                               byte[] dst, int dstOff) {
        x3 &= 0xFFFFFFFFL;
        x4 &= 0xFFFFFFFFL;
        x5 &= 0xFFFFFFFFL;
        x6 &= 0xFFFFFFFFL;

        long x8 = (i + ((i << 1) & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
        long x12 = (x3 >> 20) & 0xFFFFFFFFL;
        long x11 = (x3 >> 24) & 0xFFFFFFFFL;
        long x13 = (x4 >> 24) & 0xFL;
        long x14 = (x5 >> 24) & 0xFFFFFFFFL;
        long x10 = x6;
        long x9 = (x8 << 3) & 0xFFFFFFFFL;
        x8 = x4;
        x12 &= 0xF0L;
        x12 += x13;
        x13 = (x5 >> 20) & 0xFFFFFFFFL;
        long x16 = (x8 >> 28) & 0xFL;
        x9 &= 0xFFFFFFFFL;
        x11 &= 0xF0L;
        x14 &= 0xF0L;
        long x15 = (x6 >> 24) & 0xFL;
        x13 &= 0xF0L;
        long x17 = (x3 >> 16) & 0xFFFFFFFFL;
        x9 = (x9 << 8) & 0xFFFFFFFFL;
        x8 &= 0xFL;
        x13 += x15;
        x17 &= 0xF0L;
        x11 = (x9 + x11) & 0xFFFFFFFFL;
        x14 = (x9 + x14) & 0xFFFFFFFFL;
        x12 = (x9 + x12) & 0xFFFFFFFFL;
        x16 = at(tables, blockOffset, x11 + x16);
        x14 = (x14 + (x10 >> 28)) & 0xFFFFFFFFL;
        x12 = at(tables, blockOffset, x12 + 0x200);
        x13 = (x9 + x13) & 0xFFFFFFFFL;
        long x0 = (x6 >> 20) & 0xFL;
        x14 = at(tables, blockOffset, x14 + 0x100);
        x15 = (x9 + (x16 << 4)) & 0xFFFFFFFFL;
        x13 = at(tables, blockOffset, x13 + 0x300);
        x12 = (x9 + (x12 << 4)) & 0xFFFFFFFFL;
        x16 = (x4 >> 20) & 0xFL;
        x10 &= 0xFL;
        x14 = (x15 + x14) & 0xFFFFFFFFL;
        x15 = (x5 >> 16) & 0xFFFFFFFFL;
        x12 = (x12 + x13) & 0xFFFFFFFFL;
        x13 = (x3 >> 12) & 0xFFFFFFFFL;
        x14 = at(tables, blockOffset, x14 + 0x400);
        x12 = at(tables, blockOffset, x12 + 0x500);
        x15 &= 0xF0L;
        x16 += x17;
        x15 += x0;
        x0 = (x4 >> 16) & 0xFL;
        x17 = (x5 >> 12) & 0xFFFFFFFFL;
        x12 |= (x14 << 4);
        x13 &= 0xF0L;
        x14 = (x6 >> 16) & 0xFL;
        x13 += x0;
        x17 &= 0xF0L;
        x16 = (x9 + x16) & 0xFFFFFFFFL;
        dst[dstOff] = (byte) x12;

        x13 = (x9 + x13) & 0xFFFFFFFFL;
        x12 = x17 + x14;
        x14 = at(tables, blockOffset, x16 + 0x600);
        x13 = at(tables, blockOffset, x13 + 0x800);
        x15 = (x9 + x15) & 0xFFFFFFFFL;
        x12 = (x9 + x12) & 0xFFFFFFFFL;
        x15 = at(tables, blockOffset, x15 + 0x700);
        x16 = (x5 >> 8) & 0xFFFFFFFFL;
        x12 = at(tables, blockOffset, x12 + 0x900);
        x14 = (x9 + (x14 << 4)) & 0xFFFFFFFFL;
        x13 = (x9 + (x13 << 4)) & 0xFFFFFFFFL;
        x0 = (x6 >> 12) & 0xFL;
        x17 = (x3 >> 8) & 0xFFFFFFFFL;
        x14 = (x14 + x15) & 0xFFFFFFFFL;
        x12 = (x13 + x12) & 0xFFFFFFFFL;
        x13 = (x16 & 0xF0L);
        x14 = at(tables, blockOffset, x14 + 0xA00);
        x12 = at(tables, blockOffset, x12 + 0xB00);
        x15 = (x3 >> 4) & 0xFFFFFFFFL;
        x13 += x0;
        x0 = (x4 >> 8) & 0xFL;
        x16 = (x4 >> 12) & 0xFL;
        x12 |= (x14 << 4);
        x14 = (x15 & 0xF0L);
        x17 &= 0xF0L;
        x14 += x0;
        x15 = (x9 + 0xC00) & 0xFFFFFFFFL;
        x0 = (x5 >> 4) & 0xFFFFFFFFL;
        x17 = (x15 + x17) & 0xFFFFFFFFL;
        dst[dstOff + 1] = (byte) x12;

        x12 = (x6 >> 8) & 0xFL;
        x16 = at(tables, blockOffset, x17 + x16);
        x14 = (x9 + x14) & 0xFFFFFFFFL;
        x17 = (x0 & 0xF0L);
        x14 = at(tables, blockOffset, x14 + 0xE00);
        x12 += x17;
        x13 = (x15 + x13) & 0xFFFFFFFFL;
        x12 = (x9 + x12) & 0xFFFFFFFFL;
        x13 = at(tables, blockOffset, x13 + 0x100);
        x15 = (x9 + (x16 << 4)) & 0xFFFFFFFFL;
        x12 = at(tables, blockOffset, x12 + 0xF00);
        x14 = (x9 + (x14 << 4)) & 0xFFFFFFFFL;
        x16 = 0x1000;
        x13 = (x15 + x13) & 0xFFFFFFFFL;
        x17 = (x6 >> 4) & 0xFL;
        x0 = x5 & 0xF0L;
        x12 = (x14 + x12) & 0xFFFFFFFFL;
        x14 = 0x1100;
        x13 = at(tables, blockOffset, x13 + x16);
        x12 = at(tables, blockOffset, x12 + x14);
        x14 = 0x1200;
        x15 = (x4 >> 4) & 0xFL;
        x16 = x3 & 0xF0L;
        x17 += x0;
        x14 = (x9 + x14) & 0xFFFFFFFFL;
        x12 |= (x13 << 4);
        x13 = (x3 & 0xFL) << 4;
        x16 = (x14 + x16) & 0xFFFFFFFFL;
        dst[dstOff + 2] = (byte) x12;

        x8 += x13;
        x12 = (x14 + x17) & 0xFFFFFFFFL;
        x14 = (x5 & 0xFL) << 4;
        x13 = at(tables, blockOffset, x16 + x15);
        x8 = (x9 + x8) & 0xFFFFFFFFL;
        x15 = 0x1400;
        x12 = at(tables, blockOffset, x12 + 0x100);
        x8 = at(tables, blockOffset, x8 + x15);
        x10 += x14;
        x14 = 0x1500;
        x10 = (x9 + x10) & 0xFFFFFFFFL;
        x13 = (x9 + (x13 << 4)) & 0xFFFFFFFFL;
        x10 = at(tables, blockOffset, x10 + x14);
        x8 = (x9 + (x8 << 4)) & 0xFFFFFFFFL;
        x9 = (x13 + x12) & 0xFFFFFFFFL;
        x12 = 0x1600;
        x8 = (x8 + x10) & 0xFFFFFFFFL;
        x10 = 0x1700;
        x9 = at(tables, blockOffset, x9 + x12);
        x8 = at(tables, blockOffset, x8 + x10);
        x8 |= (x9 << 4);
        dst[dstOff + 3] = (byte) x8;
    }

    private static long at(byte[] tables, int blockOffset, long addr) {
        return tables[blockOffset + (int) addr] & 0xFFL;
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static int be16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static byte[] be16Bytes(int v) {
        return new byte[]{(byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)};
    }

    private static byte[] be32Bytes(int v) {
        return new byte[]{
                (byte) ((v >> 24) & 0xFF),
                (byte) ((v >> 16) & 0xFF),
                (byte) ((v >> 8) & 0xFF),
                (byte) (v & 0xFF)
        };
    }

    private static byte[] concat(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) len += a.length;
        byte[] res = new byte[len];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, res, pos, a.length);
            pos += a.length;
        }
        return res;
    }

    private static List<String> extractLinks(Map<Integer, byte[]> fields) throws Exception {
        byte[] sentinelBytes = fields.get(SENTINEL_SEQ);
        if (sentinelBytes == null) {
            return Collections.emptyList();
        }
        JSONObject root = new JSONObject(new String(sentinelBytes, "UTF-8"));
        JSONArray configs = root.optJSONArray("configs");
        if (configs == null) {
            return Collections.emptyList();
        }
        List<String> links = new ArrayList<String>();
        for (int i = 0; i < configs.length(); i++) {
            JSONObject cfg = configs.optJSONObject(i);
            if (cfg == null) continue;
            Object resolved = substitute(cfg, fields);
            if (!(resolved instanceof JSONObject)) continue;
            JSONObject obj = (JSONObject) resolved;
            JSONObject profile = obj.optJSONObject("v2rayProfile");
            if (profile == null) continue;
            String embedded = text(profile.opt("v2rayJson")).trim();
            if (embedded.startsWith("{") && embedded.contains("\"outbounds\"")) {
                links.add(embedded);
                continue;
            }
            String link = v2rayLink(text(obj.opt("name")), text(obj.opt("address")), flatMap(profile));
            if (link != null && !link.isEmpty()) links.add(link);
        }
        return links;
    }

    private static Object substitute(Object value, Map<Integer, byte[]> fields) {
        if (value instanceof JSONObject) {
            JSONObject in = (JSONObject) value;
            JSONObject out = new JSONObject();
            Iterator<String> keys = in.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    out.put(key, substitute(in.get(key), fields));
                } catch (Exception ignored) {
                }
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray in = (JSONArray) value;
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                try {
                    out.put(substitute(in.get(i), fields));
                } catch (Exception ignored) {
                }
            }
            return out;
        }
        if (value instanceof Number) {
            byte[] raw = fields.get(((Number) value).intValue());
            return decodeSentinels(fieldText(raw));
        }
        return value;
    }

    private static String fieldText(byte[] raw) {
        if (raw == null) return "";
        String s = new String(raw, java.nio.charset.Charset.forName("UTF-8")).trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            try {
                Object parsed = new JSONTokener(s).nextValue();
                if (parsed instanceof String) return (String) parsed;
            } catch (Exception ignored) {
            }
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String decodeSentinels(String s) {
        if (s == null || s.indexOf(NPV_SENTINEL_PREFIX) < 0) return s == null ? "" : s;
        StringBuilder out = new StringBuilder();
        while (true) {
            int idx = s.indexOf(NPV_SENTINEL_PREFIX);
            if (idx < 0) {
                out.append(s);
                break;
            }
            out.append(s, 0, idx);
            s = s.substring(idx + NPV_SENTINEL_PREFIX.length());
            int j = 0;
            while (j < s.length() && NPV_SENTINEL_ALPHABET.indexOf(s.charAt(j)) >= 0) j++;
            String token = s.substring(0, j);
            s = s.substring(j);
            byte[] decoded = token.isEmpty() ? null : NapsterLegacy.decodeBase64(token);
            if (decoded == null) out.append(NPV_SENTINEL_PREFIX).append(token);
            else out.append(new String(decoded, java.nio.charset.Charset.forName("UTF-8")));
        }
        return out.toString();
    }

    private static Map<String, String> flatMap(JSONObject obj) {
        Map<String, String> out = new HashMap<String, String>();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            out.put(key, text(obj.opt(key)));
        }
        return out;
    }

    private static String text(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        if (value instanceof Boolean) return String.valueOf(value);
        return value.toString();
    }

    private static String v2rayLink(String remarks, String address, Map<String, String> p) {
        String host = or(p.get("server"), address);
        int port = intOf(p.get("serverPort"));
        if (port == 0) port = intOf(p.get("port"));
        if (host.isEmpty() || port <= 0) return "";

        switch (intOf(p.get("configType"))) {
            case 1:
                return vmessLink(host, port, remarks, p);
            case 3:
                return shadowsocksLink(host, port, remarks, p);
            case 5:
                return vlessLink(host, port, remarks, p);
            case 6:
                return trojanLink(host, port, remarks, p);
            default:
                return "";
        }
    }

    public static String buildShareLink(String remarks, String address, org.json.JSONObject profile) {
        return v2rayLink(remarks, address, flatMap(profile));
    }

    private static String vlessLink(String host, int port, String remarks, Map<String, String> p) {
        TreeMap<String, String> q = streamQuery(p);
        q.put("encryption", or(p.get("method"), "none"));
        set(q, "flow", p.get("flow"));
        return "vless://" + str(p.get("password")) + "@" + host + ":" + port
                + "?" + encodeQuery(q) + "#" + percentEncode(remarks);
    }

    private static String trojanLink(String host, int port, String remarks, Map<String, String> p) {
        TreeMap<String, String> q = streamQuery(p);
        set(q, "flow", p.get("flow"));
        return "trojan://" + percentEncode(str(p.get("password"))) + "@" + host + ":" + port
                + "?" + encodeQuery(q) + "#" + percentEncode(remarks);
    }

    private static String shadowsocksLink(String host, int port, String remarks, Map<String, String> p) {
        String userInfo = base64Encode((str(p.get("method")) + ":" + str(p.get("password"))).getBytes());
        return "ss://" + userInfo + "@" + host + ":" + port + "#" + percentEncode(remarks);
    }

    private static String vmessLink(String host, int port, String remarks, Map<String, String> p) {
        String network = network(p);
        String headerType = or(p.get("headerType"), "none");
        String hostHeader = p.get("host");
        String path = p.get("path");

        if ("kcp".equals(network)) {
            path = or(path, p.get("seed"));
        } else if ("grpc".equals(network)) {
            headerType = p.get("mode");
            path = or(path, p.get("serviceName"));
            hostHeader = or(hostHeader, p.get("authority"));
        }

        try {
            JSONObject obj = new JSONObject();
            obj.put("v", "2");
            obj.put("ps", remarks);
            obj.put("add", host);
            obj.put("port", String.valueOf(port));
            obj.put("id", str(p.get("password")));
            obj.put("aid", String.valueOf(intOf(p.get("alterId"))));
            obj.put("scy", or(p.get("method"), "auto"));
            obj.put("net", or(p.get("network"), "tcp"));
            obj.put("type", or(headerType, "none"));
            obj.put("host", str(hostHeader));
            obj.put("path", str(path));
            obj.put("tls", vmessTls(p));
            obj.put("sni", str(p.get("sni")));
            obj.put("fp", str(p.get("fingerPrint")));
            obj.put("alpn", str(p.get("alpn")));
            obj.put("insecure", vmessInsecure(p));
            return "vmess://" + base64Encode(obj.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            return "";
        }
    }

    private static String network(Map<String, String> p) {
        String network = str(p.get("network")).toLowerCase().trim();
        if ("tcp".equals(network) || "kcp".equals(network) || "ws".equals(network)
                || "httpupgrade".equals(network) || "xhttp".equals(network)
                || "http".equals(network) || "h2".equals(network) || "grpc".equals(network)) {
            return network;
        }
        return "tcp";
    }

    private static String vmessTls(Map<String, String> p) {
        String security = p.get("security");
        return "tls".equals(security) || "reality".equals(security) ? "tls" : "";
    }

    private static String vmessInsecure(Map<String, String> p) {
        if (!"tls".equals(p.get("security"))) return "";
        return allowInsecure(p) ? "1" : "0";
    }

    private static TreeMap<String, String> streamQuery(Map<String, String> p) {
        String network = or(p.get("network"), "tcp");
        String security = or(p.get("security"), "none");
        TreeMap<String, String> q = new TreeMap<String, String>();
        q.put("type", network);
        q.put("security", security);

        if ("ws".equals(network)) {
            set(q, "path", p.get("path"));
            set(q, "host", p.get("host"));
        } else if ("grpc".equals(network)) {
            set(q, "serviceName", p.get("serviceName"));
            set(q, "mode", or(p.get("mode"), p.get("xhttpMode"), "gun"));
            set(q, "authority", p.get("authority"));
        } else if ("kcp".equals(network)) {
            set(q, "headerType", p.get("headerType"));
            set(q, "seed", p.get("seed"));
        } else if ("quic".equals(network)) {
            set(q, "key", p.get("key"));
            set(q, "headerType", p.get("headerType"));
        } else if ("xhttp".equals(network) || "httpupgrade".equals(network)) {
            set(q, "path", p.get("path"));
            set(q, "host", p.get("host"));
            set(q, "mode", or(p.get("xhttpMode"), p.get("mode")));
            set(q, "extra", p.get("xhttpExtra"));
        } else if ("tcp".equals(network) || "raw".equals(network)) {
            String headerType = p.get("headerType");
            if (headerType != null && !headerType.isEmpty() && !"none".equals(headerType)) {
                q.put("headerType", headerType);
                set(q, "host", p.get("host"));
                set(q, "path", p.get("path"));
            }
        }

        if ("tls".equals(security)) {
            set(q, "sni", p.get("sni"));
            set(q, "fp", p.get("fingerPrint"));
            set(q, "alpn", p.get("alpn"));
            if (allowInsecure(p)) q.put("allowInsecure", "1");
        } else if ("reality".equals(security)) {
            set(q, "sni", p.get("sni"));
            set(q, "fp", p.get("fingerPrint"));
            set(q, "pbk", p.get("publicKey"));
            set(q, "sid", p.get("shortId"));
            set(q, "spx", p.get("spiderX"));
        }
        return q;
    }

    private static boolean allowInsecure(Map<String, String> p) {
        for (String key : new String[]{"tlsAllowInsecure", "insecure"}) {
            String value = str(p.get(key)).toLowerCase().trim();
            if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) return true;
            if ("false".equals(value) || "0".equals(value) || "no".equals(value)) return false;
        }
        return false;
    }

    private static void set(Map<String, String> q, String key, String value) {
        if (value != null && !value.isEmpty()) q.put(key, value);
    }

    private static String or(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intOf(String value) {
        try {
            return Integer.parseInt(str(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String encodeQuery(Map<String, String> q) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : q.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(percentEncode(entry.getKey())).append('=')
                    .append(percentEncode(entry.getValue()));
        }
        return out.toString();
    }

    private static String percentEncode(String value) {
        if (value == null) return "";
        byte[] bytes;
        try {
            bytes = value.getBytes("UTF-8");
        } catch (Exception e) {
            return "";
        }
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static String base64Encode(byte[] data) {
        final char[] alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            out.append(alphabet[(n >> 18) & 63]);
            out.append(alphabet[(n >> 12) & 63]);
            out.append(alphabet[(n >> 6) & 63]);
            out.append(alphabet[n & 63]);
            i += 3;
        }
        int remaining = data.length - i;
        if (remaining == 1) {
            int n = (data[i] & 0xFF) << 16;
            out.append(alphabet[(n >> 18) & 63]);
            out.append(alphabet[(n >> 12) & 63]);
            out.append("==");
        } else if (remaining == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            out.append(alphabet[(n >> 18) & 63]);
            out.append(alphabet[(n >> 12) & 63]);
            out.append(alphabet[(n >> 6) & 63]);
            out.append('=');
        }
        return out.toString();
    }
}
