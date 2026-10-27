// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.
package app.slipnet.scanner;

import android.content.Context;
import android.os.SystemClock;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import app.whitedns.engine.WhiteDnsTester;
import app.whitedns.model.WhiteDnsProfile;
import app.whitedns.store.WhiteDnsStore;

/** DNS / end-to-end resolver scanning with cancellable background batches. */
public final class ResolverScanner {

    public interface Listener {
        void onResult(ScanResult result);

        void onProgress(int done, int total);

        void onFinished(boolean cancelled);
    }

    private static final int MAX_E2E_CONCURRENCY = 10;
    private static final int MAX_RANDOM_BYTES = 130;
    private static final char[] SUBDOMAIN = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object ACTIVE_LOCK = new Object();
    private static final List<Batch> ACTIVE = new ArrayList<Batch>();
    // WhiteDnsProcess keeps its config in one shared run folder, so only one engine may run at a
    // time. Candidates still queue in parallel; the engine starts are serialized here.
    private static final Object PROBE_LOCK = new Object();
    private static volatile Context appContext;

    private ResolverScanner() {
    }

    /**
     * Optional early binding for E2E: the contract batch signatures carry no Context. Resolution
     * falls back to the process-wide Application, and applyResolvers() captures it too.
     */
    public static void init(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        appContext = app == null ? context : app;
    }

    public static DnsTunnelResult scanDns(String host, int port, String testDomain,
                                          int timeoutMs, int querySize, boolean bothTransports) {
        if (host == null || host.length() == 0 || testDomain == null || testDomain.length() == 0) {
            return null;
        }
        ExecutorService probes = Executors.newFixedThreadPool(6, threads("slipnet-dns-probe"));
        try {
            return scanDnsInternal(host, port, testDomain.trim(), safeTimeout(timeoutMs),
                    querySize, bothTransports, probes).tunnel;
        } catch (Throwable t) {
            return null;
        } finally {
            probes.shutdownNow();
        }
    }

    public static int verifyPrism(String host, int port, String testDomain, String pubkeyHex,
                                  int timeoutMs, int probes, int threshold, int responseSize,
                                  boolean bothTransports) {
        byte[] pubkey = hexToBytes(pubkeyHex);
        if (host == null || pubkey == null || testDomain == null || testDomain.length() == 0) {
            return 0;
        }
        int timeout = safeTimeout(timeoutMs);
        int udp = PrismVerifier.verify(host, port, testDomain.trim(), pubkey, timeout,
                probes, threshold, responseSize, DnsProbe.Transport.UDP);
        if (!bothTransports) {
            return udp;
        }
        int tcp = PrismVerifier.verify(host, port, testDomain.trim(), pubkey, timeout,
                probes, threshold, responseSize, DnsProbe.Transport.TCP);
        return Math.max(udp, tcp);
    }

