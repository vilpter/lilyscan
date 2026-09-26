"""MusicXML (partwise, ``.musicxml``/``.xml``/``.mxl``) -> IR.

Used for Audiveris output, ground truth, and any second engine. The reader keeps
staff and voice numbers exactly as written, so comparisons see what the engine
produced rather than a re-interpretation.
"""

from __future__ import annotations

import re
import zipfile
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path
from typing import Literal, cast
from xml.etree import ElementTree as ET

from lilyscan.ir.models import (
    Barline,
    ChordSymbol,
    Clef,
    Event,
    KeySignature,
    Lyric,
    Measure,
    NoteHead,
    Part,
    Pitch,
    Provenance,
    Score,
    Staff,
    Step,
    TimeSignature,
)


class MusicXMLError(ValueError):
    pass


def read_musicxml_bytes(path: Path) -> bytes:
    """Return the score document, unpacking ``.mxl`` containers."""
    data = path.read_bytes()
    if not zipfile.is_zipfile(path):
        return data
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        root_name: str | None = None
        if "META-INF/container.xml" in names:
            container = ET.fromstring(z.read("META-INF/container.xml"))
            for el in container.iter():
                if el.tag.endswith("rootfile") and el.get("full-path"):
                    root_name = el.get("full-path")
                    break
        if root_name is None:
            candidates = [n for n in names if n.endswith((".xml", ".musicxml"))]
            candidates = [n for n in candidates if not n.startswith("META-INF/")]
            if not candidates:
                raise MusicXMLError(f"{path.name}: no score document in .mxl")
            root_name = candidates[0]
        return z.read(root_name)


def load_musicxml(path: Path, source: str = "musicxml") -> Score:
    return parse_musicxml(read_musicxml_bytes(path), source=source)


def _text(el: ET.Element | None, path: str, default: str | None = None) -> str | None:
    if el is None:
        return default
    found = el.find(path)
    if found is None or found.text is None:
        return default
    return found.text.strip()


def _int(el: ET.Element | None, path: str, default: int) -> int:
    t = _text(el, path)
    try:
        return int(float(t)) if t is not None else default
    except ValueError:
        return default


_KIND_ALIASES = {"": "none"}

# Literal-typed lookups for enumerated MusicXML values; unknown values map to a default.
_MODES: dict[str | None, Literal["major", "minor"]] = {"major": "major", "minor": "minor"}
_TIME_SYMBOLS: dict[str | None, Literal["common", "cut", "normal"]] = {
    "common": "common",
    "cut": "cut",
}
_CLEF_SIGNS: dict[str | None, Literal["G", "F", "C", "percussion", "TAB", "none"]] = {
    s: s for s in ("G", "F", "C", "percussion", "TAB", "none")
}
_SYLLABIC: dict[str | None, Literal["single", "begin", "middle", "end"]] = {
    s: s for s in ("single", "begin", "middle", "end")
}
_REPEATS: dict[str | None, Literal["forward", "backward"]] = {
    "forward": "forward",
    "backward": "backward",
}
_ENDING_TYPES: dict[str | None, Literal["start", "stop", "discontinue"]] = {
    s: s for s in ("start", "stop", "discontinue")
}


@dataclass
class _PartState:
    divisions: int = 1
    staves: int = 1
    time: TimeSignature | None = None
    measures: dict[int, list[Measure]] = field(default_factory=dict)  # staff -> measures


def parse_musicxml(data: bytes, source: str = "musicxml") -> Score:
    root = ET.fromstring(data)
    # Strip any namespace so lookups work for both plain and namespaced documents.
    for el in root.iter():
        if isinstance(el.tag, str) and el.tag.startswith("{"):
            el.tag = el.tag.split("}", 1)[1]
    if root.tag == "score-timewise":
        raise MusicXMLError("score-timewise documents are not supported")
    if root.tag != "score-partwise":
        raise MusicXMLError(f"not a MusicXML score (root element <{root.tag}>)")

    score = Score(
        title=_text(root, "work/work-title") or _text(root, "movement-title"),
        composer=next(
            (
                c.text.strip()
                for c in root.findall("identification/creator")
                if c.get("type") == "composer" and c.text
            ),
            None,
        ),
        source=source,
        provenance=[Provenance(stage=source)],
    )

    part_info: dict[str, tuple[str | None, str | None]] = {}
    for sp in root.findall("part-list/score-part"):
        pid = sp.get("id", "")
        part_info[pid] = (_text(sp, "part-name"), _text(sp, "part-abbreviation"))

    for part_el in root.findall("part"):
        pid = part_el.get("id", f"P{len(score.parts) + 1}")
        name, abbr = part_info.get(pid, (None, None))
        score.parts.append(_parse_part(part_el, pid, name, abbr))
    return score


