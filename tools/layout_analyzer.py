#!/usr/bin/env python3
"""Vietnamese Telex keyboard layout analyzer.

Scores the MiniKeyboardView letters layer against a word-frequency corpus.
For every word it simulates the Telex keystroke sequence (digraph expansion,
tone keys), then measures raw tap count, thumb reach, same-thumb repetition,
hand alternation, and double-tap overhead.

The search space is the full layout design: which letter sits on which key
(17 primary keys), and which 9 keys carry a double-tap secondary. Moves are
letter-key swaps and role swaps (promote/demote a letter between single-tap
and double-tap). Constraints: Telex tone keys (s f r x j) always single-tap;
the same-key digraph letters (a e o d, for aa ee oo dd) sit on keys without
secondaries so a quick double-press repeats instead of replacing.

The layout is parsed straight out of MiniKeyboardView.kt, so this script
stays in sync with the real keyboard as long as the letterKeys literal
keeps the same shape.

Usage:
    python3 tools/layout_analyzer.py [corpus_path]
"""

import re
import random
import sys
import math
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
VIEW_FILE = REPO / "app/src/main/java/com/miniqwerty/kb/MiniKeyboardView.kt"
CORPUS = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "tools/data/vi_50k.txt"

# ─────────────────────────────────────────────────────────────────────────────
# Telex model (mirrors TelexProcessor.kt)
# ─────────────────────────────────────────────────────────────────────────────

VOWEL_DIGRAPHS = {
    "ă": "aw",  # ă
    "â": "aa",  # â
    "ê": "ee",  # ê
    "ô": "oo",  # ô
    "ơ": "ow",  # ơ
    "ư": "uw",  # ư
    "đ": "dd",  # đ
}

TONE_MAP = {
    "s": {  # sắc
        "a": "á", "ă": "ắ", "â": "ấ",
        "e": "é", "ê": "ế", "i": "í",
        "o": "ó", "ô": "ố", "ơ": "ớ",
        "u": "ú", "ư": "ứ", "y": "ý",
    },
    "f": {  # huyền
        "a": "à", "ă": "ằ", "â": "ầ",
        "e": "è", "ê": "ề", "i": "ì",
        "o": "ò", "ô": "ồ", "ơ": "ờ",
        "u": "ù", "ư": "ừ", "y": "ỳ",
    },
    "r": {  # hỏi
        "a": "ả", "ă": "ẳ", "â": "ẩ",
        "e": "ẻ", "ê": "ể", "i": "ỉ",
        "o": "ỏ", "ô": "ổ", "ơ": "ở",
        "u": "ủ", "ư": "ử", "y": "ỷ",
    },
    "x": {  # ngã
        "a": "ã", "ă": "ẵ", "â": "ẫ",
        "e": "ẽ", "ê": "ễ", "i": "ĩ",
        "o": "õ", "ô": "ỗ", "ơ": "ỡ",
        "u": "ũ", "ư": "ữ", "y": "ỹ",
    },
    "j": {  # nặng
        "a": "ạ", "ă": "ặ", "â": "ậ",
        "e": "ẹ", "ê": "ệ", "i": "ị",
        "o": "ọ", "ô": "ộ", "ơ": "ợ",
        "u": "ụ", "ư": "ự", "y": "ỵ",
    },
}

# toned char -> (base vowel, tone key)
TONED_TO_KEY = {
    toned: (base, tone) for tone, table in TONE_MAP.items() for base, toned in table.items()
}

LETTER_RE = re.compile(r"^[a-zàáảãạăằắẳẵặ"
                       r"âầấẩẫậèéẻẽẹ"
                       r"êềếểễệìíỉĩị"
                       r"òóỏõọôồốổỗộ"
                       r"ơờớởỡợùúủũụ"
                       r"ưừứửữựỳỷỹỵđ]+$")


def word_to_keystrokes(word: str) -> str:
    """Expand one Vietnamese word into its Telex keystroke string.

    Tone keys are appended at the end of the word (the common way to type).
    """
    out = []
    tones = []
    for ch in word:
        if ch in TONED_TO_KEY:
            base, tone = TONED_TO_KEY[ch]
            out.append(VOWEL_DIGRAPHS.get(base, base))
            tones.append(tone)
        else:
            out.append(VOWEL_DIGRAPHS.get(ch, ch))
    return "".join(out) + "".join(tones)


