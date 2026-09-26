"""Seeded generator of random-but-valid scores, written as MusicXML ground truth.

Every piece is fully determined by its ``PieceSpec`` (category + seed), so the
corpus can be rebuilt anywhere instead of being committed. Texts for lyrics are
short public-domain liturgical and folk lines.
"""

from __future__ import annotations

import random
import re
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path
from typing import Any, Literal

from music21 import chord as m21chord
from music21 import (
    clef,
    harmony,
    instrument,
    key,
    layout,
    metadata,
    meter,
    note,
    stream,
    tie,
)

Category = Literal["solo", "piano", "satb", "leadsheet", "quartet"]
Syllabic = Literal["single", "begin", "middle", "end"]

LYRICS: dict[str, list[str]] = {
    "lat": [
        "Ky-ri-e e-le-i-son Chri-ste e-le-i-son",
        "Glo-ri-a in ex-cel-sis De-o et in ter-ra pax ho-mi-ni-bus",
        "Ag-nus De-i qui tol-lis pec-ca-ta mun-di mi-se-re-re no-bis",
        "San-ctus san-ctus san-ctus Do-mi-nus De-us Sa-ba-oth",
    ],
    "deu": [
        "Lo-be den Her-ren den mäch-ti-gen Kö-nig der Eh-ren",
        "Ein fes-te Burg ist un-ser Gott ein gu-te Wehr und Waf-fen",
        "Nun dan-ket al-le Gott mit Her-zen Mund und Hän-den",
    ],
    "fra": [
        "Au clair de la lu-ne mon a-mi Pier-rot prê-te-moi ta plu-me",
        "Il est né le di-vin En-fant jou-ez haut-bois ré-son-nez mu-set-tes",
        "À la claire fon-tai-ne m'en al-lant pro-me-ner",
    ],
    "eng": [
        "A-maz-ing grace how sweet the sound that saved a wretch like me",
        "O come all ye faith-ful joy-ful and tri-um-phant",
    ],
}

_LETTERS = "CDEFGAB"
_SHARP_ORDER = "FCGDAEB"
_FLAT_ORDER = "BEADGCF"

# Diatonic ranges (C4 = 28) and clefs per voice/instrument.
_RANGES: dict[str, tuple[int, int]] = {
    "soprano": (28, 39),  # C4-G5
    "alto": (25, 35),  # G3-D5
    "tenor": (21, 32),  # C3-G4
    "bass": (16, 28),  # E2-C4
    "solo": (29, 41),  # D4-A5
    "melody": (28, 39),
    "violin": (26, 42),  # A3-C6
    "viola": (21, 36),  # C3-E5
    "cello": (14, 30),  # C2-E4
    "rh": (28, 42),  # C4-C6
    "lh": (14, 28),  # C2-C4
}


@dataclass(frozen=True)
class PieceSpec:
    id: str
    category: Category
    seed: int
    measures: int
    language: str | None = None  # lyrics language for satb/leadsheet

    def to_dict(self) -> dict[str, object]:
        return {
            "id": self.id,
            "category": self.category,
            "seed": self.seed,
            "measures": self.measures,
            "language": self.language,
        }


@dataclass
class _KeyCtx:
    fifths: int
    alters: dict[str, int] = field(default_factory=dict)

    @classmethod
    def of(cls, fifths: int) -> _KeyCtx:
        order = _SHARP_ORDER if fifths > 0 else _FLAT_ORDER
        sign = 1 if fifths > 0 else -1
        return cls(fifths, {letter: sign for letter in order[: abs(fifths)]})

    def name(self, diatonic: int, extra_alter: int = 0) -> str:
        letter = _LETTERS[diatonic % 7]
        octave = diatonic // 7
        alter = max(-2, min(2, self.alters.get(letter, 0) + extra_alter))
        accidental = "#" * alter if alter > 0 else "-" * -alter
        return f"{letter}{accidental}{octave}"


def _rhythm_cells(ts: tuple[int, int]) -> list[list[Fraction]]:
    q = Fraction(1)
    e = Fraction(1, 2)
    s = Fraction(1, 4)
    t = Fraction(1, 3)
    if ts == (6, 8):
        return [[Fraction(3, 2)], [q, e], [e, e, e], [e, q], [Fraction(3, 4), s, e], [Fraction(3)]]
    return [
        [q],
        [q],
        [e, e],
        [e, e],
        [e, s, s],
        [s, s, e],
        [s, s, s, s],
        [Fraction(3, 2), e],
        [Fraction(2)],
        [Fraction(3, 4), s],
        [t, t, t],
    ]


