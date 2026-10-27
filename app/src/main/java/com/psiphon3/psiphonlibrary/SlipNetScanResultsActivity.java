// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.

package com.psiphon3.psiphonlibrary;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.psiphon3.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import app.slipnet.scanner.ResolverScanner;
import app.slipnet.scanner.ScanManager;
import app.slipnet.scanner.ScanResult;
import app.slipnet.scanner.ScanState;

public class SlipNetScanResultsActivity extends LocalizedActivities.AppCompatActivity {
    private static final int MAX_SELECTED = 8;
    private static final long REFRESH_INTERVAL_MS = 1000L;
    private static final int DEFAULT_PORT = 53;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_WORKING = 1;
    private static final int FILTER_PRISM = 2;
    private static final int FILTER_E2E = 3;
    private static final int FILTER_SELECTED = 4;

    private static final int SORT_SPEED = 0;
    private static final int SORT_IP = 1;
    private static final int SORT_SCORE = 2;
    private static final int SORT_E2E = 3;
    private static final int SORT_PRISM = 4;

    private static final String[] FILTER_LABELS =
            {"All", "Working", "Prism passed", "E2E passed", "Selected"};
    private static final String[] SORT_LABELS =
            {"Speed", "IP", "Score", "E2E speed", "Prism score"};
    private static final String[] SCORE_LABELS = {"0", "1", "2", "3", "4", "5", "6"};

    private final LinkedHashSet<String> selected = new LinkedHashSet<String>();
    private final List<ScanResult> visibleResults = new ArrayList<ScanResult>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ScanResultAdapter adapter;
    private ListView listView;
    private TextView emptyView;
    private TextView workingCount;
    private TextView e2eCount;
    private TextView prismCount;
    private TextView selectedCount;
    private TextView proxyBanner;
    private TextView vpnBanner;
    private Spinner filterSpinner;
    private Spinner scoreSpinner;
    private Spinner sortSpinner;
    private EditText searchField;
    private Button copyButton;
    private Button applyButton;
    private Button clearButton;
    private boolean resumed;
    private boolean applying;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            if (render()) {
                handler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.slipnet_scan_results);
        bindViews();
        addPoolActions();

        adapter = new ScanResultAdapter(this, selected);
        listView.setAdapter(adapter);

        filterSpinner.setAdapter(spinnerAdapter(FILTER_LABELS));
        sortSpinner.setAdapter(spinnerAdapter(SORT_LABELS));
        scoreSpinner.setAdapter(spinnerAdapter(SCORE_LABELS));