# ─────────────────────────────────────────────────────────────────────────────
# Layout parsing (reads MiniKeyboardView.kt directly)
# ─────────────────────────────────────────────────────────────────────────────

def parse_layout(path: Path):
    """Parse the letterKeys literal.

    Returns:
      keys  — list of dicts {id, row, x} for the 17 letter keys,
      assign — initial letter -> key id (26 letters; secondaries share their key),
      roles — initial letter -> 'P'|'S'.
    """
    text = path.read_text(encoding="utf-8")
    start = text.index("private val letterKeys")
    end = text.index("private val numericKeys")
    block = text[start:end]

    rows = []
    current = None
    key_re = re.compile(r'KeyDef\("([^"]+)",\s*(?:"([^"]+)"|null)(?:,[^)]*)?\)')
    for line in block.splitlines():
        if "listOf(" in line:
            current = []
            rows.append(current)
        m = key_re.search(line)
        if m and current is not None:
            current.append((m.group(1), m.group(2), "keyType =" in line))

    rows = [r for r in rows if r]  # drop the empty row from the outer listOf(
    widths = [len(r) for r in rows[:2]]

    keys = []
    assign = {}
    roles = {}
    for row_idx, row in enumerate(rows[:2]):
        for col, (primary, secondary, is_func) in enumerate(row):
            if is_func:
                continue
            x_units = (col + 0.5) / widths[row_idx] * 10.0  # 10 = full keyboard width
            if primary.lower() in "abcdefghijklmnopqrstuvwxyz":
                key_id = len(keys)
                keys.append({"id": key_id, "row": row_idx, "x": x_units})
                assign[primary.lower()] = key_id
                roles[primary.lower()] = "P"
            if secondary and secondary.lower() in "abcdefghijklmnopqrstuvwxyz":
                # The secondary letter shares its host key's id.
                host = next(k for k in keys if k["row"] == row_idx and k["x"] == x_units)
                assign[secondary.lower()] = host["id"]
                roles[secondary.lower()] = "S"
    assert len(keys) == 17, f"expected 17 letter keys, got {len(keys)}"
    assert len(assign) == 26, f"expected 26 letters, got {len(assign)}"
    return keys, assign, roles


# ─────────────────────────────────────────────────────────────────────────────
# Corpus statistics
# ─────────────────────────────────────────────────────────────────────────────

def load_corpus(path: Path):
    words = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) != 2:
            continue
        word, count = parts[0].lower(), int(parts[1])
        if LETTER_RE.match(word):
            words[word] = words.get(word, 0) + count
    return words


def keystroke_stats(words):
    """Weighted unigram / bigram / tone / space stats over expanded keystrokes."""
    unigrams = {}
    bigrams = {}
    tones = {}
    total_words = 0
    for word, freq in words.items():
        keys = word_to_keystrokes(word)
        total_words += freq
        for ch in keys:
            unigrams[ch] = unigrams.get(ch, 0) + freq
        for a, b in zip(keys, keys[1:]):
            bigrams[(a, b)] = bigrams.get((a, b), 0) + freq
        for ch in "sfrxj":
            n = keys.count(ch)
            if n:
                tones[ch] = tones.get(ch, 0) + freq * n
    return unigrams, bigrams, tones, total_words


# ─────────────────────────────────────────────────────────────────────────────
# Effort model (two-thumb typing)
# ─────────────────────────────────────────────────────────────────────────────

TAP_BASE = 1.0
REACH_PER_UNIT = 0.10        # per "column unit" (10 units = keyboard width)
VERTICAL_UNITS = 1.2         # row height relative to column unit
SAME_THUMB_PAIR = 0.45       # penalty when the same thumb presses consecutive keys
HAND_SWITCH_BONUS = -0.08    # bonus for alternating thumbs
DOUBLE_TAP_REPEAT = 0.20     # second press of a double-tap is a same-key repeat
SPACE_COST = 0.5

# Thumb home positions in (x units, row units); row 1 is y=0, row 2 is y=1.
LEFT_HOME = (4.0, 1.0)
RIGHT_HOME = (6.0, 1.0)

LETTERS = sorted("abcdefghijklmnopqrstuvwxyz")
TONE_KEYS = frozenset("sfrxj")
# Letters that form Telex same-key digraphs (dd, aa, ee, oo): their keys must
# have no secondary, or a double-tap would replace instead of repeat.
CLEAN_KEYS = frozenset("adeo")


