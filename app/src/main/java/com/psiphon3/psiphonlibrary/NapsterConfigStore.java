/*
 * Copyright (c) 2026, ShirOKhorshid
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.content.Context;
import android.util.Log;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Cross-process state for Napster: metadata in Tray preferences, bodies as files.
 */
public final class NapsterConfigStore {

    private static final String TAG = "Napster";
    private static final String PREF_ENABLED = "napster_enabled";
    private static final String PREF_ACTIVE_ID = "napster_active_id";
    private static final String PREF_CONFIG_LIST = "napster_config_list";
    private static final String CONFIG_DIR = "napster/configs";
    private static final String DEFAULT_NAME = "Config";
    private static final int MAX_NAME_LENGTH = 80;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();
    private static final Comparator<ConfigEntry> NEWEST_FIRST = (first, second) -> {
        if (first.createdAt == second.createdAt) return 0;
        return first.createdAt > second.createdAt ? -1 : 1;
    };

    private NapsterConfigStore() { }

    public static final class ConfigEntry {
        public final String id;
        public final String name;
        public final long createdAt;

        public ConfigEntry(String id, String name, long createdAt) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
        }
    }

    public static boolean isFeatureEnabled(Context context) {
        return new AppPreferences(context).getBoolean(PREF_ENABLED, false);
    }

    public static void setFeatureEnabled(Context context, boolean enabled) {
        new AppPreferences(context).put(PREF_ENABLED, enabled);
    }

    public static List<ConfigEntry> getConfigs(Context context) {
        List<ConfigEntry> configs = readEntries(context);
        Collections.sort(configs, NEWEST_FIRST);
        return configs;
    }

    public static ConfigEntry getActiveConfig(Context context) {
        String id = activeConfigId(context);
        if (id == null) return null;
        for (ConfigEntry entry : getConfigs(context)) {
            if (!id.equals(entry.id)) continue;
            File file = configFile(context, entry.id);
            return file != null && file.isFile() ? entry : null;
        }
        return null;
    }

    public static void setActiveConfigId(Context context, String id) {
        synchronized (LOCK) {
            AppPreferences preferences = new AppPreferences(context);
            if (id == null) preferences.remove(PREF_ACTIVE_ID);
            else preferences.put(PREF_ACTIVE_ID, id);
        }
    }

    public static ConfigEntry addConfig(Context context, String name, String body) {
        ConfigEntry entry = new ConfigEntry(newId(), cleanName(name), System.currentTimeMillis());
        synchronized (LOCK) {
            File file = configFile(context, entry.id);
            if (file == null) throw new IllegalStateException("invalid Napster config id");
            writeBody(file, body == null ? "" : body);
            List<ConfigEntry> entries = readEntries(context);
            entries.add(0, entry);
            writeEntries(context, entries);
        }
        return entry;
    }

    public static void deleteConfig(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        synchronized (LOCK) {
            File file = configFile(context, id);
            if (file != null && !file.delete() && file.exists()) Log.w(TAG, "Could not delete a Napster config file");
            List<ConfigEntry> entries = readEntries(context);
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (id.equals(entries.get(i).id)) entries.remove(i);
            }
            writeEntries(context, entries);
            if (id.equals(activeConfigId(context))) setActiveConfigId(context, null);
        }
    }

    public static String readConfigBody(Context context, String id) {
        try {
            File file = configFile(context, id);
            if (file == null || !file.isFile()) return null;
            try (InputStream input = new FileInputStream(file)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                return new String(output.toByteArray(), "UTF-8");
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read a Napster config"); return null;
        } catch (RuntimeException e) { return null; }
    }

    private static String activeConfigId(Context context) {
        try { return new AppPreferences(context).getString(PREF_ACTIVE_ID, null); }
        catch (RuntimeException e) { Log.w(TAG, "Could not read the active Napster config id"); return null; }
    }

    private static List<ConfigEntry> readEntries(Context context) {
        List<ConfigEntry> entries = new ArrayList<>();
        String json;
        try { json = new AppPreferences(context).getString(PREF_CONFIG_LIST, "[]"); }
        catch (RuntimeException e) { Log.w(TAG, "Could not read the Napster config list"); return entries; }
        if (json == null || json.trim().isEmpty()) return entries;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (id.isEmpty()) continue;
                entries.add(new ConfigEntry(id, item.optString("name", DEFAULT_NAME),
                        item.optLong("createdAt", 0L)));
            }
        } catch (JSONException e) {
            Log.w(TAG, "The Napster config list is corrupt");
            entries.clear();
        } catch (RuntimeException e) {
            entries.clear();
        }
        return entries;
    }

    private static void writeEntries(Context context, List<ConfigEntry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (ConfigEntry entry : entries) {
                array.put(new JSONObject().put("id", entry.id).put("name", entry.name)
                        .put("createdAt", entry.createdAt));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Could not save the Napster config list");
            throw new IllegalStateException("could not save the Napster config list", e);
        }
        new AppPreferences(context).put(PREF_CONFIG_LIST, array.toString());
    }

    private static void writeBody(File file, String body) {
        File directory = file.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IllegalStateException("could not create the Napster folder");
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(body.getBytes("UTF-8"));
        } catch (IOException e) {
            Log.w(TAG, "Could not write a Napster config");
            throw new IllegalStateException("could not write the Napster config", e);
        }
    }

    private static File configFile(Context context, String id) {
        if (!isValidId(id)) return null;
        return new File(new File(context.getFilesDir(), CONFIG_DIR), id + ".json");
    }

    private static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && id.indexOf('/') < 0 && id.indexOf('\\') < 0
                && !id.contains("..");
    }

    private static String cleanName(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.length() > MAX_NAME_LENGTH) cleaned = cleaned.substring(0, MAX_NAME_LENGTH);
        return cleaned.isEmpty() ? DEFAULT_NAME : cleaned;
    }

    private static String newId() {
        StringBuilder id = new StringBuilder(24).append(System.currentTimeMillis()).append('-');
        for (int i = 0; i < 8; i++) id.append(HEX[RANDOM.nextInt(HEX.length)]);
        return id.toString();
    }
}
