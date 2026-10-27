# Contract: in-app Telegram connects through CDN fronting on its own

Repo: `C:\Users\edmon\Desktop\shirokhorshid-src\shirokhorshid-android`
Branch: `feature/dns-xray-upstreams`

## Problem

In the in-app Telegram screen, the CDN fronting method today only works when the main
VPN tunnel is running: `TelegramWebActivity.prepareTunnelProtocol` switches the global
protocol preference to `cdn_fronting` and starts/restarts the whole VPN service, and
`TelegramConnector.connectMain` then requires that main tunnel's SOCKS port. Pressing
connect with the main tunnel off therefore either starts the full VPN (unwanted) or
fails. The method must work standalone: a headless tunnel core in the app process that
exposes a local SOCKS proxy, used only by the Telegram WebView.

## Verified facts (2026-10-28)

- `ca.psiphon.PsiphonTunnel` (from `app/libs/ca.psiphon.aar`) can run without VPN mode:
  `PsiphonTunnel.newPsiphonTunnel(HostService)` (one instance per process) and
  `startTunneling(String serverEntries)` / `stop()`.
- `PsiphonTunnel.HostService` only requires `Context getContext()` and
  `String getPsiphonConfig()`; `HostLogger` and `HostLibraryLoader` are default-method
  interfaces, all other callbacks have defaults. `onListeningSocksProxyPort(int)` and
  `onConnected()` are the two signals we need. `getLocalSocksProxyPort()` also exists.
- Proof this works headless in this app: `UpgradeChecker.UpgradeCheckerService` is a
  `JobIntentService implements PsiphonTunnel.HostService`, uses
  `PsiphonTunnel.newPsiphonTunnel(this)` and `startTunneling(TunnelManager.getServerEntries(this))`
  with `buildTunnelCoreConfig(..., "upgradechecker")`.
- The main VPN tunnel runs in the `:TunnelVpnService` process; `TelegramWebActivity`
  runs in the main process, so the process-wide PsiphonTunnel singleton is free there.
- `TunnelManager.getServerEntries(Context)` is package-static and returns the embedded
  server list.
- `TunnelManager.buildTunnelCoreConfig(Context, Config, boolean useUpstreamProxy,
  boolean enforceSelectedUpstream, String tempTunnelName)` is public static. With
  `tempTunnelName` non-null it puts the core data under `files/<tempTunnelName>`, which
  keeps it away from the VPN tunnel's data directory. It must be called with
  `useUpstreamProxy=false` and `enforceSelectedUpstream=false` here.
- `TunnelManager.Config` is a package-private static class; its `protocolSelection`
  value `cdn_fronting` sets `LimitTunnelProtocols` to the CDN fronting set and
  `DisableTactics=true`; `cdnFrontingCustomIpList` / `cdnFrontingCustomSni` /
  `beastMode` / `disableTimeouts` are read from the same preference keys the main
  tunnel uses.
- `shareProxyOnNetwork` must stay false for the local tunnel, otherwise the config
  pins `LocalSocksProxyPort` to the LAN-sharing port and can collide with the main
  tunnel. Leave it at its default `false`.
- Telegram UI already handles any Link port: it sets a WebView proxy rule
  `socks5://127.0.0.1:<port>` (`applyProxy`), and closes links via
  `TelegramConnector.Link.close()` on disconnect and `onDestroy`.

## 1. New headless tunnel (owner: agent A)

File to create: `app/src/main/java/com/psiphon3/psiphonlibrary/PsiphonLocalTunnel.java`

Public API (used by agent B, do not change the signature):
```java
public final class PsiphonLocalTunnel implements Closeable {
    public static PsiphonLocalTunnel start(Context context, String protocol, long timeoutMs)
            throws IOException;
    public int getPort();
    @Override public void close();
}
```

Behaviour:
- `protocol` is a protocol selection value such as `"cdn_fronting"`.
- Static lock + static active instance; at most one local tunnel per process. Starting a
  new one stops the previous one first.
