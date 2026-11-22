# UK Pro — Architecture

```
┌──────────────────────── Android (Kotlin) ────────────────────────┐        ┌────────────────────── Windows 10/11 (C# / .NET 10) ──────────────────────┐
│ app  (Jetpack Compose UI: Home, Keyboard, Remote, Clipboard,     │        │ UKPro.Desktop (WPF tray app, per-user, NOT a service)                    │
│       Files, Devices, History, Settings)                         │        │  ├ UI/            dashboard, pairing QR, remote text panel, indicator    │
│  ├ ime/        UkImeService (InputMethodService)                 │        │  ├ Input/KeyboardCapture   WH_KEYBOARD_LL, ignores LLKHF_INJECTED        │
│  ├ keyboard/   DesktopKeyboardView (Canvas, multi-touch,         │ TLS WS │  ├ Input/KeyboardInjector  SendInput (KEYEVENTF_UNICODE + VK)           │
│  │             repeat, long-press, Bangla/English layouts)       │◀══════▶│  ├ Input/PrivacyGuard      UIA IsPassword, secure desktop, exclusions    │
│  ├ sync/       InputSync, ClipboardSync, Remote control          │  or    │  ├ Clipboard/ClipboardMonitor  AddClipboardFormatListener               │
│  ├ transport/  wifi (OkHttp WSS + pinning), bt (RFCOMM client),  │ RFCOMM │  ├ Clipboard/ClipboardWriter   CF_UNICODETEXT, CF_DIBV5+PNG, CF_HDROP   │
│  │             ConnectionManager (auto / Wi-Fi / BT preferred)   │        │  ├ Remote/RemoteControl    whitelisted shortcuts, media keys, CoreAudio  │
│  ├ security/   Keystore-wrapped pairing secrets                  │        │  ├ Transport/Bluetooth     RFCOMM server (WinRT)                         │
│  ├ data/       Room (history, devices, transfers), DataStore     │        │  └ Security/Dpapi          CryptProtectData (P/Invoke)                   │
│  └ service/    UkConnectionService (foreground, connectedDevice) │        │                                                                          │
│                                                                  │        │ UKPro.Core (net10.0, zero dependencies, cross-platform, unit-tested)     │
│ core  (pure Kotlin/JVM, zero dependencies, unit-tested)          │        │  Protocol, Json envelope, ReliableChannel (seq/ACK/outbox/dedup),        │
│  Protocol, Json, ReliableChannel, Pairing (ECDH/HKDF/HMAC),      │        │  Pairing crypto, Session auth, ClipboardResolver, FileSender/Receiver,   │
│  SessionAuth, ClipboardResolver, FileSender/Receiver, KeyCodes   │        │  WssServer (TcpListener + SslStream + WebSocket.CreateFromStream)        │
└──────────────────────────────────────────────────────────────────┘        └──────────────────────────────────────────────────────────────────────────┘
```

## Module mapping (requested → implemented)

| Requested module | Android location | Windows location |
|---|---|---|
| android-ui / windows-ui | `app/ui/*` | `UKPro.Desktop/UI` |
| android-ime | `app/ime` | — |
| keyboard-engine | `app/keyboard` + `core/KeyCodes.kt` | `Core/KeyCodes.cs` |
| input-sync-engine | `app/sync/InputSync.kt` + `core/ReliableChannel.kt` | `Core/ReliableChannel.cs` |
| keyboard-capture / -injection | — | `Desktop/Input/*` |
| clipboard-engine / -monitor / -injection | `app/sync/ClipboardSync.kt` + `core/ClipboardResolver.kt` | `Desktop/Clipboard/*` + `Core/ClipboardResolver.cs` |
| file-transfer(-engine) | `core/FileTransfer.kt` + `app/transfer` | `Core/FileTransfer.cs` |
| wifi-transport | `app/transport/WifiTransport.kt` | `Core/WssServer.cs` |
| bluetooth-transport | `app/transport/BluetoothTransport.kt` | `Desktop/Transport/BluetoothServer.cs` |
| device-pairing / security-manager | `core/Pairing.kt`, `app/security` | `Core/Pairing.cs`, `Desktop/Security` |
| local-database | `app/data` (Room + DataStore) | `Desktop/Storage` (DPAPI-encrypted JSON) |

The pure cores are deliberately free of third-party dependencies so that the most
error-prone logic (ordering, dedup, crypto, chunking, conflict resolution) is identical on
both sides and can be tested without devices. Platform shells stay thin.

## Key decisions

1. **Windows is the hub/server, Android is the client.** Android can't keep a listening
   socket reliably in the background; a Windows tray app can. One hub serves many phones;
   a phone can join many hubs → multi-device (PROTOCOL §8).
2. **Physical key ids use W3C `KeyboardEvent.code`** so mapping is one table per platform.
3. **The IME keyboard is a custom Canvas `View`, the app UI is Compose.** Compose inside an
   `InputMethodService` needs lifecycle/saved-state owner hacks on the IME window and adds
   latency on every key; a Canvas view gives multi-touch, key-repeat and <5 ms draw times.
4. **Loop prevention is structural**: Android only forwards keys pressed on its own
   keyboard view (never text it committed on behalf of Windows); Windows ignores
   `LLKHF_INJECTED` events and tags its own `SendInput` calls with a `dwExtraInfo` marker.
   Clipboard uses `clipId` + `hash` + Lamport/origin ordering.
5. **Privacy-first input capture**: the low-level hook only *reads* (always calls
   `CallNextHookEx`, never blocks typing), and forwards only when: user enabled W→A, not
   paused, focused element is not `IsPassword`, foreground process is not excluded and
   not elevated-unknown, input desktop is `Default`, and the Android side reports an
   editable non-password field. Nothing is logged or stored.
6. **Bangla, Windows → Android**: simple layouts are translated via `ToUnicodeEx` per key.
   For IME/TSF composers (Avro, Bijoy, Windows Bengali Phonetic) global capture of
   committed text is not reliable, so the Windows app provides a **Remote Text Panel**
   (a WPF text box: TSF delivers committed text to it, which is sent as
   `COMPOSITION_COMMIT`). Android → Windows Bangla always works (Unicode `SendInput`).
7. **Android clipboard**: the default IME is exempt from the Android 10+ background
   clipboard read restriction, so `UkImeService` owns the clipboard listener while it is the
   selected keyboard; otherwise sync is manual (notification action, Share sheet, app button).

## Additional features requested

* **Multi-device** — hub keeps `Map<deviceId, PeerSession>`; active-target selection for
  keyboards; clipboard broadcast + relay; per-device permissions (keyboard / clipboard /
  files / remote control) in *Connected Devices*.
* **Clipboard History** — Android: Room table `clip_history` + blob dir in app-private
  storage; Windows: DPAPI-encrypted index + blobs in `%LOCALAPPDATA%\UKPro\history`.
  Search, pin, delete, clear, retention (default 200 items / 30 days), "paste on laptop"
  from the phone via `CLIPBOARD_HISTORY_APPLY`.
* **Remote Control Panel** — Android screen with Alt+Tab (hold-to-cycle), Win+D, Win+E,
  Win+Tab, Ctrl+Shift+Esc, Lock, Alt+F4, volume slider (absolute via Core Audio
  `IAudioEndpointVolume`), mute, play/pause, next, previous. Windows executes only
  whitelisted actions.
