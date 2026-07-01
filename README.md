# SnapKeys ⌨️

An Android keyboard that turns short **typo shortcuts** into full text — universally, in any app.

Type `brb` + space and it becomes `be right back`. Type `@@` and it becomes your email. Because SnapKeys is a system-wide keyboard (an Android *Input Method*), the expansions work everywhere — messaging apps, browsers, notes, forms — not just inside one app.

> Status: early scaffold. The core expansion engine, storage, keyboard service, and management UI are in place. The on-screen key layout is intentionally minimal and meant to be extended.

### 📲 Download & install

**[⬇ Download snapkeys.apk](https://github.com/rakibulism/snapkeys/releases/download/latest/snapkeys.apk)** — always the newest build from `main`, no login needed.

1. Open the link above on your phone and download the APK.
2. Tap the downloaded file. Allow "install unknown apps" if Android asks.
3. If Play Protect warns you, tap **More details → Install anyway** (expected for apps outside the Play Store).

## How it works

- **`ime/SnapKeysService`** — an `InputMethodService` registered with the system. When enabled and selected, it becomes the active keyboard across the whole device.
- **`ime/ExpansionEngine`** — pure, unit-tested logic. It watches the word before the cursor and, when a delimiter (space, newline, punctuation) is typed, replaces a matching trigger with its expansion.
- **`ime/KeyboardView`** — the on-screen keyboard, built in code: QWERTY with a number row, two symbol pages (`@ # $ € ~ …`), an emoji picker, caps-lock shift, and repeating backspace.
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

- [x] Symbols / numbers pages and emoji picker
- [ ] Long-press alternates on letter keys
- [ ] Case-preserving expansions (e.g. `Brb` → `Be right back`)
- [ ] Import / export shortcuts
- [ ] Per-shortcut enable toggle in the UI
- [ ] Themes (light/dark, key styling)
- [ ] Suggestion bar / inline autocomplete

## License

MIT — see [LICENSE](LICENSE).
