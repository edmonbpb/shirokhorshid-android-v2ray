package com.psiphon3.psiphonlibrary;

import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Collections;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Probes Telegram through the local SOCKS proxy. Tells a blocked Telegram apart
 * from a dead proxy or a dead internet link. Pure sockets, no logging.
 */
public final class TelegramReachability {

    public enum Verdict { OK, PROXY_DOWN, NO_INTERNET, TELEGRAM_BLOCKED }

    public static final class Result {
        public final Verdict verdict;
        public final int latencyMs;
        public final String detail;

        public Result(Verdict verdict, int latencyMs, String detail) {
            this.verdict = verdict;
            this.latencyMs = latencyMs;
            this.detail = detail;
        }
    }

    private static final String PROXY_HOST = "127.0.0.1";
    private static final String TELEGRAM_HOST = "web.telegram.org";
    private static final String TELEGRAM_PATH = "/a/";
    private static final String CONTROL_HOST = "www.gstatic.com";
    private static final String CONTROL_PATH = "/generate_204";
    private static final int HTTPS_PORT = 443;
    private static final int CONTROL_TIMEOUT_MS = 6000;
    private static final int STATUS_LINE_CAP = 256;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final int STEP_OK = 0;
    private static final int STEP_PROXY = 1;
    private static final int STEP_CONNECT = 2;
    private static final int STEP_TLS = 3;
    private static final int STEP_STATUS = 4;

    private TelegramReachability() {
    }

    /**
     * Connects to Telegram through the SOCKS proxy and falls back to a general
     * site to tell a block apart from a dead link. Never throws.
     */
    public static Result probe(int socksPort, int connectTimeoutMs, int tlsTimeoutMs) {
        int connectTimeout = Math.max(1, connectTimeoutMs);
        int tlsTimeout = Math.max(1, tlsTimeoutMs);
        try {
            Attempt telegram = attempt(socksPort, connectTimeout, tlsTimeout,
                    TELEGRAM_HOST, TELEGRAM_PATH);
            if (telegram.step == STEP_OK) {
                return new Result(Verdict.OK, telegram.latencyMs, "ok");
            }
            if (telegram.step == STEP_PROXY) {
                return new Result(Verdict.PROXY_DOWN, 0, "proxy refused");
            }
            if (telegram.step == STEP_CONNECT) {
                return new Result(Verdict.NO_INTERNET, 0, "connect refused");
            }
            Attempt control = attempt(socksPort, CONTROL_TIMEOUT_MS, CONTROL_TIMEOUT_MS,
                    CONTROL_HOST, CONTROL_PATH);
            if (control.step == STEP_OK) {
                return new Result(Verdict.TELEGRAM_BLOCKED, 0, "telegram blocked, internet ok");
            }
            if (control.step == STEP_TLS) {
                return new Result(Verdict.NO_INTERNET, 0, "tls timeout");
            }
            if (control.step == STEP_CONNECT) {
                return new Result(Verdict.NO_INTERNET, 0, "connect refused");
            }
            if (control.step == STEP_PROXY) {
                return new Result(Verdict.NO_INTERNET, 0, "proxy refused");
            }
            return new Result(Verdict.NO_INTERNET, 0, "no internet");
        } catch (RuntimeException e) {
            return new Result(Verdict.NO_INTERNET, 0, "no internet");
        }
    }

    /** Greeting plus CONNECT to Telegram, then close. True only on a clean reply. */
    public static boolean heartbeat(int socksPort, int timeoutMs) {
        int timeout = Math.max(1, timeoutMs);
        Socket socket = new Socket();
        try {
            return socksConnect(socket, socksPort, TELEGRAM_HOST, timeout) == STEP_OK;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    private static Attempt attempt(int socksPort, int connectTimeoutMs, int tlsTimeoutMs,
            String host, String path) {
        Socket socket = new Socket();
        SSLSocket secure = null;
        long started = System.currentTimeMillis();
        int phase = STEP_PROXY;
        try {
            int step = socksConnect(socket, socksPort, host, connectTimeoutMs);
            if (step != STEP_OK) {
                return new Attempt(step, 0);
            }
            phase = STEP_TLS;
            socket.setSoTimeout(tlsTimeoutMs);
            secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, host, HTTPS_PORT, true);
            secure.setSoTimeout(tlsTimeoutMs);
            if (Build.VERSION.SDK_INT >= 24) {
                Sni.apply(secure, host);
            }
            secure.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier()
                    .verify(host, secure.getSession())) {
                return new Attempt(STEP_TLS, elapsed(started));
            }
            OutputStream out = secure.getOutputStream();
            out.write(headRequest(host, path).getBytes(UTF8));
            out.flush();
            int status = readStatus(secure.getInputStream());
            int latency = elapsed(started);
            if (status < 0) {
                return new Attempt(STEP_TLS, latency);
            }
            if (status >= 200 && status <= 399) {
                return new Attempt(STEP_OK, latency);
            }
            return new Attempt(STEP_STATUS, latency);
        } catch (IOException e) {
            return new Attempt(phase, elapsed(started));
        } catch (RuntimeException e) {
            return new Attempt(phase, elapsed(started));
        } finally {
            closeQuietly(secure);
            closeQuietly(socket);
        }
    }

