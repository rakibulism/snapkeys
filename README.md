# SnapKeys ⌨️

An Android keyboard that turns short **typo shortcuts** into full text — universally, in any app.

Type `brb` + space and it becomes `be right back`. Type `@@` and it becomes your email. Because SnapKeys is a system-wide keyboard (an Android *Input Method*), the expansions work everywhere — messaging apps, browsers, notes, forms — not just inside one app.

> Status: early scaffold. The core expansion engine, storage, keyboard service, and management UI are in place. The on-screen key layout is intentionally minimal and meant to be extended.

### Download
https://github.com/rakibulism/snapkeys/actions/runs/28543516880/artifacts/8020931059

## How it works

- **`ime/SnapKeysService`** — an `InputMethodService` registered with the system. When enabled and selected, it becomes the active keyboard across the whole device.
- **`ime/ExpansionEngine`** — pure, unit-tested logic. It watches the word before the cursor and, when a delimiter (space, newline, punctuation) is typed, replaces a matching trigger with its expansion.
- **`ime/KeyboardView`** — a simple QWERTY key grid built in code that emits key events to the service.
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

> The Gradle wrapper JAR is not committed. Generate the wrapper once with a local
> Gradle install (`gradle wrapper --gradle-version 8.7`) or open the project in
> Android Studio, which provisions it automatically.

## Roadmap

- [ ] Symbols / numbers page and long-press alternates on the keyboard
- [ ] Case-preserving expansions (e.g. `Brb` → `Be right back`)
- [ ] Import / export shortcuts
- [ ] Per-shortcut enable toggle in the UI
- [ ] Themes (light/dark, key styling)
- [ ] Suggestion bar / inline autocomplete

## License

MIT — see [LICENSE](LICENSE).
