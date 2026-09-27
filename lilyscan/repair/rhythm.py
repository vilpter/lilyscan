"""Measure-duration constraint repair (Stage 5, item 1).

A voice whose durations do not add up to its measure usually has one misread rhythm:
a triplet whose "3" was not seen (the most common case by far), a missed or extra
augmentation dot, or a flag or beam counted one too many or too few. For each such
voice, search for the fewest of these edits that make it fill the measure exactly:

- ``triplet``: three consecutive plain notes of one type, starting on a multiple of
  the group's length, become a 3:2 triplet;
- ``dot`` / ``undot``: add or remove one augmentation dot;
- ``double`` / ``halve``: one flag fewer or more.

Candidates are ranked by how many edits they need, then by how many of the voice's
onsets they line up with onsets in the measure's other voices and staves (which do
add up), then by the engine's confidence in the events they change. An ambiguous
best candidate is not applied, nor is a dot or flag edit that does not line up more
onsets with the other voices than the voice as read; the measure stays flagged by Q3.
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass
from fractions import Fraction

from lilyscan.ir.models import Event, Provenance, Score, Staff, Voice
from lilyscan.repair import Repair

RULE = "rhythm"

# Written note types and their lengths in quarter notes.
TYPES = {
    "whole": Fraction(4),
    "half": Fraction(2),
    "quarter": Fraction(1),
    "eighth": Fraction(1, 2),
    "16th": Fraction(1, 4),
    "32nd": Fraction(1, 8),
    "64th": Fraction(1, 16),
}
_ORDER = list(TYPES)
TRIPLET = (3, 2)
MAX_SINGLE_EDITS = 1
MAX_SOLUTIONS = 64


@dataclass(frozen=True)
class Edit:
    kind: str  # "triplet", "dot", "undot", "double", "halve"
    index: int  # first event (index into the voice's non-grace events)

    @property
    def size(self) -> int:
        return 3 if self.kind == "triplet" else 1


def _plain(e: Event) -> bool:
    return (
        not e.grace
        and not e.measure_rest
        and e.tuplet is None
        and e.note_type in TYPES
        and e.duration == TYPES[e.note_type] * _dotted(e.dots)
    )


def _dotted(dots: int) -> Fraction:
    return 2 - Fraction(1, 2**dots)


def _single(e: Event, kind: str) -> Event | None:
    """The event after a one-event edit, or None if the edit does not apply."""
    if not _plain(e) or e.note_type is None:
        return None
    t, dots = e.note_type, e.dots
    if kind == "dot" and dots < 2:
        dots += 1
    elif kind == "undot" and dots > 0:
        dots -= 1
    elif kind in ("double", "halve"):
        k = _ORDER.index(t) + (-1 if kind == "double" else 1)
        if not 0 <= k < len(_ORDER):
            return None
        t = _ORDER[k]
    else:
        return None
    return e.model_copy(update={"note_type": t, "dots": dots, "duration": TYPES[t] * _dotted(dots)})


def _triplet(e: Event) -> Event:
    return e.model_copy(update={"tuplet": TRIPLET, "duration": e.duration * Fraction(2, 3)})


def _sequential(events: list[Event]) -> bool:
    """Each note starts where the previous one ends (no gaps or overlaps)."""
    pos = events[0].offset
    for e in events:
        if e.offset != pos:
            return False
        pos += e.duration
    return True


def _solutions(events: list[Event], start: Fraction, length: Fraction) -> Iterator[list[Edit]]:
    """Edit sets, fewest single-event edits first, that make ``events`` end at ``length``."""
    n = len(events)
    single = [
        {kind: _single(e, kind) for kind in ("dot", "undot", "double", "halve")} for e in events
    ]
    found = 0

    def walk(i: int, pos: Fraction, edits: list[Edit], budget: int) -> Iterator[list[Edit]]:
        nonlocal found
        if found >= MAX_SOLUTIONS or pos > length:
            return
        if i == n:
            if pos == length and edits:
                found += 1
                yield list(edits)
            return
        e = events[i]
        yield from walk(i + 1, pos + e.duration, edits, budget)
        group = events[i : i + 3]
        if (
            len(group) == 3
            and all(_plain(x) and x.note_type == e.note_type and x.dots == 0 for x in group)
            and pos % (2 * e.duration) == 0
        ):
            edits.append(Edit("triplet", i))
            yield from walk(i + 3, pos + 2 * e.duration, edits, budget)
            edits.pop()
        if budget:
            for kind, changed in single[i].items():
                if changed is not None:
                    edits.append(Edit(kind, i))
                    yield from walk(i + 1, pos + changed.duration, edits, budget - 1)
                    edits.pop()

    for budget in range(MAX_SINGLE_EDITS + 1):
        yield from (s for s in walk(0, start, [], budget) if _singles(s) == budget)


def _singles(edits: list[Edit]) -> int:
    return sum(1 for x in edits if x.kind != "triplet")


def _apply(events: list[Event], edits: list[Edit], start: Fraction) -> list[Event]:
    out = list(events)
    for edit in edits:
        if edit.kind == "triplet":
            for k in range(edit.index, edit.index + 3):
                out[k] = _triplet(out[k])
        else:
            changed = _single(out[edit.index], edit.kind)
            assert changed is not None
            out[edit.index] = changed
    pos = start
    for k, e in enumerate(out):
        if e.offset != pos:
            out[k] = e.model_copy(update={"offset": pos})
        pos += e.duration
    return out


def _confidence(events: list[Event], edits: list[Edit]) -> float:
    touched = [events[k] for x in edits for k in range(x.index, x.index + x.size)]
    known = [e.confidence for e in touched if e.confidence is not None]
    return sum(known) / len(known) if known else 1.0


@dataclass
class _Fix:
    voice: Voice
    events: list[Event]
    edits: list[Edit]


def _fix_voice(v: Voice, length: Fraction, trusted: set[Fraction]) -> _Fix | str | None:
    """A fix, a reason it could not be fixed, or None when nothing is wrong."""
    events = [e for e in v.events if not e.grace]
    if not events or any(e.measure_rest for e in events) or v.duration() == length:
        return None
    if not _sequential(events):
        return "voice has gaps"
    start = events[0].offset
    ranked = []
    for edits in _solutions(events, start, length):
        fixed = _apply(events, edits, start)
        agree = sum(1 for e in fixed if e.offset in trusted)
        ranked.append((_singles(edits), -agree, _confidence(events, edits), edits, fixed))
    if not ranked:
        return "no single rhythm edit fits"
    ranked.sort(key=lambda r: (r[0], r[1], r[2]))
    best = ranked[0]
    if len(ranked) > 1 and ranked[1][:3] == best[:3]:
        return "several rhythm edits fit equally well"
    # A changed dot or flag is a guess unless it lines the voice up with the others.
    as_read = sum(1 for e in events if e.offset in trusted)
    if best[0] and -best[1] <= as_read:
        return "no other voice confirms the rhythm edit"
    return _Fix(v, best[4], best[3])


def _write(fix: _Fix) -> None:
    """Put the fixed events back, keeping grace notes before the note they lead into."""
    fixed = iter(fix.events)
    out: list[Event] = []
    pending: list[Event] = []
    changed = {k for x in fix.edits for k in range(x.index, x.index + x.size)}
    k = 0
    for e in fix.voice.events:
        if e.grace:
            pending.append(e)
            continue
        new = next(fixed)
        if k in changed:
            new.provenance = [
                *e.provenance,
                Provenance(
                    stage="repair",
                    rule=RULE,
                    before={
                        "duration": str(e.duration),
                        "note_type": e.note_type,
                        "dots": e.dots,
                        "tuplet": list(e.tuplet) if e.tuplet else None,
                    },
                ),
            ]
        out += [g.model_copy(update={"offset": new.offset}) for g in pending] + [new]
        pending = []
        k += 1
    fix.voice.events = out + pending


def _lengths(staff: Staff) -> list[Fraction | None]:
    lengths: list[Fraction | None] = []
    length: Fraction | None = None
    for m in staff.measures:
        if m.time is not None:
            length = m.time.measure_length
        lengths.append(length)
    return lengths


def _counts(v: Voice) -> bool:
    """A voice with real rhythm (not only a measure rest or grace notes)."""
    return any(not e.grace and not e.measure_rest for e in v.events)


def repair_rhythm(score: Score) -> list[Repair]:
    staves = score.staves()
    lengths = [_lengths(s) for _, s in staves]
    # Durations as read, so a voice repaired earlier is not taken as evidence.
    read = {id(v): v.duration() for _, s in staves for m in s.measures for v in m.voices}
    repairs: list[Repair] = []
    for k, (part, staff) in enumerate(staves):
        fixed: dict[str, list[str]] = {}
        for i, m in enumerate(staff.measures):
            length = lengths[k][i]
            if length is None or m.implicit or (i == 0 and m.number == "0"):
                continue
            column = [s.measures[i] for _, s in staves if i < len(s.measures)]
            edge = i in (0, len(staff.measures) - 1)
            for v in m.voices:
                if edge and v.duration() < length:
                    continue  # a pickup or its complement, marked as such or not
                others = [w for x in column for w in x.voices if w is not v and _counts(w)]
                # A short voice, when no other voice fills the measure either, is most
                # likely a short measure (a phrase end in a chorale). Measures that are too
                # long are almost never meant, so those are always repaired.
                short = v.duration() < length
                if short and others and all(read[id(w)] != length for w in others):
                    continue
                trusted = {
                    e.offset
                    for w in others
                    if read[id(w)] == length
                    for e in w.events
                    if not e.grace
                }
                result = _fix_voice(v, length, trusted)
                if isinstance(result, _Fix):
                    _write(result)
                    edits = ", ".join(sorted({x.kind for x in result.edits}))
                    fixed.setdefault(edits, []).append(m.number or str(m.index + 1))
        for kinds, measures in fixed.items():
            repairs.append(
                Repair(
                    RULE,
                    part.id,
                    staff.number,
                    f"{part.name or part.id}: {kinds} to fill the measure",
                    measures,
                )
            )
    return repairs
