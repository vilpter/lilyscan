"""IR models: Score -> Parts -> Staves -> Measures -> Voices -> Events.

Times are exact fractions of a quarter note (``Fraction``), serialized as strings
such as ``"3/2"``. Every event can carry its source bounding box, a confidence,
and the provenance of whichever stage created or changed it.
"""

from __future__ import annotations

from fractions import Fraction
from typing import Annotated, Any, Literal

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, PlainSerializer


def _to_fraction(v: Any) -> Fraction:
    if isinstance(v, Fraction):
        return v
    if isinstance(v, bool):
        raise TypeError("booleans are not durations")
    if isinstance(v, int | str):
        return Fraction(v)
    if isinstance(v, float):
        return Fraction(v).limit_denominator(1024)
    raise TypeError(f"cannot convert {type(v).__name__} to Fraction")


Frac = Annotated[
    Fraction,
    BeforeValidator(_to_fraction),
    PlainSerializer(str, return_type=str),
]

Step = Literal["C", "D", "E", "F", "G", "A", "B"]
_STEP_SEMITONES = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
_STEP_INDEX = {s: i for i, s in enumerate("CDEFGAB")}


class _Model(BaseModel):
    model_config = ConfigDict(extra="forbid")


class BBox(_Model):
    """Pixel rectangle on a source page (0-based page index)."""

    page: int
    x: float
    y: float
    w: float
    h: float


class Provenance(_Model):
    """Which stage created or modified an element, and what it was before."""

    stage: str  # "audiveris", "musicxml", "vector-oracle", "repair", "user", ...
    rule: str | None = None
    before: dict[str, Any] | None = None


class Pitch(_Model):
    step: Step
    alter: int = Field(default=0, ge=-2, le=2)
    octave: int  # scientific pitch notation: middle C is C4

    @property
    def midi(self) -> int:
        return 12 * (self.octave + 1) + _STEP_SEMITONES[self.step] + self.alter

    @property
    def diatonic(self) -> int:
        """Diatonic step number (C4 = 28); independent of alteration."""
        return 7 * self.octave + _STEP_INDEX[self.step]

    def key(self) -> tuple[str, int, int]:
        return (self.step, self.alter, self.octave)

    @property
    def label(self) -> str:
        """Human-readable name, e.g. ``F#4`` or ``Eb2``."""
        accidental = "#" * self.alter if self.alter > 0 else "b" * -self.alter
        return f"{self.step}{accidental}{self.octave}"


class Lyric(_Model):
    text: str
    verse: int = 1
    syllabic: Literal["single", "begin", "middle", "end"] = "single"
    extend: bool = False


class NoteHead(_Model):
    pitch: Pitch
    tie_start: bool = False
    tie_stop: bool = False
    # Whether an accidental is printed (courtesy or required); pitch.alter holds
    # the sounding alteration either way.
    accidental_shown: bool = False


class Event(_Model):
    """A note, chord, or rest in one voice.

    ``offset`` is measured from the start of the measure; ``duration`` is the
    sounding duration (tuplets already applied). Grace notes have duration 0.
    """

    kind: Literal["note", "chord", "rest"]
    offset: Frac
    duration: Frac
    notes: list[NoteHead] = Field(default_factory=list)
    # Notated form, kept so output can be engraved the way the source was.
    note_type: str | None = None  # "quarter", "eighth", "16th", "whole", ...
    dots: int = 0
    tuplet: tuple[int, int] | None = None  # (actual, normal), e.g. (3, 2)
    grace: bool = False
    measure_rest: bool = False
    lyrics: list[Lyric] = Field(default_factory=list)
    articulations: list[str] = Field(default_factory=list)
    confidence: float | None = None
    bbox: BBox | None = None
    provenance: list[Provenance] = Field(default_factory=list)

    @property
    def pitches(self) -> list[Pitch]:
        return [n.pitch for n in self.notes]


class Voice(_Model):
    number: int
    events: list[Event] = Field(default_factory=list)

    def duration(self) -> Fraction:
        return sum((e.duration for e in self.events if not e.grace), Fraction(0))


class Clef(_Model):
    sign: Literal["G", "F", "C", "percussion", "TAB", "none"]
    line: int | None = None
    octave_change: int = 0
    offset: Frac = Fraction(0)


class KeySignature(_Model):
    fifths: int = Field(ge=-7, le=7)
    mode: Literal["major", "minor"] | None = None  # None: not stated in the source


class TimeSignature(_Model):
    beats: int
    beat_type: int
    symbol: Literal["common", "cut", "normal"] = "normal"

    @property
    def measure_length(self) -> Fraction:
        return Fraction(4 * self.beats, self.beat_type)


class ChordSymbol(_Model):
    offset: Frac
    root: str  # e.g. "F#", "Bb"
    kind: str  # MusicXML kind, e.g. "major", "minor-seventh", "half-diminished"
    bass: str | None = None
    text: str | None = None  # the label as printed, when known


class Barline(_Model):
    style: str = "regular"  # "regular", "light-light", "light-heavy", ...
    repeat: Literal["forward", "backward"] | None = None
    ending: str | None = None  # volta number(s), e.g. "1" or "1, 2"
    ending_type: Literal["start", "stop", "discontinue"] | None = None


class Measure(_Model):
    """One measure of one staff. ``index`` is 0-based and aligned across staves."""

    index: int
    number: str  # as printed, e.g. "12", "12a", "" for pickups
    clefs: list[Clef] = Field(default_factory=list)  # changes within this measure
    key: KeySignature | None = None  # set when the key is stated or changes here
    time: TimeSignature | None = None  # set when the time signature changes here
    voices: list[Voice] = Field(default_factory=list)
    chord_symbols: list[ChordSymbol] = Field(default_factory=list)
    left_barline: Barline | None = None
    right_barline: Barline | None = None
    implicit: bool = False  # pickup / incomplete measure not counted in numbering
    bbox: BBox | None = None
    confidence: float | None = None

    def voice(self, number: int) -> Voice:
        for v in self.voices:
            if v.number == number:
                return v
        v = Voice(number=number)
        self.voices.append(v)
        return v


class Staff(_Model):
    number: int  # 1-based within the part
    measures: list[Measure] = Field(default_factory=list)


class Part(_Model):
    id: str
    name: str | None = None
    abbreviation: str | None = None
    # Written-to-sounding transposition in semitones (e.g. -2 for B-flat clarinet),
    # when known. Pitches in the IR are always as written.
    transpose_semitones: int | None = None
    staves: list[Staff] = Field(default_factory=list)


class Score(_Model):
    title: str | None = None
    composer: str | None = None
    parts: list[Part] = Field(default_factory=list)
    source: str | None = None  # e.g. "audiveris 5.11.0", "musicxml", "synthetic"
    provenance: list[Provenance] = Field(default_factory=list)

    def staves(self) -> list[tuple[Part, Staff]]:
        return [(p, s) for p in self.parts for s in p.staves]
