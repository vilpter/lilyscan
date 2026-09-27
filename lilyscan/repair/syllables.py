"""Split lyric syllables the engine glued together (Stage 5, item 6).

When two syllables sit close together, the engraver leaves out the hyphen between
them and the OCR reads one word ("mazing" for "maz-ing"). Audiveris then attaches
the word to one note and leaves the other without a syllable.

With the ``.omr``, the word's box on the page is known, and so are the noteheads. A
word is split when its box spans the centres of neighbouring notes in the same voice
that have no syllable of their own, and a split exists that puts each piece's centre
under its note: syllables are centred under their notes, and a long word under one
note of a melisma (left-aligned, reaching over the next note) fits that badly. Glyph
widths are approximated per character, and every piece must have a vowel.
"""

from __future__ import annotations

from dataclasses import dataclass
from itertools import combinations, pairwise
from typing import Literal

from lilyscan.engine.audiveris.omr import Box, OmrBook
from lilyscan.ir.models import Event, Lyric, Provenance, Score
from lilyscan.repair import Repair

RULE = "lyric-split"
MAX_PIECES = 4
# Worst allowed distance between a piece's centre and its note, in average glyph widths.
MAX_OFFSET = 1.0

_VOWELS = set("aeiouyäöüàáâãåèéêëìíîïòóôõùúûýæœ")
# Vowel pairs that are one sound in the corpus languages, never split between syllables.
_DIPHTHONGS = {
    "ie",
    "ei",
    "ai",
    "au",
    "eu",
    "äu",
    "oi",
    "ou",
    "oo",
    "ee",
    "ae",
    "oe",
    "ay",
    "ey",
    "oy",
}
# Approximate advance widths (em) of a serif text font; unknown characters get 0.5.
_WIDTHS = {
    **dict.fromkeys("ijlI.,;:'!|", 0.28),
    **dict.fromkeys("frt", 0.35),
    **dict.fromkeys("cesz", 0.45),
    **dict.fromkeys("abdghknopquvxy", 0.55),
    **dict.fromkeys("mw", 0.8),
    **dict.fromkeys("MW", 0.9),
}


def _width(ch: str) -> float:
    if ch in _WIDTHS:
        return _WIDTHS[ch]
    return 0.7 if ch.isupper() else 0.5


@dataclass(frozen=True)
class Anchor:
    """Where a syllable for this note can sit: centred on the head, or starting at its left
    edge when a melisma follows (as engravers align a syllable held over several notes)."""

    centre: float
    left: float
    melisma: bool


def _fit(start: float, stop: float, anchor: Anchor) -> float:
    centred = abs((start + stop) / 2 - anchor.centre)
    return min(centred, abs(start - anchor.left)) if anchor.melisma else centred


def split_word(text: str, box: Box, anchors: list[Anchor]) -> tuple[float, list[str]] | None:
    """The best split of ``text`` into one piece per anchor (left to right), as (worst
    misfit in glyph widths, pieces), or None when no split fits."""
    n, k = len(text), len(anchors)
    if k < 2 or n < k:
        return None
    widths = [_width(c) for c in text]
    scale = box.w / sum(widths)
    edges = [box.x]
    for w in widths:
        edges.append(edges[-1] + w * scale)
    glyph = box.w / n
    best: tuple[float, list[str]] | None = None
    for cuts in combinations(range(1, n), k - 1):
        if any(text[c - 1 : c + 1].lower() in _DIPHTHONGS for c in cuts):
            continue
        bounds = [0, *cuts, n]
        pieces = [text[a:b] for a, b in pairwise(bounds)]
        if not all(any(c.lower() in _VOWELS for c in p) for p in pieces):
            continue
        worst = (
            max(
                _fit(edges[a], edges[b], anchor)
                for (a, b), anchor in zip(pairwise(bounds), anchors, strict=True)
            )
            / glyph
        )
        if best is None or worst < best[0]:
            best = (worst, pieces)
    if best is None or best[0] > MAX_OFFSET:
        return None
    return best


Syllabic = Literal["single", "begin", "middle", "end"]


def _syllabic(original: str, k: int) -> list[Syllabic]:
    first: Syllabic = "begin" if original in ("single", "begin") else "middle"
    last: Syllabic = "end" if original in ("single", "end") else "middle"
    middle: list[Syllabic] = ["middle"] * (k - 2)
    return [first, *middle, last]


