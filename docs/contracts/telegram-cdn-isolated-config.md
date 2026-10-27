# Contract: a private CDN config copy for the in-app Telegram tunnel

Repo: `C:\Users\edmon\Desktop\shirokhorshid-src\shirokhorshid-android`
Branch: `feature/dns-xray-upstreams`

## Goal

The standalone Telegram CDN tunnel must stop using `TunnelManager.buildTunnelCoreConfig`
and the `TunnelManager.Config` class. It gets its own copy of the CDN fronting config
code (`TelegramCdnConfig`) so that any later CDN change made for Telegram can never
affect the main app's CDN fronting connection. `TunnelManager`, its CDN constants, its
protocol behaviour and the main app's CDN path must not change in this dispatch.

## Hard rule

- Do NOT edit `TunnelManager.java` (nor any other file outside this list). The main
  app's CDN connection must remain byte-for-byte unchanged.
- Do not call `TunnelManager.buildTunnelCoreConfig`, `TunnelManager.Config`,
  `TunnelManager.putCdnFrontingConfig`, or any other `TunnelManager` helper from the new
  class. Copy the needed code instead.

## 1. New file: `app/src/main/java/com/psiphon3/psiphonlibrary/TelegramCdnConfig.java`

`public final class TelegramCdnConfig` with:
```java
public static String build(Context context)   // returns the core config JSON, or null
```

It builds the JSON entirely by itself:

1. Read these user settings read-only through `net.grandcentrix.tray.AppPreferences`
   (same keys the app already uses):
   - `R.string.cdnFrontingCustomIpListPreference` (default "")
   - `R.string.cdnFrontingCustomSniPreference` (default "")
   - `R.string.beastModePreference` (default true)
   - `R.string.disableTimeoutsPreference` (default false)
2. Copy these private static helpers **verbatim** from
   `TunnelManager.java` lines ~1543-1860 (keep the same logic; make them private static
   in the new class; do not "improve" them):
   - `jsonArray(String...)`
   - `makeCdnFrontingOverride`, `makeEdgeCdnFrontingOverride`, `putEdgeCdnFrontingOverride`
   - `parseCdnFrontingCustomIpCandidates`, `parseCdnFrontingCustomSniList`,
     `normalizeCdnFrontingCustomSni`
   - `makeStringArray`, `makeCdnFrontingScanSpec`, `makeCdnFrontingDialOverrides`,
     `putCdnFrontingConfig` (rename to `putCdnFrontingConfig` with the same body; it takes
     the custom IP list and SNI instead of `TunnelManager.Config`)
   - a private copy of the protocol array:
     ```java
     private static final String[] CDN_FRONTING_TUNNEL_PROTOCOLS = {
             "FRONTED-MEEK-CDN-OSSH",
             "FRONTED-MEEK-CDN-HTTP-OSSH",
             "FRONTED-MEEK-CDN-QUIC-OSSH",
     };
     ```
3. Core JSON fields (same literal values the shared builder uses, nothing VPN/upgrade/
   feedback/LAN-sharing related):
   - `ClientVersion` = `EmbeddedValues.CLIENT_VERSION`
   - `PropagationChannelId` = `EmbeddedValues.PROPAGATION_CHANNEL_ID`
   - `SponsorId` = `EmbeddedValues.SPONSOR_ID`
   - `RemoteServerListURLs` = `new JSONArray(EmbeddedValues.REMOTE_SERVER_LIST_URLS_JSON)`
   - `ObfuscatedServerListRootURLs` = `new JSONArray(EmbeddedValues.OBFUSCATED_SERVER_LIST_ROOT_URLS_JSON)`
   - `RemoteServerListSignaturePublicKey` = `EmbeddedValues.REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY`
   - `ServerEntrySignaturePublicKey` = `EmbeddedValues.SERVER_ENTRY_SIGNATURE_PUBLIC_KEY`
   - `ExchangeObfuscationKey` = `EmbeddedValues.SERVER_ENTRY_EXCHANGE_OBFUSCATION_KEY`
   - `AdditionalParameters` = `EmbeddedValues.ADDITIONAL_PARAMETERS`
   - `EmitDiagnosticNotices` = true, `EmitDiagnosticNetworkParameters` = true,
     `EmitBytesTransferred` = true
   - `DNSResolverAlternateServers` = `new JSONArray("[\"1.1.1.1\", \"1.0.0.1\", \"8.8.8.8\", \"8.8.4.4\"]")`
   - `DataRootDirectory` and `MigrateDataStoreDirectory` = a dedicated directory
     `new File(context.getFilesDir(), "telegram_cdn")`, created with `mkdirs()`; return
     null if it cannot be created.
   - `LimitTunnelProtocols` = `makeJsonArray(CDN_FRONTING_TUNNEL_PROTOCOLS)` and
     `DisableTactics` = true.
   - `putCdnFrontingConfig(...)` copied output: `FrontedMeekDialOverrides`,
     `FrontedMeekDialOverridesProbability` = 1.0, `FrontedMeekCDNScanUseBuiltInSpec` = true,
     plus `FrontedMeekCDNScanSpec` when non-null.
   - `AggressiveEstablishment` = true only when the beast mode preference is true.
   - When disable-timeouts is true: `NetworkLatencyMultiplierLambda` = 0.1.
4. On `JSONException` return null. Do not log config bodies, keys or server entries.

## 2. `PsiphonLocalTunnel.java`

- Remove the `protocol` parameter: `public static PsiphonLocalTunnel start(Context context, long timeoutMs) throws IOException`.
- Replace the `TunnelManager.Config` block and the `TunnelManager.buildTunnelCoreConfig`
  call with `String tunnelCoreConfig = TelegramCdnConfig.build(appContext);` and throw
  `IOException("the CDN tunnel could not start")` when it is null.
- Remove the now-unused `AppPreferences`/`R` imports and the `TunnelManager.Config` usage.
  Keep everything else (locks, latches, deadline, close/abandon, host, server entries).

## 3. `TelegramConnector.java`

- Update the single call site to `PsiphonLocalTunnel.start(ctx, timeoutMs)`.
  Nothing else changes.

## Constraints

- Java 8; no new dependencies; no manifest or resource changes.
- Do not run Gradle, do not build, do not install, do not touch a device.
- After editing, prove that `TunnelManager.java` is untouched by this dispatch (e.g.
  compare its SHA-256 before and after, or `git diff` restricted to it) and report the
  check.
- If a contract fact does not match the disk, stop and report instead of guessing.
