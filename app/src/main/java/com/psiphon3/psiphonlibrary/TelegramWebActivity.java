package com.psiphon3.psiphonlibrary;

import com.psiphon3.R;
import com.psiphon3.TunnelState;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.json.JSONTokener;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import app.slipnet.scanner.ResolverPool;
import app.whitedns.store.WhiteDnsStore;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import net.grandcentrix.tray.AppPreferences;

/**
 * Telegram web screen. Gets a local SOCKS endpoint from one of the connection
 * methods, proves that Telegram answers through it, and only then installs the
 * WebView proxy.
 */
public class TelegramWebActivity extends LocalizedActivities.AppCompatActivity {

    public static final String EXTRA_SCANNER_MODE = "telegram_scanner_mode";

    public static final String PREFS_NAME = "slipnet_prefs";
    public static final String KEY_TELEGRAM_TEXT = "telegram_text";
    public static final String KEY_TELEGRAM_COUNT = "telegram_count";
    public static final String KEY_TELEGRAM_SAVED_AT = "telegram_saved_at";
    public static final String KEY_TELEGRAM_METHOD = "telegram_method";

    private static final String START_URL = "https://web.telegram.org/a/";
    private static final String BLANK_URL = "about:blank";
    private static final long TUNNEL_TIMEOUT_MS = 45000L;
    private static final long TUNNEL_START_TIMEOUT_MS = 60000L;
    private static final long TUNNEL_POLL_INTERVAL_MS = 250L;
    private static final String PROTOCOL_AUTO = "auto";
    private static final String PROTOCOL_CONDUIT = "conduit";
    private static final int PROBE_CONNECT_TIMEOUT_MS = 6000;
    private static final int PROBE_TLS_TIMEOUT_MS = 10000;
    private static final long HEARTBEAT_INTERVAL_MS = 10000L;
    private static final int HEARTBEAT_TIMEOUT_MS = 4000;
    private static final long SPEED_INTERVAL_MS = 1000L;
    private static final long LOG_REFRESH_INTERVAL_MS = 500L;
    private static final int MAX_HEARTBEAT_FAILURES = 2;
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024; // 2 MiB
    private static final int REQUEST_FILE_CHOOSER = 4103;
    private static final int REQUEST_SERVERS = 4105;

    private enum State { IDLE, CONNECTING, VERIFYING, CONNECTED, NO_INTERNET, ERROR }

    private Spinner spMethod;
    private Button btnConnect;
    private Button btnServers;
    private Button btnRead;
    private Button btnSave;
    private Button btnLogout;
    private Button btnClose;
    private Button btnRefresh;
    private Button btnLogToggle;
    private Button btnLogClear;
    private TextView tvState;
    private TextView tvPing;
    private TextView tvSpeed;
    private TextView tvActiveServer;
    private TextView tvStatus;
    private TextView tvLog;
    private View scannerRow;
    private View logArea;
    private ScrollView logScroll;
    private ProgressBar pbProgress;
    private WebView webView;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CompositeDisposable disposables = new CompositeDisposable();

    private ValueCallback<Uri[]> fileChooserCallback;
    private TelegramConnector.Link link;
    private volatile TunnelState lastTunnelState;
    private boolean mainRunning;
    private int mainSocksPort;
    private volatile int generation;
    private boolean protocolChangedByUs;
    private boolean tunnelStartedByUs;
    private boolean tunnelRestartedForProtocol;
    private String savedProtocol = PROTOCOL_AUTO;
    private boolean pageHooked;
    private boolean scannerMode;
    private boolean resumed;
    private boolean suppressMethodAutoOpen;
    private boolean logVisible;
    private String lastExtractedText = "";
    private int lastExtractedCount;

    private State state = State.IDLE;
    private int lastPingMs = -1;
    private int heartbeatFailures;
    private boolean heartbeatRunning;
    private int heartbeatSeq;
    private long speedPrevRx = -1L;
    private long speedPrevTx = -1L;
    private long speedPrevAt;
    private long lastDownSpeed = -1L;
    private long lastUpSpeed = -1L;

