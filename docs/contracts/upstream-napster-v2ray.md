# Contract: Napster and V2Ray as Connection Protocol entries

Repo: `C:\Users\edmon\Desktop\shirokhorshid-src\shirokhorshid-android`
Feature: the "Connection Protocol" list in More Options must offer Napster and V2Ray
in addition to auto / direct / cdn_fronting / conduit / whitedns, and selecting either
one must force the Xray core to run with that source and become the only way to the
servers (fail closed, never fall back to a direct connection).

Read this whole file before editing. All paths below are relative to the repo root.

## Current relevant state (verified 2026-10-27)

- `app/src/main/res/values/psiphon_android_library_untranslated_strings.xml`
  arrays `protocol_selection_entries` / `protocol_selection_values` hold 5 items:
  auto, direct, cdn_fronting, conduit, whitedns (labels via `@string/protocol_*`).
- `app/src/main/java/com/psiphon3/psiphonlibrary/MoreOptionsPreferenceActivity.java`
  `updateProtocolSelectionSummary(...)` (starts near line 285) has cases for conduit,
  cdn_fronting, direct, whitedns; default is auto.
- `V2RayManager.start(Context)` starts the Xray core with `Source.AUTO`, which reads the
  V2Ray/Napster feature toggles and throws when both are enabled. Explicit
  `Source.NAPSTER` / `Source.V2RAY` are only used by `acquireForTelegram(...)`.
  `getUpstreamProxyUrl()` returns `http://user:pass@127.0.0.1:<httpPort>` or null.
  `isRunning()`, `stop()` exist. Failure strings already exist:
  `R.string.napster_start_failed`, `R.string.v2ray_start_failed` (with `%1$s`).
- `TunnelManager.runTunnel` (around line 1172): reads
  `protocolSelectionPreference`; `whitedns` starts `WhiteDnsUpstream` and returns on
  failure; otherwise, if the V2Ray/Napster toggles are on, `V2RayManager.start(...)`
  and returns on failure.
- `TunnelManager.buildTunnelCoreConfig(Context, Config, boolean useUpstreamProxy,
  String tempTunnelName)` (near line 1860): for `whitedns` it injects
  `UpstreamProxyUrl` from `WhiteDnsUpstream.getProxyUrl()` and returns null when it is
  missing; otherwise the `useUpstreamProxy` branch uses `V2RayManager` / HTTP proxy.
  Around line 1987 `whitedns` is mapped to `direct` before the core protocol settings.
- Call sites of `buildTunnelCoreConfig`: `TunnelManager.getPsiphonConfig()` (line ~2178,
  main VPN), `FeedbackWorker` (line ~308), `UpgradeChecker` (line ~338, temp name
  "upgradechecker").

## 1. V2RayManager (owner: agent A) — file `app/src/main/java/com/psiphon3/psiphonlibrary/V2RayManager.java`

1. Refactor `public static void start(Context context)` so its body moves to a new
   `private static void startInternal(Context context, Source source)`; `start()` calls
   `startInternal(context, Source.AUTO)`.
2. Add `public static void startForProtocol(Context context, boolean napster)` that
   calls `startInternal(context, napster ? Source.NAPSTER : Source.V2RAY)`.
3. In `startInternal`, when the core is already running and `source != Source.AUTO`:
   - if the running source matches the request (`sourceMatchesLocked(napster)`), return
     normally (reuse the running core, no restart, do not change ownership flags);
   - otherwise throw `V2RayException("the other upstream method is already running")`.
   Do the same check after a shared `sReadyLatch` completes before declaring success.
   `Source.AUTO` keeps today's behaviour exactly (return when running).
   Do not change `acquireForTelegram`, `releaseForTelegram`, `stop`, or publishing.

## 2. Tunnel manager and its callers (owner: agent B)

Files: `TunnelManager.java`, `FeedbackWorker.java`, `UpgradeChecker.java`
(all under `app/src/main/java/com/psiphon3/psiphonlibrary/`).

