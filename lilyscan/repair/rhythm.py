"""Measure-duration constraint repair (Stage 5, item 1).

A voice whose durations do not add up to its measure usually has one misread rhythm:
a triplet whose "3" was not seen (the most common case by far), a missed or extra
augmentation dot, or a flag or beam counted one too many or too few. For each such
voice, search for the fewest of these edits that make it fill the measure exactly:

- ``triplet``: three consecutive plain notes of one type, starting on a multiple of
  the group's length, become a 3:2 triplet;
- ``beam``: two to four consecutive plain notes of one type each get one flag more (a
  beam not seen: eighths read as quarters, sixteenths as eighths);
- ``dot`` / ``undot``: add or remove one augmentation dot;
- ``double`` / ``halve``: one flag fewer or more.

A triplet and a beam are one symbol each, however many notes they change. Candidates
are ranked by how many edits they need, then by how many of the voice's onsets they
line up with onsets in the measure's other voices and staves (which do add up). When
several are still equal (a triplet and a missed beam often both fit), the one whose
onsets fit the notes' places on the page clearly best is taken, as engravers space
notes by the logarithm of their durations; otherwise they are ranked by the engine's
confidence in the events they change. An ambiguous
best candidate is not applied, nor is a dot or flag edit that does not line up more
onsets with the other voices than the voice as read, unless another voice in the
measure is short or long by as much and needs the same edit at the same place (in
homorhythm, the same dot missed in every staff); the measure stays flagged by Q3.

A voice that is short when no single edit fits is often missing rests the engine did
not see: the notes are where they belong on the page, with space for the rests between
them. Rests are put into the gaps so the notes' onsets match their positions, as
engravers space notes (by the logarithm of their durations), when one placement fits
clearly better than every other.

A voice short in every staff is taken as a short measure, meant to be short (a phrase
end in a chorale), only when it is short by whole beats.
"""

from __future__ import annotations

import math
from collections import Counter
from collections.abc import Iterator
from dataclasses import dataclass, field
from fractions import Fraction

from lilyscan.ir.models import Event, Measure, Provenance, Score, Staff, TimeSignature, Voice
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
NO_SINGLE_EDIT = "no single rhythm edit fits"
# Edits that restore a symbol the engine did not see, rather than change one it saw.
MISSED = frozenset({"dot", "triplet", "beam"})
# Edits of a group of notes for one symbol (a triplet's "3", a beam), counted as one
# symbol rather than as an edit per note.
GROUPS = frozenset({"triplet", "beam"})
# A beam the engine missed leaves its notes a flag short: two to four notes of one type,
# read a value too long (eighths as quarters, sixteenths as eighths).
BEAM_SPANS = (2, 3, 4)
BEAMED = frozenset({"quarter", "eighth", "16th", "32nd"})
# Rests put in by position: at most this many rests' worth of the shortest note value,
# in a voice of at most this many notes. A placement is taken when its notes sit within
# REST_FIT of the measure's width of where their onsets put them, and every other
# placement is off REST_RATIO times as much, and by REST_MARGIN more.
MAX_REST_UNITS = 4
MAX_REST_NOTES = 12
REST_FIT = 0.03
REST_RATIO = 2.0
REST_MARGIN = 0.015


@dataclass(frozen=True)
class Edit:
    kind: str  # "triplet", "beam", "dot", "undot", "double", "halve"
    index: int  # first event (index into the voice's non-grace events)
    count: int = 1  # events a beam spans

    @property
    def size(self) -> int:
        return 3 if self.kind == "triplet" else self.count


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
        for count in BEAM_SPANS:
            group = events[i : i + count]
            if (
                len(group) == count
                and e.note_type in BEAMED
                and all(_plain(x) and x.note_type == e.note_type and x.dots == 0 for x in group)
            ):
                edits.append(Edit("beam", i, count))
                yield from walk(i + count, pos + count * e.duration / 2, edits, budget)
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
    return sum(1 for x in edits if x.kind not in GROUPS)