    public static void scanDns(final List<String> hosts, final int port, final String testDomain,
                               final int timeoutMs, int concurrency, final int querySize,
                               final boolean bothTransports, final Listener listener) {
        if (hosts == null || hosts.isEmpty()) {
            notifyFinished(listener, false);
            return;
        }
        final String domain = testDomain == null ? "" : testDomain.trim();
        final int timeout = safeTimeout(timeoutMs);
        final int workers = Math.max(1, concurrency);
        int probeWorkers = Math.max(6, Math.min(32, workers));
        Batch batch = new Batch(Executors.newFixedThreadPool(workers, threads("slipnet-dns")),
                Executors.newFixedThreadPool(probeWorkers, threads("slipnet-dns-probe")), hosts.size());
        synchronized (ACTIVE_LOCK) {
            ACTIVE.add(batch);
        }
        for (final String host : hosts) {
            if (batch.cancelled.get()) {
                break;
            }
            try {
                batch.pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        runDnsHost(batch, host, port, domain, timeout, querySize, bothTransports, listener);
                    }
                });
            } catch (RuntimeException e) {
                break;
            }
        }
        watch(batch, listener);
    }

    public static void scanPrism(final List<String> hosts, final int port, final String testDomain,
                                 String pubkeyHex, final int timeoutMs, int concurrency,
                                 final int probes, final int threshold, final int responseSize,
                                 final boolean prefilter, final int prefilterTimeoutMs,
                                 final Listener listener) {
        if (hosts == null || hosts.isEmpty()) {
            notifyFinished(listener, false);
            return;
        }
        final byte[] pubkey = hexToBytes(pubkeyHex);
        final String domain = testDomain == null ? "" : testDomain.trim();
        final int probeCount = Math.max(1, probes);
        final int need = Math.max(1, Math.min(threshold, probeCount));
        final int timeout = safeTimeout(timeoutMs);
        final int preTimeout = prefilterTimeoutMs > 0 ? prefilterTimeoutMs : timeout;
        final int workers = Math.max(1, concurrency);
        Batch batch = new Batch(Executors.newFixedThreadPool(workers, threads("slipnet-prism")),
                null, hosts.size());
        synchronized (ACTIVE_LOCK) {
            ACTIVE.add(batch);
        }
        for (final String host : hosts) {
            if (batch.cancelled.get()) {
                break;
            }
            try {
                batch.pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        runPrismHost(batch, host, port, domain, pubkey, timeout, probeCount, need,
                                responseSize, prefilter, preTimeout, listener);
                    }
                });
            } catch (RuntimeException e) {
                break;
            }
        }
        watch(batch, listener);
    }

    public static void scanE2e(final List<String> hosts, final int port, final WhiteDnsProfile base,
                               final int timeoutMs, int concurrency, final Listener listener) {
        if (hosts == null || hosts.isEmpty()) {
            notifyFinished(listener, false);
            return;
        }
        final int workers = Math.max(1, Math.min(Math.max(1, concurrency), MAX_E2E_CONCURRENCY));
        final long budget = timeoutMs > 0 ? timeoutMs : 35000L;
        Batch batch = new Batch(Executors.newFixedThreadPool(workers, threads("slipnet-e2e")),
                null, hosts.size());
        synchronized (ACTIVE_LOCK) {
            ACTIVE.add(batch);
        }
        for (final String host : hosts) {
            if (batch.cancelled.get()) {
                break;
            }
            try {
                batch.pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        runE2eHost(batch, host, port, base, budget, listener);
                    }
                });
            } catch (RuntimeException e) {
                break;
            }
        }
        watch(batch, listener);
    }

    /**
     * Replaces only the resolver list of the stored WhiteDNS profile. Domain, key and mode are
     * copied from the stored profile, never edited here.
     */
    public static boolean applyResolvers(Context ctx, List<String> hosts, int port) {
        if (ctx == null || hosts == null || hosts.isEmpty()) {
            return false;
        }
        WhiteDnsProfile profile = WhiteDnsStore.load(ctx);
        if (profile == null) {
            return false;
        }
        int safePort = port;
        if (safePort <= 0 || safePort > 65535) {
            safePort = 53;
        }
        StringBuilder joined = new StringBuilder();
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        for (String raw : hosts) {
            if (raw == null) {
                continue;
            }
            String host = raw.trim();
            if (host.length() == 0 || host.indexOf('|') >= 0 || host.indexOf(',') >= 0) {
                return false;
            }
            unique.add(host);
        }
        if (unique.isEmpty()) {
            return false;
        }
        for (String host : unique) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(host);
            if (safePort != 53) {
                joined.append(':').append(safePort);
            }
        }
        try {
            init(ctx);
            WhiteDnsStore.save(ctx, profile.withResolvers(joined.toString()));
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    public static void cancelAll() {
        List<Batch> snapshot;
        synchronized (ACTIVE_LOCK) {
            snapshot = new ArrayList<Batch>(ACTIVE);
        }
        for (Batch batch : snapshot) {
            batch.cancelled.set(true);
            stopPool(batch.pool);
            stopPool(batch.probePool);
        }
    }

    // ---- DNS ----

    private static void runDnsHost(Batch batch, String host, int port, String domain, int timeoutMs,
                                   int querySize, boolean bothTransports, Listener listener) {
        try {
            if (batch.cancelled.get()) {
                return;
            }
            ScanResult result = new ScanResult(host, port);
            result.status = ScanResult.Status.SCANNING;
            if (host == null || host.trim().length() == 0 || domain.length() == 0) {
                result.status = ScanResult.Status.ERROR;
                result.error = domain.length() == 0 ? "missing domain" : "bad host";
            } else {
                try {
                    DnsOutcome outcome = scanDnsInternal(host, port, domain, timeoutMs, querySize,
                            bothTransports, batch.probePool);
                    result.responseTimeMs = outcome.elapsedMs;
                    result.tunnel = outcome.tunnel;
                    if (bothTransports) {
                        result.udpWorking = Boolean.valueOf(outcome.udpWorking);
                        result.tcpWorking = Boolean.valueOf(outcome.tcpWorking);
                    }
                    result.status = outcome.responded
                            ? ScanResult.Status.WORKING : ScanResult.Status.TIMEOUT;
                } catch (Throwable t) {
                    result.status = ScanResult.Status.ERROR;
                    result.error = message(t);
                }
            }
            emit(batch, listener, result);
        } finally {
            progress(batch, listener);
        }
    }

    private static DnsOutcome scanDnsInternal(String host, int port, String testDomain, int timeoutMs,
                                              int querySize, boolean bothTransports,
                                              ExecutorService probePool) {
        DnsOutcome outcome = new DnsOutcome();
        long started = SystemClock.elapsedRealtime();
        String parent = parentDomain(testDomain);
        DnsProbe.Transport transport = DnsProbe.Transport.UDP;
        boolean responded;
        if (bothTransports) {
            Future<Boolean> udpFuture = submit(probePool, new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return Boolean.valueOf(basicCheck(host, port, parent, timeoutMs, DnsProbe.Transport.UDP));
                }
            });
            Future<Boolean> tcpFuture = submit(probePool, new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return Boolean.valueOf(basicCheck(host, port, parent, timeoutMs, DnsProbe.Transport.TCP));
                }
            });
            boolean udp = bool(udpFuture);
            boolean tcp = bool(tcpFuture);
            outcome.udpWorking = udp;
            outcome.tcpWorking = tcp;
            responded = udp || tcp;
            if (!udp && tcp) {
                transport = DnsProbe.Transport.TCP;
            }
        } else {
            responded = basicCheck(host, port, parent, timeoutMs, DnsProbe.Transport.UDP);
        }
        outcome.responded = responded;
        if (responded) {
            outcome.tunnel = runProbes(host, port, parent, testDomain, timeoutMs, querySize,
                    transport, probePool);
        }
        outcome.elapsedMs = SystemClock.elapsedRealtime() - started;
        return outcome;
    }

    private static boolean basicCheck(String host, int port, String parent, int timeoutMs,
                                      DnsProbe.Transport transport) {
        try {
            byte[] query = DnsProbe.buildQuery(randomSubdomain() + "." + parent, DnsProbe.TYPE_A, 0);
            DnsProbe.DnsResponse response = DnsProbe.query(host, port, query, timeoutMs, transport, 512);
            return response != null && response.received;
        } catch (Exception e) {
            return false;
        }
    }

    private static DnsTunnelResult runProbes(final String host, final int port, final String parent,
                                             final String testDomain, final int timeoutMs,
                                             final int querySize, final DnsProbe.Transport transport,
                                             ExecutorService pool) {
        Future<Boolean> ns = submit(pool, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(probeNsDelegation(host, port, parent, timeoutMs, transport));
            }
        });
        Future<Boolean> txt = submit(pool, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(probeTxt(host, port, parent, timeoutMs, transport));
            }
        });
        Future<Boolean> rnd = submit(pool, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(probeRandomSubdomain(host, port, testDomain, timeoutMs, transport));
            }
        });
        Future<Boolean> realism = submit(pool, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(probeTunnelRealism(host, port, testDomain, timeoutMs, querySize, transport));
            }
        });
        Future<Integer> edns = submit(pool, new Callable<Integer>() {
            @Override
            public Integer call() {
                return Integer.valueOf(probeEdns(host, port, testDomain, timeoutMs, transport));
            }
        });
        Future<Boolean> nxd = submit(pool, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(probeNxdomain(host, port, timeoutMs, transport));
            }
        });

        DnsTunnelResult tunnel = new DnsTunnelResult();
        tunnel.nsSupport = bool(ns);
        tunnel.txtSupport = bool(txt);
        tunnel.randomSubdomain = bool(rnd);
        tunnel.tunnelRealism = bool(realism);
        tunnel.ednsMaxPayload = number(edns);
        tunnel.edns0Support = tunnel.ednsMaxPayload > 0;
        tunnel.nxdomainCorrect = bool(nxd);
        return tunnel;
    }

    private static boolean probeNsDelegation(String host, int port, String parent, int timeoutMs,
                                             DnsProbe.Transport transport) {
        try {
            byte[] query = DnsProbe.buildQuery(parent, DnsProbe.TYPE_NS, 0);
            DnsProbe.DnsResponse ns = DnsProbe.query(host, port, query, timeoutMs, transport, 512);
            if (ns == null || !ns.received || ns.ns == null || ns.ns.isEmpty()) {
                return false;
            }
            String first = ns.ns.get(0) == null ? "" : ns.ns.get(0).trim();
            while (first.endsWith(".")) {
                first = first.substring(0, first.length() - 1);
            }
            if (first.length() == 0) {
                return false;
            }
            DnsProbe.DnsResponse glue = DnsProbe.query(host, port,
                    DnsProbe.buildQuery(first, DnsProbe.TYPE_A, 0), timeoutMs, transport, 512);
            return glue != null && glue.received && glue.a != null && glue.a.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean probeTxt(String host, int port, String parent, int timeoutMs,
                                    DnsProbe.Transport transport) {
        try {
            byte[] query = DnsProbe.buildQuery(randomSubdomain() + "." + parent, DnsProbe.TYPE_TXT, 0);
            DnsProbe.DnsResponse response = DnsProbe.query(host, port, query, timeoutMs, transport, 512);
            return response != null && response.received;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean probeRandomSubdomain(String host, int port, String testDomain, int timeoutMs,
                                                DnsProbe.Transport transport) {
        return randomAnswer(host, port, testDomain, timeoutMs, transport)
                || randomAnswer(host, port, testDomain, timeoutMs, transport);
    }

    private static boolean randomAnswer(String host, int port, String testDomain, int timeoutMs,
                                        DnsProbe.Transport transport) {
        try {
            String name = randomSubdomain() + "." + randomSubdomain() + "." + testDomain;
            DnsProbe.DnsResponse response = DnsProbe.query(host, port,
                    DnsProbe.buildQuery(name, DnsProbe.TYPE_A, 0), timeoutMs, transport, 512);
            return response != null && response.received;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean probeTunnelRealism(String host, int port, String testDomain, int timeoutMs,
                                              int querySize, DnsProbe.Transport transport) {
        try {
            int randLen = MAX_RANDOM_BYTES;
            if (querySize > 0) {
                int suffixLen = testDomain.length() + 2;
                int overhead = 12 + 4 + suffixLen;
                int available = Math.max(querySize - overhead, 10);
                randLen = Math.max(8, Math.min(MAX_RANDOM_BYTES, available * 5 / 9));
            }
            byte[] payload = new byte[randLen];
            RANDOM.nextBytes(payload);
            String name = dotifyBase32(base32Encode(payload)) + "." + testDomain;
            DnsProbe.DnsResponse response = DnsProbe.query(host, port,
                    DnsProbe.buildQuery(name, DnsProbe.TYPE_TXT, 0), timeoutMs, transport, 512);
            return response != null && response.received;
        } catch (Exception e) {
            return false;
        }
    }

    private static int probeEdns(String host, int port, String testDomain, int timeoutMs,
                                 DnsProbe.Transport transport) {
        int[] sizes = {512, 900, 1232};
        int maxWorking = 0;
        for (int size : sizes) {
            try {
                byte[] query = DnsProbe.buildQuery(randomSubdomain() + "." + testDomain,
                        DnsProbe.TYPE_TXT, size);
                DnsProbe.DnsResponse response = DnsProbe.query(host, port, query, timeoutMs, transport, 4096);
                if (response != null && response.received && response.raw != null
                        && response.length >= 12 && response.rcode != 1
                        && DnsProbe.hasOpt(response.raw, response.length)) {
                    maxWorking = size;
                }
            } catch (Exception ignored) {
            }
        }
        return maxWorking;
    }

    private static boolean probeNxdomain(String host, int port, int timeoutMs, DnsProbe.Transport transport) {
        int nxdomain = 0;
        for (int i = 0; i < 3; i++) {
            try {
                String name = "nxd-" + randomSubdomain() + ".invalid";
                DnsProbe.DnsResponse response = DnsProbe.query(host, port,
                        DnsProbe.buildQuery(name, DnsProbe.TYPE_A, 0), timeoutMs, transport, 512);
                if (response != null && response.received && response.rcode == 3) {
                    nxdomain++;
                }
            } catch (Exception ignored) {
            }
        }
        return nxdomain >= 2;
    }

    // ---- Prism ----

    private static void runPrismHost(Batch batch, String host, int port, String domain, byte[] pubkey,
                                     int timeoutMs, int probes, int threshold, int responseSize,
                                     boolean prefilter, int prefilterTimeoutMs, Listener listener) {
        try {
            if (batch.cancelled.get()) {
                return;
            }
            ScanResult result = new ScanResult(host, port);
            result.status = ScanResult.Status.SCANNING;
            result.prismTotal = Integer.valueOf(probes);
            long started = SystemClock.elapsedRealtime();
            try {
                if (host == null || host.trim().length() == 0 || domain.length() == 0 || pubkey == null) {
                    result.status = ScanResult.Status.ERROR;
                    result.error = pubkey == null ? "bad public key"
                            : (domain.length() == 0 ? "missing domain" : "bad host");
                    result.prismPassed = Integer.valueOf(0);
                } else if (prefilter && !PrismVerifier.alive(host, port, domain, prefilterTimeoutMs,
                        DnsProbe.Transport.UDP)) {
                    result.status = ScanResult.Status.TIMEOUT;
                    result.prismPassed = Integer.valueOf(0);
                } else {
                    int passed = PrismVerifier.verify(host, port, domain, pubkey, timeoutMs, probes,
                            threshold, responseSize, DnsProbe.Transport.UDP);
                    result.prismPassed = Integer.valueOf(passed);
                    result.status = passed >= threshold
                            ? ScanResult.Status.WORKING : ScanResult.Status.ERROR;
                    if (passed < threshold) {
                        result.error = "prism " + passed + "/" + probes;
                    }
                }
            } catch (Throwable t) {
                result.status = ScanResult.Status.ERROR;
                result.error = message(t);
            }
            result.responseTimeMs = SystemClock.elapsedRealtime() - started;
            emit(batch, listener, result);
        } finally {
            progress(batch, listener);
        }
    }

    // ---- E2E ----

    private static void runE2eHost(Batch batch, String host, int port, WhiteDnsProfile base,
                                   long budgetMs, Listener listener) {
        try {
            if (batch.cancelled.get()) {
                return;
            }
            ScanResult result = new ScanResult(host, port);
            result.status = ScanResult.Status.SCANNING;
            Context ctx = context();
            if (base == null || ctx == null || host == null || host.trim().length() == 0) {
                result.status = ScanResult.Status.ERROR;
                result.error = base == null ? "missing profile" : (ctx == null ? "missing context" : "bad host");
            } else {
                long started = SystemClock.elapsedRealtime();
                try {
                    int readyMs = probeCandidate(batch, ctx, base, host, budgetMs);
                    E2eOutcome outcome = new E2eOutcome();
                    outcome.success = readyMs >= 0;
                    outcome.totalMs = readyMs >= 0 ? readyMs
                            : SystemClock.elapsedRealtime() - started;
                    outcome.error = outcome.success ? null : "the tunnel did not come up";
                    outcome.phase = outcome.success ? "DONE" : "FAILED";
                    result.e2e = outcome;
                    result.responseTimeMs = outcome.totalMs;
                    result.status = outcome.success
                            ? ScanResult.Status.WORKING : ScanResult.Status.ERROR;
                    if (!outcome.success) {
                        result.error = outcome.error;
                    }
                } catch (Throwable t) {
                    result.status = ScanResult.Status.ERROR;
                    result.error = message(t);
                    result.responseTimeMs = SystemClock.elapsedRealtime() - started;
                }
            }
            emit(batch, listener, result);
        } finally {
            progress(batch, listener);
        }
    }

    /** Milliseconds the engine needed to come up with this resolver, or -1 on failure. */
    private static int probeCandidate(Batch batch, Context ctx, WhiteDnsProfile base, String host,
                                      long budgetMs) {
        synchronized (PROBE_LOCK) {
            if (batch.cancelled.get()) {
                return -1;
            }
            return WhiteDnsTester.probeWithResolver(ctx, base, host, budgetMs);
        }
    }

    // ---- batch plumbing ----

    private static void watch(final Batch batch, final Listener listener) {
        Thread watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    batch.pool.shutdown();
                    while (true) {
                        try {
                            if (batch.pool.awaitTermination(1L, TimeUnit.DAYS)) {
                                break;
                            }
                        } catch (InterruptedException e) {
                            batch.cancelled.set(true);
                            stopPool(batch.pool);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    stopPool(batch.probePool);
                    synchronized (ACTIVE_LOCK) {
                        ACTIVE.remove(batch);
                    }
                    notifyFinished(listener, batch.cancelled.get());
                }
            }
        }, "slipnet-scan-done");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void emit(Batch batch, Listener listener, ScanResult result) {
        if (!batch.cancelled.get()) {
            notifyResult(listener, result);
        }
    }

    private static void progress(Batch batch, Listener listener) {
        int done = batch.done.incrementAndGet();
        if (listener == null) {
            return;
        }
        try {
            listener.onProgress(done, batch.total);
        } catch (RuntimeException ignored) {
        }
    }

    private static void notifyResult(Listener listener, ScanResult result) {
        if (listener == null) {
            return;
        }
        try {
            listener.onResult(result);
        } catch (RuntimeException ignored) {
        }
    }

    private static void notifyFinished(Listener listener, boolean cancelled) {
        if (listener == null) {
            return;
        }
        try {
            listener.onFinished(cancelled);
        } catch (RuntimeException ignored) {
        }
    }

    private static void stopPool(ExecutorService pool) {
        if (pool == null) {
            return;
        }
        try {
            pool.shutdownNow();
        } catch (RuntimeException ignored) {
        }
    }

    private static ThreadFactory threads(final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static <T> Future<T> submit(ExecutorService pool, Callable<T> callable) {
        if (pool == null) {
            return null;
        }
        try {
            return pool.submit(callable);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean bool(Future<Boolean> future) {
        if (future == null) {
            return false;
        }
        try {
            return future.get().booleanValue();
        } catch (Exception e) {
            return false;
        }
    }

    private static int number(Future<Integer> future) {
        if (future == null) {
            return 0;
        }
        try {
            return future.get().intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String parentDomain(String domain) {
        int first = domain.indexOf('.');
        if (first >= 0 && domain.indexOf('.', first + 1) >= 0) {
            return domain.substring(first + 1);
        }
        return domain;
    }

    private static String randomSubdomain() {
        char[] out = new char[8];
        for (int i = 0; i < out.length; i++) {
            out[i] = SUBDOMAIN[RANDOM.nextInt(SUBDOMAIN.length)];
        }
        return new String(out);
    }

    private static String base32Encode(byte[] data) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(alphabet.charAt((buffer >>> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            sb.append(alphabet.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    private static String dotifyBase32(String encoded) {
        if (encoded.length() <= 57) {
            return encoded;
        }
        StringBuilder sb = new StringBuilder(encoded.length() + encoded.length() / 57);
        int remaining = encoded.length();
        int pos = 0;
        int firstLabelLen = remaining % 57;
        if (firstLabelLen > 0) {
            sb.append(encoded, 0, firstLabelLen);
            pos = firstLabelLen;
            remaining -= firstLabelLen;
            if (remaining > 0) {
                sb.append('.');
            }
        }
        while (remaining > 0) {
            int end = pos + Math.min(57, remaining);
            sb.append(encoded, pos, end);
            remaining -= end - pos;
            pos = end;
            if (remaining > 0) {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return null;
        }
        String value = hex.trim();
        if (value.length() == 0 || (value.length() & 1) == 1) {
            return null;
        }
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static int safeTimeout(int timeoutMs) {
        return timeoutMs > 0 ? timeoutMs : 3000;
    }

    // This app exposes no global Application accessor; mirror ScanManager's process-wide fallback.
    private static Context context() {
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

    private static String message(Throwable t) {
        String text = t == null ? null : t.getMessage();
        return text == null || text.length() == 0 ? "error" : text;
    }

    private static final class DnsOutcome {
        boolean responded;
        boolean udpWorking;
        boolean tcpWorking;
        long elapsedMs;
        DnsTunnelResult tunnel;
    }

    private static final class Batch {
        final ExecutorService pool;
        final ExecutorService probePool;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicInteger done = new AtomicInteger();
        final int total;

        Batch(ExecutorService pool, ExecutorService probePool, int total) {
            this.pool = pool;
            this.probePool = probePool;
            this.total = total;
        }
    }
}
