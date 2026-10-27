package app.whitedns.net;

import android.content.Context;

import java.io.Closeable;
import java.io.IOException;

import app.whitedns.engine.WhiteDnsProcess;
import app.whitedns.model.WhiteDnsProfile;

/** Local SOCKS5 endpoint backed by one WhiteDNS engine process. */
public final class WhiteDnsTunnel implements Closeable {

    private final int port;
    private final WhiteDnsProcess process;
    private boolean closed;

    private WhiteDnsTunnel(int port, WhiteDnsProcess process) {
        this.port = port;
        this.process = process;
    }

    public static WhiteDnsTunnel start(Context ctx, WhiteDnsProfile p, long timeoutMs)
            throws IOException {
        if (ctx == null) {
            throw new IOException("missing context");
        }
        if (p == null) {
            throw new IOException("missing profile");
        }
        WhiteDnsProcess process = WhiteDnsProcess.start(ctx, p, timeoutMs);
        return new WhiteDnsTunnel(process.getPort(), process);
    }

    public int getPort() {
        return port;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (process != null) {
            process.close();
        }
    }
}
