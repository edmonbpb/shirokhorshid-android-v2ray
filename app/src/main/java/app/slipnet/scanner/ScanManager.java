// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.
package app.slipnet.scanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.slipnet.util.AppLog;
import app.whitedns.model.WhiteDnsProfile;

/** Owns the single in-process scan: sequencing, cancellation, wake lock and persistence. */
public final class ScanManager {

    public enum Mode { SIMPLE, DNS, PRISM, E2E }

    public interface Observer {
        void onState(ScanState state);
    }

    public static final String PREFS = "slipnet_scan";
    public static final String KEY_WORKING_IPS = "working_ips";
    public static final String KEY_SAVED_POOL = "slipnet_pool";
    public static final String KEY_SAVED_SESSION = "saved_session";

    private static final String TAG = "ScanManager";
    private static final String SETTING = "setting.";
    private static final String WAKE_LOCK_TAG = "slipnet:scan";
    private static final long WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L;
    private static final long WAIT_SLICE_MS = 500L;
    private static final ScanManager INSTANCE = new ScanManager();

    private final Object lock = new Object();
    private final List<Observer> observers = new ArrayList<Observer>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean notifyScheduled = new AtomicBoolean(false);
    private final ScanState state = new ScanState();

    private volatile Context appContext;
    private long generation;
    private boolean cancelled;
    private boolean restored;
    private Thread worker;
    private volatile PowerManager.WakeLock wakeLock;

    private ScanManager() {
    }

    public static ScanManager get() {
        return INSTANCE;
    }

    /** Optional early binding; otherwise the application context is resolved lazily. */
    public void init(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        appContext = app == null ? context : app;
        restoreSession();
    }

    public void addObserver(Observer observer) {
        if (observer == null) {
            return;
        }
        synchronized (lock) {
            if (!observers.contains(observer)) {
                observers.add(observer);
            }
        }
    }

    public void removeObserver(Observer observer) {
        synchronized (lock) {
            observers.remove(observer);
        }
    }

