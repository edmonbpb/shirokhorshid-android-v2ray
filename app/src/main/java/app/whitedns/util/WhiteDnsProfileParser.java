package app.whitedns.util;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.whitedns.model.WhiteDnsProfile;

/**
 * Reads WhiteDNS profiles from raw TOML, a whitedns:// or cottendns:// link (Base64URL body of the
 * same TOML or of a nested profile JSON) or flat JSON with domain, encryption_key, tunnel_mode and
 * resolvers. A soar:// link carries the Base64URL body of the SOAR engine JSON; its rendering for
 * a given local port lives here as well, so the model stays free of org.json.
 *
 * <p>Pure Java on purpose: android.util.Base64 and org.json are stubs in JVM unit tests on this
 * project (no returnDefaultValues), so the link decoder, the small JSON reader and the SOAR
 * rewriter live here. Every message is short ASCII and never echoes the payload, which can hold
 * the encryption key or the server public key.
 */
public final class WhiteDnsProfileParser {

    private static final int MAX_RESOLVERS = 200;

    private static final String[] SCHEMES = {"whitedns://", "cottendns://", "stormdns://"};
    private static final String SOAR_SCHEME = "soar://";
    private static final String STORM_SCHEME = "stormdns://";
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final int MIN_KEY_LENGTH = 16;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String BASE64URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private WhiteDnsProfileParser() {
    }

    public static WhiteDnsProfile parse(String text) throws WhiteDnsException {
        if (text == null) {
            throw new WhiteDnsException("empty input");
        }
        String trimmed = text.trim();
        if (trimmed.length() == 0) {
            throw new WhiteDnsException("empty input");
        }

        String body;
        String scheme = schemeOf(trimmed);
        boolean soar = SOAR_SCHEME.equals(scheme);
        if (scheme != null) {
            body = decodeBase64Url(trimmed.substring(scheme.length()).trim()).trim();
            if (body.length() == 0) {
                throw new WhiteDnsException("empty link");
            }
        } else {
            body = trimmed;
        }
        if (soar) {
            return readSoar(body);
        }

        Fields fields = body.charAt(0) == '{' ? readJson(body) : readToml(body);
        if (STORM_SCHEME.equalsIgnoreCase(scheme)) {
            fields.engine = WhiteDnsProfile.Engine.STORM;
        }
        String domain = fields.domain.trim();
        String key = fields.key.trim();
        String name = fields.name.trim();
        validate(domain, key);
        return new WhiteDnsProfile(name, domain, key, fields.mode, fields.resolvers.trim(),
                fields.method, fields.engine, null);
    }

    private static String schemeOf(String text) {
        for (String scheme : SCHEMES) {
            if (text.regionMatches(true, 0, scheme, 0, scheme.length())) {
                return scheme;
            }
        }
        if (text.regionMatches(true, 0, SOAR_SCHEME, 0, SOAR_SCHEME.length())) {
            return SOAR_SCHEME;
        }
        return null;
    }

    /**
     * SOAR engine JSON: zone, server_public_key, an optional name, and resolvers either under
     * "resolvers" or under "system_resolvers". The decoded text is kept verbatim as rawConfig.
     */
    private static WhiteDnsProfile readSoar(String json) throws WhiteDnsException {
        if (json.charAt(0) != '{') {
            throw new WhiteDnsException("bad SOAR link");
        }
        if (json.contains("\"soarSettings\"") && json.contains("\"public_key\"")) {
            return readSoarProfile(json);
        }
        Map<String, String> map = new JsonReader(json).readObject();
        String zone = value(map, "zone").trim();
        String key = value(map, "server_public_key").trim();
        if (!hasDomain(zone)) {
            throw new WhiteDnsException("missing domain");
        }
        if (key.length() == 0) {
            throw new WhiteDnsException("SOAR link has no server key");
        }
        String name = value(map, "name").trim();
        if (name.length() == 0) {
            name = "SOAR";
        }
        String resolvers = value(map, "resolvers").trim();
        if (resolvers.length() == 0) {
            resolvers = value(map, "system_resolvers");
        }
        return new WhiteDnsProfile(name, zone, key, WhiteDnsProfile.Mode.NORMAL,
                joinResolvers(resolvers), 3, WhiteDnsProfile.Engine.SOAR, json);
    }

