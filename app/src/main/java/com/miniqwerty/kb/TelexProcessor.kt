package com.miniqwerty.kb

/**
 * Stateless Vietnamese Telex input processing engine.
 *
 * Processes a raw character buffer and resolves it into the composed Vietnamese
 * output by applying vowel transformations and tone marking rules.
 *
 * ## Vowel Transformations
 * - aw → ă    aa → â    ee → ê
 * - oo → ô    ow → ơ    uw → ư
 * - dd → đ
 *
 * ## Tone Keys
 * - s → sắc (´)    f → huyền (`)    r → hỏi (̉)
 * - x → ngã (~)    j → nặng (.)     z → (reset / no tone)
 *
 * ## State Machine Rules
 * 1. Tone keys apply to the main vowel in the current composing span.
 * 2. Pressing the same tone key twice toggles the tone off (literal char).
 * 3. Pressing a different tone key replaces the current tone.
 * 4. The key 'z' resets the tone state (literalizes any pending tone).
 */
object TelexProcessor {

    // ── Vowel transform pairs ─────────────────────────────────────────────
    private val VOWEL_TRANSFORMS: Map<String, Char> = mapOf(
        "aw" to '\u0103',  // ă
        "aa" to '\u00E2',  // â
        "ee" to '\u00EA',  // ê
        "oo" to '\u00F4',  // ô
        "ow" to '\u01A1',  // ơ
        "uw" to '\u01B0',  // ư
        "dd" to '\u0111',  // đ
    )

    // ── Tone key set ──────────────────────────────────────────────────────
    private val TONE_KEYS: Set<Char> = setOf('s', 'f', 'r', 'x', 'j')

    // ── Tone mark lookup (tone char → precomposed vowel map) ──────────────
    private val TONE_MAP: Map<Char, Map<Char, Char>> = mapOf(
        's' to mapOf( // sắc
            'a' to 'á', 'ă' to '\u1EAF', 'â' to '\u1EA5',
            'e' to 'é', 'ê' to '\u1EBF',
            'i' to 'í',
            'o' to 'ó', 'ô' to '\u1ED1', 'ơ' to '\u1EDB',
            'u' to 'ú', 'ư' to '\u1EE9',
            'y' to 'ý',
        ),
        // Fix: proper precomposed tone+breve/circumflex chars
        'f' to mapOf( // huyền
            'a' to 'à', 'ă' to '\u1EB1', 'â' to '\u1EA7',
            'e' to 'è', 'ê' to '\u1EC1',
            'i' to 'ì',
            'o' to 'ò', 'ô' to '\u1ED3', 'ơ' to '\u1EDD',
            'u' to 'ù', 'ư' to '\u1EEB',
            'y' to 'ỳ',
        ),
        'r' to mapOf( // hỏi
            'a' to 'ả', 'ă' to '\u1EB3', 'â' to '\u1EA9',
            'e' to 'ẻ', 'ê' to '\u1EC3',
            'i' to 'ỉ',
            'o' to 'ỏ', 'ô' to '\u1ED5', 'ơ' to '\u1EDF',
            'u' to 'ủ', 'ư' to '\u1EED',
            'y' to 'ỷ',
        ),
        'x' to mapOf( // ngã
            'a' to 'ã', 'ă' to '\u1EB5', 'â' to '\u1EAB',
            'e' to 'ẽ', 'ê' to '\u1EC5',
            'i' to 'ĩ',
            'o' to 'õ', 'ô' to '\u1ED7', 'ơ' to '\u1EE1',
            'u' to 'ũ', 'ư' to '\u1EEF',
            'y' to 'ỹ',
        ),
        'j' to mapOf( // nặng
            'a' to 'ạ', 'ă' to '\u1EB7', 'â' to '\u1EAD',
            'e' to 'ẹ', 'ê' to '\u1EC7',
            'i' to 'ị',
            'o' to 'ọ', 'ô' to '\u1ED9', 'ơ' to '\u1EE3',
            'u' to 'ụ', 'ư' to '\u1EF1',
            'y' to 'ỵ',
        ),
    )

    // ── Uppercase tone map (built once) ───────────────────────────────────
    private val TONE_MAP_UPPER: Map<Char, Map<Char, Char>> = TONE_MAP.mapValues { (_, vowelMap) ->
        vowelMap.mapKeys { (k, _) -> k.uppercaseChar() }
            .mapValues { (_, v) -> v.uppercaseChar() }
    }

    // ── Character classifications ─────────────────────────────────────────
    private val BASE_VOWELS     = setOf('a', 'e', 'i', 'o', 'u', 'y')
    private val SPECIAL_VOWELS  = setOf('ă', 'â', 'ê', 'ô', 'ơ', 'ư')
    private val ALL_VOWELS      = BASE_VOWELS + SPECIAL_VOWELS

    // Characters that trigger an explicit commit and state reset.
    // Space and newline always commit. Standard sentence-terminating punctuation
    // also commits so that the Telex state machine is clean for the next word.
    private val COMMIT_CHARS: Set<Char> = setOf(
        ' ', '\n', '\t', '.', ',', '!', '?', ':', ';', '…',
    )

