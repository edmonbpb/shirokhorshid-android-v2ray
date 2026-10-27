/*
 * Copyright (c) 2026, ShirOKhorshid
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Checks the Napster configs through their local HTTP proxy, one config at a time. */
public final class NapsterHealthChecker {

    private static final String TAG = "NapsterHealth";
    private static final String WORKER_NAME = "NapsterHealthCheck";
    private static final String PROXY_HOST = "127.0.0.1";
    private static final long READY_TIMEOUT_MS = 15000L;
    private static final long CONFIG_BUDGET_MS = 40000L;
    private static final long EXIT_WAIT_MS = 2000L;
    private static final long KILL_WAIT_MS = 1000L;
    private static final int PROBE_TIMEOUT_MS = 8000;
    private static final int CONTROL_TIMEOUT_MS = 6000;
    private static final int PROBE_TRIES = 2;
    private static final int STATUS_LINE_CAP = 1024;
    private static final int MAX_HEADER_LINES = 200;
    private static final int TOKEN_LENGTH = 24;
    private static final String TELEGRAM_URL = "https://web.telegram.org/a/";
    private static final String[] GENERAL_URLS = {
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204"};
    private static final String[] CONTROL_URLS = {
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204"};
    private static final char[] ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Object LOCK = new Object();
    private static volatile boolean sRunning;
    private static volatile boolean sCancelled;
    private static volatile Process sProcess;
    private static volatile Socket sSocket;

    private NapsterHealthChecker() { }

    public interface Listener {
        void onProgress(int done, int total, String currentId);
        void onResult(String id, NapsterHealthStore.Health health);
        void onFinished();
    }

    public static boolean isRunning() {
        return sRunning;
    }

    public static void checkAll(Context ctx, List<String> ids, Listener listener, Handler main) {
        if (listener == null) return;
        final Context app = ctx == null ? null : ctx.getApplicationContext();
        final Handler handler = main;
        final Listener target = listener;
        if (app == null) {
            post(handler, new FinishedTask(target));
            return;
        }
        final List<String> work = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isEmpty()) work.add(id);
            }
        }
        synchronized (LOCK) {
            if (sRunning) {
                post(handler, new FinishedTask(target));
                return;
            }
            sRunning = true;
            sCancelled = false;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runAll(app, work, target, handler);
            }
        }, WORKER_NAME);
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not start the health check thread");
            synchronized (LOCK) {
                sRunning = false;
            }
            post(handler, new FinishedTask(target));
        }
    }

    public static void cancel() {
        sCancelled = true;
        Process process = sProcess;
        if (process != null) {
            try { process.destroy(); } catch (RuntimeException ignored) { }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { process.destroyForcibly(); } catch (RuntimeException ignored) { }
            }
        }
        Socket socket = sSocket;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static void runAll(Context ctx, List<String> ids, Listener listener, Handler main) {
        try {
            int total = ids.size();
            for (int i = 0; i < total; i++) {
                if (sCancelled) return;
                String id = ids.get(i);
                post(main, new ProgressTask(listener, i + 1, total, id));
                NapsterHealthStore.Health health;
                try {
                    health = checkOne(ctx, id);
                } catch (RuntimeException e) {
                    Log.w(TAG, "A health check failed to run");
                    String kind = connectionKind(ctx);
                    health = evaluateAndSave(ctx, id, true, 0, false, null, 0,
                            kind == null ? "unknown" : kind);
                }
                if (health == null) return;
                post(main, new ResultTask(listener, id, health));
            }
        } finally {
            synchronized (LOCK) {
                sRunning = false;
            }
            sProcess = null;
            sSocket = null;
            post(main, new FinishedTask(listener));
        }
    }

    private static NapsterHealthStore.Health checkOne(Context ctx, String id) {
        long deadline = System.currentTimeMillis() + CONFIG_BUDGET_MS;
        boolean networkOk = networkControl();
        if (sCancelled) return null;
        if (!networkOk) {
            Log.i(TAG, "Health check skipped: no network control path");
            return evaluateAndSave(ctx, id, false, 0, false, null, 0, "offline");
        }
        String kind = connectionKind(ctx);
        String network = kind == null ? "unknown" : kind;
        String body = NapsterConfigStore.readConfigBody(ctx, id);
        int generalSuccesses = 0;
        boolean telegramOk = false;
        int telegramMs = 0;
        List<Integer> latencies = new ArrayList<>();
        if (body != null) {
            TempCore temp = null;
            try {
                Endpoint endpoint = reusePublishedProxy(ctx, id);
                if (endpoint != null) {
                    int publishedSocks = V2RayManager.getPublishedSocksPort(ctx);
                    if (publishedSocks <= 0
                            || !TelegramConnector.probeSocks(publishedSocks, 3000)) {
                        endpoint = null;
                    }
                }
                if (endpoint == null) {
                    temp = startTempCore(ctx, body, deadline);
                    endpoint = temp.endpoint;
                }
                if (endpoint != null && !sCancelled) {
                    for (String url : GENERAL_URLS) {
                        if (sCancelled) return null;
                        int latency = probeUrl(url, endpoint, deadline, false);
                        if (latency >= 0) {
                            generalSuccesses++;
                            latencies.add(latency);
                        }
                    }
                    if (!sCancelled) {
                        int latency = probeUrl(TELEGRAM_URL, endpoint, deadline, true);
                        if (latency >= 0) {
                            telegramOk = true;
                            telegramMs = latency;
                        }
                    }
                }
            } finally {
                stopTempCore(temp);
            }
        }
        if (sCancelled) return null;
        return evaluateAndSave(ctx, id, true, generalSuccesses, telegramOk, latencies,
                telegramMs, network);
    }

    private static NapsterHealthStore.Health evaluateAndSave(Context ctx, String id,
            boolean networkOk, int generalSuccesses, boolean telegramOk, List<Integer> latencies,
            int telegramMs, String network) {
        NapsterHealthRules.Status previousStatus = NapsterHealthRules.Status.UNKNOWN;
        int failStreak = 0;
        NapsterHealthStore.Health previous = NapsterHealthStore.load(ctx, id);
        if (previous != null) {
            failStreak = previous.failStreak;
            try {
                previousStatus = NapsterHealthRules.Status.valueOf(previous.status);
            } catch (IllegalArgumentException e) {
                previousStatus = NapsterHealthRules.Status.UNKNOWN;
            }
        }
        NapsterHealthRules.Result result = NapsterHealthRules.evaluate(new NapsterHealthRules.Input(
                networkOk, generalSuccesses, telegramOk, previousStatus, failStreak));
        NapsterHealthStore.Health health = new NapsterHealthStore.Health(
                System.currentTimeMillis(), result.status.name(),
                NapsterHealthRules.median(latencies), telegramMs, result.failStreak, network);
        try {
            NapsterHealthStore.save(ctx, id, health);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not save a health result");
        }
        return health;
    }

    private static Endpoint reusePublishedProxy(Context ctx, String id) {
        try {
            if (!NapsterConfigStore.isFeatureEnabled(ctx)) return null;
            NapsterConfigStore.ConfigEntry active = NapsterConfigStore.getActiveConfig(ctx);
            if (active == null || !id.equals(active.id)) return null;
            String url = V2RayManager.getPublishedProxyUrl(ctx);
            if (url == null) return null;
            if (!("napster:" + active.id).equals(V2RayManager.getPublishedSource(ctx))) return null;
            return parseProxyUrl(url);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Endpoint parseProxyUrl(String url) {
        if (url == null) return null;
        String text = url.trim();
        if (!text.startsWith("http://")) return null;
        text = text.substring("http://".length());
        int slash = text.indexOf('/');
        if (slash >= 0) text = text.substring(0, slash);
        int at = text.lastIndexOf('@');
        if (at < 0) return null;
        String userInfo = text.substring(0, at);
        String hostPort = text.substring(at + 1);
        int colon = userInfo.indexOf(':');
        if (colon <= 0) return null;
        String user = userInfo.substring(0, colon);
        String pass = userInfo.substring(colon + 1);
        int lastColon = hostPort.lastIndexOf(':');
        if (lastColon <= 0) return null;
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(lastColon + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (port <= 0 || port > 65535) return null;
        return new Endpoint(port, user, pass);
    }

    private static TempCore startTempCore(Context ctx, String body, long deadline) {
        File directory = new File(new File(ctx.getFilesDir(), "napster"), "check");
        File configFile = new File(directory, "config.json");
        TempCore temp = new TempCore(configFile);
        int port;
        try {
            port = freePort();
        } catch (IOException e) {
            Log.w(TAG, "Could not find a free port for the health core");
            return temp;
        }
        String user = randomToken(TOKEN_LENGTH);
        String pass = randomToken(TOKEN_LENGTH);
        String json;
        try {
            json = V2RayManager.sanitizeConfig(body, port, user, pass);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "The config was rejected for the health core");
            return temp;
        }
        try {
            writeText(configFile, json);
        } catch (IOException e) {
            Log.w(TAG, "Could not write the health core config");
            return temp;
        }
        CountDownLatch latch = new CountDownLatch(1);
        try {
            ProcessBuilder builder = new ProcessBuilder(V2RayManager.corePath(ctx), "run",
                    configFile.getAbsolutePath());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            temp.process = process;
            sProcess = process;
            Thread drain = new Thread(new StreamDrain(process.getInputStream(), latch),
                    "NapsterHealthDrain");
            drain.setDaemon(true);
            drain.start();
            long wait = Math.min(READY_TIMEOUT_MS, deadline - System.currentTimeMillis());
            if (awaitReady(process, latch, wait)) {
                temp.endpoint = new Endpoint(port, user, pass);
            } else {
                Log.w(TAG, "The health core did not become ready");
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not start the health core");
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not start the health core");
        }
        return temp;
    }

    private static void stopTempCore(TempCore temp) {
        if (temp == null) return;
        Process process = temp.process;
        if (process != null) {
            try {
                OutputStream stdin = process.getOutputStream();
                if (stdin != null) stdin.close();
            } catch (IOException ignored) {
            } catch (RuntimeException ignored) {
            }
            try { process.destroy(); } catch (RuntimeException ignored) { }
            waitForExit(process, EXIT_WAIT_MS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { process.destroyForcibly(); } catch (RuntimeException ignored) { }
                waitForExit(process, KILL_WAIT_MS);
            }
            if (sProcess == process) sProcess = null;
        }
        File file = temp.configFile;
        if (file != null && !file.delete() && file.exists()) {
            Log.w(TAG, "Could not delete the health core config");
        }
    }

    private static boolean awaitReady(Process process, CountDownLatch latch, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            if (sCancelled) return false;
            try {
                if (latch.await(100L, TimeUnit.MILLISECONDS)) return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!isAlive(process)) return false;
            if (System.currentTimeMillis() >= deadline) return false;
        }
    }

    private static int probeUrl(String url, Endpoint endpoint, long deadline, boolean telegram) {
        for (int attempt = 0; attempt < PROBE_TRIES; attempt++) {
            if (sCancelled) return -1;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) return -1;
            long clamped = Math.min((long) PROBE_TIMEOUT_MS, remaining);
            if (clamped < 1L) return -1;
            int latency = probeOnce(url, endpoint, (int) clamped, telegram);
            if (latency >= 0) return latency;
        }
        return -1;
    }

    private static int probeOnce(String url, Endpoint endpoint, int timeoutMs, boolean telegram) {
        if (endpoint == null) return -1;
        URL target;
        try {
            target = new URL(url);
        } catch (IOException e) {
            return -1;
        }
        Socket socket = new Socket();
        sSocket = socket;
        long started = System.currentTimeMillis();
        try {
            socket.connect(new InetSocketAddress(PROXY_HOST, endpoint.port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            String host = target.getHost();
            String path = pathOf(target);
            String request;
            InputStream input;
            if ("https".equalsIgnoreCase(target.getProtocol())) {
                OutputStream rawOut = socket.getOutputStream();
                rawOut.write(("CONNECT " + host + ":443 HTTP/1.1\r\n"
                        + "Host: " + host + ":443\r\n"
                        + "Proxy-Authorization: Basic " + basicAuth(endpoint) + "\r\n"
                        + "\r\n").getBytes(UTF8));
                rawOut.flush();
                int connectStatus = readStatusLine(socket.getInputStream());
                if (connectStatus < 200 || connectStatus > 299) return -1;
                if (!readHeaders(socket.getInputStream())) return -1;
                SSLSocket secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, host, 443, true);
                applySni(secure, host);
                secure.setSoTimeout(timeoutMs);
                secure.startHandshake();
                if (!HttpsURLConnection.getDefaultHostnameVerifier()
                        .verify(host, secure.getSession())) return -1;
                request = "GET " + path + " HTTP/1.1\r\n"
                        + "Host: " + host + "\r\n"
                        + "Connection: close\r\n"
                        + (telegram ? "Range: bytes=0-0\r\n" : "")
                        + "\r\n";
                OutputStream secureOut = secure.getOutputStream();
                secureOut.write(request.getBytes(UTF8));
                secureOut.flush();
                input = secure.getInputStream();
            } else {
                int port = target.getPort() > 0 ? target.getPort() : 80;
                String hostHeader = port == 80 ? host : host + ":" + port;
                request = "GET http://" + hostHeader + path + " HTTP/1.1\r\n"
                        + "Host: " + hostHeader + "\r\n"
                        + "Proxy-Authorization: Basic " + basicAuth(endpoint) + "\r\n"
                        + "Accept: */*\r\n"
                        + "Connection: close\r\n"
                        + (telegram ? "Range: bytes=0-0\r\n" : "")
                        + "\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(request.getBytes(UTF8));
                out.flush();
                input = socket.getInputStream();
            }
            int status = readStatusLine(input);
            int latency = (int) (System.currentTimeMillis() - started);
            if (!isSuccess(status, telegram)) return -1;
            return latency;
        } catch (IOException e) {
            return -1;
        } catch (RuntimeException e) {
            return -1;
        } finally {
            if (sSocket == socket) sSocket = null;
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static String pathOf(URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty()) path = "/";
        String query = url.getQuery();
        if (query != null && !query.isEmpty()) path = path + "?" + query;
        return path;
    }

    private static boolean isSuccess(int status, boolean telegram) {
        if (telegram) {
            return status == 200 || status == 204 || (status >= 300 && status <= 399);
        }
        return status >= 200 && status <= 399;
    }

    private static int readStatusLine(InputStream input) throws IOException {
        String line = readLine(input);
        if (line == null) return -1;
        if (!line.startsWith("HTTP/")) return -1;
        int space = line.indexOf(' ');
        if (space < 0 || space + 4 > line.length()) return -1;
        try {
            return Integer.parseInt(line.substring(space + 1, space + 4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean readHeaders(InputStream input) throws IOException {
        for (int i = 0; i < MAX_HEADER_LINES; i++) {
            String line = readLine(input);
            if (line == null) return false;
            if (line.isEmpty()) return true;
        }
        return false;
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder text = new StringBuilder();
        while (true) {
            int value = input.read();
            if (value < 0) return text.length() == 0 ? null : text.toString();
            if (value == '\n') return text.toString();
            if (value == '\r') continue;
            if (text.length() >= STATUS_LINE_CAP) return null;
            text.append((char) value);
        }
    }

    private static String basicAuth(Endpoint endpoint) {
        String raw = (endpoint.user == null ? "" : endpoint.user) + ":"
                + (endpoint.pass == null ? "" : endpoint.pass);
        return Base64.encodeToString(raw.getBytes(UTF8), Base64.NO_WRAP);
    }

    private static void applySni(SSLSocket socket, String host) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        SniHelper.apply(socket, host);
    }

    private static boolean networkControl() {
        for (String url : CONTROL_URLS) {
            if (sCancelled) return false;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(CONTROL_TIMEOUT_MS);
                connection.setReadTimeout(CONTROL_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                int status = connection.getResponseCode();
                if (status > 0) return true;
            } catch (IOException ignored) {
            } catch (RuntimeException ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return false;
    }

    private static String connectionKind(Context ctx) {
        if (ctx == null) return null;
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return null;
            NetworkInfo info = manager.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return null;
            int type = info.getType();
            if (type == ConnectivityManager.TYPE_WIFI) return "wifi";
            if (type == ConnectivityManager.TYPE_MOBILE) return "mobile";
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(PROXY_HOST, 0), 1);
            return socket.getLocalPort();
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static String randomToken(int length) {
        StringBuilder token = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            token.append(ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)]);
        }
        return token.toString();
    }

    private static void writeText(File file, String text) throws IOException {
        File directory = file.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IOException("could not create the check folder");
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write((text == null ? "" : text).getBytes(UTF8));
        }
    }

    private static boolean isAlive(Process process) {
        if (process == null) return false;
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static void waitForExit(Process process, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (isAlive(process)) {
            if (System.currentTimeMillis() >= deadline) return;
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void post(Handler handler, Runnable task) {
        if (handler == null) {
            task.run();
            return;
        }
        if (!handler.post(task)) task.run();
    }

    private static final class Endpoint {
        final int port;
        final String user;
        final String pass;

        Endpoint(int port, String user, String pass) {
            this.port = port;
            this.user = user;
            this.pass = pass;
        }
    }

    private static final class TempCore {
        final File configFile;
        Process process;
        Endpoint endpoint;

        TempCore(File configFile) {
            this.configFile = configFile;
        }
    }

    private static final class SniHelper {
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

    private static final class StreamDrain implements Runnable {
        private final InputStream input;
        private final CountDownLatch latch;

        StreamDrain(InputStream input, CountDownLatch latch) {
            this.input = input;
            this.latch = latch;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("XRAYCLI_READY")) latch.countDown();
                }
            } catch (IOException ignored) {
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static final class ProgressTask implements Runnable {
        private final Listener listener;
        private final int done;
        private final int total;
        private final String id;

        ProgressTask(Listener listener, int done, int total, String id) {
            this.listener = listener;
            this.done = done;
            this.total = total;
            this.id = id;
        }

        @Override
        public void run() {
            listener.onProgress(done, total, id);
        }
    }

    private static final class ResultTask implements Runnable {
        private final Listener listener;
        private final String id;
        private final NapsterHealthStore.Health health;

        ResultTask(Listener listener, String id, NapsterHealthStore.Health health) {
            this.listener = listener;
            this.id = id;
            this.health = health;
        }

        @Override
        public void run() {
            listener.onResult(id, health);
        }
    }

    private static final class FinishedTask implements Runnable {
        private final Listener listener;

        FinishedTask(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void run() {
            listener.onFinished();
        }
    }
}
