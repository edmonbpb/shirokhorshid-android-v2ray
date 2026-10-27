// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.
package app.slipnet.scanner;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads, validates and samples the resolver lists used by the SlipNet scanner. */
public final class ResolverPool {

    private static final String RESOLVERS_ASSET = "slipnet/resolvers.txt";
    private static final String GEO_ASSET_DIR = "slipnet/geo/";
    private static final String SHUFFLE_BELOW = "# SHUFFLE_BELOW";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long ENUMERATE_LIMIT = 65536L;
    private static final Random RANDOM = new Random();
    private static final Object LOCK = new Object();
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![\\d.])(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})(?![\\d.])");

    private static List<String> sResolvers;
    private static List<Integer> sBoundaries;

    private ResolverPool() {
    }

    public static List<String> defaultResolvers(Context ctx) {
        synchronized (LOCK) {
            loadLocked(ctx);
            return sResolvers;
        }
    }

    public static List<Integer> defaultTierBoundaries(Context ctx) {
        synchronized (LOCK) {
            loadLocked(ctx);
            return sBoundaries;
        }
    }

    public static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Matcher matcher = IPV4.matcher(text);
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String ip = normalizeIpv4(matcher.group(1));
            if (ip != null && seen.add(ip)) {
                out.add(ip);
            }
        }
        return out;
    }

    public static List<String> sampleCountry(Context ctx, String code, int count) {
        List<String> out = new ArrayList<>();
        if (ctx == null || code == null || count <= 0) {
            return out;
        }
        String name = code.trim().toLowerCase(Locale.US);
        if (name.isEmpty()) {
            return out;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                return out;
            }
        }
        List<Range> ranges = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open(GEO_ASSET_DIR + name + ".cidr"), UTF8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Range range = parseRange(line);
                if (range != null) {
                    ranges.add(range);
                }
            }
        } catch (IOException ignored) {
            // Unknown country or missing asset.
        }
        return sampleRanges(ranges, count);
    }

    public static List<String> expandCidrs(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || max <= 0) {
            return out;
        }
        List<Range> ranges = new ArrayList<>();
        String[] tokens = text.split("[\\s,;]+");
        for (String token : tokens) {
            Range range = parseRange(token);
            if (range != null) {
                ranges.add(range);
            }
        }
        return sampleRanges(ranges, max);
    }

    public static List<String> expandSubnet24(String ip) {
        List<String> out = new ArrayList<>(256);
        long address = parseIpv4(ip);
        if (address < 0) {
            return out;
        }
        long base = address & 0xFFFFFF00L;
        for (int host = 0; host < 256; host++) {
            out.add(formatIpv4(base + host));
        }
        return out;
    }

    /** Returns a shuffled copy; the tier before the first boundary keeps file order. */
    public static List<String> shuffleTiers(List<String> list, List<Integer> boundaries) {
        List<String> out = new ArrayList<>();
        if (list == null || list.isEmpty()) {
            return out;
        }
        out.addAll(list);
        if (boundaries == null || boundaries.isEmpty()) {
            return out;
        }
        List<Integer> cuts = new ArrayList<>();
        for (Integer boundary : boundaries) {
            if (boundary != null && boundary > 0 && boundary < out.size()) {
                cuts.add(boundary);
            }
        }
        if (cuts.isEmpty()) {
            return out;
        }
        Collections.sort(cuts);
        int start = cuts.get(0);
        for (int i = 0; i < cuts.size(); i++) {
            int end = i + 1 < cuts.size() ? cuts.get(i + 1) : out.size();
            if (end > start) {
                Collections.shuffle(out.subList(start, end), RANDOM);
            }
            start = end;
        }
        return out;
    }

    public static boolean isValidIpv4(String value) {
        return parseIpv4(value) >= 0;
    }

    private static void loadLocked(Context ctx) {
        if (sResolvers != null) {
            return;
        }
        List<String> resolvers = new ArrayList<>();
        List<Integer> boundaries = new ArrayList<>();
        if (ctx != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open(RESOLVERS_ASSET), UTF8))) {
                Set<String> seen = new HashSet<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    if (trimmed.startsWith("#")) {
                        if (trimmed.startsWith(SHUFFLE_BELOW) && !resolvers.isEmpty()) {
                            boundaries.add(resolvers.size());
                        }
                        continue;
                    }
                    String ip = normalizeIpv4(trimmed);
                    if (ip != null && seen.add(ip)) {
                        resolvers.add(ip);
                    }
                }
            } catch (IOException ignored) {
                // Missing or unreadable asset yields an empty pool.
            }
        }
        sResolvers = Collections.unmodifiableList(resolvers);
        sBoundaries = Collections.unmodifiableList(boundaries);
    }

    private static List<String> sampleRanges(List<Range> ranges, int count) {
        List<String> out = new ArrayList<>();
        if (ranges.isEmpty() || count <= 0) {
            return out;
        }
        long[] cumulative = new long[ranges.size()];
        long total = 0;
        for (int i = 0; i < ranges.size(); i++) {
            total += ranges.get(i).size;
            if (total <= 0) {
                return out;
            }
            cumulative[i] = total;
        }
        int target = (int) Math.min((long) count, total);
        if (target <= 0) {
            return out;
        }
        if (total <= ENUMERATE_LIMIT) {
            Set<Long> unique = new HashSet<>();
            List<Long> all = new ArrayList<>((int) total);
            for (Range range : ranges) {
                for (long offset = 0; offset < range.size; offset++) {
                    if (unique.add(range.base + offset)) {
                        all.add(range.base + offset);
                    }
                }
            }
            Collections.shuffle(all, RANDOM);
            int limit = Math.min(target, all.size());
            for (int i = 0; i < limit; i++) {
                out.add(formatIpv4(all.get(i)));
            }
            return out;
        }
        Set<Long> seen = new HashSet<>();
        long attempts = 0;
        long maxAttempts = (long) target * 20L + 1000L;
        while (out.size() < target && attempts < maxAttempts) {
            attempts++;
            long pick = nextLong(RANDOM, total);
            int index = rangeIndex(cumulative, pick);
            long previous = index == 0 ? 0 : cumulative[index - 1];
            long address = ranges.get(index).base + (pick - previous);
            if (seen.add(address)) {
                out.add(formatIpv4(address));
            }
        }
        return out;
    }

    private static int rangeIndex(long[] cumulative, long value) {
        int low = 0;
        int high = cumulative.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (value < cumulative[mid]) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    private static long nextLong(Random random, long bound) {
        long bits;
        long value;
        do {
            bits = random.nextLong() >>> 1;
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0L);
        return value;
    }

    private static Range parseRange(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty() || value.startsWith("#")) {
            return null;
        }
        int slash = value.indexOf('/');
        String ipPart = slash < 0 ? value : value.substring(0, slash);
        long address = parseIpv4(ipPart);
        if (address < 0) {
            return null;
        }
        int prefix = 32;
        if (slash >= 0) {
            String prefixPart = value.substring(slash + 1).trim();
            if (prefixPart.isEmpty() || prefixPart.length() > 2) {
                return null;
            }
            for (int i = 0; i < prefixPart.length(); i++) {
                char c = prefixPart.charAt(i);
                if (c < '0' || c > '9') {
                    return null;
                }
            }
            prefix = Integer.parseInt(prefixPart);
            if (prefix > 32) {
                return null;
            }
        }
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return new Range(address & mask, 1L << (32 - prefix));
    }

    private static String normalizeIpv4(String value) {
        long address = parseIpv4(value);
        return address < 0 ? null : formatIpv4(address);
    }

    private static long parseIpv4(String value) {
        if (value == null) {
            return -1L;
        }
        String text = value.trim();
        int length = text.length();
        if (length < 7 || length > 15) {
            return -1L;
        }
        long address = 0;
        int part = 0;
        int digits = 0;
        int octet = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                if (digits == 3) {
                    return -1L;
                }
                octet = octet * 10 + (c - '0');
                digits++;
                if (octet > 255) {
                    return -1L;
                }
            } else if (c == '.') {
                if (digits == 0 || part == 3) {
                    return -1L;
                }
                address = (address << 8) | octet;
                part++;
                octet = 0;
                digits = 0;
            } else {
                return -1L;
            }
        }
        if (digits == 0 || part != 3) {
            return -1L;
        }
        return (address << 8) | octet;
    }

    private static String formatIpv4(long address) {
        return ((address >>> 24) & 0xFFL) + "." + ((address >>> 16) & 0xFFL) + "."
                + ((address >>> 8) & 0xFFL) + "." + (address & 0xFFL);
    }

    private static final class Range {
        final long base;
        final long size;

        Range(long base, long size) {
            this.base = base;
            this.size = size;
        }
    }
}
