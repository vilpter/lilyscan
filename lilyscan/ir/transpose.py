"""Transposition of IR scores by a spelled interval (M9, D3).

An interval has a diatonic size (steps) and a chromatic size (semitones), so spelling
follows it: a major second up takes F# to G#, not A-flat. Key signatures and chord
symbols move with the notes.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from lilyscan.ir.models import ChordSymbol, KeySignature, Part, Pitch, Score

_STEPS = "CDEFGAB"
_NATURAL = [0, 2, 4, 5, 7, 9, 11]  # semitones of C D E F G A B above C
# Line of fifths position of each natural step (F = -1 ... B = 5).
_FIFTHS = {"F": -1, "C": 0, "G": 1, "D": 2, "A": 3, "E": 4, "B": 5}
# Perfect intervals: unison, fourth, fifth (and their octaves).
_PERFECT = {0, 3, 4}
_NAME = re.compile(r"^([+-]?)([PMmAd])(\d+)$")
# Default spelling of a chromatic size (for concert pitch from a part's transposition).
_BY_SEMITONES = ["P1", "m2", "M2", "m3", "M3", "P4", "A4", "P5", "m6", "M6", "m7", "M7"]


@dataclass(frozen=True)
class Interval:
    steps: int  # diatonic steps, negative downwards
    semitones: int

    @classmethod
    def parse(cls, text: str) -> Interval:
        """``"M2"``, ``"-m3"``, ``"P5"``, ``"A4"``, ``"P8"``, ``"M9"`` (a sign means down)."""
        match = _NAME.match(text.strip())
        if not match:
            raise ValueError(f"not an interval: {text!r} (expected e.g. M2, -m3, P5)")
        sign, quality, number = match.groups()
        n = int(number)
        if n < 1:
            raise ValueError(f"not an interval: {text!r}")
        steps = n - 1
        simple, octaves = steps % 7, steps // 7
        base = _NATURAL[simple] + 12 * octaves
        if simple in _PERFECT:
            offsets = {"P": 0, "A": 1, "d": -1}
        else:
            offsets = {"M": 0, "m": -1, "A": 1, "d": -2}
        if quality not in offsets:
            raise ValueError(f"{text!r}: a {n} is not {quality}")
        down = -1 if sign == "-" else 1
        return cls(down * steps, down * (base + offsets[quality]))

    @classmethod
    def from_semitones(cls, semitones: int) -> Interval:
        """The usual spelling of a chromatic size (e.g. -2 -> a major second down)."""
        size = abs(semitones)
        name = _BY_SEMITONES[size % 12]
        simple = cls.parse(name)
        steps = simple.steps + 7 * (size // 12)
        sign = -1 if semitones < 0 else 1
        return cls(sign * steps, sign * size)

    def __neg__(self) -> Interval:
        return Interval(-self.steps, -self.semitones)


def _natural_midi(step: str, octave: int) -> int:
    return 12 * (octave + 1) + _NATURAL[_STEPS.index(step)]


def transpose_pitch(p: Pitch, interval: Interval) -> Pitch:
    diatonic = p.diatonic + interval.steps
    step, octave = _STEPS[diatonic % 7], diatonic // 7
    alter = p.midi + interval.semitones - _natural_midi(step, octave)
    if not -2 <= alter <= 2:
        raise ValueError(f"{p.label} transposed by {interval} needs a triple accidental")
    return Pitch(step=step, octave=octave, alter=alter)


def _fifths_shift(interval: Interval) -> int:
    """How far the interval moves a key along the line of fifths."""
    c = transpose_pitch(Pitch(step="C", octave=4), interval)
    return _FIFTHS[c.step] + 7 * c.alter


def transpose_key(key: KeySignature, interval: Interval) -> KeySignature:
    fifths = key.fifths + _fifths_shift(interval)
    if not -7 <= fifths <= 7:
        raise ValueError(f"the key moves to {fifths} fifths; choose the enharmonic interval")
    return key.model_copy(update={"fifths": fifths})


def _transpose_name(name: str, interval: Interval) -> str:
    """A chord root or bass such as ``"F#"`` or ``"Bb"``."""
    alter = name.count("#") - name[1:].count("b")
    p = transpose_pitch(Pitch(step=name[0].upper(), octave=4, alter=alter), interval)
    return p.step + ("#" * p.alter if p.alter > 0 else "b" * -p.alter)


def _transpose_chord(c: ChordSymbol, interval: Interval) -> ChordSymbol:
    return c.model_copy(
        update={
            "root": _transpose_name(c.root, interval),
            "bass": _transpose_name(c.bass, interval) if c.bass else None,
            "text": None,
        }
    )


def transpose_part(part: Part, interval: Interval) -> Part:
    """A copy of ``part`` transposed by ``interval`` (notes, keys and chord symbols)."""
    out = part.model_copy(deep=True)
    for staff in out.staves:
        for m in staff.measures:
            if m.key is not None:
                m.key = transpose_key(m.key, interval)
            m.chord_symbols = [_transpose_chord(c, interval) for c in m.chord_symbols]
            for v in m.voices:
                for e in v.events:
                    for head in e.notes:
                        head.pitch = transpose_pitch(head.pitch, interval)
    return out


def to_concert_pitch(score: Score) -> Score:
    """Written pitch to sounding pitch for every transposing part (D3's export option)."""
    out = score.model_copy(deep=True)
    for k, part in enumerate(out.parts):
        if part.transpose_semitones:
            moved = transpose_part(part, Interval.from_semitones(part.transpose_semitones))
            moved.transpose_semitones = None
            out.parts[k] = moved
    return out
