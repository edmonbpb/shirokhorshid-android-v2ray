// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.

package app.slipnet.scanner;

public final class ScanState {
    public boolean running;
    public String mode;
    public int total, done, passed;
    public String phase;                       // e.g. "DNS 123/500", "E2E 2/8"
    public boolean transparentProxy;
    public final java.util.List<ScanResult> results = new java.util.ArrayList<>();
    public volatile long generation;           // bumped on each start, for stale callbacks
}
