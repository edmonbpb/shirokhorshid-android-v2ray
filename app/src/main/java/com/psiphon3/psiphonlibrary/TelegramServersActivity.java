package com.psiphon3.psiphonlibrary;

import com.psiphon3.R;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.slipnet.scanner.DnsProbe;

/**
 * Lists the servers of one Telegram connection method, pings them while visible and stores the tap
 * as the active server for the next connection.
 */
public class TelegramServersActivity extends LocalizedActivities.AppCompatActivity {

    public static final String EXTRA_METHOD = "method_ordinal";

    private static final long PING_INTERVAL_MS = 5000L;
    private static final int PING_TIMEOUT_MS = 3000;
    private static final int DNS_TIMEOUT_MS = 2000;
    private static final int DNS_UDP_BUFFER = 512;
    private static final int PING_THREADS = 6;

    private ServerAdapter adapter;
    private TelegramConnector.Method method;
    private final List<TelegramServer> servers = new ArrayList<>();
    private final Map<String, Integer> pingById = new HashMap<>();
    private final Handler pingHandler = new Handler(Looper.getMainLooper());
    private ExecutorService pingExecutor;
    private String activeId;
    private boolean pingCycleRunning;
    private boolean sorted;
    private boolean resumed;
    private int pingGeneration;
    private volatile boolean destroyed;

    private final Runnable pingCycle = this::pingAll;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        int ordinal = intent == null ? -1 : intent.getIntExtra(EXTRA_METHOD, -1);
        TelegramConnector.Method[] methods = TelegramConnector.Method.values();
        if (ordinal < 0 || ordinal >= methods.length) {
            finish();
            return;
        }
        method = methods[ordinal];

        setContentView(R.layout.telegram_servers);
        setTitle(R.string.telegram_servers_title);

        ListView listView = findViewById(R.id.telegram_servers_list);
        View noneView = findViewById(R.id.telegram_servers_none);

        activeId = TelegramServerCatalog.activeId(this, method);
        servers.addAll(TelegramServerCatalog.load(this, method));

        adapter = new ServerAdapter();
        listView.setAdapter(adapter);

        boolean hasServers = TelegramServerCatalog.hasServers(method);
        noneView.setVisibility(hasServers ? View.GONE : View.VISIBLE);
        listView.setVisibility(hasServers ? View.VISIBLE : View.GONE);

        listView.setOnItemClickListener((parent, view, position, id) -> onServerTapped(position));

        pingExecutor = Executors.newFixedThreadPool(PING_THREADS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        pingHandler.removeCallbacks(pingCycle);
        pingHandler.post(pingCycle);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // Invalidate results still in flight so a paused screen never updates or reschedules.
        pingGeneration++;
        pingCycleRunning = false;
        pingHandler.removeCallbacks(pingCycle);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        pingGeneration++;
        super.onDestroy();
        pingHandler.removeCallbacksAndMessages(null);
        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
        }
    }

    private void onServerTapped(int position) {
        if (destroyed || isFinishing() || position < 0 || position >= servers.size()) {
            return;
        }
        // The WhiteDNS method has a single profile, so the catalog keeps its selection for it.
        TelegramServerCatalog.select(this, method, servers.get(position).id);
        setResult(RESULT_OK);
        finish();
    }

    private void pingAll() {
        if (destroyed || isFinishing() || !resumed) {
            return;
        }
        if (pingCycleRunning || pingExecutor == null || pingExecutor.isShutdown()) {
            scheduleNextCycle();
            return;
        }
        if (servers.isEmpty()) {
            return;
        }
        pingCycleRunning = true;

        final int generation = pingGeneration;
        final List<TelegramServer> snapshot = new ArrayList<>(servers);
        final AtomicInteger pending = new AtomicInteger(snapshot.size());

        for (final TelegramServer server : snapshot) {
            pingExecutor.execute(() -> {
                final int milliseconds = measure(server);
                runOnUiThread(() -> {
                    if (destroyed || isFinishing() || generation != pingGeneration) {
                        return;
                    }
                    pingById.put(server.id, milliseconds);
                    if (pending.decrementAndGet() == 0) {
                        pingCycleRunning = false;
                        if (!sorted) {
                            // Sort once, after the first round, so rows do not move under a finger.
                            TelegramServerCatalog.sortByPing(servers, pingById);
                            sorted = true;
                        }
                        adapter.notifyDataSetChanged();
                        scheduleNextCycle();
                    } else {
                        adapter.notifyDataSetChanged();
                    }
                });
            });
        }
    }

    private void scheduleNextCycle() {
        if (destroyed || isFinishing() || !resumed) {
            return;
        }
        pingHandler.removeCallbacks(pingCycle);
        pingHandler.postDelayed(pingCycle, PING_INTERVAL_MS);
    }

    private int measure(TelegramServer server) {
        if (server == null || server.host.length() == 0 || server.port <= 0) {
            return -1;
        }
        try {
            if (method == TelegramConnector.Method.WHITEDNS) {
                byte[] query = DnsProbe.buildQuery("example.com", DnsProbe.TYPE_A, 0);
                long start = System.nanoTime();
                DnsProbe.DnsResponse response = DnsProbe.query(server.host, server.port, query,
                        DNS_TIMEOUT_MS, DnsProbe.Transport.UDP, DNS_UDP_BUFFER);
                if (response == null || !response.received) {
                    return -1;
                }
                long elapsed = (System.nanoTime() - start) / 1000000L;
                return (int) Math.min(elapsed, Integer.MAX_VALUE);
            }
            return NapsterPinger.ping(new NapsterPinger.Endpoint(server.host, server.port),
                    PING_TIMEOUT_MS);
        } catch (Exception e) {
            return -1;
        }
    }

    private final class ServerAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return servers.size();
        }

        @Override
        public TelegramServer getItem(int position) {
            return servers.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).id.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(TelegramServersActivity.this)
                        .inflate(R.layout.telegram_server_row, parent, false);
            }
            TelegramServer server = getItem(position);
            ((TextView) row.findViewById(R.id.telegram_server_row_name)).setText(server.name);

            TextView hostView = row.findViewById(R.id.telegram_server_row_host);
            if (server.host.length() == 0) {
                hostView.setText("");
                hostView.setVisibility(View.GONE);
            } else {
                hostView.setText(server.host + ":" + server.port);
                hostView.setVisibility(View.VISIBLE);
            }

            TextView pingView = row.findViewById(R.id.telegram_server_row_ping);
            Integer milliseconds = pingById.get(server.id);
            if (milliseconds == null) {
                pingView.setText("");
                pingView.setTextColor(0xFF9E9E9E);
            } else if (milliseconds < 0) {
                pingView.setText(R.string.telegram_servers_no_reply);
                pingView.setTextColor(0xFFF44336);
            } else {
                pingView.setText(getString(R.string.telegram_servers_ping_ms, milliseconds));
                pingView.setTextColor(milliseconds < 300 ? 0xFF4CAF50
                        : (milliseconds < 800 ? 0xFFFFC107 : 0xFFF44336));
            }

            boolean active = server.id.equals(activeId);
            RadioButton activeView = row.findViewById(R.id.telegram_server_row_active);
            activeView.setChecked(active);
            activeView.setContentDescription(active
                    ? getString(R.string.telegram_servers_active) : null);
            return row;
        }
    }
}
