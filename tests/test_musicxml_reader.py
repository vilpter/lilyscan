from __future__ import annotations

import zipfile
from fractions import Fraction
from pathlib import Path

import pytest

from lilyscan.ir.musicxml import MusicXMLError, load_musicxml, parse_musicxml

HEAD = """<?xml version="1.0" encoding="UTF-8"?>
<score-partwise version="4.0">
  <work><work-title>Test</work-title></work>
  <identification><creator type="composer">Anon</creator></identification>
  <part-list>{parts}</part-list>
"""


def doc(parts: str, body: str) -> bytes:
    return (HEAD.format(parts=parts) + body + "</score-partwise>").encode()


def note(step: str, octave: int, dur: int, typ: str, extra: str = "", pre: str = "") -> str:
    return (
        f"<note>{pre}<pitch><step>{step}</step><octave>{octave}</octave></pitch>"
        f"<duration>{dur}</duration><type>{typ}</type>{extra}</note>"
    )


SOLO = doc(
    '<score-part id="P1"><part-name>Flute</part-name><part-abbreviation>Fl.</part-abbreviation>'
    "</score-part>",
    """<part id="P1">
    <measure number="1">
      <attributes><divisions>2</divisions><key><fifths>-1</fifths><mode>minor</mode></key>
        <time><beats>4</beats><beat-type>4</beat-type></time>
        <clef><sign>G</sign><line>2</line></clef></attributes>
      """
    + note(
        "C",
        4,
        2,
        "quarter",
        '<tie type="start"/><lyric number="1"><syllabic>begin</syllabic><text>Ky</text></lyric>',
    )
    + note(
        "C",
        4,
        1,
        "eighth",
        '<tie type="stop"/><lyric number="1"><syllabic>end</syllabic><text>rie</text></lyric>',
    )
    + note("E", 4, 1, "eighth", "", "")
    + note("G", 4, 1, "eighth", "", "<chord/>")
    + """<note><rest/><duration>4</duration><type>half</type></note>
    </measure>
    <measure number="2">
      <note><grace/><pitch><step>B</step><alter>-1</alter><octave>4</octave></pitch>
        <type>eighth</type><accidental>flat</accidental></note>
      <note><pitch><step>A</step><octave>4</octave></pitch><duration>1</duration>
        <type>eighth</type><time-modification><actual-notes>3</actual-notes>
        <normal-notes>2</normal-notes></time-modification>
        <notations><articulations><staccato/></articulations></notations></note>
      <barline location="right"><bar-style>light-heavy</bar-style>
        <repeat direction="backward"/></barline>
    </measure>
  </part>""",
)


def test_header_and_part_list() -> None:
    score = parse_musicxml(SOLO)
    assert (score.title, score.composer) == ("Test", "Anon")
    part = score.parts[0]
    assert (part.id, part.name, part.abbreviation) == ("P1", "Flute", "Fl.")
    assert [s.number for s in part.staves] == [1]


def test_attributes_on_first_measure() -> None:
    m1 = parse_musicxml(SOLO).parts[0].staves[0].measures[0]
    assert m1.key is not None and (m1.key.fifths, m1.key.mode) == (-1, "minor")
    assert m1.time is not None and m1.time.measure_length == 4
    assert m1.clefs[0].sign == "G" and m1.clefs[0].line == 2


def test_events_offsets_ties_lyrics_chords() -> None:
    m1 = parse_musicxml(SOLO).parts[0].staves[0].measures[0]
    ev = m1.voices[0].events
    assert [e.offset for e in ev] == [0, 1, Fraction(3, 2), 2]
    assert [e.kind for e in ev] == ["note", "note", "chord", "rest"]
    assert ev[0].notes[0].tie_start and ev[1].notes[0].tie_stop
    assert [(ly.text, ly.syllabic) for ly in ev[0].lyrics] == [("Ky", "begin")]
    assert [p.step for p in ev[2].pitches] == ["E", "G"]
    assert m1.voices[0].duration() == 4


def test_grace_tuplet_articulation_barline() -> None:
    m2 = parse_musicxml(SOLO).parts[0].staves[0].measures[1]
    grace, a = m2.voices[0].events
    assert grace.grace and grace.duration == 0 and grace.notes[0].pitch.alter == -1
    assert grace.notes[0].accidental_shown
    assert a.tuplet == (3, 2) and a.duration == Fraction(1, 2) and a.articulations == ["staccato"]
    assert m2.right_barline is not None
    assert (m2.right_barline.style, m2.right_barline.repeat) == ("light-heavy", "backward")


PIANO = doc(
    '<score-part id="P1"><part-name>Piano</part-name></score-part>',
    """<part id="P1"><measure number="1">
      <attributes><divisions>1</divisions><staves>2</staves>
        <clef number="1"><sign>G</sign><line>2</line></clef>
        <clef number="2"><sign>F</sign><line>4</line></clef></attributes>
      <harmony><root><root-step>F</root-step><root-alter>1</root-alter></root>
        <kind text="m7b5">half-diminished</kind></harmony>
      <note><pitch><step>E</step><octave>5</octave></pitch><duration>4</duration>
        <voice>1</voice><type>whole</type><staff>1</staff></note>
      <backup><duration>4</duration></backup>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>2</duration>
        <voice>2</voice><type>half</type><staff>1</staff></note>
      <backup><duration>2</duration></backup>
      <note><pitch><step>C</step><octave>3</octave></pitch><duration>4</duration>
        <voice>5</voice><type>whole</type><staff>2</staff></note>
    </measure></part>""",
)


def test_two_staves_voices_and_chord_symbol() -> None:
    part = parse_musicxml(PIANO).parts[0]
    upper, lower = part.staves
    assert [c.sign for c in upper.measures[0].clefs] == ["G"]
    assert [c.sign for c in lower.measures[0].clefs] == ["F"]
    assert [v.number for v in upper.measures[0].voices] == [1, 2]
    assert [v.number for v in lower.measures[0].voices] == [5]
    assert lower.measures[0].voices[0].events[0].offset == 0
    chord = upper.measures[0].chord_symbols[0]
    assert (chord.root, chord.kind, chord.text) == ("F#", "half-diminished", "m7b5")


def test_every_staff_gets_every_measure(tmp_path: Path) -> None:
    score = parse_musicxml(PIANO)
    assert all(len(s.measures) == 1 for _, s in score.staves())


def test_mxl_container(tmp_path: Path) -> None:
    path = tmp_path / "t.mxl"
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(
            "META-INF/container.xml",
            '<container><rootfiles><rootfile full-path="score.xml"/></rootfiles></container>',
        )
        z.writestr("score.xml", SOLO)
    assert load_musicxml(path).title == "Test"


def test_ir_json_round_trip() -> None:
    from lilyscan.ir.models import Score

    score = parse_musicxml(SOLO)
    again = Score.model_validate_json(score.model_dump_json())
    assert again == score
    assert '"offset":"3/2"' in score.model_dump_json()


def test_rejects_non_musicxml() -> None:
    with pytest.raises(MusicXMLError):
        parse_musicxml(b"<html/>")
