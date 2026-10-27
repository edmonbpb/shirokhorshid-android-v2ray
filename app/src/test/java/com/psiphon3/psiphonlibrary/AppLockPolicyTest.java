package com.psiphon3.psiphonlibrary;

import org.junit.Assert;
import org.junit.Test;

/** Pure JVM tests for the app lock timing rule. No Android classes required. */
public class AppLockPolicyTest {

    private static final long NOW = 1000000L;
    private static final long TIMEOUT = AppLockPolicy.BACKGROUND_TIMEOUT_MS;

    private static boolean prompt(long backgroundAt, long now) {
        return AppLockPolicy.shouldPrompt(backgroundAt, now, false, false, true);
    }

    @Test
    public void timeoutIsOneMinute() {
        Assert.assertEquals(60000L, TIMEOUT);
    }

    @Test
    public void coldStartPrompts() {
        Assert.assertTrue(prompt(0L, NOW));
        Assert.assertTrue(prompt(0L, 0L));
    }

    @Test
    public void fiftyNineSecondsDoesNotPrompt() {
        Assert.assertFalse(prompt(NOW - 59000L, NOW));
    }

    @Test
    public void sixtySecondsDoesNotPrompt() {
        Assert.assertFalse(prompt(NOW - TIMEOUT, NOW));
    }

    @Test
    public void sixtyOneSecondsPrompts() {
        Assert.assertTrue(prompt(NOW - 61000L, NOW));
    }

    @Test
    public void activePromptNeverPromptsAgain() {
        Assert.assertFalse(AppLockPolicy.shouldPrompt(0L, NOW, true, false, true));
        Assert.assertFalse(AppLockPolicy.shouldPrompt(NOW - TIMEOUT - 1L, NOW, true, false, true));
    }

    @Test
    public void modeNoneNeverPrompts() {
        Assert.assertFalse(AppLockPolicy.shouldPrompt(0L, NOW, false, true, true));
        Assert.assertFalse(
                AppLockPolicy.shouldPrompt(NOW - TIMEOUT - 1L, NOW, false, true, true));
    }

    @Test
    public void unavailableNeverPrompts() {
        Assert.assertFalse(AppLockPolicy.shouldPrompt(0L, NOW, false, false, false));
        Assert.assertFalse(
                AppLockPolicy.shouldPrompt(NOW - TIMEOUT - 1L, NOW, false, false, false));
    }

    @Test
    public void backgroundAtInTheFutureDoesNotPrompt() {
        Assert.assertFalse(prompt(NOW + 60000L, NOW));
        Assert.assertFalse(prompt(NOW + 1L, NOW));
    }
}
