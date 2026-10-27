// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.
package app.slipnet.scanner;

import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Prism challenge-response verification and RFC 5737 transparent-proxy detection. */
public final class PrismVerifier {

    private static final String HMAC = "HmacSHA256";
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final char[] SUBDOMAIN = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final String[] TEST_NET = {"192.0.2.1", "198.51.100.1", "203.0.113.1"};
    private static final SecureRandom RANDOM = new SecureRandom();

    private PrismVerifier() {
    }

    /** Returns the number of passed probes; stops at the threshold or when it becomes unreachable. */
    public static int verify(String host, int port, String testDomain, byte[] pubkey,
                             int timeoutMs, int probeCount, int passThreshold, int responseSize,
                             DnsProbe.Transport transport) {
        if (host == null || testDomain == null || testDomain.length() == 0
                || pubkey == null || pubkey.length == 0 || probeCount <= 0) {
            return 0;
        }
        DnsProbe.Transport t = transport == null ? DnsProbe.Transport.UDP : transport;
        int threshold = Math.max(1, Math.min(passThreshold, probeCount));
        int maxFailures = probeCount - threshold;
        int perProbe = (int) Math.max(200L, Math.min(30000L, (long) timeoutMs / threshold));
        int passed = 0;
        int failures = 0;
        for (int i = 0; i < probeCount; i++) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (verifyOnce(host, port, testDomain, pubkey, perProbe, responseSize, t)) {
                passed++;
                if (passed >= threshold) {
                    break;
                }
            } else {
                failures++;
                if (failures > maxFailures) {
                    break;
                }
            }
        }
        return passed;
    }

    /** One A query for a random label under the parent zone; any response means alive. */
    public static boolean alive(String host, int port, String testDomain, int timeoutMs,
                                DnsProbe.Transport transport) {
        if (host == null || testDomain == null || testDomain.length() == 0) {
            return false;
        }
        try {
            String name = randomSubdomain() + "." + parentDomain(testDomain);
            DnsProbe.DnsResponse response = DnsProbe.query(host, port,
                    DnsProbe.buildQuery(name, DnsProbe.TYPE_A, 0), timeout(timeoutMs),
                    transport == null ? DnsProbe.Transport.UDP : transport, 512);
            return response != null && response.received;
        } catch (Exception e) {
            return false;
        }
    }

    /** Any answer from a TEST-NET documentation address on port 53 means DNS is intercepted. */
    public static boolean detectTransparentProxy(String testDomain, int timeoutMs) {
        if (testDomain == null || testDomain.length() == 0) {
            return false;
        }
        for (String ip : TEST_NET) {
            try {
                byte[] query = DnsProbe.buildQuery(testDomain, DnsProbe.TYPE_A, 0);
                DnsProbe.DnsResponse response = DnsProbe.query(ip, 53, query, timeout(timeoutMs),
                        DnsProbe.Transport.UDP, 512);
                if (response != null && response.received) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static int timeout(int timeoutMs) {
        return timeoutMs > 0 ? timeoutMs : 3000;
    }

    private static boolean verifyOnce(String host, int port, String testDomain, byte[] pubkey,
                                      int timeoutMs, int responseSize, DnsProbe.Transport transport) {
        try {
            byte[] nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            // nonce[14:16] carries the wanted response size, 0 when out of range.
            if (responseSize >= 200 && responseSize <= 4096) {
                nonce[14] = (byte) (responseSize >>> 8);
                nonce[15] = (byte) responseSize;
            } else {
                nonce[14] = 0;
                nonce[15] = 0;
            }

            byte[] proof = hmac(pubkey, nonce);
            byte[] payload = new byte[32];
            System.arraycopy(nonce, 0, payload, 0, 16);
            System.arraycopy(proof, 0, payload, 16, 16);
            String encoded = base32(payload);

            int ednsSize = Math.max(responseSize, 1232);
            byte[] query = DnsProbe.buildQuery(encoded + "." + testDomain, DnsProbe.TYPE_TXT, ednsSize);
            DnsProbe.DnsResponse response = DnsProbe.query(host, port, query, timeoutMs, transport, 4096);
            if (response == null || !response.received || response.raw == null || response.length < 12) {
                return false;
            }
            if (response.rcode != 0) {
                return false;
            }
            byte[] raw = DnsProbe.firstTxtRaw(response.raw, response.length);
            if (raw == null || raw.length < 32) {
                return false;
            }

            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(pubkey, HMAC));
            mac.update(nonce);
            mac.update((byte) 0x01);
            byte[] expected = mac.doFinal();

            int diff = 0;
            for (int i = 0; i < 32; i++) {
                diff |= raw[i] ^ expected[i];
            }
            return diff == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(key, HMAC));
        return mac.doFinal(data);
    }

    private static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(BASE32[(buffer >>> bitsLeft) & 0x1F]);
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return sb.toString();
    }

    private static String parentDomain(String domain) {
        int first = domain.indexOf('.');
        if (first >= 0 && domain.indexOf('.', first + 1) >= 0) {
            return domain.substring(first + 1);
        }
        return domain;
    }

    private static String randomSubdomain() {
        char[] out = new char[8];
        for (int i = 0; i < out.length; i++) {
            out[i] = SUBDOMAIN[RANDOM.nextInt(SUBDOMAIN.length)];
        }
        return new String(out);
    }
}