        final AdapterView.OnItemSelectedListener reload = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (adapter != null) {
                    applyFilters(ScanManager.get().snapshot().results);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        filterSpinner.setOnItemSelectedListener(reload);
        sortSpinner.setOnItemSelectedListener(reload);
        scoreSpinner.setOnItemSelectedListener(reload);

        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (adapter != null) {
                    applyFilters(ScanManager.get().snapshot().results);
                }
            }
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < adapter.getCount()) {
                toggleSelection(adapter.getItem(position));
            }
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < adapter.getCount()) {
                copyHost(adapter.getItem(position));
            }
            return true;
        });

        copyButton.setOnClickListener(v -> copyVisibleHosts());
        applyButton.setOnClickListener(v -> applySelectedHosts());
        clearButton.setOnClickListener(v -> clearSelection());
        findViewById(R.id.slipnet_scan_close).setOnClickListener(v -> finish());

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void bindViews() {
        listView = findViewById(R.id.slipnet_scan_list);
        emptyView = findViewById(R.id.slipnet_scan_empty);
        workingCount = findViewById(R.id.slipnet_scan_count_working);
        e2eCount = findViewById(R.id.slipnet_scan_count_e2e);
        prismCount = findViewById(R.id.slipnet_scan_count_prism);
        selectedCount = findViewById(R.id.slipnet_scan_count_selected);
        proxyBanner = findViewById(R.id.slipnet_scan_proxy_banner);
        vpnBanner = findViewById(R.id.slipnet_scan_vpn_banner);
        filterSpinner = findViewById(R.id.slipnet_scan_filter);
        scoreSpinner = findViewById(R.id.slipnet_scan_min_score);
        sortSpinner = findViewById(R.id.slipnet_scan_sort);
        searchField = findViewById(R.id.slipnet_scan_search);
        copyButton = findViewById(R.id.slipnet_scan_copy);
        applyButton = findViewById(R.id.slipnet_scan_apply);
        clearButton = findViewById(R.id.slipnet_scan_clear);
    }

    /** Adds the pool and share buttons; the layout has no ids for them. */
    private void addPoolActions() {
        if (!(copyButton.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup row = (ViewGroup) copyButton.getParent();
        if (!(row.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup root = (ViewGroup) row.getParent();
        int gap = Math.round(4 * getResources().getDisplayMetrics().density);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button pool = new Button(this);
        pool.setText("Save pool");
        pool.setLayoutParams(barButtonParams(gap));
        pool.setOnClickListener(v -> saveSelectedPool());

        Button share = new Button(this);
        share.setText("Share");
        share.setLayoutParams(barButtonParams(gap));
        share.setOnClickListener(v -> shareVisibleHosts());

        bar.addView(pool);
        bar.addView(share);
        root.addView(bar, root.indexOfChild(row) + 1);
    }

    private static LinearLayout.LayoutParams barButtonParams(int gap) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(gap, gap, gap, gap);
        return params;
    }

    private ArrayAdapter<String> spinnerAdapter(String[] labels) {
        ArrayAdapter<String> items = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels);
        items.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return items;
    }

    /** Redraws the screen from a fresh snapshot; returns whether a scan is still running. */
    private boolean render() {
        if (isFinishing()) {
            return false;
        }
        ScanState state = ScanManager.get().snapshot();
        pruneSelection(state.results);
        updateCounts(state.results);
        proxyBanner.setVisibility(state.transparentProxy ? View.VISIBLE : View.GONE);
        vpnBanner.setVisibility(vpnActive() ? View.VISIBLE : View.GONE);
        applyFilters(state.results);
        return state.running;
    }

    private void updateCounts(List<ScanResult> results) {
        int working = 0;
        int e2e = 0;
        int prism = 0;
        for (int i = 0; i < results.size(); i++) {
            ScanResult result = results.get(i);
            if (result.status == ScanResult.Status.WORKING) {
                working++;
            }
            if (result.prismPassed != null && result.prismPassed > 0) {
                prism++;
            }
            if (result.e2e != null && result.e2e.success) {
                e2e++;
            }
        }
        workingCount.setText(getString(R.string.slipnet_scan_count_working, working));
        e2eCount.setText(getString(R.string.slipnet_scan_count_e2e, e2e));
        prismCount.setText(getString(R.string.slipnet_scan_count_prism, prism));
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        selectedCount.setText(getString(R.string.slipnet_scan_count_selected, selected.size()));
    }

    private void applyFilters(List<ScanResult> results) {
        int filter = Math.max(FILTER_ALL, filterSpinner.getSelectedItemPosition());
        int minScore = Math.max(0, scoreSpinner.getSelectedItemPosition());
        String query = searchField.getText().toString().trim().toLowerCase(Locale.US);

        visibleResults.clear();
        for (int i = 0; i < results.size(); i++) {
            ScanResult result = results.get(i);
            if (!matchesFilter(result, filter) || scoreOf(result) < minScore) {
                continue;
            }
            if (query.length() > 0 && !result.host.toLowerCase(Locale.US).contains(query)) {
                continue;
            }
            visibleResults.add(result);
        }
        Collections.sort(visibleResults, comparatorFor(sortSpinner.getSelectedItemPosition()));
        adapter.setItems(visibleResults);

        boolean empty = visibleResults.isEmpty();
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        copyButton.setEnabled(!empty);
        updateSelectionButtons();
    }

    private boolean matchesFilter(ScanResult result, int filter) {
        switch (filter) {
            case FILTER_WORKING:
                return result.status == ScanResult.Status.WORKING;
            case FILTER_PRISM:
                return result.prismPassed != null && result.prismPassed > 0;
            case FILTER_E2E:
                return result.e2e != null && result.e2e.success;
            case FILTER_SELECTED:
                return selected.contains(result.key());
            default:
                return true;
        }
    }

    private void pruneSelection(List<ScanResult> results) {
        if (selected.isEmpty()) {
            return;
        }
        LinkedHashSet<String> present = new LinkedHashSet<String>();
        for (int i = 0; i < results.size(); i++) {
            present.add(results.get(i).key());
        }
        selected.retainAll(present);
    }

    private void updateSelectionButtons() {
        boolean hasSelection = !selected.isEmpty();
        clearButton.setEnabled(hasSelection);
        applyButton.setEnabled(hasSelection && !applying);
    }

    private void toggleSelection(ScanResult result) {
        if (result == null) {
            return;
        }
        String key = result.key();
        if (selected.contains(key)) {
            selected.remove(key);
        } else if (selected.size() >= MAX_SELECTED) {
            Toast.makeText(this, getString(R.string.slipnet_scan_selected, MAX_SELECTED, MAX_SELECTED),
                    Toast.LENGTH_SHORT).show();
            return;
        } else {
            selected.add(key);
        }
        updateSelectedCount();
        updateSelectionButtons();
        if (filterSpinner.getSelectedItemPosition() == FILTER_SELECTED) {
            applyFilters(ScanManager.get().snapshot().results);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void clearSelection() {
        selected.clear();
        updateSelectedCount();
        updateSelectionButtons();
        if (filterSpinner.getSelectedItemPosition() == FILTER_SELECTED) {
            applyFilters(ScanManager.get().snapshot().results);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void copyVisibleHosts() {
        if (visibleResults.isEmpty()) {
            Toast.makeText(this, R.string.slipnet_scan_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        copyToClipboard(joinedVisibleHosts());
    }

    private void shareVisibleHosts() {
        if (visibleResults.isEmpty()) {
            Toast.makeText(this, R.string.slipnet_scan_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, joinedVisibleHosts());
        startActivity(Intent.createChooser(send, "Share"));
    }

    private String joinedVisibleHosts() {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < visibleResults.size(); i++) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(visibleResults.get(i).host);
        }
        return joined.toString();
    }

    private void saveSelectedPool() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.slipnet_scan_apply_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        ScanManager.get().savePool(selectedHosts());
        Toast.makeText(this, R.string.slipnet_scan_saved, Toast.LENGTH_SHORT).show();
    }

    private List<String> selectedHosts() {
        List<ScanResult> results = ScanManager.get().snapshot().results;
        List<String> hosts = new ArrayList<String>();
        for (int i = 0; i < results.size(); i++) {
            ScanResult result = results.get(i);
            if (result != null && result.host != null && selected.contains(result.key())) {
                hosts.add(result.host);
            }
        }
        return hosts;
    }

    private void copyHost(ScanResult result) {
        if (result == null) {
            return;
        }
        copyToClipboard(result.host);
        Toast.makeText(this, result.host, Toast.LENGTH_SHORT).show();
    }

    private void copyToClipboard(String text) {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.slipnet_scan_copy), text));
    }

    private void applySelectedHosts() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.slipnet_scan_apply_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        List<ScanResult> results = ScanManager.get().snapshot().results;
        final List<String> hosts = new ArrayList<String>();
        int port = DEFAULT_PORT;
        boolean havePort = false;
        for (int i = 0; i < results.size(); i++) {
            ScanResult result = results.get(i);
            if (!selected.contains(result.key())) {
                continue;
            }
            hosts.add(result.host);
            if (!havePort) {
                port = result.port;
                havePort = true;
            }
        }
        if (hosts.isEmpty()) {
            Toast.makeText(this, R.string.slipnet_scan_apply_fail, Toast.LENGTH_SHORT).show();
            return;
        }

        final int applyPort = port;
        applying = true;
        updateSelectionButtons();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean saved = false;
                try {
                    saved = ResolverScanner.applyResolvers(getApplicationContext(), hosts, applyPort);
                } catch (RuntimeException | LinkageError ignored) {
                }
                final boolean applied = saved;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        applying = false;
                        updateSelectionButtons();
                        Toast.makeText(SlipNetScanResultsActivity.this,
                                applied ? R.string.slipnet_scan_saved : R.string.slipnet_scan_apply_fail,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "SlipNetScanApply").start();
    }

    private boolean vpnActive() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        try {
            return VpnProbe.active(this);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Kept in its own class so API 21 VPN types are never resolved on older devices. */
    private static final class VpnProbe {
        static boolean active(Context context) {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network[] networks = manager.getAllNetworks();
                for (int i = 0; i < networks.length; i++) {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(networks[i]);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        return true;
                    }
                }
                return false;
            }
            NetworkInfo info = manager.getNetworkInfo(ConnectivityManager.TYPE_VPN);
            return info != null && info.isConnected();
        }
    }

    private static Comparator<ScanResult> comparatorFor(int sort) {
        switch (sort) {
            case SORT_IP:
                return (a, b) -> compareHost(a.host, b.host);
            case SORT_SCORE:
                return (a, b) -> {
                    int order = cmpInt(scoreOf(b), scoreOf(a));
                    return order != 0 ? order : compareSpeed(a, b);
                };
            case SORT_E2E:
                return (a, b) -> {
                    int order = cmpInt(e2eRank(a), e2eRank(b));
                    if (order != 0) {
                        return order;
                    }
                    if (a.e2e != null && b.e2e != null) {
                        order = cmpLong(a.e2e.totalMs, b.e2e.totalMs);
                        if (order != 0) {
                            return order;
                        }
                    }
                    return compareHost(a.host, b.host);
                };
            case SORT_PRISM:
                return (a, b) -> {
                    int order = cmpInt(prismScore(b), prismScore(a));
                    return order != 0 ? order : compareHost(a.host, b.host);
                };
            case SORT_SPEED:
            default:
                return (a, b) -> compareSpeed(a, b);
        }
    }

    private static int compareSpeed(ScanResult a, ScanResult b) {
        int order = cmpLong(a.responseTimeMs < 0 ? Long.MAX_VALUE : a.responseTimeMs,
                b.responseTimeMs < 0 ? Long.MAX_VALUE : b.responseTimeMs);
        return order != 0 ? order : compareHost(a.host, b.host);
    }

    private static int compareHost(String a, String b) {
        long first = ipv4(a);
        long second = ipv4(b);
        if (first >= 0 && second >= 0) {
            return cmpLong(first, second);
        }
        return a.compareTo(b);
    }

    private static long ipv4(String host) {
        if (host == null) {
            return -1;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long value = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.length() == 0 || part.length() > 3) {
                return -1;
            }
            for (int c = 0; c < part.length(); c++) {
                if (!Character.isDigit(part.charAt(c))) {
                    return -1;
                }
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return -1;
            }
            value = (value << 8) | octet;
        }
        return value;
    }

    private static int scoreOf(ScanResult result) {
        return result.tunnel == null ? 0 : result.tunnel.score();
    }

    private static int prismScore(ScanResult result) {
        return result.prismPassed == null ? -1 : result.prismPassed;
    }

    private static int e2eRank(ScanResult result) {
        if (result.e2e == null) {
            return 2;
        }
        return result.e2e.success ? 0 : 1;
    }

    private static int cmpInt(int a, int b) {
        return a < b ? -1 : (a == b ? 0 : 1);
    }

    private static int cmpLong(long a, long b) {
        return a < b ? -1 : (a == b ? 0 : 1);
    }
}