def _apply(events: list[Event], edits: list[Edit], start: Fraction) -> list[Event]:
    out = list(events)
    for edit in edits:
        if edit.kind == "triplet":
            for k in range(edit.index, edit.index + 3):
                out[k] = _triplet(out[k])
        elif edit.kind == "beam":
            for k in range(edit.index, edit.index + edit.count):
                halved = _single(out[k], "halve")
                assert halved is not None
                out[k] = halved
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
    # A dot or flag edit no voice that adds up confirms: applied only when another voice
    # of the measure has the same ``signature`` (as far off, the same edits at the same
    # onsets).
    confirmed: bool = True
    signature: tuple[object, ...] = ()
    rests: bool = False  # rests put in by position, no edits
    # Other edits as few as the best one that fit too, by signature: (edits, events).
    options: dict[tuple[object, ...], tuple[list[Edit], list[Event]]] = field(default_factory=dict)


def _by_spacing(
    candidates: list[tuple[list[Edit], list[Event]]], m: Measure | None
) -> tuple[list[Edit], list[Event]] | None:
    """Of edits equally good otherwise, the one whose onsets fit the notes' places on the
    page clearly best (as engravers space notes), or None when none does."""
    if m is None or m.bbox is None:
        return None
    end = m.bbox.x + m.bbox.w
    ranked = []
    for edits, fixed in candidates:
        if any(e.bbox is None for e in fixed):
            return None
        sequence: list[tuple[Fraction, Event | None]] = [(e.duration, e) for e in fixed]
        shortest = min(d for d, _ in sequence)
        misfit, _ = _fit(_points(sequence, shortest, end))
        ranked.append((misfit / m.bbox.w, edits, fixed))
    ranked.sort(key=lambda r: r[0])
    (fit, edits, fixed), second = ranked[0], ranked[1][0]
    if fit > REST_FIT or second < REST_RATIO * fit or second - fit < REST_MARGIN:
        return None
    return edits, fixed


def _fix_voice(
    v: Voice, length: Fraction, trusted: set[Fraction], m: Measure | None = None
) -> _Fix | str | None:
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
        # Between edits equal in all else, a symbol the engine missed (a dot, a
        # triplet's "3") before one it read wrongly (a flag too many or too few, a dot
        # that is not there).
        misread = sum(1 for x in edits if x.kind not in MISSED)
        ranked.append((_singles(edits), -agree, _confidence(events, edits), misread, edits, fixed))
    if not ranked:
        return NO_SINGLE_EDIT
    ranked.sort(key=lambda r: (r[0], r[1], r[2], r[3]))
    best = ranked[0]
    # Edits as few as the best's, lining up as many onsets (a triplet and a missed beam
    # often both fit): the notes' places on the page may tell them apart.
    tied = [r for r in ranked if r[:2] == best[:2]]
    spaced = _by_spacing([(r[4], r[5]) for r in tied], m) if len(tied) > 1 else None
    if spaced is not None:
        best = next(r for r in tied if r[4] is spaced[0])
    elif len(ranked) > 1 and ranked[1][:4] == best[:4]:
        return "several rhythm edits fit equally well"
    # A changed dot or flag is a guess unless it lines the voice up with the others.
    as_read = sum(1 for e in events if e.offset in trusted)
    confirmed = not (best[0] and -best[1] <= as_read)

    def signature(edits: list[Edit]) -> tuple[object, ...]:
        return (length - v.duration(), *((x.kind, events[x.index].offset) for x in edits))

    options = {signature(r[4]): (r[4], r[5]) for r in ranked if r[0] == best[0]}
    return _Fix(v, best[5], best[4], confirmed, signature(best[4]), options=options)


def _space(duration: Fraction, shortest: Fraction) -> float:
    """The room an engraver gives a note: one unit for the shortest, one more for each
    doubling of the duration."""
    return 1.0 + math.log2(duration / shortest)


def _points(
    sequence: list[tuple[Fraction, Event | None]], shortest: Fraction, end: float
) -> list[tuple[float, float]]:
    """(spacing position, x) of each note of a measure, and of its closing barline."""
    points: list[tuple[float, float]] = []
    room = 0.0
    for duration, note in sequence:
        if note is not None and note.bbox is not None:
            points.append((room, note.bbox.x))
        room += _space(duration, shortest)
    points.append((room, end))  # the last note's room runs to the barline
    return points


