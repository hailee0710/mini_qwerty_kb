# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android custom keyboard (IME) with a compact 3-row QWERTY layout and a built-in Vietnamese Telex input engine. No app UI beyond the keyboard — the only Activity-free service is the input method itself. minSdk 21, compileSdk/targetSdk 34, JDK 17.

Toolchain pins: AGP 8.2.2, Kotlin 1.9.22, Gradle 8.5. Only external dependency is `androidx.core:core-ktx`.

## Build

The `gradlew` script is **not committed** — only `gradle/wrapper/gradle-wrapper.properties` is. CI generates the wrapper (`gradle wrapper --gradle-version 8.5`) before building; do the same locally if `./gradlew` is missing.

```bash
gradle wrapper --gradle-version 8.5   # one-time, if ./gradlew missing
./gradlew assembleDebug               # build debug APK
./gradlew lint                        # Android lint
```

Output APK: `app/build/outputs/apk/debug/`. There are no tests in this repo. CI (`app/.github/workflows/build.yml`) builds `assembleDebug` on push/PR and uploads the APK artifact.

## Architecture

Three source files, each with one clear job:

- `app/src/main/java/com/miniqwerty/kb/MiniKeyboardIME.kt` — `InputMethodService` subclass. Owns the composing buffer and all interaction with the target app via `InputConnection`.
- `app/src/main/java/com/miniqwerty/kb/MiniKeyboardView.kt` — custom `View` that draws the keyboard on a `Canvas` and handles touch. Emits events through the `OnKeyActionListener` interface (defined in this file). No Android XML layouts — everything is drawn.
- `app/src/main/java/com/miniqwerty/kb/TelexProcessor.kt` — stateless `object`; pure text transformation, no Android dependencies. Unit-testable without instrumentation.

### Composing flow

The IME keeps a raw character buffer (`rawBuffer`) for the current word. Every keystroke appends to it and calls `TelexProcessor.resolve()` on the **entire buffer** (the processor is stateless and re-resolves from scratch each time), then shows the result via `InputConnection.setComposingText()`. The resolved text is only committed on space/return/`shouldCommit` punctuation (`. , ! ? : ; …` plus whitespace), or when the input session ends (`onFinishInputView` commits pending text). Backspace removes the last raw character from the buffer rather than deleting from the editor; only when the buffer is empty does it delegate to `deleteSurroundingText`. `onReplaceCharacter` pops the last raw char and re-appends its replacement (double-tap); `onDirectCharacter` commits pending text and inserts the character directly (numeric layer).

Shift is latched (one character), not held: `MiniKeyboardView` uppercases the character before calling `onCharacter`, then auto-releases.

Quick double-tap (≤250 ms) on the same key replaces the last raw buffer character with the key's secondary letter (only letter secondaries — punctuation secondaries stay on long-press/swipe so Telex transforms like `oo→ô` survive fast typing). Backspace repeats while held: initial delay 400 ms, then every 60 ms.

Hard keys also flow through the Telex pipeline: `onKeyDown` intercepts DEL/ENTER/SPACE and routes printable characters into `onCharacter`.

### Telex rules (in TelexProcessor)

Vowel transforms: `aw→ă aa→â ee→ê oo→ô ow→ơ uw→ư dd→đ`. Tone keys: `s f r x j` (sắc/huyền/hỏi/ngã/nặng); `z` is not in the tone set. Same tone key twice toggles the tone off and spills the key as a literal; a different tone key replaces the previous one (no literal). `findMainVowelIndex` implements Vietnamese orthographic tone-placement rules (special vowels > triphthong middle > o/u-leading diphthong second vowel > i/y/u/o-ending diphthong first vowel).

### Keyboard layout

Defined as `List<List<KeyDef>>` literals in `MiniKeyboardView` — `letterKeys` and `numericKeys`, switched by `currentLayer`. Each key has a `primary` and optional `secondary` character: tap commits primary, quick double-tap replaces it with the secondary letter, swipe-down or long-press (350 ms) commits the secondary. Character keys without a secondary (vowels) have no secondary action.

Letters layer, 3 rows. Row 1 is 10 columns — `W(Q) E R F T(G) Y(P) U(,) I O(.) ⌫` — so both R and the Telex tone key F keep top slots; Q, G, P stay secondaries. Row 2 is 9 columns: `A S(Z) X(D) C(V) H(B) J(N) M(K) L(?) !(.)`. Telex tone keys F, X, J sit on the primary (top) slot — D, N are their secondaries. Row 3 uses fractional `widthUnits` spans (shift 1.5, `123` 1, space 5, return 1.5). `123` switches to the numeric layer; `ABC` switches back.

Numeric layer: row 1 is digits 1–9; row 2 is denser (10 columns) with `0 - / : ; ( ) $ & @`; row 3 spans `#+=` (2), `,` (1), `.` (1), space (2), `ABC` (2), backspace (2). Numeric-layer characters commit directly via `onDirectCharacter` (bypass the Telex buffer); `#+=` is a dead key (no symbol layer yet).

### IME wiring

`app/src/main/AndroidManifest.xml` declares the service with `BIND_INPUT_METHOD` permission. `app/src/main/res/xml/method.xml` defines two subtypes: `en_US` and `vi_VN` (Telex). To test on a device: install, then enable the keyboard in Settings → System → Languages & input.
