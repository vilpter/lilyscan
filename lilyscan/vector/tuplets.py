"""Stage 4: tuplet numbers printed in a born-digital PDF, applied to the engine's rhythm.

The engine's most common rhythm error is a triplet read as plain notes. In a PDF the
tuplet number is text (an italic 3 over or under the group), so it can be read
exactly. For each printed 3, the run of plain notes in one voice that it sits over is
turned into a triplet, when that makes the voice fill its measure.
"""

from __future__ import annotations

from dataclasses import dataclass
from fractions import Fraction
from itertools import pairwise

from lilyscan.engine.audiveris.omr import OmrBook
from lilyscan.ir.models import Event, Measure, Provenance, Score, Voice
from lilyscan.repair import Repair
from lilyscan.vector.extract import Glyph, VectorPage
from lilyscan.vector.staves import VectorStaff, find_staves

RULE = "vector-tuplet"
TRIPLET = (3, 2)
# A tuplet number sits within this many staff spaces of its staff.
_NEAR_STAFF = 6.0
# The group's centre is within this many staff spaces of the number.
_NEAR_GROUP = 2.0
_TYPES = {
    "whole": Fraction(4),
    "half": Fraction(2),
    "quarter": Fraction(1),
    "eighth": Fraction(1, 2),
    "16th": Fraction(1, 4),
    "32nd": Fraction(1, 8),
}


@dataclass
class TupletStats:
    marks: int = 0  # printed tuplet numbers found
    matched: int = 0  # already tuplets in the engine output
    applied: int = 0  # groups turned into triplets
    unplaced: int = 0  # no fitting group found

    def to_dict(self) -> dict[str, int]:
        return dict(self.__dict__)


def _plain(e: Event) -> bool:
    if e.grace or e.measure_rest or e.tuplet is not None or e.note_type not in _TYPES:
        return False
    return e.duration == _TYPES[e.note_type] * (2 - Fraction(1, 2**e.dots))


def _length(staff_measures: list[Measure]) -> dict[int, Fraction | None]:
    out: dict[int, Fraction | None] = {}
    length: Fraction | None = None
    for m in staff_measures:
        if m.time is not None:
            length = m.time.measure_length
        out[id(m)] = length
    return out


def _centre(e: Event, scale: float) -> float:
    assert e.bbox is not None
    return (e.bbox.x + e.bbox.w / 2) / scale


def _staff_of(mark: Glyph, staves: list[VectorStaff]) -> VectorStaff | None:
    x, y = mark.rect.cx, mark.origin[1]
    best, best_d = None, None
    for s in staves:
        if not s.x0 - 5 <= x <= s.x1 + 5:
            continue
        d = max(0.0, s.top - y, y - s.bottom)
        if d <= _NEAR_STAFF * s.interline and (best_d is None or d < best_d):
            best, best_d = s, d
    return best


def _group(v: Voice, x: float, scale: float, space: float) -> list[int] | None:
    """Indexes of the run of plain notes the number sits over, if one fits: its written
    length is three equal units (three eighths, a quarter and an eighth, ...)."""
    events = [e for e in v.events if not e.grace]
    best: tuple[float, list[int]] | None = None
    for i in range(len(events)):
        total = Fraction(0)
        for j in range(i, min(len(events), i + 6)):
            e = events[j]
            if e.bbox is None or not _plain(e):
                break
            total += e.duration
            if j == i:
                continue
            unit = total / 3
            if unit not in _TYPES.values():
                continue
            lo, hi = _centre(events[i], scale), _centre(events[j], scale)
            if not lo - _NEAR_GROUP * space <= x <= hi + _NEAR_GROUP * space:
                continue
            off = abs((lo + hi) / 2 - x)
            if off <= _NEAR_GROUP * space and (best is None or off < best[0]):
                best = (off, [v.events.index(events[k]) for k in range(i, j + 1)])
    return None if best is None else best[1]


