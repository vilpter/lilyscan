"""Piano accompaniment: string parts reduced onto a grand staff."""

from __future__ import annotations

from fractions import Fraction
from pathlib import Path

import pytest

from lilyscan.arrange import reduce_to_piano, sounding_shift
from lilyscan.combine import Selection, combine
from lilyscan.ir.models import (
    Clef,
    Event,
    KeySignature,
    Measure,
    NoteHead,
    Part,
    Pitch,
    Score,
    Staff,
    TimeSignature,
    Voice,
)
from lilyscan.pipeline import produce


def ev(offset: int, dur: int, *pitches: str) -> Event:
    """A note or chord from labels like "C5", or a rest when no pitch is given."""
    heads = [
        NoteHead(pitch=Pitch(step=p[0], octave=int(p[-1]), alter=p.count("#") - p.count("b")))  # type: ignore[arg-type]
        for p in pitches
    ]
    kind = "rest" if not heads else ("chord" if len(heads) > 1 else "note")
    return Event(kind=kind, offset=Fraction(offset), duration=Fraction(dur), notes=heads)


def part(pid: str, name: str, clef: Clef, *measures: list[Event]) -> Part:
    ms = [
        Measure(
            index=i,
            number=str(i + 1),
            clefs=[clef] if i == 0 else [],
            key=KeySignature(fifths=0) if i == 0 else None,
            time=TimeSignature(beats=4, beat_type=4) if i == 0 else None,
            voices=[Voice(number=1, events=events)],
        )
        for i, events in enumerate(measures)
    ]
    return Part(id=pid, name=name, staves=[Staff(number=1, measures=ms)])


TREBLE, ALTO, BASS = Clef(sign="G", line=2), Clef(sign="C", line=3), Clef(sign="F", line=4)
QUARTERS = [(0, 1), (1, 1), (2, 1), (3, 1)]


def melody() -> Part:
    return part(
        "P1", "Violin 1", TREBLE,
        [ev(o, d, p) for (o, d), p in zip(QUARTERS, ["E5", "F5", "G5", "A5"], strict=True)],
        [ev(0, 4, "C6")],
    )  # fmt: skip


def test_same_rhythm_becomes_chords_and_the_bass_sounds_an_octave_lower() -> None:
    violin2 = part(
        "P1", "Violin 2", TREBLE,
        [ev(o, d, p) for (o, d), p in zip(QUARTERS, ["C5", "D5", "E5", "F5"], strict=True)],
        [ev(0, 4, "E5")],
    )  # fmt: skip
    viola = part(
        "P1", "Viola", ALTO,
        [ev(o, d, p) for (o, d), p in zip(QUARTERS, ["G4", "A4", "C5", "C5"], strict=True)],
        [ev(0, 4, "G4")],
    )  # fmt: skip
    cello = part("P1", "Violoncello", BASS, [ev(0, 2, "C3"), ev(2, 2, "G3")], [ev(0, 4, "C3")])
    bass = part("P1", "Bass", BASS, [ev(0, 2, "C3"), ev(2, 2, "G3")], [ev(0, 4, "C3")])

    piano = reduce_to_piano([violin2, viola, cello, bass], melody())

    upper, lower = piano.staves
    assert upper.measures[0].clefs[0].sign == "G" and lower.measures[0].clefs[0].sign == "F"
    [voice] = upper.measures[0].voices
    assert [[p.label for p in e.pitches] for e in voice.events] == [
        ["G4", "C5"], ["A4", "D5"], ["C5", "E5"], ["C5", "F5"],
    ]  # fmt: skip
    [voice] = lower.measures[0].voices
    # The double bass is written an octave above where it sounds.
    assert [[p.label for p in e.pitches] for e in voice.events] == [["C2", "C3"], ["G2", "G3"]]
    assert sounding_shift(bass) == -12 and sounding_shift(cello) == 0


def test_the_piano_gets_no_bowings() -> None:
    viola = part("P1", "Viola", ALTO, [ev(0, 2, "G4"), ev(2, 2, "A4")], [ev(0, 4, "G4")])
    first, second = viola.staves[0].measures[0].voices[0].events
    first.articulations = ["up-bow", "accent"]
    second.articulations = ["down-bow"]

    piano = reduce_to_piano([viola], melody())

    events = piano.staves[0].measures[0].voices[0].events
    assert [e.articulations for e in events] == [["accent"], []]


def test_different_rhythms_keep_their_own_voices_and_rests_drop_out() -> None:
    violin2 = part(
        "P1", "Violin 2", TREBLE,
        [ev(o, d, p) for (o, d), p in zip(QUARTERS, ["C5", "D5", "E5", "F5"], strict=True)],
        [ev(0, 4)],  # resting
    )  # fmt: skip
    viola = part("P1", "Viola", ALTO, [ev(0, 2, "G4"), ev(2, 2, "A4")], [ev(0, 4, "G4")])

    piano = reduce_to_piano([violin2, viola], melody())

    first, second = piano.staves[0].measures
    assert [v.number for v in first.voices] == [1, 2]
    assert first.voices[0].events[0].pitches[0].label == "C5"  # the higher part first
    assert [[p.label for p in e.pitches] for v in second.voices for e in v.events] == [["G4"]]
    # Nothing sounds below middle C: the lower staff rests.
    assert all(not m.voices for m in piano.staves[1].measures)


def job(root: Path, p: Part) -> Path:
    produce(Score(title="Test", parts=[p]), root)
    return root


@pytest.mark.lilypond
def test_combine_melody_with_a_piano_accompaniment(tmp_path: Path) -> None:
    violin2 = part(
        "P1", "Violin 2", TREBLE,
        [ev(o, d, p) for (o, d), p in zip(QUARTERS, ["C5", "D5", "E5", "F5"], strict=True)],
        [ev(0, 4, "E5")],
    )  # fmt: skip
    cello = part("P1", "Violoncello", BASS, [ev(0, 2, "C3"), ev(2, 2, "G3")], [ev(0, 4, "C3")])
    jobs = {name: job(tmp_path / name, p) for name, p in
            (("v1", melody()), ("v2", violin2), ("vc", cello))}  # fmt: skip

    report = combine(
        [Selection(jobs["v1"], "P1")],
        tmp_path / "out",
        title="With piano",
        piano=[Selection(jobs["v2"], "P1"), Selection(jobs["vc"], "P1")],
    )

    assert report["qa"]["checks"][0]["passed"], report["qa"]  # compiles
    layout = (tmp_path / "out" / "ly" / "layout" / "score.ly").read_text(encoding="utf-8")
    assert "PianoStaff" in layout
    assert [p["job"] for p in report["piano_from"]] == ["v2", "vc"]


def test_a_renamed_part_gets_a_matching_short_name() -> None:
    from lilyscan.combine import _renamed

    engine = Part(id="P1", name="Voice", abbreviation="Voice")  # Audiveris's placeholder
    assert _renamed(engine, "Violin 1").abbreviation == "Vln. 1"
    assert _renamed(engine, "Cello").abbreviation == "Vc."
    assert _renamed(engine, "Basset horn").abbreviation is None
    assert _renamed(engine, None) is engine
