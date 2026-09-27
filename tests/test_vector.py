"""Stage 4: reading a born-digital PDF, and applying its tuplet numbers."""

from __future__ import annotations

from fractions import Fraction
from pathlib import Path

import pytest

pymupdf = pytest.importorskip("pymupdf")

from lilyscan.engine.audiveris.omr import OmrBook, OmrSheet  # noqa: E402
from lilyscan.ir.models import (  # noqa: E402
    BBox,
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
from lilyscan.vector.extract import read_pdf  # noqa: E402
from lilyscan.vector.staves import find_staves  # noqa: E402
from lilyscan.vector.tuplets import apply_tuplets  # noqa: E402

WIDTH, HEIGHT = 595.0, 842.0
SPACE = 5.0  # staff space, points
TOPS = (100.0, 180.0)
SCALE = 4.0  # engine pixels per point


def engraved(path: Path, triplet_at: float | None = None) -> None:
    doc = pymupdf.open()
    page = doc.new_page(width=WIDTH, height=HEIGHT)
    for top in TOPS:
        for line in range(5):
            page.draw_line((60, top + line * SPACE), (535, top + line * SPACE), width=0.5)
    page.insert_text((250, 60), "Title 3", fontsize=16)  # upright: not a tuplet number
    if triplet_at is not None:
        page.insert_text((triplet_at - 2, TOPS[0] - 8), "3", fontname="tiit", fontsize=8)
    doc.save(path)


def test_staves_and_italic_digits(tmp_path: Path) -> None:
    engraved(tmp_path / "a.pdf", triplet_at=120)
    [page] = read_pdf(tmp_path / "a.pdf")
    staves = find_staves(page)
    assert [round(s.top) for s in staves] == [100, 180]
    assert all(s.interline == pytest.approx(SPACE) for s in staves)
    assert staves[0].step(TOPS[0] + 4 * SPACE) == 0 and staves[0].step(TOPS[0]) == 8
    assert [g.name for g in page.glyphs] == ["italic.3"]
    assert page.music_font is None  # no embedded music font


def note(offset: Fraction, x: float, note_type: str = "eighth") -> Event:
    duration = Fraction(1, 2) if note_type == "eighth" else Fraction(1)
    return Event(
        kind="note",
        offset=offset,
        duration=duration,
        note_type=note_type,
        notes=[NoteHead(pitch=Pitch(step="C", octave=5))],  # type: ignore[arg-type]
        bbox=BBox(page=0, x=x * SCALE - 12, y=(TOPS[0] + 8) * SCALE, w=24, h=18),
    )


def measure_with_missed_triplet() -> Score:
    # 4/4: three eighths read without their 3, then three quarters (4.5 beats).
    xs = [110.0, 125.0, 140.0, 200.0, 280.0, 360.0]
    events, pos = [], Fraction(0)
    for k, x in enumerate(xs):
        e = note(pos, x, "eighth" if k < 3 else "quarter")
        events.append(e)
        pos += e.duration
    m = Measure(
        index=0,
        number="1",
        time=TimeSignature(beats=4, beat_type=4),
        voices=[Voice(number=1, events=events)],
        bbox=BBox(page=0, x=60 * SCALE, y=TOPS[0] * SCALE, w=475 * SCALE, h=4 * SPACE * SCALE),
    )
    return Score(parts=[Part(id="P1", name="Flute", staves=[Staff(number=1, measures=[m])])])


def book() -> OmrBook:
    return OmrBook("5.11.0", [OmrSheet(1, int(WIDTH * SCALE), int(HEIGHT * SCALE), 20.0, [])], {})


def test_printed_triplet_is_applied(tmp_path: Path) -> None:
    engraved(tmp_path / "a.pdf", triplet_at=125)
    score = measure_with_missed_triplet()
    repairs, stats = apply_tuplets(score, read_pdf(tmp_path / "a.pdf"), book())
    events = score.parts[0].staves[0].measures[0].voices[0].events
    assert stats.applied == 1 and [r.rule for r in repairs] == ["vector-tuplet"]
    assert [e.tuplet for e in events[:3]] == [(3, 2)] * 3
    assert [e.offset for e in events] == [0, Fraction(1, 3), Fraction(2, 3), 1, 2, 3]
    assert events[0].provenance[-1].stage == "vector-oracle"


def test_no_number_no_change(tmp_path: Path) -> None:
    engraved(tmp_path / "a.pdf")
    score = measure_with_missed_triplet()
    repairs, stats = apply_tuplets(score, read_pdf(tmp_path / "a.pdf"), book())
    assert repairs == [] and stats.marks == 0
    assert all(e.tuplet is None for e in score.parts[0].staves[0].measures[0].voices[0].events)


def test_number_over_notes_that_already_fit_is_left_alone(tmp_path: Path) -> None:
    # A 3 over the three quarters: a quarter triplet there would leave the measure half a
    # beat short, so nothing changes.
    engraved(tmp_path / "a.pdf", triplet_at=280)
    score = measure_with_missed_triplet()
    _, stats = apply_tuplets(score, read_pdf(tmp_path / "a.pdf"), book())
    assert stats.applied == 0 and stats.unplaced == 1