def key_center(key):
    return (key["x"], 0.0 if key["row"] == 0 else 1.0)


def dist(a, b):
    dx = a[0] - b[0]
    dy = (a[1] - b[1]) * VERTICAL_UNITS
    return math.hypot(dx, dy)


def thumb_of_key(key):
    c = key_center(key)
    return "L" if dist(c, LEFT_HOME) <= dist(c, RIGHT_HOME) else "R"


def sec_key_ids(assign, roles):
    return {assign[ch] for ch in roles if roles[ch] == "S"}


def legal(assign, roles):
    """True when constraints hold: tone keys single-tap, digraph letters on
    keys without secondaries, every key hosts at most one secondary."""
    sec = sec_key_ids(assign, roles)
    if len(sec) != 9:
        return False  # 9 secondary letters must sit on 9 distinct keys
    for ch in TONE_KEYS:
        if roles[ch] != "P":
            return False
    for ch in CLEAN_KEYS:
        if roles[ch] != "P" or assign[ch] in sec:
            return False
    return True


class Eval:
    """Score one layout (assign + roles) against precomputed corpus stats."""

    def __init__(self, keys, stats):
        self.keys = keys
        self.by_id = {k["id"]: k for k in keys}
        self.unigrams, self.bigrams, self.tones, self.total_words = stats
        self.cache = {}

    def _costs(self, assign, roles):
        key_of = lambda ch: self.by_id[assign[ch]]  # noqa: E731
        centers = {ch: key_center(key_of(ch)) for ch in assign}
        thumbs = {ch: thumb_of_key(key_of(ch)) for ch in assign}

        letter_taps = {}
        for ch in assign:
            c = centers[ch]
            reach = min(dist(c, LEFT_HOME), dist(c, RIGHT_HOME))
            if roles[ch] == "S":
                # Double-tap: two presses on the same key.
                letter_taps[ch] = 2.0 * (TAP_BASE + REACH_PER_UNIT * reach) + DOUBLE_TAP_REPEAT
            else:
                letter_taps[ch] = TAP_BASE + REACH_PER_UNIT * reach

        pair_cost = {}
        for (a, b), freq in self.bigrams.items():
            if assign[a] == assign[b]:
                # Same key pressed twice (dd, aa, ee, oo, or a letter and its secondary).
                pair_cost[(a, b)] = SAME_THUMB_PAIR
            elif thumbs[a] == thumbs[b]:
                pair_cost[(a, b)] = SAME_THUMB_PAIR + REACH_PER_UNIT * dist(centers[a], centers[b])
            else:
                pair_cost[(a, b)] = HAND_SWITCH_BONUS + REACH_PER_UNIT * dist(centers[a], centers[b])
        return letter_taps, pair_cost

    def score(self, assign, roles):
        """Return dict of metrics for one layout."""
        t = (tuple(sorted(assign.items())), tuple(sorted((ch, roles[ch]) for ch in roles)))
        if t in self.cache:
            return self.cache[t]
        letter_taps, pair_cost = self._costs(assign, roles)

        effort = 0.0
        taps = 0.0
        same_thumb_pairs = 0.0
        alt_pairs = 0.0
        total_pairs = 0.0
        secondary_taps = 0.0
        letter_taps_total = 0.0

        for ch, freq in self.unigrams.items():
            effort += freq * letter_taps[ch]
            letter_taps_total += freq
            n_taps = 2.0 if roles[ch] == "S" else 1.0
            taps += freq * n_taps
            if roles[ch] == "S":
                secondary_taps += freq

        thumbs = {ch: thumb_of_key(self.by_id[assign[ch]]) for ch in assign}
        for (a, b), freq in self.bigrams.items():
            pc = pair_cost[(a, b)]
            effort += freq * pc
            total_pairs += freq
            if thumbs[a] == thumbs[b]:
                same_thumb_pairs += freq
            else:
                alt_pairs += freq

        # Space after every word; assume the other thumb takes it.
        effort += self.total_words * SPACE_COST
        taps += self.total_words

        result = {
            "effort": effort,
            "effort_per_100": 100.0 * effort / letter_taps_total,
            "taps_per_100": 100.0 * taps / letter_taps_total,
            "secondary_share": secondary_taps / letter_taps_total,
            "same_thumb_rate": same_thumb_pairs / total_pairs if total_pairs else 0.0,
            "alternation_rate": alt_pairs / total_pairs if total_pairs else 0.0,
            "tone_share": sum(self.tones.values()) / letter_taps_total,
            "words": self.total_words,
        }
        self.cache[t] = result
        return result


