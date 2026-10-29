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

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.psiphon.PsiphonTunnel;

/**
 * A headless tunnel-core instance for the in-app Telegram screen.
 *
 * <p>It runs tunnel-core without VPN mode in the app process and exposes the core's local
 * SOCKS proxy, so CDN fronting works while the main VPN tunnel is down.
 */
public final class PsiphonLocalTunnel implements Closeable {

    private static final Object sLock = new Object();
    private static PsiphonLocalTunnel sActive;

    // Diagnostic notices emitted by tunnel-core. The formats mirror the ones
    // TunnelManager already parses for the main tunnel.
    private static final Pattern SCAN_ACTIVE = Pattern.compile(
            "\\(mode: ([^,]+), workers: (\\d+)(?:, candidates: \\d+, sni: \\d+)?\\)");
    private static final Pattern SCAN_COUNTS = Pattern.compile(
            "\\((?:attempts|tried): (\\d+)(?:, candidates: \\d+)?, working: (\\d+)\\)");
    private static final Pattern SCAN_FOUND = Pattern.compile(
            "\\(ip: ([^,]+), sni: ([^)]+)\\)");
    private static final Pattern CONNECTED_PROTOCOL = Pattern.compile("\\(protocol: ([^)]+)\\)");
    private static final Pattern BEAST_WORKERS = Pattern.compile("\\(workers: (\\d+)\\)");

    private static final long PROGRESS_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final Context context;
    private final String psiphonConfig;
    private final Host host;
    private final CountDownLatch portLatch = new CountDownLatch(1);
    private final AtomicInteger port = new AtomicInteger(0);
    private final AtomicBoolean exited = new AtomicBoolean(false);

    private final AtomicLong scanExhaustedCount = new AtomicLong(0);
    // 0 means "no progress line logged yet".
    private volatile long lastProgressLogNanos;
    private volatile String foundRoute;
    private volatile String connectedRegion;
    private volatile String exitAddress;
    private volatile String connectedProtocol;

    private PsiphonTunnel tunnel;
    // Guarded by sLock.
    private boolean closed;
    private boolean stopIssued;

    private PsiphonLocalTunnel(Context context, String psiphonConfig) {
        this.context = context;
        this.psiphonConfig = psiphonConfig;
        this.host = new Host();
    }

    public static PsiphonLocalTunnel start(Context context, long timeoutMs)
            throws IOException {
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }

        EmbeddedValues.initialize(appContext);

        String tunnelCoreConfig = TelegramCdnConfig.build(appContext);
        if (tunnelCoreConfig == null) {
            throw new IOException("the CDN tunnel could not start");
        }

        Utils.initializeSecureRandom();

