# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android custom keyboard (IME) with a compact 3-row QWERTY layout and a built-in Vietnamese Telex input engine, including an English-aware "Smart Telex" mode backed by a word dictionary. Beyond the keyboard itself there is one settings screen (theme mode, haptics, key popup, Smart Telex, double-tap window), which is also the app's launcher entry point. minSdk 21, compileSdk/targetSdk 34, JDK 17.

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

Source files, each with one clear job:

- `app/src/main/java/com/miniqwerty/kb/MiniKeyboardIME.kt` — `InputMethodService` subclass. Owns the composing buffer and all interaction with the target app via `InputConnection`.
- `app/src/main/java/com/miniqwerty/kb/MiniKeyboardView.kt` — custom `View` that draws the keyboard on a `Canvas` and handles touch. Emits events through the `OnKeyActionListener` interface (defined in this file). No Android XML layouts — everything is drawn.
- `app/src/main/java/com/miniqwerty/kb/TelexProcessor.kt` — stateless `object`; pure text transformation, no Android dependencies. Unit-testable without instrumentation.
- `app/src/main/java/com/miniqwerty/kb/MainActivity.kt` — settings screen (plain `Activity`, no AppCompat): theme mode radio group, switches for haptics / key popup / Smart Telex, double-tap window slider, "enable keyboard" shortcut to `Settings.ACTION_INPUT_METHOD_SETTINGS`. Also the launcher entry point.
- `app/src/main/java/com/miniqwerty/kb/Prefs.kt` — shared preference keys, defaults, and constants shared by the IME and the settings screen (`miniqwerty_kb_prefs`).
- `app/src/main/assets/vi_words.txt` — Vietnamese word list for the Smart Telex dictionary check (one lowercase word per line). Generated from `tools/data/vi_50k.txt` with `python3 tools/gen_dict.py` (Vietnamese letters only — `f j w z` and foreign characters dropped, ~40k words).

### Composing flow

The IME keeps a raw character buffer (`rawBuffer`) for the current word. Every keystroke appends to it and calls `TelexProcessor.resolve()` on the **entire buffer** (the processor is stateless and re-resolves from scratch each time), then shows the result via `InputConnection.setComposingText()`. The resolved text is committed on space/return/`shouldCommit` punctuation (`. , ! ? : ; …` plus whitespace) and on explicit user actions (`onCursorMove`, numeric-layer `onDirectCharacter`) via `commitBuffer`, which commits unconditionally — editors that fail to report composing partial offsets must not lose the visible word. Only lifecycle commits (`onStartInputView`/`onFinishInputView`) are conditional on the editor still holding a composing region (`editorHasComposingRegion` probes `ExtractedText` partial offsets): if an app cleared the field while composing (e.g. chat send button), the stale buffer is dropped instead of re-inserted. Space-bar long-press is a cursor mode: horizontal drag moves the caret via `onCursorMove` (commits pending word first). Backspace removes the last raw character from the buffer rather than deleting from the editor; only when the buffer is empty does it delegate to `deleteSurroundingText`. `onReplaceCharacter` pops the last raw char and re-appends its replacement (double-tap); `onDirectCharacter` commits pending text and inserts the character directly (numeric layer). Numeric-layer double-tap uses `onReplaceDirectCharacter`: the first tap committed the digit directly, so the editor deletes it (`deleteSurroundingText`) and inserts the symbol.

Shift is latched (one character), not held: `MiniKeyboardView` uppercases the character before calling `onCharacter`, then auto-releases.

Quick double-tap on the same key replaces the last raw buffer character with the key's secondary character. The window is user-adjustable in settings (`Prefs.KEY_DOUBLE_TAP_MS`, 100–500 ms, default 200). Backspace repeats while held: initial delay 400 ms, then every 60 ms.

Hard keys also flow through the Telex pipeline: `onKeyDown` intercepts DEL/ENTER/SPACE and routes printable characters into `onCharacter`.

