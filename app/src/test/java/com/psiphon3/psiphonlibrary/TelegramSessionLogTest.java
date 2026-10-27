package com.psiphon3.psiphonlibrary;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** Pure JVM tests for the Telegram session log. No Android classes required. */
public class TelegramSessionLogTest {

    @Before
    public void reset() {
        TelegramSessionLog.clear();
    }

    @Test
    public void appendKeepsTheOldestLineFirst() {
        TelegramSessionLog.append('D', "first");
        TelegramSessionLog.append('I', "second");
        String[] lines = TelegramSessionLog.snapshot().split("\n", -1);
        Assert.assertEquals(2, lines.length);
        Assert.assertTrue(lines[0].matches("\\d{2}:\\d{2}:\\d{2} D first"));
        Assert.assertTrue(lines[1].matches("\\d{2}:\\d{2}:\\d{2} I second"));
    }

    @Test
    public void nullMessageBecomesAnEmptyEntry() {
        TelegramSessionLog.append('W', null);
        Assert.assertTrue(TelegramSessionLog.snapshot().matches("\\d{2}:\\d{2}:\\d{2} W "));
    }

    @Test
    public void clearEmptiesTheSnapshot() {
        TelegramSessionLog.append('I', "one");
        TelegramSessionLog.clear();
        Assert.assertEquals("", TelegramSessionLog.snapshot());
    }

    @Test
    public void ringDropsTheOldestLinesPastTheLimit() {
        Assert.assertEquals(200, TelegramSessionLog.MAX_LINES);
        for (int n = 0; n < TelegramSessionLog.MAX_LINES + 5; n++) {
            TelegramSessionLog.append('I', "line " + n);
        }
        String[] lines = TelegramSessionLog.snapshot().split("\n", -1);
        Assert.assertEquals(TelegramSessionLog.MAX_LINES, lines.length);
        Assert.assertTrue(lines[0].endsWith(" I line 5"));
        Assert.assertTrue(lines[TelegramSessionLog.MAX_LINES - 1].endsWith(" I line 204"));
    }

    @Test
    public void urlsAreRedacted() {
        TelegramSessionLog.append('I', "GET https://web.telegram.org/a/ now");
        TelegramSessionLog.append('I', "GET http://example.org/x?k=1 later");
        String snapshot = TelegramSessionLog.snapshot();
        Assert.assertTrue(snapshot.contains("[url] now"));
        Assert.assertTrue(snapshot.contains("[url] later"));
        Assert.assertFalse(snapshot.contains("telegram.org"));
        Assert.assertFalse(snapshot.contains("example.org"));
        Assert.assertFalse(snapshot.contains("https"));
        Assert.assertFalse(snapshot.contains("http"));
    }

    @Test
    public void redactionCoversBothSchemesInOneMessage() {
        TelegramSessionLog.append('I', "from HTTPS://a.example/x to http://b.example/y end");
        String snapshot = TelegramSessionLog.snapshot();
        Assert.assertTrue(snapshot.endsWith(" I from [url] to [url] end"));
        Assert.assertEquals(1, snapshot.split("\n", -1).length);
    }

    @Test
    public void multiLineMessagesBecomeOneEntry() {
        TelegramSessionLog.append('W', "first\nsecond\r\nthird");
        String[] lines = TelegramSessionLog.snapshot().split("\n", -1);
        Assert.assertEquals(1, lines.length);
        Assert.assertTrue(lines[0].matches("\\d{2}:\\d{2}:\\d{2} W first second  third"));
    }

    @Test
    public void longLinesAreCappedAtThreeHundredChars() {
        StringBuilder message = new StringBuilder();
        for (int n = 0; n < 500; n++) {
            message.append('a');
        }
        TelegramSessionLog.append('E', message.toString());
        String line = TelegramSessionLog.snapshot();
        Assert.assertEquals(300, line.length());
        Assert.assertTrue(line.endsWith("a"));
    }
}
