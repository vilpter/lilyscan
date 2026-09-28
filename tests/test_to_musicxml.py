"""IR -> MusicXML: what the reader takes from a file, the writer puts back."""

from __future__ import annotations

import json
from fractions import Fraction
from pathlib import Path
from typing import Any

from lilyscan.arrange import reduce_to_piano
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
from lilyscan.ir.musicxml import load_musicxml, parse_musicxml
from lilyscan.ir.to_musicxml import to_musicxml, write_musicxml

FIXTURE = Path(__file__).parent / "fixtures" / "features.musicxml"


def comparable(score: Score) -> Any:
    """The IR as MusicXML can carry it (no page boxes, confidence or provenance)."""

    def strip(o: Any) -> Any:
        if isinstance(o, dict):
            return {
                k: strip(v)
                for k, v in o.items()
                if k not in ("bbox", "confidence", "provenance", "source")
            }
        if isinstance(o, list):
            return [strip(x) for x in o]
        return o

    return strip(json.loads(score.model_dump_json()))


def test_a_file_read_and_written_again_reads_the_same() -> None:
    score = load_musicxml(FIXTURE)
    assert comparable(parse_musicxml(to_musicxml(score))) == comparable(score)


def note(offset: int, dur: int, label: str) -> Event:
    pitch = Pitch(step=label[0], octave=int(label[-1]))  # type: ignore[arg-type]
    return Event(
        kind="note", offset=Fraction(offset), duration=Fraction(dur), notes=[NoteHead(pitch=pitch)]
    )


def one_staff(pid: str, name: str, clef: Clef, labels: list[str]) -> Part:
    m = Measure(
        index=0,
        number="1",
        clefs=[clef],
        key=KeySignature(fifths=0),
        time=TimeSignature(beats=4, beat_type=4),
        voices=[Voice(number=1, events=[note(i, 1, lab) for i, lab in enumerate(labels)])],
    )
    return Part(id=pid, name=name, staves=[Staff(number=1, measures=[m])])


def test_voices_shared_by_two_staves_are_told_apart() -> None:
    # The piano reduction numbers voices per staff; both staves use voice 1.
    melody = one_staff("V", "Violin", Clef(sign="G", line=2), ["E5", "F5", "G5", "A5"])
    viola = one_staff("A", "Viola", Clef(sign="C", line=3), ["C5", "D5", "E5", "F5"])
    cello = one_staff("C", "Cello", Clef(sign="F", line=4), ["C3", "D3", "E3", "F3"])
    piano = reduce_to_piano([viola, cello], melody)
    score = Score(title="Duo", parts=[melody, piano])
    back = parse_musicxml(to_musicxml(score))
    lower = back.parts[1].staves[1].measures[0]
    assert [p.label for v in lower.voices for e in v.events for p in e.pitches] == [
        "C3", "D3", "E3", "F3",
    ]  # fmt: skip
    assert back.parts[1].staves[0].measures[0].voices[0].number != lower.voices[0].number


def test_a_transposing_part_keeps_its_transposition(tmp_path: Path) -> None:
    clarinet = one_staff("B", "Clarinet in B-flat", Clef(sign="G", line=2), ["D5"] * 4)
    clarinet.transpose_semitones = -2
    path = write_musicxml(Score(parts=[clarinet]), tmp_path / "score.musicxml")
    assert load_musicxml(path).parts[0].transpose_semitones == -2
    assert b"<chromatic>-2</chromatic>" in path.read_bytes()
