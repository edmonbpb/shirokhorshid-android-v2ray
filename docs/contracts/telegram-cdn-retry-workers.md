# Contract: Telegram CDN keeps scanning like the main tunnel and reports the route

Repo: `C:\Users\edmon\Desktop\shirokhorshid-src\shirokhorshid-android`
Branch: `master`

## Problem

The standalone Telegram CDN tunnel gives up after a short time. Two causes:

1. `PsiphonLocalTunnel.start(...)` waits up to the whole timeout for `onConnected()` and
   then stops the core (`abandon()`), so a still-scanning core is killed (~45 s).
2. `TelegramWebActivity` probes Telegram exactly once; when the first probe fails it closes
   the Link, which stops the core immediately.

The main tunnel instead keeps the core alive while CDN workers scan and logs progress and the
route it found. The core already emits those notices; they are currently dropped for the
Telegram tunnel.

## Hard rules

- Do not change `TunnelManager`, `TelegramCdnConfig`, or the main app's CDN path.
- The private Telegram CDN config copy stays private; no new fields in it are required here.
- Java 8; no Gradle/build/device commands in agents; only the listed files.

## Facts (verified 2026-10-28)

- `PsiphonTunnel.HostService` provides `onDiagnosticMessage(String)`,
  `onConnectedServerRegion(String)`, `onClientRegion(String)`, `onClientAddress(String)`.
- `TunnelManager.onDiagnosticMessage` shows the notices the core emits; the relevant formats:
  - `beast mode active (workers: N)`
  - `cdn fronting scan active (mode: M, workers: N, candidates: C, sni: S)`
  - `cdn fronting scan progress (attempts: X, candidates: C, working: W)` (or `tried:`)
  - `cdn fronting scan exhausted (attempts: X, candidates: C, working: W)`
  - `cdn fronting scan found (ip: A, sni: B)`
  - `tunnel connected (protocol: P)`
- `TelegramSessionLog.append(char level, String message)` is static, thread-safe and redacts
  URLs; it is the screen's LOG tab.

## 1. Local tunnel (owner: agent A) — `PsiphonLocalTunnel.java`

1. `start(...)` must wait only for the local SOCKS port (the core is listening) and return;
   do NOT wait for `onConnected` and do NOT stop the core on any connect timeout. Keep the
   port timeout (`"the CDN tunnel did not open its proxy"`). Remove the connected latch wait
   and the `"did not connect in time"` error. Keep the existing start/stop/abandon locking
   behaviour otherwise.
2. In the `Host`, override the callbacks and record state (volatile/atomics):
   - `onDiagnosticMessage(String)`: parse the notices above and append short English lines to
     `TelegramSessionLog`: `cdn scan: beast mode, workers N`, `cdn scan active: mode M, workers N`,
     `cdn scan progress: attempts X, working W` (throttle progress to at most one line per 5 s),
     `cdn scan exhausted: attempts X, working W`, `cdn route found: ip A, sni B`
     (store the found route; `sni none` stays as printed), `tunnel connected: P`.
     On `cdn fronting scan exhausted` increment an exhaustion counter.
     Ignore every other diagnostic message (no raw JSON, no URLs).
   - `onConnectedServerRegion(String)`: store, log `server region: R`.
   - `onClientAddress(String)`: store, log `exit address: A`.
   - `onConnected()`: keep the existing callback but do not gate start on it.
3. Public getters for the screen (thread-safe, may return null):
   `long getScanExhaustedCount()`, `String getFoundRoute()` (e.g. `ip A, sni B`),
   `String getConnectedRegion()`, `String getExitAddress()`, `String getConnectedProtocol()`.
4. Do not log config bodies, keys or server entries.

## 2. Screen and connector (owner: agent B)

Files: `TelegramConnector.java`, `TelegramWebActivity.java`,
`app/src/main/res/values/telegram_strings.xml`,
`app/src/main/res/values-fa/telegram_strings.xml`.

1. `TelegramConnector.Link` gains a nullable `PsiphonLocalTunnel localTunnel` and an accessor
   `public PsiphonLocalTunnel localTunnel()`. Only the standalone CDN branch passes it
   (the main-tunnel reuse branch passes null). Keep the existing constructor working.
2. In `TelegramWebActivity`, for `CDN_FRONTING` only, replace the single probe with a retry
   loop in the existing `TelegramConnect` worker:
   - after the Link is returned, call `onLinkEstablished` once (VERIFYING) and then loop:
     log `probe attempt N`, set the detail to `R.string.telegram_cdn_attempt` with N,
     run `TelegramReachability.probe`; on OK break.
   - on a failed probe: log verdict + detail + `retrying`, then sleep 4 s (check
     `generation`/`isFinishing` after every wake-up; on cancel close the Link and return
     without touching the UI).
   - when `link.localTunnel()` is not null: log the found route once when it appears; if its
     exhaustion counter grew since the last check, log `cdn scan exhausted; restarting the
     tunnel`, close the Link, and call `TelegramConnector.connect(...)` again for a fresh
     tunnel; continue the loop. Any connect failure ends the loop with that error.
   - on OK: log `connected: region R, exit A, route ..., attempts N` (skip nulls) and continue
     with the existing success path unchanged (`applyProxy`, CONNECTED, ping).
   - keep every other method's current single-probe behaviour.
3. `onConnectClicked`: while the state is `CONNECTING` or `VERIFYING`, tapping the button must
   cancel (call `disconnect()`), instead of being ignored by `connect()`.
4. Add `telegram_cdn_attempt` in both locales:
   - en: `Connecting through CDN fronting... attempt %1$d`
   - fa: `در حال اتصال از راه CDN fronting... تلاش %1$d`
   (Persian verbatim from this contract; keep ZWNJ rules: no ZWNJ needed here.)
5. Do not change the proxy rule, heartbeat, speed meter or the layout.

## Verification

Controller builds, reviews and tests on the phone. Success means: with the main tunnel off,
Telegram → CDN → Connect keeps scanning past several probe failures (log shows attempts,
workers, route), eventually reaches Connected when a healthy server exists, and a tap during
connecting cancels cleanly.
