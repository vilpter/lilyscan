"""Stage 5 repair rules: part merging and octave clefs."""

from __future__ import annotations

from fractions import Fraction
from pathlib import Path

import pytest

from lilyscan.ir.models import (
    BBox,
    Clef,
    Event,
    Measure,
    NoteHead,
    Part,
    Pitch,
    Score,
    Staff,
    TimeSignature,
    Voice,
)
from lilyscan.ir.musicxml import load_musicxml
from lilyscan.lilypond.generate import generate_project
from lilyscan.qa.checks import QaReport
from lilyscan.repair import apply_repairs
from lilyscan.repair.clefs import octave_clefs
from lilyscan.repair.parts import merge_split_parts, names_compatible
from lilyscan.review import build_review
from lilyscan.runtime.config import AUDIVERIS_VERSION

FIXTURES = Path(__file__).parents[1] / "eval" / "fixtures" / "omr" / AUDIVERIS_VERSION
MEASURES = 4


def note(step: str, octave: int, offset: int = 0) -> Event:
    return Event(
        kind="note",
        offset=Fraction(offset),
        duration=Fraction(1),
        notes=[NoteHead(pitch=Pitch(step=step, octave=octave))],  # type: ignore[arg-type]
    )


def filler() -> Event:
    return Event(kind="rest", offset=Fraction(0), duration=Fraction(2), measure_rest=True)


def part(
    pid: str,
    name: str,
    present: range,
    clef: Clef,
    pitch: tuple[str, int] = ("C", 5),
) -> Part:
    """A single-staff part in 2/4 whose notes are only in the measures ``present``."""
    measures = []
    for i in range(MEASURES):
        events = [note(*pitch), note(*pitch, 1)] if i in present else [filler()]
        measures.append(
            Measure(
                index=i,
                number=str(i + 1),
                clefs=[clef] if i == 0 else [],
                time=TimeSignature(beats=2, beat_type=4) if i == 0 else None,
                voices=[Voice(number=1, events=events)],
            )
        )
    return Part(id=pid, name=name, abbreviation=name, staves=[Staff(number=1, measures=measures)])


TREBLE = Clef(sign="G", line=2)
BASS = Clef(sign="F", line=4)
FIRST, LATER = range(0, 2), range(2, MEASURES)


def split_choir(bass_abbreviation: str = "B.") -> Score:
    """What Audiveris exports when later systems use abbreviations: eight half-parts."""
    return Score(
        parts=[
            part("P1", "S.", LATER, TREBLE),
            part("P2", "A.", LATER, TREBLE),
            part("P3", "T.", LATER, TREBLE),
            part("P4", bass_abbreviation, LATER, BASS, ("C", 3)),
            part("P5", "Soprano", FIRST, TREBLE),
            part("P6", "Alto", FIRST, TREBLE),
            part("P7", "Tenor", FIRST, TREBLE),
            part("P8", "Bass", FIRST, BASS, ("C", 3)),
        ]
    )


@pytest.mark.parametrize(
    ("a", "b", "same"),
    [
        ("Fl.", "Flute", True),
        ("F1.", "Flute", True),  # OCR read the l as a 1
        ("FI.", "Flute", True),  # ... or as a capital I
        ("Vc.", "Cello", True),  # an abbreviation of another name for the instrument
        ("Vln. I", "Violin I", True),
        ("Vln. I", "Violin II", False),
        ("Vln.", "Violin II", True),  # number not read
        ("Vla.", "Viola", True),
        ("Ob.", "Flute", False),
        ("S.", "Alto", False),
        ("13.", "Bass", False),  # no letters: unreadable
    ],
)
def test_names_compatible(a: str, b: str, same: bool) -> None:
    assert names_compatible(a, b) is same


def test_merges_parts_split_between_systems() -> None:
    score = split_choir()
    repairs = merge_split_parts(score)

    assert [p.name for p in score.parts] == ["Soprano", "Alto", "Tenor", "Bass"]
    assert [p.abbreviation for p in score.parts] == ["S.", "A.", "T.", "B."]
    for p in score.parts:
        m = p.staves[0].measures
        assert all(e.kind == "note" for x in m for v in x.voices for e in v.events)
        moved = [e for x in m[2:] for v in x.voices for e in v.events]
        assert all(
            pr.stage == "repair" and pr.rule == "part-merge" for e in moved for pr in e.provenance
        )
    assert repairs[0].detail == "merged P1 (S.) into P5 (Soprano)"
    assert repairs[0].measures == ["3", "4"]
    assert [pr.rule for pr in score.provenance] == ["part-merge"] * 4


