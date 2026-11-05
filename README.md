# Domain Blocker

Android app that blocks a user-defined list of domains device-wide (no root) and
redirects blocked sites to a configurable "focus" page.

Built for personal use (sideload APK). UI text: German. Code and docs: English
(shareable repo).

## How it works

The app runs a **local VPN** (`VpnService`) that captures **only DNS traffic**
(UDP/53 to the routed DNS servers `8.8.8.8`, `1.1.1.1`, `9.9.9.9`) — all other
traffic bypasses the tunnel, so there is no data-path slowdown:

1. A DNS query arrives in the tunnel.
2. The queried domain is matched against the blocklist (exact + subdomain).
3. **Blocked** → the app answers itself:
   - `A` request → `127.0.0.1` (loopback)
   - `AAAA` request → empty answer (client falls back to `A`)
4. A tiny loopback HTTP server on `127.0.0.1:80` answers plain-HTTP requests
   to a blocked domain with **HTTP 302** → your redirect URL.
5. HTTPS connections to a blocked domain hit `127.0.0.1:443`, where nothing
   listens → **connection refused** (blocked; redirect is technically
   impossible without a MITM certificate — intentionally not done).
6. All other DNS queries are relayed untouched to the real DNS server.

## Limitations (by design)

- **DNS-based filtering**: apps that bypass the system DNS (hardcoded IPs,
  DNS-over-HTTPS in Private DNS settings, WARP-style VPNs) are not filtered.
- **HTTPS is blocked, not redirected** (see above).
- Only the three DNS server IPs are routed; if a device uses a different DNS
  resolver, add its IP to `BlockVpnService`.
- First activation asks for the Android *VPN permission* dialog — the app
  does not read or alter any traffic other than DNS.

## Build

```bash
export JAVA_HOME=/opt/data/toolchains/jdk17
export ANDROID_HOME=/opt/data/toolchains/android-sdk
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Stack: Kotlin, AGP 9.1.0, Gradle 9.3.1 (wrapper), compileSdk/targetSdk 36,
minSdk 26. Release builds are signed with the debug key so sideloading stays
friction-free for personal use.

## Install

1. Copy `app-release.apk` to the phone.
2. Allow "Install unknown apps" for the file manager.
3. Open the app, add domains, set the redirect target (trash icon on a row
   removes the domain), flip the switch and accept the VPN dialog.

## Layout

```
app/src/main/java/de/jona/domainblocker/
  MainActivity.kt     UI: switch, blocklist editor, redirect URL
  DomainStore.kt      persisted domain list + redirect URL
  BlockVpnService.kt  VpnService: DNS intercept, block/relay, response crafting
  RedirectServer.kt   loopback HTTP server -> 302 redirect
```
