# Razban for Android

Android port of the Razban network-privacy utility. Embeds the **same sing-box
core** (v1.13.12) as the Windows build via `libbox` (gomobile AAR), driven by
Android's `VpnService` instead of a wintun TUN adapter.

## Architecture

| Concern | Windows | Android |
|---|---|---|
| TUN adapter | wintun + `auto_route`/WFP | `VpnService.Builder` → `openTun()` returns the fd |
| Core control | `clash_api` HTTP (PUT /configs) | libbox `CommandServer`/`CommandClient` over a unix socket |
| Default NIC pin | `Get-NetRoute` PowerShell | `ConnectivityManager` default-network callback |
| DNS | `ClassifyingDnsServer` :5354 + anti-loop | `LocalDNSTransport` resolving on the protected underlying network |
| Per-app routing | `process_name` rules | `package_name` rules + `VpnService.Builder.addDisallowedApplication` |
| Disconnect | kill process tree + PowerShell cleanup | `closeService()` + `fd.close()` (OS restores routes) |

The **routing brain is reused verbatim**: the embedded itdoginfo+ColdBoot
ruleset, `domain_suffix` rules and the selector/urltest bundle outbounds are
the same JSON the desktop emits. `ConfigStore.adaptForAndroid()` strips the
handful of desktop-only fields (`interface_name`, `default_interface`, jumbo
MTU) at import time, so a config exported from the Windows app loads as-is.

Source layout:

```
app/src/main/java/com/razban/app/
  RazbanApp.kt              Libbox.setup() on startup
  bg/
    RazbanVpnService.kt     VpnService + PlatformInterface + CommandServerHandler (the port)
    DefaultNetworkMonitor   default-network callback → core
    LocalResolver.kt        LocalDNSTransport (no leak/loop)
    ConnectionResolver.kt   UID→package for package_name rules
    Notifications.kt        foreground-service notification
    ConfigStore (config/)   import + Android adaptation of the sing-box JSON
  ui/
    MainActivity.kt         connect/disconnect, VPN consent, clipboard import
    PerAppActivity.kt       choose apps that bypass the tunnel
```

## Getting a config into the app

1. On the desktop build, export your sing-box config (or copy `data/` config).
2. Copy the JSON to the clipboard.
3. In the app: **Импорт конфигурации из буфера**.

The app adapts it for Android automatically. byedpi (DPI fragmentation) is not
yet bundled on Android — `dpi-bypass` outbounds gracefully fall back to the
tunnel (see TODO).

## Building

Two heavy artifacts: `libbox.aar` (the Go core) and the APK.

**Via CI (recommended — no local toolchain):** push this tree to the GitHub
repo and run the `android` workflow (Actions tab → Run workflow). It installs
Go 1.24 + Android NDK 28, builds `libbox.aar`, then assembles + signs the APK
and uploads it as an artifact / attaches it to an `android-v*` release.

**Locally:** needs Go 1.24, Android SDK + NDK 28, JDK 17.
```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.0.13004108
bash build-libbox.sh v1.13.12     # → app/libs/libbox.aar
gradle :app:assembleRelease       # → app/build/outputs/apk/release/
```

## Signing

CI signs with a keystore supplied via secrets `KEYSTORE_B64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them it falls back to
a debug-signed (installable) APK.

## TODO

- byedpi (`libbyedpi.so`) for the `dpi-bypass` outbound — port the desktop
  byedpi-probe auto-fallback.
- Live per-host status/throughput via `CommandClient` streams.
- In-app config export from a paired desktop / QR import.
- Smart per-domain classification background loop (reuse SmartProbe logic).

## Legal

Network privacy utility. Not intended or promoted for accessing resources
restricted under applicable law. Provided "as is", without warranty.
