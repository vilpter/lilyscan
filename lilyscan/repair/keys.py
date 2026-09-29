"""Key-signature repair.

Audiveris reads the key signature afresh at the start of every system, and on photos
it often misses an accidental there (three sharps read as two, or as one), which puts
every note that key should alter a semitone off for the whole system. Printed music
restates the same key on every system; a real change of key comes in the middle of a
system or after a double bar. So within each stretch of a piece between real changes,
the key read on most systems wins (on a tie, the one with more accidentals: a missed
accidental is likelier than an invented one). Systems read in another key are set to
it, and the notes that the misread key had altered follow.

Where no staff changes key, the staves of a piece vote together: a viola's alto-clef
key signature, say, can be misread on most of its systems while the other parts read
it right. A staff takes the piece's key when it reads it on its first system or on
most of them; a transposing instrument, or horns written without a key, read another
key throughout and keep their own vote.
"""

from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass

from lilyscan.engine.audiveris.omr import OmrBook
from lilyscan.ir.models import KeySignature, Measure, Part, Pitch, Provenance, Score, Staff
from lilyscan.ir.musicxml import added_rest, written_measures
from lilyscan.repair import Repair

RULE = "key-signature"
_SHARPS = "FCGDAEB"
_FLATS = "BEADGCF"
_DOUBLE_BARS = frozenset({"light-light", "light-heavy", "heavy-light", "heavy-heavy"})
# Instruments written in another key than they sound, and ones written as they sound.
_TRANSPOSING = re.compile(
    r"clar|\bcl\b|trump|\btpt|\btr\b|horn|\bhn\b|\bcor\b|sax|cornet|flug|"
    r"\bin [a-g](?:b|#|s)?\b",
    re.IGNORECASE,
)
_CONCERT = re.compile(
    r"viol|vln|vla|cell|\bvc\b|bass|flute|\bfl\b|oboe|\bob\b|bassoon|\bbsn\b|"
    r"piano|pno|organ|harp|guitar|recorder|trombone|\btbn\b|tuba",
    re.IGNORECASE,
)


def may_transpose(part: Part, staff: Staff) -> bool:
    """Whether a staff that reads another key than the rest of the piece could be a
    transposing instrument, which would mean it is right to. Transposing instruments are
    written in treble clef; an alto, tenor or bass clef, or a name like Viola, says the
    part sounds as written. Audiveris names a part it cannot name "Voice"."""
    if part.transpose_semitones:
        return True
    names = " ".join(n for n in (part.name, part.abbreviation) if n and n != "Voice")
    if _TRANSPOSING.search(names):
        return True
    if _CONCERT.search(names):
        return False
    return not any(c.sign in ("C", "F") for m in staff.measures for c in m.clefs)


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
    """Engine measure indices (stacks) where systems start, and those where pieces start."""
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
    for m, written in zip(staff.measures, written_measures(staff.measures), strict=True):
        changed = m.key is not None and m.key.fifths != fifths
        if m.key is not None:
            fifths = m.key.fifths
        first = not added_rest(m)  # a rest added after a multi-measure rest starts nothing
        new_piece = (first and written in movements) or previous is None
        at_system = first and written in system_start
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


def _readings(stretch: list[_Segment]) -> list[int]:
    """The key read at the start of each system of a stretch."""
    return [s.fifths for s in stretch if s.system_start]


def _vote(readings: list[int]) -> int | None:
    """The key read most often, then the one with more accidentals; None on a draw
    between keys with as many (as many systems in two sharps as in two flats)."""
    ranked = sorted(Counter(readings).items(), key=lambda kv: (kv[1], abs(kv[0])), reverse=True)
    if not ranked:
        return None
    (best, n), *rest = ranked
    if rest and rest[0][1] == n and abs(rest[0][0]) == abs(best):
        return None
    return best