def _parse_part(part_el: ET.Element, pid: str, name: str | None, abbr: str | None) -> Part:
    part = Part(id=pid, name=name or None, abbreviation=abbr or None)
    st = _PartState()
    for index, m_el in enumerate(part_el.findall("measure")):
        _parse_measure(m_el, index, st, part)

    part.staves = [Staff(number=n, measures=ms) for n, ms in sorted(st.measures.items())]
    for staff in part.staves:
        for measure in staff.measures:
            for voice in measure.voices:
                voice.events.sort(key=lambda e: (e.offset, not e.grace))
            measure.voices.sort(key=lambda v: v.number)
    return part


def _measure_for(st: _PartState, staff: int, index: int, number: str, implicit: bool) -> Measure:
    measures = st.measures.setdefault(staff, [])
    while len(measures) <= index:
        # Fill gaps so every staff has an entry for every measure index.
        i = len(measures)
        measures.append(Measure(index=i, number=number if i == index else "", implicit=implicit))
    return measures[index]


def _parse_measure(m_el: ET.Element, index: int, st: _PartState, part: Part) -> None:
    number = m_el.get("number", str(index + 1))
    implicit = m_el.get("implicit") == "yes"

    # Make sure every known staff has this measure, even if it has no notes.
    for staff in range(1, st.staves + 1):
        _measure_for(st, staff, index, number, implicit)

    cursor = 0  # in divisions
    last_onset = 0
    last_event: dict[tuple[int, int], Event] = {}

    def frac(divs: int) -> Fraction:
        return Fraction(divs, st.divisions)

    for el in m_el:
        tag = el.tag
        if tag == "attributes":
            _apply_attributes(el, st, part, index, number, implicit, frac(cursor))
        elif tag == "backup":
            cursor -= _int(el, "duration", 0)
            cursor = max(cursor, 0)
        elif tag == "forward":
            cursor += _int(el, "duration", 0)
        elif tag == "note":
            staff = _int(el, "staff", 1)
            voice_no = _int(el, "voice", 1)
            measure = _measure_for(st, staff, index, number, implicit)
            is_chord = el.find("chord") is not None
            is_grace = el.find("grace") is not None
            divs = 0 if is_grace else _int(el, "duration", 0)
            onset = last_onset if is_chord else cursor
            head = _note_head(el)
            key = (staff, voice_no)
            prev = last_event.get(key)
            if is_chord and head is not None and prev is not None and prev.kind != "rest":
                prev.notes.append(head)
                prev.kind = "chord"
                continue
            event = _event(el, frac(onset), frac(divs), head, is_grace)
            measure.voice(voice_no).events.append(event)
            last_event[key] = event
            if not is_chord and not is_grace:
                last_onset = cursor
                cursor += divs
            elif not is_chord:
                last_onset = cursor
        elif tag == "harmony":
            measure = _measure_for(st, _int(el, "staff", 1), index, number, implicit)
            chord = _chord_symbol(el, frac(cursor + _int(el, "offset", 0)))
            if chord is not None:
                measure.chord_symbols.append(chord)
        elif tag == "barline":
            barline = _barline(el)
            for staff in range(1, st.staves + 1):
                measure = _measure_for(st, staff, index, number, implicit)
                if el.get("location", "right") == "left":
                    measure.left_barline = barline
                else:
                    measure.right_barline = barline


def _apply_attributes(
    el: ET.Element,
    st: _PartState,
    part: Part,
    index: int,
    number: str,
    implicit: bool,
    offset: Fraction,
) -> None:
    divisions = _int(el, "divisions", 0)
    if divisions > 0:
        st.divisions = divisions
    staves = _int(el, "staves", 0)
    if staves > 0:
        st.staves = staves
        for staff in range(1, staves + 1):
            _measure_for(st, staff, index, number, implicit)

    all_staves = range(1, st.staves + 1)
    for key_el in el.findall("key"):
        fifths = _int(key_el, "fifths", 0)
        key = KeySignature(fifths=max(-7, min(7, fifths)), mode=_MODES.get(_text(key_el, "mode")))
        targets = [int(key_el.get("number", "0"))] if key_el.get("number") else all_staves
        for staff in targets:
            _measure_for(st, staff, index, number, implicit).key = key

    for time_el in el.findall("time"):
        beats_text = _text(time_el, "beats", "4") or "4"
        beats = sum(int(b) for b in re.findall(r"\d+", beats_text)) or 4
        beat_type = _int(time_el, "beat-type", 4)
        ts = TimeSignature(
            beats=beats,
            beat_type=beat_type,
            symbol=_TIME_SYMBOLS.get(time_el.get("symbol"), "normal"),
        )
        st.time = ts
        targets = [int(time_el.get("number", "0"))] if time_el.get("number") else all_staves
        for staff in targets:
            _measure_for(st, staff, index, number, implicit).time = ts

    for clef_el in el.findall("clef"):
        line = _int(clef_el, "line", 0) or None
        clef = Clef(
            sign=_CLEF_SIGNS.get(_text(clef_el, "sign"), "none"),
            line=line,
            octave_change=_int(clef_el, "clef-octave-change", 0),
            offset=offset,
        )
        staff = int(clef_el.get("number", "1"))
        _measure_for(st, staff, index, number, implicit).clefs.append(clef)

    chromatic = _text(el, "transpose/chromatic")
    if chromatic is not None:
        octave = _int(el, "transpose/octave-change", 0)
        part.transpose_semitones = int(float(chromatic)) + 12 * octave


