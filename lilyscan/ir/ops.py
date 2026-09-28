"""Whole-score operations on the IR."""

from __future__ import annotations

import re
from fractions import Fraction

from lilyscan.ir.models import Event, KeySignature, Measure, Part, Score, Staff, Voice


def _key_in_force(staff: Staff) -> int:
    for m in reversed(staff.measures):
        if m.key is not None:
            return m.key.fifths
    return 0


def _same_part(a: Part, b: Part) -> bool:
    """A part carried on from one movement into the next: same staves, same name."""

    def name(p: Part) -> str:
        return re.sub(r"[^a-z0-9]", "", (p.name or "").lower())

    return len(a.staves) == len(b.staves) and name(a) == name(b)


def _rests(reference: list[Measure], offset: int) -> list[Measure]:
    """Measures for a part absent from a movement: a measure rest in each measure of
    ``reference`` (a staff of that movement), with its numbering, key and time."""
    out = []
    time = None
    for m in reference:
        time = m.time or time
        length = max(
            (sum((e.duration for e in v.events if not e.grace), Fraction(0)) for v in m.voices),
            default=Fraction(0),
        )
        length = length or (time.measure_length if time else Fraction(4))
        rest = Event(kind="rest", offset=Fraction(0), duration=length, measure_rest=True)
        out.append(
            Measure(
                index=m.index + offset,
                number=m.number,
                key=m.key,
                time=m.time,
                implicit=m.implicit,
                voices=[Voice(number=1, events=[rest])],
            )
        )
    return out


def _longest(score: Score) -> list[Measure]:
    """The measures of the score's longest staff."""
    return max((st.measures for _, st in score.staves()), key=len, default=[])


def _pad(score: Score) -> None:
    """Measure rests to the end for a part that ends early: Audiveris does not pad a part
    missing from a movement's last systems."""
    reference = _longest(score)[:]
    for _, staff in score.staves():
        staff.measures += _rests(reference[len(staff.measures) :], 0)


def _append(base: Score, extra: Score) -> None:
    """Add movement ``extra`` after ``base``, matching its parts to base's in order.

    A part of either that the other lacks is kept, with measure rests where it is absent
    (the form Audiveris gives a part missing from a system, so the part-merge repair can
    join parts the engine split between movements).
    """
    _pad(base)  # so the next movement starts at the same measure in every part
    base_reference = _longest(base)[:]
    offset = len(base_reference)
    extra_reference = _longest(extra)
    ids = {p.id for p in base.parts}
    parts = base.parts
    carried = {id(staff): _key_in_force(staff) for _, staff in base.staves()}
    continued: set[int] = set()
    last = -1
    for more in extra.parts:
        j = next((j for j in range(last + 1, len(parts)) if _same_part(parts[j], more)), None)
        if j is None:
            # A part new in this movement: absent from everything before it.
            n = len(ids) + 1
            while f"P{n}" in ids:
                n += 1
            ids.add(f"P{n}")
            new = more.model_copy(update={"id": f"P{n}", "staves": []})
            for st in more.staves:
                filler = _rests(base_reference, 0)
                first_clefs = next((m.clefs[:1] for m in st.measures if m.clefs), [])
                if filler:
                    filler[0].clefs = [c.model_copy(update={"offset": 0}) for c in first_clefs]
                new.staves.append(Staff(number=st.number, measures=filler))
                carried[id(new.staves[-1])] = _key_in_force(new.staves[-1])
            j = last + 1
            parts.insert(j, new)
        target = parts[j]
        for staff, st in zip(target.staves, more.staves, strict=False):
            staff.measures += [
                m.model_copy(update={"index": m.index + offset}) for m in st.measures
            ]
        continued.add(id(target))
        last = j
    for part in parts:
        if id(part) not in continued:
            for staff in part.staves:
                staff.measures += _rests(extra_reference, offset)
    # A movement that states no key signature has none, rather than the previous one's.
    for _, staff in base.staves():
        first = next((m for m in staff.measures if m.index == offset), None)
        if first is not None and first.key is None and carried.get(id(staff), 0) != 0:
            first.key = KeySignature(fifths=0)


def merge_scores(scores: list[Score]) -> Score:
    """Concatenate scores (e.g. one engine output file per movement) into one."""
    if not scores:
        raise ValueError("nothing to merge")
    base = scores[0].model_copy(deep=True)
    for extra in scores[1:]:
        _append(base, extra.model_copy(deep=True))
    _pad(base)
    return base


def counts(score: Score) -> dict[str, int]:
    staves = score.staves()
    return {
        "parts": len(score.parts),
        "staves": len(staves),
        "measures": max((len(s.measures) for _, s in staves), default=0),
        "notes": sum(
            len(e.notes) for _, s in staves for m in s.measures for v in m.voices for e in v.events
        ),
    }
