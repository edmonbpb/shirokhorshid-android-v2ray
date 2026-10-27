// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.

package app.slipnet.scanner;

public final class ScanResult {
    public enum Status { PENDING, SCANNING, WORKING, CENSORED, TIMEOUT, ERROR }

    public final String host;
    public final int port;
    public volatile Status status;
    public volatile long responseTimeMs;   // -1 unknown
    public volatile String error;
    public volatile DnsTunnelResult tunnel;
    public volatile Integer prismPassed;
    public volatile Integer prismTotal;
    public volatile Boolean udpWorking;
    public volatile Boolean tcpWorking;    // only BOTH mode
    public volatile E2eOutcome e2e;

    public ScanResult(String host, int port) {
        this.host = host;
        this.port = port;
        this.status = Status.PENDING;
        this.responseTimeMs = -1;
    }

    public String key() {
        return host + ":" + port;
    }
}
