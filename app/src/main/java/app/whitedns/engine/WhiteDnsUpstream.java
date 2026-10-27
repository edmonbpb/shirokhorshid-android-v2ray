package app.whitedns.engine;

import android.content.Context;

import java.io.IOException;

import app.slipnet.util.AppLog;
import app.whitedns.model.WhiteDnsProfile;
import app.whitedns.store.WhiteDnsStore;

/**
 * Runs the saved WhiteDNS profile as the main tunnel's upstream proxy. The engine's local
 * listener speaks SOCKS5, which is the mode the process wrapper renders and proves, so the
 * tunnel core is pointed at it with a socks5 URL. Fail closed: no profile or no engine means no
 * URL, and the caller must not fall back to a direct connection. Never logs the profile key.
 */
public final class WhiteDnsUpstream {

    private static final String TAG = "WhiteDns";
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final String SCHEME = "socks5://";
    private static final long START_TIMEOUT_MS = 20000L;
    private static final Object LOCK = new Object();

    private static WhiteDnsProcess sProcess;
    private static String sProxyUrl;

    private WhiteDnsUpstream() {
    }

    /**
     * Starts the engine for the saved profile. Safe to call while it is already running. The
     * engine rules (Android 8 or newer, and a 64-bit device for SOAR and StormDNS) are enforced
     * by {@link WhiteDnsProcess#start}.
     */
    public static void start(Context ctx) throws IOException {
        if (ctx == null) {
            throw new IOException("missing context");
        }
        synchronized (LOCK) {
            if (sProcess != null) {
                return;
            }
            Context app = ctx.getApplicationContext();
            if (app == null) {
                app = ctx;
            }
            WhiteDnsProfile profile = WhiteDnsStore.load(app);
            if (profile == null) {
                throw new IOException("no WhiteDNS profile");
            }
            WhiteDnsProcess process = WhiteDnsProcess.start(app, profile, START_TIMEOUT_MS);
            sProcess = process;
            sProxyUrl = SCHEME + LOCAL_HOST + ":" + process.getPort();
            AppLog.i(TAG, "upstream ready on port " + process.getPort());
        }
    }

    /** The local proxy URL while the engine is running, else null. */
    public static String getProxyUrl() {
        synchronized (LOCK) {
            return sProcess == null ? null : sProxyUrl;
        }
    }

    public static boolean isRunning() {
        synchronized (LOCK) {
            return sProcess != null;
        }
    }

    /** Closes the engine once; safe to call from every stop path. */
    public static void stop() {
        WhiteDnsProcess process;
        synchronized (LOCK) {
            process = sProcess;
            sProcess = null;
            sProxyUrl = null;
        }
        if (process == null) {
            return;
        }
        try {
            process.close();
        } catch (RuntimeException e) {
            AppLog.w(TAG, "could not stop the upstream engine");
        }
    }
}
