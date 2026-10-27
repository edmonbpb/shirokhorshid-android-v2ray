package app.whitedns;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import app.whitedns.model.WhiteDnsProfile;
import app.whitedns.util.WhiteDnsProfileParser;

/** JVM tests for the rendered TOML template and the immutable resolver replace. */
public class WhiteDnsProfileTest {

    private static final String KEY = "0123456789abcdef";

    @Test
    public void rendersEveryContractKey() {
        WhiteDnsProfile p = new WhiteDnsProfile("Home", "example.com", KEY,
                WhiteDnsProfile.Mode.NORMAL, "1.1.1.1");
        Assert.assertEquals(WhiteDnsProfile.Engine.COTTEN, p.engine);
        Assert.assertNull(p.rawConfig);
        String toml = p.toToml(12345);
        Assert.assertTrue(toml.contains("DOMAINS = [\"example.com\"]\n"));
        Assert.assertTrue(toml.contains("DATA_ENCRYPTION_METHOD = 3\n"));
        Assert.assertTrue(toml.contains("ENCRYPTION_KEY = \"" + KEY + "\"\n"));
        Assert.assertTrue(toml.contains("TUNNEL_MODE = \"legacy\"\n"));
        Assert.assertTrue(toml.contains("PROTOCOL_TYPE = \"SOCKS5\"\n"));
        Assert.assertTrue(toml.contains("LISTEN_IP = \"127.0.0.1\"\n"));
        Assert.assertTrue(toml.contains("LISTEN_PORT = 12345\n"));
        Assert.assertTrue(toml.contains("SOCKS5_AUTH = false\n"));
        Assert.assertTrue(toml.contains("LOCAL_DNS_ENABLED = false\n"));
        Assert.assertTrue(toml.contains("RESOLVER_IP_MODE = \"auto\"\n"));
        Assert.assertTrue(toml.contains("STARTUP_MODE = \"resolvers\"\n"));
        Assert.assertTrue(toml.contains("TERMINAL_UI = \"plain\"\n"));
        Assert.assertTrue(toml.contains("LOG_LEVEL = \"info\"\n"));
        Assert.assertTrue(toml.contains("FAST_CONNECT = true\n"));
        Assert.assertTrue(toml.contains("MAX_ACTIVE_STREAMS = 64\n"));
        Assert.assertTrue(toml.contains("LOCAL_HANDSHAKE_TIMEOUT_SECONDS = 10\n"));
    }

    @Test
    public void selectsCottenMaxMode() {
        WhiteDnsProfile p = new WhiteDnsProfile("", "example.com", KEY,
                WhiteDnsProfile.Mode.COTTENMAX, "");
        Assert.assertTrue(p.toToml(9000).contains("TUNNEL_MODE = \"cottenmax\"\n"));
    }

    @Test
    public void splitsDomainsOnCommas() {
        WhiteDnsProfile p = new WhiteDnsProfile("", "a.example.com, b.example.com", KEY,
                WhiteDnsProfile.Mode.NORMAL, "");
        Assert.assertTrue(p.toToml(1)
                .contains("DOMAINS = [\"a.example.com\", \"b.example.com\"]"));
    }

    @Test
    public void replacesOnlyResolvers() {
        WhiteDnsProfile base = new WhiteDnsProfile("Home", "example.com", KEY,
                WhiteDnsProfile.Mode.COTTENMAX, "1.1.1.1");
        WhiteDnsProfile next = base.withResolvers("9.9.9.9, [2001:db8::1]:53\n8.8.4.4");
        List<String> resolvers = next.resolverList();
        Assert.assertEquals(3, resolvers.size());
        Assert.assertEquals("9.9.9.9", resolvers.get(0));
        Assert.assertEquals("[2001:db8::1]:53", resolvers.get(1));
        Assert.assertEquals("8.8.4.4", resolvers.get(2));
        Assert.assertEquals("1.1.1.1", base.resolvers);
        Assert.assertEquals(KEY, next.key);
        Assert.assertEquals("example.com", next.domain);
        Assert.assertEquals("Home", next.name);
        Assert.assertEquals(WhiteDnsProfile.Mode.COTTENMAX, next.mode);
    }

    @Test
    public void renderedTomlParsesBack() throws Exception {
        WhiteDnsProfile p = new WhiteDnsProfile("Home", "a.example.com,b.example.com", KEY,
                WhiteDnsProfile.Mode.COTTENMAX, "1.1.1.1");
        WhiteDnsProfile back = WhiteDnsProfileParser.parse(p.toToml(7777));
        Assert.assertEquals(p.domain, back.domain);
        Assert.assertEquals(p.key, back.key);
        Assert.assertEquals(p.mode, back.mode);
    }

    @Test
    public void cottenEngineConfigIsTheSameToml() {
        WhiteDnsProfile p = new WhiteDnsProfile("Home", "example.com", KEY,
                WhiteDnsProfile.Mode.COTTENMAX, "1.1.1.1", 5);
        String config = p.toEngineConfig(8080);
        Assert.assertEquals(p.toToml(8080), config);
        Assert.assertTrue(config.contains("LISTEN_PORT = 8080\n"));
        Assert.assertTrue(config.contains("DATA_ENCRYPTION_METHOD = 5\n"));
        Assert.assertTrue(config.contains("TUNNEL_MODE = \"cottenmax\"\n"));
    }

    @Test
    public void withResolversKeepsEngineAndRawConfig() {
        String raw = "{\"zone\":\"a.example.com\",\"listen\":\"127.0.0.1:1\"}";
        WhiteDnsProfile base = new WhiteDnsProfile("Home", "a.example.com", KEY,
                WhiteDnsProfile.Mode.NORMAL, "1.1.1.1", 3, WhiteDnsProfile.Engine.SOAR, raw);
        WhiteDnsProfile next = base.withResolvers("9.9.9.9");
        Assert.assertEquals(WhiteDnsProfile.Engine.SOAR, next.engine);
        Assert.assertEquals(raw, next.rawConfig);
        Assert.assertEquals("9.9.9.9", next.resolvers);
        Assert.assertEquals(KEY, next.key);
        Assert.assertEquals("a.example.com", next.domain);
    }

    @Test
    public void withEngineSwitchesTheRenderedConfig() {
        WhiteDnsProfile cotten = new WhiteDnsProfile("Home", "example.com", KEY,
                WhiteDnsProfile.Mode.NORMAL, "1.1.1.1");
        WhiteDnsProfile soar = cotten.withEngine(WhiteDnsProfile.Engine.SOAR);
        Assert.assertEquals(WhiteDnsProfile.Engine.SOAR, soar.engine);
        Assert.assertNull(soar.rawConfig);
        String config = soar.toEngineConfig(6060);
        Assert.assertTrue(config.startsWith("{"));
        Assert.assertTrue(config.contains("\"zone\": \"example.com\""));
        Assert.assertTrue(config.contains("\"listen\": \"127.0.0.1:6060\""));
        Assert.assertFalse(config.contains("ENCRYPTION_KEY"));
        Assert.assertEquals(WhiteDnsProfile.Engine.COTTEN, cotten.engine);
    }
}
