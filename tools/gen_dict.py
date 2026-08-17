#!/usr/bin/env python3
"""Generate app/src/main/assets/vi_words.txt from tools/data/vi_50k.txt.

One lowercase Vietnamese word per line — every line's first field whose
characters all belong to the Vietnamese alphabet (12 vowel bases x 6 tone
forms, precomposed NFC, plus the consonants b c d đ g h k l m n p q r s t v x).
Words containing f j w z or any foreign character are dropped: they can never
match a resolved Telex output.
"""
import unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SRC = REPO / "tools" / "data" / "vi_50k.txt"
DST = REPO / "app" / "src" / "main" / "assets" / "vi_words.txt"

vowels = "aăâeêioôơuưy"
consonants = "bcdđghklmnpqrstvx"

letters = set(consonants)
for base in vowels:
    for tone in ("", "́", "̀", "̉", "̃", "̣"):
        letters.add(unicodedata.normalize("NFC", base + tone))

words = []
with open(SRC, encoding="utf-8") as f:
    for line in f:
        parts = line.split()
        if len(parts) < 2:
            continue
        word = parts[0]
        if word and all(c in letters for c in word):
            words.append(word)

with open(DST, "w", encoding="utf-8") as f:
    f.write("\n".join(words) + "\n")

print(f"{len(words)} words -> {DST}")
