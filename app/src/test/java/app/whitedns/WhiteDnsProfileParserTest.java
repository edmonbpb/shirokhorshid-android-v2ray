package app.whitedns;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.Charset;
import java.util.List;

import app.whitedns.model.WhiteDnsProfile;
import app.whitedns.util.WhiteDnsException;
import app.whitedns.util.WhiteDnsProfileParser;

/** JVM tests for the pure Java WhiteDNS profile parser and the SOAR JSON rendering. */
public class WhiteDnsProfileParserTest {

    private static final String KEY = "0123456789abcdef";
    /** Base64 of 32 bytes, the shape the SOAR engine expects for its server public key. */
    private static final String SOAR_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String BASE64URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final String TOML =
            "DOMAINS = [\"example.com\", \"alt.example.com\"]\n"
                    + "DATA_ENCRYPTION_METHOD = 3\n"
                    + "ENCRYPTION_KEY = \"" + KEY + "\"\n"
                    + "TUNNEL_MODE = \"cottenmax\"\n"
                    + "LISTEN_IP = \"127.0.0.1\"\n"
                    + "LISTEN_PORT = 5555\n";

    @Test
    public void parsesToml() throws Exception {
        WhiteDnsProfile p = WhiteDnsProfileParser.parse(TOML);
        Assert.assertEquals("example.com,alt.example.com", p.domain);
        Assert.assertEquals(KEY, p.key);
        Assert.assertEquals(WhiteDnsProfile.Mode.COTTENMAX, p.mode);
        Assert.assertEquals("", p.name);
    }

    @Test
    public void parsesJson() throws Exception {
        String json = "{\"domain\": \"example.com\", \"encryption_key\": \"" + KEY + "\","
                + " \"tunnel_mode\": \"normal\","
                + " \"resolvers\": [\"1.1.1.1\", \"8.8.8.8:53\"],"
                + " \"name\": \"Home\"}";
        WhiteDnsProfile p = WhiteDnsProfileParser.parse(json);
        Assert.assertEquals("example.com", p.domain);
        Assert.assertEquals(KEY, p.key);
        Assert.assertEquals(WhiteDnsProfile.Mode.NORMAL, p.mode);
        Assert.assertEquals("Home", p.name);
        Assert.assertEquals("1.1.1.1,8.8.8.8:53", p.resolvers);
        Assert.assertEquals(2, WhiteDnsProfileParser.parseResolvers(p.resolvers).size());
    }

    @Test
    public void parsesWhitednsLink() throws Exception {
        WhiteDnsProfile p = WhiteDnsProfileParser.parse("whitedns://" + base64Url(TOML));
        Assert.assertEquals("example.com,alt.example.com", p.domain);
        Assert.assertEquals(KEY, p.key);
        Assert.assertEquals(WhiteDnsProfile.Mode.COTTENMAX, p.mode);
    }

    @Test
    public void rejectsMissingKey() {
        String toml = "DOMAINS = [\"example.com\"]\nDATA_ENCRYPTION_METHOD = 3\n";
        try {
            WhiteDnsProfileParser.parse(toml);
            Assert.fail("expected failure");
        } catch (WhiteDnsException e) {
            Assert.assertEquals("missing encryption key", e.getMessage());
        }
    }

    @Test
    public void rejectsMissingDomain() {
        String toml = "ENCRYPTION_KEY = \"" + KEY + "\"\nTUNNEL_MODE = \"legacy\"\n";
        try {
            WhiteDnsProfileParser.parse(toml);
            Assert.fail("expected failure");
        } catch (WhiteDnsException e) {
            Assert.assertEquals("missing domain", e.getMessage());
        }
    }

    @Test
    public void rejectsShortKey() {
        String toml = "DOMAINS = [\"example.com\"]\nENCRYPTION_KEY = \"0123456789abcde\"\n";
        try {
            WhiteDnsProfileParser.parse(toml);
            Assert.fail("expected failure");
        } catch (WhiteDnsException e) {
            Assert.assertEquals("encryption key too short", e.getMessage());
        }
    }