def _measure_rhythm(rng: random.Random, ts: tuple[int, int]) -> list[Fraction]:
    length = Fraction(4 * ts[0], ts[1])
    cells = _rhythm_cells(ts)
    out: list[Fraction] = []
    remaining = length
    while remaining > 0:
        fitting = [c for c in cells if sum(c) <= remaining]
        if not fitting:
            out.append(remaining)
            break
        cell = rng.choice(fitting)
        out.extend(cell)
        remaining -= sum(cell)
    return out


class _Walker:
    """Random-walk melody generator inside a diatonic range."""

    def __init__(self, rng: random.Random, lo: int, hi: int) -> None:
        self.rng = rng
        self.lo, self.hi = lo, hi
        self.pos = rng.randint(lo + 2, hi - 2)

    def next(self) -> int:
        r = self.rng.random()
        step = self.rng.choice([-1, 1]) if r < 0.55 else self.rng.choice([-2, 2])
        if r > 0.85:
            step = self.rng.choice([-5, -4, -3, 3, 4, 5])
        self.pos += step
        if self.pos < self.lo or self.pos > self.hi:
            self.pos -= 2 * step
        self.pos = max(self.lo, min(self.hi, self.pos))
        return self.pos


def _syllables(text: str) -> list[tuple[str, Syllabic]]:
    out: list[tuple[str, Syllabic]] = []
    for word in text.split():
        parts = word.split("-")
        if len(parts) == 1:
            out.append((parts[0], "single"))
            continue
        for i, syl in enumerate(parts):
            kind: Syllabic = "begin" if i == 0 else "end" if i == len(parts) - 1 else "middle"
            out.append((syl, kind))
    return out


def _setup(m: stream.Measure, ts: tuple[int, int], k: _KeyCtx, mode: str, c: clef.Clef) -> None:
    m.insert(0, c)
    m.insert(0, key.KeySignature(k.fifths).asKey(mode))
    m.insert(0, meter.TimeSignature(f"{ts[0]}/{ts[1]}"))


def _fill_line(
    rng: random.Random,
    part: stream.Stream[Any],
    measures: int,
    ts: tuple[int, int],
    k: _KeyCtx,
    mode: str,
    c: clef.Clef,
    rng_name: str,
    rhythms: list[list[Fraction]] | None = None,
    rest_p: float = 0.08,
) -> list[note.Note]:
    walker = _Walker(rng, *_RANGES[rng_name])
    sung: list[note.Note] = []
    prev: note.Note | None = None
    for i in range(measures):
        m = stream.Measure(number=i + 1)
        if i == 0:
            _setup(m, ts, k, mode, c)
        rhythm = rhythms[i] if rhythms is not None else _measure_rhythm(rng, ts)
        for ql in rhythm:
            if rng.random() < rest_p:
                m.append(note.Rest(quarterLength=ql))
                prev = None
                continue
            if prev is not None and rng.random() < 0.07:
                n = note.Note(prev.pitch.nameWithOctave, quarterLength=ql)
                prev.tie = tie.Tie("start") if prev.tie is None else tie.Tie("continue")
                n.tie = tie.Tie("stop")
            else:
                extra = rng.choice([-1, 1]) if rng.random() < 0.06 else 0
                n = note.Note(k.name(walker.next(), extra), quarterLength=ql)
                sung.append(n)
            m.append(n)
            prev = n
        if i == measures - 1:
            m.rightBarline = "final"
        part.append(m)
    return sung


