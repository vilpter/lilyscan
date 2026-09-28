"""IR -> MusicXML (partwise 4.0): the score as Lilyscan has it, with its repairs.

The engine's own MusicXML predates Lilyscan's repairs, and a combined score has none, so
this writes the IR out for other notation programs. It is the inverse of
``lilyscan.ir.musicxml``: whatever that reader takes from a file, this writer puts back,
so a file read and written again reads the same.
"""

from __future__ import annotations

import math
import re
from fractions import Fraction
from pathlib import Path
from xml.etree import ElementTree as ET

from lilyscan.ir.models import Barline, ChordSymbol, Clef, Event, Measure, NoteHead, Part, Score
from lilyscan.ir.transpose import Interval

_DEFAULT_LINE = {"G": 2, "F": 4, "C": 3}
_ACCIDENTALS = {2: "double-sharp", 1: "sharp", 0: "natural", -1: "flat", -2: "flat-flat"}
_TYPES = {
    Fraction(4): "whole",
    Fraction(2): "half",
    Fraction(1): "quarter",
    Fraction(1, 2): "eighth",
    Fraction(1, 4): "16th",
    Fraction(1, 8): "32nd",
    Fraction(1, 16): "64th",
    Fraction(8): "breve",
}
_SAFE_TAG = re.compile(r"^[A-Za-z][\w-]*$")
# Articulations are written as elements named as the reader found them.
_HEADER = (
    '<?xml version="1.0" encoding="UTF-8" standalone="no"?>\n'
    '<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" '
    '"http://www.musicxml.org/dtds/partwise.dtd">\n'
)


def _sub(parent: ET.Element, tag: str, text: str | int | None = None, **attrs: str) -> ET.Element:
    el = ET.SubElement(parent, tag, attrs)
    if text is not None:
        el.text = str(text)
    return el


def _divisions(score: Score) -> int:
    """Divisions per quarter note: every offset and duration a whole number of them."""
    denominators = {1}
    for _, staff in score.staves():
        for m in staff.measures:
            for v in m.voices:
                for e in v.events:
                    denominators.update({e.offset.denominator, e.duration.denominator})
            denominators.update(c.offset.denominator for c in m.clefs)
            denominators.update(c.offset.denominator for c in m.chord_symbols)
    return math.lcm(*denominators)


def _note_type(e: Event) -> tuple[str | None, int]:
    """The written type and dots, from the event or derived from its duration."""
    if e.note_type:
        return e.note_type, e.dots
    written = e.duration
    if e.tuplet:
        written = written * e.tuplet[0] / e.tuplet[1]
    for dots, factor in ((0, Fraction(1)), (1, Fraction(3, 2)), (2, Fraction(7, 4))):
        base = written / factor
        if base in _TYPES:
            return _TYPES[base], dots
    return None, 0


def _voices(measures: list[Measure | None], staff_numbers: list[int]) -> dict[tuple[int, int], int]:
    """(staff, voice) -> voice number written in one measure: kept, unless an earlier
    staff of the part uses the same number in that measure."""
    taken: dict[int, int] = {}
    out: dict[tuple[int, int], int] = {}
    for staff, m in zip(staff_numbers, measures, strict=True):
        for n in sorted({v.number for v in m.voices if v.events} if m else set()):
            written = n
            while written in taken and taken[written] != staff:
                written += 4
            taken[written] = staff
            out[(staff, n)] = written
    return out


def _clef(parent: ET.Element, clef: Clef, staff: int | None) -> None:
    el = _sub(parent, "clef", **({"number": str(staff)} if staff else {}))
    _sub(el, "sign", clef.sign)
    line = clef.line or _DEFAULT_LINE.get(clef.sign)
    if line is not None and clef.sign in _DEFAULT_LINE:
        _sub(el, "line", line)
    if clef.octave_change:
        _sub(el, "clef-octave-change", clef.octave_change)


def _barline(parent: ET.Element, b: Barline, location: str) -> None:
    el = _sub(parent, "barline", location=location)
    if b.style and b.style != "regular":
        _sub(el, "bar-style", b.style)
    if b.ending:
        _sub(
            el,
            "ending",
            number=b.ending,
            type=b.ending_type or ("start" if location == "left" else "stop"),
        )
    if b.repeat:
        _sub(el, "repeat", direction=b.repeat)


def _root(name: str) -> tuple[str, int]:
    step, rest = name[0].upper(), name[1:]
    return step, rest.count("#") - rest.count("b")


