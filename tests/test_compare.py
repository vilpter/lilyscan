from __future__ import annotations

from fractions import Fraction

from lilyscan.evaluation.compare import align, compare, levenshtein, staff_sigs
from lilyscan.ir.models import (
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
from lilyscan.ir.ops import merge_scores


def n(
    step: str, octave: int, offset: Fraction | int, dur: Fraction | int = 1, lyric: str = ""
) -> Event:
    return Event(
        kind="note",
        offset=Fraction(offset),
        duration=Fraction(dur),
        notes=[NoteHead(pitch=Pitch(step=step, octave=octave))],  # type: ignore[arg-type]
        lyrics=[Lyric(text=lyric)] if lyric else [],
    )


def rest(offset: int, dur: int, measure_rest: bool = False) -> Event:
    return Event(
        kind="rest", offset=Fraction(offset), duration=Fraction(dur), measure_rest=measure_rest
    )


def measure(i: int, *voices: list[Event]) -> Measure:
    return Measure(
        index=i,
        number=str(i + 1),
        time=TimeSignature(beats=2, beat_type=4) if i == 0 else None,
        voices=[Voice(number=k + 1, events=v) for k, v in enumerate(voices)],
    )


def score(*measures: Measure) -> Score:
    return Score(parts=[Part(id="P1", staves=[Staff(number=1, measures=list(measures))])])


GT = score(
    measure(0, [n("C", 4, 0, lyric="Ky"), n("D", 4, 1, lyric="ri")]),
    measure(1, [n("E", 4, 0), n("F", 4, 1)]),
    measure(2, [n("G", 4, 0, 2)]),
)


def test_levenshtein() -> None:
    assert levenshtein("kitten", "sitting") == 3
    assert levenshtein([], [1, 2]) == 2


def test_identical_scores_are_perfect() -> None:
    c = compare(GT, GT)
    assert c.measure_accuracy == 1.0 and c.edit_rate == 0.0
    assert c.note_prf == (1.0, 1.0, 1.0)
    assert c.lyric_accuracy == 1.0


def test_one_wrong_pitch_costs_one_measure_and_one_edit() -> None:
    pred = score(
        measure(0, [n("C", 4, 0, lyric="Ky"), n("D", 4, 1, lyric="ri")]),
        measure(1, [n("E", 4, 0), n("G", 4, 1)]),
        measure(2, [n("G", 4, 0, 2)]),
    )
    c = compare(GT, pred)
    assert c.exact_measures == 2
    assert c.event_edits == 1
    assert c.matched_notes == 4


def test_missing_measure_does_not_shift_the_rest() -> None:
    pred = score(
        measure(0, [n("C", 4, 0, lyric="Ky"), n("D", 4, 1, lyric="ri")]),
        measure(1, [n("G", 4, 0, 2)]),
    )
    c = compare(GT, pred)
    assert c.exact_measures == 2
    assert c.event_edits == 2
    pairs = align(staff_sigs(GT)[0], staff_sigs(pred)[0])
    assert pairs == [(0, 0), (1, None), (2, 1)]


def test_voice_numbers_and_rest_only_filler_voices_are_ignored() -> None:
    pred = score(
        measure(0, [rest(0, 2)], [n("C", 4, 0, lyric="Ky"), n("D", 4, 1, lyric="ri")]),
        measure(1, [n("E", 4, 0), n("F", 4, 1)]),
        measure(2, [n("G", 4, 0, 2)]),
    )
    assert compare(GT, pred).measure_accuracy == 1.0


def test_measure_rest_uses_the_measure_length() -> None:
    gt = score(measure(0, [rest(0, 2, measure_rest=True)]))
    pred = score(measure(0, [rest(0, 4, measure_rest=True)]))
    assert compare(gt, pred).measure_accuracy == 1.0


def test_engine_failure_scores_zero() -> None:
    c = compare(GT, None)
    assert not c.engine_ok and c.measure_accuracy == 0.0 and c.note_prf[2] == 0.0
    assert c.lyric_accuracy == 0.0


def test_merge_scores_appends_measures() -> None:
    merged = merge_scores([GT, GT])
    staff = merged.parts[0].staves[0]
    assert [m.index for m in staff.measures] == [0, 1, 2, 3, 4, 5]