    /** Copy of the live state. */
    public ScanState snapshot() {
        restoreSession();
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    public void startDns(List<String> hosts, int port, String domain, int timeoutMs,
                         int concurrency, int querySize, boolean both) {
        List<String> list = sanitize(hosts);
        if (list.isEmpty()) {
            AppLog.w(TAG, "startDns: no hosts");
            return;
        }
        SharedPreferences.Editor edit = editor();
        putString(edit, "mode", Mode.DNS.name());
        putInt(edit, "port", port);
        putString(edit, "domain", domain);
        putInt(edit, "timeout", timeoutMs);
        putInt(edit, "concurrency", concurrency);
        putInt(edit, "querySize", querySize);
        putBoolean(edit, "both", both);
        apply(edit);
        if (!begin(Mode.DNS, list.size())) {
            return;
        }
        final long gen = currentGeneration();
        final List<String> target = list;
        final int dnsPort = port;
        final String testDomain = domain;
        final int dnsTimeout = timeoutMs;
        final int workers = concurrency;
        final int size = querySize;
        final boolean transports = both;
        startWorker(new Runnable() {
            @Override
            public void run() {
                runDns(gen, target, dnsPort, testDomain, dnsTimeout, workers, size, transports);
            }
        });
    }

    public void startPrism(List<String> hosts, int port, String domain, String pubkeyHex,
                           int timeoutMs, int concurrency, int probes, int threshold,
                           int responseSize, boolean prefilter, int prefilterTimeout) {
        List<String> list = sanitize(hosts);
        if (list.isEmpty()) {
            AppLog.w(TAG, "startPrism: no hosts");
            return;
        }
        SharedPreferences.Editor edit = editor();
        putString(edit, "mode", Mode.PRISM.name());
        putInt(edit, "port", port);
        putString(edit, "domain", domain);
        putInt(edit, "timeout", timeoutMs);
        putInt(edit, "concurrency", concurrency);
        putInt(edit, "probes", probes);
        putInt(edit, "threshold", threshold);
        putInt(edit, "responseSize", responseSize);
        putBoolean(edit, "prefilter", prefilter);
        putInt(edit, "prefilterTimeout", prefilterTimeout);
        apply(edit);
        if (!begin(Mode.PRISM, list.size())) {
            return;
        }
        final long gen = currentGeneration();
        final List<String> target = list;
        final int prismPort = port;
        final String testDomain = domain;
        final String pubkey = pubkeyHex;
        final int prismTimeout = timeoutMs;
        final int workers = concurrency;
        final int probeCount = probes;
        final int passThreshold = threshold;
        final int size = responseSize;
        final boolean filter = prefilter;
        final int filterTimeout = prefilterTimeout;
        startWorker(new Runnable() {
            @Override
            public void run() {
                runPrism(gen, target, prismPort, testDomain, pubkey, prismTimeout, workers,
                        probeCount, passThreshold, size, filter, filterTimeout);
            }
        });
    }

    public void startE2e(List<String> hosts, int port, WhiteDnsProfile base, int timeoutMs,
                         int concurrency) {
        List<String> list = sanitize(hosts);
        if (list.isEmpty()) {
            AppLog.w(TAG, "startE2e: no hosts");
            return;
        }
        if (base == null) {
            AppLog.w(TAG, "startE2e: no profile");
            return;
        }
        SharedPreferences.Editor edit = editor();
        putString(edit, "mode", Mode.E2E.name());
        putInt(edit, "port", port);
        putInt(edit, "timeout", timeoutMs);
        putInt(edit, "concurrency", concurrency);
        apply(edit);
        if (!begin(Mode.E2E, list.size())) {
            return;
        }
        final long gen = currentGeneration();
        final List<String> target = list;
        final int e2ePort = port;
        final WhiteDnsProfile profile = base;
        final int e2eTimeout = timeoutMs;
        final int workers = concurrency;
        startWorker(new Runnable() {
            @Override
            public void run() {
                runE2e(gen, target, e2ePort, profile, e2eTimeout, workers, false);
            }
        });
    }

    public void startSimple(List<String> hosts, int port, String domain, WhiteDnsProfile base,
                            int timeoutMs, int concurrency, int querySize, boolean both,
                            int minScore, int e2eTimeoutMs, int e2eConcurrency) {
        List<String> list = sanitize(hosts);
        if (list.isEmpty()) {
            AppLog.w(TAG, "startSimple: no hosts");
            return;
        }
        if (base == null) {
            AppLog.w(TAG, "startSimple: no profile");
            return;
        }
        SharedPreferences.Editor edit = editor();
        putString(edit, "mode", Mode.SIMPLE.name());
        putInt(edit, "port", port);
        putString(edit, "domain", domain);
        putInt(edit, "timeout", timeoutMs);
        putInt(edit, "concurrency", concurrency);
        putInt(edit, "querySize", querySize);
        putBoolean(edit, "both", both);
        putInt(edit, "minScore", minScore);
        putInt(edit, "e2eTimeout", e2eTimeoutMs);
        putInt(edit, "e2eConcurrency", e2eConcurrency);
        apply(edit);
        if (!begin(Mode.SIMPLE, list.size())) {
            return;
        }
        final long gen = currentGeneration();
        final List<String> target = list;
        final int scanPort = port;
        final String testDomain = domain;
        final WhiteDnsProfile profile = base;
        final int dnsTimeout = timeoutMs;
        final int workers = concurrency;
        final int size = querySize;
        final boolean transports = both;
        final int score = minScore;
        final int e2eTimeout = e2eTimeoutMs;
        final int e2eWorkers = e2eConcurrency;
        startWorker(new Runnable() {
            @Override
            public void run() {
                runSimple(gen, target, scanPort, testDomain, profile, dnsTimeout, workers, size,
                        transports, score, e2eTimeout, e2eWorkers);
            }
        });
    }

    /** Cancels the running scanner and E2E batch; partial results stay in the state. */
    public void stop() {
        boolean active;
        synchronized (lock) {
            active = state.running;
            cancelled = true;
            generation++;
            state.generation = generation;
            state.running = false;
        }
        AppLog.w(TAG, "stop");
        try {
            ResolverScanner.cancelAll();
        } catch (Throwable t) {
            AppLog.e(TAG, "cancel failed: " + t);
        }
        if (active) {
            persistWorkingIps();
            persistSession();
        }
        notifyObservers();
    }

    public void savePool(List<String> hosts) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        prefs.edit().putString(KEY_SAVED_POOL, join(sanitize(hosts))).apply();
    }

