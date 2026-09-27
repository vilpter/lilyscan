"""Splitting lyric syllables the engine read as one word (Stage 5, item 6)."""

from __future__ import annotations

from fractions import Fraction

from lilyscan.engine.audiveris.omr import Box, Inter, OmrBook, OmrSheet, OmrSystem
from lilyscan.ir.models import (
    BBox,
    Event,
    Lyric,
    Measure,
    NoteHead,
    Part,
    Pitch,
    Score,
    Staff,
    TimeSignature,
    Voice,
)
from lilyscan.repair.syllables import Anchor, split_glued_syllables, split_word

HEAD_W = 20.0


def anchor(centre: float, melisma: bool = False) -> Anchor:
    return Anchor(centre, centre - HEAD_W / 2, melisma)


def test_centred_syllables_are_split_where_they_sit() -> None:
    # "maz" centred at 700 and "ing" at 780, read as one word 660-820.
    found = split_word("mazing", Box(660, 550, 160, 40), [anchor(700), anchor(780)])
    assert found is not None and found[1] == ["maz", "ing"]


def test_three_syllables() -> None:
    # With the glyph widths, "tri", "um" and "phant" are centred near 130, 202 and 322.
    found = split_word(
        "triumphant", Box(100, 550, 300, 40), [anchor(130), anchor(202), anchor(322)]
    )
    assert found is not None and found[1] == ["tri", "um", "phant"]


def test_a_diphthong_is_not_split() -> None:
    # One syllable under two close notes (the second a melisma note) stays whole.
    assert (
        split_word("sieh", Box(100, 550, 70, 40), [anchor(115, melisma=True), anchor(150)]) is None
    )


def test_a_word_left_aligned_over_a_melisma_is_not_split() -> None:
    # "strength" starts at the first head and reaches over the second.
    assert (
        split_word("strength", Box(90, 550, 150, 40), [anchor(100, melisma=True), anchor(160)])
        is None
    )


def note_at(x: float, offset: int, lyric: str | None = None) -> Event:
    return Event(
        kind="note",
        offset=Fraction(offset),
        duration=Fraction(1),
        notes=[NoteHead(pitch=Pitch(step="C", octave=5))],
        lyrics=[Lyric(text=lyric, syllabic="single")] if lyric else [],
        bbox=BBox(page=0, x=x - HEAD_W / 2, y=400, w=HEAD_W, h=18),
    )


def page(*items: tuple[str, Box]) -> OmrBook:
    inters = [
        Inter(
            id=k,
            kind="lyric-item",
            shape="LYRICS",
            grade=0.8,
            ctx_grade=0.8,
            staff=1,
            box=box,
            step=None,
            value=text,
            role="Syllable",
        )
        for k, (text, box) in enumerate(items)
    ]
    system = OmrSystem(stacks=[], parts={}, inters=inters)
    return OmrBook("5.11.0", [OmrSheet(1, 2480, 3508, 20.0, [system])], {})


def one_measure(*events: Event) -> Score:
    m = Measure(
        index=0,
        number="1",
        time=TimeSignature(beats=len(events), beat_type=4),
        voices=[Voice(number=1, events=list(events))],
    )
    return Score(parts=[Part(id="P1", name="Voice", staves=[Staff(number=1, measures=[m])])])


def test_glued_word_is_spread_over_its_notes() -> None:
    # The engine put "mazing" on the second note; the first has no syllable.
    first, second, third = note_at(700, 0), note_at(780, 1, "mazing"), note_at(900, 2, "grace")
    score = one_measure(first, second, third)
    repairs = split_glued_syllables(score, page(("mazing", Box(660, 550, 160, 40))))
    assert [(ly.text, ly.syllabic) for ly in first.lyrics] == [("maz", "begin")]
    assert [(ly.text, ly.syllabic) for ly in second.lyrics] == [("ing", "end")]
    assert third.lyrics[0].text == "grace"
    assert second.provenance[-1].before == {"lyrics": [[1, "mazing"]]}
    assert [(r.rule, r.measures) for r in repairs] == [("lyric-split", ["1"])]


def test_word_under_one_note_is_left_alone() -> None:
    first, second = note_at(700, 0, "grace"), note_at(820, 1)
    score = one_measure(first, second)
    assert split_glued_syllables(score, page(("grace", Box(665, 550, 70, 40)))) == []
    assert [ly.text for ly in first.lyrics] == ["grace"] and second.lyrics == []
