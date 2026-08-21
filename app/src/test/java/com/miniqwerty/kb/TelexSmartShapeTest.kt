package com.miniqwerty.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart Telex coverage: syllable-shape validation, dictionary fallback,
 * forced-literal honoring, and commit-character classification.
 */
class TelexSmartShapeTest {

    // ── Shape validation (smart = true, no dict) ─────────────────────────

    @Test fun `valid Vietnamese words pass shape validation`() {
        assertEquals("người", TelexProcessor.resolve("nguowif", smart = true))
        assertEquals("được", TelexProcessor.resolve("dduowcj", smart = true))
        assertEquals("thương", TelexProcessor.resolve("thuowng", smart = true))
        assertEquals("cân", TelexProcessor.resolve("caan", smart = true))
    }

    @Test fun `impossible Vietnamese clusters fall back to raw`() {
        assertEquals("cluster", TelexProcessor.resolve("cluster", smart = true))
        assertEquals("good", TelexProcessor.resolve("good", smart = true))
        assertEquals("pool", TelexProcessor.resolve("pool", smart = true))
        assertEquals("zoo", TelexProcessor.resolve("zoo", smart = true))
        assertEquals("fix", TelexProcessor.resolve("fix", smart = true))
    }

    @Test fun `typing prefixes of Vietnamese words parse without flicker`() {
        assertEquals("ngu", TelexProcessor.resolve("ngu", smart = true))
        assertEquals("ngư", TelexProcessor.resolve("nguw", smart = true))
        assertEquals("đư", TelexProcessor.resolve("dduw", smart = true))
    }

    @Test fun `standalone consonant word passes shape and dict`() {
        // "dd" → đ — the only Vietnamese word with no vowel. The lone onset
        // must pass shape validation or smart mode falls back to "dd".
        assertEquals("đ", TelexProcessor.resolve("dd", smart = true))
        assertEquals("đ", TelexProcessor.resolve("dd", smart = true, dict = setOf("đ")))
    }

    @Test fun `onset plus coda without a vowel passes shape and dict`() {
        // "ddc" → "đc" — the texting contraction of "được". Like "đ" it has
        // no nucleus; an onset with a trailing coda must pass shape or smart
        // mode falls back to the raw "ddc".
        assertEquals("đc", TelexProcessor.resolve("ddc", smart = true))
        assertEquals("đc", TelexProcessor.resolve("ddc", smart = true, dict = setOf("đc")))
    }

    // ── Dictionary fallback ──────────────────────────────────────────────

    @Test fun `resolved word missing from the dict falls back to raw`() {
        // Shape passes (ba + e are both syllables) but the dict rejects it.
        assertEquals("base", TelexProcessor.resolve("base", smart = true, dict = emptySet()))
        assertEquals("base", TelexProcessor.resolve("base", smart = true, dict = setOf("máy")))
        // Trailing-tone word: raw keystrokes come back at commit.
        assertEquals("giengs", TelexProcessor.resolve("giengs", smart = true, dict = emptySet()))
    }

    @Test fun `resolved word present in the dict survives`() {
        assertEquals("máy", TelexProcessor.resolve("masy", smart = true, dict = setOf("máy")))
        assertEquals("máy", TelexProcessor.resolve("mays", smart = true, dict = setOf("máy")))
        // ê needs the "ee" digraph (gi + ee + ng): "giengs" → "giéng" (plain e).
        assertEquals("giếng", TelexProcessor.resolve("gieengs", smart = true, dict = setOf("giếng")))
    }

    @Test fun `forced literal is honored even when the dict rejects it`() {
        // Undo / same-tone-twice forms are deliberate — dict never overrides.
        assertEquals("door", TelexProcessor.resolve("dooor", smart = true, dict = emptySet()))
        assertEquals("for", TelexProcessor.resolve("forr", smart = true, dict = emptySet()))
        assertEquals("good", TelexProcessor.resolve("goood", smart = true, dict = emptySet()))
    }

    @Test fun `smart off ignores both shape and dict`() {
        assertEquals("báe", TelexProcessor.resolve("base", smart = false, dict = emptySet()))
        assertEquals("máy", TelexProcessor.resolve("masy", smart = false, dict = emptySet()))
    }

    // ── Commit characters ────────────────────────────────────────────────

    @Test fun `whitespace and punctuation commit`() {
        assertTrue(TelexProcessor.shouldCommit(' '))
        assertTrue(TelexProcessor.shouldCommit('\n'))
        assertTrue(TelexProcessor.shouldCommit('\t'))
        assertTrue(TelexProcessor.shouldCommit('.'))
        assertTrue(TelexProcessor.shouldCommit(','))
        assertTrue(TelexProcessor.shouldCommit('!'))
        assertTrue(TelexProcessor.shouldCommit('?'))
        assertTrue(TelexProcessor.shouldCommit(':'))
        assertTrue(TelexProcessor.shouldCommit(';'))
        assertTrue(TelexProcessor.shouldCommit('…'))
    }

    @Test fun `letters digits and tone keys do not commit`() {
        assertFalse(TelexProcessor.shouldCommit('a'))
        assertFalse(TelexProcessor.shouldCommit('9'))
        assertFalse(TelexProcessor.shouldCommit('s'))
        assertFalse(TelexProcessor.shouldCommit('-'))
        assertFalse(TelexProcessor.shouldCommit('_'))
    }
}
