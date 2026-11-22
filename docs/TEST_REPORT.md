# Test report — 2026-11-22 (build 0.2.0)

Environment: Linux cloud workspace (Ubuntu 24.04, .NET 10.0.115, OpenJDK 21, kotlinc 1.3.31).
No Android SDK, no NuGet access, no physical devices — see ENVIRONMENT_REPORT.md.

## 1. Automated tests that ran here

### C# core — `dotnet run --project windows/tests/UKPro.Core.Tests` → **26/26 passed**
Envelope (Bangla, version check), **18 scripts** (বাংলা, हिन्दी, اردو, العربية, Русский, Ελληνικά, 中文, 日本語, 한국어, ไทย, עברית, Tiếng Việt, தமிழ், accented Latin, emoji ZWJ/flags, non-BMP CJK), dead-key composition (´e→é, ¨U→Ü, ~n→ñ…), binary frames, reliable channel (order, duplicates, gaps,
ACK, resend, stale-keystroke NOOP, session reset), pairing crypto (agreement, wrong code,
code normalisation), session MACs, clipboard (echo suppression, convergence, hashes), file
transfer (5 MiB verified, resume, corruption, unsafe names), key codes, and **hub end-to-end over
real TLS WebSockets**: pairing, auth, both-way text, ACK trimming, reconnect with exactly-once
delivery in both directions, bad MAC / unknown device / reused code rejected, multi-device broadcast.

### Kotlin core — `CoreTests` → **20/20 passed**
JSON codec, envelopes, frames, **shared test vectors produced by C#** (ECDH, HKDF secret,
confirmations, verification number, session MACs, session key, clipboard hashes, 18-script
text hashes — all bit-identical), reliable channel, clipboard resolver, file transfer, offer parsing, key codes.

### Kotlin ↔ C# interop over TLS WebSocket — `interop/run.sh` → **9/9 passed**
| Step | Result |
|---|---|
| QR pairing with pinned certificate + mutual HMAC auth | PASS |
| Wrong certificate fingerprint refused | PASS |
| TEXT_COMMIT in every script (Bangla, Hindi, Urdu, Arabic, Cyrillic, Greek, CJK, Korean, Thai, Tamil, emoji) | PASS |
| KEY_DOWN Ctrl+C round-trip | PASS |
| Clipboard text: hash verified by hub, duplicate clipId rejected | PASS |
| 3 MiB file phone→laptop and laptop→phone, SHA-256 verified | PASS (≈11–12 MiB/s on loopback) |
| Message queued offline delivered exactly once after reconnect | PASS |
| Keyboard event round-trip latency, 100 samples | PASS — median ≈1.0 ms, p95 ≈4 ms (loopback) |
| Manual-code pairing (PAIR_INFO, TOFU fingerprint, verification number) | PASS |

### Desktop app partial type-check
Win32 P/Invoke, SendInput injector, keyboard hook, remote control + Core Audio, mDNS, storage:
compiled against net10.0 here (**build succeeded**). The WPF/UI Automation files cannot be
compiled here (Windows Desktop targeting pack comes from NuGet, which is blocked).

## 2. Required test matrix (PART 14)

Legend: **A** = automated test passed here · **W** = implemented, needs a device run · **✗** = not implemented yet.

| # | Test | Status | Notes |
|---|---|---|---|
| 1 | Android keyboard → Notepad | W | Protocol path A (interop step 3–4); SendInput needs Windows |
| 2 | Android keyboard → Word | W | |
| 3 | Android keyboard → browser fields | W | |
| 4 | Windows keyboard → Android fields | W | Hook + IME path |
| 5 | Bidirectional English | W | |
| 6 | Bidirectional Bangla | W / A | Bangla text over the protocol: A. IME-composed Windows Bangla uses the Remote Text Panel |
| 7 | Backspace and Enter | W | |
| 8 | Ctrl+C / Ctrl+V | W / A | Shortcut transport: A |
| 9 | Modifier keys | W | Chords are self-contained taps — no stuck modifiers by design |
| 10 | Text clipboard sync | A (core) / W | |
| 11 | Image clipboard sync | W | |
| 12 | PDF transfer | A (core, any binary) / W | |
| 13 | DOCX transfer | A (core) / W | |
| 14 | Video transfer | A (core) / W | |
| 15 | Large file transfer | A (5 MiB streamed, chunked) / W | Multi-GB needs devices |
| 16 | Wi-Fi disconnection | A (simulated drop + resend) / W | |
| 17 | Bluetooth reconnection | ✗ | Phase 8 |
| 18 | Wi-Fi → Bluetooth fallback | ✗ (sequence/dedup part A) | Phase 8 transport missing |
| 19 | Duplicate event prevention | A | |
| 20 | Password field protection | W | Windows UIA IsPassword; Android EditorInfo |
| 21 | Restart + automatic reconnection | A (new session id / resend) / W | |

## 3. Measurements

| Metric | Value | Where |
|---|---|---|
| Keyboard round-trip latency | median ≈1.0 ms, p95 ≈4 ms | loopback, includes TLS + JSON + echo |
| File throughput | ≈11–12 MiB/s | loopback, 256 KiB chunks, single CPU-bound VM |
| Clipboard latency, memory, CPU, reconnection time on real Wi-Fi | not measured | needs your phone + laptop |

## 4. How to re-run
```bash
dotnet run --project windows/tests/UKPro.Core.Tests     # C# core
./interop/run.sh                                         # Kotlin core tests + interop
cd android && ./gradlew :core:test                      # Kotlin core via Gradle (JUnit wrapper)
```
