// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.

package com.psiphon3.psiphonlibrary;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.psiphon3.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;

import app.slipnet.scanner.DnsTunnelResult;
import app.slipnet.scanner.ResolverPool;
import app.slipnet.scanner.ResolverScanner;
import app.slipnet.scanner.ScanManager;
import app.slipnet.scanner.ScanResult;
import app.slipnet.scanner.ScanState;
import app.whitedns.model.WhiteDnsProfile;
import app.whitedns.store.WhiteDnsStore;

public class SlipNetScannerActivity extends LocalizedActivities.AppCompatActivity
        implements ScanManager.Observer {

    private static final String PREFS_NAME = "slipnet_prefs";
    private static final String PREF_TELEGRAM_TEXT = "telegram_text";
    private static final String PREF_TELEGRAM_SAVED_AT = "telegram_saved_at";

    private static final int MODE_SIMPLE = 0;
    private static final int MODE_DNS = 1;
    private static final int MODE_E2E = 2;

    private static final int SOURCE_DEFAULT = 0;
    private static final int SOURCE_PASTE = 1;
    private static final int SOURCE_COUNTRY = 2;
    private static final int SOURCE_CUSTOM = 3;
    private static final int SOURCE_FILE = 4;
    private static final int SOURCE_TELEGRAM = 5;

    private static final int REQUEST_OPEN_FILE = 4001;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;

    private static final int TRANSPORT_BOTH = 2;
    private static final int MAX_HOSTS = 100000;
    private static final int LIVE_LIMIT = 50;
    private static final int DEFAULT_SAMPLE = 500;
    private static final String[] COUNTRY_CODES = {"ir", "ir_dns", "cn", "ru"};
    private static final String[] COUNTRY_LABELS = {"IR", "IR-DNS", "CN", "RU"};

    private static final String STATE_PASTE = "slipnet_scan_paste";
    private static final String STATE_SOURCE = "slipnet_scan_source";
    private static final String STATE_COUNTRY = "slipnet_scan_country";
    private static final String STATE_FILE = "slipnet_scan_file";

    private Spinner modeSpinner;
    private Spinner sourceSpinner;
    private Spinner countrySpinner;
    private Spinner transportSpinner;
    private EditText domainField;
    private EditText portField;
    private EditText timeoutField;
    private EditText workersField;
    private EditText e2eTimeoutField;
    private EditText e2eWorkersField;
    private EditText minScoreField;
    private EditText sampleField;
    private EditText customField;
    private CheckBox shuffleCheck;
    private CheckBox expandCheck;
    private View countryRow;
    private View customRow;
    private View telegramRow;
    private TextView telegramStatus;
    private TextView telegramStatusView;
    private TextView statusText;
    private Button telegramOpenButton;
    private TextView statusView;
    private TextView previewView;
    private TextView progressText;
    private TextView phaseText;
    private TextView foundText;
    private TextView passedText;
    private TextView proxyView;
    private TextView liveText;
    private ProgressBar progressBar;
    private Button startButton;
    private Button stopButton;
    private Button continueButton;
    private Button resultsButton;
    private Button lastScanButton;
    private Button loadPoolButton;

    private final List<String> hosts = new ArrayList<>();
    private WhiteDnsProfile profile;
    private boolean hasConfig;
    private boolean loading;
    private boolean startingPending;
    private boolean sourceDirty = true;
    private boolean stoppedWithPartial;
    private String pasteText = "";
    private String fileText = "";
    private int lastSourceIndex;
    private int lastCountryIndex;
    private int sourceBeforeFile = SOURCE_DEFAULT;
    private int loadToken;
    private ScanState lastState;
    private RunRequest lastRun;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.slipnet_scanner);
        setTitle(R.string.label_slipnet_scanner);
        bindViews();
        statusText = statusView;
        telegramStatusView = findViewById(R.id.slipnet_scanner_telegram_status);
        Button btnOpenTelegram = findViewById(R.id.slipnet_scanner_telegram_open);
        if (btnOpenTelegram != null) {
            btnOpenTelegram.setOnClickListener(v -> {
                Intent intent = new Intent(this, TelegramWebActivity.class);
                intent.putExtra(TelegramWebActivity.EXTRA_SCANNER_MODE, true);
                startActivity(intent);
            });
        }
        if (savedInstanceState != null) {
            pasteText = savedInstanceState.getString(STATE_PASTE, "");
            fileText = savedInstanceState.getString(STATE_FILE, "");
            lastSourceIndex = savedInstanceState.getInt(STATE_SOURCE, 0);
            lastCountryIndex = savedInstanceState.getInt(STATE_COUNTRY, 0);
        }
        setupSpinners();
        setupListeners();
        ScanManager.get().init(this);
        ResolverScanner.init(this);
        previewView.setText(getString(R.string.slipnet_scan_preview, 0));
        updateSourceRows();
        refreshButtons();
        loadConfig();
        loadSource();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PASTE, pasteText.length() <= 100000 ? pasteText : "");
        outState.putString(STATE_FILE, fileText.length() <= 100000 ? fileText : "");
        outState.putInt(STATE_SOURCE, sourceSpinner == null
                ? SOURCE_DEFAULT : sourceSpinner.getSelectedItemPosition());
        outState.putInt(STATE_COUNTRY, countrySpinner == null
                ? 0 : countrySpinner.getSelectedItemPosition());
    }

    @Override
    protected void onStart() {
        super.onStart();
        ScanManager.get().addObserver(this);
        render(ScanManager.get().snapshot());
    }

    @Override
    protected void onStop() {
        ScanManager.get().removeObserver(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sourceSpinner != null
                && sourceSpinner.getSelectedItemPosition() == SOURCE_TELEGRAM) {
            loadSource();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_FILE) {
            return;
        }
        if (resultCode != RESULT_OK) {
            revertFileSource();
            Toast.makeText(this, getString(R.string.slipnet_scan_error, "no file selected"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        loadFile(data == null ? null : data.getData());
    }

    @Override
    public void onState(ScanState state) {
        if (state == null || isFinishing()) {
            return;
        }
        render(state);
        if (!state.running) {
            stopService(new Intent(this, SlipNetScanService.class));
        }
    }

    private void startScanService() {
        Intent intent = new Intent(this, SlipNetScanService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (RuntimeException ignored) {
            // The scan itself keeps running in-process.
        }
    }

    private void bindViews() {
        modeSpinner = findViewById(R.id.slipnet_scanner_mode);
        sourceSpinner = findViewById(R.id.slipnet_scanner_source);
        addSourceButtons();
        countrySpinner = findViewById(R.id.slipnet_scanner_country);
        transportSpinner = findViewById(R.id.slipnet_scanner_transport);
        domainField = findViewById(R.id.slipnet_scanner_domain);
        portField = findViewById(R.id.slipnet_scanner_port);
        timeoutField = findViewById(R.id.slipnet_scanner_timeout);
        workersField = findViewById(R.id.slipnet_scanner_workers);
        e2eTimeoutField = findViewById(R.id.slipnet_scanner_e2e_timeout);
        e2eWorkersField = findViewById(R.id.slipnet_scanner_e2e_workers);
        minScoreField = findViewById(R.id.slipnet_scanner_min_score);
        sampleField = findViewById(R.id.slipnet_scanner_sample);
        customField = findViewById(R.id.slipnet_scanner_custom);
        shuffleCheck = findViewById(R.id.slipnet_scanner_shuffle);
        expandCheck = findViewById(R.id.slipnet_scanner_expand);
        countryRow = findViewById(R.id.slipnet_scanner_country_row);
        customRow = findViewById(R.id.slipnet_scanner_custom_row);
        telegramRow = findViewById(R.id.slipnet_scanner_telegram_row);
        telegramStatus = findViewById(R.id.slipnet_scanner_telegram_status);
        telegramOpenButton = findViewById(R.id.slipnet_scanner_telegram_open);
        statusView = findViewById(R.id.slipnet_scanner_status);
        previewView = findViewById(R.id.slipnet_scanner_preview);
        progressText = findViewById(R.id.slipnet_scanner_progress);
        phaseText = findViewById(R.id.slipnet_scanner_phase);
        foundText = findViewById(R.id.slipnet_scanner_found);
        passedText = findViewById(R.id.slipnet_scanner_passed);
        proxyView = findViewById(R.id.slipnet_scanner_proxy);
        liveText = findViewById(R.id.slipnet_scanner_live);
        progressBar = findViewById(R.id.slipnet_scanner_progress_bar);
        startButton = findViewById(R.id.slipnet_scanner_start);
        stopButton = findViewById(R.id.slipnet_scanner_stop);
        continueButton = findViewById(R.id.slipnet_scanner_continue);
        resultsButton = findViewById(R.id.slipnet_scanner_results);
    }

    private void setupSpinners() {
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{
                getString(R.string.slipnet_scan_simple),
                getString(R.string.slipnet_scan_advanced),
                getString(R.string.slipnet_scan_e2e)});
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);

        ArrayAdapter<String> sourceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{
                getString(R.string.slipnet_scan_source_default),
                getString(R.string.slipnet_scan_source_paste),
                getString(R.string.slipnet_scan_source_country),
                getString(R.string.slipnet_scan_source_custom),
                "File",
                getString(R.string.slipnet_scan_source_telegram)});
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceSpinner.setAdapter(sourceAdapter);
        sourceSpinner.setSelection(Math.max(0, Math.min(SOURCE_TELEGRAM, lastSourceIndex)));

        ArrayAdapter<String> countryAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, COUNTRY_LABELS);
        countryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        countrySpinner.setAdapter(countryAdapter);
        countrySpinner.setSelection(Math.max(0, Math.min(COUNTRY_CODES.length - 1, lastCountryIndex)));

        ArrayAdapter<String> transportAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"UDP", "TCP", "BOTH"});
        transportAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        transportSpinner.setAdapter(transportAdapter);
    }

    private void setupListeners() {
        sourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateSourceRows();
                if (position == lastSourceIndex) {
                    return;
                }
                sourceBeforeFile = lastSourceIndex;
                lastSourceIndex = position;
                sourceDirty = true;
                if (position == SOURCE_PASTE) {
                    showPasteDialog();
                } else if (position == SOURCE_FILE) {
                    openFilePicker();
                } else {
                    loadSource();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        countrySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == lastCountryIndex) {
                    return;
                }
                lastCountryIndex = position;
                sourceDirty = true;
                if (sourceSpinner.getSelectedItemPosition() == SOURCE_COUNTRY) {
                    loadSource();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        startButton.setOnClickListener(v -> onStartClicked());
        stopButton.setOnClickListener(v -> onStopClicked());
        continueButton.setOnClickListener(v -> onContinueClicked());
        resultsButton.setOnClickListener(v -> openResults());
        if (lastScanButton != null) {
            lastScanButton.setOnClickListener(v -> loadLastScan());
        }
        if (loadPoolButton != null) {
            loadPoolButton.setOnClickListener(v -> loadSavedPool());
        }

        shuffleCheck.setOnCheckedChangeListener((buttonView, isChecked) -> sourceDirty = true);
        expandCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sourceDirty = true;
            int source = sourceSpinner.getSelectedItemPosition();
            if (source == SOURCE_PASTE || source == SOURCE_CUSTOM) {
                loadSource();
            }
        });

        watchDirty(sampleField);
        watchDirty(customField);
    }

    // The layout has no slot for these buttons, so they are built here beside the source spinner.
    private void addSourceButtons() {
        ViewGroup parent = (ViewGroup) sourceSpinner.getParent();
        if (parent == null) {
            return;
        }
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ViewGroup.LayoutParams existing = sourceSpinner.getLayoutParams();
        if (existing instanceof LinearLayout.LayoutParams) {
            rowParams.topMargin = ((LinearLayout.LayoutParams) existing).topMargin;
        }
        int index = parent.indexOfChild(sourceSpinner);

        lastScanButton = new Button(this);
        lastScanButton.setText("Load last scan");
        loadPoolButton = new Button(this);
        loadPoolButton.setText(R.string.slipnet_scan_load_pool);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.addView(lastScanButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams poolParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        poolParams.leftMargin = gap;
        buttons.addView(loadPoolButton, poolParams);

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        parent.removeView(sourceSpinner);
        block.addView(sourceSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        block.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        parent.addView(block, index, rowParams);
    }

    private void watchDirty(EditText field) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                sourceDirty = true;
            }
        });
    }

    private void updateSourceRows() {
        int source = sourceSpinner.getSelectedItemPosition();
        countryRow.setVisibility(source == SOURCE_COUNTRY ? View.VISIBLE : View.GONE);
        customRow.setVisibility(source == SOURCE_CUSTOM ? View.VISIBLE : View.GONE);
        telegramRow.setVisibility(source == SOURCE_TELEGRAM ? View.VISIBLE : View.GONE);
    }

    private void loadConfig() {
        final Context appContext = getApplicationContext();
        new Thread(() -> {
            WhiteDnsProfile loadedProfile = null;
            try {
                loadedProfile = WhiteDnsStore.load(appContext);
            } catch (Exception | LinkageError ignored) {
                loadedProfile = null;
            }
            final WhiteDnsProfile parsed = loadedProfile;
            onUiThread(() -> {
                profile = parsed;
                hasConfig = parsed != null;
                if (hasConfig) {
                    if (domainField.getText().length() == 0) {
                        domainField.setText(parsed.domain);
                    }
                } else {
                    showStatus(getString(R.string.slipnet_scan_need_config));
                }
                refreshButtons();
            });
        }, "SlipNetScanConfig").start();
    }

    private void loadTelegramSource() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String text = prefs.getString(TelegramWebActivity.KEY_TELEGRAM_TEXT, "");
        List<String> parsed = ResolverPool.parse(text);
        hosts.clear();
        hosts.addAll(parsed);
        loading = false;
        if (parsed.isEmpty()) {
            if (telegramStatusView != null) {
                telegramStatusView.setText(R.string.slipnet_scan_telegram_empty);
            }
            statusText.setText(R.string.slipnet_scan_telegram_empty);
        } else {
            if (telegramStatusView != null) {
                telegramStatusView.setText(getString(R.string.slipnet_scan_telegram_count, parsed.size()));
            }
            statusText.setText(getString(R.string.slipnet_scan_telegram_count, parsed.size()));
        }
        refreshButtons();
    }

    private void loadSource() {
        if (sourceSpinner.getSelectedItemPosition() == SOURCE_TELEGRAM) {
            loadTelegramSource();
            return;
        }
        final int token = ++loadToken;
        final Context appContext = getApplicationContext();
        final int source = Math.max(SOURCE_DEFAULT, sourceSpinner.getSelectedItemPosition());
        final String country = COUNTRY_CODES[countryIndex()];
        final int sample = Math.max(1, intValue(sampleField, DEFAULT_SAMPLE));
        final String custom = customField.getText().toString();
        final String paste = pasteText;
        final boolean shuffle = shuffleCheck.isChecked();
        final boolean expand = expandCheck.isChecked();
        final List<String> current = new ArrayList<>(hosts);

        loading = true;
        refreshButtons();
        new Thread(() -> {
            List<String> loaded = new ArrayList<>();
            String failure = null;
            try {
                switch (source) {
                    case SOURCE_DEFAULT:
                        loaded.addAll(ResolverPool.defaultResolvers(appContext));
                        if (shuffle) {
                            loaded = ResolverPool.shuffleTiers(loaded,
                                    ResolverPool.defaultTierBoundaries(appContext));
                        }
                        break;
                    case SOURCE_PASTE:
                        loaded.addAll(paste.trim().isEmpty()
                                ? current : ResolverPool.parse(paste));
                        if (shuffle && !loaded.isEmpty()) {
                            Collections.shuffle(loaded);
                        }
                        break;
                    case SOURCE_COUNTRY:
                        loaded.addAll(ResolverPool.sampleCountry(appContext, country, sample));
                        if (shuffle && !loaded.isEmpty()) {
                            Collections.shuffle(loaded);
                        }
                        break;
                    case SOURCE_FILE:
                        loaded.addAll(fileText.trim().isEmpty()
                                ? current : ResolverPool.parse(fileText));
                        if (shuffle && !loaded.isEmpty()) {
                            Collections.shuffle(loaded);
                        }
                        break;
                    case SOURCE_CUSTOM:
                    default:
                        loaded.addAll(ResolverPool.expandCidrs(custom, MAX_HOSTS));
                        if (shuffle && !loaded.isEmpty()) {
                            Collections.shuffle(loaded);
                        }
                        break;
                }
                if (expand && (source == SOURCE_PASTE || source == SOURCE_CUSTOM)) {
                    loaded = expandToSubnets(loaded);
                }
            } catch (Exception | LinkageError e) {
                failure = messageOf(e);
            }
            final List<String> result = loaded;
            final String error = failure;
            final int finishedToken = token;
            onUiThread(() -> {
                if (finishedToken != loadToken) {
                    return;
                }
                loading = false;
                if (error == null) {
                    hosts.clear();
                    hosts.addAll(result);
                    previewView.setText(getString(R.string.slipnet_scan_preview, hosts.size()));
                    sourceDirty = false;
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                }
                refreshButtons();
                if (startingPending) {
                    startingPending = false;
                    if (!hosts.isEmpty() && !sourceDirty) {
                        dispatchStart();
                    }
                }
            });
        }, "SlipNetScanSource").start();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        try {
            startActivityForResult(intent, REQUEST_OPEN_FILE);
        } catch (RuntimeException e) {
            revertFileSource();
            Toast.makeText(this, getString(R.string.slipnet_scan_error, messageOf(e)),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void loadFile(@Nullable Uri uri) {
        if (uri == null) {
            revertFileSource();
            Toast.makeText(this, getString(R.string.slipnet_scan_error, "no file selected"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        final int token = ++loadToken;
        final Context appContext = getApplicationContext();
        final boolean shuffle = shuffleCheck.isChecked();
        loading = true;
        refreshButtons();
        new Thread(() -> {
            List<String> loaded = new ArrayList<>();
            String text = null;
            String failure = null;
            try {
                text = readText(appContext, uri);
                if (text == null) {
                    failure = appContext.getString(R.string.slipnet_scan_error,
                            "cannot read the selected file");
                } else {
                    loaded.addAll(ResolverPool.parse(text));
                    if (shuffle && !loaded.isEmpty()) {
                        Collections.shuffle(loaded);
                    }
                }
            } catch (Exception | LinkageError e) {
                failure = appContext.getString(R.string.slipnet_scan_error, messageOf(e));
            }
            final List<String> result = loaded;
            final String content = text;
            final String error = failure;
            final int finishedToken = token;
            onUiThread(() -> {
                if (finishedToken != loadToken) {
                    return;
                }
                loading = false;
                if (error == null) {
                    fileText = content == null ? "" : content;
                    hosts.clear();
                    hosts.addAll(result);
                    previewView.setText(getString(R.string.slipnet_scan_preview, hosts.size()));
                    sourceDirty = false;
                } else {
                    revertFileSource();
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                }
                refreshButtons();
                if (startingPending) {
                    startingPending = false;
                    if (!hosts.isEmpty() && !sourceDirty) {
                        dispatchStart();
                    }
                }
            });
        }, "SlipNetScanFile").start();
    }

    private void loadLastScan() {
        SharedPreferences prefs = getSharedPreferences(ScanManager.PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(ScanManager.KEY_WORKING_IPS, "");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (raw != null) {
            String[] lines = raw.split("\r?\n");
            for (int i = 0; i < lines.length && unique.size() < MAX_HOSTS; i++) {
                String host = lines[i].trim();
                if (!host.isEmpty()) {
                    unique.add(host);
                }
            }
        }
        if (unique.isEmpty()) {
            Toast.makeText(this, R.string.slipnet_scan_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        loadToken++;
        loading = false;
        startingPending = false;
        hosts.clear();
        hosts.addAll(unique);
        previewView.setText(getString(R.string.slipnet_scan_preview, hosts.size()));
        sourceDirty = false;
        refreshButtons();
    }

    private void loadSavedPool() {
        List<String> pool = ScanManager.get().loadPool();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (pool != null) {
            for (int i = 0; i < pool.size() && unique.size() < MAX_HOSTS; i++) {
                String host = pool.get(i);
                if (host == null) {
                    continue;
                }
                host = host.trim();
                if (!host.isEmpty()) {
                    unique.add(host);
                }
            }
        }
        if (unique.isEmpty()) {
            Toast.makeText(this, R.string.slipnet_scan_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        loadToken++;
        loading = false;
        startingPending = false;
        hosts.clear();
        hosts.addAll(unique);
        previewView.setText(getString(R.string.slipnet_scan_preview, hosts.size()));
        sourceDirty = false;
        refreshButtons();
    }

    private void revertFileSource() {
        if (sourceSpinner.getSelectedItemPosition() != SOURCE_FILE) {
            return;
        }
        lastSourceIndex = sourceBeforeFile;
        sourceSpinner.setSelection(sourceBeforeFile);
        updateSourceRows();
    }

    private static List<String> expandToSubnets(List<String> source) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        if (source == null) {
            return new ArrayList<>(expanded);
        }
        for (int i = 0; i < source.size() && expanded.size() < MAX_HOSTS; i++) {
            String host = source.get(i);
            if (!ResolverPool.isValidIpv4(host)) {
                continue;
            }
            List<String> subnet = ResolverPool.expandSubnet24(host);
            if (subnet == null || subnet.isEmpty()) {
                expanded.add(host);
                continue;
            }
            for (int j = 0; j < subnet.size() && expanded.size() < MAX_HOSTS; j++) {
                expanded.add(subnet.get(j));
            }
        }
        return new ArrayList<>(expanded);
    }

    private void showPasteDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);

        CharSequence clipboardText = readClipboardText();
        if (clipboardText != null) {
            input.setText(clipboardText);
            input.setSelection(input.length());
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.slipnet_scan_paste_title)
                .setView(withPadding(input))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    pasteText = input.getText().toString();
                    sourceDirty = true;
                    loadSource();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onStartClicked() {
        if (!hasConfig || profile == null) {
            showStatus(getString(R.string.slipnet_scan_need_config));
            return;
        }
        if (loading) {
            startingPending = true;
            refreshButtons();
            return;
        }
        if (sourceDirty || hosts.isEmpty()) {
            startingPending = true;
            loadSource();
            return;
        }
        dispatchStart();
    }

    private void dispatchStart() {
        if (!hasConfig || profile == null) {
            showStatus(getString(R.string.slipnet_scan_need_config));
            return;
        }
        if (hosts.isEmpty()) {
            return;
        }
        final int mode = Math.max(MODE_SIMPLE, modeSpinner.getSelectedItemPosition());
        final String domain = valueOf(domainField);
        final int port = clamp(intValue(portField, 53), 1, 65535);
        final int timeout = Math.max(200, intValue(timeoutField, 3000));
        final int workers = Math.max(1, intValue(workersField, 50));
        final int e2eTimeout = Math.max(200, intValue(e2eTimeoutField, 15000));
        final int e2eWorkers = Math.max(1, intValue(e2eWorkersField, 6));
        final int minScore = clamp(intValue(minScoreField, 2), 0, DnsTunnelResult.MAX_SCORE);
        final boolean both = transportSpinner.getSelectedItemPosition() == TRANSPORT_BOTH;

        if (mode != MODE_E2E && domain.isEmpty()) {
            showStatus(getString(R.string.slipnet_scan_need_config));
            return;
        }

        RunRequest request = new RunRequest();
        request.mode = mode;
        request.hosts = new ArrayList<>(hosts);
        request.port = port;
        request.domain = domain;
        request.timeout = timeout;
        request.workers = workers;
        request.minScore = minScore;
        request.e2eTimeout = e2eTimeout;
        request.e2eWorkers = e2eWorkers;
        request.both = both;
        request.profile = profile;

        lastRun = request;
        stoppedWithPartial = false;
        hideStatus();
        if (request.start(ScanManager.get())) {
            startScanService();
        }
        refreshButtons();
    }

    private void onStopClicked() {
        startingPending = false;
        ScanManager manager = ScanManager.get();
        manager.stop();
        stopService(new Intent(this, SlipNetScanService.class));
        ScanState state = manager.snapshot();
        if (state.results != null && !state.results.isEmpty() && lastRun != null) {
            stoppedWithPartial = true;
        }
        render(state);
    }

    private void onContinueClicked() {
        if (lastRun == null || !stoppedWithPartial) {
            return;
        }
        stoppedWithPartial = false;
        hideStatus();
        if (lastRun.start(ScanManager.get())) {
            startScanService();
        }
        refreshButtons();
    }

    private void openResults() {
        if (isFinishing()) {
            return;
        }
        startActivity(new Intent(this, SlipNetScanResultsActivity.class));
    }

    private void render(ScanState state) {
        if (state == null) {
            return;
        }
        lastState = state;
        int total = Math.max(0, state.total);
        int done = Math.max(0, Math.min(state.done, total));
        progressText.setText(getString(R.string.slipnet_scan_progress, done, total));
        if (total > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setMax(total);
            progressBar.setProgress(done);
        } else {
            progressBar.setIndeterminate(state.running);
            progressBar.setProgress(0);
        }
        phaseText.setText(state.phase == null ? "" : state.phase);

        int working = 0;
        int passed = 0;
        List<ScanResult> results = state.results;
        for (int i = 0; i < results.size(); i++) {
            ScanResult result = results.get(i);
            if (result == null) {
                continue;
            }
            if (result.status == ScanResult.Status.WORKING) {
                working++;
            }
            if (result.e2e != null && result.e2e.success) {
                passed++;
            }
        }
        foundText.setText(getString(R.string.slipnet_scan_found, working));
        passedText.setText(getString(R.string.slipnet_scan_passed, passed));
        renderLive(results);
        proxyView.setVisibility(state.transparentProxy ? View.VISIBLE : View.GONE);
        refreshButtons();
    }

    private void renderLive(List<ScanResult> results) {
        if (results == null || results.isEmpty()) {
            liveText.setText(R.string.slipnet_scan_empty);
            return;
        }
        int from = Math.max(0, results.size() - LIVE_LIMIT);
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < results.size(); i++) {
            ScanResult result = results.get(i);
            if (result == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            appendResult(builder, result);
        }
        liveText.setText(builder);
    }

    private static void appendResult(StringBuilder builder, ScanResult result) {
        builder.append(result.host).append(':').append(result.port).append(' ');
        builder.append(result.status == null ? "?" : result.status.name());
        if (result.responseTimeMs >= 0) {
            builder.append(' ').append(result.responseTimeMs).append("ms");
        }
        if (result.tunnel != null) {
            builder.append(" score=").append(result.tunnel.score())
                    .append('/').append(DnsTunnelResult.MAX_SCORE);
        }
        if (result.prismPassed != null) {
            builder.append(" prism=").append(result.prismPassed)
                    .append('/').append(result.prismTotal == null ? 0 : result.prismTotal);
        }
        if (result.e2e != null) {
            builder.append(result.e2e.success ? " e2e=ok" : " e2e=fail")
                    .append(' ').append(result.e2e.totalMs).append("ms");
        }
    }

    private void refreshButtons() {
        boolean running = lastState != null && lastState.running;
        boolean hasResults = lastState != null && lastState.results != null
                && !lastState.results.isEmpty();
        startButton.setEnabled(hasConfig && !running && !loading && !startingPending
                && !hosts.isEmpty());
        stopButton.setEnabled(running || startingPending);
        continueButton.setEnabled(!running && !loading && !startingPending
                && lastRun != null && stoppedWithPartial);
        resultsButton.setEnabled(hasResults);
    }

    private void showStatus(String text) {
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusView.setText("");
        statusView.setVisibility(View.GONE);
    }

    private int countryIndex() {
        int position = countrySpinner.getSelectedItemPosition();
        return position < 0 || position >= COUNTRY_CODES.length ? 0 : position;
    }

    private static String valueOf(EditText field) {
        return field.getText().toString().trim();
    }

    private static int intValue(EditText field, int fallback) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Nullable
    private static String readText(Context context, Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) {
            return null;
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_FILE_BYTES) {
                    throw new IOException("file too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Nullable
    private CharSequence readClipboardText() {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager == null || !manager.hasPrimaryClip()) {
                return null;
            }
            ClipData clipData = manager.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) {
                return null;
            }
            return clipData.getItemAt(0).coerceToText(this);
        } catch (RuntimeException e) {
            // Clipboard reads can be refused when the app has no window focus.
            return null;
        }
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.toString() : message;
    }

    private View withPadding(View content) {
        FrameLayout container = new FrameLayout(this);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);
        container.addView(content);
        return container;
    }

    private void onUiThread(Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                action.run();
            }
        });
    }

    private static final class RunRequest {
        int mode;
        List<String> hosts;
        int port;
        String domain;
        int timeout;
        int workers;
        int minScore;
        int e2eTimeout;
        int e2eWorkers;
        boolean both;
        WhiteDnsProfile profile;

        boolean start(ScanManager manager) {
            switch (mode) {
                case MODE_SIMPLE:
                    manager.startSimple(hosts, port, domain, profile, timeout, workers, 0,
                            both, minScore, e2eTimeout, e2eWorkers);
                    break;
                case MODE_DNS:
                    manager.startDns(hosts, port, domain, timeout, workers, 0, both);
                    break;
                case MODE_E2E:
                    manager.startE2e(hosts, port, profile, e2eTimeout, e2eWorkers);
                    break;
                default:
                    return false;
            }
            return manager.snapshot().running;
        }
    }
}
