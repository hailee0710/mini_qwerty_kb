# Mini Qwerty Keyboard

A compact 3-row QWERTY Android keyboard (IME) with a built-in Vietnamese **Telex** input engine and an English-aware **Smart Telex** mode backed by a ~40k-word dictionary.

**Zero permissions. Zero network.** The keyboard reads only what you type and sends it nowhere.

- [Tiếng Việt](README.vi.md)

## Features

- **Smart Telex** — type Vietnamese with Telex, English words type literally. A dictionary + syllable-shape validator keeps English looking English while Vietnamese composes with live accents.
- **Full Telex support** — all 6 tone keys, every vowel digraph, correct orthographic tone placement, mid-word tone keys, triple-letter undo.
- **Compact 3-row QWERTY** — the 9 rarest letters are quick double-tap secondaries on their QWERTY home keys; tone keys `X S F R J` sit where Telex typists expect them.
- **Numeric/symbol layer** with symbol double-tap secondaries.
- **Light/dark/system theme**, haptics toggle, adjustable keyboard height (drag handle), adjustable double-tap window.
- **Tap accuracy** — nearest-key hit targets with thumb-offset correction, press feedback on key-down, and multi-touch handling so fast typing never drops a key.
- **No special permissions** — nothing to grant, nothing to leak.

## Install

Download the latest `app-release.apk` from the [Releases](https://github.com/hailee0710/mini_qwerty_kb/releases) page and open it on your device.

Then enable the keyboard: **Settings → System → Languages & input → On-screen keyboard → Mini Qwerty** (the app's settings screen has a one-tap shortcut).

## Telex input

Tone keys:

| Key | Tone | Example |
|-----|------|---------|
| `s` | sắc | `masy` → `máy` |
| `f` | huyền | `maf` → `mà` |
| `r` | hỏi | `mar` → `mả` |
| `x` | ngã | `max` → `mã` |
| `j` | nặng | `maj` → `mạ` |

Vowel digraphs (same key twice, or the digraph sequence):

```
aa → â    ee → ê    oo → ô    aw → ă
ow → ơ    uw → ư    dd → đ    uow → ươ
```

Special nuclei: `iê` = `iee`, `uô` = `uoo`, `yê` = `yee`, `uâ` = `uaa`, `uyê` = `uyee`, `ươ` = `uow`.

Type the digraph's last letter a third time to undo it — the word becomes literal:
`dooor` → `door`, `goood` → `good`, `uww` → `uw`, `uoww` → `uow`, `forr` → `for`.

Same tone key twice toggles the tone off; a different tone key replaces the previous one.

## Smart Telex

With Smart Telex on, a resolved Telex output is checked against Vietnamese syllable shape. Impossible shapes fall back to the raw input, so English words like `cluster`, `good`, `pool` type literally. At commit time the result is also checked against a ~40k Vietnamese word list.

Typing quirks it can't fix (same as ibus-bamboo's auto-restore): `door`, `bus`, `this` still tone because the toned form is a real dictionary word. Use the undo form instead: `dooor`, `buss`, `thiss`, `forr`.

## Keyboard layout

- **Row 1:** `X(Q) W(?) E R T H(Y) U I(P) O ,(.)` — comma top, dot below.
- **Row 2:** `A S(Z) D F(C) G(V) N(B) J(K) M(L)` + backspace.
- **Row 3:** shift, `123`, space, return.
- **Numeric layer:** digits, `@ ! % : ) - ? = / ]` with double-tap secondaries `~ # $ & ( _ + ; ' [`; plus `.` and `📋` (clipboard).

Quick double-tap on a key replaces the last character with the key's secondary. Long-press (350 ms) or a deliberate swipe-down commits the secondary. Double-tap Shift for caps lock (⇪). Backspace repeats while held. Fast typing is safe: overlapping taps (the next finger landing before the previous lifts) still register both keys, and a thumb roll on a tap won't fire a swipe or a double-tap.

## Settings

- Theme: system / light / dark
- Haptics on/off
- Smart Telex on/off
- Double-tap window (100–500 ms)

## Build

Prerequisites: JDK 17, Android SDK, Gradle 8.5.

```bash
gradle wrapper --gradle-version 8.5   # if ./gradlew missing (not committed)
./gradlew assembleDebug               # debug APK
./gradlew :app:testDebugUnitTest      # unit tests (77 tests: Telex, tone, vowels, Smart Telex, backspace sim)
./gradlew bundleRelease               # release AAB for publishing
```

Release signing reads `miniqwerty.*` properties from `~/.gradle/gradle.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without them the release build is unsigned, so plain clones still build.

Output APKs: `app/build/outputs/apk/`, AAB: `app/build/outputs/bundle/release/`.

## Privacy

No `uses-permission` entries in the manifest. No internet permission, no telemetry, no analytics. Keystrokes never leave the device.

## License

[MIT](LICENSE)