def _items(book: OmrBook) -> dict[int, list[tuple[str, Box]]]:
    """Lyric syllables per page index: (text, box)."""
    out: dict[int, list[tuple[str, Box]]] = {}
    for page, sheet in enumerate(book.sheets):
        out[page] = [
            (i.value, i.box)
            for system in sheet.systems
            for i in system.inters
            if i.kind == "lyric-item" and i.role == "Syllable" and i.value and i.box
        ]
    return out


def _centre(e: Event) -> float | None:
    return None if e.bbox is None else e.bbox.x + e.bbox.w / 2


def _free(e: Event, verse: int, page: int) -> bool:
    """A note that could take a syllable of this verse."""
    return (
        e.kind != "rest"
        and not e.grace
        and e.bbox is not None
        and e.bbox.page == page
        and not any(ly.verse == verse for ly in e.lyrics)
        and not (e.notes and all(h.tie_stop for h in e.notes))
    )


def split_glued_syllables(score: Score, book: OmrBook) -> list[Repair]:
    items = _items(book)
    repairs: list[Repair] = []
    for part, staff in score.staves():
        measures: list[str] = []
        for m in staff.measures:
            for v in m.voices:
                events = [e for e in v.events if not e.grace]
                for i, e in enumerate(events):
                    x = _centre(e)
                    if x is None or e.bbox is None:
                        continue
                    for ly in list(e.lyrics):
                        box = next(
                            (
                                b
                                for text, b in items.get(e.bbox.page, [])
                                if text.strip() == ly.text.strip()
                                and b.x <= x <= b.x + b.w
                                and b.y > e.bbox.y
                            ),
                            None,
                        )
                        if box is None or len(ly.text.strip()) < 2:
                            continue
                        near = [e]
                        for step in (-1, 1):
                            j = i + step
                            while 0 <= j < len(events) and len(near) < MAX_PIECES + 2:
                                other = events[j]
                                cx = _centre(other)
                                if cx is None or not _free(other, ly.verse, e.bbox.page):
                                    break
                                if not box.x <= cx <= box.x + box.w:
                                    break
                                near.append(other)
                                j += step
                        near.sort(key=lambda g: events.index(g))
                        found = _best_split(ly, box, e, near, events)
                        if found is None:
                            continue
                        group, pieces = found
                        _apply(group, ly, pieces)
                        measures.append(m.number or str(m.index + 1))
        if measures:
            repairs.append(
                Repair(
                    RULE,
                    part.id,
                    staff.number,
                    f"{part.name or part.id}: split syllables the engine read as one word",
                    sorted(set(measures), key=measures.index),
                )
            )
    return repairs


def _anchor(g: Event, group: list[Event], events: list[Event], verse: int) -> Anchor:
    assert g.bbox is not None
    k = events.index(g)
    after = events[k + 1] if k + 1 < len(events) else None
    melisma = (
        after is not None
        and after not in group
        and after.kind != "rest"
        and not any(ly.verse == verse for ly in after.lyrics)
    )
    return Anchor(g.bbox.x + g.bbox.w / 2, g.bbox.x, melisma)


def _best_split(
    ly: Lyric, box: Box, owner: Event, near: list[Event], events: list[Event]
) -> tuple[list[Event], list[str]] | None:
    """The notes (the owner and some free neighbours) and pieces that fit best."""
    best: tuple[float, list[Event], list[str]] | None = None
    others = [g for g in near if g is not owner]
    for size in range(1, min(MAX_PIECES, len(near))):
        for chosen in combinations(others, size):
            group = sorted([owner, *chosen], key=events.index)
            anchors = [_anchor(g, group, events, ly.verse) for g in group]
            found = split_word(ly.text.strip(), box, anchors)
            if found is not None and (best is None or found[0] < best[0]):
                best = (found[0], group, found[1])
    return None if best is None else (best[1], best[2])


def _apply(group: list[Event], original: Lyric, pieces: list[str]) -> None:
    owner = next(g for g in group if any(ly is original for ly in g.lyrics))
    kinds = _syllabic(original.syllabic, len(pieces))
    for g, text, syllabic in zip(group, pieces, kinds, strict=True):
        before = [[ly.verse, ly.text] for ly in g.lyrics]
        new = Lyric(
            text=text,
            verse=original.verse,
            syllabic=syllabic,
            extend=original.extend and g is group[-1],
        )
        if g is owner:
            g.lyrics = [new if ly is original else ly for ly in g.lyrics]
        else:
            g.lyrics = sorted([*g.lyrics, new], key=lambda ly: ly.verse)
        g.provenance.append(Provenance(stage="repair", rule=RULE, before={"lyrics": before}))
