# Contract: engine settings in More Options; remove engine rows from the main Options screen

Repo: `C:\Users\edmon\Desktop\shirokhorshid-src\shirokhorshid-android`

## Goal

1. More Options must show the settings entry for all three connection engines together,
   directly below the Advanced category (whose last row is Beast mode):
   WhiteDNS, Napster, V2Ray. Today only the WhiteDNS row exists there; Napster and
   V2Ray only open from the main Options tab, so when a user selects Napster or V2Ray
   in "Connection Protocol" no matching settings entry is visible.
2. Remove the V2Ray and Napster rows from the main Options screen
   (`settings_preferences_screen.xml`). WhiteDNS is already absent there, leave it absent.
   After this change the three engines are reachable only from More Options.

## Current state (verified 2026-10-28)

- `app/src/main/res/xml/settings_preferences_screen.xml` lines 16-23 contain:
  `<Preference android:key="@string/v2rayOptionsPreferenceKey" ... />` and
  `<Preference android:key="@string/napsterOptionsPreferenceKey" ... />`.
- `app/src/main/java/com/psiphon3/OptionsTabFragment.java`:
  - field `private Preference napsterOptionsPreference;` (line ~57)
  - V2Ray row handler starting at line ~120 (`V2RayPreferenceActivity`,
    `REQUEST_CODE_V2RAY_PREFERENCES`)
  - Napster row handler at line ~130 (`NapsterActivity`)
  - Napster summary block at lines ~269-278 (`NapsterConfigStore`)
  - constant `REQUEST_CODE_V2RAY_PREFERENCES` at line ~52; it is not handled in
    `onActivityResult`, only used by the V2Ray row handler.
- `app/src/main/res/xml/more_options_preferences.xml`: the Advanced category ends with the
  Beast mode switch (line ~76). After the CDN and Conduit categories there is a standalone
  `<Preference android:key="@string/slipnetTestPreferenceKey" android:title="@string/label_slipnet_test" android:summary="@string/whitedns_more_summary" />` (lines 127-131).
- `MoreOptionsPreferenceActivity.setupWhiteDns(...)` (line ~622) wires that row to
  `SlipNetTestActivity`.
- Existing key strings already present in
  `values/psiphon_android_library_untranslated_strings.xml`:
  `v2rayOptionsPreferenceKey`, `napsterOptionsPreferenceKey`, `slipnetTestPreferenceKey`.
  Reuse them; do not invent new key strings.
- Existing labels: `label_v2ray_settings`, `label_napster_settings`, `label_slipnet_test`
  (values and values-fa). Existing summary: `whitedns_more_summary`.
- The main-screen summary for Napster ("On: <name>") comes only from OptionsTabFragment.

## 1. Resources (owner: agent A)

Files: `app/src/main/res/xml/settings_preferences_screen.xml`,
`app/src/main/res/xml/more_options_preferences.xml`,
`app/src/main/res/values/psiphon_android_library_strings.xml`,
`app/src/main/res/values-fa/psiphon_android_library_strings.xml`.

1. Delete the V2Ray and Napster `<Preference>` blocks from `settings_preferences_screen.xml`.
   Do not touch any other row.
2. In `more_options_preferences.xml`, insert a new category immediately after the Advanced
   category (after the Beast mode switch, before `cdnFrontingCategory`):
   ```xml
   <PreferenceCategory
       android:key="connectionEnginesCategory"
       android:title="@string/preference_category_connection_engines_title">
       <Preference
           android:icon="@drawable/baseline_device_hub_white_24"
           android:key="@string/slipnetTestPreferenceKey"
           android:summary="@string/whitedns_more_summary"
           android:title="@string/label_slipnet_test" />
       <Preference
           android:icon="@drawable/baseline_device_hub_white_24"
           android:key="@string/napsterOptionsPreferenceKey"
           android:summary="@string/napster_more_summary"
           android:title="@string/label_napster_settings" />
       <Preference
           android:icon="@drawable/baseline_device_hub_white_24"
           android:key="@string/v2rayOptionsPreferenceKey"
           android:summary="@string/v2ray_more_summary"
           android:title="@string/label_v2ray_settings" />
   </PreferenceCategory>
   ```
   Remove the old standalone WhiteDNS `<Preference>` that is currently after the Conduit
   category, so the row exists only once.
3. Add three strings in English (`values/psiphon_android_library_strings.xml`):
   - `preference_category_connection_engines_title`: `Connection engines`
   - `napster_more_summary`: `Active Napster config and servers`
   - `v2ray_more_summary`: `Active V2Ray config and servers`
   And in Persian (`values-fa/psiphon_android_library_strings.xml`), with ZWNJ where needed:
   - `preference_category_connection_engines_title`: `موتورهای اتصال`
   - `napster_more_summary`: `کانفیگ فعال نپستر و سرورها`
   - `v2ray_more_summary`: `کانفیگ فعال وی‌تو‌ری و سرورها`

## 2. Java wiring and cleanup (owner: agent B)

Files: `app/src/main/java/com/psiphon3/OptionsTabFragment.java`,
`app/src/main/java/com/psiphon3/psiphonlibrary/MoreOptionsPreferenceActivity.java`.

1. `MoreOptionsPreferenceActivity`: extend `setupWhiteDns(...)` (rename is optional) so the
   Napster and V2Ray rows open their screens:
   - `napsterOptionsPreferenceKey` -> `startActivity(new Intent(activity, NapsterActivity.class))`
   - `v2rayOptionsPreferenceKey` -> `startActivity(new Intent(activity, V2RayPreferenceActivity.class))`
   Follow the existing null-check / `isFinishing()` pattern used for the WhiteDNS row.
   Add the two imports.
2. `OptionsTabFragment`: remove the V2Ray and Napster row handlers, the
   `napsterOptionsPreference` field, the Napster summary block in
   `setSummaryFromPreferences`, and the now-unused `REQUEST_CODE_V2RAY_PREFERENCES`
   constant. Remove imports that become unused only (`NapsterActivity`,
   `NapsterConfigStore`, `V2RayPreferenceActivity`). Leave every other row, handler and
   summary untouched.

## Constraints

- No behaviour change to the tunnel, the protocol list, or the engine screens themselves.
- Do not run Gradle, do not install anything, do not touch a device.
- Keep XML attribute style/order consistent with the surrounding file; no reformatting.
- Each agent edits only the files listed in its own section.