    public List<String> loadPool() {
        SharedPreferences prefs = prefs();
        List<String> hosts = new ArrayList<String>();
        if (prefs == null) {
            return hosts;
        }
        String raw = prefs.getString(KEY_SAVED_POOL, "");
        if (raw == null) {
            return hosts;
        }
        String[] lines = raw.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String host = lines[i].trim();
            if (host.length() > 0) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    /** Hosts persisted by the last finished scan; empty when nothing was saved yet. */
    public List<String> loadLastWorkingIps() {
        SharedPreferences prefs = prefs();
        List<String> hosts = new ArrayList<String>();
        if (prefs == null) {
            return hosts;
        }
        String raw = prefs.getString(KEY_WORKING_IPS, "");
        if (raw == null) {
            return hosts;
        }
        String[] lines = raw.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String host = lines[i].trim();
            if (host.length() > 0) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    /** True when a finished scan is stored for the next app start. */
    public boolean hasSavedSession() {
        String raw = readSavedSession();
        return raw != null && raw.length() > 0;
    }

    public void clearSavedSession() {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().remove(KEY_SAVED_SESSION).apply();
        }
    }

    // Session format: line 1 = mode|total|done|passed|phase, then one line per result.
    private void persistSession() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        String payload;
        synchronized (lock) {
            payload = buildSessionLocked();
        }
        SharedPreferences.Editor edit = prefs.edit();
        if (payload == null) {
            edit.remove(KEY_SAVED_SESSION);
        } else {
            edit.putString(KEY_SAVED_SESSION, payload);
        }
        edit.apply();
    }