def _harmony(parent: ET.Element, c: ChordSymbol, divisions: int) -> None:
    el = _sub(parent, "harmony")
    root = _sub(el, "root")
    step, alter = _root(c.root)
    _sub(root, "root-step", step)
    if alter:
        _sub(root, "root-alter", alter)
    kind = _sub(el, "kind", c.kind)
    if c.text:
        kind.set("text", c.text)
    if c.bass:
        bass = _sub(el, "bass")
        step, alter = _root(c.bass)
        _sub(bass, "bass-step", step)
        if alter:
            _sub(bass, "bass-alter", alter)
    if c.offset:
        _sub(el, "offset", int(c.offset * divisions))


def _tuplet_marks(events: list[Event]) -> dict[int, str]:
    """id(event) -> "start" or "stop" for the brackets of runs of tuplet notes."""
    marks: dict[int, str] = {}
    run: list[Event] = []
    span, filled = Fraction(0), Fraction(0)

    def close() -> None:
        if run:
            marks[id(run[0])] = "start"
            marks[id(run[-1])] = "stop" if len(run) > 1 else "start"

    for e in events:
        if e.tuplet is None or e.grace:
            close()
            run, filled = [], Fraction(0)
            continue
        if not run or (run[0].tuplet != e.tuplet) or filled >= span:
            close()
            actual, normal = e.tuplet
            span = e.duration * actual / normal * normal  # the group's sounding length
            run, filled = [], Fraction(0)
        run.append(e)
        filled += e.duration
    close()
    return marks


def _note(
    parent: ET.Element,
    e: Event,
    head: NoteHead | None,
    chord: bool,
    voice: int,
    staff: int | None,
    divisions: int,
    tuplet_mark: str | None,
) -> None:
    el = _sub(parent, "note")
    if e.grace:
        _sub(el, "grace")
    if chord:
        _sub(el, "chord")
    if head is None:
        _sub(el, "rest", **({"measure": "yes"} if e.measure_rest else {}))
    else:
        pitch = _sub(el, "pitch")
        _sub(pitch, "step", head.pitch.step)
        if head.pitch.alter:
            _sub(pitch, "alter", head.pitch.alter)
        _sub(pitch, "octave", head.pitch.octave)
    if not e.grace:
        _sub(el, "duration", int(e.duration * divisions))
    if head is not None and head.tie_stop:
        _sub(el, "tie", type="stop")
    if head is not None and head.tie_start:
        _sub(el, "tie", type="start")
    _sub(el, "voice", voice)
    kind, dots = _note_type(e)
    if kind and not (head is None and e.measure_rest):
        _sub(el, "type", kind)
        for _ in range(dots):
            _sub(el, "dot")
    if head is not None and head.accidental_shown:
        _sub(el, "accidental", _ACCIDENTALS.get(head.pitch.alter, "natural"))
    if e.tuplet:
        tm = _sub(el, "time-modification")
        _sub(tm, "actual-notes", e.tuplet[0])
        _sub(tm, "normal-notes", e.tuplet[1])
    if staff is not None:
        _sub(el, "staff", staff)
    notations = ET.Element("notations")
    if head is not None and head.tie_stop:
        _sub(notations, "tied", type="stop")
    if head is not None and head.tie_start:
        _sub(notations, "tied", type="start")
    if tuplet_mark and not chord:
        _sub(notations, "tuplet", type=tuplet_mark)
    marks = [a for a in e.articulations if a != "fermata" and _SAFE_TAG.match(a)]
    if marks and not chord:
        arts = _sub(notations, "articulations")
        for a in marks:
            _sub(arts, a)
    if "fermata" in e.articulations and not chord:
        _sub(notations, "fermata")
    if len(notations):
        el.append(notations)
    if not chord:
        for ly in e.lyrics:
            lyric = _sub(el, "lyric", number=str(ly.verse))
            _sub(lyric, "syllabic", ly.syllabic)
            _sub(lyric, "text", ly.text)
            if ly.extend:
                _sub(lyric, "extend")


