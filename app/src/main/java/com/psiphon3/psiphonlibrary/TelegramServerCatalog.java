package com.psiphon3.psiphonlibrary;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import app.whitedns.model.WhiteDnsProfile;
import app.whitedns.store.WhiteDnsStore;

/**
 * Builds the server list for one Telegram connection method and remembers which server is active.
 * Config bodies are only read to extract host and port; they never leave this class.
 */
public final class TelegramServerCatalog {

    private static final String WHITEDNS_ID = "dnstt";
    private static final String WHITEDNS_NAME_FALLBACK = "WhiteDNS";
    private static final int WHITEDNS_PORT = 53;

    private TelegramServerCatalog() {
    }

    public static List<TelegramServer> load(Context ctx, TelegramConnector.Method method) {
        List<TelegramServer> servers = new ArrayList<>();
        if (ctx == null || method == null) {
            return servers;
        }
        switch (method) {
            case NAPSTER:
                appendNapsterServers(ctx, servers);
                break;
            case V2RAY:
                appendV2RayServers(ctx, servers);
                break;
            case WHITEDNS:
                servers.add(whitednsServer(ctx));
                break;
            default:
                break;
        }
        return servers;
    }

    public static String activeId(Context ctx, TelegramConnector.Method method) {
        if (ctx == null || method == null) {
            return null;
        }
        switch (method) {
            case NAPSTER: {
                NapsterConfigStore.ConfigEntry entry = NapsterConfigStore.getActiveConfig(ctx);
                return entry == null ? null : entry.id;
            }
            case V2RAY: {
                V2RayConfigStore.ConfigEntry entry = V2RayConfigStore.getActiveConfig(ctx);
                return entry == null ? null : entry.id;
            }
            case WHITEDNS:
                return WHITEDNS_ID;
            default:
                return null;
        }
    }

    public static boolean select(Context ctx, TelegramConnector.Method method, String id) {
        if (ctx == null || method == null || id == null || id.length() == 0) {
            return false;
        }
        switch (method) {
            case NAPSTER:
                NapsterConfigStore.setActiveConfigId(ctx, id);
                return true;
            case V2RAY:
                V2RayConfigStore.setActiveConfigId(ctx, id);
                return true;
            default:
                return false;
        }
    }

    public static boolean hasServers(TelegramConnector.Method method) {
        if (method == null) {
            return false;
        }
        return method == TelegramConnector.Method.NAPSTER
                || method == TelegramConnector.Method.V2RAY
                || method == TelegramConnector.Method.WHITEDNS;
    }

    /**
     * Sorts in place: quickest first, entries without a reply last, equal pings by name.
     * The ping map is only read.
     */
    public static void sortByPing(List<TelegramServer> servers, Map<String, Integer> pingById) {
        if (servers == null || servers.size() < 2) {
            return;
        }
        final Map<String, Integer> pings = pingById == null
                ? Collections.<String, Integer>emptyMap()
                : pingById;
        Comparator<TelegramServer> order = (first, second) -> {
            if (first == null || second == null) {
                if (first == second) {
                    return 0;
                }
                return first == null ? 1 : -1;
            }
            int firstPing = rankOf(pings.get(first.id));
            int secondPing = rankOf(pings.get(second.id));
            if (firstPing != secondPing) {
                return firstPing < secondPing ? -1 : 1;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(nameOf(first), nameOf(second));
        };
        Collections.sort(servers, order);
    }

    private static int rankOf(Integer milliseconds) {
        if (milliseconds == null || milliseconds < 0) {
            return Integer.MAX_VALUE;
        }
        return milliseconds;
    }

    private static String nameOf(TelegramServer server) {
        return server == null || server.name == null ? "" : server.name;
    }

    private static void appendNapsterServers(Context ctx, List<TelegramServer> servers) {
        for (NapsterConfigStore.ConfigEntry entry : NapsterConfigStore.getConfigs(ctx)) {
            if (entry == null || entry.id == null || entry.id.length() == 0) {
                continue;
            }
            appendServer(servers, entry.id, entry.name,
                    NapsterConfigStore.readConfigBody(ctx, entry.id));
        }
    }

    private static void appendV2RayServers(Context ctx, List<TelegramServer> servers) {
        for (V2RayConfigStore.ConfigEntry entry : V2RayConfigStore.getConfigs(ctx)) {
            if (entry == null || entry.id == null || entry.id.length() == 0) {
                continue;
            }
            appendServer(servers, entry.id, entry.name,
                    V2RayConfigStore.readConfigBody(ctx, entry.id));
        }
    }

    private static void appendServer(List<TelegramServer> servers, String id, String name,
            String body) {
        NapsterPinger.Endpoint endpoint = NapsterPinger.endpointOf(body);
        if (endpoint != null) {
            servers.add(new TelegramServer(id, name, endpoint.host, endpoint.port));
        }
    }

    private static TelegramServer whitednsServer(Context ctx) {
        String name = null;
        String host = "";
        int port = WHITEDNS_PORT;
        try {
            WhiteDnsProfile profile = WhiteDnsStore.load(ctx);
            if (profile != null) {
                name = profile.name;
                List<String> resolvers = profile.resolverList();
                if (!resolvers.isEmpty()) {
                    String entry = resolvers.get(0);
                    int bracket = entry.startsWith("[") ? entry.indexOf(']') : -1;
                    if (bracket > 0) {
                        host = entry.substring(1, bracket);
                        port = portOf(entry, bracket + 1, port);
                    } else {
                        int colon = entry.indexOf(':');
                        if (colon > 0 && colon == entry.lastIndexOf(':')) {
                            host = entry.substring(0, colon);
                            port = portOf(entry, colon, port);
                        } else {
                            host = entry;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String cleanedName = name == null ? "" : name.trim();
        if (cleanedName.length() == 0) {
            cleanedName = WHITEDNS_NAME_FALLBACK;
        }
        return new TelegramServer(WHITEDNS_ID, cleanedName, host, port);
    }

    /** Port after the ':' at {@code separator} in a resolver entry, or the fallback. */
    private static int portOf(String entry, int separator, int fallback) {
        if (separator >= entry.length() || entry.charAt(separator) != ':'
                || separator + 1 >= entry.length()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(entry.substring(separator + 1).trim());
            return value > 0 && value <= 65535 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
