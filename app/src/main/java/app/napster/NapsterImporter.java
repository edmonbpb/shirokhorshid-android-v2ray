package app.napster;

import android.content.Context;

import com.psiphon3.psiphonlibrary.V2RayManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Public facade for importing Napster .npvt (legacy) and .npvs (gen2) config files.
 * Caller runs importBytes on a worker thread.
 */
public final class NapsterImporter {

    public static final int MAX_FILE_SIZE = 8 * 1024 * 1024; // 8 MiB
    public static final int MAX_ITEMS = 200;

    public static final class Item {
        public final String name;
        public final String body;

        public Item(String name, String body) {
            this.name = name != null ? name.trim() : "Napster";
            this.body = body != null ? body.trim() : "";
        }
    }

    public static final class Result {
        public final List<Item> items;
        public final List<String> errors;

        public Result(List<Item> items, List<String> errors) {
            this.items = items != null ? items : new ArrayList<Item>();
            this.errors = errors != null ? errors : new ArrayList<String>();
        }
    }

    public static final class NapsterException extends Exception {
        public NapsterException(String message) {
            super(message);
        }

        public NapsterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private NapsterImporter() {
    }

    public static Result importBytes(Context context, byte[] data) throws NapsterException {
        if (data == null || data.length == 0) {
            throw new NapsterException("file is empty");
        }
        if (data.length > MAX_FILE_SIZE) {
            throw new NapsterException("file too large");
        }

        List<Item> items = new ArrayList<Item>();
        List<String> errors = new ArrayList<String>();

        try {
            if (NapsterLegacy.isLegacy(data)) {
                importLegacy(context, data, items, errors);
            } else if (NapsterGen2.isGen2(data)) {
                importGen2(context, data, items, errors);
            } else {
                throw new NapsterException("unsupported Napster file");
            }
        } catch (NapsterException e) {
            throw e;
        } catch (Exception e) {
            throw new NapsterException(e.getMessage() != null ? e.getMessage() : "failed to parse Napster file", e);
        }

        if (items.isEmpty()) {
            if (!errors.isEmpty()) {
                throw new NapsterException(errors.get(0));
            }
            throw new NapsterException("no valid configurations found");
        }

        return new Result(items, errors);
    }

    private static void importLegacy(Context context, byte[] data, List<Item> items, List<String> errors)
            throws Exception {
        InputStream is = null;
        byte[] tables;
        try {
            is = context.getAssets().open("napster/legacy_tables.bin");
            tables = NapsterTables.getLegacyTables(is);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }

        List<byte[]> payloads = NapsterLegacy.decryptPayload(data, tables);
        for (int i = 0; i < payloads.size() && items.size() < MAX_ITEMS; i++) {
            byte[] p = payloads.get(i);
            try {
                String text = new String(p, "UTF-8").trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.startsWith("{") || text.startsWith("[")) {
                    Object root = new JSONTokener(text).nextValue();
                    collectLegacyConfigs(root, context, items, errors, i + 1);
                    continue;
                }
                if (isShareLink(text)) {
                    String json = V2RayManager.convertShareLinks(context, text);
                    String name = extractLinkName(text, "Napster " + (items.size() + 1));
                    items.add(new Item(name, json));
                }
            } catch (Exception e) {
                errors.add("Token " + (i + 1) + ": "
                        + (e.getMessage() != null ? e.getMessage() : "decode error"));
            }
        }
    }

