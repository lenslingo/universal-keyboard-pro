# Phase 1 — Environment inspection (2026-11-22)

The work was done in a **Linux cloud workspace**, not on your laptop or phone. Neither of
your devices is reachable from it, so hardware checks (Bluetooth adapter, firewall, real
Wi-Fi) must be done on your machines with `scripts/check-windows-env.ps1`.

| Item | Result in this workspace |
|---|---|
| OS | Ubuntu 24.04.5 LTS, x86_64, 2 vCPU, 7.8 GiB RAM |
| JDK | OpenJDK 21.0.13 ✅ |
| Kotlin | `kotlinc` 1.3.31 (Ubuntu package) ✅ — used for the pure-Kotlin core and tests; the Android build uses Kotlin 2.x via Gradle |
| Gradle | 8.14.3 ✅ |
| Android SDK / AGP | ❌ not installed; `dl.google.com` and `maven.google.com` blocked (HTTP 403) → **APK cannot be built here** |
| Maven Central / Gradle Plugin Portal | ❌ blocked (403) → no third-party JVM libraries |
| .NET SDK | .NET 10.0.115 installed from Ubuntu apt ✅ — console/library builds and tests work |
| NuGet (api.nuget.org) | ❌ blocked (403) → WPF/Windows targeting packs cannot be restored → **Windows .exe cannot be built here** |
| Bluetooth hardware | n/a (cloud VM) |
| Network | Loopback TLS/WebSocket works → used for the Kotlin↔C# interop test |

## What this means

* Platform-independent code (protocol, reliability, crypto, file chunking, clipboard
  conflict resolution, TLS WebSocket server/client) is **compiled and tested here**.
* Platform shells (Android IME/UI, Windows WPF/hooks/SendInput/clipboard) are written but
  must be compiled on your machine (or by the included GitHub Actions workflow, which runs
  on GitHub's Ubuntu + Windows runners where Google Maven and NuGet are available).

## Check your machines

Windows (PowerShell):
```powershell
powershell -ExecutionPolicy Bypass -File scripts\check-windows-env.ps1
```
Android: Settings → About phone (Android version ≥ 10), and enable Developer options →
USB debugging if you want to install with `adb`.

## Required firewall rule (Windows)

The installer adds it; manually:
```powershell
New-NetFirewallRule -DisplayName "UK Pro (LAN)" -Direction Inbound -Protocol TCP -LocalPort 47821 -Profile Private -Action Allow
New-NetFirewallRule -DisplayName "UK Pro mDNS" -Direction Inbound -Protocol UDP -LocalPort 5353 -Profile Private -Action Allow
```
The rule is scoped to the **Private** profile only; set your home Wi-Fi / hotspot to
"Private network". On Public networks Windows Firewall keeps the port closed, and the
server additionally refuses connections from non-private (non-RFC1918/link-local) addresses.
