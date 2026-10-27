package com.psiphon3.psiphonlibrary;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import com.psiphon3.R;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asks the phone to verify the user with its own security method.
 *
 * <p>Every path delivers exactly one callback, always on the main thread. The caller
 * (the gate) keeps its lock screen up on cancel; this class never decides that.</p>
 *
 * <p>All dialogs read their text from security_strings.xml, and the API-specific bodies
 * live in nested classes so a Dalvik device (API 14-20) never has to resolve
 * BiometricPrompt or FingerprintManager.</p>
 */
public final class AppLockAuthenticator {

    public interface Callback {
        void onSuccess();

        void onCancel();

        void onUnavailable(String reason);
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Executor MAIN_EXECUTOR = MAIN_HANDLER::post;

    private AppLockAuthenticator() {
    }

    public static void authenticate(Activity activity, AppLockStore.Mode mode, Callback callback) {
        if (callback == null) {
            return;
        }
        Callback once = new OnceCallback(callback);
        if (activity == null || mode == null || mode == AppLockStore.Mode.NONE) {
            once.onUnavailable("no lock mode");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            once.onUnavailable("android too old");
            return;
        }
        if (mode == AppLockStore.Mode.DEVICE_CREDENTIAL) {
            Api21.confirmDeviceCredential(activity, once);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Api28.authenticate(activity, mode, once);
            return;
        }
        if (mode == AppLockStore.Mode.BIOMETRIC) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                once.onUnavailable("biometric needs android 6");
                return;
            }
            Api23.authenticateWithFingerprint(activity, once);
            return;
        }
        // ANY on 21-27: the keyguard asks for the device credential.
        Api21.confirmDeviceCredential(activity, once);
    }

    /** API 21-27: a system activity that returns through the host activity's onActivityResult. */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static final class Api21 {
        static void confirmDeviceCredential(Activity activity, Callback callback) {
            try {
                KeyguardManager keyguard =
                        (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
                Intent intent = keyguard == null ? null
                        : keyguard.createConfirmDeviceCredentialIntent(
                                activity.getString(R.string.app_lock_locked_title),
                                activity.getString(R.string.app_lock_locked_message));
                if (intent == null) {
                    callback.onUnavailable("no device credential");
                    return;
                }
                AppLockGate.expectResult(callback);
                activity.startActivityForResult(intent, AppLockGate.REQUEST_CODE);
            } catch (Exception e) {
                AppLockGate.cancelPendingResult(callback);
                callback.onUnavailable("no device credential");
            }
        }
    }

    /** API 23-27 biometric only: no BiometricPrompt existed yet, so use the fingerprint sensor. */
    @TargetApi(Build.VERSION_CODES.M)
    private static final class Api23 {
        static void authenticateWithFingerprint(Activity activity, final Callback callback) {
            Object service = activity.getSystemService(Context.FINGERPRINT_SERVICE);
            if (!(service instanceof FingerprintManager)) {
                callback.onUnavailable("no fingerprint hardware");
                return;
            }
            final FingerprintManager fingerprint = (FingerprintManager) service;
            boolean hardware;
            boolean enrolled;
            try {
                hardware = fingerprint.isHardwareDetected();
                enrolled = fingerprint.hasEnrolledFingerprints();
            } catch (RuntimeException e) {
                callback.onUnavailable("fingerprint unavailable");
                return;
            }
            if (!hardware) {
                callback.onUnavailable("no fingerprint hardware");
                return;
            }
            if (!enrolled) {
                callback.onUnavailable("no fingerprint enrolled");
                return;
            }
            fingerprint.authenticate(null, new CancellationSignal(), 0,
                    new FingerprintManager.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                FingerprintManager.AuthenticationResult result) {
                            callback.onSuccess();
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            if (errorCode == FingerprintManager.FINGERPRINT_ERROR_CANCELED
                                    || errorCode
                                    == FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED) {
                                callback.onCancel();
                            } else {
                                callback.onUnavailable("fingerprint error");
                            }
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            // One finger did not match; the sensor keeps listening.
                        }
                    }, MAIN_HANDLER);
        }
    }

    /** API 28+: BiometricPrompt is available, including its device-credential modes. */
    @TargetApi(Build.VERSION_CODES.P)
    private static final class Api28 {
        static void authenticate(Activity activity, AppLockStore.Mode mode, final Callback callback) {
            try {
                BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                        .setTitle(activity.getString(R.string.app_lock_locked_title))
                        .setSubtitle(activity.getString(R.string.app_lock_locked_message));

                if (mode == AppLockStore.Mode.BIOMETRIC) {
                    builder.setNegativeButton(activity.getString(android.R.string.cancel), MAIN_EXECUTOR,
                            (dialog, which) -> callback.onCancel());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        builder.setAllowedAuthenticators(
                                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                                        | android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK);
                    }
                } else if (mode == AppLockStore.Mode.ANY) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        builder.setAllowedAuthenticators(
                                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                                        | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                    } else {
                        builder.setDeviceCredentialAllowed(true);
                    }
                } else {
                    Api21.confirmDeviceCredential(activity, callback);
                    return;
                }

                BiometricPrompt prompt = builder.build();
                prompt.authenticate(new CancellationSignal(), MAIN_EXECUTOR, new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        callback.onSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                || errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                            callback.onCancel();
                        } else if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE
                                || errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS
                                || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
                                || errorCode
                                == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) {
                            Api21.confirmDeviceCredential(activity, callback);
                        } else {
                            callback.onCancel();
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                    }
                });
            } catch (Exception e) {
                Api21.confirmDeviceCredential(activity, callback);
            }
        }
    }

    /** API 30+: BiometricPrompt names the exact methods it may use. */
    @TargetApi(Build.VERSION_CODES.R)
    private static final class Api30 {
        static void setAllowedAuthenticators(BiometricPrompt.Builder builder,
                AppLockStore.Mode mode) {
            builder.setAllowedAuthenticators(authenticators(mode));
        }

        private static int authenticators(AppLockStore.Mode mode) {
            if (mode == AppLockStore.Mode.DEVICE_CREDENTIAL) {
                return android.hardware.biometrics.BiometricManager.Authenticators
                        .DEVICE_CREDENTIAL;
            }
            if (mode == AppLockStore.Mode.ANY) {
                return android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | android.hardware.biometrics.BiometricManager.Authenticators
                        .DEVICE_CREDENTIAL;
            }
            return android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK;
        }
    }

    /** Makes sure a chatty system callback cannot unlock twice or report two outcomes. */
    private static final class OnceCallback implements Callback {
        private final Callback delegate;
        private final AtomicBoolean delivered = new AtomicBoolean(false);

        OnceCallback(Callback delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onSuccess() {
            deliver(delegate::onSuccess);
        }

        @Override
        public void onCancel() {
            deliver(delegate::onCancel);
        }

        @Override
        public void onUnavailable(String reason) {
            deliver(() -> delegate.onUnavailable(reason));
        }

        private void deliver(Runnable action) {
            if (!delivered.compareAndSet(false, true)) {
                return;
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action.run();
            } else {
                MAIN_HANDLER.post(action);
            }
        }
    }
}
