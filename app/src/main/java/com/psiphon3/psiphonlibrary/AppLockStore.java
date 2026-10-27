package com.psiphon3.psiphonlibrary;

import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;

/**
 * Stores the app lock choice and reports what this phone can actually verify.
 * All reads are cheap: the preferences file is loaded once per process by the framework.
 */
public final class AppLockStore {

    public enum Mode { NONE, BIOMETRIC, DEVICE_CREDENTIAL, ANY }

    private static final String PREFS_NAME = "security_prefs";
    private static final String KEY_MODE = "app_lock_mode";

    private AppLockStore() {
    }

    public static Mode getMode(Context ctx) {
        if (ctx == null) {
            return Mode.NONE;
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int ordinal = prefs.getInt(KEY_MODE, Mode.NONE.ordinal());
        Mode[] modes = Mode.values();
        if (ordinal < 0 || ordinal >= modes.length) {
            return Mode.NONE;
        }
        return modes[ordinal];
    }

    public static void setMode(Context ctx, Mode mode) {
        if (ctx == null) {
            return;
        }
        Mode stored = mode == null ? Mode.NONE : mode;
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MODE, stored.ordinal())
                .apply();
    }

    public static boolean isDeviceSecure(Context ctx) {
        if (ctx == null) {
            return false;
        }
        KeyguardManager keyguard =
                (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return keyguard.isDeviceSecure();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Deprecated in favor of isDeviceSecure() on 23+, but it is the only
            // answer 21 and 22 can give.
            return keyguard.isKeyguardSecure();
        }
        return false;
    }

    public static boolean hasBiometric(Context ctx) {
        if (ctx == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // BiometricPrompt reports errors but has no "is enrolled" query before
            // BiometricManager arrived, so hardware plus a secure keyguard is the proxy.
            PackageManager pm = ctx.getPackageManager();
            if (pm == null) {
                return false;
            }
            boolean hardware = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // BiometricPrompt only gained face support in Android 10.
                hardware = hardware || pm.hasSystemFeature(PackageManager.FEATURE_FACE);
            }
            return hardware && isDeviceSecure(ctx);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return hasEnrolledFingerprint(ctx);
        }
        return false;
    }

    public static boolean supports(Context ctx, Mode mode) {
        if (mode == null || mode == Mode.NONE) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || !isDeviceSecure(ctx)) {
            return false;
        }
        if (mode == Mode.BIOMETRIC) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && hasBiometric(ctx);
        }
        return true;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private static boolean hasEnrolledFingerprint(Context ctx) {
        Object service = ctx.getSystemService(Context.FINGERPRINT_SERVICE);
        if (!(service instanceof FingerprintManager)) {
            return false;
        }
        FingerprintManager fingerprint = (FingerprintManager) service;
        try {
            return fingerprint.hasEnrolledFingerprints();
        } catch (RuntimeException e) {
            // A few vendor builds throw instead of returning false.
            return false;
        }
    }
}