def _fit(points: list[tuple[float, float]]) -> tuple[float, float]:
    """The line through ``points`` (spacing position, x): its root-mean-square misfit and
    its slope (pixels per spacing unit); an infinite misfit when it does not rise."""
    n = len(points)
    mp = sum(p for p, _ in points) / n
    mx = sum(x for _, x in points) / n
    spread = sum((p - mp) ** 2 for p, _ in points)
    slope = sum((p - mp) * (x - mx) for p, x in points) / spread if spread else 0.0
    if slope <= 0:
        return math.inf, 0.0
    misfit = math.sqrt(sum((x - (mx + slope * (p - mp))) ** 2 for p, x in points) / n)
    return misfit, slope


def _gaps(units: int, slots: int) -> Iterator[tuple[int, ...]]:
    """Every way to share ``units`` among ``slots`` gaps."""
    if slots == 1:
        yield (units,)
        return
    for first in range(units + 1):
        for rest in _gaps(units - first, slots - 1):
            yield (first, *rest)


def _rests(offset: Fraction, length: Fraction) -> list[Event]:
    """Rests of written values filling ``length`` from ``offset``, longest first."""
    out = []
    for name, value in TYPES.items():
        while length >= value:
            out.append(
                Event(
                    kind="rest",
                    offset=offset,
                    duration=value,
                    note_type=name,
                    provenance=[Provenance(stage="repair", rule=RULE, before={"rest": "put in"})],
                )
            )
            offset += value
            length -= value
    return out


def _with_rests(v: Voice, length: Fraction, m: Measure) -> _Fix | None:
    """The voice with rests put in where its notes' positions on the page leave room for
    them, or None when no placement clearly fits best."""
    events = list(v.events)
    if (
        m.bbox is None
        or not 2 <= len(events) <= MAX_REST_NOTES
        or any(e.grace or e.bbox is None for e in events)
        or events[0].offset != 0
        or not _sequential(events)
    ):
        return None
    unit = min(e.duration for e in events)
    units = (length - v.duration()) / unit
    if units.denominator != 1 or not 0 < units <= MAX_REST_UNITS:
        return None
    end = m.bbox.x + m.bbox.w
    lead = events[0].bbox.x - m.bbox.x if events[0].bbox else 0.0  # barline to first note
    ranked = []
    for gaps in _gaps(int(units), len(events) + 1):
        # The measure as it would be: rests (None) of the shortest value in the gaps.
        sequence: list[tuple[Fraction, Event | None]] = [(unit, None)] * gaps[0]
        for e, gap in zip(events, gaps[1:], strict=True):
            sequence += [(e.duration, e)] + [(unit, None)] * gap
        shortest = min(d for d, _ in sequence)
        misfit, slope = _fit(_points(sequence, shortest, end))
        # Rests before the first note need room between the barline and that note.
        if gaps[0] and lead < 0.5 * slope * gaps[0] * _space(unit, shortest):
            continue
        ranked.append((misfit / m.bbox.w, gaps))
    ranked.sort()
    if len(ranked) < 2:
        return None
    (fit, gaps), (second, _) = ranked[0], ranked[1]
    if fit > REST_FIT or second < REST_RATIO * fit or second - fit < REST_MARGIN:
        return None
    out: list[Event] = []
    pos = Fraction(0)
    out += _rests(pos, unit * gaps[0])
    pos += unit * gaps[0]
    for e, gap in zip(events, gaps[1:], strict=True):
        moved = e if e.offset == pos else e.model_copy(update={"offset": pos})
        if moved is not e:
            moved.provenance = [
                *e.provenance,
                Provenance(stage="repair", rule=RULE, before={"offset": str(e.offset)}),
            ]
        out.append(moved)
        pos += e.duration
        out += _rests(pos, unit * gap)
        pos += unit * gap
    return _Fix(v, out, [], rests=True)