1. `runTunnel` around line 1175: after the `whitedns` branch add an `else if` for
   `"napster".equals(selectedProtocol) || "v2ray".equals(selectedProtocol)`:
   ```java
   boolean napster = "napster".equals(selectedProtocol);
   try {
       V2RayManager.startForProtocol(getContext(), napster);
   } catch (V2RayManager.V2RayException e) {
       int message = napster ? R.string.napster_start_failed : R.string.v2ray_start_failed;
       MyLog.e(message, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
       return;
   }
   ```
   The existing toggle branch stays as the final `else`.
2. `buildTunnelCoreConfig`: add a new parameter `boolean enforceSelectedUpstream`
   between `useUpstreamProxy` and `tempTunnelName`. Guard the existing `whitedns`
   branch with `enforceSelectedUpstream &&`, and add a branch before
   `else if (useUpstreamProxy)`:
   ```java
   } else if (enforceSelectedUpstream &&
           ("napster".equals(tunnelConfig.protocolSelection)
                   || "v2ray".equals(tunnelConfig.protocolSelection))) {
       String coreProxyUrl = V2RayManager.getUpstreamProxyUrl();
       if (coreProxyUrl == null) {
           int message = "napster".equals(tunnelConfig.protocolSelection)
                   ? R.string.napster_start_failed : R.string.v2ray_start_failed;
           MyLog.e(message, MyLog.Sensitivity.NOT_SENSITIVE, "the Xray core is not running");
           return null;
       }
       json.put("UpstreamProxyUrl", coreProxyUrl);
   } else if (useUpstreamProxy) {
   ```
   Update the three call sites: `TunnelManager.getPsiphonConfig()` passes `true`;
   `FeedbackWorker` and `UpgradeChecker` pass `false`. Do not change any other
   argument.
3. Around line 1987 map all three upstream protocols to the core's `direct` set:
   ```java
   if ("whitedns".equals(protocolSelection) || "napster".equals(protocolSelection)
           || "v2ray".equals(protocolSelection)) {
       protocolSelection = "direct";
   }
   ```
   Update the surrounding comment to say the DNS/Xray engines are upstream proxies,
   so the core never receives those values.
   Do not change stop paths: `V2RayManager.stop()` is already called on tunnel stop.

## 3. Resources and summary (owner: agent C)

Files: `app/src/main/res/values/psiphon_android_library_untranslated_strings.xml`,
`app/src/main/res/values/psiphon_android_library_strings.xml`,
`app/src/main/res/values-fa/psiphon_android_library_strings.xml`,
`app/src/main/java/com/psiphon3/psiphonlibrary/MoreOptionsPreferenceActivity.java`.

1. Append to both arrays (entries and values must stay index-aligned):
   `<item>@string/protocol_napster</item>` + `<item>napster</item>`,
   `<item>@string/protocol_v2ray</item>` + `<item>v2ray</item>`.
2. New strings (values, English):
   - `protocol_napster`: `Napster (Xray core)`
   - `protocol_v2ray`: `V2Ray (Xray core)`
   - `protocolSelectionSummaryNapster`: `Reach the servers through the active Napster config`
   - `protocolSelectionSummaryV2ray`: `Reach the servers through the active V2Ray config`
   Persian (`values-fa`), with ZWNJ where needed:
   - `protocol_napster`: `نپستر (هستهٔ Xray)`
   - `protocol_v2ray`: `وی‌تو‌ری (هستهٔ Xray)`
   - `protocolSelectionSummaryNapster`: `رسیدن به سرورها از طریق کانفیگ فعال نپستر`
   - `protocolSelectionSummaryV2ray`: `رسیدن به سرورها از طریق کانفیگ فعال وی‌تو‌ری`
3. `updateProtocolSelectionSummary`: add `case "napster"` and `case "v2ray"` that set the
   matching summary; keep the existing default `auto`.

## Constraints

- No new dependencies, no layout changes, no changes to the in-app Telegram method list.
- Do not run Gradle, do not install anything, do not touch a device.
- Do not reformat unrelated code; keep the existing log/exception wording style.
- All agents edit only the files listed under their own section.