- Build the core config once:
  - `EmbeddedValues.initialize(context)` first.
  - `TunnelManager.Config config = new TunnelManager.Config();`
  - read from `new AppPreferences(context)` with the existing preference keys
    (`R.string.protocolSelectionPreference` is overridden with `protocol`,
    `R.string.disableTimeoutsPreference`, `R.string.beastModePreference`,
    `R.string.cdnFrontingCustomIpListPreference`, `R.string.cdnFrontingCustomSniPreference`,
    `R.string.egressRegionPreference`). Do not read LAN-sharing settings.
  - `TunnelManager.buildTunnelCoreConfig(context, config, false, false, "telegram_tunnel")`.
    If it returns null, `getPsiphonConfig()` returns `""`.
- HostService implementation is a private inner class overriding only `getContext()`,
  `getPsiphonConfig()`, `onListeningSocksProxyPort(int)`, `onConnected()`,
  `onSocksProxyPortInUse(int)` and `onExiting()`. Count down latches for port and
  connected; record the port in an AtomicInteger.
- `start()`: stop any previous instance, create the host and
  `PsiphonTunnel.newPsiphonTunnel(host)`, call `startTunneling(TunnelManager.getServerEntries(context))`,
  then wait up to `timeoutMs` for the port and for `onConnected`. On failure stop the
  core and throw `IOException` with a short English message:
  - `"the CDN tunnel could not start"` for a start exception/null config,
  - `"the CDN tunnel did not open its proxy"` when no port arrives,
  - `"the CDN tunnel did not connect in time"` when no connected signal arrives.
- `close()` must be safe from the UI thread: mark closed, clear the static instance,
  and call `tunnel.stop()` on a short-lived daemon thread. Idempotent.
- No logging of config bodies, keys or server entries.

## 2. Connector routing (owner: agent B)

File: `app/src/main/java/com/psiphon3/psiphonlibrary/TelegramConnector.java`

- Keep CONDUIT exactly as today (still needs the main tunnel).
- Change only `CDN_FRONTING`:
  ```java
  case CDN_FRONTING:
      return connectCdnFronting(ctx, mainRunning, mainSocksPort, timeoutMs);
  ```
- New `connectCdnFronting`:
  - If the main tunnel is running with a real SOCKS port AND the stored
    `protocolSelectionPreference` value equals `"cdn_fronting"`, reuse it via the
    existing `connectMain(Method.CDN_FRONTING, true, mainSocksPort)`.
  - Otherwise start `PsiphonLocalTunnel.start(ctx, "cdn_fronting", timeoutMs)`, probe
    the port with the existing `probeSocks`, and return
    `new Link(Method.CDN_FRONTING, port, localTunnel, false)`. On probe failure close
    the local tunnel and throw `IOException("the CDN tunnel did not answer")`.
  - Do not change the main protocol preference and do not touch the VPN service.
- Add the minimal imports (`net.grandcentrix.tray.AppPreferences`).

## 3. Telegram screen (owner: agent C)

File: `app/src/main/java/com/psiphon3/psiphonlibrary/TelegramWebActivity.java`

- In `prepareTunnelProtocol(...)`, after resetting `tunnelRestartedForProtocol`, return
  `false` immediately for `TelegramConnector.Method.CDN_FRONTING`: no protocol
  preference change, no starting or restarting of the VPN tunnel, no
  `tunnelStartedByUs`. Conduit behaviour stays exactly as today.
- Leave the pre-connect checks, status texts, heartbeat, proxy rule and cleanup
  untouched; CDN already is not in the "main tunnel required" check.
- Do not change any other method.

## Constraints

- Java 8, no new dependencies, no manifest changes.
- Do not run Gradle, do not install, do not touch a device (controller builds and tests).
- Only the files listed per agent may change; no reformatting.
- If a fact above does not match the disk, stop and report instead of guessing.