    @Test
    public void parsesResolversWithDedupeAndValidation() {
        String raw = "1.1.1.1, 1.1.1.1\n8.8.8.8:5353 [2001:db8::1]:53 2001:db8::1"
                + " bad 999.1.1.1 1.1.1.1:0 10.0.0.1:70000";
        List<String> resolvers = WhiteDnsProfileParser.parseResolvers(raw);
        Assert.assertEquals(4, resolvers.size());
        Assert.assertEquals("1.1.1.1", resolvers.get(0));
        Assert.assertEquals("8.8.8.8:5353", resolvers.get(1));
        Assert.assertEquals("[2001:db8::1]:53", resolvers.get(2));
        Assert.assertEquals("2001:db8::1", resolvers.get(3));
    }

    @Test
    public void capsResolversAtTwoHundred() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            if (i > 0) {
                raw.append(',');
            }
            raw.append("10.0.").append(i / 100).append('.').append(i % 100 + 1);
        }
        List<String> resolvers = WhiteDnsProfileParser.parseResolvers(raw.toString());
        Assert.assertEquals(200, resolvers.size());
        Assert.assertEquals("10.0.0.1", resolvers.get(0));
        Assert.assertEquals("10.0.1.100", resolvers.get(199));
    }

    @Test
    public void escapesTomlStrings() throws Exception {
        String toml = "DOMAINS = [\"a\\\"b.com\", \"c\\\\d.com\"]\n"
                + "DATA_ENCRYPTION_METHOD = 3\n"
                + "ENCRYPTION_KEY = \"ab\\\"cd\\\\ef0123456789\"\n"
                + "TUNNEL_MODE = \"legacy\"\n";
        WhiteDnsProfile p = WhiteDnsProfileParser.parse(toml);
        Assert.assertEquals("ab\"cd\\ef0123456789", p.key);
        Assert.assertEquals("a\"b.com,c\\d.com", p.domain);

        String rendered = p.toToml(7000);
        Assert.assertTrue(rendered.contains("DOMAINS = [\"a\\\"b.com\", \"c\\\\d.com\"]"));
        Assert.assertTrue(rendered.contains("ENCRYPTION_KEY = \"ab\\\"cd\\\\ef0123456789\""));
        Assert.assertTrue(rendered.contains("TUNNEL_MODE = \"legacy\""));
    }

    private static final String COTTEN_LINK_BODY =
            "eyJwcm9maWxlIjp7ImVuZ2luZSI6ImNvdHRlbmRucyIsImlkIjoiZGM4MGYyMzNiZjQyNDg0MzdjZDMyZWU0"
                    + "N2NlOWRkYTg6Y290dGVuIiwibmFtZSI6IndoaXRlZG5zIHB1YmxpYyAyIiwicmVzb2x2ZXJzIjpbIjEuMS4x"
                    + "LjEiLCI4LjguOC44Il0sInNlcnZlciI6eyJkb21haW4iOiJjb3QuZXhhbXBsZS53aGl0ZWRucy5kb3dubG9h"
                    + "ZCIsImVuY3J5cHRpb25fa2V5IjoiODU5YzY4MWYzZGU2NDE4ZmVmY2ZlNmY4ZTY0OWYzN2U4ZDkyMGU2MzEx"
                    + "NzEyOWE3MjEwNmM3NjRhNDRkOTFmYyIsImVuY3J5cHRpb25fbWV0aG9kIjo1fX0sInNjaGVtYSI6IndoaXRl"
                    + "ZG5zLnByb2ZpbGUiLCJ2ZXJzaW9uIjoxfQ";

    private static final String SOAR_JSON =
            "{\"zone\":\"soar.example.whitedns.download\","
                    + "\"server_public_key\":\"" + SOAR_KEY + "\","
                    + "\"system_resolvers\":[\"1.1.1.1\",\"8.8.8.8\"],"
                    + "\"listen\":\"127.0.0.1:1080\","
                    + "\"username\":\"\",\"password\":\"\","
                    + "\"soar\":{\"transport\":\"auto\",\"discovery\":true,"
                    + "\"maxInflight\":128,\"country\":\"IR\"}}";

    @Test
    public void parsesCottenDnsProfileLink() throws Exception {
        WhiteDnsProfile profile =
                WhiteDnsProfileParser.parse("cottendns://" + COTTEN_LINK_BODY);
        Assert.assertEquals("cot.example.whitedns.download", profile.domain);
        Assert.assertEquals(5, profile.method);
        Assert.assertEquals(WhiteDnsProfile.Mode.NORMAL, profile.mode);
        Assert.assertEquals(WhiteDnsProfile.Engine.COTTEN, profile.engine);
        Assert.assertNull(profile.rawConfig);
        Assert.assertEquals(2, profile.resolverList().size());
        Assert.assertEquals("1.1.1.1", profile.resolverList().get(0));
    }

    private static final String SOAR_PROFILE_BODY =
            "eyJwcm9maWxlIjp7ImlkIjoiZGM4MGYyMzNiZjQyNDg0MzdjZDMyZWU0N2NlOWRkYTgiLCJuYW1lIjoid2hp"
                    + "dGVkbnMgcHVibGljIDIiLCJyZXNvbHZlcnMiOlsiMS4xLjEuMSIsIjguOC44LjgiXSwic2VydmVyIjp7"
                    + "ImRvbWFpbiI6IjNzYXBlbTU3aWplZWc3d3RmM3NoejIuc29hci53aGl0ZWRucy5kb3dubG9hZCIsInB1"
                    + "YmxpY19rZXkiOiJ1VVdrOU4yc3lXVG1sUUFPOHlsa3ZpaWkxSVFHRjlJa0JabFFSYkZsNjNNPSJ9LCJz"
                    + "b2FyU2V0dGluZ3MiOnsidHJhbnNwb3J0IjoiYXV0byIsImRpc2NvdmVyeSI6dHJ1ZSwiZGlzY292ZXJ5"
                    + "V2FudCI6MiwiZGlzY292ZXJ5Q29uY3VycmVuY3kiOjIsImRpc2NvdmVyeVBhY2VNcyI6MTAwMCwic2Fm"
                    + "ZVFOYW1lIjoxMDEsIm1heFFOYW1lIjoyNTMsIm1heEFuc3dlciI6MTIzMiwiaG9sZE1zIjo1MDAsIm1h"
                    + "eEluZmxpZ2h0IjoxMjgsIm1heFBhdGhXaW5kb3ciOjMyLCJtYXhTdHJlYW1zIjoxMjgsInN0cmVhbVdp"
                    + "bmRvd0tpQiI6MjU2LCJzZW5kQnVmZmVyS2lCIjo2NCwic2Vzc2lvbklkbGVTZWNvbmRzIjo5MCwiZGlh"
                    + "bFRpbWVvdXRTZWNvbmRzIjo5MCwiY291bnRyeSI6IklSIn19LCJzY2hlbWEiOiJ3aGl0ZWRucy5wcm9m"
                    + "aWxlIiwidmVyc2lvbiI6MX0";

    private static final String STORM_LINK_BODY =
            "eyJzY2hlbWEiOiJ3aGl0ZWRucy5wcm9maWxlIiwidmVyc2lvbiI6MSwicHJvZmlsZSI6eyJuYW1lIjoi"
                    + "Q29ubmVjdGlvbiIsInNlcnZlciI6eyJkb21haW4iOiJhLm93ZWwubXkiLCJlbmNyeXB0aW9uX2tleSI6"
                    + "ImFhYWFhYWEtQHJhaGdvemFyOTQ3MjVfaXAtYWFhYWFhIiwiZW5jcnlwdGlvbl9tZXRob2QiOjF9fX0";

    @Test
    public void parsesStormDnsLink() throws Exception {
        WhiteDnsProfile profile =
                WhiteDnsProfileParser.parse("stormdns://" + STORM_LINK_BODY);
        Assert.assertEquals(WhiteDnsProfile.Engine.STORM, profile.engine);
        Assert.assertEquals("a.owel.my", profile.domain);
        Assert.assertEquals(1, profile.method);
        Assert.assertEquals("Connection", profile.name);
    }

    @Test
    public void parsesNestedSoarProfileLink() throws Exception {
        WhiteDnsProfile profile =
                WhiteDnsProfileParser.parse("soar://" + SOAR_PROFILE_BODY);
        Assert.assertEquals(WhiteDnsProfile.Engine.SOAR, profile.engine);
        Assert.assertEquals("3sapem57ijeeg7wtf3shz2.soar.whitedns.download", profile.domain);
        Assert.assertEquals(44, profile.key.length());
        Assert.assertEquals(2, profile.resolverList().size());
        Assert.assertTrue(profile.rawConfig.contains("\"zone\":"));
        Assert.assertTrue(profile.rawConfig.contains("\"soar\":{"));
        Assert.assertFalse(profile.rawConfig.contains("\"profile\""));
    }

    @Test
    public void soarResolverScanRewritesTheRawConfig() throws Exception {
        WhiteDnsProfile profile =
                WhiteDnsProfileParser.parse("soar://" + SOAR_PROFILE_BODY);
        WhiteDnsProfile changed = profile.withResolvers("9.9.9.9");
        Assert.assertEquals("9.9.9.9", changed.resolverList().get(0));
        Assert.assertTrue(changed.rawConfig.contains("9.9.9.9"));
        Assert.assertFalse(changed.rawConfig.contains("1.1.1.1"));
    }

    @Test
    public void parsesSoarLink() throws Exception {
        WhiteDnsProfile profile = WhiteDnsProfileParser.parse("soar://" + base64Url(SOAR_JSON));
        Assert.assertEquals(WhiteDnsProfile.Engine.SOAR, profile.engine);
        Assert.assertEquals("soar.example.whitedns.download", profile.domain);
        Assert.assertEquals(SOAR_KEY, profile.key);
        Assert.assertEquals("SOAR", profile.name);
        Assert.assertEquals(2, profile.resolverList().size());
        Assert.assertEquals("1.1.1.1", profile.resolverList().get(0));
        Assert.assertEquals("8.8.8.8", profile.resolverList().get(1));
        Assert.assertEquals(SOAR_JSON, profile.rawConfig);
    }

    @Test
    public void rejectsSoarWithoutServerKey() {
        String json = "{\"zone\":\"soar.example.whitedns.download\","
                + "\"system_resolvers\":[\"1.1.1.1\"],"
                + "\"listen\":\"127.0.0.1:1080\"}";
        try {
            WhiteDnsProfileParser.parse("soar://" + base64Url(json));
            Assert.fail("a SOAR link without a server key must be rejected");
        } catch (WhiteDnsException e) {
            Assert.assertEquals("SOAR link has no server key", e.getMessage());
            Assert.assertFalse(e.getMessage().contains("soar.example.whitedns.download"));
        }
    }

    @Test
    public void soarConfigTakesTheNewListenPort() throws Exception {
        WhiteDnsProfile profile = WhiteDnsProfileParser.parse("soar://" + base64Url(SOAR_JSON));
        String config = profile.toEngineConfig(7123);
        Assert.assertTrue(config.endsWith("}"));
        // The rest of the raw body keeps its own spacing; only the listen value is swapped.
        Assert.assertTrue(config.contains("\"listen\":\"127.0.0.1:7123\""));
        Assert.assertFalse(config.contains("127.0.0.1:1080"));
        Assert.assertTrue(config.contains("\"zone\":\"soar.example.whitedns.download\""));
        Assert.assertEquals(1, countOf(config, "\"listen\""));
    }

    @Test
    public void soarConfigInsertsListenWhenMissing() {
        String raw = "{\"zone\":\"a.example.com\",\"server_public_key\":\"" + SOAR_KEY + "\"}";
        WhiteDnsProfile profile = new WhiteDnsProfile("Home", "a.example.com", SOAR_KEY,
                WhiteDnsProfile.Mode.NORMAL, "1.1.1.1", 3, WhiteDnsProfile.Engine.SOAR, raw);
        String config = profile.toEngineConfig(9999);
        Assert.assertTrue(config.endsWith("}"));
        Assert.assertTrue(config.contains("\"listen\": \"127.0.0.1:9999\""));
        Assert.assertTrue(config.contains("\"zone\":\"a.example.com\""));
    }

    @Test
    public void soarConfigIsBuiltWhenTheRawConfigIsMissing() {
        WhiteDnsProfile profile = new WhiteDnsProfile("Home", "a.example.com", SOAR_KEY,
                WhiteDnsProfile.Mode.NORMAL, "9.9.9.9", 3, WhiteDnsProfile.Engine.SOAR, null);
        Assert.assertNull(profile.rawConfig);
        String config = profile.toEngineConfig(5555);
        Assert.assertTrue(config.contains("\"zone\": \"a.example.com\""));
        Assert.assertTrue(config.contains("\"server_public_key\": \"" + SOAR_KEY + "\""));
        Assert.assertTrue(config.contains("\"system_resolvers\": [\"9.9.9.9\"]"));
        Assert.assertTrue(config.contains("\"listen\": \"127.0.0.1:5555\""));
        Assert.assertTrue(config.contains("\"country\": \"IR\""));
        Assert.assertTrue(config.endsWith("}"));
    }

    @Test
    public void storePersistenceNeedsAndroid() {
        // WhiteDnsStore reads a Tray prefs context, so engine and raw persistence is only
        // exercised on a device; the JVM test run skips it on purpose.
        Assume.assumeTrue("WhiteDnsStore needs an Android context", false);
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @Test
    public void errorsNeverContainTheKey() {
        String secret = "secretkeyABCDEFGH";
        String[] inputs = new String[]{
                "DOMAINS = [\"\"]\nENCRYPTION_KEY = \"" + secret + "\"\n",
                "ENCRYPTION_KEY = \"" + secret + "\"\n",
                "DOMAINS = [\"example.com\"]\nENCRYPTION_KEY = \"abc123\"\n",
                "{\"domain\": \"example.com\", \"encryption_key\": \"" + secret + "\"",
                "whitedns://not-base64!",
                "DOMAINS = [nope]\nENCRYPTION_KEY = \"" + secret + "\"\n",
                "ENCRYPTION_KEY = \"unclosed\n",
                "soar://" + base64Url("{\"zone\":\"\",\"server_public_key\":\"" + secret + "\"}"),
                "soar://" + base64Url("{\"zone\":\"a.example.com\",\"server_public_key\":\""
                        + secret + "\",\"listen\":}")
        };
        for (String input : inputs) {
            String message;
            try {
                WhiteDnsProfileParser.parse(input);
                Assert.fail("expected failure");
                return;
            } catch (WhiteDnsException e) {
                message = e.getMessage();
            }
            Assert.assertNotNull(message);
            Assert.assertFalse(message.contains(secret));
            Assert.assertFalse(message.contains("abc123"));
            for (int i = 0; i < message.length(); i++) {
                char c = message.charAt(i);
                Assert.assertTrue("non-ascii message char", c >= 0x20 && c < 0x7F);
            }
        }
    }

    /** Unpadded Base64URL, so the test does not depend on an encoder on the classpath. */
    private static String base64Url(String text) {
        byte[] data = text.getBytes(UTF8);
        StringBuilder sb = new StringBuilder();
        int index = 0;
        while (index + 2 < data.length) {
            int chunk = ((data[index] & 0xFF) << 16) | ((data[index + 1] & 0xFF) << 8)
                    | (data[index + 2] & 0xFF);
            sb.append(BASE64URL.charAt((chunk >> 18) & 63));
            sb.append(BASE64URL.charAt((chunk >> 12) & 63));
            sb.append(BASE64URL.charAt((chunk >> 6) & 63));
            sb.append(BASE64URL.charAt(chunk & 63));
            index += 3;
        }
        int rest = data.length - index;
        if (rest == 1) {
            int chunk = (data[index] & 0xFF) << 16;
            sb.append(BASE64URL.charAt((chunk >> 18) & 63));
            sb.append(BASE64URL.charAt((chunk >> 12) & 63));
        } else if (rest == 2) {
            int chunk = ((data[index] & 0xFF) << 16) | ((data[index + 1] & 0xFF) << 8);
            sb.append(BASE64URL.charAt((chunk >> 18) & 63));
            sb.append(BASE64URL.charAt((chunk >> 12) & 63));
            sb.append(BASE64URL.charAt((chunk >> 6) & 63));
        }
        return sb.toString();
    }
}
