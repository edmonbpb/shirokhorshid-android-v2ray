package app.napster;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure Java 8 HKDF-SHA256 implementation per RFC 5869.
 * API 14+ safe, uses standard javax.crypto.Mac ("HmacSHA256").
 */
public final class HkdfSha256 {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private HkdfSha256() {
    }

    public static byte[] extract(byte[] salt, byte[] ikm) {
        try {
            if (salt == null || salt.length == 0) {
                salt = new byte[HASH_LEN];
            }
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(salt, HMAC_SHA256));
            return mac.doFinal(ikm);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static byte[] expand(byte[] prk, byte[] info, int outLen) {
        if (outLen < 0 || outLen > 255 * HASH_LEN) {
            throw new IllegalArgumentException("Invalid outLen: " + outLen);
        }
        if (info == null) {
            info = new byte[0];
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(prk, HMAC_SHA256));

            byte[] okm = new byte[outLen];
            byte[] t = new byte[0];
            int generated = 0;
            byte counter = 1;

            while (generated < outLen) {
                mac.reset();
                if (t.length > 0) {
                    mac.update(t);
                }
                mac.update(info);
                mac.update(counter);
                t = mac.doFinal();

                int toCopy = Math.min(t.length, outLen - generated);
                System.arraycopy(t, 0, okm, generated, toCopy);
                generated += toCopy;
                counter++;
            }
            return okm;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static byte[] deriveKey(byte[] ikm, byte[] salt, byte[] info, int outLen) {
        byte[] prk = extract(salt, ikm);
        return expand(prk, info, outLen);
    }
}