    /**
     * Resolves a raw character buffer into the fully composed Vietnamese display
     * string. This is the single entry point for the IME.
     *
     * @param raw  The full raw character buffer typed so far.
     * @return     The composed string ready for [InputConnection.setComposingText].
     */
    fun resolve(raw: String): String {
        if (raw.isEmpty()) return ""

        // 1. Split into content + trailing tone-key suffix
        val baseLen = raw.indexOfLast { it !in TONE_KEYS } + 1
        // Guard: if EVERY char is a tone key, there is no vowel to tone —
        // treat all as literal.
        if (baseLen == 0) return raw

        val content    = raw.substring(0, baseLen)
        val toneSuffix = raw.substring(baseLen)

        // 2. Apply left-to-right vowel transformations
        val base = applyVowelTransforms(content)

        if (toneSuffix.isEmpty()) return base

        // 3. If the base has no vowel, trailing tone keys are literal.
        if (!hasVowel(base)) {
            return base + toneSuffix
        }

        // 4. Process the tone-key suffix into a final tone + literal spill.
        val (finalTone, literals) = reduceToneSuffix(toneSuffix)

        return if (finalTone != null) {
            applyTone(base + literals, finalTone)
        } else {
            base + literals
        }
    }

    /**
     * Returns `true` when [char] should commit the current composing word
     * and reset internal state.
     */
    fun shouldCommit(char: Char): Boolean = char in COMMIT_CHARS

    // ── private helpers ───────────────────────────────────────────────────

    /** True when [s] contains at least one Vietnamese vowel character. */
    private fun hasVowel(s: String): Boolean =
        s.any { it.lowercaseChar() in ALL_VOWELS }

    /** Left-to-right greedy vowel-transformation pass. */
    private fun applyVowelTransforms(s: String): String {
        if (s.length < 2) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (i + 1 < s.length) {
                val key = s.substring(i, i + 2).lowercase()
                val replacement = VOWEL_TRANSFORMS[key]
                if (replacement != null) {
                    sb.append(if (s[i].isUpperCase()) replacement.uppercaseChar() else replacement)
                    i += 2
                    continue
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    /**
     * Reduces a string consisting exclusively of tone keys into:
     * - [finalTone]: the active tone (or null if toggled off)
     * - [literals]:  literal chars spilled from cancelled tones.
     *
     * Rules: same-tone twice → toggle off (char becomes literal);
     *        different tone → replace (old tone is discarded, no literal).
     */
    private data class ToneResult(val finalTone: Char?, val literals: String)

    private fun reduceToneSuffix(toneSuffix: String): ToneResult {
        var tone: Char? = null
        val literals = StringBuilder()

        for (ch in toneSuffix) {
            if (tone == null) {
                tone = ch
            } else if (tone == ch) {
                // Same tone → toggle off: the tone char becomes literal.
                literals.append(ch)
                tone = null
            } else {
                // Different tone → replace; previous tone is discarded.
                tone = ch
            }
        }
        return ToneResult(tone, literals.toString())
    }

    /**
     * Applies [tone] to the main vowel of [base] and returns the result.
     * If no vowel is found the string is returned unchanged.
     */
    private fun applyTone(base: String, tone: Char): String {
        val idx = findMainVowelIndex(base) ?: return base
        val vowel = base[idx]
        val isUpper = vowel.isUpperCase()
        val lookupKey = vowel.lowercaseChar()

        val toneMap = if (isUpper) TONE_MAP_UPPER[tone] else TONE_MAP[tone]
        val tonedVowel = toneMap?.get(lookupKey) ?: return base

        return buildString(base.length) {
            append(base, 0, idx)
            append(tonedVowel)
            append(base, idx + 1, base.length)
        }
    }

    /**
     * Locates the "main vowel" index in a Vietnamese string according to
     * standard orthographic tone-placement rules.
     *
     * Priority:
     *  1. Modified vowels (â ê ô ă ơ ư) always carry the tone.
     *  2. Triphthongs (3+ vowels): tone on the middle vowel.
     *  3. Diphthongs starting with o/u (oa, oe, ua, uê, uy, uơ):
     *     tone on the second vowel.
     *  4. Diphthongs ending with i/y/u/o (ai, ay, ao, au, ui, oi…):
     *     tone on the first vowel.
     *  5. Otherwise: first vowel.
     */
    private fun findMainVowelIndex(word: String): Int? {
        // Rule 1: special vowel
        for (i in word.indices) {
            if (word[i].lowercaseChar() in SPECIAL_VOWELS) return i
        }

        val positions = word.indices.filter {
            word[it].lowercaseChar() in ALL_VOWELS
        }
        if (positions.isEmpty()) return null
        if (positions.size == 1) return positions[0]

        val first  = word[positions[0]].lowercaseChar()
        val last   = word[positions.last()].lowercaseChar()

        // Rule 2: triphthong
        if (positions.size >= 3) return positions[positions.size - 2]

        // Rule 3: starts with o/u
        if (first == 'o' || first == 'u') return positions[1]

        // Rule 4: ends with i / y / u / o
        if (last == 'i' || last == 'y' || last == 'u' || last == 'o') return positions[0]

        // Rule 5: default
        return positions[0]
    }
}