    /**
     * Opens a tunnel to host:443 through the local SOCKS proxy.
     * Returns STEP_OK, STEP_PROXY or STEP_CONNECT.
     */
    private static int socksConnect(Socket socket, int socksPort, String host, int timeoutMs)
            throws IOException {
        socket.connect(new InetSocketAddress(PROXY_HOST, socksPort), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(new byte[] {(byte) 0x05, (byte) 0x01, (byte) 0x00});
        out.flush();
        byte[] greeting = new byte[2];
        if (!readFully(in, greeting)) {
            return STEP_PROXY;
        }
        if (greeting[0] != (byte) 0x05 || greeting[1] != (byte) 0x00) {
            return STEP_PROXY;
        }
        byte[] hostBytes = host.getBytes(UTF8);
        if (hostBytes.length > 255) {
            return STEP_PROXY;
        }
        byte[] request = new byte[7 + hostBytes.length];
        request[0] = (byte) 0x05;
        request[1] = (byte) 0x01;
        request[2] = (byte) 0x00;
        request[3] = (byte) 0x03;
        request[4] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, request, 5, hostBytes.length);
        request[5 + hostBytes.length] = (byte) ((HTTPS_PORT >> 8) & 0xFF);
        request[6 + hostBytes.length] = (byte) (HTTPS_PORT & 0xFF);
        out.write(request);
        out.flush();
        byte[] header = new byte[4];
        if (!readFully(in, header)) {
            return STEP_CONNECT;
        }
        if (header[0] != (byte) 0x05 || header[1] != (byte) 0x00) {
            return STEP_CONNECT;
        }
        int addressType = header[3] & 0xFF;
        int addressLength;
        if (addressType == 0x01) {
            addressLength = 4;
        } else if (addressType == 0x04) {
            addressLength = 16;
        } else if (addressType == 0x03) {
            byte[] length = new byte[1];
            if (!readFully(in, length)) {
                return STEP_CONNECT;
            }
            addressLength = length[0] & 0xFF;
        } else {
            return STEP_CONNECT;
        }
        if (!readFully(in, new byte[addressLength + 2])) {
            return STEP_CONNECT;
        }
        return STEP_OK;
    }

    private static boolean readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    private static String headRequest(String host, String path) {
        return "HEAD " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
    }

    private static int readStatus(InputStream input) throws IOException {
        String line = readLine(input);
        if (line == null || !line.startsWith("HTTP/")) {
            return -1;
        }
        int space = line.indexOf(' ');
        if (space < 0 || space + 4 > line.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(line.substring(space + 1, space + 4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder text = new StringBuilder();
        while (true) {
            int value = input.read();
            if (value < 0) {
                return text.length() == 0 ? null : text.toString();
            }
            if (value == '\n') {
                return text.toString();
            }
            if (value == '\r') {
                continue;
            }
            if (text.length() >= STATUS_LINE_CAP) {
                return null;
            }
            text.append((char) value);
        }
    }

    private static int elapsed(long started) {
        long ms = System.currentTimeMillis() - started;
        if (ms < 0L) {
            return 0;
        }
        return (int) Math.min(ms, Integer.MAX_VALUE);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        } catch (RuntimeException ignored) {
        }
    }

    private static final class Attempt {
        final int step;
        final int latencyMs;

        Attempt(int step, int latencyMs) {
            this.step = step;
            this.latencyMs = latencyMs;
        }
    }

    /** Keeps the API 24 call out of the verifier path on older devices. */
    private static final class Sni {
        static void apply(SSLSocket socket, String host) {
            try {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setServerNames(Collections.singletonList(
                        new javax.net.ssl.SNIHostName(host)));
                socket.setSSLParameters(parameters);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