def _write(fix: _Fix) -> None:
    """Put the fixed events back, keeping grace notes before the note they lead into."""
    if fix.rests:
        fix.voice.events = fix.events
        return
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


def _in_force(staff: Staff) -> list[TimeSignature | None]:
    times: list[TimeSignature | None] = []
    time: TimeSignature | None = None
    for m in staff.measures:
        if m.time is not None:
            time = m.time
        times.append(time)
    return times


def _whole_beats(short: Fraction, time: TimeSignature | None) -> bool:
    """Whether a measure short by ``short`` is short by whole beats (a dotted quarter in
    6/8, 9/8 and 12/8)."""
    if time is None:
        return True
    beat = Fraction(4, time.beat_type)
    if time.beat_type == 8 and time.beats % 3 == 0 and time.beats > 3:
        beat *= 3
    return (short / beat).denominator == 1


def _counts(v: Voice) -> bool:
    """A voice with real rhythm (not only a measure rest or grace notes)."""
    return any(not e.grace and not e.measure_rest for e in v.events)


def repair_rhythm(score: Score) -> list[Repair]:
    staves = score.staves()
    times = [_in_force(s) for _, s in staves]
    # Durations as read, so a voice repaired earlier is not taken as evidence.
    read = {id(v): v.duration() for _, s in staves for m in s.measures for v in m.voices}
    found: list[tuple[int, Measure, _Fix]] = []
    for i in range(max((len(s.measures) for _, s in staves), default=0)):
        column = [s.measures[i] for _, s in staves if i < len(s.measures)]
        fixes: list[tuple[int, Measure, _Fix]] = []
        for k, (_, staff) in enumerate(staves):
            if i >= len(staff.measures):
                continue
            m, time = staff.measures[i], times[k][i]
            if time is None or m.implicit or (i == 0 and m.number == "0"):
                continue
            length = time.measure_length
            edge = i in (0, len(staff.measures) - 1)
            for v in m.voices:
                if edge and v.duration() < length:
                    continue  # a pickup or its complement, marked as such or not
                others = [w for x in column for w in x.voices if w is not v and _counts(w)]
                # A short voice, when no other voice fills the measure either, is most
                # likely a short measure (a phrase end in a chorale), if it is short by
                # whole beats. Measures that are too long are almost never meant, so
                # those are always repaired.
                short = v.duration() < length
                if (
                    short
                    and others
                    and all(read[id(w)] != length for w in others)
                    and _whole_beats(length - v.duration(), time)
                ):
                    continue
                trusted = {
                    e.offset
                    for w in others
                    if read[id(w)] == length
                    for e in w.events
                    if not e.grace
                }
                result = _fix_voice(v, length, trusted, m)
                if result == NO_SINGLE_EDIT and short:
                    result = _with_rests(v, length, m)
                if isinstance(result, _Fix):
                    fixes.append((k, m, result))
        # Unconfirmed edits that restore missed symbols confirm each other (the same dot
        # missed in every staff of a homorhythmic passage; not the same flag misread):
        # the edit most voices rank best (at least two, and no other as often) goes to
        # every unconfirmed voice it fits.
        unconfirmed = [f for _, _, f in fixes if not f.confirmed]
        votes = Counter(
            f.signature for f in unconfirmed if all(x.kind in MISSED for x in f.edits)
        ).most_common()
        if votes and votes[0][1] >= 2 and (len(votes) == 1 or votes[1][1] < votes[0][1]):
            chosen = votes[0][0]
            for f in unconfirmed:
                if chosen in f.options:
                    f.edits, f.events = f.options[chosen]
                    f.signature, f.confirmed = chosen, True
        found += [(k, m, fix) for k, m, fix in fixes if fix.confirmed]
    fixed: dict[int, dict[str, list[str]]] = {}
    for k, m, fix in found:
        _write(fix)
        kinds = "rests" if fix.rests else ", ".join(sorted({x.kind for x in fix.edits}))
        fixed.setdefault(k, {}).setdefault(kinds, []).append(m.number or str(m.index + 1))
    repairs: list[Repair] = []
    for k, (part, staff) in enumerate(staves):
        for kinds, measures in fixed.get(k, {}).items():
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
