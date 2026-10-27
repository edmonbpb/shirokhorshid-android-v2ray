package com.psiphon3.psiphonlibrary;

import com.psiphon3.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.napster.NapsterImporter;

/** Standalone Napster configuration manager, upstream switch and per-link ping display. */
public class NapsterActivity extends LocalizedActivities.AppCompatActivity {

    private static final int REQUEST_CODE_PICK_NAPSTER = 3001;
    private static final long MAX_NAPSTER_FILE_BYTES = 8L * 1024L * 1024L;
    private static final long PING_INTERVAL_MS = 5000L;
    private static final int PING_TIMEOUT_MS = 3000;
    private static final int PING_THREADS = 6;
    private static final long HEALTH_INTERVAL_MS = 900000L;

    private ListView listView;
    private Switch enableSwitch;
    private Switch autoCheckSwitch;
    private TextView statusView;
    private TextView emptyView;
    private Button checkAllButton;
    private ConfigAdapter adapter;
    private final List<NapsterConfigStore.ConfigEntry> entries = new ArrayList<>();
    private final Map<String, Integer> pingResults = new HashMap<>();
    private final Map<String, NapsterPinger.Endpoint> endpoints = new ConcurrentHashMap<>();
    private final Map<String, NapsterHealthStore.Health> healthById = new HashMap<>();
    private final Handler pingHandler = new Handler(Looper.getMainLooper());
    private final Handler healthHandler = new Handler(Looper.getMainLooper());
    private ExecutorService pingExecutor;
    private boolean pingCycleRunning;
    private boolean updatingSwitch;
    private boolean updatingAutoSwitch;
    private boolean resumed;
    private volatile boolean destroyed;

