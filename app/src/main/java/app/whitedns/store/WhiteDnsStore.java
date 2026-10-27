package app.whitedns.store;

import android.content.Context;

import net.grandcentrix.tray.TrayPreferences;

import app.whitedns.model.WhiteDnsProfile;

/** Single stored WhiteDNS profile, kept in the Tray prefs module "whitedns_prefs". */
public final class WhiteDnsStore {

    private static final String PREFS = "whitedns_prefs";
    private static final int PREFS_VERSION = 1;
    private static final String KEY_NAME = "whitedns_name";
    private static final String KEY_DOMAIN = "whitedns_domain";
    private static final String KEY_KEY = "whitedns_key";
    private static final String KEY_MODE = "whitedns_mode";
    private static final String KEY_RESOLVERS = "whitedns_resolvers";
    private static final String KEY_METHOD = "whitedns_method";
    private static final String KEY_ENGINE = "whitedns_engine";
    /** SOAR engine JSON as uploaded; never logged, never shown. */
    private static final String KEY_RAW = "whitedns_raw";

    private WhiteDnsStore() {
    }

    public static void save(Context ctx, WhiteDnsProfile p) {
        if (ctx == null || p == null) {
            return;
        }
        TrayPreferences prefs = prefs(ctx);
        prefs.put(KEY_NAME, p.name);
        prefs.put(KEY_DOMAIN, p.domain);
        prefs.put(KEY_KEY, p.key);
        prefs.put(KEY_MODE, p.mode == null ? WhiteDnsProfile.Mode.NORMAL.name() : p.mode.name());
        prefs.put(KEY_RESOLVERS, p.resolvers);
        prefs.put(KEY_METHOD, p.method);
        prefs.put(KEY_ENGINE, p.engine.name());
        if (p.engine == WhiteDnsProfile.Engine.SOAR
                && p.rawConfig != null && p.rawConfig.length() > 0) {
            prefs.put(KEY_RAW, p.rawConfig);
        } else {
            prefs.remove(KEY_RAW);
        }
    }

    public static WhiteDnsProfile load(Context ctx) {
        if (ctx == null) {
            return null;
        }
        TrayPreferences prefs = prefs(ctx);
        String domain = prefs.getString(KEY_DOMAIN, "");
        String key = prefs.getString(KEY_KEY, "");
        if (domain == null || domain.trim().length() == 0) {
            return null;
        }
        if (key == null || key.trim().length() == 0) {
            return null;
        }
        String name = prefs.getString(KEY_NAME, "");
        String resolvers = prefs.getString(KEY_RESOLVERS, "");
        String raw = prefs.getString(KEY_RAW, "");
        if (raw == null || raw.trim().length() == 0) {
            raw = null;
        }
        return new WhiteDnsProfile(name, domain, key, modeOf(prefs.getString(KEY_MODE, "")),
                resolvers, prefs.getInt(KEY_METHOD, 3),
                engineOf(prefs.getString(KEY_ENGINE, "")), raw);
    }

    public static boolean hasProfile(Context ctx) {
        return load(ctx) != null;
    }

    public static void clear(Context ctx) {
        if (ctx == null) {
            return;
        }
        TrayPreferences prefs = prefs(ctx);
        prefs.remove(KEY_NAME);
        prefs.remove(KEY_DOMAIN);
        prefs.remove(KEY_KEY);
        prefs.remove(KEY_MODE);
        prefs.remove(KEY_RESOLVERS);
        prefs.remove(KEY_METHOD);
        prefs.remove(KEY_ENGINE);
        prefs.remove(KEY_RAW);
    }

    private static TrayPreferences prefs(Context ctx) {
        return new TrayPreferences(ctx, PREFS, PREFS_VERSION);
    }

    private static WhiteDnsProfile.Engine engineOf(String value) {
        if (value != null) {
            try {
                return WhiteDnsProfile.Engine.valueOf(value.trim());
            } catch (IllegalArgumentException ignored) {
                return WhiteDnsProfile.Engine.COTTEN;
            }
        }
        return WhiteDnsProfile.Engine.COTTEN;
    }

    private static WhiteDnsProfile.Mode modeOf(String value) {
        if (value != null) {
            try {
                return WhiteDnsProfile.Mode.valueOf(value.trim());
            } catch (IllegalArgumentException ignored) {
                return WhiteDnsProfile.Mode.NORMAL;
            }
        }
        return WhiteDnsProfile.Mode.NORMAL;
    }
}
