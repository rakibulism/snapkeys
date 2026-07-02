# SnapKeys ⌨️

An Android keyboard that turns short **typo shortcuts** into full text — universally, in any app.

Type `brb` + space and it becomes `be right back`. Type `@@` and it becomes your email. Because SnapKeys is a system-wide keyboard (an Android *Input Method*), the expansions work everywhere — messaging apps, browsers, notes, forms — not just inside one app.

> Status: v0.2.0 — a full-featured keyboard. Gboard-style layout and behaviors, word prediction, swipe typing, emoji, encrypted cloud sync, and in-keyboard snippet capture.

## Features

**Typing**
- QWERTY with optional number row, two symbol pages, and an emoji picker with recents
- Suggestion bar: your shortcut expansions, dictionary completions, and words learned from your typing
- Swipe typing, long-press accent alternates, double-space period, auto-capitalization
- Slide on the space bar to move the cursor; hold backspace to repeat; double-tap shift for caps lock
- Key preview bubbles, haptics, and key sounds, in Gboard's light/dark palettes following the system theme
- Clipboard chip: copy anything, paste it with one tap from the toolbar
- Voice typing: 🎤 in the toolbar hands off to the system voice keyboard

**Text expansion**
- Triggers expand everywhere, in any app; case-preserving (`Brb` → `Be right back`)
- Triggers may contain special characters (`@@`, `!!`, `addr.`)
- Save snippets right from the keyboard: tap 🔖, type a trigger, done
- Per-shortcut enable toggle, import/export as JSON

**Sync & privacy**
- End-to-end-encrypted backup of shortcuts to Google Drive (see below)
- The typing dictionary and learned words never leave the device
- Keyboard settings (⚙️): sound, vibration, number row, suggestions, double-space period

### 📲 Download & install

**[⬇ Download snapkeys.apk](https://github.com/rakibulism/snapkeys/releases/download/latest/snapkeys.apk)** — always the newest build from `main`, no login needed.

1. Open the link above on your phone and download the APK.
2. Tap the downloaded file. Allow "install unknown apps" if Android asks.
3. If Play Protect warns you, tap **More details → Install anyway** (expected for apps outside the Play Store).

## How it works

- **`ime/SnapKeysService`** — an `InputMethodService` registered with the system. When enabled and selected, it becomes the active keyboard across the whole device.
- **`ime/ExpansionEngine`** — pure, unit-tested logic. It watches the word before the cursor and, when a delimiter (space, newline, punctuation) is typed, replaces a matching trigger with its expansion.
- **`ime/KeyboardView`** — the on-screen keyboard, built in code: QWERTY with a number row, two symbol pages (`@ # $ € ~ …`), an emoji picker, caps-lock shift, repeating backspace, long-press accents, swipe typing, and a suggestion bar.
- **`ime/WordPredictor`** — pure, unit-tested completion + swipe-path matching over a 10k-word frequency dictionary ([google-10000-english](https://github.com/first20hours/google-10000-english)) plus words learned from your typing (stored on-device).
- **`data/Shortcut` + `data/ShortcutStore`** — the shortcut model and JSON-backed persistence (SharedPreferences).
- **`ui/MainActivity` + `ui/EditShortcutActivity`** — manage (add / edit / delete) your shortcuts.

## Using it on a device

1. Build & install the app (see below).
2. Open **SnapKeys** and tap **Enable SnapKeys keyboard** — this opens
   *Settings → System → Languages & input → On-screen keyboard*. Toggle SnapKeys on.
3. In any text field, tap the keyboard-switch button and choose **SnapKeys Keyboard**.
4. Add shortcuts in the app. They apply instantly, everywhere.

Ships with a few defaults (`brb`, `omw`, `ty`, `@@`) so you can try it immediately.

## Build

```bash
# Requires the Android SDK. From the project root:
./gradlew assembleDebug        # build the APK
./gradlew test                 # run the ExpansionEngine unit tests
./gradlew installDebug         # install onto a connected device/emulator
```

## Encrypted sync with Google Drive

Sign in with a Google account in the app and your shortcuts back up to Google
Drive and follow you to any device you sign into.

- **End-to-end encrypted**: the list is encrypted on-device (AES-256-GCM, key
  derived from your sync passphrase with PBKDF2) before upload. Google only
  stores an opaque blob. The same passphrase on another device decrypts it —
  and if you forget the passphrase, the synced data is unrecoverable by design.
- **Private app storage**: data lives in Drive's hidden per-app folder
  (`drive.appdata` scope). SnapKeys can't see your Drive files, and other apps
  can't see SnapKeys data.
- Sync runs when you open the app and right after saving a snippet from the
  keyboard; the most recently changed side wins.

### One-time developer setup (required for sign-in to work)

Google Sign-In needs an OAuth client registered to this app's signature:

1. In [Google Cloud Console](https://console.cloud.google.com/), create a
   project and enable the **Google Drive API**.
2. Configure the OAuth consent screen (External, add your account as a test
   user) and add the scope `https://www.googleapis.com/auth/drive.appdata`.
3. Create an **OAuth client ID → Android** with:
   - Package name: `com.snapkeys.app`
   - SHA-1: `C4:4E:4B:A8:4A:EF:15:FC:99:2F:46:35:D9:AE:45:BF:E5:65:9C:35`
     (the committed `app/debug.p12` signing key; if you switch keys, update this)

No client ID goes in the code — Google matches the app by package + signature.

## Roadmap

- [ ] Smarter swipe typing (language-model scoring instead of path matching)
- [ ] Emoji search
- [ ] Custom themes (colors, key shapes, backgrounds)
- [ ] Multilingual dictionaries and layouts
- [ ] Play Store release (release signing, privacy policy, listing)

## License

MIT — see [LICENSE](LICENSE).