def test_unreadable_name_is_placed_by_its_neighbours() -> None:
    score = split_choir(bass_abbreviation="13.")
    merge_split_parts(score)
    assert [p.name for p in score.parts] == ["Soprano", "Alto", "Tenor", "Bass"]


def test_does_not_merge_overlapping_or_different_clefs() -> None:
    overlapping = Score(
        parts=[part("P1", "Fl.", range(1, 4), TREBLE), part("P2", "Flute", FIRST, TREBLE)]
    )
    assert merge_split_parts(overlapping) == []
    assert len(overlapping.parts) == 2

    other_clef = Score(parts=[part("P1", "Vc.", LATER, TREBLE), part("P2", "Cello", FIRST, BASS)])
    assert merge_split_parts(other_clef) == []


def test_a_rest_with_a_page_box_is_present() -> None:
    # With geometry, a measure rest the engine read (it has a box) is real music, so
    # both parts sit in the same system and are not merged.
    score = Score(parts=[part("P1", "Fl.", LATER, TREBLE), part("P2", "Flute", FIRST, TREBLE)])
    score.parts[0].staves[0].measures[0].bbox = BBox(page=0, x=0, y=0, w=10, h=10)
    assert merge_split_parts(score) == []


def test_parts_first_seen_later_keep_their_place_in_the_system() -> None:
    # Clarinet only appears from the second system, between flute and bassoon.
    score = Score(
        parts=[
            part("P1", "Fl.", LATER, TREBLE),
            part("P2", "Cl.", LATER, TREBLE),
            part("P3", "Fg.", LATER, BASS),
            part("P4", "Flute", FIRST, TREBLE),
            part("P5", "Bassoon", FIRST, BASS),
        ]
    )
    merge_split_parts(score)
    assert [p.name for p in score.parts] == ["Flute", "Cl.", "Bassoon"]


def test_octave_clef_lowers_a_tenor_read_in_plain_treble() -> None:
    score = split_choir()
    repairs = apply_repairs(score)  # merges first, then sees the whole tenor line

    assert [r.rule for r in repairs] == ["part-merge"] * 4 + ["octave-clef"]
    tenor = score.parts[2].staves[0]
    assert tenor.measures[0].clefs == [Clef(sign="G", line=2, octave_change=-1)]
    heads = [h for m in tenor.measures for v in m.voices for e in v.events for h in e.notes]
    assert {h.pitch.octave for h in heads} == {4}
    first = tenor.measures[0].voices[0].events[0]
    assert first.provenance[-1].rule == "octave-clef"
    assert first.provenance[-1].before == {"pitches": ["C5"]}
    # The other voices are untouched.
    assert {
        h.pitch.octave
        for m in score.parts[0].staves[0].measures
        for v in m.voices
        for e in v.events
        for h in e.notes
    } == {5}


def test_octave_clef_leaves_a_tenor_already_in_range() -> None:
    score = Score(
        parts=[
            part("P1", "Soprano", range(MEASURES), TREBLE),
            part("P2", "Alto", range(MEASURES), TREBLE),
            part("P3", "Tenor", range(MEASURES), TREBLE, ("A", 3)),
            part("P4", "Bass", range(MEASURES), BASS, ("C", 3)),
        ]
    )
    assert octave_clefs(score) == []
    assert octave_clefs(Score(parts=[part("P1", "Flute", range(MEASURES), TREBLE)])) == []


def test_generator_notes_staff_repairs_once() -> None:
    score = split_choir()
    apply_repairs(score)
    project = generate_project(score)
    tenor = project.files["parts/tenor.ly"]
    assert tenor.count("% fix: octave-clef: treble clef read without its 8") == 1
    assert tenor.count("% fix: part-merge: merged P3 (T.) into P7 (Tenor)") == 1
    assert "%{ fix:" not in tenor  # no per-note markers for staff-wide repairs
    assert '\\clef "treble_8"' in tenor


def test_review_lists_repairs() -> None:
    score = split_choir()
    apply_repairs(score)
    review = build_review(score, generate_project(score), QaReport(checks=[]))
    assert [r["rule"] for r in review["repairs"]] == ["part-merge"] * 4 + ["octave-clef"]
    assert review["repairs"][-1]["part"] == "P7"
    assert review["review"] == []  # staff-wide repairs do not flag every measure


def test_recorded_engine_output_split_flute() -> None:
    score = load_musicxml(FIXTURES / "split-flute" / "output.mxl", "audiveris")
    repairs = apply_repairs(score)
    assert [r.detail for r in repairs] == ["merged P1 (F1.) into P2 (Flute)"]
    assert [p.name for p in score.parts] == ["Flute"]
