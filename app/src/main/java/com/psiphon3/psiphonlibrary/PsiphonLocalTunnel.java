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

    private final Context context;
    private final String psiphonConfig;
    private final Host host;
    private final CountDownLatch portLatch = new CountDownLatch(1);
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final AtomicInteger port = new AtomicInteger(0);
    private final AtomicBoolean exited = new AtomicBoolean(false);

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

        boolean connected;
        try {
            connected = localTunnel.connectedLatch.await(remainingMs(deadline), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            localTunnel.abandon();
            throw new IOException("the CDN tunnel did not connect in time", e);
        }
        if (!connected || localTunnel.exited.get()) {
            localTunnel.abandon();
            throw new IOException("the CDN tunnel did not connect in time");
        }

        return localTunnel;
    }

    public int getPort() {
        return port.get();
    }

    @Override
    public void close() {
        synchronized (sLock) {
            if (closed) {
                return;
            }
            closed = true;
            if (sActive == this) {
                sActive = null;
            }
        }

        Thread stopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (sLock) {
                    // A newer tunnel owns the process-wide core now and has already
                    // stopped this one; stopping here would kill the new tunnel.
                    if (sActive != null && sActive != PsiphonLocalTunnel.this) {
                        return;
                    }
                    stopLocked();
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
            connectedLatch.countDown();
        }

        @Override
        public void onSocksProxyPortInUse(int socksProxyPort) {
            // The core selects another local port; wait for onListeningSocksProxyPort.
        }

        @Override
        public void onExiting() {
            exited.set(true);
            portLatch.countDown();
            connectedLatch.countDown();
        }
    }
}
