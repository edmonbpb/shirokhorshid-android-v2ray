// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.
package app.slipnet.util;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Bounded in-app log; mirrors SlipNet's AppLog. Never pass secrets here. */
public final class AppLog {

    private static final int MAX_LINES = 500;
    private static final String[] RING = new String[MAX_LINES];
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static int head;
    private static int count;

    private AppLog() {
    }

    public static synchronized void d(String tag, String msg) {
        Log.d(tag, msg);
        append('D', tag, msg);
    }

    public static synchronized void i(String tag, String msg) {
        Log.i(tag, msg);
        append('I', tag, msg);
    }

    public static synchronized void w(String tag, String msg) {
        Log.w(tag, msg);
        append('W', tag, msg);
    }

    public static synchronized void e(String tag, String msg) {
        Log.e(tag, msg);
        append('E', tag, msg);
    }

    /** Oldest first, joined with '\n'. */
    public static synchronized String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < count; n++) {
            if (n > 0) {
                sb.append('\n');
            }
            sb.append(RING[(head + n) % MAX_LINES]);
        }
        return sb.toString();
    }

    public static synchronized int size() {
        return count;
    }

    public static synchronized void clear() {
        for (int n = 0; n < MAX_LINES; n++) {
            RING[n] = null;
        }
        head = 0;
        count = 0;
    }

    private static void append(char level, String tag, String msg) {
        String line = TIME.format(new Date()) + " " + level + "/" + tag + ": "
                + (msg == null ? "" : msg);
        if (count == MAX_LINES) {
            RING[head] = line;
            head = (head + 1) % MAX_LINES;
        } else {
            RING[(head + count) % MAX_LINES] = line;
            count++;
        }
    }
}