    private String buildSessionLocked() {
        if (state.mode == null || state.results.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(token(state.mode)).append('|')
                .append(state.total).append('|')
                .append(state.done).append('|')
                .append(state.passed).append('|')
                .append(token(state.phase));
        for (int i = 0; i < state.results.size(); i++) {
            ScanResult result = state.results.get(i);
            if (result == null) {
                continue;
            }
            DnsTunnelResult tunnel = result.tunnel;
            E2eOutcome e2e = result.e2e;
            builder.append('\n')
                    .append(result.host == null ? "" : result.host).append('|')
                    .append(result.port).append('|')
                    .append(result.status == null ? ScanResult.Status.ERROR.name()
                            : result.status.name()).append('|')
                    .append(result.responseTimeMs).append('|')
                    .append(tunnel != null && tunnel.nsSupport ? 1 : 0).append('|')
                    .append(tunnel != null && tunnel.txtSupport ? 1 : 0).append('|')
                    .append(tunnel != null && tunnel.randomSubdomain ? 1 : 0).append('|')
                    .append(tunnel != null && tunnel.tunnelRealism ? 1 : 0).append('|')
                    .append(tunnel != null && tunnel.edns0Support ? 1 : 0).append('|')
                    .append(tunnel != null && tunnel.nxdomainCorrect ? 1 : 0).append('|')
                    .append(tunnel == null ? 0 : tunnel.ednsMaxPayload).append('|')
                    .append(result.prismPassed == null ? "" : result.prismPassed.toString()).append('|')
                    .append(result.prismTotal == null ? "" : result.prismTotal.toString()).append('|')
                    .append(e2e != null && e2e.success ? 1 : 0).append('|')
                    .append(e2e == null ? 0L : e2e.totalMs).append('|')
                    .append(e2e == null ? 0 : e2e.httpStatus).append('|')
                    .append(token(result.error));
        }
        return builder.toString();
    }

    private void restoreSession() {
        synchronized (lock) {
            if (restored || state.running || !state.results.isEmpty()) {
                return;
            }
            if (prefs() == null) {
                return;
            }
            restored = true;
            String raw = readSavedSession();
            if (raw == null || raw.length() == 0) {
                return;
            }
            String[] lines = raw.split("\\n");
            String[] meta = lines.length == 0 ? new String[0] : lines[0].split("\\|", -1);
            if (meta.length < 5 || meta[0].length() == 0) {
                return;
            }
            state.mode = meta[0];
            state.total = parseInt(meta[1], 0);
            state.done = parseInt(meta[2], 0);
            state.passed = parseInt(meta[3], 0);
            state.phase = meta[4];
            state.running = false;
            for (int i = 1; i < lines.length; i++) {
                ScanResult result = parseSessionResult(lines[i]);
                if (result != null) {
                    state.results.add(result);
                }
            }
        }
    }

    private static ScanResult parseSessionResult(String line) {
        if (line == null || line.length() == 0) {
            return null;
        }
        String[] parts = line.split("\\|", -1);
        if (parts.length < 17) {
            return null;
        }
        ScanResult result = new ScanResult(parts[0], parseInt(parts[1], 0));
        try {
            result.status = ScanResult.Status.valueOf(parts[2]);
        } catch (IllegalArgumentException e) {
            result.status = ScanResult.Status.ERROR;
        }
        result.responseTimeMs = parseLong(parts[3], -1L);
        boolean[] flags = new boolean[6];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = "1".equals(parts[4 + i]);
        }
        int edns = parseInt(parts[10], 0);
        if (edns > 0 || flags[0] || flags[1] || flags[2] || flags[3] || flags[4] || flags[5]) {
            DnsTunnelResult tunnel = new DnsTunnelResult();
            tunnel.nsSupport = flags[0];
            tunnel.txtSupport = flags[1];
            tunnel.randomSubdomain = flags[2];
            tunnel.tunnelRealism = flags[3];
            tunnel.edns0Support = flags[4];
            tunnel.nxdomainCorrect = flags[5];
            tunnel.ednsMaxPayload = edns;
            result.tunnel = tunnel;
        }
        result.prismPassed = parseIntOrNull(parts[11]);
        result.prismTotal = parseIntOrNull(parts[12]);
        String error = parts[16];
        if (error.length() > 0) {
            result.error = error;
        }
        boolean e2eOk = "1".equals(parts[13]);
        long e2eMs = parseLong(parts[14], 0L);
        int http = parseInt(parts[15], 0);
        if (e2eOk || e2eMs > 0L || http > 0) {
            E2eOutcome outcome = new E2eOutcome();
            outcome.success = e2eOk;
            outcome.totalMs = e2eMs;
            outcome.httpStatus = http;
            outcome.error = e2eOk || error.length() == 0 ? null : error;
            outcome.phase = e2eOk ? "DONE" : null;
            result.e2e = outcome;
        }
        return result;
    }