    /**
     * The whiteDNS SOAR profile nests the engine fields:
     * profile.server.domain, profile.server.public_key, profile.resolvers, profile.soarSettings.
     * It is flattened into the JSON the local SOAR wrapper reads.
     */
    private static WhiteDnsProfile readSoarProfile(String json) throws WhiteDnsException {
        String zone = firstMatch(json, "\"domain\"\\s*:\\s*\"([^\"]*)\"").trim();
        String key = firstMatch(json, "\"public_key\"\\s*:\\s*\"([^\"]*)\"").trim();
        String name = firstMatch(json, "\"name\"\\s*:\\s*\"([^\"]*)\"").trim();
        String list = firstMatch(json, "\"resolvers\"\\s*:\\s*\\[([^\\]]*)\\]");
        String settings = firstMatch(json, "\"soarSettings\"\\s*:\\s*\\{([^}]*)\\}");
        if (!hasDomain(zone)) {
            throw new WhiteDnsException("missing domain");
        }
        if (key.length() == 0) {
            throw new WhiteDnsException("SOAR link has no server key");
        }
        if (name.length() == 0) {
            name = "SOAR";
        }
        String resolvers = joinResolvers(list.replaceAll("\"", "").replace(' ', ','));
        StringBuilder flat = new StringBuilder(512);
        flat.append("{\"zone\":");
        appendJsonString(flat, zone);
        flat.append(",\"server_public_key\":");
        appendJsonString(flat, key);
        flat.append(",\"system_resolvers\":[");
        java.util.List<String> parsed = parseResolvers(resolvers);
        for (int i = 0; i < parsed.size(); i++) {
            if (i > 0) {
                flat.append(',');
            }
            appendJsonString(flat, parsed.get(i));
        }
        flat.append("],\"listen\":\"127.0.0.1:0\",\"username\":\"\",\"password\":\"\",\"soar\":{");
        flat.append(settings.trim());
        flat.append("}}");
        return new WhiteDnsProfile(name, zone, key, WhiteDnsProfile.Mode.NORMAL, resolvers, 3,
                WhiteDnsProfile.Engine.SOAR, flat.toString());
    }