        PsiphonLocalTunnel localTunnel = new PsiphonLocalTunnel(appContext, tunnelCoreConfig);
        synchronized (sLock) {
            PsiphonLocalTunnel previous = sActive;
            if (previous != null) {
                previous.stopLocked();
            }
            sActive = localTunnel;
            try {
                localTunnel.tunnel = PsiphonTunnel.newPsiphonTunnel(localTunnel.host);
                localTunnel.tunnel.startTunneling(TunnelManager.getServerEntries(appContext));
            } catch (PsiphonTunnel.Exception | RuntimeException e) {
                if (sActive == localTunnel) {
                    sActive = null;
                }
                localTunnel.stopLocked();
                throw new IOException("the CDN tunnel could not start", e);
            }
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean portReady;
        try {
            portReady = localTunnel.portLatch.await(remainingMs(deadline), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            localTunnel.abandon();
            throw new IOException("the CDN tunnel did not open its proxy", e);
        }
        if (!portReady || localTunnel.exited.get() || localTunnel.port.get() <= 0) {
            localTunnel.abandon();
            throw new IOException("the CDN tunnel did not open its proxy");
        }

        // The core is listening, but its CDN workers may still be scanning for a route.
        // Return now and leave the core running; the caller retries until it connects.
        return localTunnel;
    }

    public int getPort() {
        return port.get();
    }

    /** How many times the core reported that the CDN scan ran out of candidates. */
    public long getScanExhaustedCount() {
        return scanExhaustedCount.get();
    }

    /** The last route the scan found, e.g. {@code ip 1.2.3.4, sni none}; null until found. */
    public String getFoundRoute() {
        return foundRoute;
    }

    /** The server region reported by the core; null until reported. */
    public String getConnectedRegion() {
        return connectedRegion;
    }

    /** The public exit address reported by the core; null until reported. */
    public String getExitAddress() {
        return exitAddress;
    }

    /** The connected protocol, e.g. {@code SSH+OBFUSCATED}; null until reported. */
    public String getConnectedProtocol() {
        return connectedProtocol;
    }

    /** True when the core reported that it is exiting, so the tunnel can no longer connect. */
    public boolean isExited() {
        return exited.get();
    }

    @Override
    public void close() {
        synchronized (sLock) {
            if (closed) {
                return;
            }
            closed = true;
        }

        Thread stopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (sLock) {
                    // A newer tunnel owns the process-wide core now and start() already
                    // stopped this one; stopping here would kill the new tunnel.
                    if (sActive != null && sActive != PsiphonLocalTunnel.this) {
                        return;
                    }
                    stopLocked();
                    if (sActive == PsiphonLocalTunnel.this) {
                        sActive = null;
                    }
                }
            }
        }, "psiphon-local-tunnel-stop");
        stopThread.setDaemon(true);
        stopThread.start();
    }

    // Must be called with sLock held.
    private void stopLocked() {
        if (stopIssued) {
            return;
        }
        stopIssued = true;
        if (tunnel != null) {
            tunnel.stop();
        }
    }

    private void abandon() {
        synchronized (sLock) {
            // A newer tunnel owns the process-wide core now; leave it running.
            if (sActive != this) {
                return;
            }
            sActive = null;
            stopLocked();
        }
    }

    private static long remainingMs(long deadlineNanos) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return remaining > 0L ? remaining : 0L;
    }

    private final class Host implements PsiphonTunnel.HostService {

        @Override
        public Context getContext() {
            return context;
        }

        @Override
        public String getPsiphonConfig() {
            return psiphonConfig;
        }

        @Override
        public void onListeningSocksProxyPort(int socksProxyPort) {
            if (socksProxyPort > 0) {
                port.set(socksProxyPort);
                portLatch.countDown();
            }
        }

        @Override
        public void onConnected() {
            // start() no longer waits for this. The core keeps scanning CDN workers and
            // reports the route through onDiagnosticMessage.
        }

        @Override
        public void onConnectedServerRegion(String region) {
            if (region == null) {
                return;
            }
            connectedRegion = region;
            TelegramSessionLog.append('I', "server region: " + region);
        }

        @Override
        public void onClientAddress(String address) {
            if (address == null) {
                return;
            }
            exitAddress = address;
            TelegramSessionLog.append('I', "exit address: " + address);
        }

        @Override
        public void onDiagnosticMessage(String message) {
            if (message == null) {
                return;
            }
            // Only the CDN scan and connect notices below are shown. Everything else,
            // including raw JSON, is dropped on purpose.
            if (message.contains("cdn fronting scan found")) {
                logFoundRoute(message);
            } else if (message.contains("cdn fronting scan active (mode:")) {
                logScanActive(message);
            } else if (message.contains("cdn fronting scan progress (attempts:")
                    || message.contains("cdn fronting scan progress (tried:")) {
                logScanProgress(message);
            } else if (message.contains("cdn fronting scan exhausted (attempts:")) {
                logScanExhausted(message);
            } else if (message.contains("beast mode active (workers:")) {
                Matcher matcher = BEAST_WORKERS.matcher(message);
                if (matcher.find()) {
                    TelegramSessionLog.append('I', "cdn scan: beast mode, workers " + matcher.group(1));
                }
            } else if (message.contains("tunnel connected (protocol:")) {
                Matcher matcher = CONNECTED_PROTOCOL.matcher(message);
                if (matcher.find()) {
                    String protocol = matcher.group(1);
                    connectedProtocol = protocol;
                    TelegramSessionLog.append('I', "tunnel connected: " + protocol);
                }
            }
        }

        @Override
        public void onSocksProxyPortInUse(int socksProxyPort) {
            // The core selects another local port; wait for onListeningSocksProxyPort.
        }

        @Override
        public void onExiting() {
            exited.set(true);
            portLatch.countDown();
        }
    }

    private void logScanActive(String message) {
        Matcher matcher = SCAN_ACTIVE.matcher(message);
        if (matcher.find()) {
            TelegramSessionLog.append('I', "cdn scan active: mode " + matcher.group(1)
                    + ", workers " + matcher.group(2));
        }
    }

    private void logScanProgress(String message) {
        Matcher matcher = SCAN_COUNTS.matcher(message);
        if (!matcher.find()) {
            return;
        }
        long now = System.nanoTime();
        long last = lastProgressLogNanos;
        if (last != 0L && now - last < PROGRESS_LOG_INTERVAL_NANOS) {
            return;
        }
        lastProgressLogNanos = now;
        TelegramSessionLog.append('I', "cdn scan progress: attempts " + matcher.group(1)
                + ", working " + matcher.group(2));
    }

    private void logScanExhausted(String message) {
        scanExhaustedCount.incrementAndGet();
        Matcher matcher = SCAN_COUNTS.matcher(message);
        if (matcher.find()) {
            TelegramSessionLog.append('W', "cdn scan exhausted: attempts " + matcher.group(1)
                    + ", working " + matcher.group(2));
        }
    }

    private void logFoundRoute(String message) {
        Matcher matcher = SCAN_FOUND.matcher(message);
        if (!matcher.find()) {
            return;
        }
        String ip = unescapeRedactionSafeIp(matcher.group(1));
        String sni = matcher.group(2);
        String route = "ip " + ip + ", sni " + sni;
        foundRoute = route;
        TelegramSessionLog.append('I', "cdn route found: " + route);
    }

    private static String unescapeRedactionSafeIp(String address) {
        return address.replaceAll("\\\\+([.:])", "$1");
    }
}
