package com.psiphon3.psiphonlibrary;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded in-memory log for the Telegram screen. Pure Java so it runs in a
 * plain JVM test; never pass cookies, config bodies or full URLs here.
 */
public final class TelegramSessionLog {

    public static final int MAX_LINES = 200;

    private static final int MAX_LINE_CHARS = 300;
    private static final Pattern URL = Pattern.compile("https?://\\S*", Pattern.CASE_INSENSITIVE);
    private static final String URL_PLACEHOLDER = "[url]";
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static final String[] RING = new String[MAX_LINES];
    private static int head;
    private static int count;

    private TelegramSessionLog() {
    }

    /** Levels match AppLog: D, I, W, E. One entry is always one physical line. */
    public static synchronized void append(char level, String message) {
        String text = message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
        Matcher matcher = URL.matcher(text);
        if (matcher.find()) {
            text = matcher.replaceAll(URL_PLACEHOLDER);
        }
        String line = TIME.format(new Date()) + " " + level + " " + text;
        if (line.length() > MAX_LINE_CHARS) {
            line = line.substring(0, MAX_LINE_CHARS);
        }
        if (count == MAX_LINES) {
            RING[head] = line;
            head = (head + 1) % MAX_LINES;
        } else {
            RING[(head + count) % MAX_LINES] = line;
            count++;
        }
    }

    /** Oldest first, joined with '\n'. */
    public static synchronized String snapshot() {
        StringBuilder builder = new StringBuilder();
        for (int n = 0; n < count; n++) {
            if (n > 0) {
                builder.append('\n');
            }
            builder.append(RING[(head + n) % MAX_LINES]);
        }
        return builder.toString();
    }

    public static synchronized void clear() {
        for (int n = 0; n < MAX_LINES; n++) {
            RING[n] = null;
        }
        head = 0;
        count = 0;
    }
}
