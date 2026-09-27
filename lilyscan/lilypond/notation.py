"""IR values -> LilyPond tokens (Dutch note names, absolute octaves)."""

from __future__ import annotations

from fractions import Fraction

from lilyscan.ir.models import Clef, KeySignature, Pitch, TimeSignature

_ALTER_SUFFIX = {-2: "eses", -1: "es", 0: "", 1: "is", 2: "isis"}


def pitch_name(step: str, alter: int) -> str:
    """Dutch name without octave: c, cis, des, es (E-flat), as (A-flat), bes, ..."""
    base = step.lower()
    if base in ("e", "a") and alter < 0:
        return base + _ALTER_SUFFIX[alter][1:]  # es, eses -> es, ses; as, ases
    return base + _ALTER_SUFFIX[alter]


def octave_marks(octave: int) -> str:
    """LilyPond's unmarked octave is the one starting at C3."""
    return "'" * (octave - 3) if octave >= 3 else "," * (3 - octave)


def absolute_pitch(p: Pitch) -> str:
    return pitch_name(p.step, p.alter) + octave_marks(p.octave)


_TYPE_DENOMINATOR = {
    "maxima": "\\maxima",
    "long": "\\longa",
    "breve": "\\breve",
    "whole": "1",
    "half": "2",
    "quarter": "4",
    "eighth": "8",
    "16th": "16",
    "32nd": "32",
    "64th": "64",
    "128th": "128",
    "256th": "256",
}
# Undotted values in quarter notes, longest first.
_BASES: list[tuple[Fraction, str]] = [
    (Fraction(8), "\\breve"),
    (Fraction(4), "1"),
    (Fraction(2), "2"),
    (Fraction(1), "4"),
    (Fraction(1, 2), "8"),
    (Fraction(1, 4), "16"),
    (Fraction(1, 8), "32"),
    (Fraction(1, 16), "64"),
    (Fraction(1, 32), "128"),
]


def dotted_value(base: Fraction, dots: int) -> Fraction:
    return base * (2 - Fraction(1, 2**dots))


def single_duration(length: Fraction, max_dots: int = 2) -> str | None:
    """One LilyPond duration for ``length`` quarters, if one exists (e.g. 3/2 -> '4.')."""
    for base, token in _BASES:
        for dots in range(max_dots + 1):
            if dotted_value(base, dots) == length:
                return token + "." * dots
    return None


def _greedy(length: Fraction) -> tuple[list[str], Fraction]:
    """Greedy decomposition into dotted durations, and what is left over."""
    out: list[str] = []
    remaining = length
    while remaining > 0:
        for base, token in _BASES:
            if base <= remaining:
                dots = 0
                while dots < 2 and dotted_value(base, dots + 1) <= remaining:
                    dots += 1
                out.append(token + "." * dots)
                remaining -= dotted_value(base, dots)
                break
        else:  # shorter than a 128th
            break
    return out, remaining


def split_duration(length: Fraction) -> list[str]:
    """Greedy decomposition into dotted durations (a remainder under a 128th is dropped)."""
    return _greedy(length)[0]


def skips(length: Fraction) -> list[str]:
    """Spacer rests lasting exactly ``length`` quarters. A length that dotted durations
    cannot add up to (up to a triplet position, say) ends with a scaled skip, ``s1*1/12``."""
    tokens, rest = _greedy(length)
    if length > 0 and rest > 0:
        tokens.append(f"1*{rest / 4}")
    return ["s" + t for t in tokens]


def written_duration(
    note_type: str | None, dots: int, duration: Fraction, tuplet: tuple[int, int] | None
) -> str:
    """Duration token for an event, preferring its notated type."""
    if note_type in _TYPE_DENOMINATOR:
        return _TYPE_DENOMINATOR[note_type] + "." * dots
    written = duration * Fraction(tuplet[0], tuplet[1]) if tuplet else duration
    token = single_duration(written, max_dots=3)
    return token if token is not None else "4"


_MAJOR_TONICS = [
    "ces",
    "ges",
    "des",
    "as",
    "es",
    "bes",
    "f",
    "c",
    "g",
    "d",
    "a",
    "e",
    "b",
    "fis",
    "cis",
]
_MINOR_TONICS = [
    "as",
    "es",
    "bes",
    "f",
    "c",
    "g",
    "d",
    "a",
    "e",
    "b",
    "fis",
    "cis",
    "gis",
    "dis",
    "ais",
]


def key_command(key: KeySignature) -> str:
    if key.mode == "minor":
        return f"\\key {_MINOR_TONICS[key.fifths + 7]} \\minor"
    return f"\\key {_MAJOR_TONICS[key.fifths + 7]} \\major"


def time_command(time: TimeSignature) -> str:
    return f"\\time {time.beats}/{time.beat_type}"


_CLEFS = {
    ("G", 2): "treble",
    ("G", 1): "french",
    ("F", 4): "bass",
    ("F", 3): "varbaritone",
    ("F", 5): "subbass",
    ("C", 1): "soprano",
    ("C", 2): "mezzosoprano",
    ("C", 3): "alto",
    ("C", 4): "tenor",
    ("C", 5): "baritone",
}


def clef_command(clef: Clef) -> str:
    if clef.sign == "percussion":
        return "\\clef percussion"
    if clef.sign in ("TAB", "none"):
        return "\\clef treble"
    default_line = {"G": 2, "F": 4, "C": 3}[clef.sign]
    name = _CLEFS.get((clef.sign, clef.line or default_line), "treble")
    if clef.octave_change:
        mark = "_" if clef.octave_change < 0 else "^"
        return f'\\clef "{name}{mark}{8 if abs(clef.octave_change) == 1 else 15}"'
    return f"\\clef {name}"


# MusicXML <kind> -> LilyPond chordmode modifier.
CHORD_MODIFIERS = {
    "major": "",
    "minor": ":m",
    "augmented": ":aug",
    "diminished": ":dim",
    "dominant": ":7",
    "major-seventh": ":maj7",
    "minor-seventh": ":m7",
    "diminished-seventh": ":dim7",
    "augmented-seventh": ":aug7",
    "half-diminished": ":m7.5-",
    "major-minor": ":m7+",
    "major-sixth": ":6",
    "minor-sixth": ":m6",
    "dominant-ninth": ":9",
    "major-ninth": ":maj9",
    "minor-ninth": ":m9",
    "dominant-11th": ":11",
    "major-11th": ":maj11",
    "minor-11th": ":m11",
    "dominant-13th": ":13",
    "major-13th": ":maj13",
    "minor-13th": ":m13",
    "suspended-second": ":sus2",
    "suspended-fourth": ":sus4",
    "power": ":5",
}


def chord_root(name: str) -> str:
    """'F#' / 'Bb' / 'Ebb' -> 'fis' / 'bes' / 'eses'."""
    step = name[0].upper()
    alter = name.count("#") - name.count("b", 1)
    return pitch_name(step, max(-2, min(2, alter)))


_TRANSPOSITION_NAMES = ["c", "des", "d", "es", "e", "f", "ges", "g", "as", "a", "bes", "b"]


def transposition_pitch(semitones: int) -> str:
    """Written-to-sounding interval as a \\transposition pitch (0 -> c', -2 -> bes)."""
    midi = 60 + semitones
    octave = midi // 12 - 1
    return _TRANSPOSITION_NAMES[midi % 12] + octave_marks(octave)


def lily_string(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'
