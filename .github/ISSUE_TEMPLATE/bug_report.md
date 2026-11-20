---
name: Bug report
about: Report a problem with the Shir o Khorshid app
title: "[Bug]: "
labels: bug
---

## What happened?

Describe what went wrong, and what you expected to happen instead.

## App version

The Shir o Khorshid version you are running (e.g. 2026.10.29 — visible at the top of the Options screen).

## Android version and device

e.g. Samsung A15, Android 14 (API 34). Note: the DNS engines require Android 5.0 (API 21); the core client supports API 14+.

## Which path failed?

- **Main tunnel** — which Connection Protocol was selected (WhiteDNS, Napster, or V2Ray)? Remember: the selected engine is the only way to the servers (fail closed, no silent direct fallback), so a failure of that engine means no connection.
- **In-app Telegram CDN** — did the scan start, did it find a route, did Telegram connect?

## Log lines

Copy the relevant lines from the in-app log. For the main tunnel include the connected protocol and region; for the in-app Telegram CDN include the scan progress, the route it found (CDN route IP and SNI), the protocol and the region.

Please redact any personal information (your own IP address, account identifiers, phone numbers) before pasting.

## Reproduces with default settings?

Does the problem still occur after resetting the app to default settings (no changes under More Options → Connection engines)? If it only happens with a specific configuration, list what you changed.