    private final Runnable pingCycle = this::pingAll;
    private final Runnable healthCycle = this::runScheduledHealthCheck;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.napster);
        setTitle(R.string.napster_title);

        listView = findViewById(R.id.napster_list);
        enableSwitch = findViewById(R.id.napster_switch);
        statusView = findViewById(R.id.napster_status);
        emptyView = findViewById(R.id.napster_empty);
        Button addButton = findViewById(R.id.napster_add);
        Button testButton = findViewById(R.id.napster_test);
        Button deleteButton = findViewById(R.id.napster_delete);
        Button pingAllButton = findViewById(R.id.napster_ping_all);
        checkAllButton = findViewById(R.id.napster_check_all);
        autoCheckSwitch = findViewById(R.id.napster_auto_check);

        adapter = new ConfigAdapter();
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        pingExecutor = Executors.newFixedThreadPool(PING_THREADS);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= entries.size()) {
                return;
            }
            NapsterConfigStore.ConfigEntry entry = entries.get(position);
            NapsterConfigStore.setActiveConfigId(this, entry.id);
            listView.setItemChecked(position, true);
            adapter.setActiveId(entry.id);
            updateStatus();
        });

        addButton.setOnClickListener(v -> pickNapsterFile());
        testButton.setOnClickListener(v -> testActiveConfig());
        deleteButton.setOnClickListener(v -> confirmDelete());
        pingAllButton.setOnClickListener(v -> pingAllNow());
        checkAllButton.setOnClickListener(v -> checkAllNow());

        autoCheckSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingAutoSwitch) {
                return;
            }
            NapsterHealthStore.setAutoEnabled(this, isChecked);
            if (isChecked) {
                scheduleHealthCheck();
            } else {
                healthHandler.removeCallbacks(healthCycle);
            }
        });

        enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingSwitch) {
                return;
            }
            if (isChecked) {
                NapsterConfigStore.ConfigEntry active = NapsterConfigStore.getActiveConfig(this);
                if (active == null) {
                    updatingSwitch = true;
                    enableSwitch.setChecked(false);
                    updatingSwitch = false;
                    Toast.makeText(this, R.string.napster_no_selection, Toast.LENGTH_SHORT).show();
                    return;
                }
                NapsterConfigStore.setFeatureEnabled(this, true);
                V2RayConfigStore.setFeatureEnabled(this, false);
                Toast.makeText(this, R.string.napster_apply_next_connect, Toast.LENGTH_LONG).show();
            } else {
                NapsterConfigStore.setFeatureEnabled(this, false);
            }
            updateStatus();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        pingHandler.removeCallbacks(pingCycle);
        pingHandler.post(pingCycle);
        scheduleHealthCheck();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        pingHandler.removeCallbacks(pingCycle);
        healthHandler.removeCallbacks(healthCycle);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
        pingHandler.removeCallbacksAndMessages(null);
        healthHandler.removeCallbacksAndMessages(null);
        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
        }
    }

    private void refresh() {
        entries.clear();
        entries.addAll(NapsterConfigStore.getConfigs(this));

        healthById.clear();
        for (NapsterConfigStore.ConfigEntry entry : entries) {
            NapsterHealthStore.Health health = NapsterHealthStore.load(this, entry.id);
            if (health != null) {
                healthById.put(entry.id, health);
            }
        }

        NapsterConfigStore.ConfigEntry active = NapsterConfigStore.getActiveConfig(this);
        adapter.setData(entries, active == null ? null : active.id);

        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        listView.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);

        if (active != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (active.id.equals(entries.get(i).id)) {
                    listView.setItemChecked(i, true);
                    break;
                }
            }
        }

        updatingSwitch = true;
        enableSwitch.setChecked(NapsterConfigStore.isFeatureEnabled(this));
        updatingSwitch = false;

        updatingAutoSwitch = true;
        autoCheckSwitch.setChecked(NapsterHealthStore.isAutoEnabled(this));
        updatingAutoSwitch = false;

        updateStatus();
    }

    private void updateStatus() {
        boolean enabled = NapsterConfigStore.isFeatureEnabled(this);
        NapsterConfigStore.ConfigEntry active = NapsterConfigStore.getActiveConfig(this);
        if (enabled && active != null) {
            statusView.setText(getString(R.string.napster_home_status_on, active.name));
        } else if (enabled) {
            statusView.setText(R.string.napster_home_no_config);
        } else {
            statusView.setText(R.string.napster_select_hint);
        }
    }

    private void pingAllNow() {
        pingHandler.removeCallbacks(pingCycle);
        pingAll();
    }

    private void pingAll() {
        if (destroyed) {
            return;
        }
        if (entries.isEmpty()) {
            scheduleNextCycle();
            return;
        }
        if (pingCycleRunning || pingExecutor == null || pingExecutor.isShutdown()) {
            scheduleNextCycle();
            return;
        }
        pingCycleRunning = true;

        final List<NapsterConfigStore.ConfigEntry> snapshot = new ArrayList<>(entries);
        final AtomicInteger pending = new AtomicInteger(snapshot.size());
        final Context appContext = getApplicationContext();

        for (final NapsterConfigStore.ConfigEntry entry : snapshot) {
            pingExecutor.execute(() -> {
                int milliseconds = -1;
                try {
                    NapsterPinger.Endpoint endpoint = endpoints.get(entry.id);
                    if (endpoint == null) {
                        String body = NapsterConfigStore.readConfigBody(appContext, entry.id);
                        endpoint = NapsterPinger.endpointOf(body);
                        if (endpoint != null) {
                            endpoints.put(entry.id, endpoint);
                        }
                    }
                    if (endpoint != null) {
                        milliseconds = NapsterPinger.ping(endpoint, PING_TIMEOUT_MS);
                    }
                } catch (Exception ignored) {
                }
                final int result = milliseconds;
                runOnUiThread(() -> {
                    pingResults.put(entry.id, result);
                    adapter.notifyDataSetChanged();
                    if (pending.decrementAndGet() == 0) {
                        pingCycleRunning = false;
                        scheduleNextCycle();
                    }
                });
            });
        }
    }

    private void scheduleNextCycle() {
        if (destroyed) {
            return;
        }
        pingHandler.removeCallbacks(pingCycle);
        pingHandler.postDelayed(pingCycle, PING_INTERVAL_MS);
    }

    private void checkAllNow() {
        if (NapsterHealthChecker.isRunning()) {
            NapsterHealthChecker.cancel();
            return;
        }
        startHealthCheck();
    }

    private void startHealthCheck() {
        List<String> ids = new ArrayList<>();
        for (NapsterConfigStore.ConfigEntry entry : entries) {
            if (entry.id != null && !entry.id.isEmpty()) {
                ids.add(entry.id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        NapsterHealthChecker.checkAll(this, ids, healthListener,
                new Handler(Looper.getMainLooper()));
    }

    private void runScheduledHealthCheck() {
        if (destroyed) {
            return;
        }
        if (!NapsterHealthChecker.isRunning() && !entries.isEmpty()
                && NapsterHealthStore.isAutoEnabled(this)) {
            startHealthCheck();
        }
        scheduleHealthCheck();
    }

    private void scheduleHealthCheck() {
        healthHandler.removeCallbacks(healthCycle);
        if (destroyed || !resumed) {
            return;
        }
        if (!NapsterHealthStore.isAutoEnabled(this)) {
            return;
        }
        healthHandler.postDelayed(healthCycle, HEALTH_INTERVAL_MS);
    }

    private String nameOfEntry(String id) {
        if (id == null) {
            return null;
        }
        for (int i = 0; i < entries.size(); i++) {
            NapsterConfigStore.ConfigEntry entry = entries.get(i);
            if (id.equals(entry.id)) {
                return entry.name;
            }
        }
        return null;
    }

    private String healthDetail(NapsterHealthStore.Health health) {
        int general = health.generalMs > 0 ? health.generalMs : 0;
        int telegram = health.telegramMs > 0 ? health.telegramMs : 0;
        long age = System.currentTimeMillis() - health.at;
        if (age < 0L) {
            age = 0L;
        }
        String relative;
        if (age < 60000L) {
            relative = getString(R.string.napster_health_now);
        } else if (age < 3600000L) {
            relative = getString(R.string.napster_health_minutes_ago, (int) (age / 60000L));
        } else {
            relative = getString(R.string.napster_health_hours_ago, (int) (age / 3600000L));
        }
        // The separated dot is the same middle dot used inside the detail string.
        return getString(R.string.napster_health_detail, general, telegram)
                + " \u00B7 " + relative;
    }

    private static int healthLabelRes(String status) {
        if ("HEALTHY".equals(status)) {
            return R.string.napster_health_healthy;
        }
        if ("SEMI_HEALTHY".equals(status)) {
            return R.string.napster_health_semi;
        }
        if ("SUSPICIOUS".equals(status)) {
            return R.string.napster_health_suspicious;
        }
        if ("DEAD".equals(status)) {
            return R.string.napster_health_dead;
        }
        if ("UNKNOWN".equals(status)) {
            return R.string.napster_health_unknown;
        }
        return 0;
    }

    private static int healthColor(String status) {
        if ("HEALTHY".equals(status)) {
            return 0xFF2E7D32;
        }
        if ("SEMI_HEALTHY".equals(status) || "SUSPICIOUS".equals(status)) {
            return 0xFFF9A825;
        }
        if ("DEAD".equals(status)) {
            return 0xFFC62828;
        }
        return 0xFF757575;
    }

    private void pickNapsterFile() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_NAPSTER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.napster_import_failed, messageOf(e)),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_CODE_PICK_NAPSTER) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                startImport(data.getData());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void startImport(final Uri uri) {
        final Context appContext = getApplicationContext();
        new Thread(() -> {
            try {
                byte[] bytes = readBytesFromUri(appContext, uri, MAX_NAPSTER_FILE_BYTES);
                NapsterImporter.Result result = NapsterImporter.importBytes(appContext, bytes);
                boolean hadActive = NapsterConfigStore.getActiveConfig(appContext) != null;
                int added = 0;
                String firstId = null;
                for (NapsterImporter.Item item : result.items) {
                    NapsterConfigStore.ConfigEntry entry =
                            NapsterConfigStore.addConfig(appContext, item.name, item.body);
                    if (firstId == null) {
                        firstId = entry.id;
                    }
                    added++;
                }
                if (!hadActive && firstId != null) {
                    NapsterConfigStore.setActiveConfigId(appContext, firstId);
                }
                final int finalAdded = added;
                final int finalFailed = result.errors.size();
                final List<String> finalErrors = new ArrayList<>(result.errors);
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    showResultDialog(finalAdded, finalFailed, finalErrors);
                    refresh();
                    pingAllNow();
                });
            } catch (Exception e) {
                final String message = messageOf(e);
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    int text = "file too large".equals(message)
                            ? R.string.napster_file_too_large
                            : R.string.napster_import_failed;
                    Toast.makeText(NapsterActivity.this,
                            text == R.string.napster_file_too_large
                                    ? getString(text) : getString(text, message),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "NapsterImport").start();
    }

    private void showResultDialog(int added, int failed, List<String> errors) {
        if (isFinishing()) {
            return;
        }
        String message;
        if (failed == 0) {
            message = getString(R.string.napster_import_ok, added);
        } else if (added > 0) {
            message = getString(R.string.napster_import_partial, added, failed);
        } else {
            message = getString(R.string.napster_import_empty);
        }
        if (!errors.isEmpty()) {
            StringBuilder details = new StringBuilder(message).append("\n\n");
            for (int i = 0; i < errors.size() && i < 3; i++) {
                details.append("- ").append(errors.get(i)).append("\n");
            }
            message = details.toString().trim();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.napster_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void testActiveConfig() {
        final NapsterConfigStore.ConfigEntry active = NapsterConfigStore.getActiveConfig(this);
        final String body = active == null ? null : NapsterConfigStore.readConfigBody(this, active.id);
        if (body == null) {
            Toast.makeText(this, R.string.napster_no_selection, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.napster_testing, Toast.LENGTH_SHORT).show();
        final Context appContext = getApplicationContext();
        new Thread(() -> {
            final String error = V2RayManager.testConfig(appContext, body);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                String message = error == null
                        ? getString(R.string.napster_test_ok)
                        : getString(R.string.napster_test_failed, error);
                new AlertDialog.Builder(NapsterActivity.this)
                        .setTitle(R.string.napster_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "NapsterTest").start();
    }

    private void confirmDelete() {
        int position = listView.getCheckedItemPosition();
        NapsterConfigStore.ConfigEntry entry = null;
        if (position != ListView.INVALID_POSITION && position < entries.size()) {
            entry = entries.get(position);
        }
        if (entry == null) {
            entry = NapsterConfigStore.getActiveConfig(this);
        }
        if (entry == null) {
            Toast.makeText(this, R.string.napster_no_selection, Toast.LENGTH_SHORT).show();
            return;
        }
        final NapsterConfigStore.ConfigEntry target = entry;
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.napster_delete_confirm, target.name))
                .setPositiveButton(R.string.napster_delete_yes, (dialog, which) -> {
                    pingResults.remove(target.id);
                    endpoints.remove(target.id);
                    NapsterConfigStore.deleteConfig(this, target.id);
                    refresh();
                    pingAllNow();
                })
                .setNegativeButton(R.string.napster_cancel, null)
                .show();
    }

    private static byte[] readBytesFromUri(Context context, Uri uri, long maxBytes) throws IOException {
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
                if (total > maxBytes) {
                    throw new IOException("file too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String messageOf(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "unknown error" : message;
    }

    private final NapsterHealthChecker.Listener healthListener =
            new NapsterHealthChecker.Listener() {
                @Override
                public void onProgress(int done, int total, String currentId) {
                    if (destroyed) {
                        return;
                    }
                    String label = getString(R.string.napster_checking_progress, done, total);
                    String name = nameOfEntry(currentId);
                    if (name != null && !name.isEmpty()) {
                        label = label + " - " + name;
                    }
                    checkAllButton.setText(label);
                }

                @Override
                public void onResult(String id, NapsterHealthStore.Health health) {
                    if (destroyed || id == null || health == null) {
                        return;
                    }
                    healthById.put(id, health);
                    adapter.notifyDataSetChanged();
                }

                @Override
                public void onFinished() {
                    if (destroyed) {
                        return;
                    }
                    checkAllButton.setText(R.string.napster_check_all);
                    scheduleHealthCheck();
                }
            };

    private final class ConfigAdapter extends BaseAdapter {
        private final List<NapsterConfigStore.ConfigEntry> items = new ArrayList<>();
        private String activeId;

        void setData(List<NapsterConfigStore.ConfigEntry> data, String activeId) {
            items.clear();
            items.addAll(data);
            this.activeId = activeId;
            notifyDataSetChanged();
        }

        void setActiveId(String id) {
            activeId = id;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public NapsterConfigStore.ConfigEntry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).id.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(NapsterActivity.this)
                        .inflate(R.layout.napster_row, parent, false);
            }
            NapsterConfigStore.ConfigEntry entry = getItem(position);
            ((TextView) row.findViewById(R.id.napster_row_name)).setText(entry.name);

            TextView pingView = row.findViewById(R.id.napster_row_ping);
            Integer milliseconds = pingResults.get(entry.id);
            if (milliseconds == null) {
                pingView.setText(R.string.napster_ping_waiting);
                pingView.setTextColor(0xFF9E9E9E);
            } else if (milliseconds < 0) {
                pingView.setText(R.string.napster_ping_timeout);
                pingView.setTextColor(0xFFF44336);
            } else {
                pingView.setText(getString(R.string.napster_ping_ms, milliseconds));
                pingView.setTextColor(milliseconds < 300 ? 0xFF4CAF50
                        : (milliseconds < 800 ? 0xFFFFC107 : 0xFFF44336));
            }

            TextView healthView = row.findViewById(R.id.napster_row_health);
            TextView detailView = row.findViewById(R.id.napster_row_health_detail);
            View healthLine = row.findViewById(R.id.napster_row_health_line);
            NapsterHealthStore.Health health = healthById.get(entry.id);
            if (health == null) {
                healthView.setText("");
                healthView.setTextColor(0xFF9E9E9E);
                detailView.setText("");
                healthLine.setVisibility(View.GONE);
            } else {
                int label = healthLabelRes(health.status);
                if (label == 0) {
                    label = R.string.napster_health_unknown;
                }
                healthView.setText(label);
                healthView.setTextColor(healthColor(health.status));
                detailView.setText(healthDetail(health));
                healthLine.setVisibility(View.VISIBLE);
            }

            ((RadioButton) row.findViewById(R.id.napster_row_check))
                    .setChecked(entry.id.equals(activeId));
            return row;
        }
    }
}
