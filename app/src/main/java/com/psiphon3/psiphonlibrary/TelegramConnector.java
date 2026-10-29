package com.psiphon3.psiphonlibrary;

import com.psiphon3.R;

import android.content.Context;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import app.whitedns.model.WhiteDnsProfile;
import app.whitedns.net.WhiteDnsTunnel;
import app.whitedns.store.WhiteDnsStore;
import net.grandcentrix.tray.AppPreferences;

/** Obtains a local SOCKS5 endpoint for the Telegram screen from one of the app methods. */
public final class TelegramConnector {

    public enum Method { WHITEDNS, V2RAY, NAPSTER, MAIN, COMBINED, CONDUIT, CDN_FRONTING }

    public static final class Link implements Closeable {
        public final Method method;
        public final int socksPort;
        private final Closeable tunnel;
        private final boolean releaseCore;
        private final PsiphonLocalTunnel localTunnel;
        private boolean closed;

        Link(Method method, int socksPort, Closeable tunnel, boolean releaseCore) {
            this(method, socksPort, tunnel, releaseCore, null);
        }

        Link(Method method, int socksPort, Closeable tunnel, boolean releaseCore,
                PsiphonLocalTunnel localTunnel) {
            this.method = method;
            this.socksPort = socksPort;
            this.tunnel = tunnel;
            this.releaseCore = releaseCore;
            this.localTunnel = localTunnel;
        }

        /** The local core behind a standalone CDN fronting link; null for every other method. */
        public PsiphonLocalTunnel localTunnel() {
            return localTunnel;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (tunnel != null) {
                try {
                    tunnel.close();
                } catch (Exception ignored) {
                }
            }
            if (releaseCore) {
                V2RayManager.releaseForTelegram();
            }
        }
    }

    private TelegramConnector() {
    }

    public static Link connect(Context ctx, Method method, boolean mainRunning,
            int mainSocksPort, boolean upstreamEnabled, long timeoutMs) throws IOException {
        if (ctx == null || method == null) {
            throw new IOException("no connection method selected");
        }
        switch (method) {
            case WHITEDNS:
                return connectWhitedns(ctx, timeoutMs);
            case V2RAY:
                return connectCore(ctx, method, false, mainRunning);
            case NAPSTER:
                return connectCore(ctx, method, true, mainRunning);
            case MAIN:
                return connectMain(method, mainRunning, mainSocksPort);
            case COMBINED:
                if (!upstreamEnabled) {
                    throw new IOException("enable Napster or V2Ray first");
                }
                return connectMain(method, mainRunning, mainSocksPort);
            case CONDUIT:
                return connectMain(method, mainRunning, mainSocksPort);
            case CDN_FRONTING:
                return connectCdnFronting(ctx, mainRunning, mainSocksPort, timeoutMs);
            default:
                throw new IOException("unknown connection method");
        }
    }

    private static Link connectWhitedns(Context ctx, long timeoutMs) throws IOException {
        WhiteDnsProfile profile = WhiteDnsStore.load(ctx);
        if (profile == null) {
            throw new IOException("no WhiteDNS profile");
        }
        WhiteDnsTunnel tunnel = WhiteDnsTunnel.start(ctx, profile, timeoutMs);
        int port;
        try {
            port = tunnel.getPort();
            if (!probeSocks(port, 8000)) {
                throw new IOException("the WhiteDNS tunnel did not answer");
            }
        } catch (RuntimeException e) {
            try { tunnel.close(); } catch (RuntimeException ignored) { }
            throw new IOException("the WhiteDNS tunnel did not answer");
        } catch (IOException e) {
            try { tunnel.close(); } catch (RuntimeException ignored) { }
            throw e;
        }
        return new Link(Method.WHITEDNS, port, tunnel, false);
    }

    private static Link connectCore(Context ctx, Method method, boolean napster, boolean mainRunning)
            throws IOException {
        if (mainRunning) {
            int port = V2RayManager.getPublishedSocksPort(ctx);
            String source = V2RayManager.getPublishedSource(ctx);
            boolean matches = napster
                    ? (source != null && source.startsWith("napster"))
                    : "v2ray".equals(source);
            if (port <= 0 || !matches) {
                throw new IOException("the selected method is not running on the main tunnel");
            }
            if (!probeSocks(port, 8000)) {
                throw new IOException("the selected method did not answer");
            }
            return new Link(method, port, null, false);
        }
        int port;
        try {
            port = V2RayManager.acquireForTelegram(ctx, napster);
        } catch (V2RayManager.V2RayException e) {
            throw new IOException("could not start the selected method");
        }
        if (port <= 0 || !probeSocks(port, 8000)) {
            V2RayManager.releaseForTelegram();
            throw new IOException("the selected method did not answer");
        }
        return new Link(method, port, null, true);
    }

    private static Link connectMain(Method method, boolean mainRunning, int mainSocksPort)
            throws IOException {
        if (!mainRunning || mainSocksPort <= 0) {
            throw new IOException("start the main tunnel first");
        }
        if (!probeSocks(mainSocksPort, 8000)) {
            throw new IOException("the main tunnel is not answering");
        }
        return new Link(method, mainSocksPort, null, false);
    }

    private static Link connectCdnFronting(Context ctx, boolean mainRunning, int mainSocksPort,
            long timeoutMs) throws IOException {
        String storedProtocol = "auto";
        try {
            storedProtocol = new AppPreferences(ctx)
                    .getString(ctx.getString(R.string.protocolSelectionPreference), "auto");
        } catch (RuntimeException ignored) {
        }
        if (mainRunning && mainSocksPort > 0 && "cdn_fronting".equals(storedProtocol)) {
            return connectMain(Method.CDN_FRONTING, true, mainSocksPort);
        }
        PsiphonLocalTunnel localTunnel = PsiphonLocalTunnel.start(ctx, timeoutMs);
        int port;
        try {
            port = localTunnel.getPort();
            if (!probeSocks(port, 8000)) {
                throw new IOException("the CDN tunnel did not answer");
            }
        } catch (RuntimeException e) {
            try { localTunnel.close(); } catch (RuntimeException ignored) { }
            throw new IOException("the CDN tunnel did not answer");
        } catch (IOException e) {
            try { localTunnel.close(); } catch (RuntimeException ignored) { }
            throw e;
        }
        return new Link(Method.CDN_FRONTING, port, localTunnel, false, localTunnel);
    }

    public static boolean probeSocks(int port, int timeoutMs) {
        if (port <= 0) {
            return false;
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.getOutputStream().write(new byte[]{5, 1, 0});
            socket.getOutputStream().flush();
            byte[] reply = new byte[2];
            int read = socket.getInputStream().read(reply);
            return read == 2 && reply[0] == 5 && reply[1] == 0;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