# ─────────────────────────────────────────────────────────────────────────────
# Layout search
# ─────────────────────────────────────────────────────────────────────────────

def swap_complete(assign, roles, a, b):
    """Swap two letters wholesale: each takes the other's (key, role)."""
    assign[a], assign[b] = assign[b], assign[a]
    roles[a], roles[b] = roles[b], roles[a]


def hill_climb(assign0, roles0, evaluator, max_iter=200):
    """Greedy complete letter swaps until no swap improves.
    Returns (assign, roles, steps)."""
    assign = dict(assign0)
    roles = dict(roles0)
    best_score = evaluator.score(assign, roles)["effort"]
    steps = []
    improved = True
    while improved and len(steps) < max_iter:
        improved = False
        best_move = None
        letters = list(assign)
        for i in range(len(letters)):
            for j in range(i + 1, len(letters)):
                a, b = letters[i], letters[j]
                swap_complete(assign, roles, a, b)
                if legal(assign, roles):
                    s = evaluator.score(assign, roles)["effort"]
                    if s < best_score - 1e-12:
                        best_score = s
                        best_move = (a, b)
                swap_complete(assign, roles, a, b)
        if best_move:
            a, b = best_move
            swap_complete(assign, roles, a, b)
            steps.append((a, b, best_score))
            improved = True
    return assign, roles, steps


def random_layout(seed, keys):
    """Random valid layout: tone keys primary, digraph letters on secondary-free
    keys, 9 double-tap letters chosen from the rest."""
    rng = random.Random(seed)
    key_ids = [k["id"] for k in keys]
    rng.shuffle(key_ids)
    sec_ids = key_ids[:9]
    non_sec = key_ids[9:]  # 8 keys without secondaries

    # Digraph letters need 4 of the 8 secondary-free keys.
    clean_l = sorted(CLEAN_KEYS)
    rng.shuffle(non_sec)
    assign = dict(zip(clean_l, non_sec[:4]))
    roles = {ch: "P" for ch in clean_l}

    # Tone keys: primary anywhere (secondary-carrying keys allowed).
    tone_l = sorted(TONE_KEYS)
    rng.shuffle(tone_l)
    used = set(assign.values())
    free = [k for k in key_ids if k not in used]
    rng.shuffle(free)
    for ch in tone_l:
        assign[ch] = free.pop()
        roles[ch] = "P"

    # Remaining 17 letters: 8 more primary on the 8 free keys, 9 secondary on
    # the 9 secondary-carrying keys.
    rest = [ch for ch in LETTERS if ch not in assign]
    rng.shuffle(rest)
    used = set(assign.values())
    free = [k for k in key_ids if k not in used]
    rng.shuffle(free)
    for ch in rest[:8]:
        assign[ch] = free.pop()
        roles[ch] = "P"
    for ch in rest[8:]:
        assign[ch] = sec_ids.pop()
        roles[ch] = "S"
    return assign, roles


def all_single_swaps(assign0, roles0, evaluator, limit=None):
    """Evaluate every legal complete letter swap; return sorted
    (effort, a, b, assign, roles)."""
    results = []
    assign = dict(assign0)
    roles = dict(roles0)
    letters = list(assign)
    for i in range(len(letters)):
        for j in range(i + 1, len(letters)):
            a, b = letters[i], letters[j]
            swap_complete(assign, roles, a, b)
            if legal(assign, roles):
                results.append((evaluator.score(assign, roles)["effort"], a, b,
                                dict(assign), dict(roles)))
            swap_complete(assign, roles, a, b)
    results.sort()
    return results[:limit] if limit else results


# ─────────────────────────────────────────────────────────────────────────────
# Reporting
# ─────────────────────────────────────────────────────────────────────────────

def describe(keys, assign, roles):
    """Human-readable key map, like the keyboard rows."""
    by_id = {k["id"]: k for k in keys}
    grid = [[" " for _ in range(10)] for _ in range(2)]
    sec_by_col = [{}, {}]
    for ch in LETTERS:
        k = by_id[assign[ch]]
        col = int(k["x"] - 0.5)
        if roles[ch] == "P":
            grid[k["row"]][col] = ch.upper()
        else:
            sec_by_col[k["row"]][col] = ch.upper()
    row1 = " ".join(grid[0])
    row2 = " ".join(grid[1])
    sec1 = "".join(sec_by_col[0].get(c, "") for c in range(10))
    sec2 = "".join(sec_by_col[1].get(c, "") for c in range(10))
    return f"{row1}  /{sec1}\n{row2}  /{sec2}"