def _add_lyrics(notes: list[note.Note], text: str) -> None:
    syl = _syllables(text)
    assigned = list(zip(notes, syl * (len(notes) // max(len(syl), 1) + 1), strict=False))
    # Stop at a word boundary so the text never ends on a dangling hyphen.
    while assigned and assigned[-1][1][1] in ("begin", "middle"):
        assigned.pop()
    for n, (s, kind) in assigned:
        n.lyrics.append(note.Lyric(text=s, syllabic=kind))


def _new_part(pid: str, inst: instrument.Instrument, name: str, abbreviation: str) -> stream.Part:
    inst.partName = name
    inst.partAbbreviation = abbreviation
    inst.instrumentAbbreviation = abbreviation
    p = stream.Part(id=pid)
    p.partName = name
    p.partAbbreviation = abbreviation
    p.insert(0, inst)
    return p


def _random_meter(rng: random.Random) -> tuple[int, int]:
    return rng.choices([(4, 4), (3, 4), (2, 4), (6, 8)], weights=[40, 25, 15, 20])[0]


def build_score(spec: PieceSpec) -> stream.Score:
    rng = random.Random(f"{spec.category}:{spec.seed}")
    ts = _random_meter(rng)
    k = _KeyCtx.of(rng.randint(-4, 4))
    mode = "minor" if rng.random() < 0.3 else "major"
    score = stream.Score()
    score.insert(0, metadata.Metadata(title=f"Lilyscan {spec.id}", composer="Generated"))

    if spec.category == "solo":
        p = _new_part("P1", instrument.Flute(), "Flute", "Fl.")
        _fill_line(rng, p, spec.measures, ts, k, mode, clef.TrebleClef(), "solo")
        score.insert(0, p)

    elif spec.category == "quartet":
        strings: list[tuple[instrument.Instrument, str, str, clef.Clef, str]] = [
            (instrument.Violin(), "Violin I", "Vln. I", clef.TrebleClef(), "violin"),
            (instrument.Violin(), "Violin II", "Vln. II", clef.TrebleClef(), "violin"),
            (instrument.Viola(), "Viola", "Vla.", clef.AltoClef(), "viola"),
            (instrument.Violoncello(), "Violoncello", "Vc.", clef.BassClef(), "cello"),
        ]
        for i, (inst, name, abbr, c, r) in enumerate(strings, 1):
            p = _new_part(f"P{i}", inst, name, abbr)
            _fill_line(rng, p, spec.measures, ts, k, mode, c, r)
            score.insert(0, p)

    elif spec.category == "satb":
        text = rng.choice(LYRICS[spec.language or "lat"])
        # Homophonic: one shared rhythm and no rests, so the parts sing together.
        rhythms = [_measure_rhythm(rng, ts) for _ in range(spec.measures)]
        choir: list[tuple[instrument.Instrument, str, str, clef.Clef, str]] = [
            (instrument.Soprano(), "Soprano", "S.", clef.TrebleClef(), "soprano"),
            (instrument.Alto(), "Alto", "A.", clef.TrebleClef(), "alto"),
            (instrument.Tenor(), "Tenor", "T.", clef.Treble8vbClef(), "tenor"),
            (instrument.Bass(), "Bass", "B.", clef.BassClef(), "bass"),
        ]
        for i, (inst, name, abbr, c, r) in enumerate(choir, 1):
            p = _new_part(f"P{i}", inst, name, abbr)
            sung = _fill_line(rng, p, spec.measures, ts, k, mode, c, r, rhythms, rest_p=0.0)
            _add_lyrics(sung, text)
            score.insert(0, p)

    elif spec.category == "leadsheet":
        text = rng.choice(LYRICS[spec.language or "eng"])
        p = _new_part("P1", instrument.Vocalist(), "Voice", "Vo.")
        sung = _fill_line(rng, p, spec.measures, ts, k, mode, clef.TrebleClef(), "melody")
        _add_lyrics(sung, text)
        _add_chord_symbols(rng, p, k, ts)
        score.insert(0, p)

    elif spec.category == "piano":
        # music21 derives MusicXML part ids from object ids unless set explicitly,
        # which would make the ground truth differ between runs.
        rh = stream.PartStaff(id="P1")
        lh = stream.PartStaff(id="P1-lower")
        rh.partName = "Piano"
        rh.partAbbreviation = "Pno."
        rh.insert(0, instrument.Piano())
        lh.insert(0, instrument.Piano())
        _fill_line(rng, rh, spec.measures, ts, k, mode, clef.TrebleClef(), "rh")
        _add_inner_voice(rng, rh, k, ts, probability=0.3)
        _fill_bass_chords(rng, lh, spec.measures, ts, k, mode)
        score.insert(0, rh)
        score.insert(0, lh)
        score.insert(0, layout.StaffGroup([rh, lh], name="Piano", symbol="brace"))
    return score


def _add_inner_voice(
    rng: random.Random,
    part: stream.Stream[Any],
    k: _KeyCtx,
    ts: tuple[int, int],
    probability: float,
) -> None:
    """Split some measures into two voices: the existing line on top, longer notes below."""
    length = Fraction(4 * ts[0], ts[1])
    beat = Fraction(3, 2) if ts == (6, 8) else Fraction(1)
    for m in part.getElementsByClass(stream.Measure):
        if rng.random() >= probability:
            continue
        upper = list(m.notesAndRests)
        pitched = [n for n in upper if isinstance(n, note.Note)]
        if not pitched:
            continue
        # music21's diatonicNoteNum is 1-based (C4 = 29); ours is C4 = 28.
        floor = min(n.pitch.diatonicNoteNum - 1 for n in pitched)
        v1 = stream.Voice(id="1")
        v2 = stream.Voice(id="2")
        for n in upper:
            offset = n.offset
            m.remove(n)
            v1.insert(offset, n)
        position = Fraction(0)
        while position < length:
            ql = min(length - position, rng.choice([beat, 2 * beat]))
            d = max(24, floor - rng.randint(2, 4))
            v2.insert(float(position), note.Note(k.name(d), quarterLength=ql))
            position += ql
        m.insert(0, v1)
        m.insert(0, v2)


def _fill_bass_chords(
    rng: random.Random,
    part: stream.Stream[Any],
    measures: int,
    ts: tuple[int, int],
    k: _KeyCtx,
    mode: str,
) -> None:
    beat = Fraction(3, 2) if ts == (6, 8) else Fraction(1)
    length = Fraction(4 * ts[0], ts[1])
    root_walker = _Walker(rng, 14, 21)
    for i in range(measures):
        m = stream.Measure(number=i + 1)
        if i == 0:
            _setup(m, ts, k, mode, clef.BassClef())
        remaining = length
        while remaining > 0:
            ql = min(remaining, rng.choice([beat, 2 * beat]))
            root = root_walker.next()
            size = rng.choice([1, 2, 3])
            pitches = [k.name(root + 2 * j) for j in range(size)]
            m.append(
                note.Note(pitches[0], quarterLength=ql)
                if size == 1
                else m21chord.Chord(pitches, quarterLength=ql)
            )
            remaining -= ql
        if i == measures - 1:
            m.rightBarline = "final"
        part.append(m)


_QUALITIES = ["", "m", "7", "m7", "maj7", "m7b5", "dim", "sus4"]


def _add_chord_symbols(
    rng: random.Random, part: stream.Part, k: _KeyCtx, ts: tuple[int, int]
) -> None:
    length = Fraction(4 * ts[0], ts[1])
    for m in part.getElementsByClass(stream.Measure):
        offsets = [Fraction(0)] if rng.random() < 0.6 or length < 3 else [Fraction(0), length / 2]
        for off in offsets:
            # music21 figures spell flats as "-" (e.g. "B-m7b5").
            root = k.name(rng.randint(0, 6) + 28)[:-1]
            figure = root + rng.choice(_QUALITIES)
            m.insert(float(off), harmony.ChordSymbol(figure))


def write_ground_truth(spec: PieceSpec, out_dir: Path) -> Path:
    """Write the piece's MusicXML ground truth and return its path."""
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{spec.id}.musicxml"
    build_score(spec).write("musicxml", fp=str(path))
    # music21 copies the title into <movement-title>, which engraves as a duplicate
    # subtitle; the work title alone is enough.
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"\s*<movement-title>[^<]*</movement-title>", "", text, count=1)
    text = re.sub(r"\s*<encoding-date>[^<]*</encoding-date>", "", text, count=1)
    # music21 writes random hash ids for parts and instruments; renumber them in
    # order of appearance so the same spec always yields the same file.
    renamed: dict[str, str] = {}
    for old in re.findall(r'id="([PI][0-9a-f]{32})"', text):
        if old not in renamed:
            count = sum(1 for v in renamed.values() if v[0] == old[0])
            renamed[old] = f"{old[0]}{count + 1}"
    for old, new in renamed.items():
        text = text.replace(old, new)
    path.write_text(text, encoding="utf-8")
    return path
