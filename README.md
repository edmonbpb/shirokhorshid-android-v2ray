# Shir o Khorshid

An unofficial, independently maintained build of the [Psiphon](https://psiphon.ca) open-source Android client.

## Disclaimer

This application is **NOT** developed, endorsed, sponsored, or affiliated with Psiphon Inc. in any way.

For the official Psiphon application, visit: https://psiphon.ca

## What is this?

Shir o Khorshid is a community-driven, modded build of the Psiphon open-source client. It adds extra controls and settings to give users more flexibility over their connection. Based on [Psiphon's open-source Android client](https://github.com/Psiphon-Inc/psiphon-android).

## Latest build

| | |
|---|---|
| Version | 2026.10.29 |
| APK | `ShirOKhorshid-2026.10.29.apk` |
| Minimum Android | 5.0 (API 21) for the DNS engines; the core client keeps minSdk 14 |

Highlights of this release:

- **In-app Telegram CDN keeps scanning like the main tunnel.** It retries until a healthy CDN route is found, restarts its own core when a scan is exhausted, and logs the workers, the route (IP and SNI), the connected protocol and the region.
- **Connection Protocol** also offers Napster and V2Ray as the tunnel's upstream, next to WhiteDNS. Choosing one makes it the only way to the servers (fail closed, no silent direct fallback).
- **More Options → Connection engines** groups WhiteDNS, Napster and V2Ray settings; the main Options screen is back to the core rows only.
- **In-app Telegram: CDN fronting works standalone.** It runs its own headless tunnel core with a private CDN config copy, so it no longer needs the main VPN tunnel and cannot affect the app's own CDN path.

See [CHANGELOG.md](CHANGELOG.md) for details.

## License

This software is licensed under the [GNU General Public License v3.0](LICENSE) (GPL-3.0), the same license as the original Psiphon Android client.

## No Warranty

THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED. USE AT YOUR OWN RISK. See the [LICENSE](LICENSE) file for full terms.
