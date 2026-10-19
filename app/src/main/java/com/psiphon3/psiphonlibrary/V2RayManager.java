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
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs the Xray core process and exposes it as an upstream HTTP proxy. */
public final class V2RayManager {

    private static final String TAG = "V2Ray";
    private static final Object LOCK = new Object();
    private static final String CORE_LIBRARY = "libxraycore.so";
    private static final long READY_TIMEOUT_MS = 20000L;
    private static final int MAX_MEMORY_LOG_LINES = 400;
    private static final int MAX_ERROR_LENGTH = 400;
    private static final String PING_URL = "https://www.gstatic.com/generate_204";
    private static final char[] ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final String[] DROPPED_FIELDS = {"inbounds", "dns", "routing", "observatory",
            "burstObservatory", "api", "stats", "policy", "reverse", "env", "fakedns", "metrics"};
    private static final SecureRandom RANDOM = new SecureRandom();

    private static Process sProcess;
    private static OutputStream sProcessStdin;
    private static CountDownLatch sReadyLatch;
    private static boolean sStarting;
    private static boolean sRunning;
    private static String sProxyUrl;
    private static final ArrayDeque<String> sLogLines = new ArrayDeque<>();

    private V2RayManager() { }

    public static final class V2RayException extends Exception {
        public V2RayException(String message) { super(message); }
        public V2RayException(String message, Throwable cause) { super(message, cause); }
    }

    public static boolean isRunning() {
        synchronized (LOCK) { return (sRunning && isProcessAlive(sProcess)); }
    }

    public static void start(Context context) throws V2RayException {
        Log.i(TAG, "start requested");
        CountDownLatch latch;
        synchronized (LOCK) {
            if (sRunning && isProcessAlive(sProcess)) return;
            latch = sStarting ? sReadyLatch : startLocked(context);
        }
        boolean ready = await(latch, READY_TIMEOUT_MS);
        synchronized (LOCK) {
            Log.i(TAG, "start ready=" + ready + " alive=" + isProcessAlive(sProcess));
            if (ready && sReadyLatch == latch && isProcessAlive(sProcess)) {
                sStarting = false; sRunning = true;
                return;
            }
            String tail = tailLocked(20);
            if (sReadyLatch != latch) {
                if (sRunning && isProcessAlive(sProcess)) return;
                throw new V2RayException("Xray did not start: " + tail);
            }
            stopLocked();
            Log.w(TAG, "Xray did not start: " + tail);
            throw new V2RayException("Xray did not start: " + tail);
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            try { stopLocked(); } catch (RuntimeException e) { Log.w(TAG, "Stop failed"); }
        }
    }

    public static String getUpstreamProxyUrl() {
        synchronized (LOCK) { return (sRunning && isProcessAlive(sProcess)) ? sProxyUrl : null; }
    }

    public static String convertShareLinks(Context context, String text) throws V2RayException {
        File input = new File(new File(new File(context.getFilesDir(), "v2ray"), "tmp"),
                "convert.txt");
        try { writeText(input, text == null ? "" : text); }
        catch (IOException e) { throw new V2RayException("could not write the input file", e); }
        CommandResult result;
        try { result = runCommand(new String[]{corePath(context), "convert",
                input.getAbsolutePath()}, 30000L); }
        catch (IOException e) { throw new V2RayException("could not run the Xray core", e); }
        finally {
            if (!input.delete() && input.exists()) {
                Log.w(TAG, "Could not delete the temporary share-link file");
            }
        }
        String output = lastJsonLine(result.stdout);
        if (!result.timedOut && result.exitCode == 0 && !output.isEmpty()) return output;
        Log.w(TAG, "Could not convert the V2Ray share links");
        throw new V2RayException(errorText(result, "could not convert the share links"));
    }

    public static String testConfig(Context context, String configBody) {
        String sanitized;
        try {
            sanitized = sanitizeConfig(configBody, 1, "v2ray-test", "v2ray-test");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "The V2Ray config was rejected");
            return e.getMessage() == null ? "invalid config" : e.getMessage();
        }
        try {
            JSONArray outbounds = new JSONObject(sanitized).getJSONArray("outbounds");
            JSONObject onlyOutbounds = new JSONObject().put("outbounds", outbounds);
            JSONObject test = new JSONObject().put("xrayJson", onlyOutbounds.toString())
                    .put("outboundTag", tagOf(outbounds.getJSONObject(0)));
            JSONObject request = new JSONObject().put("apiVersion", 3).put("method", "pingBatch")
                    .put("payload", new JSONObject().put("configs", new JSONArray().put(test))
                            .put("timeout", 8).put("url", PING_URL));
            File file = new File(new File(new File(context.getFilesDir(), "v2ray"), "tmp"),
                    "ping.json");
            writeText(file, request.toString());
            try {
                CommandResult result = runCommand(
                        new String[]{corePath(context), "invoke", file.getAbsolutePath()}, 25000L);
                if (result.timedOut || result.exitCode != 0) {
                    Log.w(TAG, "The V2Ray config test could not run");
                    return errorText(result, "could not run the config test");
                }
                return parseTestResponse(result.stdout);
            } finally {
                if (!file.delete() && file.exists()) {
                    Log.w(TAG, "Could not delete the temporary config test file");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "The V2Ray config test failed");
            return e.getMessage() == null ? "unexpected response" : e.getMessage();
        }
    }

