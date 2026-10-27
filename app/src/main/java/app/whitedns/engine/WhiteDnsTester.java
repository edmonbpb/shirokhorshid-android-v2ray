package app.whitedns.engine;

import android.content.Context;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;

import app.whitedns.model.WhiteDnsProfile;

/**
 * One-shot checks. The full test proves the tunnel carries a real HTTPS request; the resolver
 * probe measures how long one resolver takes to bring the local port up. Blocking: call off the
 * UI thread. Neither method ever logs or returns the encryption key.
 */
public final class WhiteDnsTester {

    private static final String TEST_URL = "https://www.gstatic.com/generate_204";
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final int IO_TIMEOUT_MS = 10000;
    private static final long START_TIMEOUT_MS = 20000L;
    private static final int PROBE_TIMEOUT_MS = 5000;
    private static final int MAX_ERROR_LENGTH = 120;

    private WhiteDnsTester() {
    }

    /** Null when a 2xx or 3xx answer came back through the tunnel, else a short ASCII error. */
    public static String test(Context ctx, WhiteDnsProfile p) {
        if (ctx == null || p == null) {
            return "missing profile";
        }
        WhiteDnsProcess process;
        try {
            process = WhiteDnsProcess.start(ctx, p, START_TIMEOUT_MS);
        } catch (IOException e) {
            return ascii(e.getMessage(), "could not start the engine");
        }
        try {
            int port = process.getPort();
            if (!probeSocks(port, PROBE_TIMEOUT_MS)) {
                return "the engine did not answer on its port";
            }
            int status;
            try {
                status = httpStatus(port);
            } catch (IOException e) {
                return "the request did not go through";
            } catch (RuntimeException e) {
                return "the request did not go through";
            }
            if (status >= 200 && status < 400) {
                return null;
            }
            return "http status " + status;
        } finally {
            process.close();
        }
    }

    /** Milliseconds until the single-resolver profile is ready, or -1 when it never came up. */
    public static int probeWithResolver(Context ctx, WhiteDnsProfile p, String resolver,
            long timeoutMs) {
        if (ctx == null || p == null) {
            return -1;
        }
        String value = resolver == null ? "" : resolver.trim();
        if (value.length() == 0) {
            return -1;
        }
        WhiteDnsProfile single = p.withResolvers(value);
        long startedAt = System.currentTimeMillis();
        WhiteDnsProcess process;
        try {
            process = WhiteDnsProcess.start(ctx, single, timeoutMs);
        } catch (IOException e) {
            return -1;
        }
        try {
            long elapsed = System.currentTimeMillis() - startedAt;
            if (elapsed < 0L) {
                return 0;
            }
            return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
        } finally {
            process.close();
        }
    }

    private static int httpStatus(int port) throws IOException {
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(LOCAL_HOST, port));
        URLConnection raw = new URL(TEST_URL).openConnection(proxy);
        if (!(raw instanceof HttpURLConnection)) {
            throw new IOException("test url is not http");
        }
        HttpURLConnection connection = (HttpURLConnection) raw;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(IO_TIMEOUT_MS);
            connection.setReadTimeout(IO_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    /** SOCKS5 greeting against the local engine, mirrored from the existing connector helper. */
    private static boolean probeSocks(int port, int timeoutMs) {
        if (port <= 0) {
            return false;
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(LOCAL_HOST, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.getOutputStream().write(new byte[]{5, 1, 0});
            socket.getOutputStream().flush();
            byte[] reply = new byte[2];
            int read = 0;
            while (read < reply.length) {
                int count = socket.getInputStream().read(reply, read, reply.length - read);
                if (count < 0) {
                    return false;
                }
                read += count;
            }
            return reply[0] == 5 && reply[1] == 0;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String ascii(String message, String fallback) {
        if (message == null || message.trim().length() == 0) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder();
        String value = message.trim();
        for (int i = 0; i < value.length() && sb.length() < MAX_ERROR_LENGTH; i++) {
            char c = value.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return sb.length() == 0 ? fallback : sb.toString();
    }
}