    private static void collectLegacyConfigs(Object node, Context context,
            List<Item> items, List<String> errors, int token) {
        if (node == null || items.size() >= MAX_ITEMS) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && items.size() < MAX_ITEMS; i++) {
                collectLegacyConfigs(array.opt(i), context, items, errors, token);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject obj = (JSONObject) node;
        JSONObject profile = obj.optJSONObject("v2rayProfile");
        if (profile != null) {
            String link = NapsterGen2.buildShareLink(
                    resolveName(obj, "Napster " + (items.size() + 1)),
                    obj.optString("address", ""), profile);
            if (isShareLink(link)) {
                try {
                    String json = V2RayManager.convertShareLinks(context, link);
                    items.add(new Item(resolveName(obj, "Napster " + (items.size() + 1)), json));
                } catch (Exception e) {
                    errors.add("Token " + token + ": could not convert the configuration");
                }
            }
            return;
        }
        Object embedded = obj.opt("v2rayJson");
        if (embedded != null && embedded != JSONObject.NULL) {
            String body = embedded instanceof JSONObject
                    ? embedded.toString() : String.valueOf(embedded).trim();
            if (isShareLink(body)) {
                try {
                    String json = V2RayManager.convertShareLinks(context, body);
                    items.add(new Item(resolveName(obj, "Napster " + (items.size() + 1)), json));
                } catch (Exception e) {
                    errors.add("Token " + token + ": could not convert the configuration");
                }
            } else if (body.startsWith("{")) {
                items.add(new Item(resolveName(obj, "Napster " + (items.size() + 1)), body));
            }
            return;
        }
        if (obj.has("outbounds")) {
            items.add(new Item(resolveName(obj, "Napster " + (items.size() + 1)), obj.toString()));
            return;
        }
        java.util.Iterator<String> keys = obj.keys();
        while (keys.hasNext() && items.size() < MAX_ITEMS) {
            Object value = obj.opt(keys.next());
            if (value instanceof JSONObject || value instanceof JSONArray) {
                collectLegacyConfigs(value, context, items, errors, token);
            }
        }
    }

    private static void importGen2(Context context, byte[] data, List<Item> items, List<String> errors)
            throws Exception {
        InputStream is = null;
        byte[] tables;
        try {
            is = context.getAssets().open("napster/gen2_tables.bin.z");
            tables = NapsterTables.getGen2Tables(is);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }

        List<String> links = NapsterGen2.open(data, tables);
        for (int i = 0; i < links.size() && items.size() < MAX_ITEMS; i++) {
            String link = links.get(i);
            try {
                if (link.trim().startsWith("{")) {
                    String name = resolveGen2BodyName(link, "Napster " + (items.size() + 1));
                    items.add(new Item(name, link));
                    continue;
                }
                String json = V2RayManager.convertShareLinks(context, link);
                String name = extractLinkName(link, "Napster " + (items.size() + 1));
                items.add(new Item(name, json));
            } catch (Exception e) {
                errors.add("Item " + (i + 1) + ": could not convert the configuration");
            }
        }
    }

    private static String resolveGen2BodyName(String body, String fallback) {
        try {
            JSONObject obj = new JSONObject(body);
            String name = obj.optString("remarks", null);
            if (name == null || name.trim().isEmpty()) {
                name = obj.optString("ps", null);
            }
            if (name != null && !name.trim().isEmpty()) {
                return trimName(name);
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static boolean isShareLink(String text) {
        if (text == null) return false;
        String s = text.toLowerCase();
        return s.startsWith("vless://") || s.startsWith("vmess://")
                || s.startsWith("trojan://") || s.startsWith("ss://");
    }

    private static String resolveName(JSONObject obj, String fallback) {
        String name = obj.optString("remarks", null);
        if (name == null || name.trim().isEmpty()) {
            name = obj.optString("name", null);
        }
        if (name == null || name.trim().isEmpty()) {
            name = fallback;
        }
        return trimName(name);
    }

    private static String extractLinkName(String link, String fallback) {
        int hash = link.indexOf('#');
        if (hash >= 0 && hash + 1 < link.length()) {
            try {
                String frag = link.substring(hash + 1).trim();
                String decoded = URLDecoder.decode(frag, "UTF-8");
                if (!decoded.trim().isEmpty()) {
                    return trimName(decoded);
                }
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private static String trimName(String s) {
        if (s == null) return "Napster";
        s = s.trim();
        if (s.length() > 80) {
            s = s.substring(0, 80);
        }
        return s;
    }
}