    private final Runnable heartbeatTask = this::runHeartbeat;
    private final Runnable speedTask = this::sampleSpeed;
    private final Runnable logTask = new Runnable() {
        @Override
        public void run() {
            refreshLog();
            if (logVisible && resumed) {
                mainHandler.postDelayed(this, LOG_REFRESH_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.telegram_web);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        scannerMode = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_SCANNER_MODE, false);

        spMethod = findViewById(R.id.sp_telegram_method);
        btnConnect = findViewById(R.id.btn_telegram_connect);
        btnServers = findViewById(R.id.btn_telegram_servers);
        btnRead = findViewById(R.id.btn_telegram_read);
        btnSave = findViewById(R.id.btn_telegram_save);
        btnLogout = findViewById(R.id.btn_telegram_logout);
        btnClose = findViewById(R.id.btn_telegram_close);
        btnRefresh = findViewById(R.id.btn_telegram_refresh);
        btnLogToggle = findViewById(R.id.btn_telegram_log_toggle);
        btnLogClear = findViewById(R.id.btn_telegram_log_clear);
        tvState = findViewById(R.id.tv_telegram_state);
        tvPing = findViewById(R.id.tv_telegram_ping);
        tvSpeed = findViewById(R.id.tv_telegram_speed);
        tvActiveServer = findViewById(R.id.tv_telegram_active_server);
        tvStatus = findViewById(R.id.tv_telegram_status);
        tvLog = findViewById(R.id.tv_telegram_log);
        scannerRow = findViewById(R.id.ll_telegram_scanner_row);
        logArea = findViewById(R.id.ll_telegram_log_area);
        logScroll = findViewById(R.id.sv_telegram_log);
        pbProgress = findViewById(R.id.pb_telegram_progress);
        webView = findViewById(R.id.wv_telegram);

        scannerRow.setVisibility(scannerMode ? View.VISIBLE : View.GONE);

        setupMethodSpinner();
        setupWebView();

        btnConnect.setOnClickListener(v -> onConnectClicked());
        btnServers.setOnClickListener(v -> openServers(selectedMethod()));
        btnRefresh.setOnClickListener(v -> refreshPage());
        btnLogToggle.setOnClickListener(v -> toggleLog());
        btnLogClear.setOnClickListener(v -> clearLog());
        btnRead.setOnClickListener(v -> readChatList());
        btnSave.setOnClickListener(v -> saveList());
        btnLogout.setOnClickListener(v -> logout());
        btnClose.setOnClickListener(v -> finish());

        resetExtracted();
        updateStateUi();
        updateStatusLines();
        refreshActiveServerLine();
        updateLogToggle();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void setupMethodSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.telegram_method_names, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMethod.setAdapter(adapter);

        int saved = getPrefs().getInt(KEY_TELEGRAM_METHOD,
                TelegramConnector.Method.WHITEDNS.ordinal());
        if (saved < 0 || saved >= TelegramConnector.Method.values().length) {
            saved = TelegramConnector.Method.WHITEDNS.ordinal();
        }
        suppressMethodAutoOpen = true;
        spMethod.setSelection(saved, false);
        spMethod.post(() -> suppressMethodAutoOpen = false);

        spMethod.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPrefs().edit().putInt(KEY_TELEGRAM_METHOD, position).apply();
                refreshActiveServerLine();
                if (suppressMethodAutoOpen || state == State.CONNECTED
                        || state == State.CONNECTING || state == State.VERIFYING) {
                    return;
                }
                TelegramConnector.Method method = selectedMethod();
                if (method == TelegramConnector.Method.NAPSTER
                        || method == TelegramConnector.Method.V2RAY) {
                    openServers(method);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private TelegramConnector.Method selectedMethod() {
        int position = spMethod.getSelectedItemPosition();
        TelegramConnector.Method[] methods = TelegramConnector.Method.values();
        if (position < 0 || position >= methods.length) {
            return TelegramConnector.Method.WHITEDNS;
        }
        return methods[position];
    }

    private String methodName(TelegramConnector.Method method) {
        int position = method.ordinal();
        if (position < 0 || position >= spMethod.getCount()) {
            return method.name();
        }
        Object item = spMethod.getItemAtPosition(position);
        return item == null ? method.name() : item.toString();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        webView.addJavascriptInterface(
                new TelegramDownloadBridge(this, mainHandler), TelegramDownloadBridge.JS_NAME);

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                handleDownload(url, userAgent, contentDisposition, mimeType));

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (pbProgress == null) {
                    return;
                }
                if (newProgress < 100) {
                    pbProgress.setVisibility(View.VISIBLE);
                    pbProgress.setProgress(newProgress);
                } else {
                    pbProgress.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                    Message resultMsg) {
                if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
                    return false;
                }
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(webView);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams fileChooserParams) {
                return showFileChooser(callback, fileChooserParams);
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webView.setWebViewClient(new ModernClient());
        } else {
            webView.setWebViewClient(new LegacyClient());
        }
    }

    private class BaseClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request == null || request.getUrl() == null) {
                return true;
            }
            return handleUrl(request.getUrl().toString());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageHooked = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (pageHooked || url == null) {
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                return;
            }
            if (!isTelegramHost(Uri.parse(url).getHost())) {
                return;
            }
            pageHooked = true;
            view.evaluateJavascript(TelegramDownloadBridge.JS_HOOK, null);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private final class ModernClient extends BaseClient {
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                WebResourceError error) {
            if (request == null || !request.isForMainFrame()) {
                return;
            }
            onMainFrameError(error == null ? -1 : error.getErrorCode());
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                WebResourceResponse errorResponse) {
            if (request == null || !request.isForMainFrame()) {
                return;
            }
            onMainFrameHttpError(errorResponse == null ? -1 : errorResponse.getStatusCode());
        }
    }

    private final class LegacyClient extends BaseClient {
        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                String failingUrl) {
            String current = view == null ? null : view.getUrl();
            if (failingUrl == null || current == null || !failingUrl.equals(current)) {
                return;
            }
            onMainFrameError(errorCode);
        }
    }

    private void onMainFrameError(int errorCode) {
        if (isFinishing() || state != State.CONNECTED) {
            return;
        }
        TelegramSessionLog.append('W', "page error " + errorCode);
        if (pbProgress != null) {
            pbProgress.setVisibility(View.GONE);
        }
        setState(State.NO_INTERNET);
        setDetail(getString(R.string.telegram_proxy_lost));
    }

    private void onMainFrameHttpError(int statusCode) {
        if (isFinishing() || state != State.CONNECTED) {
            return;
        }
        TelegramSessionLog.append('W', "http error " + statusCode);
        if (pbProgress != null) {
            pbProgress.setVisibility(View.GONE);
        }
        setState(State.NO_INTERNET);
        setDetail(getString(R.string.telegram_proxy_lost));
    }

    private static boolean isTelegramHost(String host) {
        if (host == null) {
            return false;
        }
        String value = host.toLowerCase(Locale.US);
        return value.equals("telegram.org") || value.endsWith(".telegram.org")
                || value.equals("t.me") || value.endsWith(".t.me")
                || value.equals("telegram.me") || value.endsWith(".telegram.me");
    }

    private boolean handleUrl(String url) {
        if (url == null) {
            return true;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (scheme == null) {
            return true;
        }
        String value = scheme.toLowerCase(Locale.US);
        if (value.equals("http") || value.equals("https")) {
            return !isTelegramHost(uri.getHost());
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (RuntimeException ignored) {
        }
        return true;
    }

    private boolean showFileChooser(ValueCallback<Uri[]> callback,
            WebChromeClient.FileChooserParams params) {
        if (callback == null) {
            return false;
        }
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
        }
        fileChooserCallback = callback;

        Intent intent = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && params != null) {
            try {
                intent = params.createIntent();
            } catch (RuntimeException e) {
                intent = null;
            }
        }
        if (intent == null) {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
        }
        try {
            startActivityForResult(Intent.createChooser(intent, null), REQUEST_FILE_CHOOSER);
        } catch (RuntimeException e) {
            fileChooserCallback = null;
            callback.onReceiveValue(null);
            return false;
        }
        return true;
    }

    private void onConnectClicked() {
        if (state == State.CONNECTED) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        if (state == State.CONNECTING || state == State.VERIFYING) {
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            TelegramSessionLog.append('E', "proxy override not supported");
            setState(State.ERROR);
            setDetail(getString(R.string.telegram_error_unsupported_webview));
            return;
        }

        TelegramConnector.Method method = selectedMethod();
        boolean upstreamEnabled = V2RayConfigStore.isFeatureEnabled(this)
                || NapsterConfigStore.isFeatureEnabled(this);

        if ((method == TelegramConnector.Method.MAIN || method == TelegramConnector.Method.COMBINED)
                && !mainRunning) {
            TelegramSessionLog.append('E', "main tunnel is off");
            setState(State.ERROR);
            setDetail(getString(R.string.telegram_error_main_off));
            return;
        }
        if (method == TelegramConnector.Method.COMBINED && !upstreamEnabled) {
            TelegramSessionLog.append('E', "upstream is off");
            setState(State.ERROR);
            setDetail(getString(R.string.telegram_error_upstream_off));
            return;
        }
        if (method == TelegramConnector.Method.WHITEDNS && !WhiteDnsStore.hasProfile(this)) {
            TelegramSessionLog.append('E', "no WhiteDNS profile");
            setState(State.ERROR);
            setDetail(getString(R.string.telegram_error_no_config));
            return;
        }

        final int myGeneration = ++generation;
        final TelegramConnector.Method methodToUse = method;
        final boolean upstream = upstreamEnabled;
        final boolean running = mainRunning;
        final int socksPort = mainSocksPort;

        stopHeartbeat();
        stopSpeed();
        resetSpeedBaseline();
        clearProxy();
        closeLink();
        lastPingMs = -1;
        heartbeatFailures = 0;
        updateStatusLines();

        final boolean tunnelStartedHere = prepareTunnelProtocol(methodToUse);
        final boolean expectDown = tunnelRestartedForProtocol;

        setState(State.CONNECTING);
        setDetail(getString(R.string.telegram_connecting_method, methodName(method)));
        TelegramSessionLog.append('I', "connecting " + logMethodName(method));

        Thread worker = new Thread(() -> {
            TelegramConnector.Link result = null;
            String error = null;
            boolean tunnelReady = running;
            int tunnelPort = socksPort;
            try {
                if (tunnelStartedHere) {
                    int port = waitForTunnel(myGeneration, expectDown);
                    if (port <= 0) {
                        runOnUiThread(() -> onTunnelWaitFailed(myGeneration,
                                R.string.telegram_error_protocol));
                        return;
                    }
                    tunnelReady = true;
                    tunnelPort = port;
                }
                result = TelegramConnector.connect(getApplicationContext(), methodToUse,
                        tunnelReady, tunnelPort, upstream, TUNNEL_TIMEOUT_MS);
            } catch (IOException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            TelegramReachability.Result probe = null;
            if (result != null) {
                TelegramSessionLog.append('I', "link up, probing Telegram");
                final TelegramConnector.Link established = result;
                runOnUiThread(() -> onLinkEstablished(myGeneration));
                try {
                    probe = TelegramReachability.probe(established.socksPort,
                            PROBE_CONNECT_TIMEOUT_MS, PROBE_TLS_TIMEOUT_MS);
                } catch (RuntimeException e) {
                    probe = new TelegramReachability.Result(
                            TelegramReachability.Verdict.NO_INTERNET, 0, "probe failed");
                }
            }
            final TelegramConnector.Link connected = result;
            final TelegramReachability.Result verdict = probe;
            final String failure = error;
            runOnUiThread(() -> onConnectFinished(myGeneration, connected, verdict, failure));
        }, "TelegramConnect");
        worker.setDaemon(true);
        worker.start();
    }

    private static String logMethodName(TelegramConnector.Method method) {
        switch (method) {
            case CONDUIT:
                return "conduit";
            case CDN_FRONTING:
                return "cdn fronting";
            default:
                return method.name();
        }
    }

    /**
     * Points the tunnel at the protocol behind the conduit method, restarting the tunnel when it
     * is already up with another protocol. Returns true when the caller must wait for the tunnel
     * to connect. CDN fronting never uses the main tunnel: it starts its own local core.
     */
    private boolean prepareTunnelProtocol(TelegramConnector.Method method) {
        tunnelRestartedForProtocol = false;
        if (method != TelegramConnector.Method.CONDUIT) {
            return false;
        }
        String key = getString(R.string.protocolSelectionPreference);
        String target = PROTOCOL_CONDUIT;
        String current = PROTOCOL_AUTO;
        try {
            current = new AppPreferences(this).getString(key, PROTOCOL_AUTO);
        } catch (RuntimeException ignored) {
        }
        boolean switchProtocol = !target.equals(current);
        boolean runningNow = mainRunning && mainSocksPort > 0;

        if (!runningNow) {
            if (switchProtocol) {
                applyProtocolChange(key, current, target);
            }
            tunnelStartedByUs = true;
            startTunnel();
            TelegramSessionLog.append('I', "starting tunnel with " + logMethodName(method));
            return true;
        }
        if (!switchProtocol) {
            return false;
        }
        getTunnelServiceInteractor().stopTunnelService();
        applyProtocolChange(key, current, target);
        tunnelStartedByUs = true;
        tunnelRestartedForProtocol = true;
        startTunnel();
        TelegramSessionLog.append('I', "restarting tunnel with " + logMethodName(method));
        return true;
    }

    private void applyProtocolChange(String key, String current, String target) {
        try {
            new AppPreferences(this).put(key, target);
        } catch (RuntimeException e) {
            return;
        }
        savedProtocol = current;
        protocolChangedByUs = true;
    }

    private int waitForTunnel(int myGeneration, boolean expectDownFirst) {
        long deadline = SystemClock.elapsedRealtime() + TUNNEL_START_TIMEOUT_MS;
        boolean tunnelDown = !expectDownFirst;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (myGeneration != generation || isFinishing()) {
                return -1;
            }
            TunnelState snapshot = lastTunnelState;
            TunnelState.ConnectionData data = snapshot == null ? null : snapshot.connectionData();
            boolean connected = data != null && data.isConnected() && data.socksPort() > 0;
            if (!connected) {
                tunnelDown = true;
            } else if (tunnelDown) {
                return data.socksPort();
            }
            try {
                Thread.sleep(TUNNEL_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }

    private void onTunnelWaitFailed(int myGeneration, int messageRes) {
        releaseTunnelControl();
        if (myGeneration != generation || isFinishing()) {
            return;
        }
        TelegramSessionLog.append('E', "tunnel did not come up with this protocol");
        setState(State.ERROR);
        setDetail(getString(messageRes));
    }

    /** Stops a tunnel this screen started and puts the previous protocol value back. */
    private void releaseTunnelControl() {
        tunnelRestartedForProtocol = false;
        if (tunnelStartedByUs) {
            tunnelStartedByUs = false;
            try {
                getTunnelServiceInteractor().stopTunnelService();
                TelegramSessionLog.append('I', "tunnel stopped");
            } catch (RuntimeException ignored) {
            }
        }
        if (protocolChangedByUs) {
            protocolChangedByUs = false;
            String restore = savedProtocol == null ? PROTOCOL_AUTO : savedProtocol;
            savedProtocol = PROTOCOL_AUTO;
            try {
                new AppPreferences(this).put(
                        getString(R.string.protocolSelectionPreference), restore);
                TelegramSessionLog.append('I', "protocol restored to " + restore);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void onLinkEstablished(int myGeneration) {
        if (myGeneration != generation || isFinishing()) {
            return;
        }
        setState(State.VERIFYING);
        setDetail(getString(R.string.telegram_verifying_detail));
    }

    private void onConnectFinished(int myGeneration, @Nullable TelegramConnector.Link result,
            @Nullable TelegramReachability.Result probe, @Nullable String error) {
        if (myGeneration != generation || isFinishing()) {
            if (result != null) {
                result.close();
            }
            return;
        }
        if (result == null) {
            TelegramSessionLog.append('E', "connect failed");
            setState(State.ERROR);
            setDetail(getString(R.string.telegram_error_method, error == null ? "" : error));
            return;
        }
        TelegramReachability.Result verdict = probe;
        if (verdict == null) {
            verdict = new TelegramReachability.Result(
                    TelegramReachability.Verdict.NO_INTERNET, 0, "no probe");
        }
        logProbe(verdict);
        if (verdict.verdict != TelegramReachability.Verdict.OK) {
            result.close();
            if (verdict.verdict == TelegramReachability.Verdict.PROXY_DOWN) {
                setState(State.ERROR);
            } else {
                setState(State.NO_INTERNET);
            }
            setDetail(getString(R.string.telegram_error_method,
                    verdict.detail == null ? "" : verdict.detail));
            return;
        }
        link = result;
        lastPingMs = verdict.latencyMs;
        heartbeatFailures = 0;
        updateStatusLines();
        setState(State.CONNECTED);
        setDetail("");
        applyProxy(result.socksPort, myGeneration);
    }

    private void logProbe(TelegramReachability.Result result) {
        String detail = result.detail == null ? "" : result.detail;
        char level = result.verdict == TelegramReachability.Verdict.OK ? 'I' : 'W';
        TelegramSessionLog.append(level, "probe "
                + result.verdict.name().toLowerCase(Locale.US) + " " + result.latencyMs
                + " ms (" + detail + ")");
    }

    private void applyProxy(final int socksPort, final int myGeneration) {
        try {
            ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule("socks5://127.0.0.1:" + socksPort)
                    .build();
            ProxyController.getInstance().setProxyOverride(config,
                    command -> mainHandler.post(command),
                    () -> mainHandler.post(() -> {
                        if (myGeneration != generation || isFinishing() || webView == null) {
                            return;
                        }
                        if (link == null || state != State.CONNECTED) {
                            return;
                        }
                        TelegramSessionLog.append('I', "proxy applied");
                        webView.loadUrl(START_URL);
                    }));
        } catch (RuntimeException e) {
            onProxyUnavailable(myGeneration);
        }
    }

    private void onProxyUnavailable(int myGeneration) {
        if (myGeneration != generation || isFinishing()) {
            return;
        }
        TelegramSessionLog.append('E', "proxy override failed");
        clearProxy();
        closeLink();
        setState(State.ERROR);
        setDetail(getString(R.string.telegram_error_unsupported_webview));
    }

    private void clearProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            return;
        }
        try {
            ProxyController.getInstance().clearProxyOverride(
                    command -> mainHandler.post(command), () -> {
                    });
        } catch (RuntimeException ignored) {
        }
    }

    private void closeLink() {
        TelegramConnector.Link old = link;
        link = null;
        if (old != null) {
            old.close();
        }
    }

    private void disconnect() {
        generation++;
        stopHeartbeat();
        stopSpeed();
        resetSpeedBaseline();
        clearProxy();
        closeLink();
        releaseTunnelControl();
        heartbeatFailures = 0;
        lastPingMs = -1;
        setState(State.IDLE);
        setDetail(getString(R.string.telegram_hint));
        updateStatusLines();
        if (webView != null) {
            webView.loadUrl(BLANK_URL);
        }
        TelegramSessionLog.append('I', "disconnected");
    }

    private void setState(State next) {
        State previous = state;
        state = next;
        if (previous == State.CONNECTED && next != State.CONNECTED) {
            stopHeartbeat();
            stopSpeed();
            resetSpeedBaseline();
        }
        if (next == State.CONNECTED) {
            resetSpeedBaseline();
        }
        updateStateUi();
        if (next == State.CONNECTED && resumed) {
            startHeartbeat();
            startSpeed();
        }
    }

    private void updateStateUi() {
        if (tvState == null) {
            return;
        }
        tvState.setText(stateLabel());
        tvState.setTextColor(stateColor());
        if (state == State.CONNECTED) {
            btnConnect.setText(R.string.telegram_disconnect);
        } else if (link != null) {
            btnConnect.setText(R.string.telegram_try_again);
        } else {
            btnConnect.setText(R.string.telegram_connect);
        }
        btnConnect.setEnabled(state != State.CONNECTING && state != State.VERIFYING);
        boolean methodLocked = state == State.CONNECTED || state == State.CONNECTING
                || state == State.VERIFYING;
        spMethod.setEnabled(!methodLocked);
        btnRead.setEnabled(state == State.CONNECTED);
        btnServers.setEnabled(true);
        if (pbProgress != null) {
            if (state == State.CONNECTING || state == State.VERIFYING) {
                pbProgress.setVisibility(View.VISIBLE);
                pbProgress.setProgress(0);
            } else {
                pbProgress.setVisibility(View.GONE);
            }
        }
    }

    private int stateLabel() {
        switch (state) {
            case CONNECTING:
                return R.string.telegram_status_connecting;
            case VERIFYING:
                return R.string.telegram_status_verifying;
            case CONNECTED:
                return R.string.telegram_status_connected;
            case NO_INTERNET:
                return R.string.telegram_status_no_internet;
            case ERROR:
                return R.string.telegram_status_error;
            case IDLE:
            default:
                return R.string.telegram_status_idle;
        }
    }

    private int stateColor() {
        switch (state) {
            case CONNECTED:
                return 0xFF2E7D32;
            case CONNECTING:
            case VERIFYING:
                return 0xFFEF6C00;
            case NO_INTERNET:
                return 0xFFD84315;
            case ERROR:
                return 0xFFC62828;
            case IDLE:
            default:
                return 0xFF616161;
        }
    }

    private void setDetail(String text) {
        if (tvStatus != null) {
            tvStatus.setText(text == null ? "" : text);
        }
    }

    private void updateStatusLines() {
        if (tvPing != null) {
            String value = lastPingMs >= 0
                    ? getString(R.string.telegram_ping_ms, lastPingMs)
                    : getString(R.string.telegram_no_value);
            tvPing.setText(getString(R.string.telegram_ping, value));
        }
        updateSpeedLine();
    }

    private void updateSpeedLine() {
        if (tvSpeed == null) {
            return;
        }
        String down = lastDownSpeed >= 0L
                ? TelegramSpeedMeter.format(lastDownSpeed)
                : getString(R.string.telegram_no_value);
        String up = lastUpSpeed >= 0L
                ? TelegramSpeedMeter.format(lastUpSpeed)
                : getString(R.string.telegram_no_value);
        tvSpeed.setText(getString(R.string.telegram_speed, down, up));
    }

    private void refreshActiveServerLine() {
        if (tvActiveServer == null) {
            return;
        }
        String name = null;
        TelegramConnector.Method method = selectedMethod();
        try {
            String activeId = TelegramServerCatalog.activeId(this, method);
            if (activeId != null && activeId.length() > 0) {
                List<TelegramServer> servers = TelegramServerCatalog.load(this, method);
                for (int index = 0; index < servers.size(); index++) {
                    TelegramServer server = servers.get(index);
                    if (server != null && activeId.equals(server.id)) {
                        name = server.name;
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        String shown = name == null || name.trim().isEmpty()
                ? getString(R.string.telegram_no_server)
                : name;
        tvActiveServer.setText(getString(R.string.telegram_active_server, shown));
    }

    private void openServers(TelegramConnector.Method method) {
        if (method == null) {
            return;
        }
        Intent intent = new Intent(this, TelegramServersActivity.class);
        intent.putExtra(TelegramServersActivity.EXTRA_METHOD, method.ordinal());
        startActivityForResult(intent, REQUEST_SERVERS);
    }

    private void refreshPage() {
        if (webView == null) {
            return;
        }
        webView.reload();
        TelegramSessionLog.append('I', "reload");
    }

    private void toggleLog() {
        logVisible = !logVisible;
        updateLogToggle();
        if (logVisible) {
            refreshLog();
            startLogRefresh();
        } else {
            mainHandler.removeCallbacks(logTask);
        }
    }

    private void updateLogToggle() {
        if (logArea != null) {
            logArea.setVisibility(logVisible ? View.VISIBLE : View.GONE);
        }
        if (btnLogToggle != null) {
            btnLogToggle.setText(logVisible ? R.string.telegram_log_hide
                    : R.string.telegram_log);
        }
    }

    private void clearLog() {
        TelegramSessionLog.clear();
        if (tvLog != null) {
            tvLog.setText("");
        }
    }

    private void startLogRefresh() {
        mainHandler.removeCallbacks(logTask);
        if (logVisible && resumed) {
            mainHandler.postDelayed(logTask, LOG_REFRESH_INTERVAL_MS);
        }
    }

    private void refreshLog() {
        if (!logVisible || tvLog == null || isFinishing()) {
            return;
        }
        String snapshot = TelegramSessionLog.snapshot();
        if (snapshot == null) {
            snapshot = "";
        }
        if (!snapshot.contentEquals(tvLog.getText())) {
            tvLog.setText(snapshot);
            if (logScroll != null) {
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private void startHeartbeat() {
        mainHandler.removeCallbacks(heartbeatTask);
        heartbeatSeq++;
        heartbeatRunning = false;
        heartbeatFailures = 0;
        if (state == State.CONNECTED && resumed) {
            mainHandler.postDelayed(heartbeatTask, HEARTBEAT_INTERVAL_MS);
        }
    }

    private void stopHeartbeat() {
        mainHandler.removeCallbacks(heartbeatTask);
        heartbeatSeq++;
        heartbeatRunning = false;
    }

    private void scheduleHeartbeat() {
        mainHandler.removeCallbacks(heartbeatTask);
        if (state == State.CONNECTED && resumed && !isFinishing()) {
            mainHandler.postDelayed(heartbeatTask, HEARTBEAT_INTERVAL_MS);
        }
    }

    private void runHeartbeat() {
        if (isFinishing() || !resumed || state != State.CONNECTED || link == null) {
            return;
        }
        if (heartbeatRunning) {
            scheduleHeartbeat();
            return;
        }
        final TelegramConnector.Link active = link;
        final int myGeneration = generation;
        final int mySeq = ++heartbeatSeq;
        heartbeatRunning = true;
        Thread worker = new Thread(() -> {
            long started = SystemClock.elapsedRealtime();
            boolean alive;
            try {
                alive = TelegramReachability.heartbeat(active.socksPort, HEARTBEAT_TIMEOUT_MS);
            } catch (RuntimeException e) {
                alive = false;
            }
            int measured = (int) Math.min(SystemClock.elapsedRealtime() - started,
                    Integer.MAX_VALUE);
            final boolean ok = alive;
            final int latencyMs = measured;
            runOnUiThread(() -> onHeartbeatResult(myGeneration, mySeq, active, ok, latencyMs));
        }, "TelegramHeartbeat");
        worker.setDaemon(true);
        worker.start();
    }

    private void onHeartbeatResult(int myGeneration, int mySeq, TelegramConnector.Link active,
            boolean alive, int latencyMs) {
        if (mySeq != heartbeatSeq) {
            return;
        }
        heartbeatRunning = false;
        if (isFinishing() || myGeneration != generation || link != active
                || state != State.CONNECTED) {
            return;
        }
        if (alive) {
            heartbeatFailures = 0;
            lastPingMs = latencyMs;
            updateStatusLines();
            TelegramSessionLog.append('I', "heartbeat ok " + latencyMs + " ms");
            scheduleHeartbeat();
            return;
        }
        heartbeatFailures++;
        TelegramSessionLog.append('W', "heartbeat failed " + heartbeatFailures + "/"
                + MAX_HEARTBEAT_FAILURES);
        if (heartbeatFailures >= MAX_HEARTBEAT_FAILURES) {
            heartbeatFailures = 0;
            setState(State.NO_INTERNET);
            setDetail(getString(R.string.telegram_proxy_lost));
            TelegramSessionLog.append('W', "heartbeat failed twice, link kept");
            return;
        }
        scheduleHeartbeat();
    }

    private void startSpeed() {
        mainHandler.removeCallbacks(speedTask);
        if (state == State.CONNECTED && resumed) {
            mainHandler.postDelayed(speedTask, SPEED_INTERVAL_MS);
        }
    }

    private void stopSpeed() {
        mainHandler.removeCallbacks(speedTask);
    }

    private void resetSpeedBaseline() {
        speedPrevRx = -1L;
        speedPrevTx = -1L;
        speedPrevAt = 0L;
        lastDownSpeed = -1L;
        lastUpSpeed = -1L;
        updateSpeedLine();
    }

    private void sampleSpeed() {
        long rx = TrafficStats.getUidRxBytes(Process.myUid());
        long tx = TrafficStats.getUidTxBytes(Process.myUid());
        long now = SystemClock.elapsedRealtime();
        if (speedPrevRx < 0L || speedPrevTx < 0L || speedPrevAt == 0L) {
            speedPrevRx = rx;
            speedPrevTx = tx;
            speedPrevAt = now;
            lastDownSpeed = -1L;
            lastUpSpeed = -1L;
        } else {
            long elapsed = now - speedPrevAt;
            lastDownSpeed = TelegramSpeedMeter.rate(speedPrevRx, 0L, rx, 0L, elapsed);
            lastUpSpeed = TelegramSpeedMeter.rate(0L, speedPrevTx, 0L, tx, elapsed);
            speedPrevRx = rx;
            speedPrevTx = tx;
            speedPrevAt = now;
        }
        updateSpeedLine();
        if (state == State.CONNECTED && resumed) {
            mainHandler.postDelayed(speedTask, SPEED_INTERVAL_MS);
        }
    }

    private void handleDownload(String url, String userAgent, String contentDisposition,
            String mimeType) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        if (!TelegramDownloader.hasStoragePermission(this)) {
            TelegramDownloader.ensurePermission(this);
            tvStatus.setText(R.string.telegram_error_storage_permission);
            return;
        }
        String cookies = CookieManager.getInstance().getCookie(url);
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        TelegramConnector.Link active = link;
        TelegramConnector.Method method = active != null ? active.method : selectedMethod();
        if (method == TelegramConnector.Method.MAIN || method == TelegramConnector.Method.COMBINED) {
            TelegramDownloader.enqueueSystem(getApplicationContext(), url, cookies, userAgent,
                    fileName, mimeType);
            TelegramSessionLog.append('I', "download via system");
            return;
        }
        if (active == null) {
            tvStatus.setText(R.string.telegram_download_failed);
            TelegramSessionLog.append('W', "download without a link");
            return;
        }
        TelegramSessionLog.append('I', "download via tunnel");
        TelegramDownloader.downloadViaSocks(this, mainHandler, active.socksPort, url, cookies,
                userAgent, fileName, mimeType, new TelegramDownloader.Result() {
                    @Override
                    public void onDone(String path) {
                        if (isFinishing()) {
                            return;
                        }
                        TelegramSessionLog.append('I', "download saved");
                        tvStatus.setText(getString(R.string.telegram_download_saved, path));
                    }

                    @Override
                    public void onError(String message) {
                        if (isFinishing()) {
                            return;
                        }
                        TelegramSessionLog.append('W', "download failed");
                        tvStatus.setText(isTooLarge(message)
                                ? R.string.telegram_download_too_large
                                : R.string.telegram_download_failed);
                    }
                });
    }

    private static boolean isTooLarge(String message) {
        return message != null && message.toLowerCase(Locale.US).contains("too large");
    }

    private void readChatList() {
        if (webView == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        webView.evaluateJavascript("(function(){return document.body?document.body.innerText:'';})()", value -> {
            String text = "";
            if (value != null && !value.equals("null")) {
                try {
                    Object parsed = new JSONTokener(value).nextValue();
                    text = String.valueOf(parsed);
                } catch (Exception e) {
                    text = value;
                }
            }

            List<String> ips = ResolverPool.parse(text);
            if (!ips.isEmpty()) {
                lastExtractedText = text;
                lastExtractedCount = ips.size();
                tvStatus.setText(getString(R.string.telegram_status_found, lastExtractedCount));
                btnSave.setText(getString(R.string.telegram_save, lastExtractedCount));
                btnSave.setEnabled(true);
                TelegramSessionLog.append('I', "read " + lastExtractedCount + " addresses");
            } else {
                tvStatus.setText(R.string.telegram_status_none);
                btnSave.setEnabled(false);
                TelegramSessionLog.append('I', "read no addresses");
            }
        });
    }

    private void saveList() {
        if (lastExtractedCount == 0 || lastExtractedText.isEmpty()) {
            return;
        }

        String toSave = lastExtractedText;
        if (toSave.length() > MAX_TEXT_BYTES) {
            toSave = toSave.substring(0, MAX_TEXT_BYTES);
        }

        getPrefs().edit()
                .putString(KEY_TELEGRAM_TEXT, toSave)
                .putInt(KEY_TELEGRAM_COUNT, lastExtractedCount)
                .putLong(KEY_TELEGRAM_SAVED_AT, System.currentTimeMillis())
                .apply();

        TelegramSessionLog.append('I', "list saved");
        Toast.makeText(this, getString(R.string.telegram_saved_ok, lastExtractedCount),
                Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void resetExtracted() {
        lastExtractedText = "";
        lastExtractedCount = 0;
        if (btnSave != null) {
            btnSave.setText(getString(R.string.telegram_save, 0));
            btnSave.setEnabled(false);
        }
    }

    private void logout() {
        disconnect();
        new AlertDialog.Builder(this)
                .setTitle(R.string.telegram_logout_title)
                .setCancelable(true)
                .setPositiveButton(R.string.telegram_logout_wipe,
                        (dialog, which) -> wipeTelegramData())
                .setNeutralButton(R.string.telegram_logout_keep,
                        (dialog, which) -> keepTelegramSession())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void wipeTelegramData() {
        try {
            final CookieManager cookies = CookieManager.getInstance();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookies.removeAllCookies(value -> cookies.flush());
            } else {
                cookies.removeAllCookie();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            WebStorage.getInstance().deleteAllData();
        } catch (RuntimeException ignored) {
        }
        try {
            WebViewDatabase.getInstance(this).clearFormData();
        } catch (RuntimeException ignored) {
        }
        if (webView != null) {
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
            webView.loadUrl(BLANK_URL);
        }
        getPrefs().edit()
                .remove(KEY_TELEGRAM_TEXT)
                .remove(KEY_TELEGRAM_COUNT)
                .remove(KEY_TELEGRAM_SAVED_AT)
                .apply();
        resetExtracted();
        TelegramSessionLog.append('I', "logout: wiped");
    }

    private void keepTelegramSession() {
        if (webView != null) {
            webView.loadUrl(BLANK_URL);
        }
        resetExtracted();
        TelegramSessionLog.append('I', "logout: kept");
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        disposables.add(getTunnelServiceInteractor().tunnelStateFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::onTunnelState)
                .subscribe());
        refreshActiveServerLine();
        if (state == State.CONNECTED) {
            resetSpeedBaseline();
            startHeartbeat();
            startSpeed();
        }
        if (logVisible) {
            startLogRefresh();
        }
    }

    private void onTunnelState(@Nullable TunnelState state) {
        lastTunnelState = state;
        boolean connected = state != null && state.connectionData() != null
                && state.connectionData().isConnected();
        mainRunning = connected;
        mainSocksPort = state != null && state.connectionData() != null
                ? state.connectionData().socksPort() : 0;
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopHeartbeat();
        stopSpeed();
        mainHandler.removeCallbacks(logTask);
        disposables.clear();
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_SERVERS) {
            if (resultCode == RESULT_OK) {
                refreshActiveServerLine();
                TelegramSessionLog.append('I', "server selection updated");
                if (state == State.CONNECTED) {
                    Toast.makeText(this, R.string.telegram_servers_next_connection,
                            Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }
        if (requestCode == REQUEST_FILE_CHOOSER) {
            ValueCallback<Uri[]> callback = fileChooserCallback;
            fileChooserCallback = null;
            if (callback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                callback.onReceiveValue(results);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        generation++;
        resumed = false;
        mainHandler.removeCallbacksAndMessages(null);
        disposables.clear();
        clearProxy();
        closeLink();
        releaseTunnelControl();
        if (webView != null) {
            webView.removeJavascriptInterface(TelegramDownloadBridge.JS_NAME);
            ViewParent parent = webView.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