    private static String joinResolvers(String text) {
        StringBuilder sb = new StringBuilder();
        for (String resolver : parseResolvers(text)) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(resolver);
        }
        return sb.toString();
    }

    /**
     * SOAR engine config for one local port. The stored raw config is kept as it came in, with
     * only "listen" replaced (or added); without a raw config a minimal body is built from the
     * profile fields. Pure string work, so JVM tests and the model can call it.
     */
    public static String renderSoarJson(WhiteDnsProfile profile, int listenPort) {
        String listen = LOCAL_HOST + ":" + listenPort;
        String raw = profile == null ? null : profile.rawConfig;
        if (raw != null && raw.length() > 0 && raw.charAt(0) == '{') {
            String rewritten = replaceListen(raw, listen);
            if (rewritten != null) {
                return rewritten;
            }
        }
        return minimalSoarJson(profile, listen);
    }

    /** Same text with the top-level "listen" value swapped, or the member added. */
    private static String replaceListen(String json, String listen) {
        int open = json.indexOf('{');
        if (open < 0) {
            return null;
        }
        int index = open + 1;
        int lastEnd = -1;
        int members = 0;
        while (true) {
            index = skipSpace(json, index);
            if (index >= json.length()) {
                return null;
            }
            char c = json.charAt(index);
            if (c == '}') {
                String member = "\"listen\": \"" + listen + "\"";
                StringBuilder sb = new StringBuilder(json.length() + member.length() + 4);
                if (members == 0) {
                    sb.append(json, 0, open + 1).append(member);
                } else {
                    sb.append(json, 0, lastEnd).append(", ").append(member);
                }
                sb.append(json, index, json.length());
                return sb.toString();
            }
            if (c != '"') {
                return null;
            }
            int keyEnd = stringEnd(json, index);
            if (keyEnd < 0) {
                return null;
            }
            String key = json.substring(index + 1, keyEnd);
            index = skipSpace(json, keyEnd + 1);
            if (index >= json.length() || json.charAt(index) != ':') {
                return null;
            }
            index = skipSpace(json, index + 1);
            int end = valueEnd(json, index);
            if (end < 0) {
                return null;
            }
            if ("listen".equals(key)) {
                StringBuilder sb = new StringBuilder(json.length() + 24);
                sb.append(json, 0, index).append('"').append(listen).append('"');
                sb.append(json, end, json.length());
                return sb.toString();
            }
            lastEnd = end;
            members++;
            index = skipSpace(json, end);
            if (index >= json.length()) {
                return null;
            }
            if (json.charAt(index) == ',') {
                index++;
            } else if (json.charAt(index) != '}') {
                return null;
            }
        }
    }

    /** End offset (exclusive) of the JSON value starting at {@code start}, or -1. */
    private static int valueEnd(String json, int start) {
        if (start >= json.length()) {
            return -1;
        }
        char c = json.charAt(start);
        if (c == '"') {
            int end = stringEnd(json, start);
            return end < 0 ? -1 : end + 1;
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            for (int i = start; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '"') {
                    int end = stringEnd(json, i);
                    if (end < 0) {
                        return -1;
                    }
                    i = end;
                } else if (ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ']') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
            }
            return -1;
        }
        int i = start;
        while (i < json.length()) {
            char ch = json.charAt(i);
            if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) {
                break;
            }
            i++;
        }
        return i > start ? i : -1;
    }

    private static int skipSpace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    /** Minimal body from the profile fields, using the engine defaults for the tuning block. */
    private static String minimalSoarJson(WhiteDnsProfile profile, String listen) {
        String zone = profile == null ? "" : firstDomain(profile.domain);
        String key = profile == null ? "" : profile.key;
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"zone\": ");
        appendJsonString(sb, zone);
        sb.append(", \"server_public_key\": ");
        appendJsonString(sb, key);
        sb.append(", \"system_resolvers\": [");
        List<String> resolvers = profile == null
                ? new ArrayList<String>() : parseResolvers(profile.resolvers);
        if (resolvers.isEmpty()) {
            resolvers = new ArrayList<String>();
            resolvers.add("1.1.1.1");
            resolvers.add("8.8.8.8");
        }
        for (int i = 0; i < resolvers.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            appendJsonString(sb, resolvers.get(i));
        }
        sb.append("], \"listen\": \"").append(listen).append('"');
        sb.append(", \"username\": \"\", \"password\": \"\"");
        sb.append(", \"soar\": {");
        sb.append("\"transport\": \"auto\"");
        sb.append(", \"discovery\": true");
        sb.append(", \"discoveryWant\": 2");
        sb.append(", \"discoveryConcurrency\": 2");
        sb.append(", \"discoveryPaceMs\": 1000");
        sb.append(", \"safeQName\": 101");
        sb.append(", \"maxQName\": 253");
        sb.append(", \"maxAnswer\": 1232");
        sb.append(", \"holdMs\": 500");
        sb.append(", \"maxInflight\": 128");
        sb.append(", \"maxPathWindow\": 32");
        sb.append(", \"maxStreams\": 128");
        sb.append(", \"streamWindowKiB\": 256");
        sb.append(", \"sendBufferKiB\": 64");
        sb.append(", \"sessionIdleSeconds\": 90");
        sb.append(", \"dialTimeoutSeconds\": 90");
        sb.append(", \"country\": \"IR\"");
        sb.append("}}");
        return sb.toString();
    }

    private static String firstDomain(String domain) {
        if (domain == null) {
            return "";
        }
        String[] parts = domain.split(",");
        for (String part : parts) {
            String value = part.trim();
            if (value.length() > 0) {
                return value;
            }
        }
        return "";
    }

    private static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append("\\u00");
                sb.append(Character.forDigit((c >> 4) & 0xF, 16));
                sb.append(Character.forDigit(c & 0xF, 16));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    public static List<String> parseResolvers(String text) {        List<String> resolvers = new ArrayList<String>();
        if (text == null) {
            return resolvers;
        }
        String[] parts = text.split("[,\\s]+");
        for (String part : parts) {
            String value = part.trim();
            if (value.length() == 0 || !isValidResolver(value) || resolvers.contains(value)) {
                continue;
            }
            resolvers.add(value);
            if (resolvers.size() >= MAX_RESOLVERS) {
                break;
            }
        }
        return resolvers;
    }

    private static void validate(String domain, String key) throws WhiteDnsException {
        if (!hasDomain(domain)) {
            throw new WhiteDnsException("missing domain");
        }
        if (key.length() == 0) {
            throw new WhiteDnsException("missing encryption key");
        }
        if (key.length() < MIN_KEY_LENGTH) {
            throw new WhiteDnsException("encryption key too short");
        }
    }

    private static boolean hasDomain(String domain) {
        if (domain.length() == 0) {
            return false;
        }
        String[] parts = domain.split(",");
        for (String part : parts) {
            if (part.trim().length() > 0) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // TOML

    private static Fields readToml(String text) throws WhiteDnsException {
        Fields fields = new Fields();
        List<String> domains = new ArrayList<String>();
        String[] lines = text.split("\n", -1);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.length() == 0 || line.charAt(0) == '#') {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = line.substring(0, equals).trim().toUpperCase(Locale.US);
            String value = stripComment(line.substring(equals + 1).trim());
            if ("DOMAINS".equals(name)) {
                domains.addAll(readDomainArray(value));
            } else if ("ENCRYPTION_KEY".equals(name)) {
                fields.key = readScalar(value);
            } else if ("TUNNEL_MODE".equals(name)) {
                fields.mode = modeOf(readScalar(value));
            } else if ("DATA_ENCRYPTION_METHOD".equals(name)) {
                fields.method = intValue(readScalar(value), 3);
            } else if ("NAME".equals(name)) {
                fields.name = readScalar(value);
            }
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < domains.size(); i++) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(domains.get(i));
        }
        fields.domain = joined.toString();
        return fields;
    }

    private static String stripComment(String value) {
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted && c == '\\') {
                i++;
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && c == '#') {
                return value.substring(0, i);
            }
        }
        return value;
    }

    private static List<String> readDomainArray(String value) throws WhiteDnsException {
        List<String> domains = new ArrayList<String>();
        String trimmed = value.trim();
        if (trimmed.length() == 0) {
            return domains;
        }
        if (trimmed.charAt(0) != '[') {
            String single = readScalar(trimmed);
            if (single.length() > 0) {
                domains.add(single);
            }
            return domains;
        }
        int end = trimmed.lastIndexOf(']');
        if (end < 0) {
            throw new WhiteDnsException("bad domains");
        }
        if (trimmed.substring(end + 1).trim().length() > 0) {
            throw new WhiteDnsException("bad domains");
        }
        String inner = trimmed.substring(1, end);
        int index = 0;
        while (index < inner.length()) {
            char c = inner.charAt(index);
            if (c == ',' || Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c != '"') {
                throw new WhiteDnsException("bad domains");
            }
            int close = stringEnd(inner, index);
            if (close < 0) {
                throw new WhiteDnsException("bad domains");
            }
            String item = readScalar(inner.substring(index, close + 1));
            if (item.length() > 0) {
                domains.add(item);
            }
            index = close + 1;
        }
        return domains;
    }

    private static int stringEnd(String text, int start) {
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /** Quoted TOML string with backslash escapes, or a bare token up to end of line. */
    private static String readScalar(String value) throws WhiteDnsException {
        String text = value.trim();
        if (text.length() == 0) {
            return "";
        }
        if (text.charAt(0) != '"') {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        boolean closed = false;
        while (index < text.length()) {
            char c = text.charAt(index++);
            if (c == '\\') {
                if (index >= text.length()) {
                    throw new WhiteDnsException("bad toml value");
                }
                char escape = text.charAt(index++);
                if (escape == '"' || escape == '\\') {
                    sb.append(escape);
                } else if (escape == 'n') {
                    sb.append('\n');
                } else if (escape == 'r') {
                    sb.append('\r');
                } else if (escape == 't') {
                    sb.append('\t');
                } else {
                    throw new WhiteDnsException("bad toml escape");
                }
            } else if (c == '"') {
                closed = true;
                break;
            } else {
                sb.append(c);
            }
        }
        if (!closed) {
            throw new WhiteDnsException("unclosed toml string");
        }
        if (text.substring(index).trim().length() > 0) {
            throw new WhiteDnsException("bad toml value");
        }
        return sb.toString();
    }

    private static WhiteDnsProfile.Mode modeOf(String value) {
        return value.trim().equalsIgnoreCase("cottenmax")
                ? WhiteDnsProfile.Mode.COTTENMAX : WhiteDnsProfile.Mode.NORMAL;
    }

    // ---------------------------------------------------------------------------------------
    // JSON

    private static Fields readJson(String text) throws WhiteDnsException {
        if (text.contains("\"profile\"")) {
            return readWhiteDnsProfileJson(text);
        }
        Map<String, String> map = new JsonReader(text).readObject();
        Fields fields = new Fields();
        fields.name = value(map, "name");
        fields.domain = value(map, "domain");
        fields.key = value(map, "encryption_key");
        fields.resolvers = value(map, "resolvers");
        fields.mode = modeOf(value(map, "tunnel_mode"));
        fields.method = intValue(value(map, "encryption_method"), 3);
        return fields;
    }

    /**
     * The whiteDNS profile schema nests the useful fields:
     * profile.server.domain, profile.server.encryption_key, profile.server.encryption_method.
     */
    private static Fields readWhiteDnsProfileJson(String text) throws WhiteDnsException {
        Fields fields = new Fields();
        String engine = firstMatch(text, "\"engine\"\\s*:\\s*\"([^\"]*)\"");
        if ("soar".equalsIgnoreCase(engine)) {
            throw new WhiteDnsException("SOAR profile needs a soar:// link");
        }
        fields.name = firstMatch(text, "\"name\"\\s*:\\s*\"([^\"]*)\"");
        fields.domain = firstMatch(text, "\"domain\"\\s*:\\s*\"([^\"]*)\"");
        fields.key = firstMatch(text, "\"encryption_key\"\\s*:\\s*\"([^\"]*)\"");
        fields.mode = modeOf(firstMatch(text, "\"tunnel_mode\"\\s*:\\s*\"([^\"]*)\""));
        fields.method = intValue(firstMatch(text, "\"encryption_method\"\\s*:\\s*(\\d+)"), 3);
        String list = firstMatch(text, "\"resolvers\"\\s*:\\s*\\[([^\\]]*)\\]");
        StringBuilder resolvers = new StringBuilder();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(list);
        while (matcher.find()) {
            if (resolvers.length() > 0) {
                resolvers.append('\n');
            }
            resolvers.append(matcher.group(1));
        }
        fields.resolvers = resolvers.toString();
        return fields;
    }

    private static String firstMatch(String text, String pattern) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int intValue(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 1 && parsed <= 5 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String value(Map<String, String> map, String name) {
        String found = map.get(name);
        return found == null ? "" : found;
    }

    /** Minimal flat-object JSON reader: object of string/number/bool/array values. */
    private static final class JsonReader {

        private final String text;
        private int index;

        JsonReader(String text) {
            this.text = text;
        }

        Map<String, String> readObject() throws WhiteDnsException {
            Map<String, String> fields = new HashMap<String, String>();
            skipWhitespace();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                index++;
                finish();
                return fields;
            }
            while (true) {
                skipWhitespace();
                String name = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                fields.put(name, readValue());
                skipWhitespace();
                char next = next();
                if (next == '}') {
                    finish();
                    return fields;
                }
                if (next != ',') {
                    throw error();
                }
            }
        }

        private String readValue() throws WhiteDnsException {
            char c = peek();
            if (c == '"') {
                return readString();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '{') {
                skipContainer();
                return "";
            }
            return readLiteral();
        }

        private String readArray() throws WhiteDnsException {
            expect('[');
            StringBuilder sb = new StringBuilder();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return "";
            }
            while (true) {
                skipWhitespace();
                char c = peek();
                String item;
                if (c == '"') {
                    item = readString();
                } else if (c == '[' || c == '{') {
                    skipContainer();
                    item = "";
                } else {
                    item = readLiteral();
                }
                if (item.length() > 0) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(item);
                }
                skipWhitespace();
                char next = next();
                if (next == ']') {
                    return sb.toString();
                }
                if (next != ',') {
                    throw error();
                }
            }
        }

        private String readLiteral() throws WhiteDnsException {
            int start = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                    break;
                }
                boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                        || (c >= 'A' && c <= 'Z') || c == '+' || c == '-' || c == '.';
                if (!allowed) {
                    throw error();
                }
                index++;
            }
            if (index == start) {
                throw error();
            }
            String token = text.substring(start, index);
            if ("null".equals(token)) {
                return "";
            }
            return token;
        }

        private String readString() throws WhiteDnsException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (index >= text.length()) {
                    throw error();
                }
                char c = text.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (index >= text.length()) {
                        throw error();
                    }
                    char escape = text.charAt(index++);
                    if (escape == '"' || escape == '\\' || escape == '/') {
                        sb.append(escape);
                    } else if (escape == 'b') {
                        sb.append('\b');
                    } else if (escape == 'f') {
                        sb.append('\f');
                    } else if (escape == 'n') {
                        sb.append('\n');
                    } else if (escape == 'r') {
                        sb.append('\r');
                    } else if (escape == 't') {
                        sb.append('\t');
                    } else if (escape == 'u') {
                        if (index + 4 > text.length()) {
                            throw error();
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(text.charAt(index++), 16);
                            if (digit < 0) {
                                throw error();
                            }
                            code = (code << 4) | digit;
                        }
                        sb.append((char) code);
                    } else {
                        throw error();
                    }
                } else if (c < 0x20) {
                    throw error();
                } else {
                    sb.append(c);
                }
            }
        }

        private void skipContainer() throws WhiteDnsException {
            char open = next();
            char close = open == '{' ? '}' : ']';
            int depth = 1;
            while (depth > 0) {
                if (index >= text.length()) {
                    throw error();
                }
                char c = text.charAt(index++);
                if (c == '"') {
                    index--;
                    readString();
                } else if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                } else if (c == '{' || c == '[') {
                    skipContainerFrom(c);
                }
            }
        }

        private void skipContainerFrom(char open) throws WhiteDnsException {
            index--;
            skipContainer();
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private char peek() throws WhiteDnsException {
            if (index >= text.length()) {
                throw error();
            }
            return text.charAt(index);
        }

        private char next() throws WhiteDnsException {
            if (index >= text.length()) {
                throw error();
            }
            return text.charAt(index++);
        }

        private void expect(char expected) throws WhiteDnsException {
            if (next() != expected) {
                throw error();
            }
        }

        private void finish() throws WhiteDnsException {
            skipWhitespace();
            if (index < text.length()) {
                throw error();
            }
        }

        private WhiteDnsException error() {
            return new WhiteDnsException("bad json");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Base64URL

    private static String decodeBase64Url(String encoded) throws WhiteDnsException {
        StringBuilder clean = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '=') {
                break;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            clean.append(c);
        }
        int length = clean.length();
        if (length == 0 || length % 4 == 1) {
            throw new WhiteDnsException("bad whitedns link");
        }
        byte[] out = new byte[length * 3 / 4];
        int buffer = 0;
        int bits = 0;
        int written = 0;
        for (int i = 0; i < length; i++) {
            int value = BASE64URL.indexOf(clean.charAt(i));
            if (value < 0) {
                throw new WhiteDnsException("bad whitedns link");
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[written++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        if (written == out.length) {
            return new String(out, UTF8);
        }
        byte[] shorter = new byte[written];
        System.arraycopy(out, 0, shorter, 0, written);
        return new String(shorter, UTF8);
    }

    // ---------------------------------------------------------------------------------------
    // Resolvers

    private static boolean isValidResolver(String value) {
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                return false;
            }
            String host = value.substring(1, close);
            String rest = value.substring(close + 1);
            if (rest.length() == 0) {
                return isIpv6(host);
            }
            return rest.charAt(0) == ':' && isIpv6(host) && isPort(rest.substring(1));
        }
        int first = value.indexOf(':');
        int last = value.lastIndexOf(':');
        if (first < 0) {
            return isIpv4(value);
        }
        if (first == last) {
            return isIpv4(value.substring(0, first)) && isPort(value.substring(first + 1));
        }
        return isIpv6(value);
    }

    private static boolean isPort(String value) {
        if (value.length() == 0 || value.length() > 5) {
            return false;
        }
        int port = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            port = port * 10 + (c - '0');
        }
        return port > 0 && port <= 65535;
    }

    private static boolean isIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.length() == 0 || part.length() > 3) {
                return false;
            }
            int octet = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                octet = octet * 10 + (c - '0');
            }
            if (octet > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6(String value) {
        if (value.length() < 2 || value.indexOf(':') < 0) {
            return false;
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && compression != value.lastIndexOf("::")) {
            return false;
        }
        String head = value;
        String tailQuad = null;
        int dot = value.lastIndexOf('.');
        if (dot >= 0) {
            int groupStart = value.lastIndexOf(':', dot);
            if (groupStart < 0) {
                return false;
            }
            tailQuad = value.substring(groupStart + 1);
            if (!isIpv4(tailQuad)) {
                return false;
            }
            head = value.substring(0, groupStart);
            if (head.endsWith(":")) {
                head = head + "0";
            } else if (head.length() == 0) {
                return false;
            } else {
                head = head + ":0";
            }
        }
        String[] groups = head.split(":", -1);
        int filled = 0;
        boolean sawEmpty = false;
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            if (group.length() == 0) {
                if (compression < 0) {
                    return false;
                }
                sawEmpty = true;
                continue;
            }
            if (group.length() > 4) {
                return false;
            }
            for (int n = 0; n < group.length(); n++) {
                if (Character.digit(group.charAt(n), 16) < 0) {
                    return false;
                }
            }
            filled++;
        }
        if (compression < 0) {
            return filled == 8 && !sawEmpty;
        }
        return filled < 8;
    }

    private static final class Fields {
        String name = "";
        String domain = "";
        String key = "";
        String resolvers = "";
        WhiteDnsProfile.Mode mode = WhiteDnsProfile.Mode.NORMAL;
        int method = 3;
        WhiteDnsProfile.Engine engine = WhiteDnsProfile.Engine.COTTEN;
    }
}
