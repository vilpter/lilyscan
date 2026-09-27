"""Octave-clef repair.

Engines often miss the small "8" under a treble clef used for tenor voices (and
read the clef as a plain treble clef), putting the whole line an octave too high.
A staff is repaired when there is tenor evidence (its part name, or its place as
the third of four voices in a choir) and its notes sit where a tenor line written
in a plain treble clef would sound an octave above a tenor's range.
"""

from __future__ import annotations

import re
from statistics import median

from lilyscan.ir.models import Clef, Part, Pitch, Provenance, Score, Staff
from lilyscan.repair import Repair

RULE = "octave-clef"
_TENOR = re.compile(r"tenor|^t\.?$|^ten\.?$", re.IGNORECASE)
_VOCAL = re.compile(r"soprano|alto|tenor|bass|^[satb]\.?$", re.IGNORECASE)

# A tenor line sounds roughly C3-A4 (median around A3 = MIDI 57). Read an octave too
# high it centres around A4 (69). Repair when the median is at or above D4 (62), well
# clear of any plausible sounding tenor line.
_OCTAVE_HIGH_MEDIAN = 62


def _is_tenor(part: Part, score: Score) -> bool:
    for label in (part.name or "", part.abbreviation or ""):
        if _TENOR.search(label.strip()):
            return True
    # Unnamed choir: four single-staff vocal parts, this one third.
    vocal = [
        p for p in score.parts if any(_VOCAL.search(x or "") for x in (p.name, p.abbreviation))
    ]
    return len(score.parts) == 4 and len(vocal) >= 3 and score.parts.index(part) == 2


def _plain_treble_throughout(staff: Staff) -> bool:
    clefs = [c for m in staff.measures for c in m.clefs]
    return bool(clefs) and all(
        c.sign == "G" and c.line in (2, None) and c.octave_change == 0 for c in clefs
    )


def _midi_notes(staff: Staff) -> list[int]:
    return [p.midi for m in staff.measures for v in m.voices for e in v.events for p in e.pitches]


def octave_clefs(score: Score) -> list[Repair]:
    repairs: list[Repair] = []
    for part in score.parts:
        if len(part.staves) != 1 or not _is_tenor(part, score):
            continue
        staff = part.staves[0]
        notes = _midi_notes(staff)
        centre = median(notes) if notes else 0
        if not notes or not _plain_treble_throughout(staff) or centre < _OCTAVE_HIGH_MEDIAN:
            continue
        for m in staff.measures:
            m.clefs = [Clef(sign="G", line=2, octave_change=-1, offset=c.offset) for c in m.clefs]
            for v in m.voices:
                for e in v.events:
                    if not e.notes:
                        continue
                    before = [h.pitch.label for h in e.notes]
                    for h in e.notes:
                        h.pitch = Pitch(
                            step=h.pitch.step, alter=h.pitch.alter, octave=h.pitch.octave - 1
                        )
                    e.provenance.append(
                        Provenance(stage="repair", rule=RULE, before={"pitches": before})
                    )
        detail = "treble clef read without its 8; lowered the staff an octave"
        score.provenance.append(
            Provenance(
                stage="repair",
                rule=RULE,
                before={"part": part.id, "staff": staff.number, "detail": detail},
            )
        )
        repairs.append(
            Repair(
                rule=RULE,
                part=part.id,
                staff=staff.number,
                detail=f"{part.name or part.id}: {detail} (median note was MIDI {centre:.0f})",
                measures=[m.number or str(m.index + 1) for m in staff.measures],
            )
        )
    return repairs
