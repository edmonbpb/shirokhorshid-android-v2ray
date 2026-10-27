package com.psiphon3.psiphonlibrary;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.psiphon3.R;

import java.lang.ref.WeakReference;

/**
 * Shows the app lock over whichever activity is in front and keeps it there until the
 * phone's own security check succeeds.
 *
 * <p>The host activity forwards onResume, onPause and the device-credential result;
 * see the contract's wiring wave. Everything here runs on the main thread.</p>
 *
 * <p>The state is static on purpose: rotating the phone destroys the activity but not
 * the process, and the lock must outlive that. The only thing held here is a weak
 * reference to the current activity, so a finished screen can still be collected.</p>
 */
public final class AppLockGate {

    /** Request code for the system credential screen started by the authenticator. */
    public static final int REQUEST_CODE = 4104;

    private static final int SCRIM_COLOR = 0xF2000000;
    private static final Object OVERLAY_TAG = new Object();

    private static boolean locked = true;
    private static long backgroundAt;
    private static boolean promptActive;
    private static int resumedActivities = 0;
    private static final Handler S_HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable S_LOCK_RUNNABLE = () -> {
        if (resumedActivities <= 0) {
            locked = true;
        }
    };
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static WeakReference<View> overlayView = new WeakReference<>(null);
    /** The not-yet-answered device-credential intent (API 21-27), if one is up. */
    private static AppLockAuthenticator.Callback pendingCallback;

    private AppLockGate() {
    }

    public static void arm() {
        locked = true;
        backgroundAt = 0L;
    }

    public static void onActivityResumed(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        resumedActivities++;
        S_HANDLER.removeCallbacks(S_LOCK_RUNNABLE);

        Activity previous = currentActivity.get();
        currentActivity = new WeakReference<>(activity);

        AppLockStore.Mode mode = AppLockStore.getMode(activity);
        if (mode == AppLockStore.Mode.NONE) {
            reset();
            return;
        }

        if (!AppLockStore.supports(activity, mode)) {
            // The saved check can no longer run (the fingerprint was removed, for
            // example). Unlock rather than trap the user behind an impossible step.
            reset();
            return;
        }

        if (promptActive) {
            // Our own authentication window is on top; its callback decides.
            return;
        }

        if (locked) {
            showOverlay(activity);
            authenticate(activity, mode);
            return;
        }

        removeTaggedOverlay(activity);
    }

    public static void onActivityPaused(Activity activity) {
        if (activity == null) {
            return;
        }
        if (promptActive) {
            // The pause was caused by our own authentication window.
            return;
        }
        if (activity.isChangingConfigurations()) {
            // Rotation is not a trip to the background.
            return;
        }
        resumedActivities--;
        if (resumedActivities <= 0) {
            resumedActivities = 0;
            backgroundAt = SystemClock.elapsedRealtime();
            S_HANDLER.postDelayed(S_LOCK_RUNNABLE, 1000L);
        }
    }

    /** The device-credential intent result, forwarded by the host activity. */
    public static void onAuthenticationResult(boolean success) {
        AppLockAuthenticator.Callback callback = pendingCallback;
        pendingCallback = null;
        if (callback == null) {
            return;
        }
        if (success) {
            callback.onSuccess();
        } else {
            callback.onCancel();
        }
    }

    public static void reset() {
        removeOverlay();
        locked = false;
        backgroundAt = 0L;
        promptActive = false;
        pendingCallback = null;
        currentActivity = new WeakReference<>(null);
    }

    /** Used by the authenticator before starting the device-credential intent. */
    static void expectResult(AppLockAuthenticator.Callback callback) {
        pendingCallback = callback;
    }

    static void cancelPendingResult(AppLockAuthenticator.Callback callback) {
        if (pendingCallback == callback) {
            pendingCallback = null;
        }
    }

    private static void authenticate(Activity activity, AppLockStore.Mode mode) {
        promptActive = true;
        AppLockAuthenticator.authenticate(activity, mode, new GateCallback());
    }

