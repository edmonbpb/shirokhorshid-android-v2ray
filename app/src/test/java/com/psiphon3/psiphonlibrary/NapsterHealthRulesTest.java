package com.psiphon3.psiphonlibrary;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure JVM tests for the Napster health decision rules. No Android classes required. */
public class NapsterHealthRulesTest {

    private static NapsterHealthRules.Result evaluate(boolean networkOk, int generalSuccesses,
            boolean telegramOk, NapsterHealthRules.Status previous, int failStreak) {
        return NapsterHealthRules.evaluate(new NapsterHealthRules.Input(
                networkOk, generalSuccesses, telegramOk, previous, failStreak));
    }

    @Test
    public void networkDownKeepsTheStreakAndIsUnknown() {
        NapsterHealthRules.Result result = evaluate(false, 0, false,
                NapsterHealthRules.Status.DEAD, 4);
        Assert.assertEquals(NapsterHealthRules.Status.UNKNOWN, result.status);
        Assert.assertEquals(4, result.failStreak);
    }

    @Test
    public void firstGeneralFailureIsSuspicious() {
        NapsterHealthRules.Result result = evaluate(true, 0, false,
                NapsterHealthRules.Status.UNKNOWN, 0);
        Assert.assertEquals(NapsterHealthRules.Status.SUSPICIOUS, result.status);
        Assert.assertEquals(1, result.failStreak);
    }

    @Test
    public void secondGeneralFailureIsDead() {
        NapsterHealthRules.Result result = evaluate(true, 0, false,
                NapsterHealthRules.Status.SUSPICIOUS, 1);
        Assert.assertEquals(NapsterHealthRules.Status.DEAD, result.status);
        Assert.assertEquals(2, result.failStreak);
    }

    @Test
    public void oneGeneralSuccessIsStillSuspicious() {
        NapsterHealthRules.Result result = evaluate(true, 1, false,
                NapsterHealthRules.Status.UNKNOWN, 0);
        Assert.assertEquals(NapsterHealthRules.Status.SUSPICIOUS, result.status);
        Assert.assertEquals(1, result.failStreak);
    }

    @Test
    public void generalAndTelegramSuccessIsHealthy() {
        NapsterHealthRules.Result result = evaluate(true, 2, true,
                NapsterHealthRules.Status.SUSPICIOUS, 1);
        Assert.assertEquals(NapsterHealthRules.Status.HEALTHY, result.status);
        Assert.assertEquals(0, result.failStreak);
    }

    @Test
    public void generalSuccessWithTelegramFailureIsSemiHealthy() {
        NapsterHealthRules.Result result = evaluate(true, 2, false,
                NapsterHealthRules.Status.DEAD, 3);
        Assert.assertEquals(NapsterHealthRules.Status.SEMI_HEALTHY, result.status);
        Assert.assertEquals(0, result.failStreak);
    }

    @Test
    public void successFromDeadResetsTheStreak() {
        NapsterHealthRules.Result result = evaluate(true, 3, true,
                NapsterHealthRules.Status.DEAD, 5);
        Assert.assertEquals(NapsterHealthRules.Status.HEALTHY, result.status);
        Assert.assertEquals(0, result.failStreak);
    }

    @Test
    public void medianOfAnEmptyListIsZero() {
        Assert.assertEquals(0, NapsterHealthRules.median(null));
        Assert.assertEquals(0, NapsterHealthRules.median(new ArrayList<Integer>()));
    }

    @Test
    public void medianOfAnOddListIsTheMiddleValue() {
        Assert.assertEquals(2, NapsterHealthRules.median(Arrays.asList(9, 1, 2)));
        Assert.assertEquals(7, NapsterHealthRules.median(Collections.singletonList(7)));
    }

    @Test
    public void medianOfAnEvenListAveragesTheMiddlePair() {
        Assert.assertEquals(2, NapsterHealthRules.median(Arrays.asList(4, 1, 3, 2)));
        Assert.assertEquals(3, NapsterHealthRules.median(Arrays.asList(2, 4)));
    }

    @Test
    public void medianDoesNotReorderTheCallerList() {
        List<Integer> values = Arrays.asList(3, 1, 2);
        NapsterHealthRules.median(values);
        Assert.assertEquals(Arrays.asList(3, 1, 2), values);
    }
}