_STEPS = {"C", "D", "E", "F", "G", "A", "B"}


def _note_head(el: ET.Element) -> NoteHead | None:
    pitch_el = el.find("pitch")
    if pitch_el is not None:
        step = _text(pitch_el, "step", "C") or "C"
        alter_text = _text(pitch_el, "alter")
        alter = round(float(alter_text)) if alter_text else 0
        octave = _int(pitch_el, "octave", 4)
    else:
        unpitched = el.find("unpitched")
        if unpitched is None:
            return None
        step = _text(unpitched, "display-step", "C") or "C"
        alter = 0
        octave = _int(unpitched, "display-octave", 4)
    if step not in _STEPS:
        return None
    ties = {t.get("type") for t in el.findall("tie")}
    ties |= {t.get("type") for t in el.findall("notations/tied")}
    return NoteHead(
        pitch=Pitch(step=cast(Step, step), alter=max(-2, min(2, alter)), octave=octave),
        tie_start="start" in ties,
        tie_stop="stop" in ties,
        accidental_shown=el.find("accidental") is not None,
    )


def _event(
    el: ET.Element, offset: Fraction, duration: Fraction, head: NoteHead | None, grace: bool
) -> Event:
    rest_el = el.find("rest")
    tuplet: tuple[int, int] | None = None
    tm = el.find("time-modification")
    if tm is not None:
        tuplet = (_int(tm, "actual-notes", 1), _int(tm, "normal-notes", 1))
    lyrics: list[Lyric] = []
    for i, ly in enumerate(el.findall("lyric")):
        text = "".join(t.text or "" for t in ly.findall("text")).strip()
        if not text:
            continue
        verse_attr = ly.get("number", "")
        verse = int(verse_attr) if verse_attr.isdigit() else i + 1
        lyrics.append(
            Lyric(
                text=text,
                verse=verse,
                syllabic=_SYLLABIC.get(_text(ly, "syllabic"), "single"),
                extend=ly.find("extend") is not None,
            )
        )
    articulations = [a.tag for a in el.findall("notations/articulations/*")]
    if el.find("notations/fermata") is not None:
        articulations.append("fermata")
    return Event(
        kind="rest" if rest_el is not None or head is None else "note",
        offset=offset,
        duration=duration,
        notes=[head] if head is not None and rest_el is None else [],
        note_type=_text(el, "type"),
        dots=len(el.findall("dot")),
        tuplet=tuplet,
        grace=grace,
        measure_rest=rest_el is not None and rest_el.get("measure") == "yes",
        lyrics=lyrics,
        articulations=articulations,
    )


def _alter_name(step: str, alter: int) -> str:
    return step + ("#" * alter if alter > 0 else "b" * -alter)


def _chord_symbol(el: ET.Element, offset: Fraction) -> ChordSymbol | None:
    root_step = _text(el, "root/root-step")
    if root_step is None:
        return None
    root = _alter_name(root_step, round(float(_text(el, "root/root-alter", "0") or 0)))
    kind_el = el.find("kind")
    kind = (kind_el.text or "").strip() if kind_el is not None else "major"
    kind = _KIND_ALIASES.get(kind, kind) or "major"
    bass_step = _text(el, "bass/bass-step")
    bass = (
        _alter_name(bass_step, round(float(_text(el, "bass/bass-alter", "0") or 0)))
        if bass_step
        else None
    )
    label = kind_el.get("text") if kind_el is not None else None
    return ChordSymbol(offset=offset, root=root, kind=kind, bass=bass, text=label)


def _barline(el: ET.Element) -> Barline:
    repeat_el = el.find("repeat")
    ending_el = el.find("ending")
    return Barline(
        style=_text(el, "bar-style", "regular") or "regular",
        repeat=_REPEATS.get(repeat_el.get("direction") if repeat_el is not None else None),
        ending=ending_el.get("number") if ending_el is not None else None,
        ending_type=_ENDING_TYPES.get(ending_el.get("type") if ending_el is not None else None),
    )
