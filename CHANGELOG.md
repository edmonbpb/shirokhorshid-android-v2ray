# Changelog

## 2026.10.29

### Fixed
- In-app Telegram CDN fronting no longer gives up after a few attempts. The tunnel core now stays alive while its workers scan, Telegram is retried every 4 seconds, the core is restarted when the scan is exhausted or the core exits, and a tap on the button cancels immediately.

### Added
- The Telegram log now shows the CDN scan (workers and progress), the route it found (IP and SNI), the connected protocol and region, and which attempt succeeded.

### Verified on device
- Samsung A15, main tunnel off: connected after 20 attempts (~80 s) via `FRONTED-MEEK-CDN-OSSH` to `2.21.2.104` (region US); Telegram ping 977 ms; Disconnect closed the local core.

## 2026.10.28

### Added
- Connection Protocol offers **Napster** and **V2Ray** as the tunnel's upstream, alongside **WhiteDNS**. Selecting one makes that engine the only way to the servers (fail closed, no silent direct fallback).
- More Options → **Connection engines**: WhiteDNS, Napster and V2Ray settings grouped in one section directly below Beast Mode.
- In-app Telegram: **CDN fronting works standalone** through a headless tunnel core with a private CDN config copy, so it no longer needs the main VPN tunnel and cannot affect the app's own CDN path.

### Changed
- The V2Ray and Napster rows were removed from the main Options screen; all three engines are now reachable from More Options only.
- Telegram's CDN method no longer switches the global protocol preference or restarts the main tunnel.

### Fixed
- The Connection Protocol row now shows the correct summary for every value.
- The selected protocol is written straight to the tunnel's store and can no longer revert to an older value after closing More Options or restarting the app. If that write fails, the change is refused and a message is shown.

### Verified on device
- Samsung A15: Telegram → CDN fronting → Connect reached **Connected** (ping 722 ms) with the main tunnel off; the local engine started and stopped with the link, and the main tunnel never started.
- Samsung A15: protocol choice survived close/reopen and force-stop/relaunch for both WhiteDNS and Direct.

## Earlier builds

There is no changelog for builds before 2026.10.28. The delivered APKs in the release folder carry their dates in the file name.
