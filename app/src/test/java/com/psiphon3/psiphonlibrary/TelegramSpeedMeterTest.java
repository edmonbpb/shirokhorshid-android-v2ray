package com.psiphon3.psiphonlibrary;

import org.junit.Assert;
import org.junit.Test;

/** Pure JVM tests for the Telegram transfer speed meter. No Android classes required. */
public class TelegramSpeedMeterTest {

    @Test
    public void rateOfNormalDeltaAddsBothDirections() {
        Assert.assertEquals(4000L, TelegramSpeedMeter.rate(1000L, 500L, 4000L, 1500L, 1000L));
    }

    @Test
    public void rateScalesWithTheWindow() {
        Assert.assertEquals(1500L, TelegramSpeedMeter.rate(0L, 0L, 3000L, 0L, 2000L));
        Assert.assertEquals(333L, TelegramSpeedMeter.rate(0L, 0L, 1000L, 0L, 3000L));
    }

    @Test
    public void zeroElapsedWindowIsInvalid() {
        Assert.assertEquals(-1L, TelegramSpeedMeter.rate(0L, 0L, 100L, 0L, 0L));
    }

    @Test
    public void negativeElapsedWindowIsInvalid() {
        Assert.assertEquals(-1L, TelegramSpeedMeter.rate(0L, 0L, 100L, 0L, -5L));
    }

    @Test
    public void negativeCountersAreInvalid() {
        Assert.assertEquals(-1L, TelegramSpeedMeter.rate(-1L, 0L, 100L, 0L, 1000L));
        Assert.assertEquals(-1L, TelegramSpeedMeter.rate(0L, -1L, 100L, 0L, 1000L));
        Assert.assertEquals(-1L, TelegramSpeedMeter.rate(0L, 0L, -1L, 0L, 1000L));
        Assert.assertEquals(-1L, TelegramSpeedMeter.rate(0L, 0L, 100L, -1L, 1000L));
    }

    @Test
    public void unchangedCountersReportZero() {
        Assert.assertEquals(0L, TelegramSpeedMeter.rate(10L, 20L, 10L, 20L, 1000L));
    }

    @Test
    public void counterResetReportsZero() {
        Assert.assertEquals(0L, TelegramSpeedMeter.rate(5000L, 5000L, 4000L, 7000L, 1000L));
        Assert.assertEquals(0L, TelegramSpeedMeter.rate(5000L, 5000L, 4000L, 4000L, 1000L));
        Assert.assertEquals(0L, TelegramSpeedMeter.rate(5000L, 5000L, 7000L, 4000L, 1000L));
    }

    @Test
    public void formatUsesBytesBelowOneKilobyte() {
        Assert.assertEquals("0 B/s", TelegramSpeedMeter.format(0L));
        Assert.assertEquals("999 B/s", TelegramSpeedMeter.format(999L));
        Assert.assertEquals("1023 B/s", TelegramSpeedMeter.format(1023L));
    }

    @Test
    public void formatUsesOneDecimalForKilobytes() {
        Assert.assertEquals("1.0 KB/s", TelegramSpeedMeter.format(1024L));
        Assert.assertEquals("1.5 KB/s", TelegramSpeedMeter.format(1536L));
        Assert.assertEquals("1023.9 KB/s", TelegramSpeedMeter.format(1024L * 1024L - 1L));
    }

    @Test
    public void formatUsesOneDecimalForMegabytes() {
        Assert.assertEquals("1.0 MB/s", TelegramSpeedMeter.format(1024L * 1024L));
        Assert.assertEquals("1.5 MB/s", TelegramSpeedMeter.format(1024L * 1024L + 512L * 1024L));
        Assert.assertEquals("5.0 MB/s", TelegramSpeedMeter.format(5L * 1024L * 1024L));
    }

    @Test
    public void formatShowsAnEmDashForANegativeRate() {
        Assert.assertEquals("\u2014", TelegramSpeedMeter.format(-1L));
    }
}
