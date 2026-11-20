# Shir o Khorshid

An unofficial, independently maintained build of the [Psiphon](https://psiphon.ca) open-source Android client.

## Disclaimer

This application is **NOT** developed, endorsed, sponsored, or affiliated with Psiphon Inc. in any way.

For the official Psiphon application, visit: https://psiphon.ca

## What is this?

Shir o Khorshid is a community-driven, modded build of the Psiphon open-source client. It adds extra controls and settings to give users more flexibility over their connection. Based on [Psiphon's open-source Android client](https://github.com/Psiphon-Inc/psiphon-android).

## Why this project matters

Shir o Khorshid was developed to help people stay connected when internet access becomes difficult, restricted, or disrupted—especially during war, crises, or heavy censorship. Its purpose is to support access to the open internet so users can reach vital information and stay in touch with friends and family when it matters most.

## Roadmap: AI-assisted maintainer workflows

Shir o Khorshid is a solo-maintained project. To keep up with the codebase, the maintainer plans to use AI-assisted tooling (Codex-style coding agents) for core maintainer work. This is development-side workflow tooling, not a shipped app feature—nothing AI-generated will ever run on users' devices as part of the app itself.

Planned areas:

- **Pull request review of the native tunnel core and JNI glue** (the Go tunnel core and Xray CLI, and the badvpn/tun2socks C code under `app/src/main/jni`), where memory-safety and ABI mistakes are easy to miss.
- **Automation for issue triage and changelog/release housekeeping** in the existing GitHub Actions build, so reports get handled faster and releases stay consistent.
- **Security review of TLS, config parsing, and fail-closed routing logic**—the parts of the app that users under active network surveillance depend on not to leak.

These are planned workflows, not existing features; none of them are in place yet, and this section will be updated as they land.

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
