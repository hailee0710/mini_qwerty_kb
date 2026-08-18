package com.miniqwerty.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulates the MiniKeyboardIME composing state machine — the raw buffer, the
 * `composingShowsRaw` flag, and the `onBackspace` branch — to verify the
 * "backspace does not redo the tone" fix across many words.
 *
 * The simulation mirrors MiniKeyboardIME exactly, minus the InputConnection:
 * every keystroke appends to the raw buffer and re-resolves through
 * TelexProcessor (shape-only, dict = null), and backspace either shows the raw
 * prefix (when the previous display was the literal buffer) or re-resolves.
 */
class TelexBackspaceTest {

    private class Sim {
        val raw = StringBuilder()
        var composingShowsRaw = false
        val displays = mutableListOf<String>()

        fun type(ch: Char) {
            raw.append(ch)
            updateComposing()
        }

        fun backspace() {
            if (raw.isEmpty()) return
            raw.deleteCharAt(raw.lastIndex)
            if (composingShowsRaw) {
                // Buffer was displayed literally — keep the raw prefix, do not
                // re-apply the tone the popped character had undone.
                if (raw.isEmpty()) {
                    displays.add("")
                    composingShowsRaw = false
                } else {
                    displays.add(raw.toString())
                }
            } else {
                updateComposing()
            }
        }

        private fun updateComposing() {
            if (raw.isEmpty()) {
                displays.add("")
                composingShowsRaw = false
                return
            }
            val resolved = TelexProcessor.resolve(raw.toString(), smart = true, dict = null)
            composingShowsRaw = resolved == raw.toString()
            displays.add(resolved)
        }
    }

    private fun simulate(word: String): List<String> {
        val s = Sim()
        word.forEach { s.type(it) }
        repeat(word.length) { s.backspace() }
        return s.displays
    }

    @Test
    fun reportedLanternCase() {
        val seq = simulate("lantern")
        // Typing: "lanter" shows the false tone, "lantern" falls back literal.
        assertEquals("lảnte", seq[5])
        assertEquals("lantern", seq[6])
        // Backspace must NOT redo the tone.
        assertEquals("lanter", seq[7])
        assertEquals("lante", seq[8])
        assertEquals("lant", seq[9])
        assertEquals("lan", seq[10])
        assertEquals("la", seq[11])
        assertEquals("l", seq[12])
        assertEquals("", seq[13])
    }

    @Test
    fun englishWordsBackspaceStaysLiteral() {
        val words = listOf(
            "lantern", "cluster", "pool", "zoo", "fix", "office", "question",
            "world", "school", "month", "here", "there", "thing", "think",
            "have", "some", "their", "would", "could", "house", "water",
            "years", "really", "never", "after", "other", "about", "people", "time"
        )
        // Words whose full-word display stays toned while typing are the
        // documented Smart Telex limitation (their toned form is shape-valid,
        // e.g. "this" → "thí", "their" → "thểi"); the commit-time dict check
        // restores the English word. For words that DO fall back to literal
        // live, the fix's contract must hold.
        val nonLiteral = words.filter { simulate(it)[it.length - 1] != it }
        System.out.println("Stays toned live (dict corrects at commit): $nonLiteral")

        for (w in words) {
            val seq = simulate(w)
            val typeLen = w.length
            val fullDisplay = seq[typeLen - 1]
            if (fullDisplay == w) {
                // The guarantee under test: once the display is the literal
                // word, every backspace shows the exact raw prefix — never a
                // re-toned form.
                for (k in 1..w.length) {
                    assertEquals(
                        "$w backspace $k",
                        w.substring(0, w.length - k),
                        seq[typeLen - 1 + k]
                    )
                }
            }
        }
    }

    @Test
    fun vietnameseStillResolvesOnBackspace() {
        // "hòa" — tone key 'f' applies, backspace removes it cleanly.
        val hoaf = simulate("hoaf")
        assertEquals("hòa", hoaf[3])
        assertEquals("hoa", hoaf[4])
        assertEquals("ho", hoaf[5])

        // "không" is typed as "khong" — no tone keys, stays literal.
        val khong = simulate("khong")
        assertEquals("khong", khong[4])
        assertEquals("khon", khong[5])

        // "aa" → "â", backspace → "a".
        val aa = simulate("aa")
        assertEquals("â", aa[1])
        assertEquals("a", aa[2])

        // "tooi" → "tôi", backspace → "tô" → "to" (correct Vietnamese prefix).
        val tooi = simulate("tooi")
        assertEquals("tôi", tooi[3])
        assertEquals("tô", tooi[4])
        assertEquals("to", tooi[5])
    }

    @Test
    fun backspaceFromTonedPrefixRemovesToneNotRedo() {
        // "lanter" typed alone: the tone IS displayed ("lảnte", shape passes
        // as "lan"+"te"); backspacing removes the tone key and the display
        // becomes the raw prefix — the tone is dropped, not reapplied.
        // typeLen is 6 (l a n t e r); backspaces follow at seq[6..11].
        val seq = simulate("lanter")
        assertEquals("lảnte", seq[5])
        assertEquals("lante", seq[6])
        // And at every later step it is the raw prefix.
        assertEquals("lant", seq[7])
        assertEquals("lan", seq[8])
        assertEquals("la", seq[9])
        assertEquals("l", seq[10])
        assertEquals("", seq[11])
    }
}
