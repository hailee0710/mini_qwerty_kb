package com.miniqwerty.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the stateless Telex engine — no Android dependencies,
 * runs on the local JVM via `./gradlew :app:testDebugUnitTest`.
 */
class TelexProcessorTest {

    // Small stand-in for the real dictionary: only the words the assertions
    // rely on. Tests that need the fallback pass a dict missing the toned form.
    private val dict: Set<String> = setOf(
        "má", "máy", "mát", "mứa", "ươm", "trượt",
    )

    // ── Mid-word tone keys (the "masy" bug) ──────────────────────────────

    @Test fun `tone key before a vowel tones the word - masy to may`() {
        assertEquals("máy", TelexProcessor.resolve("masy", smart = true, dict = dict))
    }

    @Test fun `tone key before a coda consonant tones the word - mast to mat`() {
        assertEquals("mát", TelexProcessor.resolve("mast", smart = true, dict = dict))
    }

    @Test fun `mid-word tone works with smart off - pure Telex`() {
        assertEquals("máy", TelexProcessor.resolve("masy"))
        assertEquals("mát", TelexProcessor.resolve("mast"))
    }

    @Test fun `mid-word tone applies to the main vowel - mas to ma`() {
        assertEquals("má", TelexProcessor.resolve("mas", smart = true, dict = dict))
    }

    @Test fun `trailing tone key still works - mays to may`() {
        assertEquals("máy", TelexProcessor.resolve("mays", smart = true, dict = dict))
    }

    @Test fun `embedded and trailing tone keys reduce together`() {
        // m-a-s-y-s: mid 's' tones 'a', trailing 's' toggles it off → "mays".
        assertEquals("mays", TelexProcessor.resolve("masys"))
    }

    // ── Onset tone keys stay literal ─────────────────────────────────────

    @Test fun `tone key before any vowel is an onset consonant - sad`() {
        assertEquals("sad", TelexProcessor.resolve("sad", smart = true, dict = dict))
        assertEquals("sun", TelexProcessor.resolve("sun", smart = true, dict = dict))
    }

    @Test fun `trailing tone key on an unparseable syllable falls back raw - fix`() {
        assertEquals("fix", TelexProcessor.resolve("fix", smart = true, dict = dict))
    }

    // ── English words with mid-word tone keys stay literal via dict ─────

    @Test fun `base falls back to raw at commit - shape alone is not enough`() {
        // "base" → "báe" passes the shape check (ba + e are both syllables),
        // so it needs the dictionary. Without it the toned form leaks through.
        assertEquals("báe", TelexProcessor.resolve("base", smart = true, dict = null))
        assertEquals("base", TelexProcessor.resolve("base", smart = true, dict = dict))
    }

    @Test fun `case and resume fall back to raw with a dictionary`() {
        assertEquals("case", TelexProcessor.resolve("case", smart = true, dict = dict))
        assertEquals("resume", TelexProcessor.resolve("resume", smart = true, dict = dict))
    }

    @Test fun `doubled tone-key letters are consonants - office message carry`() {
        // "ff" in office, "ss" in message, "rr" in carry must not tone or toggle.
        assertEquals("office", TelexProcessor.resolve("office", smart = true, dict = dict))
        assertEquals("message", TelexProcessor.resolve("message", smart = true, dict = dict))
        assertEquals("carry", TelexProcessor.resolve("carry", smart = true, dict = dict))
        assertEquals("coffee", TelexProcessor.resolve("coffee", smart = true, dict = dict))
    }

    // ── hasEmbeddedTone: IME dictionary-gating helper ────────────────────

    @Test fun `hasEmbeddedTone detects mid-word tone keys only`() {
        assertTrue(TelexProcessor.hasEmbeddedTone("masy"))
        assertTrue(TelexProcessor.hasEmbeddedTone("mast"))
        assertTrue(TelexProcessor.hasEmbeddedTone("base"))
        assertTrue(TelexProcessor.hasEmbeddedTone("lantern"))
        assertFalse(TelexProcessor.hasEmbeddedTone("mays"))   // trailing only
        assertFalse(TelexProcessor.hasEmbeddedTone("giengs")) // trailing only
        assertFalse(TelexProcessor.hasEmbeddedTone("sad"))    // onset consonant
        assertFalse(TelexProcessor.hasEmbeddedTone("fix"))    // trailing only
        assertFalse(TelexProcessor.hasEmbeddedTone("office")) // doubled ff
        assertFalse(TelexProcessor.hasEmbeddedTone("carry"))  // doubled rr
    }

    // ── Undo / literal override preserved ────────────────────────────────

    @Test fun `triple-letter undo still forces a literal word`() {
        assertEquals("door", TelexProcessor.resolve("dooor", smart = true, dict = dict))
        assertEquals("good", TelexProcessor.resolve("goood", smart = true, dict = dict))
        assertEquals("uow", TelexProcessor.resolve("uoww", smart = true, dict = dict))
    }

    @Test fun `same tone twice still spills the tone key literally`() {
        assertEquals("for", TelexProcessor.resolve("forr", smart = true, dict = dict))
    }

    // ── Vowel transforms + smart dict fallback ───────────────────────────

    @Test fun `vowel transforms still resolve after mid-word tones`() {
        // m-u-w-a-s: uw → ư, tone sắc on ư → mứa.
        assertEquals("mứa", TelexProcessor.resolve("muwas", smart = true, dict = dict))
        assertEquals("ươm", TelexProcessor.resolve("uowm", smart = true, dict = dict))
    }

    @Test fun `bare fragment not in dict commits raw - uow`() {
        assertEquals("uow", TelexProcessor.resolve("uow", smart = true, dict = dict))
    }

    @Test fun `late-w word survives smart and dict - truotwj`() {
        // "truotwj" → trượt: the trailing w converts uo → ươ mid-word.
        assertEquals("trượt", TelexProcessor.resolve("truotwj", smart = true, dict = dict))
        // Without the dictionary the resolved form falls back to the raw keys.
        assertEquals("truotwj", TelexProcessor.resolve("truotwj", smart = true, dict = emptySet()))
    }

    @Test fun `toned form missing from dict falls back to raw - for`() {
        assertEquals("for", TelexProcessor.resolve("for", smart = true, dict = dict))
    }

    // ── Other English words with Telex-letter shapes ─────────────────────

    @Test fun `shape-invalid English words stay literal - good pool zoo`() {
        assertEquals("good", TelexProcessor.resolve("good", smart = true, dict = dict))
        assertEquals("pool", TelexProcessor.resolve("pool", smart = true, dict = dict))
        assertEquals("zoo", TelexProcessor.resolve("zoo", smart = true, dict = dict))
    }
}
