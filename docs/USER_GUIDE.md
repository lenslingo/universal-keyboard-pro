# UK Pro — Installation, pairing and user manual

## 1. Install

**Windows**: run `UKPro-Setup-0.2.0.exe`. It installs to Program Files, adds a firewall rule for
*Private* networks only, and (optionally) starts UK Pro at sign-in. UK Pro lives in the tray.
Make sure your Wi-Fi / hotspot is set to **Private network** (Settings → Network → Wi-Fi → properties).

**Android**: install the APK, open **UK Pro**, allow notifications. Then on the Home screen:
1. **Enable** → turn on *UK Pro Desktop Keyboard* in system settings.
2. **Select keyboard** → choose it as the active keyboard.

## 2. Pair (once per phone/laptop)

**QR (recommended)**
1. Laptop: tray icon → *Pair a new phone…* — a QR code and an 8-character code appear (valid 5 min, single use).
2. Phone: *Connected Devices* → **Scan QR code**.
3. Laptop asks *Allow "<phone>" to pair?* with a 6-digit number → **Yes**.

**Code (no camera / QR not readable)**
1. Phone: *Connected Devices* → pick the laptop under *Found on this network* (or type its IP), type the code.
2. Compare the **verification number** on the phone with the laptop's dialog; accept only if they match.

Both devices must be on the same Wi-Fi or the phone's hotspot. No account or internet is needed.
Several phones can pair with one laptop, and one phone with several laptops.

## 3. Everyday use

### Keyboard
* **Phone → PC**: type with UK Pro Keyboard in any app. The toolbar chip chooses where keys go:
  *Phone+PC*, *PC only*, *Phone only*. Tap the laptop name to switch between connected laptops.
* **PC → phone (mirroring)**: laptop tray → *PC keyboard → Phone*. An orange bar at the top of the
  screen shows while mirroring and has a **Pause** button. Keys go to the phone's focused text field
  and still type normally on the PC.
* **Move / resize the keyboard**: tap **⇕** on the keyboard toolbar → drag the bar up/down, or use
  ▲ ▼ (move), A− A+ (key height), Narrower/Wider and ⇤ ⇔ ⇥ (one-handed left / centre / right).
  The same settings are in *Keyboard → Appearance*. Reset restores the default.
* **Languages (≈40 layouts)**: *Keyboard → Languages* — tick the ones you use; the 🌐 key cycles through them.
  Latin (English, Español, Português, Français AZERTY, Deutsch QWERTZ, Italiano, Türkçe, Polski, …, Tiếng Việt Telex),
  বাংলা, हिन्दी, मराठी, नेपाली, ਪੰਜਾਬੀ, ગુજરાતી, ଓଡ଼ିଆ, தமிழ், తెలుగు, ಕನ್ನಡ, മലയാളം, اردو, العربية, فارسی, עברית,
  Русский, Українська, Қазақша, Српски, Ελληνικά, ქართული, Հայերեն, ไทย, 한국어 (jamo → syllables), 日本語 (romaji → kana).
  Korean/Japanese/Vietnamese text is shown underlined while composing and goes to the PC only when committed.
  Indic conjuncts = consonant + `q` (্ / ् …) + consonant. Long-press keys for accents and extra letters.
* **Any other language** (Chinese, Japanese kanji …): phone menu → **Universal text** with any keyboard
  installed on your phone; on the laptop the **Remote text** tab with any Windows input method.
* Modifiers: tap once = next key only, double-tap = lock, or hold with one finger.
* **Nav** shows Insert/Home/End/PgUp/PgDn/arrows and the numeric keypad.

### Touchpad / mouse
Tap **🖱** on the keyboard toolbar (or phone menu → *Touchpad / Mouse*). One finger moves the pointer,
tap = click, double-tap-and-hold or long-press = drag, two-finger tap = right-click, two-finger slide =
scroll, the right-hand strip scrolls, and the Left / Middle / Right buttons can be held for dragging.
Pointer speed, scroll speed and natural scrolling are in *Touchpad / Mouse*. The laptop can switch the
touchpad off in *Settings → Allow phone touchpad*.

### Remote Control (phone menu → Remote Control)
Alt+Tab, Win+D, Win+E, Win+Tab, Task Manager (Ctrl+Shift+Esc), Alt+F4, Print Screen, Lock,
volume slider/mute, play/pause, next, previous.

### Clipboard
Copy on one device, paste on the other — text, images and files. If the phone keyboard is not
the active keyboard, use the notification's **Send clipboard** action.
*Clipboard* screen: history on this phone (copy again, send to PC, share, pin, delete) and the
**Laptop** tab (copy/paste an older laptop item on the PC). The laptop has its own history tab.
Files copied on the phone can be pasted in Explorer; on the phone, apps that cannot paste files get
**Open / Share** in the notification.

### Files
No size limit on Wi-Fi — only free storage space (checked before a transfer starts). Phone: *File Transfer* → Choose files, or the Share sheet → **Send to PC (UK Pro)**. Laptop: *Send file
to phone…*. Phone saves to *Downloads/UK Pro*; laptop to *Downloads\UK Pro*. Transfers resume after
a dropped connection and are SHA-256 verified.

### Pause and privacy
Pause from the phone notification, the IME toolbar ⏸, the laptop tray or the orange bar.
Password fields, the Windows secure desktop (UAC, lock screen) and excluded apps (Settings tab on
the laptop) are never mirrored. Keystrokes are not logged or stored.

## 4. Troubleshooting
| Symptom | Fix |
|---|---|
| Phone can't find the laptop | Same network? Network set to *Private*? Try the IP shown in the pairing panel. Some hotspots isolate clients. |
| Typing on PC does nothing in one app | That app runs as administrator (Windows UIPI) — see KNOWN_LIMITATIONS #5. |
| PC → phone does nothing | Is UK Pro Keyboard selected and a text field focused on the phone? Is mirroring on and not paused? |
| Clipboard from phone not synced | Select UK Pro Keyboard, or use *Send clipboard* in the notification. |
