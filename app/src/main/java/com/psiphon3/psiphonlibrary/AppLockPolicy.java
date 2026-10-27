package com.psiphon3.psiphonlibrary;

/**
 * Pure timing rule for the app lock. Deliberately free of Android imports so the
 * boundary cases can be tested on the JVM.
 */
public final class AppLockPolicy {

    /** How long the app may stay in the background before it asks again. */
    public static final long BACKGROUND_TIMEOUT_MS = 60000L;

    private AppLockPolicy() {
    }

    /**
     * @param backgroundAt  elapsedRealtime when the app went to the background, 0 if never
     * @param now           current elapsedRealtime
     * @param promptActive  an authentication window or our overlay is already up
     * @param modeNone      the saved choice is NONE
     * @param available     the saved choice can be performed on this phone right now
     */
    public static boolean shouldPrompt(long backgroundAt, long now, boolean promptActive,
            boolean modeNone, boolean available) {
        if (promptActive || modeNone || !available) {
            return false;
        }
        if (backgroundAt == 0L) {
            return true;
        }
        return now - backgroundAt > BACKGROUND_TIMEOUT_MS;
    }
}