    public static String getCoreVersion(Context context) throws V2RayException {
        CommandResult result;
        try { result = runCommand(new String[]{corePath(context), "version"}, 20000L); }
        catch (IOException e) { throw new V2RayException("could not run the Xray core", e); }
        String output = result.stdout.trim();
        if (!result.timedOut && result.exitCode == 0 && !output.isEmpty()) return output;
        Log.w(TAG, "Could not read the Xray core version");
        throw new V2RayException(errorText(result, "could not read the core version"));
    }

    public static String readLogTail(Context context, int maxLines) {
        if (maxLines <= 0) return "";
        File log = new File(new File(new File(context.getFilesDir(), "v2ray"), "logs"), "core.log");
        if (!log.isFile()) return "";
        ArrayDeque<String> lines = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(log), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= maxLines) lines.removeFirst();
                lines.addLast(line);
            }
        } catch (Exception e) { Log.w(TAG, "Could not read the Xray log"); }
        return joinLines(lines);
    }

    static String sanitizeConfig(String body, int port, String user, String pass) {
        JSONObject source;
        try { source = new JSONObject(body == null ? "" : body); }
        catch (JSONException e) { throw new IllegalArgumentException("invalid JSON", e); }
        try {
            String json = buildSanitized(source, port, user, pass).toString();
            new JSONObject(json);
            return json;
        } catch (JSONException e) { throw new IllegalArgumentException("invalid config", e); }
    }

    private static JSONObject buildSanitized(JSONObject source, int port, String user, String pass)
            throws JSONException {
        JSONArray sourceOutbounds = source.optJSONArray("outbounds");
        List<JSONObject> outbounds = new ArrayList<>();
        if (sourceOutbounds != null) for (int i = 0; i < sourceOutbounds.length(); i++) {
            JSONObject outbound = sourceOutbounds.optJSONObject(i);
            if (outbound != null) outbounds.add(outbound);
        }
        if (outbounds.isEmpty()) throw new IllegalArgumentException("no outbounds");
        JSONObject primary = null;
        for (JSONObject outbound : outbounds) {
            if ("proxy".equals(tagOf(outbound))) { primary = outbound; break; }
        }
        if (primary == null) for (JSONObject outbound : outbounds) {
            if (isBuiltinProtocol(outbound.optString("protocol", ""))) continue;
            primary = outbound; break;
        }
        if (primary == null) throw new IllegalArgumentException("no proxy outbound");

        String primaryTag = tagOf(primary);
        if (primaryTag.isEmpty() || isTagUsed(primaryTag, outbounds, primary)) {
            primaryTag = newTag(outbounds);
            primary.put("tag", primaryTag);
        }
        String inboundTag = "v2ray-local";
        int inboundTagSuffix = 2;
        while (isTagUsed(inboundTag, outbounds, null)) {
            inboundTag = "v2ray-local-" + inboundTagSuffix++;
        }
        for (JSONObject outbound : outbounds) {
            JSONObject stream = outbound.optJSONObject("streamSettings");
            JSONObject sockopt = stream == null ? null : stream.optJSONObject("sockopt");
            if (sockopt == null) continue;
            sockopt.remove("mark");
            sockopt.remove("interface");
            sockopt.remove("bindAddress");
        }

        JSONArray outputOutbounds = new JSONArray().put(primary);
        for (JSONObject outbound : outbounds) if (outbound != primary) outputOutbounds.put(outbound);
        JSONObject output = new JSONObject();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("outbounds".equals(key) || isDroppedField(key)) continue;
            output.put(key, source.get(key));
        }
        output.put("log", new JSONObject().put("loglevel", "info"));
        output.put("inbounds", new JSONArray().put(new JSONObject()
                .put("tag", inboundTag).put("listen", "127.0.0.1").put("port", port)
                .put("protocol", "http")
                .put("settings", new JSONObject()
                        .put("accounts", new JSONArray().put(new JSONObject()
                                .put("user", user).put("pass", pass)))
                        .put("allowTransparent", false))));
        output.put("routing", new JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", new JSONArray().put(new JSONObject()
                        .put("type", "field").put("network", "tcp,udp")
                        .put("outboundTag", primaryTag))));
        output.put("outbounds", outputOutbounds);
        return output;
    }

    private static String parseTestResponse(String stdout) {
        try {
            JSONObject response = new JSONObject(lastJsonLine(stdout));
            JSONObject data = response.optJSONObject("data");
            JSONArray results = data == null ? null : data.optJSONArray("results");
            JSONObject first = results == null || results.length() == 0
                    ? null : results.optJSONObject(0);
            if (response.optBoolean("success", false) && first != null
                    && first.optBoolean("success", false)) return null;
            String error = first == null ? "" : stringValue(first, "error");
            if (error.isEmpty()) error = stringValue(response, "error");
            return error.isEmpty() ? "unexpected response" : error;
        } catch (JSONException e) { return "unexpected response"; }
    }

    private static CountDownLatch startLocked(Context context) throws V2RayException {
        V2RayConfigStore.ConfigEntry entry = V2RayConfigStore.getActiveConfig(context);
        if (entry == null) throw new V2RayException("no active V2Ray config");
        String body = V2RayConfigStore.readConfigBody(context, entry.id);
        if (body == null) throw new V2RayException("no active V2Ray config");
        int port = findFreePort(); String user = randomToken(24); String pass = randomToken(24);
        Log.i(TAG, "active config=" + entry.id + " port=" + port);
        String json;
        try { json = sanitizeConfig(body, port, user, pass); }
        catch (IllegalArgumentException e) { throw new V2RayException("invalid V2Ray config: " + e.getMessage(), e); }
        File base = new File(context.getFilesDir(), "v2ray");
        File configFile = new File(new File(base, "run"), "config.json");
        File logFile = new File(new File(base, "logs"), "core.log");
        try {
            writeText(configFile, json);
            writeText(logFile, "Xray core started at "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n");
            ProcessBuilder builder = new ProcessBuilder(
                    corePath(context), "run", configFile.getAbsolutePath());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            sProcess = process;
            sProcessStdin = process.getOutputStream();
            sReadyLatch = new CountDownLatch(1);
            sStarting = true; sRunning = false;
            sProxyUrl = "http://" + user + ":" + pass + "@127.0.0.1:" + port;
            sLogLines.clear();
            Thread reader = new Thread(new LogReader(process, logFile), "V2RayLogReader");
            reader.setDaemon(true);
            reader.start();
            return sReadyLatch;
        } catch (IOException e) {
            stopLocked();
            throw new V2RayException("could not start the Xray core", e);
        }
    }

    private static void stopLocked() {
        Process process = sProcess;
        OutputStream stdin = sProcessStdin;
        CountDownLatch latch = sReadyLatch;
        sStarting = false; sRunning = false; sProxyUrl = null;
        if (latch != null) latch.countDown();
        if (stdin != null) { try { stdin.close(); } catch (IOException ignored) { } }
        if (process != null) {
            waitForExit(process, 3000L);
            if (isProcessAlive(process)) {
                try { process.destroy(); } catch (RuntimeException ignored) { }
                waitForExit(process, 2000L);
                if (isProcessAlive(process)) { killProcess(process); waitForExit(process, 1000L); }
            }
        }
        sProcess = null; sProcessStdin = null; sReadyLatch = null;
    }

    private static boolean await(CountDownLatch latch, long timeoutMs) {
        if (latch == null) return false;
        try { return latch.await(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    private static boolean isProcessAlive(Process process) {
        if (process == null) return false;
        try { process.exitValue(); return false; }
        catch (IllegalThreadStateException e) { return true; }
    }

    private static void waitForExit(Process process, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (isProcessAlive(process)) {
            if (System.currentTimeMillis() >= deadline) return;
            try { Thread.sleep(20L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static void killProcess(Process process) {
        try {
            process.destroy();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) process.destroyForcibly();
        } catch (RuntimeException ignored) { }
    }

    private static String tailLocked(int maxLines) {
        ArrayDeque<String> lines = new ArrayDeque<>(sLogLines);
        while (lines.size() > maxLines) lines.removeFirst();
        return joinLines(lines);
    }

    private static String joinLines(Iterable<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (text.length() > 0) text.append('\n');
            text.append(line);
        }
        return text.toString();
    }
    private static final class LogReader implements Runnable {
        private final Process process;
        private final File logFile;

        LogReader(Process process, File logFile) { this.process = process; this.logFile = logFile; }

        @Override
        public void run() {
            FileOutputStream log = null;
            try { log = new FileOutputStream(logFile, true); } catch (IOException ignored) { }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (log != null) {
                        try { log.write((line + "\n").getBytes("UTF-8")); log.flush(); }
                        catch (IOException ignored) { }
                    }
                    synchronized (LOCK) {
                        if (sLogLines.size() >= MAX_MEMORY_LOG_LINES) sLogLines.removeFirst();
                        sLogLines.addLast(line);
                        if (sProcess == process && sReadyLatch != null
                                && line.contains("XRAYCLI_READY")) {
                            sReadyLatch.countDown();
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (log != null) { try { log.close(); } catch (IOException ignored) { } }
                synchronized (LOCK) {
                    if (sProcess == process) {
                        if (sReadyLatch != null) sReadyLatch.countDown();
                        sProcess = null; sProcessStdin = null; sReadyLatch = null;
                        sStarting = false; sRunning = false; sProxyUrl = null;
                    }
                }
            }
        }
    }

    private static CommandResult runCommand(String[] command, long timeoutMs) throws IOException {
        Process process = new ProcessBuilder(command).start();
        StringBuffer stdout = new StringBuffer();
        StringBuffer stderr = new StringBuffer();
        Thread out = new Thread(() -> drain(process.getInputStream(), stdout), "V2Ray-stdout");
        Thread err = new Thread(() -> drain(process.getErrorStream(), stderr), "V2Ray-stderr");
        out.setDaemon(true); err.setDaemon(true);
        out.start(); err.start();
        waitForExit(process, timeoutMs);
        boolean timedOut = isProcessAlive(process);
        if (timedOut) { killProcess(process); waitForExit(process, 1000L); }
        int exitCode = -1;
        try { exitCode = process.exitValue(); } catch (IllegalThreadStateException ignored) { }
        try { out.join(2000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { err.join(2000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return new CommandResult(exitCode, timedOut, stdout.toString(), stderr.toString());
    }

    private static void drain(InputStream input, StringBuffer buffer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
            char[] chunk = new char[4096];
            int read;
            while ((read = reader.read(chunk)) != -1) buffer.append(chunk, 0, read);
        } catch (IOException ignored) { }    }

    private static final class CommandResult {
        final int exitCode;
        final boolean timedOut;
        final String stdout;
        final String stderr;

        CommandResult(int exitCode, boolean timedOut, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }
    }

    private static void writeText(File file, String text) throws IOException {
        File directory = file.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()
                && !directory.isDirectory()) throw new IOException("could not create folder");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write((text == null ? "" : text).getBytes("UTF-8"));
        }
    }

    private static String lastJsonLine(String output) {
        if (output == null) return "";
        String[] lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{")) return line;
        }
        return output.trim();
    }

    private static String errorText(CommandResult result, String fallback) {
        String error = result.stderr.trim();
        if (error.length() > MAX_ERROR_LENGTH) error = error.substring(0, MAX_ERROR_LENGTH);
        if (!error.isEmpty()) return error;
        return result.timedOut ? fallback + ": timed out"
                : fallback + " (exit " + result.exitCode + ")";
    }

    private static String stringValue(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }

    private static String tagOf(JSONObject outbound) {
        Object tag = outbound.opt("tag");
        return tag instanceof String ? (String) tag : "";
    }

    private static boolean isTagUsed(String tag, List<JSONObject> outbounds, JSONObject primary) {
        for (JSONObject outbound : outbounds) {
            if (outbound != primary && tag.equals(tagOf(outbound))) return true;
        }
        return false;
    }

    private static String newTag(List<JSONObject> outbounds) {
        if (!isTagUsed("proxy", outbounds, null)) return "proxy";
        if (!isTagUsed("v2ray-primary", outbounds, null)) return "v2ray-primary";
        int suffix = 2;
        while (isTagUsed("v2ray-primary-" + suffix, outbounds, null)) suffix++;
        return "v2ray-primary-" + suffix;
    }

    private static boolean isBuiltinProtocol(String protocol) {
        return "freedom".equals(protocol) || "blackhole".equals(protocol)
                || "dns".equals(protocol) || "loopback".equals(protocol);
    }

    private static boolean isDroppedField(String key) {
        for (String field : DROPPED_FIELDS) if (field.equals(key)) return true;
        return false;
    }

    private static int findFreePort() throws V2RayException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new V2RayException("could not find a free port", e);
        }
    }

    private static String randomToken(int length) {
        StringBuilder token = new StringBuilder(length);
        for (int i = 0; i < length; i++) token.append(ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)]);
        return token.toString();
    }

    private static String corePath(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, CORE_LIBRARY)
                .getAbsolutePath();
    }
}
