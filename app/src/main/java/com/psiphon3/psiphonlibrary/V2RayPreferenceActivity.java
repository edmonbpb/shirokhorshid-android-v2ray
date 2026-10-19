/*
 * Copyright (c) 2026, Shir o Khorshid contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.psiphon3.psiphonlibrary;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.psiphon3.MainActivityViewModel;
import com.psiphon3.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class V2RayPreferenceActivity extends LocalizedActivities.AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, new V2RayPreferenceFragment())
                    .commit();
        }

        MainActivityViewModel viewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(MainActivityViewModel.class);
        getLifecycle().addObserver(viewModel);
    }

    public static class V2RayPreferenceFragment extends PsiphonPreferenceFragmentCompat {
        private static final String KEY_ENABLED = "v2ray_enabled";
        private static final String KEY_ACTIVE_CONFIG = "v2ray_active_config";
        private static final String KEY_ADD_PASTE = "v2ray_add_paste";
        private static final String KEY_ADD_FILE = "v2ray_add_file";
        private static final String KEY_DELETE_CONFIG = "v2ray_delete_config";
        private static final String KEY_TEST_CONFIG = "v2ray_test_config";
        private static final String KEY_SHOW_LOG = "v2ray_show_log";
        private static final String KEY_CORE_VERSION = "v2ray_core_version";

        private static final int REQUEST_CODE_PICK_FILE = 2001;
        private static final int LOG_TAIL_LINES = 300;
        private static final long MAX_CONFIG_FILE_BYTES = 4L * 1024L * 1024L;
        private static final String DEFAULT_CONFIG_NAME = "Config";
        private static final Pattern REMARKS_PATTERN =
                Pattern.compile("\"remarks\"\\s*:\\s*\"([^\"]{1,60})\"");

        private SwitchPreferenceCompat enabledPreference;
        private ListPreference activeConfigPreference;
        private Preference deletePreference;
        private Preference testPreference;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            super.onCreatePreferences(savedInstanceState, rootKey);
            addPreferencesFromResource(R.xml.v2ray_preferences);

            enabledPreference = findPreference(KEY_ENABLED);
            activeConfigPreference = findPreference(KEY_ACTIVE_CONFIG);
            deletePreference = findPreference(KEY_DELETE_CONFIG);
            testPreference = findPreference(KEY_TEST_CONFIG);

            enabledPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                Activity activity = getActivity();
                if (activity == null) {
                    return false;
                }
                V2RayConfigStore.setFeatureEnabled(activity.getApplicationContext(), (Boolean) newValue);
                refreshPreferenceState();
                return true;
            });

            activeConfigPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                Activity activity = getActivity();
                if (activity == null) {
                    return false;
                }
                V2RayConfigStore.setActiveConfigId(activity.getApplicationContext(), (String) newValue);
                runOnUiThreadIfAlive(this::refreshPreferenceState);
                return true;
            });

            findPreference(KEY_ADD_PASTE).setOnPreferenceClickListener(p -> { showPasteDialog(); return true; });
            findPreference(KEY_ADD_FILE).setOnPreferenceClickListener(p -> { pickConfigFile(); return true; });
            deletePreference.setOnPreferenceClickListener(p -> { confirmDelete(); return true; });
            testPreference.setOnPreferenceClickListener(p -> { testActiveConfig(); return true; });
            findPreference(KEY_SHOW_LOG).setOnPreferenceClickListener(p -> { showLog(); return true; });
            findPreference(KEY_CORE_VERSION).setOnPreferenceClickListener(p -> { showCoreVersion(); return true; });

            refreshPreferenceState();
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            if (requestCode == REQUEST_CODE_PICK_FILE) {
                if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                    startFileImport(data.getData());
                }
                return;
            }
            super.onActivityResult(requestCode, resultCode, data);
        }

        private void refreshPreferenceState() {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            Context appContext = activity.getApplicationContext();

            List<V2RayConfigStore.ConfigEntry> configs = V2RayConfigStore.getConfigs(appContext);
            V2RayConfigStore.ConfigEntry active = V2RayConfigStore.getActiveConfig(appContext);
            boolean enabled = V2RayConfigStore.isFeatureEnabled(appContext);

            CharSequence[] entries = new CharSequence[configs.size()];
            CharSequence[] values = new CharSequence[configs.size()];
            for (int i = 0; i < configs.size(); i++) {
                entries[i] = configs.get(i).name;
                values[i] = configs.get(i).id;
            }

            activeConfigPreference.setEntries(entries);
            activeConfigPreference.setEntryValues(values);
            activeConfigPreference.setValue(active == null ? null : active.id);
            activeConfigPreference.setSummary(active == null
                    ? getString(R.string.v2ray_no_config) : active.name);
            activeConfigPreference.setEnabled(!configs.isEmpty());

            enabledPreference.setChecked(enabled);
            enabledPreference.setSummary(buildEnabledSummary(enabled, active != null));

            deletePreference.setEnabled(active != null);
            testPreference.setEnabled(active != null);
        }

        private String buildEnabledSummary(boolean enabled, boolean hasActiveConfig) {
            String summary;
            if (!enabled) {
                summary = getString(R.string.v2ray_enabled_summary_off);
            } else {
                summary = getString(hasActiveConfig
                        ? R.string.v2ray_enabled_summary_on
                        : R.string.v2ray_enabled_summary_needs_config);
            }
            return summary + "\n" + getString(R.string.v2ray_reconnect_hint);
        }

        private void showPasteDialog() {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }

            final EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setMinLines(4);
            input.setGravity(Gravity.TOP | Gravity.START);

            CharSequence clipboardText = readClipboardText(activity);
            if (clipboardText != null) {
                input.setText(clipboardText);
                input.setSelection(input.length());
            }

            new AlertDialog.Builder(activity)
                    .setTitle(R.string.v2ray_add_dialog_title)
                    .setView(withPadding(activity, input))
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> startImport(input.getText().toString()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void pickConfigFile() {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            } else {
                // ACTION_OPEN_DOCUMENT exists from API 19; the app supports API 14.
                intent = new Intent(Intent.ACTION_GET_CONTENT);
            }
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            try {
                startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(activity,
                        getString(R.string.v2ray_import_failed, messageOf(e)),
                        Toast.LENGTH_LONG).show();
            }
        }

        private void confirmDelete() {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.v2ray_delete_title)
                    .setMessage(R.string.v2ray_delete_confirm)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteActiveConfig())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void deleteActiveConfig() {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            final Context appContext = activity.getApplicationContext();
            new Thread(() -> {
                V2RayConfigStore.ConfigEntry active = V2RayConfigStore.getActiveConfig(appContext);
                if (active != null) {
                    V2RayConfigStore.deleteConfig(appContext, active.id);
                }
                runOnUiThreadIfAlive(this::refreshPreferenceState);
            }, "V2RayDeleteConfig").start();
        }

        private void testActiveConfig() {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            final Context appContext = activity.getApplicationContext();
            new Thread(() -> {
                V2RayConfigStore.ConfigEntry active = V2RayConfigStore.getActiveConfig(appContext);
                String body = active == null ? null : V2RayConfigStore.readConfigBody(appContext, active.id);
                if (body == null) {
                    runOnUiThreadIfAlive(() -> showToast(getString(R.string.v2ray_no_active_config_test)));
                    return;
                }
                final String error = V2RayManager.testConfig(appContext, body);
                runOnUiThreadIfAlive(() -> {
                    String message = error == null
                            ? getString(R.string.v2ray_test_ok)
                            : getString(R.string.v2ray_test_failed) + "\n" + error;
                    new AlertDialog.Builder(getActivity())
                            .setTitle(R.string.v2ray_test_title)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }, "V2RayTestConfig").start();
        }

        private void showLog() {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            final Context appContext = activity.getApplicationContext();
            new Thread(() -> {
                final String log = V2RayManager.readLogTail(appContext, LOG_TAIL_LINES);
                runOnUiThreadIfAlive(() -> {
                    Activity current = getActivity();
                    TextView textView = new TextView(current);
                    textView.setTypeface(Typeface.MONOSPACE);
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    textView.setTextIsSelectable(true);
                    textView.setText(log);

                    int logHeight = Math.round(320 * current.getResources().getDisplayMetrics().density);
                    ScrollView scrollView = new ScrollView(current);
                    scrollView.setLayoutParams(new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, logHeight));
                    scrollView.addView(textView);

                    new AlertDialog.Builder(current)
                            .setTitle(R.string.v2ray_show_log_title)
                            .setView(withPadding(current, scrollView))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }, "V2RayReadLog").start();
        }

        private void showCoreVersion() {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            final Context appContext = activity.getApplicationContext();
            new Thread(() -> {
                String message;
                try {
                    message = V2RayManager.getCoreVersion(appContext);
                } catch (V2RayManager.V2RayException e) {
                    message = messageOf(e);
                }
                final String text = message;
                runOnUiThreadIfAlive(() -> showToast(text));
            }, "V2RayCoreVersion").start();
        }

        private void startImport(final String text) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            final Context appContext = activity.getApplicationContext();
            new Thread(() -> {
                try {
                    importConfig(appContext, text);
                    reportImportSuccess();
                } catch (Exception e) {
                    reportImportFailure(messageOf(e));
                }
            }, "V2RayImport").start();
        }

        private void startFileImport(final Uri uri) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            final Context appContext = activity.getApplicationContext();
            new Thread(() -> {
                try {
                    String text = readTextFromUri(appContext, uri);
                    importConfig(appContext, text);
                    reportImportSuccess();
                } catch (Exception e) {
                    reportImportFailure(messageOf(e));
                }
            }, "V2RayFileImport").start();
        }

        private void importConfig(Context appContext, String rawText) throws V2RayManager.V2RayException {
            String text = rawText == null ? "" : rawText.trim();
            String body = text.startsWith("{")
                    ? text
                    : V2RayManager.convertShareLinks(appContext, text);
            body = body.trim();
            V2RayConfigStore.ConfigEntry entry =
                    V2RayConfigStore.addConfig(appContext, extractConfigName(body), body);
            if (entry != null) {
                V2RayConfigStore.setActiveConfigId(appContext, entry.id);
            }
        }

        private void reportImportSuccess() {
            runOnUiThreadIfAlive(() -> {
                Toast.makeText(getActivity(), R.string.v2ray_import_ok, Toast.LENGTH_SHORT).show();
                refreshPreferenceState();
            });
        }

        private void reportImportFailure(final String message) {
            runOnUiThreadIfAlive(() -> Toast.makeText(getActivity(),
                    getString(R.string.v2ray_import_failed, message), Toast.LENGTH_LONG).show());
        }

        private static String extractConfigName(String body) {
            Matcher matcher = REMARKS_PATTERN.matcher(body);
            if (matcher.find()) {
                String name = matcher.group(1).trim();
                if (!name.isEmpty()) {
                    return name;
                }
            }
            return DEFAULT_CONFIG_NAME;
        }

        private static String readTextFromUri(Context context, Uri uri) throws IOException {
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IOException("the selected file could not be opened");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8 * 1024];
                int read;
                long total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_CONFIG_FILE_BYTES) {
                        throw new IOException("the selected file is larger than 4 MiB");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toString("UTF-8");
            }
        }

        @Nullable
        private static CharSequence readClipboardText(Activity activity) {
            try {
                ClipboardManager clipboardManager =
                        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
                    return null;
                }
                ClipData clipData = clipboardManager.getPrimaryClip();
                if (clipData == null || clipData.getItemCount() == 0) {
                    return null;
                }
                return clipData.getItemAt(0).coerceToText(activity);
            } catch (RuntimeException e) {
                // Clipboard reads can be refused when the app has no window focus.
                return null;
            }
        }

        private static String messageOf(Exception e) {
            String message = e.getMessage();
            return message == null || message.isEmpty() ? e.toString() : message;
        }

        private static View withPadding(Activity activity, View content) {
            FrameLayout container = new FrameLayout(activity);
            int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
            container.setPadding(padding, padding, padding, padding);
            container.addView(content);
            return container;
        }

        private void showToast(CharSequence text) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        }

        private void runOnUiThreadIfAlive(final Runnable action) {
            Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            activity.runOnUiThread(() -> {
                Activity current = getActivity();
                if (current == null || current.isFinishing()) {
                    return;
                }
                action.run();
            });
        }
    }
}