def _targets(
    stretches: list[list[list[_Segment]]], pieces: list[int], keeps: list[bool]
) -> dict[int, int]:
    """The key each stretch of each staff should be in, by the stretch's ``id``.

    ``keeps[k]``: staff ``k`` could be a transposing instrument, so it keeps a key it
    reads throughout. Another staff takes the piece's key when most staves read it.
    """

    def piece(stretch: list[_Segment]) -> int:
        start = stretch[0].measures[0].index
        return max((p for p in pieces if p <= start), default=0)

    targets: dict[int, int] = {}
    for p in {piece(st) for staff in stretches for st in staff}:
        staves = [
            (keep, [st for st in staff if piece(st) == p])
            for keep, staff in zip(keeps, stretches, strict=True)
        ]
        in_piece = [(keep, staff) for keep, staff in staves if staff]
        # The staves vote together only when none of them changes key in the piece.
        together = all(len(staff) == 1 for _, staff in in_piece)
        readings_all = [r for _, staff in in_piece for r in _readings(staff[0])]
        key = _vote(readings_all) if together else None
        # One or two staves reading another key throughout, against most of the others:
        # misread, unless the staff could be a transposing instrument.
        agree = sum(1 for _, staff in in_piece if _vote(_readings(staff[0])) == key)
        most = key is not None and agree >= 2 and agree > len(in_piece) - agree
        for keep, staff in in_piece:
            for stretch in staff:
                readings = _readings(stretch)
                own = _vote(readings)
                follows = key in (readings[0], own) or (most and not keep)
                if key is not None and readings and follows:
                    targets[id(stretch)] = key
                elif own is not None:
                    targets[id(stretch)] = own
    return targets


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
    staves = [(part, staff) for part in score.parts for staff in part.staves]
    stretches = [_stretches(staff, starts, movements) for _, staff in staves]
    keeps = [may_transpose(part, staff) for part, staff in staves]
    targets = _targets(stretches, sorted(movements), keeps)
    repairs: list[Repair] = []
    for (part, staff), own in zip(staves, stretches, strict=True):
        first: list[str] = []  # where each corrected system starts
        fixed: list[str] = []
        notes = 0
        for stretch in own:
            key = targets.get(id(stretch))
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
            f"{', '.join(first)}; set to the key the piece is read in "
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


def ensemble_keys(parts: list[Part], keeps: list[bool]) -> dict[int, list[str]]:
    """Parts of a combined score, lined up measure by measure, where one or two read
    another key than the others at the same measures: set to the others' key, with the
    notes it alters respelled. ``keeps[k]``: part ``k`` transposes (or may), so its key
    is its own. Returns the measures changed, by part.

    A stretch of disagreement that borders a change of the others' key is left alone: a
    key change a few measures off is more likely a part lined up a little wrong than a
    misread key signature.
    """
    voters = [k for k, keep in enumerate(keeps) if not keep]
    if len(voters) < 3:
        return {}
    in_force: dict[int, list[int]] = {}
    for k in voters:
        fifths, keys = 0, []
        for m in parts[k].staves[0].measures:
            if m.key is not None:
                fifths = m.key.fifths
            keys.append(fifths)
        in_force[k] = keys
    columns = min(len(v) for v in in_force.values())
    majority: list[int | None] = []
    for c in range(columns):
        (key, n), *_ = Counter(in_force[k][c] for k in voters).most_common()
        majority.append(key if n >= 2 and n > len(voters) - n else None)
    turns = {c for c in range(1, columns) if majority[c] != majority[c - 1]}
    changed: dict[int, list[str]] = {}
    targets: dict[int, list[int]] = {k: list(v) for k, v in in_force.items()}
    for k in voters:
        c = 0
        while c < columns:
            if majority[c] is None or in_force[k][c] == majority[c]:
                c += 1
                continue
            end = c
            while (
                end + 1 < columns
                and majority[end + 1] == majority[c]
                and (in_force[k][end + 1] != majority[c])
            ):
                end += 1
            if not any(t in turns for t in range(c, end + 2)):
                for col in range(c, end + 1):
                    old, target = in_force[k][col], majority[col]
                    assert target is not None
                    targets[k][col] = target
                    for staff in parts[k].staves:
                        if col < len(staff.measures):
                            _respell(staff.measures[col], old, target)
                    m = parts[k].staves[0].measures[col]
                    changed.setdefault(k, []).append(m.number or str(m.index + 1))
            c = end + 1
    for k in changed:
        for staff in parts[k].staves:
            previous = None
            for c, m in enumerate(staff.measures[:columns]):
                key = targets[k][c]
                if key != previous:
                    mode = m.key.mode if m.key is not None else None
                    m.key = KeySignature(fifths=key, mode=mode)
                elif m.key is not None:
                    m.key = None
                previous = key
    return changed
