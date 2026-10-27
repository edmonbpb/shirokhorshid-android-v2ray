package com.psiphon3.psiphonlibrary;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure JVM tests for the server ordering used by the Telegram server picker. */
public class TelegramServerCatalogTest {

    private static TelegramServer server(String id, String name) {
        return new TelegramServer(id, name, "server.example", 443);
    }

    private static List<String> idsOf(List<TelegramServer> servers) {
        List<String> ids = new ArrayList<>();
        for (TelegramServer server : servers) {
            ids.add(server == null ? null : server.id);
        }
        return ids;
    }

    @Test
    public void quickestPingComesFirst() {
        List<TelegramServer> servers = new ArrayList<>(Arrays.asList(
                server("slow", "Slow"), server("fast", "Fast"), server("middle", "Middle")));
        Map<String, Integer> pings = new HashMap<>();
        pings.put("slow", 80);
        pings.put("fast", 20);
        pings.put("middle", 45);

        TelegramServerCatalog.sortByPing(servers, pings);

        Assert.assertEquals(Arrays.asList("fast", "middle", "slow"), idsOf(servers));
    }

    @Test
    public void failedOrMissingPingsGoLast() {
        List<TelegramServer> servers = new ArrayList<>(Arrays.asList(
                server("missing", "Aaa"), server("failed", "Zzz"), server("alive", "Mmm")));
        Map<String, Integer> pings = new HashMap<>();
        pings.put("failed", -1);
        pings.put("alive", 30);

        TelegramServerCatalog.sortByPing(servers, pings);

        Assert.assertEquals(Arrays.asList("alive", "missing", "failed"), idsOf(servers));
    }

    @Test
    public void equalPingsAreOrderedByNameIgnoringCase() {
        List<TelegramServer> servers = new ArrayList<>(Arrays.asList(
                server("upper", "Banana"), server("lower", "apple")));
        Map<String, Integer> pings = new HashMap<>();
        pings.put("upper", 50);
        pings.put("lower", 50);

        TelegramServerCatalog.sortByPing(servers, pings);

        Assert.assertEquals(Arrays.asList("lower", "upper"), idsOf(servers));
    }

    @Test
    public void sortingIsNullSafe() {
        TelegramServerCatalog.sortByPing(null, null);
        TelegramServerCatalog.sortByPing(null, new HashMap<String, Integer>());

        List<TelegramServer> empty = new ArrayList<>();
        TelegramServerCatalog.sortByPing(empty, null);
        Assert.assertTrue(empty.isEmpty());

        List<TelegramServer> single = new ArrayList<>(Arrays.asList(server("only", "Only")));
        TelegramServerCatalog.sortByPing(single, null);
        Assert.assertEquals(Arrays.asList("only"), idsOf(single));

        List<TelegramServer> named = new ArrayList<>(Arrays.asList(
                server("second", "b"), server("first", "A")));
        TelegramServerCatalog.sortByPing(named, null);
        Assert.assertEquals(Arrays.asList("first", "second"), idsOf(named));
    }

    @Test
    public void nullEntriesGoLast() {
        List<TelegramServer> servers = new ArrayList<>(Arrays.asList(
                null, server("b", "B"), server("a", "A")));
        Map<String, Integer> pings = new HashMap<>();
        pings.put("a", 10);
        pings.put("b", 20);

        TelegramServerCatalog.sortByPing(servers, pings);

        Assert.assertEquals(Arrays.asList("a", "b", null), idsOf(servers));
    }

    @Test
    public void sortingDoesNotChangeThePingMap() {
        List<TelegramServer> servers = new ArrayList<>(Arrays.asList(
                server("a", "A"), server("b", "B"), server("c", "C")));
        Map<String, Integer> pings = new HashMap<>();
        pings.put("a", 70);
        pings.put("b", -1);
        pings.put("c", 15);
        pings.put("not-in-list", 5);
        Map<String, Integer> copy = new HashMap<>(pings);

        TelegramServerCatalog.sortByPing(servers, pings);

        Assert.assertEquals(copy, pings);
        Assert.assertEquals(4, pings.size());
        Assert.assertEquals(Integer.valueOf(5), pings.get("not-in-list"));
    }
}
