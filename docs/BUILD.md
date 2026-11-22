# Build guide

## Option A — GitHub Actions (no local setup)
Push this folder to a GitHub repository. `.github/workflows/build.yml` runs on every push:
1. C# core tests, Kotlin core tests, Kotlin↔C# interop test.
2. `android` job → `ukpro-android` artifact (debug + release APK).
3. `windows` job → `ukpro-windows` artifact (`UKPro.exe` + `UKPro-Setup-0.2.0.exe`).

## Option B — on your machines

### Windows companion (Windows 10 1809+ / 11)
Requirements: .NET 10 SDK (`winget install Microsoft.DotNet.SDK.10`), optional Inno Setup 6
(`winget install JRSoftware.InnoSetup`).
```powershell
cd UKPro
powershell -ExecutionPolicy Bypass -File windows\build.ps1
# → dist\win-x64\UKPro.exe  and  dist\UKPro-Setup-0.2.0.exe
```
Manual steps:
```powershell
dotnet build windows\UKPro.sln -c Release
dotnet run --project windows\tests\UKPro.Core.Tests -c Release
dotnet publish windows\src\UKPro.Desktop -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist\win-x64
& "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe" windows\installer\UKPro.iss
```

### Android APK (Android 10+)
Requirements: Android Studio (Ladybug or newer) or JDK 17 + Android SDK 35.
```bash
cd UKPro/android
./gradlew :core:test            # shared core tests
./gradlew :app:assembleDebug    # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease  # → app/build/outputs/apk/release/app-release.apk
```
Release signing (optional) — keep the keystore out of the repo:
```bash
export UKPRO_KEYSTORE=/path/ukpro.jks UKPRO_KEYSTORE_PASSWORD=… UKPRO_KEY_ALIAS=ukpro UKPRO_KEY_PASSWORD=…
./gradlew :app:assembleRelease
```
Without these variables the release APK is signed with the debug key (fine for personal installs).

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or copy the APK to the phone
and open it (allow "Install unknown apps" for your file manager).

### Interop test (any OS with .NET 10, JDK 17, kotlinc)
```bash
./interop/run.sh
```
