package app.whitedns.engine;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import app.slipnet.util.AppLog;
import app.whitedns.model.WhiteDnsProfile;
import app.whitedns.util.WhiteDnsProfileParser;

/**
 * Runs the prebuilt engine binary for the profile's engine and exposes its local SOCKS5 port.
 * CottenDNS reads a TOML config plus a resolvers file; SOAR reads one JSON config. Readiness is
 * proven by our own SOCKS5 greeting, never by log text. The key is written to the private config
 * file only and is redacted from the log ring.
 */
public final class WhiteDnsProcess implements Closeable {

    private static final String TAG = "WhiteDns";
    private static final String BINARY_COTTEN = "libcottendns_client.so";
    private static final String BINARY_SOAR = "libsoar_client.so";
    private static final String BINARY_STORM = "libstormdns_client.so";
    private static final String RUN_DIR = "whitedns/run";
    private static final String CONFIG_TOML = "config.toml";
    private static final String CONFIG_JSON = "config.json";
    private static final String RESOLVERS_FILE = "resolvers.txt";
    private static final String REDACTED = "***";
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final String SOAR_UNSUPPORTED =
            "SOAR needs a 64-bit device on Android 8 or newer";
    private static final String STORM_UNSUPPORTED =
            "StormDNS needs a 64-bit device on Android 8 or newer";
    private static final int LOG_LINES = 400;
    private static final long POLL_MS = 100L;
    private static final int PROBE_TIMEOUT_MS = 1000;
    private static final long STOP_WAIT_MS = 2000L;
    private static final long FORCE_WAIT_MS = 1000L;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Process process;
    private final int port;
    private final String secret;
    private final File runDir;
    private final Thread reader;
    private final Object lock = new Object();
    private final List<String> tail = new ArrayList<String>();

    private volatile boolean stopped;

