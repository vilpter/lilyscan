from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from lilyscan.ir.musicxml import load_musicxml
from lilyscan.synth.corpus import VARIANTS, build_corpus, load_corpus, load_spec
from lilyscan.synth.degrade import photo, scan
from lilyscan.synth.generate import PieceSpec, write_ground_truth

SPECS = [
    PieceSpec("solo", "solo", 7, 8),
    PieceSpec("piano", "piano", 7, 8),
    PieceSpec("satb", "satb", 7, 6, "lat"),
    PieceSpec("lead", "leadsheet", 7, 6, "fra"),
    PieceSpec("quartet", "quartet", 7, 6),
]


@pytest.mark.parametrize("spec", SPECS, ids=lambda s: s.category)
def test_generated_measures_are_complete(spec: PieceSpec, tmp_path: Path) -> None:
    score = load_musicxml(write_ground_truth(spec, tmp_path))
    for _, staff in score.staves():
        assert len(staff.measures) == spec.measures
        length = None
        for m in staff.measures:
            length = m.time.measure_length if m.time else length
            assert length is not None
            for v in m.voices:
                assert v.duration() == length, (staff.number, m.number, v.number)


def test_generation_is_deterministic(tmp_path: Path) -> None:
    a = load_musicxml(write_ground_truth(SPECS[2], tmp_path / "a"))
    b = load_musicxml(write_ground_truth(SPECS[2], tmp_path / "b"))
    assert a == b


def test_lyrics_and_chords_present(tmp_path: Path) -> None:
    satb = load_musicxml(write_ground_truth(SPECS[2], tmp_path))
    lyrics = [
        ly
        for _, s in satb.staves()
        for m in s.measures
        for v in m.voices
        for e in v.events
        for ly in e.lyrics
    ]
    assert lyrics and lyrics[-1].syllabic in ("single", "end")
    lead = load_musicxml(write_ground_truth(SPECS[3], tmp_path))
    assert any(m.chord_symbols for _, s in lead.staves() for m in s.measures)


def test_degradations_are_deterministic_and_bounded() -> None:
    img = np.ones((200, 150), dtype=np.float32)
    img[50:60, 20:130] = 0.0
    for fn in (scan, photo):
        a, b = fn(img, 3), fn(img, 3)
        assert np.array_equal(a, b)
        assert a.min() >= 0.0 and a.max() <= 1.0


def test_seed_spec_is_valid() -> None:
    specs = load_spec(Path(__file__).parents[1] / "eval" / "corpus" / "seed.json")
    assert len(specs) >= 20
    assert {s.category for s in specs} == {"solo", "piano", "satb", "leadsheet", "quartet"}
    assert {s.language for s in specs if s.category == "satb"} == {"lat", "deu", "fra", "eng"}


@pytest.mark.lilypond
def test_build_corpus_one_piece(tmp_path: Path) -> None:
    spec = tmp_path / "spec.json"
    spec.write_text(json.dumps({"pieces": [SPECS[0].to_dict()]}), encoding="utf-8")
    items = build_corpus(spec, tmp_path / "corpus")
    assert items[0].complete()
    assert [i.spec for i in load_corpus(tmp_path / "corpus")] == [SPECS[0]]
    assert all(items[0].input(v).stat().st_size > 0 for v in VARIANTS)


def test_building_one_piece_keeps_the_others_in_the_manifest(tmp_path: Path) -> None:
    spec = tmp_path / "spec.json"
    spec.write_text(json.dumps({"pieces": [s.to_dict() for s in SPECS[:2]]}), encoding="utf-8")
    build_corpus(spec, tmp_path / "corpus")
    build_corpus(spec, tmp_path / "corpus", only={SPECS[1].id})
    assert [i.spec for i in load_corpus(tmp_path / "corpus")] == SPECS[:2]


def test_repertoire_spec_round_trips() -> None:
    specs = load_spec(Path(__file__).parents[1] / "eval" / "corpus" / "repertoire.json")
    assert {s.category for s in specs} >= {"satb", "piano", "quartet", "leadsheet", "song"}
    for s in specs:
        assert s.work and s.engraver == "lilyscan"
        again = PieceSpec(**{k: v for k, v in s.to_dict().items()})  # type: ignore[arg-type]
        assert again == s


@pytest.mark.lilypond
def test_lilyscan_engraver_writes_one_page(tmp_path: Path) -> None:
    from lilyscan.synth.engrave import engrave

    fixture = Path(__file__).parent / "fixtures" / "features.musicxml"
    result = engrave(fixture, tmp_path, engraver="lilyscan")
    assert (result.pdf.name, result.png.name) == ("score.pdf", "score.png")
    assert (tmp_path / "ly" / "main.ly").is_file()


def test_parts_named_for_instruments_lose_the_default_piano() -> None:
    from music21 import instrument, note, stream

    from lilyscan.synth.generate import instruments_from_names

    score = stream.Score()
    for name in ("Violin 1", "Viola", "Piano", "Choir"):
        part = stream.Part()
        part.partName = name
        part.insert(0, instrument.Piano())  # what music21 gives a part a work left bare
        part.append(note.Note("C4"))
        score.append(part)
    instruments_from_names(score)
    got = [(p.partAbbreviation, type(p.getInstrument()).__name__) for p in score.parts]
    assert got == [("Vln. 1", "Violin"), ("Vla.", "Viola"), ("Pno", "Piano"), ("Ch.", "Choir")]
