package com.miniqwerty.kb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises `findMainVowelIndex` through [TelexProcessor.resolve] (smart off,
 * so shape validation cannot mask placement mistakes). Every case appends the
 * tone key to a raw word and asserts the orthographically correct toning.
 *
 * Input convention: this keyboard spells special-vowel nuclei with explicit
 * digraphs — iê = "iee", yê = "yee", uô = "uoo", uyê = "uyee", uâ = "uaa",
 * ươ = "uow". The transform produces the special vowel, so tone placement
 * falls through to Rule 1 (special vowel carries the tone). Plain "ie"/"uo"
 * spellings are NOT nuclei here (they are English fallbacks), which is pinned
 * at the bottom of this file.
 */
class TelexTonePlacementTest {

    /** Resolve word + trailing tone key, pure core (no smart validation). */
    private fun tone(word: String, t: Char): String =
        TelexProcessor.resolve("$word$t")

    // ── Rule 1: special vowels (â ê ô ă ơ ư) always carry the tone ───────

    @Test fun `special vowels carry the tone`() {
        assertEquals("ấn", tone("aan", 's'))   // â
        assertEquals("ến", tone("een", 's'))   // ê
        assertEquals("ống", tone("oong", 's')) // ô
        assertEquals("ắ", tone("aw", 's'))     // ă
        assertEquals("ớ", tone("ow", 's'))     // ơ
        assertEquals("ứ", tone("uw", 's'))     // ư
    }

    @Test fun `uâ nucleus via uaa carries the tone on the a`() {
        assertEquals("tuần", tone("tuaan", 'f'))
        assertEquals("quất", tone("quaat", 's'))
    }

    // ── Rule 1 exception: the ươ cluster tones the ơ ─────────────────────

    @Test fun `uow cluster tones the second vowel`() {
        assertEquals("ướng", tone("uowng", 's'))   // ươ + ng
        assertEquals("được", tone("dduowc", 'j'))  // đ + ươc, nặng on ơ
        assertEquals("người", tone("nguowi", 'f')) // ươ + i, huyền on ơ
        assertEquals("thướng", tone("thuowng", 's'))
        // Late w (after the closing consonant): "truotw" → ươt still tones ơ.
        assertEquals("trượt", tone("truotw", 'j'))
    }

    // ── Rule 2: triphthongs tone the middle vowel ────────────────────────

    @Test fun `triphthongs tone the middle vowel`() {
        assertEquals("khoái", tone("khoai", 's'))  // oa + i
        assertEquals("khuỷa", tone("khuya", 'r'))  // u + ya
        assertEquals("uối", tone("uooi", 's'))     // u + ô + i
    }

    @Test fun `ieu and yeu nuclei tone the special e`() {
        assertEquals("iếu", tone("ieeu", 's')) // i + ê + u
        assertEquals("yếu", tone("yeeu", 's')) // y + ê + u
    }

    // ── Rule 3: gi- digraph tones the following vowel ────────────────────

    @Test fun `gi digraph passes the tone to the next vowel`() {
        assertEquals("gió", tone("gio", 's'))
        assertEquals("già", tone("gia", 'f'))
        assertEquals("giá", tone("gia", 's'))
    }

    // ── Rule 4: qu- digraph tones the following vowel ────────────────────

    @Test fun `qu digraph passes the tone to the next vowel`() {
        assertEquals("quá", tone("qua", 's'))
        assertEquals("quà", tone("qua", 'f'))
        assertEquals("quả", tone("qua", 'r'))
    }

    // ── Rule 5: oa/oe/uy with a closing consonant tone the second vowel ──

    @Test fun `oa oe uy with a coda tone the second vowel`() {
        assertEquals("toàn", tone("toan", 'f'))
        assertEquals("hoàng", tone("hoang", 'f'))
        assertEquals("khoét", tone("khoet", 's'))
        assertEquals("suýt", tone("suyt", 's'))
        assertEquals("toát", tone("toat", 's'))
    }

    @Test fun `oa oe uy without a coda tone the first vowel`() {
        assertEquals("hòa", tone("hoa", 'f'))
        assertEquals("khỏe", tone("khoe", 'r'))
        assertEquals("thúy", tone("thuy", 's'))
    }

    // ── Rule 6: i/y/u/o-ending diphthongs tone the first vowel ───────────

    @Test fun `i y u o ending diphthongs tone the first vowel`() {
        assertEquals("ái", tone("ai", 's'))
        assertEquals("bụi", tone("bui", 'j'))
        assertEquals("mía", tone("mia", 's')) // ia → first i
        assertEquals("múa", tone("mua", 's')) // ua → first u (plain, no coda)
        assertEquals("ói", tone("oi", 's'))
        assertEquals("bói", tone("boi", 's'))
    }

    // ── Rule 7: default first vowel ──────────────────────────────────────

    @Test fun `default tones the first vowel`() {
        assertEquals("bán", tone("ban", 's'))
        assertEquals("cóng", tone("cong", 's'))
        assertEquals("kén", tone("ken", 's'))
        assertEquals("sáng", tone("sang", 's'))
    }

    // ── Special-vowel nuclei spelled via explicit digraphs ───────────────

    @Test fun `iee nucleus tones the special e`() {
        assertEquals("tiếng", tone("tieeng", 's'))
        assertEquals("liền", tone("lieen", 'f'))
        assertEquals("biển", tone("bieen", 'r'))
        assertEquals("nghiếng", tone("nghieeng", 's'))
    }

    @Test fun `yee nucleus tones the special e`() {
        assertEquals("yến", tone("yeen", 's'))
        assertEquals("yền", tone("yeen", 'f'))
    }

    @Test fun `uoo nucleus tones the special o`() {
        assertEquals("uống", tone("uoong", 's'))
        assertEquals("muốn", tone("muoon", 's'))
        assertEquals("thuộc", tone("thuooc", 'j'))
    }

    @Test fun `uyee nucleus tones the special e`() {
        assertEquals("thuyền", tone("thuyeen", 'f'))
        assertEquals("chuyến", tone("chuyeen", 's'))
        assertEquals("quyền", tone("quyeen", 'f'))
        assertEquals("tuyện", tone("tuyeen", 'j'))
    }

    // ── Plain ie/ye/uo are NOT nuclei — pin the fallback ─────────────────

    @Test fun `plain ie ye uo spellings do not form nuclei`() {
        // No "ie"→"iê" transform: plain vowels fall back to the default
        // first-vowel rule. These spellings are English words, not Vi nuclei.
        assertEquals("tíeng", tone("tieng", 's'))
        assertEquals("ýen", tone("yen", 's'))
        assertEquals("úong", tone("uong", 's'))
    }

    // ── Single vowel, no tone to place ───────────────────────────────────

    @Test fun `single vowel takes the tone directly`() {
        assertEquals("á", tone("a", 's'))
        assertEquals("ò", tone("o", 'f'))
        assertEquals("ỷ", tone("y", 'r'))
    }
}
