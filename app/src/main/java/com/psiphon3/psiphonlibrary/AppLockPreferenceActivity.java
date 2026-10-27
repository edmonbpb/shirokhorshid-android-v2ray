package com.psiphon3.psiphonlibrary;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.psiphon3.R;

/**
 * Lets the user choose how the app lock checks them, and says plainly which choices this
 * phone can actually perform.
 *
 * <p>The choice is saved the moment a row is picked; the lock itself is asked for elsewhere
 * (see AppLockGate). Rows that cannot run on this phone are disabled with a short reason,
 * and the device credential rows stay off until the phone has a screen lock.</p>
 */
public class AppLockPreferenceActivity extends LocalizedActivities.AppCompatActivity {

    private RadioGroup modeGroup;
    private RadioButton noneRow;
    private RadioButton biometricRow;
    private RadioButton deviceRow;
    private RadioButton anyRow;
    private TextView biometricReason;
    private TextView deviceReason;
    private TextView anyReason;
    private TextView statusView;
    private TextView availableBody;
    private View settingsButton;
    /** Set while the code itself moves the radio, so that move is not saved as a user choice. */
    private boolean binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.app_lock_settings);
        setTitle(R.string.app_lock_title);

        modeGroup = findViewById(R.id.app_lock_mode_group);
        noneRow = findViewById(R.id.app_lock_mode_none);
        biometricRow = findViewById(R.id.app_lock_mode_biometric);
        deviceRow = findViewById(R.id.app_lock_mode_device);
        anyRow = findViewById(R.id.app_lock_mode_any);
        biometricReason = findViewById(R.id.app_lock_reason_biometric);
        deviceReason = findViewById(R.id.app_lock_reason_device);
        anyReason = findViewById(R.id.app_lock_reason_any);
        statusView = findViewById(R.id.app_lock_status);
        availableBody = findViewById(R.id.app_lock_available_body);
        settingsButton = findViewById(R.id.app_lock_open_settings);

        settingsButton.setOnClickListener(view -> openSecuritySettings());
        modeGroup.setOnCheckedChangeListener(this::onModeSelected);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The screen lock may have been set or removed while the user was in system settings.
        refresh();
    }

    /**
     * Re-reads what this phone supports and the stored choice. Safe to call at any time; the
     * radio move is not reported as a user selection.
     */
    private void refresh() {
        boolean secure = AppLockStore.isDeviceSecure(this);
        boolean biometric = AppLockStore.supports(this, AppLockStore.Mode.BIOMETRIC);
        boolean device = AppLockStore.supports(this, AppLockStore.Mode.DEVICE_CREDENTIAL);
        boolean any = AppLockStore.supports(this, AppLockStore.Mode.ANY);

        noneRow.setEnabled(true);
        applyRow(biometricRow, biometricReason, biometric,
                R.string.app_lock_unavailable_biometric);
        applyRow(deviceRow, deviceReason, device,
                R.string.app_lock_unavailable_device);
        applyRow(anyRow, anyReason, any,
                R.string.app_lock_unavailable_any);

        statusView.setText(R.string.app_lock_not_secure);
        statusView.setVisibility(secure ? View.GONE : View.VISIBLE);
        settingsButton.setVisibility(secure ? View.GONE : View.VISIBLE);

        availableBody.setText(describeAvailable(biometric, device, any));

        binding = true;
        modeGroup.check(radioId(AppLockStore.getMode(this)));
        binding = false;
    }

    private static void applyRow(RadioButton row, TextView reason, boolean supported,
            int reasonText) {
        row.setEnabled(supported);
        if (supported) {
            reason.setVisibility(View.GONE);
        } else {
            reason.setText(reasonText);
            reason.setVisibility(View.VISIBLE);
        }
    }

    /** One line per choice this phone can run, in the same order as the radio rows. */
    private CharSequence describeAvailable(boolean biometric, boolean device, boolean any) {
        StringBuilder text = new StringBuilder(getString(R.string.app_lock_none));
        if (biometric) {
            text.append('\n').append(getString(R.string.app_lock_biometric));
        }
        if (device) {
            text.append('\n').append(getString(R.string.app_lock_device));
        }
        if (any) {
            text.append('\n').append(getString(R.string.app_lock_any));
        }
        return text;
    }

    private void onModeSelected(RadioGroup group, int checkedId) {
        if (binding) {
            return;
        }
        AppLockStore.Mode mode = modeOf(checkedId);
        if (mode == null) {
            return;
        }
        if (!AppLockStore.supports(this, mode)) {
            // The phone changed under an open screen (a fingerprint was removed, for example).
            // Show the stored choice again instead of saving something that cannot run.
            refresh();
            return;
        }
        AppLockStore.setMode(this, mode);
        if (mode != AppLockStore.Mode.NONE) {
            AppLockGate.arm();
        } else {
            AppLockGate.reset();
        }
        Toast.makeText(this, R.string.app_lock_saved, Toast.LENGTH_SHORT).show();
    }

    private void openSecuritySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            // Some vendor builds have no security settings entry; the note above stays.
        }
    }

    private static AppLockStore.Mode modeOf(int radioId) {
        if (radioId == R.id.app_lock_mode_biometric) {
            return AppLockStore.Mode.BIOMETRIC;
        }
        if (radioId == R.id.app_lock_mode_device) {
            return AppLockStore.Mode.DEVICE_CREDENTIAL;
        }
        if (radioId == R.id.app_lock_mode_any) {
            return AppLockStore.Mode.ANY;
        }
        if (radioId == R.id.app_lock_mode_none) {
            return AppLockStore.Mode.NONE;
        }
        return null;
    }

    private static int radioId(AppLockStore.Mode mode) {
        if (mode == AppLockStore.Mode.BIOMETRIC) {
            return R.id.app_lock_mode_biometric;
        }
        if (mode == AppLockStore.Mode.DEVICE_CREDENTIAL) {
            return R.id.app_lock_mode_device;
        }
        if (mode == AppLockStore.Mode.ANY) {
            return R.id.app_lock_mode_any;
        }
        return R.id.app_lock_mode_none;
    }
}
