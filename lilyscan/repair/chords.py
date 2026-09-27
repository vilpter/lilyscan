"""Chord-name grammar: printed chord symbols (``F#m7b5``, ``Bb/D``) to MusicXML kinds."""

from __future__ import annotations

import re
from dataclasses import dataclass

_NAME = re.compile(r"^([A-G])([#b♯♭]?)([^/]*?)(?:/([A-G])([#b♯♭]?))?$")

# Printed suffix -> MusicXML <kind>. Case matters only for m (minor) vs M (major).
_KINDS = {
    "": "major",
    "M": "major",
    "maj": "major",
    "m": "minor",
    "mi": "minor",
    "min": "minor",
    "-": "minor",
    "7": "dominant",
    "maj7": "major-seventh",
    "M7": "major-seventh",
    "ma7": "major-seventh",
    "Δ7": "major-seventh",
    "Δ": "major-seventh",
    "m7": "minor-seventh",
    "mi7": "minor-seventh",
    "min7": "minor-seventh",
    "-7": "minor-seventh",
    "dim": "diminished",
    "°": "diminished",
    "dim7": "diminished-seventh",
    "°7": "diminished-seventh",
    "m7b5": "half-diminished",
    "m7-5": "half-diminished",
    "-7b5": "half-diminished",
    "ø": "half-diminished",
    "ø7": "half-diminished",
    "aug": "augmented",
    "+": "augmented",
    "aug7": "augmented-seventh",
    "+7": "augmented-seventh",
    "mmaj7": "major-minor",
    "mM7": "major-minor",
    "m(maj7)": "major-minor",
    "6": "major-sixth",
    "m6": "minor-sixth",
    "9": "dominant-ninth",
    "maj9": "major-ninth",
    "m9": "minor-ninth",
    "11": "dominant-11th",
    "m11": "minor-11th",
    "13": "dominant-13th",
    "maj13": "major-13th",
    "m13": "minor-13th",
    "sus2": "suspended-second",
    "sus4": "suspended-fourth",
    "sus": "suspended-fourth",
    "5": "power",
}
# Suffixes that can be matched case-insensitively (OCR often upper-cases "SUS4").
_FOLDED = {k.lower(): v for k, v in _KINDS.items() if "m" not in k.lower()}


@dataclass(frozen=True)
class ChordName:
    root: str  # "F#", "Bb"
    kind: str  # MusicXML kind
    bass: str | None
    text: str

    @property
    def decorated(self) -> bool:
        """More than a bare letter, so unlikely to be a word or syllable."""
        return self.kind != "major" or self.bass is not None or len(self.root) > 1


def _accidental(mark: str) -> str:
    return {"♯": "#", "♭": "b"}.get(mark, mark)


def parse_chord_name(text: str) -> ChordName | None:
    """``"F#m7b5"`` -> ChordName("F#", "half-diminished", None); None if not a chord name."""
    match = _NAME.match(text.strip())
    if not match:
        return None
    step, acc, suffix, bass_step, bass_acc = match.groups()
    suffix = suffix.replace(" ", "")
    kind = _KINDS.get(suffix) or _FOLDED.get(suffix.lower())
    if kind is None:
        return None
    bass = f"{bass_step}{_accidental(bass_acc)}" if bass_step else None
    return ChordName(f"{step}{_accidental(acc)}", kind, bass, text.strip())
