"""Transposition and the score combiner (M9)."""

from __future__ import annotations

from pathlib import Path

import pytest

from lilyscan.combine import CombineError, Selection, _transposed, combine
from lilyscan.ir.models import KeySignature, Pitch, Score
from lilyscan.ir.musicxml import load_musicxml
from lilyscan.ir.transpose import Interval, to_concert_pitch, transpose_key, transpose_pitch
from lilyscan.pipeline import produce

FIXTURE = Path(__file__).parent / "fixtures" / "features.musicxml"


@pytest.mark.parametrize(
    ("text", "steps", "semitones"),
    [
        ("M2", 1, 2),
        ("-m3", -2, -3),
        ("P5", 4, 7),
        ("A4", 3, 6),
        ("d5", 4, 6),
        ("P8", 7, 12),
        ("M9", 8, 14),
    ],
)
def test_interval_parse(text: str, steps: int, semitones: int) -> None:
    assert Interval.parse(text) == Interval(steps, semitones)


@pytest.mark.parametrize("text", ["M4", "P3", "x2", "M0", ""])
def test_interval_parse_rejects(text: str) -> None:
    with pytest.raises(ValueError):
        Interval.parse(text)


def test_spelling_follows_the_interval() -> None:
    fs4 = Pitch(step="F", octave=4, alter=1)
    assert transpose_pitch(fs4, Interval.parse("M2")).label == "G#4"
    assert transpose_pitch(fs4, Interval.parse("d3")).label == "Ab4"
    assert transpose_pitch(Pitch(step="B", octave=3, alter=-1), Interval.parse("-m3")).label == "G3"
    assert transpose_key(KeySignature(fifths=-1), Interval.parse("M2")).fifths == 1  # F -> G


def test_concert_pitch_for_a_b_flat_part() -> None:
    score = load_musicxml(FIXTURE)
    part = score.parts[0]
    part.transpose_semitones = -2  # written a major second above sounding
    written = [
        h.pitch
        for m in part.staves[0].measures
        for v in m.voices
        for e in v.events
        for h in e.notes
    ]
    concert = to_concert_pitch(score)
    sounding = [
        h.pitch
        for m in concert.parts[0].staves[0].measures
        for v in m.voices
        for e in v.events
        for h in e.notes
    ]
    assert concert.parts[0].transpose_semitones is None
    assert [s.midi for s in sounding] == [w.midi - 2 for w in written]


def test_transposed_wraps_only_whole_variable_names() -> None:
    layout = '\\new Voice = "fluteVoice" \\fluteMusic\n\\fluteMusicTwo\n'
    out = _transposed(layout, ["fluteMusic"], "d'")
    assert "\\transpose c' d' \\fluteMusic\n" in out
    assert "\\fluteMusicTwo" in out and "\\transpose c' d' \\fluteMusicTwo" not in out


def job(root: Path) -> Path:
    produce(load_musicxml(FIXTURE, "musicxml"), root)
    return root


@pytest.mark.lilypond
def test_combine_parts_from_two_jobs_keeps_edits_and_transposes(tmp_path: Path) -> None:
    a, b = job(tmp_path / "a"), job(tmp_path / "b")
    score = load_musicxml(FIXTURE)
    first = score.parts[0]
    # An edit made in the review UI of job b: it must reach the combined score.
    slug = (
        "voice"
        if (b / "ly" / "parts" / "voice.ly").is_file()
        else next((b / "ly" / "parts").glob("*.ly")).stem
    )
    edited = b / "ly" / "parts" / f"{slug}.ly"
    edited.write_text(
        edited.read_text(encoding="utf-8") + "\n% edited in the review UI\n", encoding="utf-8"
    )

    report = combine(
        [
            Selection(a, first.id),
            Selection(b, first.id, transpose="M2", name="Second"),
        ],
        tmp_path / "combined",
        title="Two voices",
    )

    assert report["qa"]["checks"][0]["passed"], report["qa"]  # Q1: compiles
    files = {
        p.relative_to(tmp_path / "combined" / "ly").as_posix(): p.read_text(encoding="utf-8")
        for p in (tmp_path / "combined" / "ly").rglob("*.ly")
        if "svg" not in p.parts
    }
    parts = sorted(k for k in files if k.startswith("parts/"))
    assert len(parts) == 2
    assert any("% edited in the review UI" in files[k] for k in parts)
    assert "\\transpose c' d'" in files["layout/score.ly"]
    assert "Two voices" in files["main.ly"]
    assert [c["job"] for c in report["combined_from"]] == ["a", "b"]


@pytest.mark.lilypond
def test_combine_refuses_parts_that_do_not_line_up(tmp_path: Path) -> None:
    a = job(tmp_path / "a")
    score = load_musicxml(FIXTURE)
    short = score.model_copy(deep=True)
    for part in short.parts:
        for staff in part.staves:
            staff.measures = staff.measures[:-1]
    produce(short, tmp_path / "b")
    with pytest.raises(CombineError, match="measures"):
        combine(
            [Selection(a, score.parts[0].id), Selection(tmp_path / "b", score.parts[0].id)],
            tmp_path / "c",
        )


def test_combine_needs_a_finished_job(tmp_path: Path) -> None:
    with pytest.raises(CombineError, match="no finished score"):
        combine([Selection(tmp_path / "missing", "P1")], tmp_path / "out")


@pytest.mark.lilypond
def test_combine_at_concert_pitch(tmp_path: Path) -> None:
    score = load_musicxml(FIXTURE, "musicxml")
    clarinet = score.parts[0]
    clarinet.name = "Clarinet in B-flat"
    clarinet.transpose_semitones = -2
    produce(score, tmp_path / "a")
    source = next((tmp_path / "a" / "ly" / "parts").glob("clarinet*.ly")).read_text(
        encoding="utf-8"
    )
    assert "transposition bes" in source

    report = combine([Selection(tmp_path / "a", clarinet.id, transpose="concert")], tmp_path / "c")

    assert report["qa"]["checks"][0]["passed"], report["qa"]
    part_file = next((tmp_path / "c" / "ly" / "parts").glob("*.ly")).read_text(encoding="utf-8")
    layout = (tmp_path / "c" / "ly" / "layout" / "score.ly").read_text(encoding="utf-8")
    assert "transposition" not in part_file
    assert "transpose c' bes" in layout
    combined = Score.model_validate_json(
        (tmp_path / "c" / "ir" / "score.json").read_text(encoding="utf-8")
    )
    assert combined.parts[0].transpose_semitones is None
    written = [
        h.pitch.midi
        for m in clarinet.staves[0].measures
        for v in m.voices
        for e in v.events
        for h in e.notes
    ]
    sounding = [
        h.pitch.midi
        for m in combined.parts[0].staves[0].measures
        for v in m.voices
        for e in v.events
        for h in e.notes
    ]
    assert sounding == [w - 2 for w in written]
