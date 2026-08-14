#!/usr/bin/env python3
"""Generate the IME suggestion assets: word list and bigram table.

Two outputs, written to app/src/main/assets/ (packaged automatically by AGP):

vi_words.txt   — top Vietnamese words with corpus frequency, one per line:
                 word<TAB>freq, sorted by (-freq, word). Built from
                 tools/data/vi_50k.txt (the same corpus the layout analyzer
                 consumes; committed at 3a40460, no license file).

vi_bigrams.txt — adjacent word pairs with counts, one per line:
                 prev<TAB>next<TAB>count, sorted by (prev, -count). Built from
                 a real Vietnamese sentence corpus (default: Leipzig Corpora
                 news 2022, 1M sentences, CC BY 4.0 —
                 https://wortschatz.uni-leipzig.de/en/download/Vietnamese).
                 Downloaded at dev time only; the generated asset is
                 committed, builds never touch the network.

Both assets are consumed by SuggestionEngine.kt (accent-insensitive prefix
matching and next-word prediction). Verify the license of any corpus you
point --bigram-source at before committing the derived asset.

Usage:
    python3 tools/generate_wordlist.py [--bigram-source URL|PATH] [--top N] [--top-bigrams N]
"""

import argparse
import collections
import re
import tarfile
import tempfile
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WORDLIST_SRC = REPO / "tools/data/vi_50k.txt"
ASSETS_DIR = REPO / "app/src/main/assets"
DEFAULT_BIGRAM_SOURCE = "https://downloads.wortschatz-leipzig.de/corpora/vie_news_2022_1M.tar.gz"

# Letters only (after lowercase): no digits, punctuation, whitespace.
# Vietnamese letters incl. diacritics are all in \w's letter category.
TOKEN_RE = re.compile(r"[^\W\d_]+")


def tokenize(text: str):
    for token in text.split():
        t = token.lower()
        if TOKEN_RE.fullmatch(t):
            yield t


def build_wordlist(top: int) -> None:
    """Filter/dedupe vi_50k.txt and emit the frequency-sorted word asset."""
    counts: dict[str, int] = {}
    with open(WORDLIST_SRC, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) != 2:
                continue
            word, freq = parts
            word = word.lower()
            if not TOKEN_RE.fullmatch(word):
                continue
            try:
                freq = int(freq)
            except ValueError:
                continue
            # Same word appears with multiple counts in the source — keep max.
            counts[word] = max(counts.get(word, 0), freq)

    ordered = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:top]
    out = ASSETS_DIR / "vi_words.txt"
    with open(out, "w", encoding="utf-8") as f:
        for word, freq in ordered:
            f.write(f"{word}\t{freq}\n")
    print(f"vi_words.txt: {len(ordered)} words (max freq {ordered[0][1]})")


def fetch_sentences(bigram_source: str) -> Path:
    """Return a local plain-text sentences file for the given URL or path."""
    if Path(bigram_source).is_file():
        return Path(bigram_source)

    print(f"downloading {bigram_source} ...")
    with tempfile.TemporaryDirectory() as tmp:
        archive = Path(tmp) / "corpus.tar.gz"
        urllib.request.urlretrieve(bigram_source, archive)
        with tarfile.open(archive, "r:gz") as tar:
            sentences = [m for m in tar.getmembers() if m.name.endswith("-sentences.txt")]
            if not sentences:
                raise SystemExit("no *-sentences.txt member found in corpus archive")
            tar.extract(sentences[0], tmp)
            extracted = Path(tmp) / sentences[0].name
            # Copy out before the temp dir disappears.
            dest = REPO / "tools/data/corpus" / sentences[0].name
            dest.parent.mkdir(parents=True, exist_ok=True)
            with open(extracted, encoding="utf-8") as src, open(dest, "w", encoding="utf-8") as dst:
                dst.write(src.read())
            return dest


def build_bigrams(sentences_path: Path, wordlist: set[str], top_bigrams: int) -> None:
    """Count adjacent word pairs across sentences, emit top pairs per prev."""
    pair_counts: collections.Counter = collections.Counter()
    with open(sentences_path, encoding="utf-8") as f:
        for line in f:
            prev = None
            for token in tokenize(line):
                if prev is not None and prev in wordlist and token in wordlist:
                    pair_counts[(prev, token)] += 1
                prev = token

    # Group by prev, keep top pairs per prev, then flatten frequency-first.
    per_prev: dict[str, list[tuple[str, int]]] = {}
    for (prev, nxt), count in pair_counts.items():
        per_prev.setdefault(prev, []).append((nxt, count))
    flat = []
    for prev, pairs in per_prev.items():
        pairs.sort(key=lambda nc: (-nc[1], nc[0]))
        flat.extend((prev, nxt, count) for nxt, count in pairs)
    # Global cap by pair frequency — keeps the asset ~2-4MB.
    flat.sort(key=lambda pnc: -pnc[2])
    flat = flat[:top_bigrams]
    flat.sort(key=lambda pnc: (pnc[0], -pnc[2]))

    out = ASSETS_DIR / "vi_bigrams.txt"
    with open(out, "w", encoding="utf-8") as f:
        for prev, nxt, count in flat:
            f.write(f"{prev}\t{nxt}\t{count}\n")
    print(f"vi_bigrams.txt: {len(flat)} pairs across {len(per_prev)} prev words")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bigram-source", default=DEFAULT_BIGRAM_SOURCE)
    parser.add_argument("--top", type=int, default=50000, help="max words in vi_words.txt")
    parser.add_argument("--top-bigrams", type=int, default=200000, help="max pairs in vi_bigrams.txt")
    args = parser.parse_args()

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    build_wordlist(args.top)

    wordlist: set[str] = set()
    with open(ASSETS_DIR / "vi_words.txt", encoding="utf-8") as f:
        for line in f:
            wordlist.add(line.split("\t", 1)[0])

    sentences = fetch_sentences(args.bigram_source)
    build_bigrams(sentences, wordlist, args.top_bigrams)


if __name__ == "__main__":
    main()
