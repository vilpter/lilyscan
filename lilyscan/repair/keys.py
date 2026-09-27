"""Key-signature repair.

Audiveris reads the key signature afresh at the start of every system, and on photos
it often misses an accidental there (three sharps read as two, or as one), which puts
every note that key should alter a semitone off for the whole system. Printed music
restates the same key on every system; a real change of key comes in the middle of a
system or after a double bar. So within each stretch of a piece between real changes,
the key read on most systems wins (on a tie, the one with more accidentals: a missed
accidental is likelier than an invented one). Systems read in another key are set to
it, and the notes that the misread key had altered follow.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass

from lilyscan.engine.audiveris.omr import OmrBook
from lilyscan.ir.models import KeySignature, Measure, Pitch, Provenance, Score, Staff
from lilyscan.repair import Repair

RULE = "key-signature"
_SHARPS = "FCGDAEB"
_FLATS = "BEADGCF"
_DOUBLE_BARS = frozenset({"light-light", "light-heavy", "heavy-light", "heavy-heavy"})


def key_alter(fifths: int, step: str) -> int:
    """How a key signature alters a step: +1 sharp, -1 flat, 0 neither."""
    if fifths > 0 and step in _SHARPS[:fifths]:
        return 1
    if fifths < 0 and step in _FLATS[:-fifths]:
        return -1
    return 0


@dataclass
class _Segment:
    """Measures read in one key: a whole system, or the part of one after a key change."""

    measures: list[Measure]
    fifths: int
    system_start: bool


def _layout(book: OmrBook) -> tuple[list[int], set[int]]:
    """Measure indices where systems start, and those where pieces start."""
    starts: list[int] = []
    movements: set[int] = set()
    index = 0
    for sheet in book.sheets:
        for system in sheet.systems:
            if not system.stacks:
                continue
            starts.append(index)
            if system.starts_movement or index == 0:
                movements.add(index)
            index += len(system.stacks)
    return starts, movements


def _stretches(staff: Staff, starts: list[int], movements: set[int]) -> list[list[_Segment]]:
    """The staff's segments grouped into stretches that should share one key."""
    system_start = set(starts)
    stretches: list[list[_Segment]] = []
    fifths = 0
    previous: Measure | None = None
    for m in staff.measures:
        changed = m.key is not None and m.key.fifths != fifths
        if m.key is not None:
            fifths = m.key.fifths
        new_piece = m.index in movements or previous is None
        at_system = m.index in system_start
        if new_piece:
            stretches.append([_Segment([m], fifths, True)])
        elif at_system or changed:
            double_bar = previous is not None and (
                (
                    previous.right_barline is not None
                    and previous.right_barline.style in _DOUBLE_BARS
                )
                or (m.left_barline is not None and m.left_barline.style in _DOUBLE_BARS)
            )
            segment = _Segment([m], fifths, at_system)
            # A change in mid-system, or after a double bar, is printed as a change.
            if changed and (not at_system or double_bar):
                stretches.append([segment])
            else:
                stretches[-1].append(segment)
        else:
            stretches[-1][-1].measures.append(m)
        previous = m
    return stretches


def _winner(segments: list[_Segment]) -> int | None:
    """The stretch's key: read on the most systems, then the one with more accidentals."""
    votes = Counter(s.fifths for s in segments if s.system_start)
    if len(votes) < 2:
        return None
    ranked = sorted(votes.items(), key=lambda kv: (kv[1], abs(kv[0])), reverse=True)
    (best, n), (runner, n2) = ranked[0], ranked[1]
    if n == n2 and abs(best) == abs(runner):
        return None  # e.g. as many systems in two sharps as in two flats: no call
    return best


def _respell(m: Measure, old: int, new: int) -> int:
    """Move the notes in ``m`` that key ``old`` altered to key ``new``; returns how many
    events changed."""
    changed = 0
    # An accidental printed in the measure holds for that step and octave until the bar.
    printed: set[tuple[str, int]] = set()
    events = sorted(
        (e for v in m.voices for e in v.events if e.notes), key=lambda e: (e.offset, not e.grace)
    )
    for e in events:
        before = [h.pitch.label for h in e.notes]
        moved = False
        for h in e.notes:
            p = h.pitch
            if h.accidental_shown:
                printed.add((p.step, p.octave))
                continue
            if (p.step, p.octave) in printed or p.alter != key_alter(old, p.step):
                continue
            alter = key_alter(new, p.step)
            if alter != p.alter:
                h.pitch = Pitch(step=p.step, alter=alter, octave=p.octave)
                moved = True
        if moved:
            e.provenance.append(Provenance(stage="repair", rule=RULE, before={"pitches": before}))
            changed += 1
    return changed


def consistent_keys(score: Score, book: OmrBook) -> list[Repair]:
    starts, movements = _layout(book)
    repairs: list[Repair] = []
    for part in score.parts:
        for staff in part.staves:
            first: list[str] = []  # where each corrected system starts
            fixed: list[str] = []
            notes = 0
            for stretch in _stretches(staff, starts, movements):
                key = _winner(stretch)
                if key is None:
                    continue
                for segment in stretch:
                    if segment.fifths == key:
                        continue
                    for m in segment.measures:
                        notes += _respell(m, segment.fifths, key)
                        if m.key is not None or m.index in movements:
                            mode = m.key.mode if m.key is not None else None
                            m.key = KeySignature(fifths=key, mode=mode)
                        fixed.append(m.number or str(m.index + 1))
                    first.append(fixed[-len(segment.measures)])
            if not fixed:
                continue
            _drop_restated_keys(staff, movements)
            detail = (
                f"{part.name or part.id}: key signature misread on the system(s) from measure "
                f"{', '.join(first)}; set to the key read on the rest of the piece "
                f"({notes} note(s) respelled)"
            )
            repairs.append(
                Repair(rule=RULE, part=part.id, staff=staff.number, detail=detail, measures=fixed)
            )
    return repairs


def _drop_restated_keys(staff: Staff, movements: set[int]) -> None:
    """After a repair, a system may restate the key already in force; keep only changes."""
    fifths: int | None = None
    for m in staff.measures:
        if m.key is None:
            continue
        if m.key.fifths == fifths and m.index not in movements:
            m.key = None
        else:
            fifths = m.key.fifths