def _write_measure(
    part_el: ET.Element,
    part: Part,
    i: int,
    divisions: int,
    first: bool,
) -> None:
    staves = part.staves
    measures: list[Measure | None] = [
        st.measures[i] if i < len(st.measures) else None for st in staves
    ]
    lead = next(m for m in measures if m is not None)
    number = lead.number or (str(i) if lead.implicit else str(i + 1))
    m_el = _sub(part_el, "measure", number=number, **({"implicit": "yes"} if lead.implicit else {}))
    multi = len(staves) > 1
    voices = _voices(measures, [st.number for st in staves])

    attrs = ET.Element("attributes")
    if first:
        _sub(attrs, "divisions", divisions)
    if lead.key is not None:
        key = _sub(attrs, "key")
        _sub(key, "fifths", lead.key.fifths)
        if lead.key.mode:
            _sub(key, "mode", lead.key.mode)
    if lead.time is not None:
        time = _sub(
            attrs, "time", **({"symbol": lead.time.symbol} if lead.time.symbol != "normal" else {})
        )
        _sub(time, "beats", lead.time.beats)
        _sub(time, "beat-type", lead.time.beat_type)
    if first and multi:
        _sub(attrs, "staves", len(staves))
    for st, m in zip(staves, measures, strict=True):
        for clef in (c for c in (m.clefs if m else []) if c.offset == 0):
            _clef(attrs, clef, st.number if multi else None)
    if first and part.transpose_semitones:
        semis = part.transpose_semitones
        octaves, chromatic = divmod(abs(semis), 12)
        sign = -1 if semis < 0 else 1
        tr = _sub(attrs, "transpose")
        steps = Interval.from_semitones(sign * chromatic).steps
        _sub(tr, "diatonic", steps)
        _sub(tr, "chromatic", sign * chromatic)
        if octaves:
            _sub(tr, "octave-change", sign * octaves)
    if len(attrs):
        m_el.append(attrs)

    if lead.left_barline is not None:
        _barline(m_el, lead.left_barline, "left")
    for c in lead.chord_symbols:
        _harmony(m_el, c, divisions)

    streams = [
        (st, m, v)
        for st, m in zip(staves, measures, strict=True)
        if m is not None
        for v in sorted(m.voices, key=lambda v: v.number)
        if v.events
    ]
    for k, (st, m, v) in enumerate(streams):
        staff_no = st.number if multi else None
        voice_no = voices[(st.number, v.number)]
        events = sorted(v.events, key=lambda e: (e.offset, not e.grace))
        pending = (
            [c for c in m.clefs if c.offset > 0]
            if v is min((x for x in m.voices if x.events), key=lambda x: x.number)
            else []
        )
        marks = _tuplet_marks(events)
        cursor = Fraction(0)
        for e in events:
            if e.offset > cursor:
                fwd = _sub(m_el, "forward")
                _sub(fwd, "duration", int((e.offset - cursor) * divisions))
                _sub(fwd, "voice", voice_no)
                if staff_no:
                    _sub(fwd, "staff", staff_no)
                cursor = e.offset
            while pending and pending[0].offset <= cursor:
                clef_attrs = _sub(m_el, "attributes")
                _clef(clef_attrs, pending.pop(0), staff_no)
            heads: list[NoteHead | None] = list(e.notes) or [None]
            for n, head in enumerate(heads):
                _note(m_el, e, head, n > 0, voice_no, staff_no, divisions, marks.get(id(e)))
            if not e.grace:
                cursor += e.duration
        for clef in pending:  # a clef change after the voice's last note
            if clef.offset > cursor:
                fwd = _sub(m_el, "forward")
                _sub(fwd, "duration", int((clef.offset - cursor) * divisions))
                _sub(fwd, "voice", voice_no)
                if staff_no:
                    _sub(fwd, "staff", staff_no)
                cursor = clef.offset
            _clef(_sub(m_el, "attributes"), clef, staff_no)
        if k < len(streams) - 1 and cursor > 0:
            back = _sub(m_el, "backup")
            _sub(back, "duration", int(cursor * divisions))

    if lead.right_barline is not None:
        _barline(m_el, lead.right_barline, "right")


def to_musicxml(score: Score) -> bytes:
    root = ET.Element("score-partwise", version="4.0")
    if score.title:
        work = _sub(root, "work")
        _sub(work, "work-title", score.title)
    ident = _sub(root, "identification")
    if score.composer:
        _sub(ident, "creator", score.composer, type="composer")
    encoding = _sub(ident, "encoding")
    _sub(encoding, "software", "Lilyscan")
    part_list = _sub(root, "part-list")
    divisions = _divisions(score)
    ids = [f"P{k}" for k in range(1, len(score.parts) + 1)]
    for pid, part in zip(ids, score.parts, strict=True):
        sp = _sub(part_list, "score-part", id=pid)
        _sub(sp, "part-name", part.name or "")
        if part.abbreviation:
            _sub(sp, "part-abbreviation", part.abbreviation)
    for pid, part in zip(ids, score.parts, strict=True):
        part_el = _sub(root, "part", id=pid)
        count = max((len(st.measures) for st in part.staves), default=0)
        for i in range(count):
            _write_measure(part_el, part, i, divisions, first=i == 0)
    ET.indent(root, space="  ")
    return (_HEADER + ET.tostring(root, encoding="unicode") + "\n").encode("utf-8")


def write_musicxml(score: Score, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(to_musicxml(score))
    return path