    private static void retry() {
        Activity activity = currentActivity.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        AppLockStore.Mode mode = AppLockStore.getMode(activity);
        if (mode == AppLockStore.Mode.NONE) {
            reset();
            return;
        }
        if (promptActive || !AppLockStore.supports(activity, mode)) {
            return;
        }
        locked = true;
        authenticate(activity, mode);
    }

    private static void showOverlay(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        // One lock screen per activity, even if showOverlay is called again.
        removeTaggedOverlay(activity);

        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(OVERLAY_TAG);
        overlay.setBackgroundColor(SCRIM_COLOR);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        overlay.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                activity.finishAffinity();
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_BACK;
        });

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = dp(activity, 24);
        column.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(activity);
        title.setText(R.string.app_lock_locked_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setGravity(Gravity.CENTER);
        column.addView(title);

        TextView message = new TextView(activity);
        message.setText(R.string.app_lock_locked_message);
        message.setTextColor(0xFFBBBBBB);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = wrapContent();
        messageParams.topMargin = dp(activity, 12);
        column.addView(message, messageParams);

        Button unlock = new Button(activity);
        unlock.setText(R.string.app_lock_unlock);
        unlock.setOnClickListener(view -> retry());
        LinearLayout.LayoutParams unlockParams = wrapContent();
        unlockParams.topMargin = dp(activity, 24);
        column.addView(unlock, unlockParams);

        if (!AppLockStore.isDeviceSecure(activity)) {
            Button settings = new Button(activity);
            settings.setText(R.string.app_lock_open_settings);
            settings.setOnClickListener(view -> openSecuritySettings(activity));
            LinearLayout.LayoutParams settingsParams = wrapContent();
            settingsParams.topMargin = dp(activity, 8);
            column.addView(settings, settingsParams);
        }

        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        columnParams.gravity = Gravity.CENTER;
        overlay.addView(column, columnParams);

        content.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        overlayView = new WeakReference<>(overlay);
        // Back must not reach the screen below; the key listener above eats it.
        overlay.post(overlay::requestFocus);
    }

    private static void openSecuritySettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            // Some builds have no security settings entry; the lock screen stays.
        }
    }

    private static View overlayFor(Activity activity) {
        View overlay = overlayView.get();
        if (overlay == null) {
            return null;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return null;
        }
        return overlay.getParent() == content ? overlay : null;
    }

    private static void removeOverlay() {
        View overlay = overlayView.get();
        overlayView = new WeakReference<>(null);
        ViewParent parent = overlay == null ? null : overlay.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(overlay);
        }
        Activity current = currentActivity.get();
        if (current != null) {
            removeTaggedOverlay(current);
        }
    }

    private static void removeTaggedOverlay(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            View child = content.getChildAt(i);
            if (child.getTag() == OVERLAY_TAG) {
                content.removeViewAt(i);
            }
        }
    }

    private static LinearLayout.LayoutParams wrapContent() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** On cancel: the screen stays locked and its unlock button is the only way back in. */
    private static void keepOverlay() {
        if (!locked) {
            return;
        }
        Activity activity = currentActivity.get();
        if (activity == null || activity.isFinishing() || overlayFor(activity) != null) {
            return;
        }
        showOverlay(activity);
    }

    private static final class GateCallback implements AppLockAuthenticator.Callback {
        private boolean delivered;

        @Override
        public void onSuccess() {
            if (delivered) {
                return;
            }
            delivered = true;
            promptActive = false;
            locked = false;
            removeOverlay();
            pendingCallback = null;
            // A result can arrive before onResume (the credential activity on API 21-27
            // returns through onActivityResult). Recording the unlock time instead of 0
            // keeps that resume from treating the moment as a cold start and locking
            // the user out a second time. Zero stays the "never been in the foreground
            // since the process started" marker.
            backgroundAt = SystemClock.elapsedRealtime();
        }

        @Override
        public void onCancel() {
            if (delivered) {
                return;
            }
            delivered = true;
            promptActive = false;
            keepOverlay();
        }

        @Override
        public void onUnavailable(String reason) {
            if (delivered) {
                return;
            }
            delivered = true;
            promptActive = false;
            // Keep the lock screen: the phone's security check exists but refused to run.
            keepOverlay();
        }
    }
}
