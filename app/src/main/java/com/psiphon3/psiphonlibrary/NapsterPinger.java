package com.psiphon3.psiphonlibrary;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Socket;

/** Measures TCP connect latency to the server behind a stored Xray config. */
public final class NapsterPinger {

    public static final class Endpoint {
        public final String host;
        public final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private NapsterPinger() {
    }

    public static Endpoint endpointOf(String configBody) {
        if (configBody == null) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(configBody);
            JSONArray outbounds = root.optJSONArray("outbounds");
            if (outbounds == null) {
                return null;
            }
            for (int i = 0; i < outbounds.length(); i++) {
                JSONObject outbound = outbounds.optJSONObject(i);
                if (outbound == null) {
                    continue;
                }
                String protocol = outbound.optString("protocol", "");
                if (!"vless".equals(protocol) && !"vmess".equals(protocol)
                        && !"trojan".equals(protocol) && !"shadowsocks".equals(protocol)) {
                    continue;
                }
                JSONObject settings = outbound.optJSONObject("settings");
                if (settings == null) {
                    continue;
                }
                Endpoint direct = endpointOf(settings);
                if (direct != null) {
                    return direct;
                }
                JSONArray vnext = settings.optJSONArray("vnext");
                if (vnext != null && vnext.length() > 0) {
                    JSONObject node = vnext.optJSONObject(0);
                    Endpoint endpoint = endpointOf(node);
                    if (endpoint != null) {
                        return endpoint;
                    }
                }
                JSONArray servers = settings.optJSONArray("servers");
                if (servers != null && servers.length() > 0) {
                    JSONObject node = servers.optJSONObject(0);
                    Endpoint endpoint = endpointOf(node);
                    if (endpoint != null) {
                        return endpoint;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Endpoint endpointOf(JSONObject node) {
        if (node == null) {
            return null;
        }
        String host = node.optString("address", "").trim();
        if (host.isEmpty()) {
            return null;
        }
        Object rawPort = node.opt("port");
        int port = 0;
        if (rawPort instanceof Number) {
            port = ((Number) rawPort).intValue();
        } else if (rawPort != null) {
            try {
                port = Integer.parseInt(String.valueOf(rawPort).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (port <= 0 || port > 65535) {
            return null;
        }
        return new Endpoint(host, port);
    }

    /** Returns the connect time in milliseconds, or -1 when the endpoint did not answer. */
    public static int ping(Endpoint endpoint, int timeoutMs) {
        if (endpoint == null) {
            return -1;
        }
        Socket socket = new Socket();
        try {
            long start = System.nanoTime();
            socket.connect(new InetSocketAddress(endpoint.host, endpoint.port), timeoutMs);
            long elapsed = (System.nanoTime() - start) / 1000000L;
            return (int) Math.min(elapsed, Integer.MAX_VALUE);
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