def _apply(v: Voice, indexes: list[int]) -> None:
    for k in indexes:
        e = v.events[k]
        e.provenance.append(
            Provenance(
                stage="vector-oracle",
                rule=RULE,
                before={"duration": str(e.duration), "tuplet": None},
            )
        )
        v.events[k] = e.model_copy(
            update={"duration": e.duration * Fraction(2, 3), "tuplet": TRIPLET}
        )
    # Re-lay the voice: each note starts where the previous one ends.
    pos = next((e.offset for e in v.events if not e.grace), Fraction(0))
    for k, e in enumerate(v.events):
        if e.grace:
            continue
        if e.offset != pos:
            v.events[k] = e.model_copy(update={"offset": pos})
        pos += e.duration


def _sequential(v: Voice) -> bool:
    events = [e for e in v.events if not e.grace]
    return all(a.offset + a.duration == b.offset for a, b in pairwise(events))


def apply_tuplets(
    score: Score, pages: list[VectorPage], book: OmrBook
) -> tuple[list[Repair], TupletStats]:
    stats = TupletStats()
    repairs: list[Repair] = []
    # page index (as in bboxes) -> (staves, marks, scale)
    layout: dict[int, tuple[list[VectorStaff], list[Glyph], float]] = {}
    for index, sheet in enumerate(book.sheets):
        if 0 <= sheet.number - 1 < len(pages):
            page = pages[sheet.number - 1]
            marks = [g for g in page.glyphs if g.name == "italic.3"]
            if marks:
                layout[index] = (find_staves(page), marks, sheet.width / page.width)
                stats.marks += len(marks)
    if not layout:
        return repairs, stats
    for part, staff in score.staves():
        lengths = _length(staff.measures)
        fixed: list[str] = []
        for m in staff.measures:
            if m.bbox is None or m.bbox.page not in layout:
                continue
            staves, marks, scale = layout[m.bbox.page]
            x0, x1 = m.bbox.x / scale, (m.bbox.x + m.bbox.w) / scale
            y = (m.bbox.y + m.bbox.h / 2) / scale
            here = []
            for mark in marks:
                home = _staff_of(mark, staves)
                if (
                    home is not None
                    and x0 <= mark.rect.cx <= x1
                    and home.top - 1 <= y <= home.bottom + 1
                ):
                    here.append((mark, home.interline))
            if here and _place(m, here, scale, lengths[id(m)], stats):
                fixed.append(m.number or str(m.index + 1))
        if fixed:
            repairs.append(
                Repair(
                    RULE,
                    part.id,
                    staff.number,
                    f"{part.name or part.id}: triplets printed in the PDF",
                    fixed,
                )
            )
    return repairs, stats


def _place(
    m: Measure,
    marks: list[tuple[Glyph, float]],
    scale: float,
    length: Fraction | None,
    stats: TupletStats,
) -> bool:
    """Turn the groups under this measure's tuplet numbers into triplets, each number
    going to the nearest voice with a fitting group, when every changed voice then
    fills the measure."""
    if length is None:
        stats.unplaced += len(marks)
        return False
    chosen: dict[int, list[list[int]]] = {}  # voice index -> groups
    for mark, space in marks:
        x = mark.rect.cx
        if any(
            e.tuplet is not None
            and e.bbox is not None
            and abs(_centre(e, scale) - x) <= _NEAR_GROUP * space
            for v in m.voices
            for e in v.events
        ):
            stats.matched += 1  # the engine read this one
            continue
        options = []
        for k, v in enumerate(m.voices):
            if not _sequential(v):
                continue
            group = _group(v, x, scale, space)
            if group is None:
                continue
            ys = [
                (v.events[i].bbox.y + v.events[i].bbox.h / 2) / scale  # type: ignore[union-attr]
                for i in group
            ]
            options.append((abs(sum(ys) / len(ys) - mark.origin[1]), k, group))
        if not options:
            stats.unplaced += 1
            continue
        _, k, group = min(options)
        chosen.setdefault(k, []).append(group)
    changed = False
    for k, groups in chosen.items():
        v = m.voices[k]
        flat = [i for g in groups for i in g]
        saving = sum((v.events[i].duration for i in flat), Fraction(0)) / 3
        if len(set(flat)) != len(flat) or v.duration() - saving != length:
            stats.unplaced += len(groups)
            continue
        _apply(v, sorted(flat))
        stats.applied += len(groups)
        changed = True
    return changed
