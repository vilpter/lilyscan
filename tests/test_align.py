"""Measure alignment of parts read separately (combiner)."""

from __future__ import annotations

from fractions import Fraction
from pathlib import Path

import pytest

from lilyscan.align import align_parts
from lilyscan.combine import CombineError, Selection, combine
from lilyscan.ir.models import (
    Barline,
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
from lilyscan.pipeline import produce

# Four rhythms (durations in quarters), so each measure is recognisable by its onsets.
RHYTHMS = [[4], [2, 2], [1, 1, 2], [1, 1, 1, 1], [3, 1], [2, 1, 1]]


def measure(i: int, rhythm: list[int], time: bool = True) -> Measure:
    events, offset = [], 0
    for d in rhythm:
        head = NoteHead(pitch=Pitch(step="C", octave=5))
        events.append(
            Event(kind="note", offset=Fraction(offset), duration=Fraction(d), notes=[head])
        )
        offset += d
    return Measure(
        index=i,
        number=str(i + 1),
        clefs=[Clef(sign="G", line=2)] if i == 0 else [],
        time=TimeSignature(beats=4, beat_type=4) if i == 0 and time else None,
        voices=[Voice(number=1, events=events)],
    )


def part(pid: str, rhythms: list[list[int]], time: bool = True) -> Part:
    ms = [measure(i, r, time) for i, r in enumerate(rhythms)]
    ms[-1].right_barline = Barline(style="light-heavy")
    return Part(id=pid, name=pid, staves=[Staff(number=1, measures=ms)])


def onsets(p: Part) -> list[list[int]]:
    return [
        [int(e.duration) for v in m.voices for e in v.events if e.notes]
        for m in p.staves[0].measures
    ]


PIECE = [RHYTHMS[i % 6] for i in range(12)]


def test_a_missing_measure_becomes_a_flagged_rest_in_its_place() -> None:
    full = part("A", PIECE)
    short = part("B", PIECE[:5] + PIECE[6:])  # the engine missed measure 6 of part B
    third = part("C", PIECE)
    (a, b, _), alignment = align_parts([full, short, third])
    assert alignment.measures == 12 and alignment.filled == [[], [5], []]
    rest = b.staves[0].measures[5].voices[0].events[0]
    assert rest.measure_rest and rest.confidence == 0.0 and rest.duration == 4
    assert onsets(b)[6:] == onsets(a)[6:]


def test_an_extra_measure_gives_the_others_a_rest() -> None:
    extra = part("B", [*PIECE[:3], [1, 3], *PIECE[3:]])
    _, alignment = align_parts([part("A", PIECE), extra, part("C", PIECE)])
    assert alignment.measures == 13
    assert alignment.filled == [[3], [], [3]]


def test_parts_that_line_up_are_left_alone() -> None:
    parts = [part("A", PIECE), part("B", PIECE)]
    aligned, alignment = align_parts(parts)
    assert not alignment.changed
    assert [onsets(p) for p in aligned] == [onsets(p) for p in parts]


def test_parts_as_long_as_each_other_pair_measure_for_measure() -> None:
    # Different instruments play different rhythms, and a stretch of one part can look
    # like another's shifted by a measure. That is no reason to shift the part (a gap on
    # each side): parts as long as each other have the same measures.
    other = PIECE[:4] + PIECE[5:11] + [PIECE[4]] + PIECE[11:]
    parts = [part("A", PIECE), part("B", other), part("C", PIECE)]
    aligned, alignment = align_parts(parts)
    assert not alignment.changed and alignment.measures == 12
    assert onsets(aligned[1]) == other


def test_the_reference_is_the_longest_count_two_parts_share() -> None:
    # Two parts of 12 measures, two that each missed a different measure: the short ones
    # get a rest where they miss it, and no part gets a measure the others lack.
    short_a = PIECE[:3] + PIECE[4:]
    short_b = PIECE[:8] + PIECE[9:]
    parts = [part("A", short_a), part("B", PIECE), part("C", short_b), part("D", PIECE)]
    _, alignment = align_parts(parts)
    assert alignment.measures == 12
    assert alignment.filled == [[3], [], [8], []]


def test_a_part_without_a_time_signature_takes_the_references() -> None:
    parts = [part("A", PIECE), part("B", PIECE), part("C", PIECE, time=False)]
    (_, _, c), _ = align_parts(parts)
    assert c.staves[0].measures[0].time == TimeSignature(beats=4, beat_type=4)


def test_a_missing_first_measure_keeps_the_opening_clef() -> None:
    late = part("B", PIECE[1:])
    (_, b, _), alignment = align_parts([part("A", PIECE), late, part("C", PIECE)])
    assert alignment.filled[1] == [0]
    assert b.staves[0].measures[0].clefs[0].sign == "G"


def test_a_rest_among_like_measures_goes_last_and_they_are_flagged() -> None:
    # Three measures alike; part B has two. The rest could go in any of the three places
    # at the same cost: it goes last, and B's measures there are flagged as unsure.
    like = [1, 3]
    full = part("A", PIECE[:4] + [like] * 3 + PIECE[4:9])
    short = part("B", PIECE[:4] + [like] * 2 + PIECE[4:9])
    for e in (e for m in short.staves[0].measures for v in m.voices for e in v.events):
        e.confidence = 0.9
    (_, b, _), alignment = align_parts(
        [full, short, part("C", PIECE[:4] + [like] * 3 + PIECE[4:9])]
    )
    assert alignment.filled == [[], [6], []]
    assert alignment.unsure == [[], [(4, 6)], []]
    confidences = [{e.confidence for v in m.voices for e in v.events} for m in b.staves[0].measures]
    assert confidences[3] == {0.9} and confidences[7] == {0.9}
    assert confidences[4] == confidences[5] == {1 / 3} and confidences[6] == {0.0}
    # The part as read keeps its confidence.
    assert {e.confidence for v in short.staves[0].measures[4].voices for e in v.events} == {0.9}


def test_a_lost_last_measure_is_the_last_one_missing() -> None:
    # The engine lost the last measure, and with it the final bar line. The last two
    # measures are alike, but only the final bar line's place fits: the rest goes last,
    # and its place is not in doubt.
    piece = [*PIECE[:10], [4], [4]]
    short = part("B", piece[:11])
    short.staves[0].measures[-1].right_barline = None
    _, alignment = align_parts([part("A", piece), short, part("C", piece)])
    assert alignment.filled == [[], [11], []]
    assert alignment.unsure == [[], [], []]


def job(root: Path, p: Part) -> Path:
    produce(Score(title="Test", parts=[p]), root)
    return root


@pytest.mark.lilypond
def test_combine_lines_up_parts_and_reports_the_rests(tmp_path: Path) -> None:
    a = job(tmp_path / "a", part("P1", PIECE))
    b = job(tmp_path / "b", part("P1", PIECE[:7] + PIECE[8:]))
    report = combine(
        [Selection(a, "P1", name="Violin"), Selection(b, "P1", name="Viola")], tmp_path / "c"
    )
    assert report["qa"]["checks"][0]["passed"], report["qa"]  # compiles
    assert report["alignment"]["rests_added"] == {"Viola": [8]}
    assert "placement_unsure" not in report["alignment"]


@pytest.mark.lilypond
def test_combine_reports_where_a_rest_could_as_well_go(tmp_path: Path) -> None:
    like = [1, 3]
    a = job(tmp_path / "a", part("P1", PIECE[:4] + [like] * 3 + PIECE[4:9]))
    b = job(tmp_path / "b", part("P1", PIECE[:4] + [like] * 2 + PIECE[4:9]))
    report = combine(
        [Selection(a, "P1", name="Violin"), Selection(b, "P1", name="Viola")], tmp_path / "c"
    )
    assert report["alignment"]["rests_added"] == {"Viola": [7]}
    assert report["alignment"]["placement_unsure"] == {"Viola": [[5, 7]]}


@pytest.mark.lilypond
def test_combine_refuses_parts_that_hardly_line_up(tmp_path: Path) -> None:
    a = job(tmp_path / "a", part("P1", PIECE))
    b = job(tmp_path / "b", part("P1", [[1, 1, 1, 1]] * 3))  # another piece altogether
    with pytest.raises(CombineError, match="same piece"):
        combine([Selection(a, "P1"), Selection(b, "P1")], tmp_path / "c")
