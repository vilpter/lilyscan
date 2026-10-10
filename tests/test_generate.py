"""Golden-file tests for the LilyPond generator.

After an intentional change to the output, regenerate the golden files with
LILYSCAN_UPDATE_GOLDEN=1 and review the diff.
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from lilyscan.ir.models import BBox, Provenance
from lilyscan.ir.musicxml import load_musicxml
from lilyscan.lilypond.generate import generate_project, write_project
from lilyscan.qa.checks import run_checks

FIXTURE = Path(__file__).parent / "fixtures" / "features.musicxml"
GOLDEN = Path(__file__).parent / "golden" / "features"


def test_matches_golden_files() -> None:
    project = generate_project(load_musicxml(FIXTURE))
    if os.environ.get("LILYSCAN_UPDATE_GOLDEN") == "1":
        for rel, text in project.files.items():
            path = GOLDEN / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8", newline="\n")
    expected = {
        p.relative_to(GOLDEN).as_posix(): p.read_text(encoding="utf-8")
        for p in sorted(GOLDEN.rglob("*.ly"))
    }
    assert project.files == expected


def test_output_is_deterministic() -> None:
    score = load_musicxml(FIXTURE)
    assert generate_project(score).files == generate_project(score.model_copy(deep=True)).files


def test_every_measure_line_is_mapped() -> None:
    project = generate_project(load_musicxml(FIXTURE))
    mapped = {(rel, line) for rel, line in project.measure_lines}
    for rel, text in project.files.items():
        if not rel.startswith("parts/"):
            continue
        for n, line in enumerate(text.splitlines(), 1):
            if line.rstrip().endswith("|") or " | \\bar" in line:
                assert (rel, n) in mapped, (rel, n, line)


def test_low_confidence_and_repair_markers() -> None:
    score = load_musicxml(FIXTURE)
    event = score.parts[0].staves[0].measures[1].voices[0].events[0]
    event.confidence = 0.42
    event.bbox = BBox(page=1, x=812.4, y=340.2, w=10, h=10)
    event.provenance.append(Provenance(stage="repair", rule="missing-dot"))
    text = generate_project(score).files["parts/voice.ly"]
    assert "g'2 %{ ?? conf=0.42 bbox=p2:(812,340) %} %{ fix: missing-dot %}" in text


HOSTILE_LYRICS = ["V2.26.0", "__", "--", "{x}", "a\\b", 'say "hi"', "2nd", "#t", "l'amour", "Kö-"]


@pytest.mark.lilypond
def test_engine_text_in_lyrics_always_compiles(tmp_path: Path) -> None:
    """OCR can put page footers or noise into lyrics; the project must still compile."""
    score = load_musicxml(FIXTURE)
    events = [e for m in score.parts[0].staves[0].measures for v in m.voices for e in v.events]
    sung = [e for e in events if e.lyrics]
    for event, text in zip(sung, HOSTILE_LYRICS, strict=False):
        event.lyrics[0].text = text
    project = write_project(score, tmp_path)
    report = run_checks(score, tmp_path, project)
    assert report.check("Q1").passed, report.check("Q1").details
    assert "l'amour" in project.files["parts/voice.ly"]  # plain words stay unquoted
    assert '"V2.26.0"' in project.files["parts/voice.ly"]


@pytest.mark.lilypond
def test_generated_project_passes_all_checks(tmp_path: Path) -> None:
    score = load_musicxml(FIXTURE)
    project = write_project(score, tmp_path)
    report = run_checks(score, tmp_path, project)
    assert report.passed, report.to_dict()
    assert "main.pdf" in report.outputs


def test_voice_entering_mid_measure_keeps_its_onset() -> None:
    from fractions import Fraction

    from lilyscan.ir.models import Event, NoteHead, Pitch, Voice

    score = load_musicxml(FIXTURE)
    m = score.parts[1].staves[0].measures[1]  # piano upper, two voices
    late = Event(
        kind="note",
        offset=Fraction(2),
        duration=Fraction(2),
        notes=[NoteHead(pitch=Pitch(step="C", octave=4))],
    )
    m.voices[1] = Voice(number=2, events=[late])
    text = generate_project(score).files["parts/piano.ly"]
    assert r"\voiceTwo s2 c'2" in text


def _shorten_voice_measure_one(score):  # type: ignore[no-untyped-def]
    """Drop the last quarter note of the voice's measure 1, as a misread engine would."""
    voice = score.parts[0].staves[0].measures[1].voices[0]
    voice.events.pop()
    return score


def test_short_measure_gets_one_length_in_every_staff() -> None:
    score = _shorten_voice_measure_one(load_musicxml(FIXTURE))
    files = generate_project(score).files
    # The piano still fills 4/4, so the grid keeps measure 1 at a whole note: the voice
    # is padded with a spacer instead of shortening the measure.
    voice_m1 = next(line for line in files["parts/voice.ly"].splitlines() if "g'2" in line)
    assert voice_m1.rstrip().endswith("s4 |")
    assert "measureLength" not in files["parts/voice.ly"]


def test_measure_everyone_misreads_gets_override_and_marker() -> None:
    from fractions import Fraction

    score = load_musicxml(FIXTURE)
    # Every staff comes out a quarter short in measure 1 (3/4 in a 4/4 bar).
    score.parts[0].staves[0].measures[1].voices[0].events.pop()  # voice: drop d''4
    upper, lower = score.parts[1].staves
    chord = upper.measures[1].voices[0].events[0]  # whole-note chord -> dotted half
    chord.duration, chord.note_type, chord.dots = Fraction(3), "half", 1
    inner = upper.measures[1].voices[1].events[1]  # fis'2 -> fis'4
    inner.duration, inner.note_type = Fraction(1), "quarter"
    bass = lower.measures[1].voices[0].events[1]  # b2 -> b4
    bass.duration, bass.note_type = Fraction(1), "quarter"

    files = generate_project(score).files
    override = r"\set Timing.measureLength = #3/4 %{ ?? rhythm: 3/4 of 1 %}"
    for name in ("parts/voice.ly", "parts/piano.ly"):
        text = files[name]
        assert text.count(override) == (1 if name == "parts/voice.ly" else 2), name
        # Measure 2 changes time signature, which resets the length by itself.
        assert r"\set Timing.measureLength = #1" not in text
    assert "  g2 d4:7 |  % m. 1" in files["chords.ly"]


@pytest.mark.lilypond
def test_misread_measure_still_compiles_cleanly(tmp_path: Path) -> None:
    score = _shorten_voice_measure_one(load_musicxml(FIXTURE))
    project = write_project(score, tmp_path)
    report = run_checks(score, tmp_path, project)
    assert report.check("Q1").passed and report.check("Q2").passed
    assert not report.check("Q3").passed  # the rhythm problem is still reported


def test_short_name_drops_the_role_and_long_names_wrap() -> None:
    from lilyscan.lilypond.generate import _instrument_name, _short_name

    assert _short_name("Vln. 1 (melody)") == "Vln. 1"
    assert _short_name("Vla.") == "Vla."
    assert _instrument_name("Violoncello") == '"Violoncello"'  # one word: nothing to wrap
    assert (
        _instrument_name("Viola (Fiddle 1)") == r'\markup \center-column { "Viola" "(Fiddle 1)" }'
    )
