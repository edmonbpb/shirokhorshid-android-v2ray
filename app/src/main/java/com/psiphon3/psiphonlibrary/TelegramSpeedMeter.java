package com.psiphon3.psiphonlibrary;

/** Turns TrafficStats counters into a transfer rate and a short label. Pure Java. */
public final class TelegramSpeedMeter {

    private static final long KB = 1024L;
    private static final long MB = 1024L * 1024L;
    private static final String EM_DASH = "\u2014";

    private TelegramSpeedMeter() {
    }

    /**
     * Bytes per second over the elapsed window. Returns -1 for a bad window or
     * negative counters, 0 when a counter went backwards (reset).
     */
    public static long rate(long prevRx, long prevTx, long curRx, long curTx, long elapsedMs) {
        if (elapsedMs <= 0L) {
            return -1L;
        }
        if (prevRx < 0L || prevTx < 0L || curRx < 0L || curTx < 0L) {
            return -1L;
        }
        if (curRx < prevRx || curTx < prevTx) {
            return 0L;
        }
        long delta = (curRx - prevRx) + (curTx - prevTx);
        return delta * 1000L / elapsedMs;
    }

    /** "0 B/s", "12.3 KB/s", "1.2 MB/s", or an em dash when the rate is negative. */
    public static String format(long bytesPerSecond) {
        if (bytesPerSecond < 0L) {
            return EM_DASH;
        }
        if (bytesPerSecond < KB) {
            return bytesPerSecond + " B/s";
        }
        if (bytesPerSecond < MB) {
            return tenths(bytesPerSecond, KB) + " KB/s";
        }
        return tenths(bytesPerSecond, MB) + " MB/s";
    }

    private static String tenths(long value, long unit) {
        long scaled = value * 10L / unit;
        return (scaled / 10L) + "." + (scaled % 10L);
    }
}
