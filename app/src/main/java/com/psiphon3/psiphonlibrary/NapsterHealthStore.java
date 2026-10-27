package com.psiphon3.psiphonlibrary;

import android.content.Context;
import android.util.Log;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/** Persists Napster health results and the auto-check switch. */
public final class NapsterHealthStore {

    private static final String TAG = "NapsterHealth";
    private static final String KEY_ENTRIES = "napster_health";
    private static final String KEY_AUTO = "napster_health_auto";
    private static final Object LOCK = new Object();

    public static final class Health {
        public final long at;
        public final String status;
        public final int generalMs;
        public final int telegramMs;
        public final int failStreak;
        public final String network;

        public Health(long at, String status, int generalMs, int telegramMs,
                int failStreak, String network) {
            this.at = at;
            this.status = status == null ? "UNKNOWN" : status;
            this.generalMs = generalMs;
            this.telegramMs = telegramMs;
            this.failStreak = failStreak;
            this.network = network == null ? "" : network;
        }
    }

    private NapsterHealthStore() {
    }

    public static void save(Context context, String id, Health health) {
        if (context == null || id == null || id.isEmpty() || health == null) {
            return;
        }
        synchronized (LOCK) {
            JSONObject root = readRoot(context);
            try {
                root.put(id, new JSONObject()
                        .put("at", health.at)
                        .put("status", health.status)
                        .put("generalMs", health.generalMs)
                        .put("telegramMs", health.telegramMs)
                        .put("failStreak", health.failStreak)
                        .put("network", health.network));
            } catch (JSONException e) {
                Log.w(TAG, "Could not encode a health result");
                return;
            }
            new AppPreferences(context).put(KEY_ENTRIES, root.toString());
        }
    }

    public static Health load(Context context, String id) {
        if (context == null || id == null || id.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            JSONObject entry = readRoot(context).optJSONObject(id);
            if (entry == null) {
                return null;
            }
            return new Health(entry.optLong("at", 0L), entry.optString("status", "UNKNOWN"),
                    entry.optInt("generalMs", 0), entry.optInt("telegramMs", 0),
                    entry.optInt("failStreak", 0), entry.optString("network", ""));
        }
    }

    public static boolean isAutoEnabled(Context context) {
        try {
            return new AppPreferences(context).getBoolean(KEY_AUTO, true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static void setAutoEnabled(Context context, boolean enabled) {
        new AppPreferences(context).put(KEY_AUTO, enabled);
    }

    private static JSONObject readRoot(Context context) {
        String raw;
        try {
            raw = new AppPreferences(context).getString(KEY_ENTRIES, "{}");
        } catch (RuntimeException e) {
            return new JSONObject();
        }
        if (raw == null || raw.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            Log.w(TAG, "The health store is corrupt");
            return new JSONObject();
        }
    }
}
