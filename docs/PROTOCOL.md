# UK Pro Shared Protocol — v1

Status: **implemented and interop-tested** in `windows/src/UKPro.Core` (C#) and
`android/core` (Kotlin). Test vectors live in `shared/test-vectors/`.

## 1. Transports and framing

| Transport | Framing | Security |
|---|---|---|
| Wi-Fi (primary) | WebSocket (RFC 6455) over TLS 1.3 (1.2 fallback), path `/ukpro/v1`. Text frames = JSON envelope. Binary frames = file chunks. | Server certificate pinned by SHA-256 fingerprint (from QR). App-layer mutual HMAC auth (§4). |
| Bluetooth Classic RFCOMM (fallback) | Length-prefixed records: `kind:u8` (`0x01` JSON, `0x02` binary) + `len:u32 BE` + bytes. Same envelope/binary layout as Wi-Fi. | Secure (bonded, encrypted) RFCOMM socket + same app-layer HMAC auth; records are additionally sealed with AES-256-GCM using the session key (§4.3). |

Service UUID (RFCOMM): `7c3e2a1e-5b0f-4a8e-9d2c-55c0a1b2c3d4`.
mDNS/NSD service type: `_ukpro._tcp` — TXT: `id`, `name`, `fp` (cert SHA-256 hex), `v=1`.
Default TCP port: `47821` (falls back to the next free port; advertised via mDNS/QR).

The server role is the **Windows hub**. Android devices are clients. One hub accepts many
Android clients; one Android client may hold sessions with many hubs (§8).

## 2. Envelope

Every JSON message:

```json
{
  "v": 1,
  "type": "TEXT_COMMIT",
  "eventId": "7f1c0b0e-3f3a-4b0d-9a77-2a8f0c9d1e55",
  "sessionId": "b6c1…",
  "sourceDeviceId": "and-2f9a…",
  "targetDeviceId": "win-81c0…",
  "seq": 42,
  "ts": 1763770000123,
  "payload": { "text": "আমি" }
}
```

| Field | Rule |
|---|---|
| `v` | Protocol version. Receivers reject `v` they don't support with `ERROR{code:"UNSUPPORTED_VERSION"}`. |
| `eventId` | UUID v4, unique per event. Used for deduplication (LRU of the last 4096 ids). |
| `sessionId` | Random id chosen by the sender for its outgoing stream to this peer. Survives reconnects and transport switches; changes when the app restarts. |
| `seq` | `> 0` for **reliable** messages (strictly +1 per message per `(sourceDeviceId, sessionId)`), `0` for **unreliable** ones (`PING`, `PONG`, `ACK`, `FILE_CHUNK`, `AUTH_*`, `PAIR_*`, `DEVICE_STATUS`). |
| `ts` | Sender wall clock, Unix ms. Informational only (latency display); never used for ordering. |
| `targetDeviceId` | Device id or `*` (broadcast; the hub relays to every authorised peer that enabled the feature). |

### 2.1 Reliable delivery (exactly-once across transport switches)

* Sender keeps every reliable message in an **outbox** until acknowledged.
* Receiver delivers strictly in `seq` order: `seq ≤ lastDelivered` → duplicate, dropped;
  `seq = lastDelivered+1` → delivered, then buffered successors drained; gap → buffered
  (max 1024, otherwise the session is reset with `ERROR{code:"RESYNC"}`).
* Receiver sends `ACK{upTo:n}` (cumulative) at most every 50 ms or every 32 messages.
* On (re)connect the auth handshake carries `peerSessionId` (the peer's stream it has seen)
  and `lastRecvSeq` for it; the sender resends every outbox entry with `seq > lastRecvSeq` on the new
  transport. Time-sensitive input (`KEY_*`, `TEXT_COMMIT`, remote actions) older than 10 s is
  replaced by a `NOOP` with the same `seq`, so stale keystrokes are never typed into whatever
  window has focus later. Combined with in-order delivery this guarantees no loss and no duplicates when
  Wi-Fi drops and Bluetooth takes over.

## 3. Message types

Required by the specification: `KEY_DOWN KEY_UP TEXT_COMMIT COMPOSITION_COMMIT CLIPBOARD_TEXT
CLIPBOARD_IMAGE CLIPBOARD_FILE FILE_START FILE_CHUNK FILE_COMPLETE DEVICE_STATUS PAIR_REQUEST
PAIR_ACCEPTED PING PONG`.

Extensions (needed to implement the above safely): `ACK ERROR PAIR_REJECTED AUTH_HELLO
AUTH_CHALLENGE AUTH_RESPONSE AUTH_OK AUTH_FAIL FILE_ACCEPT FILE_RESUME FILE_PAUSE FILE_CANCEL
FILE_RESULT INPUT_FOCUS SYNC_CONFIG REMOTE_ACTION MEDIA_CONTROL VOLUME_SET
CLIPBOARD_HISTORY_REQUEST CLIPBOARD_HISTORY_RESPONSE CLIPBOARD_HISTORY_APPLY CLIPBOARD_FETCH DEVICE_LIST NOOP`.

### 3.1 Input

`KEY_DOWN` / `KEY_UP` (reliable):
```json
{ "code": "KeyA", "key": "a", "mods": 3, "repeat": false }
```
* `code` — W3C UI Events `KeyboardEvent.code` value (platform-neutral physical key id:
  `KeyA…KeyZ`, `Digit0…9`, `F1…F12`, `Enter`, `Backspace`, `Tab`, `Escape`, `Delete`,
  `Insert`, `Home`, `End`, `PageUp`, `PageDown`, `ArrowUp/Down/Left/Right`, `Numpad0…9`,
  `NumpadAdd`…, `ControlLeft`, `ShiftLeft`, `AltLeft`, `MetaLeft`, `CapsLock`, `Space`,
  punctuation codes…). Each side maps `code` ↔ native (Windows VK, Android KEYCODE).
* `key` — the character the source keyboard layout produced (`""` for non-printing keys).
  Windows computes it with `ToUnicodeEx(..., wFlags=0x4)` (does not disturb dead-key state).
* `mods` bitmask — `1` Ctrl, `2` Shift, `4` Alt, `8` Meta/Win, `16` CapsLock, `32` AltGr.

Receiver rule: if `mods` has no Ctrl/Alt/Meta and `key` is printable → insert text `key`;
otherwise replay as a key event (shortcut/navigation).

`TEXT_COMMIT` (reliable) `{ "text": "Hello\nবাংলা" }` — finished text. Never contains
uncommitted composition.
`COMPOSITION_COMMIT` (reliable) `{ "text": "আমি", "composer": "bn-phonetic" }` — text that
finished an IME composition. Treated like `TEXT_COMMIT` by the receiver.

`INPUT_FOCUS` (reliable) `{ "editable": true, "password": false, "app": "com.whatsapp" }` —
tells the peer whether its forwarded input currently has a valid, non-sensitive target.
Senders stop forwarding while the peer reports `editable:false` or `password:true`.

### 3.2 Clipboard

All clipboard payloads carry `clipId` (UUID), `hash` (SHA-256 hex of the canonical
content), `lamport` (Lamport clock), `origin` (device id where the copy happened).

* `CLIPBOARD_TEXT` `{ …, "text": "…", "mime": "text/plain" }` — inline up to 1 MiB UTF-8;
  larger text is sent as a `text/plain` transfer.
* `CLIPBOARD_IMAGE` `{ …, "mime": "image/png", "size": 123456, "width":1080, "height":2400,
  "transferId": "…" }` — bytes follow through the file-transfer engine (§5).
* `CLIPBOARD_FILE` `{ …, "files": [ { "name": "a.pdf", "size": 1, "transferId": "…" } ], "auto": true }` —
  if the total size is ≤ the auto-transfer limit (default 50 MiB) the bytes follow immediately
  (`auto:true`); otherwise the receiver shows the files and fetches them on demand with
  `CLIPBOARD_FETCH {clipId}`.

Canonical hash input: text → UTF-8 bytes with line breaks normalised to `\n`;
image → PNG-encoded bytes; files → `name\0size\0id\n` per file, sorted by name, where `id` is
chosen by the origin (Windows uses the last-write time so copying a 4 GB file never blocks; the
transfer itself is still SHA-256 verified). Receivers treat file hashes as opaque and reuse the
origin's hash for echo suppression.

**Loop prevention**: a device never re-broadcasts a clip whose `hash` equals the last clip it
applied from a remote peer, and ignores incoming `clipId`s it has already seen.
**Conflict resolution** (near-simultaneous copies): every device keeps a Lamport clock
`L` (`L=L+1` on local copy, `L=max(L,incoming)` on receive). An incoming clip replaces the
current clip only if `(lamport, origin)` is lexicographically greater than the current
clip's `(lamport, origin)`. All devices therefore converge on the same winner regardless of
arrival order.

### 3.3 Clipboard history (extension)

`CLIPBOARD_HISTORY_REQUEST {limit, before?}` → `CLIPBOARD_HISTORY_RESPONSE {items:[{clipId,
kind, preview, size, origin, ts, pinned}]}`. `CLIPBOARD_HISTORY_APPLY {clipId, paste:bool}`
puts an older entry back on the receiver's clipboard and (if `paste`) injects Ctrl+V.
Sensitive clips (Android `EXTRA_IS_SENSITIVE`, Windows `ExcludeClipboardContentFromMonitorProcessing`
/ `CanIncludeInClipboardHistory=0` / `Clipboard Viewer Ignore`) are never stored or synced.

### 3.4 Remote control (extension)

`REMOTE_ACTION {action}` — whitelisted on Windows: `ALT_TAB`, `ALT_SHIFT_TAB`, `WIN_D`, `WIN_E`,
`WIN_TAB`, `TASK_MANAGER` (Ctrl+Shift+Esc), `WIN_L` (lock), `ALT_F4`, `PRINT_SCREEN`.
`MEDIA_CONTROL {action}` — `PLAY_PAUSE NEXT PREV STOP VOL_UP VOL_DOWN MUTE`.
`VOLUME_SET {level:0..100}` — absolute master volume (Core Audio).
`DEVICE_STATUS` from Windows includes `{volume, muted, foregroundApp, kbCapture, ...}`.

### 3.5 Device / config

`DEVICE_STATUS {name, platform, battery?, volume?, muted?, transport, features…}` (unreliable).
`SYNC_CONFIG {a2w, w2a, clipboard, paused}` — keeps both sides' switches visible.
`DEVICE_LIST {devices:[{id,name,platform,connected}]}` — hub → clients (multi-device).
`PING {n}` / `PONG {n}` every 5 s; RTT shown as latency; 3 missed pongs → link dead.

## 4. Security

### 4.1 Pairing (QR, one-time code)

1. Windows creates an ephemeral P-256 key pair and an 8-character one-time code
   (Crockford base32, 40 bits, single use, 5 min expiry) and shows a QR:
   `ukpro://pair?v=1&h=<ip1,ip2>&p=<port>&fp=<cert sha256 hex>&id=<hubId>&n=<name>&c=<code>&pk=<server pub, base64url, uncompressed point>`
2. Android connects with the certificate pinned to `fp`, generates its own ephemeral P-256
   key, computes `Z = ECDH(clientPriv, serverPub)` and
   `secret = HKDF-SHA256(ikm=Z, salt=UTF8(code), info=UTF8("UKPro pair v1|"+hubId+"|"+clientId), L=32)`.
3. `transcript = hubId|clientId|serverPkB64|clientPkB64|fp`.
   Android → `PAIR_REQUEST {deviceId, name, platform, pk, confirm = HMAC-SHA256(secret, "client-confirm|"+transcript)}`.
4. Windows derives the same secret, verifies `confirm` (constant-time), asks the user
   *"Allow <name> to pair?"* and answers `PAIR_ACCEPTED {deviceId, name, confirm = HMAC(secret, "server-confirm|"+transcript)}`
   or `PAIR_REJECTED`. One wrong confirm burns the code.
5. Both store `{peerId, name, secret, fp}` — Android: secret wrapped by an AES-GCM key in
   Android Keystore; Windows: DPAPI (`CryptProtectData`, CurrentUser scope).

Manual pairing (no camera): Android finds the hub with NSD (or the user types the IP), the user
types the 8-char code, and the phone connects trusting the certificate on first use and recording
its fingerprint `fp`. It sends `PAIR_INFO {}`; the hub answers `PAIR_INFO {hubId, name, pk}` while
an offer is active. The flow then continues exactly as above with that `pk` and the *observed* `fp`
in the transcript — a man-in-the-middle with another certificate makes the confirmation fail.
Both screens also show a 6-digit **verification number** `= first 20 bits of SHA-256(transcript) mod 10⁶`
for the user to compare. The secret itself never crosses the network.

### 4.2 Connection authentication (every connection, mutual)

```
C → S  AUTH_HELLO     {deviceId, nonce: nC, sessionId, peerSessionId, lastRecvSeq}
S → C  AUTH_CHALLENGE {deviceId, nonce: nS, sessionId, peerSessionId, lastRecvSeq,
                       mac: HMAC(secret, "S|"+nC+"|"+nS+"|"+fp)}
C → S  AUTH_RESPONSE  {mac: HMAC(secret, "C|"+nC+"|"+nS+"|"+fp)}
S → C  AUTH_OK        {}
```
Nonces are 32 random bytes (base64url). `fp` binds the auth to the pinned TLS certificate
(channel binding). Unknown device or bad MAC → `AUTH_FAIL` and close. 5 failures/min from
one IP → that IP is refused for 60 s.

### 4.3 Session key (Bluetooth record sealing)

`sessionKey = HKDF-SHA256(ikm=secret, salt=nC‖nS, info="UKPro session v1", L=32)`;
records: `nonce(12) ‖ AES-256-GCM(record, aad = kind)`; nonce = 4-byte direction tag +
8-byte counter.

## 5. File transfer

```
A → B  FILE_START  {transferId, name, size, mime, sha256, chunkSize, purpose:"file"|"clipboard"}
B → A  FILE_ACCEPT {transferId, resumeFrom: <bytes already stored>}   (or FILE_CANCEL)
A → B  binary frames (FILE_CHUNK) …
A → B  FILE_COMPLETE {transferId}
B → A  FILE_RESULT {transferId, ok, sha256}
```
Binary frame layout (`FILE_CHUNK`): `headerLen:u32 BE ‖ header JSON (UTF-8) ‖ data`.
Header: `{"type":"FILE_CHUNK","transferId":…,"offset":…,"length":…}`.
* Chunk size: default 256 KiB on Wi-Fi, 16 KiB on Bluetooth (configurable 16 KiB–4 MiB).
* Files are streamed from disk; at most a few chunks are in memory.
* Receiver writes to `<name>.ukpart` + `<name>.ukpart.json` (received offset). Reconnect →
  sender re-sends `FILE_START`; receiver answers `resumeFrom`. Integrity is verified with
  SHA-256 over the whole file before the `.ukpart` is renamed.
* `FILE_PAUSE` / `FILE_CANCEL` work in both directions. Bluetooth-only mode: default limit
  25 MiB per file (configurable), with progress.

## 6. Error codes

`UNSUPPORTED_VERSION, NOT_AUTHORISED, RESYNC, BAD_MESSAGE, FEATURE_DISABLED,
PASSWORD_FIELD, NO_EDITABLE_FIELD, ELEVATED_TARGET, TRANSFER_TOO_LARGE, HASH_MISMATCH`.

## 7. Versioning

Additive changes (new optional fields, new types) keep `v=1`; unknown types are answered
with nothing and ignored; unknown fields are ignored.

## 8. Multi-device routing

* The hub keeps one session per paired Android device. Each device has its own secret.
* **Keyboard**: Windows → Android goes to the *active target* (chosen in the tray menu,
  dashboard, or with the configurable hotkey `Ctrl+Alt+]`); Android → Windows goes to the
  Android device's chosen *active laptop*.
* **Clipboard**: broadcast (`targetDeviceId:"*"`). The hub relays an Android clip to other
  Android clients unchanged (`origin`, `clipId`, `lamport` preserved), so phone ↔ phone sync
  works through a laptop. Dedup by `clipId` prevents relay cycles between hubs.
* **Files**: always point-to-point.