def diff_letters(assign_a, roles_a, assign_b, roles_b):
    changes = []
    for ch in LETTERS:
        if assign_a[ch] != assign_b[ch] or roles_a[ch] != roles_b[ch]:
            changes.append(ch)
    return changes


def line(assign, roles, evaluator, label=""):
    m = evaluator.score(assign, roles)
    print(f"{label:26s} effort {m['effort_per_100']:7.2f}   taps {m['taps_per_100']:7.2f}   "
          f"dt {100*m['secondary_share']:5.1f}%   same-thumb {100*m['same_thumb_rate']:5.1f}%   "
          f"alt {100*m['alternation_rate']:5.1f}%")
    return m


def main():
    keys, assign0, roles0 = parse_layout(VIEW_FILE)
    words = load_corpus(CORPUS)
    stats = keystroke_stats(words)
    evaluator = Eval(keys, stats)

    assign = dict(assign0)
    roles = dict(roles0)
    assert legal(assign, roles), "current layout violates constraints"

    cur = evaluator.score(assign, roles)

    print("=" * 78)
    print("VIETNAMESE TELEX LAYOUT ANALYSIS")
    print(f"corpus: {CORPUS.name} ({len(words)} word types, {sum(words.values())} tokens)")
    print("=" * 78)

    print("\n─ current layout ─────────────────────────────────────────────")
    print(describe(keys, assign, roles))
    line(assign, roles, evaluator, "current")

    # ── all single letter-key swaps, ranked ──────────────────────────────────
    print("\n─ all single letter swaps from current, ranked ───────────────")
    singles = all_single_swaps(assign, roles, evaluator)
    for rank, (effort, a, b, trial_a, trial_r) in enumerate(singles[:20], 1):
        m = evaluator.score(trial_a, trial_r)
        pct = 100 * (effort - cur["effort"]) / cur["effort"]
        print(f"  {rank:2d}. {a.upper()}<->{b.upper():4s} effort {m['effort_per_100']:7.2f} "
              f"({pct:+.1f}%)  taps {m['taps_per_100']:7.2f}  dt {100*m['secondary_share']:4.1f}%")
    print(f"  ({len(singles)} swaps total)")

    # ── greedy improvement path from current ─────────────────────────────────
    print("\n─ greedy improvement path from current ───────────────────────")
    ga, gr, steps = hill_climb(assign, roles, evaluator)
    replay_a, replay_r = dict(assign), dict(roles)
    base_effort = cur["effort"]
    for a, b, effort in steps:
        swap_complete(replay_a, replay_r, a, b)
        m = evaluator.score(replay_a, replay_r)
        print(f"  {a.upper()}<->{b.upper():4s} → effort {m['effort_per_100']:7.2f} "
              f"({100*(effort-base_effort)/base_effort:+.1f}% cum)  dt {100*m['secondary_share']:4.1f}%")
    print("\n  greedy endpoint:")
    print(describe(keys, ga, gr))
    line(ga, gr, evaluator, "greedy endpoint")
    moved = diff_letters(assign, roles, ga, gr)
    print(f"  moved: {', '.join(sorted(c.upper() for c in moved))}")

    # ── hill-climb with restarts ─────────────────────────────────────────────
    print("\n─ hill-climb, 20 restarts (all constraints on) ───────────────")
    best = None
    for seed in range(20):
        if seed == 0:
            start_a, start_r = dict(assign), dict(roles)
        else:
            start_a, start_r = random_layout(seed, keys)
        ca, cr, _ = hill_climb(start_a, start_r, evaluator)
        e = evaluator.score(ca, cr)["effort"]
        if best is None or e < best[0]:
            best = (e, ca, cr, "from current" if seed == 0 else f"restart {seed}")
    effort, ba, br, src = best
    print(f"best ({src}):")
    print(describe(keys, ba, br))
    line(ba, br, evaluator, "best")
    moved = diff_letters(assign, roles, ba, br)
    print(f"moved: {', '.join(sorted(c.upper() for c in moved))}")
    assert legal(ba, br), "best layout violates constraints"
    print("constraint check: clean")


if __name__ == "__main__":
    main()
