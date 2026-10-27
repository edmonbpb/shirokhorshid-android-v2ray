// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.

package com.psiphon3.psiphonlibrary;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.psiphon3.R;

import app.slipnet.scanner.ScanManager;
import app.slipnet.scanner.ScanState;

/** Foreground host for ScanManager so a scan keeps running after the activity closes. */
public class SlipNetScanService extends Service implements ScanManager.Observer {

    public static final String ACTION_STOP =
            "com.psiphon3.psiphonlibrary.SlipNetScanService.STOP";

    private static final String CHANNEL_ID = "slipnet_scan";
    private static final int NOTIFICATION_ID = 1001;
    private static final long STOP_DELAY_MS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stopRunnable = new Runnable() {
        @Override
        public void run() {
            stopForeground(true);
            stopSelf();
        }
    };

    private NotificationManager notificationManager;
    private boolean observing;
    private boolean finishing;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.label_slipnet_scanner), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            finishing = true;
            ScanManager.get().stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(stopRunnable);
        finishing = false;
        ScanManager manager = ScanManager.get();
        manager.init(this);
        manager.addObserver(this);
        observing = true;
        ScanState state = manager.snapshot();
        startForeground(NOTIFICATION_ID, buildNotification(state));
        if (!state.running) {
            finishing = true;
            handler.postDelayed(stopRunnable, STOP_DELAY_MS);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onState(ScanState state) {
        if (state == null || finishing) {
            return;
        }
        updateNotification(state);
        if (!state.running) {
            finishing = true;
            handler.postDelayed(stopRunnable, STOP_DELAY_MS);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(stopRunnable);
        if (observing) {
            observing = false;
            ScanManager.get().removeObserver(this);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateNotification(ScanState state) {
        if (notificationManager == null) {
            return;
        }
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(state));
        } catch (RuntimeException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(ScanState state) {
        int total = Math.max(0, state.total);
        int done = Math.max(0, Math.min(state.done, total));
        String text = getString(R.string.slipnet_scan_progress, done, total);
        if (state.phase != null && state.phase.length() > 0) {
            text = text + " \u00B7 " + state.phase;
        }

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, SlipNetScannerActivity.class), pendingIntentFlags());
        Intent stopIntent = new Intent(this, SlipNetScanService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
                pendingIntentFlags());

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.baseline_device_hub_white_24)
                .setContentTitle(getString(R.string.label_slipnet_scanner))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(total, done, false);
        if (Build.VERSION.SDK_INT >= 16) {
            builder.setPriority(Notification.PRIORITY_LOW);
            builder.addAction(0, getString(R.string.slipnet_scan_stop), stopPendingIntent);
            return builder.build();
        }
        return builder.getNotification();
    }

    private static int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }
}
