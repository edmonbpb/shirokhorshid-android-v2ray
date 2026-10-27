package app.whitedns.model;

import java.util.ArrayList;
import java.util.List;

import app.whitedns.util.WhiteDnsProfileParser;

/**
 * Immutable WhiteDNS profile. Pure Java: it carries the engine config templates but has no
 * Android dependency, so JVM tests can build and render it. The SOAR JSON rendering itself lives
 * in {@link WhiteDnsProfileParser}, which is also pure Java, and is delegated to from here.
 */
public final class WhiteDnsProfile {

    public enum Mode { NORMAL, COTTENMAX }

    public enum Engine { COTTEN, SOAR, STORM }

    public final String name;
    public final String domain;
    public final String key;
    public final Mode mode;
    public final String resolvers;
    /** DATA_ENCRYPTION_METHOD expected by the server; 3 when the profile does not say. */
    public final int method;
    public final Engine engine;
    /** Decoded SOAR JSON for SOAR profiles; null for CottenDNS. Never log this. */
    public final String rawConfig;

    public WhiteDnsProfile(String name, String domain, String key, Mode mode, String resolvers) {
        this(name, domain, key, mode, resolvers, 3);
    }

    public WhiteDnsProfile(String name, String domain, String key, Mode mode, String resolvers,
            int method) {
        this(name, domain, key, mode, resolvers, method, Engine.COTTEN, null);
    }

    public WhiteDnsProfile(String name, String domain, String key, Mode mode, String resolvers,
            int method, Engine engine, String rawConfig) {
        this.name = clean(name);
        this.domain = clean(domain);
        this.key = clean(key);
        this.mode = mode == null ? Mode.NORMAL : mode;
        this.resolvers = clean(resolvers);
        this.method = method >= 1 && method <= 5 ? method : 3;
        this.engine = engine == null ? Engine.COTTEN : engine;
        this.rawConfig = this.engine == Engine.SOAR && rawConfig != null
                ? rawConfig.trim() : null;
    }

    /** Same profile with a replaced resolver list; engine, raw config, key and domain stay. */
    public WhiteDnsProfile withResolvers(String newResolvers) {
        String raw = rawConfig;
        if (engine == Engine.SOAR && raw != null) {
            raw = replaceSystemResolvers(raw, newResolvers);
        }
        return new WhiteDnsProfile(name, domain, key, mode, newResolvers, method, engine, raw);
    }

    /** SOAR keeps its resolver list inside the JSON, so a scan must rewrite it there too. */
    private static String replaceSystemResolvers(String raw, String resolvers) {
        StringBuilder array = new StringBuilder("[");
        String[] parts = (resolvers == null ? "" : resolvers).split("[,\\s]+");
        boolean first = true;
        for (String part : parts) {
            String value = part.trim();
            if (value.length() == 0) {
                continue;
            }
            if (!first) {
                array.append(',');
            }
            first = false;
            array.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' || c == '\\') {
                    array.append('\\');
                }
                array.append(c);
            }
            array.append('"');
        }
        array.append(']');
        return raw.replaceFirst("\"system_resolvers\"\\s*:\\s*\\[[^\\]]*\\]",
                java.util.regex.Matcher.quoteReplacement("\"system_resolvers\":" + array));
    }

    /** Same profile on another engine; a COTTEN profile has no raw config to carry over. */
    public WhiteDnsProfile withEngine(Engine newEngine) {
        return new WhiteDnsProfile(name, domain, key, mode, resolvers, method, newEngine,
                rawConfig);
    }

    /** Resolvers split on commas and whitespace, empty entries dropped, order preserved. */
    public List<String> resolverList() {
        List<String> list = new ArrayList<String>();
        if (resolvers.length() == 0) {
            return list;
        }
        String[] parts = resolvers.split("[,\\s]+");
        for (String part : parts) {
            String value = part.trim();
            if (value.length() > 0) {
                list.add(value);
            }
        }
        return list;
    }

    /** Engine config for the given local port: CottenDNS TOML, or SOAR JSON. */
    public String toEngineConfig(int listenPort) {
        return toEngineConfig(listenPort, "SOCKS5");
    }

    /**
     * Same engine config with an explicit local proxy protocol, for example "HTTP" when the
     * engine is used as an upstream proxy. SOAR carries its own protocol inside its JSON, so the
     * argument applies to the TOML engines only.
     */
    public String toEngineConfig(int listenPort, String protocolType) {
        if (engine == Engine.SOAR) {
            return WhiteDnsProfileParser.renderSoarJson(this, listenPort);
        }
        return toToml(listenPort, protocolType);
    }

    /** Renders the engine config; the domain field is split on commas into DOMAINS elements. */
    public String toToml(int listenPort) {
        return toToml(listenPort, "SOCKS5");
    }

    /** Same TOML with the given value rendered for PROTOCOL_TYPE. */
    public String toToml(int listenPort, String protocolType) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("DOMAINS = [");
        List<String> domains = domainList();
        for (int i = 0; i < domains.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(escapeToml(domains.get(i))).append('"');
        }
        sb.append("]\n");
        sb.append("DATA_ENCRYPTION_METHOD = ").append(method).append('\n');
        sb.append("ENCRYPTION_KEY = \"").append(escapeToml(key)).append("\"\n");
        sb.append("TUNNEL_MODE = \"")
                .append(mode == Mode.COTTENMAX ? "cottenmax" : "legacy").append("\"\n");
        sb.append("PROTOCOL_TYPE = \"").append(escapeToml(protocolName(protocolType)))
                .append("\"\n");
        sb.append("LISTEN_IP = \"127.0.0.1\"\n");
        sb.append("LISTEN_PORT = ").append(listenPort).append('\n');
        sb.append("SOCKS5_AUTH = false\n");
        sb.append("LOCAL_DNS_ENABLED = false\n");
        sb.append("RESOLVER_IP_MODE = \"auto\"\n");
        sb.append("STARTUP_MODE = \"resolvers\"\n");
        sb.append("TERMINAL_UI = \"plain\"\n");
        sb.append("LOG_LEVEL = \"info\"\n");
        sb.append("FAST_CONNECT = true\n");
        sb.append("MAX_ACTIVE_STREAMS = 64\n");
        sb.append("LOCAL_HANDSHAKE_TIMEOUT_SECONDS = 10\n");
        return sb.toString();
    }

    private List<String> domainList() {
        List<String> list = new ArrayList<String>();
        if (domain.length() == 0) {
            return list;
        }
        String[] parts = domain.split(",");
        for (String part : parts) {
            String value = part.trim();
            if (value.length() > 0) {
                list.add(value);
            }
        }
        return list;
    }

    /** A blank protocol keeps the engine on its default SOCKS5 listener. */
    private static String protocolName(String value) {
        String cleaned = clean(value);
        return cleaned.length() == 0 ? "SOCKS5" : cleaned;
    }

    private static String escapeToml(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '"') {
                sb.append("\\\"");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
