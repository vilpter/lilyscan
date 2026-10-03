"""Piano accompaniment: an ensemble's other parts reduced onto a piano grand staff.

The melody part is left as it is; this builds the piano part that goes with it. Each
accompanying part goes to the staff for the register it sounds in (its median pitch:
middle C and up on the upper staff, below it on the lower), a double bass an octave
below where it is written. On each staff, parts playing the same rhythm in a measure
merge into chords; otherwise each keeps a voice of its own. A part resting through a
measure where others play is left out there. The staves take the piano's clefs (treble
over bass), and the melody's key and time signatures and barlines.
"""

from __future__ import annotations

import re
from fractions import Fraction
from statistics import median

from lilyscan.ir.models import BOWINGS, Clef, Event, Measure, NoteHead, Part, Staff, Voice
from lilyscan.ir.transpose import Interval, transpose_part

MIDDLE_C = 60
MAX_VOICES = 4  # per staff, as the LilyPond generator engraves
# Written an octave above where it sounds.
_OCTAVE_LOWER = re.compile(r"contrabass|double\s*bass|^bass\b|^cb\.?$|^db\.?$|kontrabass", re.I)


def sounding_shift(part: Part) -> int:
    """Semitones from written to sounding pitch."""
    if part.transpose_semitones:
        return part.transpose_semitones
    names = [part.name or "", part.abbreviation or ""]
    return -12 if any(_OCTAVE_LOWER.search(n.strip()) for n in names) else 0


def _median_pitch(part: Part) -> float | None:
    pitches = [
        p.midi
        for st in part.staves
        for m in st.measures
        for v in m.voices
        for e in v.events
        for p in e.pitches
    ]
    return median(pitches) if pitches else None


def _rhythm(voice: Voice) -> tuple[tuple[Fraction, Fraction, bool, bool], ...]:
    return tuple((e.offset, e.duration, e.grace, e.kind == "rest") for e in voice.events)


def _plays(voice: Voice) -> bool:
    return any(e.notes for e in voice.events)


def _chord(events: list[Event]) -> Event:
    """Events at the same onset, of the same length, as one (a chord when they differ)."""
    first = events[0]
    if first.kind == "rest":
        return first.model_copy(deep=True, update={"lyrics": []})
    heads: dict[tuple[str, int, int], NoteHead] = {}
    for e in events:
        for h in e.notes:
            key = h.pitch.key()
            if key in heads:  # a unison: keep one head, tied if either is
                old = heads[key]
                heads[key] = old.model_copy(
                    update={
                        "tie_start": old.tie_start or h.tie_start,
                        "tie_stop": old.tie_stop or h.tie_stop,
                    }
                )
            else:
                heads[key] = h.model_copy(deep=True)
    notes = sorted(heads.values(), key=lambda h: h.pitch.midi)
    confidences = [e.confidence for e in events if e.confidence is not None]
    # Bowings tell a string player which way to draw the bow: not for the piano.
    articulations = sorted({a for e in events for a in e.articulations if a not in BOWINGS})
    return first.model_copy(
        deep=True,
        update={
            "kind": "chord" if len(notes) > 1 else "note",
            "notes": notes,
            "lyrics": [],
            "articulations": articulations,
            "confidence": min(confidences) if confidences else None,
            "bbox": None,
        },
    )


def _merge(voices: list[Voice]) -> list[Voice]:
    """One staff's voices in one measure, highest first: same rhythms become chords."""
    playing = [v for v in voices if _plays(v)]
    if not playing:
        return [voices[0].model_copy(deep=True, update={"number": 1})] if voices else []
    groups: dict[tuple[tuple[Fraction, Fraction, bool, bool], ...], list[Voice]] = {}
    for v in playing:
        groups.setdefault(_rhythm(v), []).append(v)
    merged = []
    for group in groups.values():
        events = [_chord([v.events[i] for v in group]) for i in range(len(group[0].events))]
        merged.append(Voice(number=0, events=events))

    def height(v: Voice) -> float:
        pitches = [p.midi for e in v.events for p in e.pitches]
        return -median(pitches) if pitches else 0.0

    merged.sort(key=height)
    if len(merged) > MAX_VOICES:
        # Rare with string parts: keep the top voices and the bass line.
        merged = [*merged[: MAX_VOICES - 1], merged[-1]]
    return [v.model_copy(update={"number": k}) for k, v in enumerate(merged, 1)]


def reduce_to_piano(parts: list[Part], reference: Part, name: str = "Piano") -> Part:
    """The piano part made from ``parts``, measure by measure along ``reference``.

    Every part must have as many measures as ``reference`` (see ``combine``).
    """
    upper: list[Part] = []
    lower: list[Part] = []
    for p in parts:
        shift = sounding_shift(p)
        sounding = transpose_part(p, Interval.from_semitones(shift)) if shift else p
        centre = _median_pitch(sounding)
        (lower if centre is not None and centre < MIDDLE_C else upper).append(sounding)
    ref = reference.staves[0].measures
    staves = []
    for number, sources, clef in (
        (1, upper, Clef(sign="G", line=2)),
        (2, lower, Clef(sign="F", line=4)),
    ):
        measures = []
        for i, r in enumerate(ref):
            voices = [
                v
                for s in sources
                for st in s.staves
                if i < len(st.measures)
                for v in st.measures[i].voices
                if v.events
            ]
            m = Measure(
                index=r.index,
                number=r.number,
                clefs=[clef] if i == 0 else [],
                key=r.key,
                time=r.time,
                implicit=r.implicit,
                left_barline=r.left_barline,
                right_barline=r.right_barline,
                voices=_merge(voices),
            )
            measures.append(m)
        staves.append(Staff(number=number, measures=measures))
    return Part(id=name, name=name, abbreviation="Pno.", staves=staves)
