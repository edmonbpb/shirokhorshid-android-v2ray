// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.

package com.psiphon3.psiphonlibrary;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.psiphon3.R;

import app.slipnet.util.AppLog;

public class SlipNetLogActivity extends LocalizedActivities.AppCompatActivity {
    private static final long REFRESH_INTERVAL_MS = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private TextView titleView;
    private TextView logView;
    private ScrollView scrollView;
    private int lastCount = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.slipnet_log);

        titleView = findViewById(R.id.slipnet_log_title);
        logView = findViewById(R.id.slipnet_log_text);
        scrollView = findViewById(R.id.slipnet_log_scroll);

        findViewById(R.id.slipnet_log_copy).setOnClickListener(v -> copyLog());
        findViewById(R.id.slipnet_log_clear).setOnClickListener(v -> {
            AppLog.clear();
            refresh();
        });
        findViewById(R.id.slipnet_log_close).setOnClickListener(v -> finish());

        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(refreshTask);
        handler.postDelayed(refreshTask, REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refreshTask);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void refresh() {
        String snapshot = AppLog.snapshot();
        if (snapshot == null) {
            snapshot = "";
        }
        String shown = snapshot.isEmpty() ? getString(R.string.slipnet_logs_empty) : snapshot;
        if (!shown.contentEquals(logView.getText())) {
            logView.setText(shown);
            scrollToBottom();
        }
        int count = AppLog.size();
        if (count != lastCount) {
            lastCount = count;
            titleView.setText(getString(R.string.slipnet_logs_count, count));
        }
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void copyLog() {
        String snapshot = AppLog.snapshot();
        if (snapshot == null) {
            snapshot = "";
        }
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.label_slipnet_logs), snapshot));
        }
    }
}
