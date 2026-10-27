// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.

package com.psiphon3.psiphonlibrary;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;

import com.psiphon3.MainActivityViewModel;
import com.psiphon3.R;

import java.util.List;

import app.slipnet.util.AppLog;
import app.whitedns.engine.WhiteDnsTester;
import app.whitedns.model.WhiteDnsProfile;
import app.whitedns.store.WhiteDnsStore;
import app.whitedns.util.WhiteDnsException;
import app.whitedns.util.WhiteDnsProfileParser;

public class SlipNetTestActivity extends LocalizedActivities.AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, new SlipNetPreferenceFragment())
                    .commit();
        }

        MainActivityViewModel viewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(MainActivityViewModel.class);
        getLifecycle().addObserver(viewModel);
    }

    public static class SlipNetPreferenceFragment extends PsiphonPreferenceFragmentCompat {
        private static final String TAG = "WhiteDnsTest";

        private static final String KEY_PASTE = "slipnet_paste_link";
        private static final String KEY_MODE = "slipnet_edit";
        private static final String KEY_RESOLVERS = "slipnet_resolvers";
        private static final String KEY_SCANNER = "slipnet_scanner";
        private static final String KEY_TEST = "slipnet_run_test";
        private static final String KEY_STATUS = "slipnet_status";
        private static final String KEY_LOGS = "slipnet_logs";
        private static final String KEY_CLEAR = "slipnet_clear_link";
        private static final String SOAR_LABEL = "SOAR";
        private static final String STORM_LABEL = "StormDNS";
        private static final String SOAR_NEEDS_LINK =
                "The SOAR engine needs a soar:// link";
        private static final String COTTEN_NEEDS_LINK =
                "This SOAR profile needs a CottenDNS link before Normal or CottenMax";

        private Preference statusPreference;
        private Preference pastePreference;
        private Preference modePreference;
        private Preference resolversPreference;
        private Preference scannerPreference;
        private Preference testPreference;
        private Preference logsPreference;
        private Preference clearPreference;

        private WhiteDnsProfile profile;
        private boolean testing;
        private ProgressDialog progressDialog;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            super.onCreatePreferences(savedInstanceState, rootKey);
            addPreferencesFromResource(R.xml.slipnet_preferences);

            statusPreference = findPreference(KEY_STATUS);
            pastePreference = findPreference(KEY_PASTE);
            modePreference = findPreference(KEY_MODE);
            resolversPreference = findPreference(KEY_RESOLVERS);
            scannerPreference = findPreference(KEY_SCANNER);
            testPreference = findPreference(KEY_TEST);
            logsPreference = findPreference(KEY_LOGS);
            clearPreference = findPreference(KEY_CLEAR);

            pastePreference.setOnPreferenceClickListener(p -> {
                showPasteDialog();
                return true;
            });
            modePreference.setOnPreferenceClickListener(p -> {
                showModeDialog();
                return true;
            });
            resolversPreference.setOnPreferenceClickListener(p -> {
                showResolversDialog();
                return true;
            });
            scannerPreference.setOnPreferenceClickListener(p -> {
                openScanner();
                return true;
            });
            testPreference.setOnPreferenceClickListener(p -> {
                startTest();
                return true;
            });
            logsPreference.setOnPreferenceClickListener(p -> {
                openLogs();
                return true;
            });
            clearPreference.setOnPreferenceClickListener(p -> {
                confirmClear();
                return true;
            });
        }

        @Override
        public void onResume() {
            super.onResume();
            refreshProfile();
        }

        @Override
        public void onDestroy() {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            progressDialog = null;
            super.onDestroy();
        }

        /** Reads the saved profile and shows it without ever revealing the encryption key. */
        private void refreshProfile() {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            WhiteDnsProfile saved = WhiteDnsStore.load(activity.getApplicationContext());
            profile = saved;
            if (saved == null) {
                statusPreference.setSummary(R.string.slipnet_profile_empty);
            } else {
                String label = saved.name.length() == 0 ? saved.domain : saved.name;
                int resolvers = WhiteDnsProfileParser.parseResolvers(saved.resolvers).size();
                statusPreference.setSummary(getString(R.string.slipnet_profile_summary,
                        label, modeLabel(saved), resolvers));
            }
            boolean ready = saved != null && !testing;
            modePreference.setEnabled(ready);
            resolversPreference.setEnabled(ready);
            testPreference.setEnabled(ready);
            clearPreference.setEnabled(ready);
        }

        private String modeName(WhiteDnsProfile.Mode mode) {
            return mode == WhiteDnsProfile.Mode.COTTENMAX
                    ? getString(R.string.slipnet_mode_cottenmax)
                    : getString(R.string.slipnet_mode_normal);
        }

        /** The row shows the engine for a SOAR or StormDNS profile; its mode plays no part there. */
        private String modeLabel(WhiteDnsProfile saved) {
            if (saved.engine == WhiteDnsProfile.Engine.SOAR) {
                return SOAR_LABEL;
            }
            if (saved.engine == WhiteDnsProfile.Engine.STORM) {
                return STORM_LABEL;
            }
            return modeName(saved.mode);
        }

        private void showPasteDialog() {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }

            final EditText input = multilineField(activity, 6);
            CharSequence clipboardText = readClipboardText(activity);
            if (clipboardText != null) {
                input.setText(clipboardText);
                input.setSelection(input.length());
            }

            new AlertDialog.Builder(activity)
                    .setTitle(R.string.slipnet_paste_dialog_title)
                    .setView(withPadding(activity, input))
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> savePasted(input.getText().toString()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void savePasted(final String rawText) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            final String text = rawText == null ? "" : rawText.trim();
            if (text.length() == 0) {
                return;
            }
            final Context appContext = activity.getApplicationContext();
            new Thread(() -> {
                try {
                    WhiteDnsProfile parsed = WhiteDnsProfileParser.parse(text);
                    WhiteDnsStore.save(appContext, parsed);
                    final String label = parsed.name.length() == 0 ? parsed.domain : parsed.name;
                    runOnUiThreadIfAlive(() -> {
                        toast(getString(R.string.slipnet_paste_summary_saved, label));
                        refreshProfile();
                    });
                } catch (WhiteDnsException e) {
                    final String message = messageOf(e);
                    runOnUiThreadIfAlive(() -> showMessage(R.string.slipnet_fail_parse, message));
                } catch (RuntimeException | LinkageError e) {
                    runOnUiThreadIfAlive(() -> showMessage(R.string.slipnet_fail_parse, null));
                }
            }, "WhiteDnsParse").start();
        }

        private void showModeDialog() {
            Activity activity = getActivity();
            if (activity == null || profile == null) {
                return;
            }
            final WhiteDnsProfile saved = profile;
            final String[] modes = {
                    getString(R.string.slipnet_mode_normal),
                    getString(R.string.slipnet_mode_cottenmax),
                    SOAR_LABEL
            };
            int checked = saved.engine == WhiteDnsProfile.Engine.SOAR
                    || saved.engine == WhiteDnsProfile.Engine.STORM ? 2
                    : saved.mode == WhiteDnsProfile.Mode.COTTENMAX ? 1 : 0;
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.slipnet_mode_title)
                    .setSingleChoiceItems(modes, checked,
                            (dialog, which) -> {
                                dialog.dismiss();
                                chooseMode(saved, which);
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        /**
         * An engine is fixed by the link it came from, so a mismatched pick is refused with a
         * short message instead of rewriting the saved profile.
         */
        private void chooseMode(final WhiteDnsProfile saved, int which) {
            boolean soar = saved.engine == WhiteDnsProfile.Engine.SOAR;
            boolean other = saved.engine != WhiteDnsProfile.Engine.COTTEN;
            if (which == 2 && !other) {
                showMessage(R.string.slipnet_mode_title, SOAR_NEEDS_LINK);
                return;
            }
            if (which < 2 && other) {
                showMessage(R.string.slipnet_mode_title, COTTEN_NEEDS_LINK);
                return;
            }
            if (which == 2) {
                toast(getString(R.string.slipnet_mode_saved, soar ? SOAR_LABEL : STORM_LABEL));
                return;
            }
            saveMode(saved, which == 1
                    ? WhiteDnsProfile.Mode.COTTENMAX
                    : WhiteDnsProfile.Mode.NORMAL);
        }

        private void saveMode(final WhiteDnsProfile saved, final WhiteDnsProfile.Mode mode) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            WhiteDnsStore.save(activity.getApplicationContext(),
                    new WhiteDnsProfile(saved.name, saved.domain, saved.key, mode,
                            saved.resolvers, saved.method, saved.engine, saved.rawConfig));
            toast(getString(R.string.slipnet_mode_saved, modeName(mode)));
            refreshProfile();
        }

        private void showResolversDialog() {
            Activity activity = getActivity();
            if (activity == null || profile == null) {
                return;
            }
            final WhiteDnsProfile saved = profile;
            final EditText input = multilineField(activity, 5);
            input.setText(saved.resolvers);
            input.setSelection(input.length());

            new AlertDialog.Builder(activity)
                    .setTitle(R.string.slipnet_resolvers_dialog_title)
                    .setView(withPadding(activity, input))
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> saveResolvers(saved, input.getText().toString()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void saveResolvers(final WhiteDnsProfile saved, final String rawText) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            final String text = rawText == null ? "" : rawText.trim();
            List<String> resolvers = WhiteDnsProfileParser.parseResolvers(text);
            if (resolvers.isEmpty() && text.length() > 0) {
                toast(getString(R.string.slipnet_resolvers_invalid));
                return;
            }
            WhiteDnsStore.save(activity.getApplicationContext(), saved.withResolvers(text));
            toast(getString(R.string.slipnet_resolvers_saved, resolvers.size()));
            refreshProfile();
        }

        private void openScanner() {
            Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            startActivity(new Intent(activity, SlipNetScannerActivity.class));
        }

        private void openLogs() {
            Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            startActivity(new Intent(activity, SlipNetLogActivity.class));
        }

        private void startTest() {
            Activity activity = getActivity();
            if (activity == null || testing || profile == null) {
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                toast(getString(R.string.slipnet_unsupported_abi));
                return;
            }
            final WhiteDnsProfile saved = profile;
            final Context appContext = activity.getApplicationContext();

            testing = true;
            progressDialog = new ProgressDialog(activity);
            progressDialog.setMessage(getString(R.string.slipnet_test_progress));
            progressDialog.setCancelable(false);
            progressDialog.show();
            refreshProfile();

            new Thread(() -> {
                long startedAt = System.currentTimeMillis();
                String failure;
                try {
                    failure = WhiteDnsTester.test(appContext, saved);
                } catch (RuntimeException | LinkageError e) {
                    failure = messageOf(e);
                }
                final long elapsedMs = System.currentTimeMillis() - startedAt;
                final String error = failure;
                runOnUiThreadIfAlive(() -> finishTest(saved, error, elapsedMs));
            }, "WhiteDnsTest").start();
        }

        private void finishTest(final WhiteDnsProfile saved, @Nullable final String error,
                long elapsedMs) {
            testing = false;
            if (progressDialog != null) {
                progressDialog.dismiss();
                progressDialog = null;
            }
            refreshProfile();

            long seconds = Math.max(1L, (elapsedMs + 999L) / 1000L);
            if (error == null) {
                AppLog.i(TAG, "test passed: domain " + saved.domain + ", mode "
                        + saved.mode.name() + ", " + seconds + "s");
                showMessage(R.string.slipnet_test_result_title,
                        getString(R.string.slipnet_success, seconds));
            } else {
                AppLog.w(TAG, "test failed: domain " + saved.domain + ", mode "
                        + saved.mode.name() + ", error " + error);
                showMessage(R.string.slipnet_fail_unknown, error);
            }
        }

        private void confirmClear() {
            Activity activity = getActivity();
            if (activity == null || profile == null) {
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.slipnet_clear_title)
                    .setMessage(R.string.slipnet_clear_summary)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> clearProfile())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void clearProfile() {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            WhiteDnsStore.clear(activity.getApplicationContext());
            refreshProfile();
        }

        private void showMessage(int titleRes, @Nullable final String message) {
            Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(titleRes)
                    .setMessage(message == null ? "" : message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }

        private void toast(CharSequence text) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        }

        private static EditText multilineField(Activity activity, int minLines) {
            EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setMinLines(minLines);
            input.setGravity(Gravity.TOP | Gravity.START);
            return input;
        }

        @Nullable
        private static CharSequence readClipboardText(Activity activity) {
            try {
                ClipboardManager clipboardManager =
                        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
                    return null;
                }
                ClipData clipData = clipboardManager.getPrimaryClip();
                if (clipData == null || clipData.getItemCount() == 0) {
                    return null;
                }
                return clipData.getItemAt(0).coerceToText(activity);
            } catch (RuntimeException e) {
                // Clipboard reads can be refused when the app has no window focus.
                return null;
            }
        }

        private static String messageOf(Throwable e) {
            String message = e.getMessage();
            return message == null || message.isEmpty() ? e.toString() : message;
        }

        private static View withPadding(Activity activity, View content) {
            FrameLayout container = new FrameLayout(activity);
            int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
            container.setPadding(padding, padding, padding, padding);
            container.addView(content);
            return container;
        }

        private void runOnUiThreadIfAlive(final Runnable action) {
            Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            activity.runOnUiThread(() -> {
                Activity current = getActivity();
                if (current == null || current.isFinishing()) {
                    return;
                }
                action.run();
            });
        }
    }
}