    private WhiteDnsProcess(Process process, int port, String secret, File runDir) {
        this.process = process;
        this.port = port;
        this.secret = secret == null ? "" : secret;
        this.runDir = runDir;
        this.reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "whitedns-reader");
        this.reader.setDaemon(true);
    }

    public static WhiteDnsProcess start(Context ctx, WhiteDnsProfile p, long timeoutMs)
            throws IOException {
        boolean soar = p != null && p.engine == WhiteDnsProfile.Engine.SOAR;
        boolean storm = p != null && p.engine == WhiteDnsProfile.Engine.STORM;
        if (Build.VERSION.SDK_INT < 26) {
            throw new IOException(soar || storm ? SOAR_UNSUPPORTED
                    : "WhiteDNS needs Android 8 or newer");
        }
        if (ctx == null || p == null) {
            throw new IOException("missing context or profile");
        }
        if ((soar || storm) && !hasArm64()) {
            throw new IOException(soar ? SOAR_UNSUPPORTED : STORM_UNSUPPORTED);
        }
        String binaryName = soar ? BINARY_SOAR : (storm ? BINARY_STORM : BINARY_COTTEN);
        String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        File binary = nativeDir == null ? null : new File(nativeDir, binaryName);
        if (binary == null || !binary.isFile()) {
            throw new IOException(binaryName + " not found");
        }
        File dir = new File(ctx.getFilesDir(), RUN_DIR);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("could not create the run folder");
        }

        int port = freePort();
        File config = new File(dir, soar ? CONFIG_JSON : CONFIG_TOML);
        File resolvers = soar ? null : new File(dir, RESOLVERS_FILE);
        deleteQuietly(new File(dir, soar ? CONFIG_TOML : CONFIG_JSON));
        try {
            writeText(config, p.toEngineConfig(port));
            if (resolvers != null) {
                writeText(resolvers, resolverText(p));
            }
        } catch (IOException e) {
            deleteQuietly(config);
            deleteQuietly(resolvers);
            throw new IOException("could not write the WhiteDNS config");
        }

        List<String> cmd = new ArrayList<String>();
        cmd.add(binary.getAbsolutePath());
        cmd.add("-config");
        cmd.add(config.getAbsolutePath());
        if (resolvers != null) {
            cmd.add("-resolvers");
            cmd.add(resolvers.getAbsolutePath());
        }
        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.directory(dir);
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            deleteQuietly(config);
            deleteQuietly(resolvers);
            throw new IOException("could not start the WhiteDNS engine");
        }

        WhiteDnsProcess engine = new WhiteDnsProcess(process, port, p.key, dir);
        engine.reader.start();
        AppLog.d(TAG, "start engine=" + p.engine.name() + " port=" + port + " mode="
                + p.mode.name() + " resolvers="
                + WhiteDnsProfileParser.parseResolvers(p.resolvers).size());
        try {
            engine.awaitReady(timeoutMs);
            AppLog.i(TAG, "ready on " + LOCAL_HOST + ":" + port);
            return engine;
        } catch (IOException e) {
            engine.close();
            throw e;
        }
    }

    public int getPort() {
        return port;
    }

    /** Oldest first, joined with newlines; lines containing the key were already redacted. */
    public String logTail(int lines) {
        synchronized (lock) {
            int wanted = Math.min(Math.max(0, lines), tail.size());
            int start = tail.size() - wanted;
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < tail.size(); i++) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tail.get(i));
            }
            return sb.toString();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (stopped) {
                return;
            }
            stopped = true;
        }
        AppLog.w(TAG, "stop");
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        process.destroy();
        waitFor(STOP_WAIT_MS);
        if (Build.VERSION.SDK_INT >= 26) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }
        waitFor(FORCE_WAIT_MS);
        reader.interrupt();
        deleteQuietly(new File(runDir, CONFIG_TOML));
        deleteQuietly(new File(runDir, CONFIG_JSON));
        deleteQuietly(new File(runDir, RESOLVERS_FILE));
    }

    /** SOAR ships for arm64 only, so a 32-bit device must be turned away before the spawn. */
    private static boolean hasArm64() {
        String[] abis = Build.SUPPORTED_64_BIT_ABIS;
        if (abis == null) {
            return false;
        }
        for (String abi : abis) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    private void awaitReady(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (stopped) {
                throw new IOException("the engine was stopped");
            }
            if (!isAlive(process)) {
                throw new IOException("the engine exited before it was ready");
            }
            if (probe(port)) {
                return;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for the engine");
            }
        }
        throw new IOException("the engine did not open its port in time");
    }

    /** SOCKS5 greeting: VER 5, one method, no auth, expect 05 00. */
    private static boolean probe(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(LOCAL_HOST, port), PROBE_TIMEOUT_MS);
            socket.setSoTimeout(PROBE_TIMEOUT_MS);
            socket.getOutputStream().write(new byte[]{5, 1, 0});
            socket.getOutputStream().flush();
            byte[] reply = new byte[2];
            return readFully(socket, reply) && reply[0] == 5 && reply[1] == 0;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    private static boolean readFully(Socket socket, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int count = socket.getInputStream().read(buffer, read, buffer.length - read);
            if (count < 0) {
                return false;
            }
            read += count;
        }
        return true;
    }

    private void readLoop() {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF8));
            String line;
            while ((line = in.readLine()) != null) {
                append(redact(line));
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(in);
        }
    }

    private void append(String line) {
        synchronized (lock) {
            tail.add(line);
            while (tail.size() > LOG_LINES) {
                tail.remove(0);
            }
        }
    }

    private String redact(String line) {
        if (secret.length() == 0 || line == null) {
            return line == null ? "" : line;
        }
        return line.replace(secret, REDACTED);
    }

    private static String resolverText(WhiteDnsProfile p) {
        List<String> resolvers = WhiteDnsProfileParser.parseResolvers(p.resolvers);
        StringBuilder sb = new StringBuilder();
        for (String resolver : resolvers) {
            sb.append(resolver).append('\n');
        }
        return sb.toString();
    }

    private static void writeText(File file, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(file, false);
        try {
            out.write(text.getBytes(UTF8));
        } finally {
            closeQuietly(out);
        }
    }

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            closeQuietly(socket);
        }
    }

    private static void waitFor(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean isAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        if (file.exists() && !file.delete() && file.exists()) {
            AppLog.w(TAG, "could not delete " + file.getName());
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }
}