### Telex rules (in TelexProcessor)

Vowel transforms: `aw→ă aa→â ee→ê oo→ô ow→ơ uw→ư uow→ươ dd→đ` (triple digraphs checked before pairs; `uow` is not applied after `q` — no Vietnamese word starts `qư`, so `quowf` resolves to `quờ`). Tone keys: `s f r x j` (sắc/huyền/hỏi/ngã/nặng); `z` is not in the tone set. Same tone key twice toggles the tone off and spills the key as a literal; a different tone key replaces the previous one (no literal). Typing a third copy of a transform's last letter undoes the transform and makes the rest of the word literal: `dooor` → door, `goood` → good, `uww` → uw, `oww` → ow, `uoww` → uow, `forr` → for — trailing tone keys after an undo stay literal. Vietnamese never uses triple letters, so this cannot misfire. The gesture is this keyboard's equivalent of ibus-bamboo's Shift+Space restore-word. `findMainVowelIndex` implements Vietnamese orthographic tone-placement rules, in priority order: 1) special vowels — but in the `ươ` cluster the tone lands on `ơ` (`người`, `được`) — 2) triphthong middle, 3) gi-digraph (i is a consonant, tone on the following vowel: `gió`, `già`), 4) qu-digraph (u is a consonant: `quả`, `quá`), 5) oa/oe/uy with a closing consonant tone the second vowel (`toàn`, `hoàng`, `khoét`, `suýt` — modern style without a coda tones the first vowel: `hòa`, `khỏe`, `thúy`), 6) i/y/u/o-ending diphthong first vowel, 7) default first vowel.

### Smart Telex

`TelexProcessor.resolve(raw, smart, dict)` wraps the core Telex resolution with English-aware validation: when `smart` is on and the resolved output differs from the raw input, the output is checked against a Vietnamese syllable-shape parser (`isValidVietnameseSequence`, a backtracking parse over onset/nucleus/coda tables). If the shape is invalid, the raw input is returned unchanged — English words with impossible Vietnamese clusters (`cluster`, `good`, `pool`, `zoo`, `fix`) show literally while typing. When `dict` (a `Set<String>`) is also passed, a resolved word absent from the dictionary falls back to raw at commit time (`for` — `fỏ` is not a dictionary word — commits as `for`; `office` likewise). Output the user explicitly forced literal (an undo or a tone-key spill) is always honored, dictionary or not.

The IME calls `resolve` with `dict = null` from `updateComposingText` (shape validation applies live while composing) and with the loaded dictionary from `commitBuffer` / `commitPending` / `onSpace` (Gboard-style correction at commit). `MiniKeyboardIME.loadWordDict()` loads `assets/vi_words.txt` into a `HashSet` on a background thread; on failure the dictionary check is skipped and only shape validation applies. Every Vietnamese word and every typing prefix of one parses, so there is no flicker for Vietnamese input. Toggling Smart Telex off restores the exact legacy behavior.