    private String readSavedSession() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return null;
        }
        try {
            return prefs.getString(KEY_SAVED_SESSION, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String token(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null || value.length() == 0) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean begin(Mode mode, int total) {
        synchronized (lock) {
            if (state.running || (worker != null && worker.isAlive())) {
                AppLog.w(TAG, "busy");
                return false;
            }
            cancelled = false;
            generation++;
            state.generation = generation;
            state.running = true;
            state.mode = mode.name();
            state.total = total;
            state.done = 0;
            state.passed = 0;
            state.phase = "";
            state.transparentProxy = false;
            state.results.clear();
            restored = true;
        }
        AppLog.d(TAG, "start mode=" + mode + " hosts=" + total);
        clearSavedSession();
        notifyObservers();
        return true;
    }

    private long currentGeneration() {
        synchronized (lock) {
            return generation;
        }
    }

    private void startWorker(final Runnable body) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acquireWakeLock();
                try {
                    body.run();
                } catch (Throwable t) {
                    AppLog.e(TAG, "scan failed: " + t);
                } finally {
                    releaseWakeLock();
                    complete();
                }
            }
        }, "SlipNetScan");
        thread.setDaemon(true);
        synchronized (lock) {
            worker = thread;
        }
        thread.start();
    }

    private void complete() {
        persistWorkingIps();
        boolean wasRunning;
        synchronized (lock) {
            wasRunning = state.running;
            state.running = false;
            worker = null;
        }
        persistSession();
        if (wasRunning) {
            AppLog.i(TAG, "scan finished");
            notifyObservers();
        }
    }

    private void runDns(long gen, List<String> hosts, int port, String domain, int timeoutMs,
                        int concurrency, int querySize, boolean both) {
        final CountDownLatch done = new CountDownLatch(1);
        ResolverScanner.scanDns(hosts, port, domain, timeoutMs, concurrency, querySize, both,
                dnsListener(gen, done));
        await(gen, done);
    }

    private void runPrism(long gen, List<String> hosts, int port, String domain, String pubkey,
                          int timeoutMs, int concurrency, int probes, int threshold,
                          int responseSize, boolean prefilter, int prefilterTimeout) {
        boolean transparent = false;
        if (domain != null && domain.trim().length() > 0) {
            try {
                transparent = PrismVerifier.detectTransparentProxy(domain,
                        Math.min(timeoutMs, 2000));
            } catch (Throwable t) {
                AppLog.w(TAG, "transparent proxy probe failed: " + t);
            }
        }
        synchronized (lock) {
            if (cancelled || gen != generation) {
                return;
            }
            state.transparentProxy = transparent;
        }
        notifyObservers();
        final CountDownLatch done = new CountDownLatch(1);
        ResolverScanner.scanPrism(hosts, port, domain, pubkey, timeoutMs, concurrency, probes,
                threshold, responseSize, prefilter, prefilterTimeout,
                prismListener(gen, done, threshold));
        await(gen, done);
    }

    private void runE2e(long gen, List<String> hosts, int port, WhiteDnsProfile base,
                        int timeoutMs, int concurrency, boolean merge) {
        final CountDownLatch done = new CountDownLatch(1);
        ResolverScanner.scanE2e(hosts, port, base, timeoutMs, concurrency,
                e2eListener(gen, done, merge));
        await(gen, done);
    }

    private void runSimple(long gen, List<String> hosts, int port, String domain,
                           WhiteDnsProfile base, int timeoutMs, int concurrency, int querySize,
                           boolean both, int minScore, int e2eTimeoutMs, int e2eConcurrency) {
        runDns(gen, hosts, port, domain, timeoutMs, concurrency, querySize, both);
        List<String> candidates = new ArrayList<String>();
        synchronized (lock) {
            if (cancelled || gen != generation) {
                return;
            }
            List<ScanResult> kept = new ArrayList<ScanResult>();
            for (int i = 0; i < state.results.size(); i++) {
                ScanResult result = state.results.get(i);
                if (result != null && result.tunnel != null
                        && result.tunnel.score() >= minScore) {
                    kept.add(result);
                    if (result.host != null) {
                        candidates.add(result.host);
                    }
                }
            }
            state.results.clear();
            state.results.addAll(kept);
            state.passed = 0;
            state.done = 0;
            state.total = kept.size();
            state.phase = "E2E 0/" + kept.size();
        }
        notifyObservers();
        if (candidates.isEmpty()) {
            AppLog.i(TAG, "no resolver reached score " + minScore);
            return;
        }
        runE2e(gen, candidates, port, base, e2eTimeoutMs, e2eConcurrency, true);
    }

    private ResolverScanner.Listener dnsListener(final long gen, final CountDownLatch done) {
        return new ResolverScanner.Listener() {
            @Override
            public void onResult(ScanResult result) {
                synchronized (lock) {
                    if (result == null || cancelled || gen != generation) {
                        return;
                    }
                    state.results.add(result);
                    state.passed = countPassedLocked(Mode.DNS, 0);
                }
                notifyObservers();
            }

            @Override
            public void onProgress(int doneCount, int totalCount) {
                synchronized (lock) {
                    if (cancelled || gen != generation) {
                        return;
                    }
                    state.done = doneCount;
                    if (totalCount > 0) {
                        state.total = totalCount;
                    }
                    state.phase = "DNS " + doneCount + "/" + totalCount;
                }
                notifyObservers();
            }

            @Override
            public void onFinished(boolean wasCancelled) {
                done.countDown();
            }
        };
    }

    private ResolverScanner.Listener prismListener(final long gen, final CountDownLatch done,
                                                   final int threshold) {
        return new ResolverScanner.Listener() {
            @Override
            public void onResult(ScanResult result) {
                synchronized (lock) {
                    if (result == null || cancelled || gen != generation) {
                        return;
                    }
                    state.results.add(result);
                    state.passed = countPassedLocked(Mode.PRISM, threshold);
                }
                notifyObservers();
            }

            @Override
            public void onProgress(int doneCount, int totalCount) {
                synchronized (lock) {
                    if (cancelled || gen != generation) {
                        return;
                    }
                    state.done = doneCount;
                    if (totalCount > 0) {
                        state.total = totalCount;
                    }
                    state.phase = "PRISM " + doneCount + "/" + totalCount;
                }
                notifyObservers();
            }

            @Override
            public void onFinished(boolean wasCancelled) {
                done.countDown();
            }
        };
    }

    private ResolverScanner.Listener e2eListener(final long gen, final CountDownLatch done,
                                                 final boolean merge) {
        return new ResolverScanner.Listener() {
            @Override
            public void onResult(ScanResult result) {
                synchronized (lock) {
                    if (result == null || cancelled || gen != generation) {
                        return;
                    }
                    if (merge) {
                        attachE2eLocked(result);
                    } else {
                        state.results.add(result);
                    }
                    state.passed = countPassedLocked(Mode.E2E, 0);
                }
                notifyObservers();
            }

            @Override
            public void onProgress(int doneCount, int totalCount) {
                synchronized (lock) {
                    if (cancelled || gen != generation) {
                        return;
                    }
                    state.done = doneCount;
                    if (totalCount > 0) {
                        state.total = totalCount;
                    }
                    state.phase = "E2E " + doneCount + "/" + totalCount;
                }
                notifyObservers();
            }

            @Override
            public void onFinished(boolean wasCancelled) {
                done.countDown();
            }
        };
    }

    private void attachE2eLocked(ScanResult result) {
        String key = result.key();
        for (int i = 0; i < state.results.size(); i++) {
            ScanResult existing = state.results.get(i);
            if (existing != null && key != null && key.equals(existing.key())) {
                existing.e2e = result.e2e;
                return;
            }
        }
        state.results.add(result);
    }

    private int countPassedLocked(Mode mode, int threshold) {
        int count = 0;
        for (int i = 0; i < state.results.size(); i++) {
            if (isPassed(state.results.get(i), mode, threshold)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isPassed(ScanResult result, Mode mode, int threshold) {
        if (result == null) {
            return false;
        }
        switch (mode) {
            case PRISM:
                return result.prismPassed != null && result.prismPassed >= threshold;
            case E2E:
                return result.e2e != null && result.e2e.success;
            default:
                return result.status == ScanResult.Status.WORKING;
        }
    }

    private boolean await(long gen, CountDownLatch done) {
        while (true) {
            try {
                if (done.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            synchronized (lock) {
                if (cancelled || gen != generation) {
                    return false;
                }
            }
        }
    }

    private void notifyObservers() {
        if (!notifyScheduled.compareAndSet(false, true)) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                notifyScheduled.set(false);
                ScanState copy = snapshot();
                List<Observer> targets;
                synchronized (lock) {
                    if (observers.isEmpty()) {
                        return;
                    }
                    targets = new ArrayList<Observer>(observers);
                }
                for (int i = 0; i < targets.size(); i++) {
                    try {
                        targets.get(i).onState(copy);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        });
    }

    private ScanState snapshotLocked() {
        ScanState copy = new ScanState();
        copy.running = state.running;
        copy.mode = state.mode;
        copy.total = state.total;
        copy.done = state.done;
        copy.passed = state.passed;
        copy.phase = state.phase;
        copy.transparentProxy = state.transparentProxy;
        copy.results.addAll(state.results);
        copy.generation = state.generation;
        return copy;
    }

    private void persistWorkingIps() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        List<String> working = new ArrayList<String>();
        synchronized (lock) {
            for (int i = 0; i < state.results.size(); i++) {
                ScanResult result = state.results.get(i);
                if (result != null && result.host != null && isWorking(result)) {
                    working.add(result.host);
                }
            }
        }
        prefs.edit().putString(KEY_WORKING_IPS, join(working)).apply();
    }

    private static boolean isWorking(ScanResult result) {
        if (result.status == ScanResult.Status.WORKING) {
            return true;
        }
        if (result.e2e != null && result.e2e.success) {
            return true;
        }
        return result.prismPassed != null && result.prismPassed > 0;
    }

    private void acquireWakeLock() {
        Context context = context();
        if (context == null) {
            AppLog.w(TAG, "wake lock skipped: no context");
            return;
        }
        try {
            PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (manager == null) {
                return;
            }
            PowerManager.WakeLock lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG);
            lock.setReferenceCounted(false);
            lock.acquire(WAKE_LOCK_TIMEOUT_MS);
            wakeLock = lock;
        } catch (RuntimeException e) {
            AppLog.w(TAG, "wake lock failed: " + e);
        }
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock lock = wakeLock;
        wakeLock = null;
        if (lock == null) {
            return;
        }
        try {
            if (lock.isHeld()) {
                lock.release();
            }
        } catch (RuntimeException ignored) {
        }
    }

    // This app exposes no global Application accessor; fall back to the process-wide instance.
    private Context context() {
        Context context = appContext;
        if (context != null) {
            return context;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                Context found = ((Context) app).getApplicationContext();
                context = found == null ? (Context) app : found;
                appContext = context;
            }
        } catch (Throwable ignored) {
        }
        return context;
    }

    private SharedPreferences prefs() {
        Context context = context();
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SharedPreferences.Editor editor() {
        SharedPreferences prefs = prefs();
        return prefs == null ? null : prefs.edit();
    }

    private static void putInt(SharedPreferences.Editor edit, String key, int value) {
        if (edit != null) {
            edit.putInt(SETTING + key, value);
        }
    }

    private static void putString(SharedPreferences.Editor edit, String key, String value) {
        if (edit != null) {
            edit.putString(SETTING + key, value == null ? "" : value);
        }
    }

    private static void putBoolean(SharedPreferences.Editor edit, String key, boolean value) {
        if (edit != null) {
            edit.putBoolean(SETTING + key, value);
        }
    }

    private static void apply(SharedPreferences.Editor edit) {
        if (edit != null) {
            edit.apply();
        }
    }

    private static List<String> sanitize(List<String> hosts) {
        Set<String> unique = new LinkedHashSet<String>();
        if (hosts != null) {
            for (int i = 0; i < hosts.size(); i++) {
                String host = hosts.get(i);
                if (host == null) {
                    continue;
                }
                String trimmed = host.trim();
                if (trimmed.length() > 0) {
                    unique.add(trimmed);
                }
            }
        }
        return new ArrayList<String>(unique);
    }

    private static String join(List<String> hosts) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < hosts.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(hosts.get(i));
        }
        return builder.toString();
    }
}
