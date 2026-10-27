# Changelog

## 2026.10.28

### Added
- Connection Protocol offers **Napster** and **V2Ray** as the tunnel's upstream, alongside **WhiteDNS**. Selecting one makes that engine the only way to the servers (fail closed, no silent direct fallback).
- More Options → **Connection engines**: WhiteDNS, Napster and V2Ray settings grouped in one section directly below Beast Mode.
- In-app Telegram: **CDN fronting works standalone** through a headless tunnel core with a private CDN config copy, so it no longer needs the main VPN tunnel and cannot affect the app's own CDN path.

### Changed
- The V2Ray and Napster rows were removed from the main Options screen; all three engines are now reachable from More Options only.
- The Connection Protocol row now shows the correct summary for every value.
- Telegram's CDN method no longer switches the global protocol preference or restarts the main tunnel.

### Verified on device
- Samsung A15: Telegram → CDN fronting → Connect reached **Connected** (ping 722 ms) with the main tunnel off; the local engine started and stopped with the link, and the main tunnel never started.

## Earlier builds

There is no changelog for builds before 2026.10.28. The delivered APKs in the release folder carry their dates in the file name.