Documented limitations (same shape as ibus-bamboo's auto-restore): `door` → `dổ`, `bus` → `bú`, `this` → `thí` still tone when typed literally, because the toned forms are real dictionary words and the raw sequences are identical (`door` and `dổ` are both d-o-o-r) — type the undo form instead (`dooor`, `buss`, `thiss`, `forr`). Words outside the top-50k list (e.g. `giễng`) fall back to raw at commit; Smart Telex off restores the legacy behavior. Bare Telex fragments commit raw when the resolved form is not a word (`uow` + space stays `uow`; `uowm` → `ươm` works).

### Keyboard layout

Defined as `List<List<KeyDef>>` literals in `MiniKeyboardView` — `letterKeys` and `numericKeys`, switched by `currentLayer`. Each key has a `primary` and optional `secondary` character: tap commits primary, quick double-tap replaces it with the secondary letter, swipe-down or long-press (350 ms) commits the secondary. Character keys without a secondary (vowels) have no secondary action.

Letters layer, 3 rows. Row 1 is 10 columns — `X(Q) W(?) E R T H(Y) U I(P) O ,(.)` — comma on top, dot below it. Row 2 is 9.5 units: `A S(Z) D F(C) G(V) N(B) J(K) M(L)` at 1 unit each plus ⌫ at 1.5. The row pins to row 1's 10-unit grid and centers itself — letters keep row 1's width exactly, a 0.25-unit margin remains on each side, and the slight offset against row 1 gives a natural staggered typing feel. The layout is QWERTY-familiar by design: `tools/layout_analyzer.py` optimizes effort + λ·displacement from each letter's QWERTY home (λ=0.5), so every key sits at or next to its QWERTY position and the 9 rarest letters (Q Y P Z C V B K L) become double-tap secondaries on the key nearest their QWERTY home. Tone keys X, S, F, R, J sit where Vietnamese Telex typists expect them. A, E, O, D sit on keys without secondaries so the Telex same-key digraphs `aa ee oo dd` stay typeable via quick double-press. Row 3 uses fractional `widthUnits` spans (shift 1.5, `123` 1, space 5, return 1.5) and is 75% of the standard row height (`effectiveRows` = row count − 1 + 0.75, so both 3-row layers measure 2.75 row-units; the clipboard layer uses the letters-layer count to keep the window height stable). `123` switches to the numeric layer; `ABC` switches back.

Numeric layer: row 1 is 10 digits with no secondaries — `1 2 3 4 5 6 7 8 9 0`. Row 2 is 11 units of frequent symbols plus ⌫: `@ ! % : ) - ? = / ]` with double-tap secondaries `~ # $ & ( _ + ; ' [` (`(` under `)`, `[` under `]`, `&` under `:`). Row 3 spans `ABC` (1.5), space (6), `📋` (1), `.` (1, `,` double-tap), `⏎` (1.5) — 11 total units so the dot matches the row-2 key width. Numeric-layer characters commit directly via `onDirectCharacter` (bypass the Telex buffer). Rare symbols (`` ` \ | " < > { } ^ * ``) are intentionally unavailable on the numeric layer.

### Theme and sizing

The view draws an opaque background (no transparent IME window) in a light or dark palette. Theme mode is user-selectable via `Prefs.KEY_THEME_MODE` (system / light / dark, default system); `MiniKeyboardView.resolveDarkTheme()` resolves the pref and `refreshTheme()` reapplies colors. The IME calls `refreshTheme()` on configuration change and every `onStartInputView`, so settings changes apply to the next keyboard window. A drag handle strip (14 dp) at the top resizes the keyboard: dragging changes `rowHeightDp` (clamped 30–75 dp, default 46), persisted in `Prefs.KEY_ROW_HEIGHT_DP`, applied on the next `onMeasure`.

Key presses vibrate via `performHapticFeedback` (`KEYBOARD_TAP`, `LONG_PRESS` on long-press fire) when `Prefs.KEY_HAPTIC_ENABLED` is on (default). While a character key is held, `drawKeyPopup` draws a bubble above the key (below for row 1) showing the typed character, gated by `Prefs.KEY_KEY_POPUP_ENABLED` (default on); long-press dismisses it. Both toggles are re-read in `refreshTheme()` so settings changes apply live.

### IME wiring

`app/src/main/AndroidManifest.xml` declares the service with `BIND_INPUT_METHOD` permission and `MainActivity` as the MAIN/LAUNCHER entry point; the application carries a launcher icon (`mipmap/ic_launcher`, adaptive + legacy densities) and `@string/app_name`. `app/src/main/res/xml/method.xml` defines two subtypes: `en_US` and `vi_VN` (Telex). To test on a device: install, then enable the keyboard in Settings → System → Languages & input (the settings screen has a shortcut button).
