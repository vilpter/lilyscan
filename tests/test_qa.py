from __future__ import annotations

from fractions import Fraction
from pathlib import Path

import pytest

from lilyscan.ir.models import (
    Barline,
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
from lilyscan.lilypond.generate import write_project
from lilyscan.qa.checks import (
    alignment_check,
    compile_checks,
    instrument_range,
    range_check,
    rhythm_check,
)


def note(step: str, octave: int, offset: int, dur: int) -> Event:
    return Event(
        kind="note",
        offset=Fraction(offset),
        duration=Fraction(dur),
        notes=[NoteHead(pitch=Pitch(step=step, octave=octave))],  # type: ignore[arg-type]
    )


def staff(*voices_per_measure: list[Event], number: int = 1, implicit_first: bool = False) -> Staff:
    return Staff(
        number=number,
        measures=[
            Measure(
                index=i,
                number=str(i + 1),
                implicit=implicit_first and i == 0,
                time=TimeSignature(beats=4, beat_type=4) if i == 0 else None,
                voices=[Voice(number=1, events=events)],
            )
            for i, events in enumerate(voices_per_measure)
        ],
    )


def test_rhythm_flags_short_measure_but_allows_marked_pickup() -> None:
    full = [note("C", 4, 0, 4)]
    short = [note("C", 4, 0, 3)]
    pickup = [note("G", 4, 0, 1)]
    ok = Score(parts=[Part(id="P1", staves=[staff(pickup, full, short, implicit_first=True)])])
    result = rhythm_check(ok)
    assert result.passed, result.details  # pickup of 1 + final measure of 3

    bad = Score(parts=[Part(id="P1", staves=[staff(full, short, full)])])
    result = rhythm_check(bad)
    assert not result.passed
    assert result.details[0]["measure"] == "2" and result.details[0]["actual"] == "3"


def test_range_uses_instrument_names() -> None:
    # E3 is below the violin's open G string (G3 = 55) but fine for an unknown instrument.
    violin = Part(id="P1", name="Violin I", staves=[staff([note("E", 3, 0, 4)])])
    assert instrument_range(violin)[0] == (55, 103)
    result = range_check(Score(parts=[violin]))
    assert not result.passed and result.details[0]["pitch"] == "E3"
    unknown = Part(id="P2", name="Theremin", staves=[staff([note("E", 3, 0, 4)])])
    assert range_check(Score(parts=[unknown])).passed


def test_alignment_reports_count_and_barline_mismatches() -> None:
    a = staff([note("C", 4, 0, 4)], [note("C", 4, 0, 4)])
    b = staff([note("C", 4, 0, 4)])
    result = alignment_check(Score(parts=[Part(id="P1", staves=[a]), Part(id="P2", staves=[b])]))
    assert [d["kind"] for d in result.details] == ["measure-count"]

    c = staff([note("C", 4, 0, 4)])
    c.measures[0].right_barline = Barline(style="light-heavy")
    result = alignment_check(Score(parts=[Part(id="P1", staves=[b]), Part(id="P2", staves=[c])]))
    assert [d["kind"] for d in result.details] == ["barline"]


@pytest.mark.lilypond
def test_bar_check_failures_map_to_measures(tmp_path: Path) -> None:
    score = load_musicxml(Path(__file__).parent / "fixtures" / "features.musicxml")
    # An engine can report a notated type that disagrees with the duration. The IR
    # (and so the measure grid) keeps the half note, but LilyPond engraves a whole
    # note, so its bar check fails in voice measure 1.
    event = score.parts[0].staves[0].measures[1].voices[0].events[0]
    event.note_type = "whole"
    project = write_project(score, tmp_path)
    q1, q2, _ = compile_checks(tmp_path, project)
    assert q1.passed and not q2.passed
    assert {(d["part"], d["measure"]) for d in q2.details} >= {("P1", "1")}
