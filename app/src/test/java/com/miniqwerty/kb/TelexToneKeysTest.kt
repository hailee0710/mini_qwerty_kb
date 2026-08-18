package com.miniqwerty.kb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tone-key coverage: the full vowel × tone mark matrix (validates the
 * hand-written precomposed-character map), suffix reduction rules (toggle,
 * replace, spill), and the no-vowel / all-tone-key edge cases.
 */
class TelexToneKeysTest {

    private fun tone(vowel: Char, t: Char): String =
        TelexProcessor.resolve("$vowel$t")

    // ── Full tone matrix: every base vowel × every tone key ──────────────

    @Test fun `sack tone matrix`() {
        assertEquals("á", tone('a', 's')); assertEquals("ắ", tone('ă', 's'))
        assertEquals("ấ", tone('â', 's')); assertEquals("é", tone('e', 's'))
        assertEquals("ế", tone('ê', 's')); assertEquals("í", tone('i', 's'))
        assertEquals("ó", tone('o', 's')); assertEquals("ố", tone('ô', 's'))
        assertEquals("ớ", tone('ơ', 's')); assertEquals("ú", tone('u', 's'))
        assertEquals("ứ", tone('ư', 's')); assertEquals("ý", tone('y', 's'))
    }

    @Test fun `huyen tone matrix`() {
        assertEquals("à", tone('a', 'f')); assertEquals("ằ", tone('ă', 'f'))
        assertEquals("ầ", tone('â', 'f')); assertEquals("è", tone('e', 'f'))
        assertEquals("ề", tone('ê', 'f')); assertEquals("ì", tone('i', 'f'))
        assertEquals("ò", tone('o', 'f')); assertEquals("ồ", tone('ô', 'f'))
        assertEquals("ờ", tone('ơ', 'f')); assertEquals("ù", tone('u', 'f'))
        assertEquals("ừ", tone('ư', 'f')); assertEquals("ỳ", tone('y', 'f'))
    }

    @Test fun `hoi tone matrix`() {
        assertEquals("ả", tone('a', 'r')); assertEquals("ẳ", tone('ă', 'r'))
        assertEquals("ẩ", tone('â', 'r')); assertEquals("ẻ", tone('e', 'r'))
        assertEquals("ể", tone('ê', 'r')); assertEquals("ỉ", tone('i', 'r'))
        assertEquals("ỏ", tone('o', 'r')); assertEquals("ổ", tone('ô', 'r'))
        assertEquals("ở", tone('ơ', 'r')); assertEquals("ủ", tone('u', 'r'))
        assertEquals("ử", tone('ư', 'r')); assertEquals("ỷ", tone('y', 'r'))
    }

    @Test fun `nga tone matrix`() {
        assertEquals("ã", tone('a', 'x')); assertEquals("ẵ", tone('ă', 'x'))
        assertEquals("ẫ", tone('â', 'x')); assertEquals("ẽ", tone('e', 'x'))
        assertEquals("ễ", tone('ê', 'x')); assertEquals("ĩ", tone('i', 'x'))
        assertEquals("õ", tone('o', 'x')); assertEquals("ỗ", tone('ô', 'x'))
        assertEquals("ỡ", tone('ơ', 'x')); assertEquals("ũ", tone('u', 'x'))
        assertEquals("ữ", tone('ư', 'x')); assertEquals("ỹ", tone('y', 'x'))
    }

    @Test fun `nang tone matrix`() {
        assertEquals("ạ", tone('a', 'j')); assertEquals("ặ", tone('ă', 'j'))
        assertEquals("ậ", tone('â', 'j')); assertEquals("ẹ", tone('e', 'j'))
        assertEquals("ệ", tone('ê', 'j')); assertEquals("ị", tone('i', 'j'))
        assertEquals("ọ", tone('o', 'j')); assertEquals("ộ", tone('ô', 'j'))
        assertEquals("ợ", tone('ơ', 'j')); assertEquals("ụ", tone('u', 'j'))
        assertEquals("ự", tone('ư', 'j')); assertEquals("ỵ", tone('y', 'j'))
    }

    // ── Tone on the main vowel of a word ─────────────────────────────────

    @Test fun `tone applies to the main vowel of a word`() {
        assertEquals("máy", TelexProcessor.resolve("mays"))
        assertEquals("toàn", TelexProcessor.resolve("toanf"))
    }

    // ── Suffix reduction: same-tone twice toggles off ────────────────────

    @Test fun `same tone twice toggles off and spills the key`() {
        assertEquals("mays", TelexProcessor.resolve("mayss"))
        assertEquals("for", TelexProcessor.resolve("forr"))
        assertEquals("ans", TelexProcessor.resolve("anss"))
    }

    @Test fun `different tone key replaces the previous tone`() {
        assertEquals("mà", TelexProcessor.resolve("masf"))  // s then f
        assertEquals("máy", TelexProcessor.resolve("mayfs")) // f then s
        assertEquals("mả", TelexProcessor.resolve("masfr")) // s, f, r
    }

    @Test fun `long tone runs reduce left to right`() {
        assertEquals("má", TelexProcessor.resolve("masfs")) // s f s → s
        assertEquals("mayf", TelexProcessor.resolve("maysff")) // s f f → toggle spill
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test fun `tone key with no vowel is literal`() {
        assertEquals("bs", TelexProcessor.resolve("bs"))
        assertEquals("bcx", TelexProcessor.resolve("bcx"))
    }

    @Test fun `all tone keys resolve literally`() {
        assertEquals("sfr", TelexProcessor.resolve("sfr"))
        assertEquals("sjx", TelexProcessor.resolve("sjx"))
    }

    @Test fun `z is not a tone key`() {
        assertEquals("az", TelexProcessor.resolve("az"))
        // z is a plain onset letter; the oo transform still fires.
        assertEquals("zô", TelexProcessor.resolve("zoo"))
    }
}
