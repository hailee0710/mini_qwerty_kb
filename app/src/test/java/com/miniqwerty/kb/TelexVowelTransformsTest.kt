package com.miniqwerty.kb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vowel-transform coverage: every digraph, the triple digraph, their undo
 * (third-press) forms, and case handling.
 */
class TelexVowelTransformsTest {

    // ── Single digraphs ──────────────────────────────────────────────────

    @Test fun `each vowel transform maps to its vowel`() {
        assertEquals("ă", TelexProcessor.resolve("aw"))
        assertEquals("â", TelexProcessor.resolve("aa"))
        assertEquals("ê", TelexProcessor.resolve("ee"))
        assertEquals("ô", TelexProcessor.resolve("oo"))
        assertEquals("ơ", TelexProcessor.resolve("ow"))
        assertEquals("ư", TelexProcessor.resolve("uw"))
        assertEquals("đ", TelexProcessor.resolve("dd"))
        assertEquals("ươ", TelexProcessor.resolve("uow"))
    }

    @Test fun `transforms resolve inside words`() {
        assertEquals("cân", TelexProcessor.resolve("caan"))
        assertEquals("đê", TelexProcessor.resolve("ddee"))
        assertEquals("tôi", TelexProcessor.resolve("tooi"))
        assertEquals("mưa", TelexProcessor.resolve("muwa"))
        assertEquals("mơ", TelexProcessor.resolve("mow"))
        assertEquals("thương", TelexProcessor.resolve("thuowng"))
    }

    @Test fun `triple digraph is checked before the pair`() {
        // "uow" must become ươ, not u + ơ (pair "ow" would fire first).
        assertEquals("ươ", TelexProcessor.resolve("uow"))
        assertEquals("mươi", TelexProcessor.resolve("muowi"))
    }

    @Test fun `uow after q stays qu plus o`() {
        // No Vietnamese word starts with qư — quow resolves to quờ.
        assertEquals("quơ", TelexProcessor.resolve("quow"))
        assertEquals("quờ", TelexProcessor.resolve("quowf"))
    }

    @Test fun `no transform on non-matching pairs`() {
        assertEquals("ab", TelexProcessor.resolve("ab"))
        assertEquals("ba", TelexProcessor.resolve("ba"))
        assertEquals("ai", TelexProcessor.resolve("ai"))
    }

    // ── Undo: a third copy of the digraph's last letter ──────────────────

    @Test fun `third press undoes the transform`() {
        assertEquals("door", TelexProcessor.resolve("dooor"))
        assertEquals("good", TelexProcessor.resolve("goood"))
        assertEquals("uw", TelexProcessor.resolve("uww"))
        assertEquals("ow", TelexProcessor.resolve("oww"))
        assertEquals("uow", TelexProcessor.resolve("uoww"))
        assertEquals("aa", TelexProcessor.resolve("aaa"))
        assertEquals("ee", TelexProcessor.resolve("eee"))
        assertEquals("oo", TelexProcessor.resolve("ooo"))
    }

    @Test fun `undo keeps the rest of the word literal`() {
        assertEquals("good", TelexProcessor.resolve("goood"))
        // "caaan" → the third a undoes "aa" → "caan" (literal, not re-transformed).
        assertEquals("caan", TelexProcessor.resolve("caaan"))
    }

    @Test fun `trailing tone keys after an undo stay literal`() {
        assertEquals("doors", TelexProcessor.resolve("dooors"))
        assertEquals("goods", TelexProcessor.resolve("gooods"))
    }

    // ── Case handling ────────────────────────────────────────────────────

    @Test fun `uppercase transforms produce uppercase vowels`() {
        assertEquals("Ă", TelexProcessor.resolve("Aw"))
        assertEquals("Â", TelexProcessor.resolve("AA"))
        assertEquals("Đ", TelexProcessor.resolve("DD"))
        assertEquals("DOOR", TelexProcessor.resolve("DOOOR"))
        assertEquals("DOOR", TelexProcessor.resolve("dooor".uppercase()))
    }

    @Test fun `uppercase vowel tones via the uppercase tone map`() {
        assertEquals("Á", TelexProcessor.resolve("As"))
        assertEquals("Ế", TelexProcessor.resolve("Ês"))
        assertEquals("Được", TelexProcessor.resolve("Dduowcj"))
    }

    @Test fun `uppercase tone keys are consonants not tones`() {
        assertEquals("MAS", TelexProcessor.resolve("MAS"))
        // An already-toned vowel is not a recognized base vowel, so the
        // trailing tone key spills as a literal.
        assertEquals("Ếs", TelexProcessor.resolve("Ếs"))
    }
}
