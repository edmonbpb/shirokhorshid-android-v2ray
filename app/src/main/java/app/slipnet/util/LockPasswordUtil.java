// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.
package app.slipnet.util;

import java.nio.charset.Charset;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * JNI mirror of SlipNet's LockPasswordUtil.
 * Encrypted config payload: [version 0x01][IV 12 bytes][ciphertext + 16-byte tag].
 */
public final class LockPasswordUtil {

    private static final LockPasswordUtil INSTANCE = new LockPasswordUtil();

    private static final int VERSION = 0x01;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int KEY_LENGTH = 32;

    static {
        System.loadLibrary("slipstream");
    }

    private LockPasswordUtil() {
    }

    private native byte[] nativeGetConfigKey();

    public static byte[] getConfigKey() {
        return INSTANCE.nativeGetConfigKey();
    }

    public static String decryptConfig(byte[] data) throws Exception {
        if (data == null || data.length < 1 + IV_LENGTH + TAG_LENGTH) {
            throw new IllegalArgumentException("encrypted payload too short");
        }
        if ((data[0] & 0xFF) != VERSION) {
            throw new IllegalArgumentException("unsupported payload version");
        }
        byte[] key = INSTANCE.nativeGetConfigKey();
        if (key == null || key.length != KEY_LENGTH) {
            throw new IllegalStateException("invalid config key");
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(data, 1, iv, 0, IV_LENGTH);
        int cipherLength = data.length - 1 - IV_LENGTH;
        byte[] cipherText = new byte[cipherLength];
        System.arraycopy(data, 1 + IV_LENGTH, cipherText, 0, cipherLength);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] plain = cipher.doFinal(cipherText);
        return new String(plain, Charset.forName("UTF-8"));
    }
}
