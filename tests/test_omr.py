"""The .omr reader against real Audiveris 5.11.0 projects (eval/fixtures/omr)."""

from __future__ import annotations

from pathlib import Path

import pytest

from lilyscan.engine.audiveris.omr import (
    OmrError,
    attach_geometry,
    middle_line_diatonic,
    read_omr,
    staff_step,
)
from lilyscan.ir.models import Clef, Pitch
from lilyscan.ir.musicxml import load_musicxml
from lilyscan.runtime.config import AUDIVERIS_VERSION

FIXTURES = Path(__file__).parents[1] / "eval" / "fixtures" / "omr" / AUDIVERIS_VERSION


def load(name: str):  # type: ignore[no-untyped-def]
    d = FIXTURES / name
    return load_musicxml(d / "output.mxl", "audiveris"), read_omr(d / "book.omr")


@pytest.mark.parametrize(
    ("clef", "middle"),
    [
        (Clef(sign="G", line=2), 34),  # B4
        (Clef(sign="F", line=4), 22),  # D3
        (Clef(sign="C", line=3), 28),  # C4
        (Clef(sign="C", line=4), 26),  # A3
        (Clef(sign="G", line=2, octave_change=-1), 27),  # B3
    ],
)
def test_middle_line(clef: Clef, middle: int) -> None:
    assert middle_line_diatonic(clef) == middle


def test_staff_step_direction() -> None:
    treble = Clef(sign="G", line=2)
    assert staff_step(Pitch(step="B", octave=4), treble) == 0
    assert staff_step(Pitch(step="D", octave=5), treble) == -2  # above the middle line
    assert staff_step(Pitch(step="G", octave=4), treble) == 2


def test_reads_book_structure() -> None:
    _, book = load("split-flute")
    assert book.software_version == AUDIVERIS_VERSION
    assert len(book.sheets) == 1
    sheet = book.sheets[0]
    assert (sheet.width, sheet.height) == (2480, 3508)
    assert sum(len(s.stacks) for s in sheet.systems) == 16
    # The engine split one flute into two logical parts; the reader exposes that.
    assert sorted(book.logical_parts.values()) == ["F1.", "Flute"]


def test_attaches_boxes_and_confidence() -> None:
    score, book = load("piano-two-voices")
    stats = attach_geometry(score, book)
    assert stats.located_rate >= 0.98
    sheet = book.sheets[0]
    for _, staff in score.staves():
        for m in staff.measures:
            assert m.bbox is not None
            for v in m.voices:
                for e in v.events:
                    if e.bbox is None:
                        continue
                    assert e.bbox.page == 0
                    assert 0 <= e.bbox.x <= sheet.width and 0 <= e.bbox.y <= sheet.height
                    # Event boxes sit inside their measure horizontally.
                    assert m.bbox.x - 40 <= e.bbox.x <= m.bbox.x + m.bbox.w + 40
                    assert e.confidence is None or 0.0 <= e.confidence <= 1.0


def test_split_part_measures_are_not_mappable() -> None:
    score, book = load("split-flute")
    stats = attach_geometry(score, book)
    # Each half-part only exists on some systems; padding rests elsewhere are skipped.
    assert stats.mappable < stats.events
    assert stats.located_rate >= 0.95


def test_rejects_non_omr(tmp_path: Path) -> None:
    bad = tmp_path / "x.omr"
    bad.write_bytes(b"not a zip")
    with pytest.raises(OmrError):
        read_omr(bad)


def test_import_engine_output_attaches_geometry_and_overlays(tmp_path: Path) -> None:
    import shutil

    from lilyscan.pipeline import import_engine_output, repair_engine_output

    src = FIXTURES / "piano-two-voices"
    shutil.copy(src / "output.mxl", tmp_path / "score.mxl")
    shutil.copy(src / "book.omr", tmp_path / "score.omr")
    score, geometry = import_engine_output(
        tmp_path, {"mxl_files": ["score.mxl"], "omr_files": ["score.omr"]}
    )
    assert geometry is not None and geometry["audiveris"] == AUDIVERIS_VERSION
    assert geometry["located_rate"] >= 0.98
    assert geometry["pages"][0]["width"] == 2480
    assert geometry["confidence"] == "audiveris" and geometry["overlays"] == []
    # Overlays are drawn after repairs, with Lilyscan's calibrated confidence.
    repair_engine_output(score, tmp_path, geometry)
    assert geometry["confidence"] == "lilyscan"
    assert geometry["overlays"] == ["overlays/page-1.png"]
    assert (tmp_path / "overlays" / "page-1.png").stat().st_size > 0
    assert any(
        e.bbox for _, s in score.staves() for m in s.measures for v in m.voices for e in v.events
    )


def test_import_engine_output_without_omr(tmp_path: Path) -> None:
    import shutil

    from lilyscan.pipeline import import_engine_output

    shutil.copy(FIXTURES / "piano-two-voices" / "output.mxl", tmp_path / "score.mxl")
    score, geometry = import_engine_output(tmp_path, {"mxl_files": ["score.mxl"]})
    assert geometry is None and score.parts


def test_confidence_colours() -> None:
    from lilyscan.overlay import HIGH, LOW, MID, UNKNOWN, confidence_colour

    assert [confidence_colour(c) for c in (0.95, 0.8, 0.6, 0.2, None)] == [
        HIGH,
        HIGH,
        MID,
        LOW,
        UNKNOWN,
    ]
