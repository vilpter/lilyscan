"""Key signatures misread on single systems (Stage 5)."""

from __future__ import annotations

from fractions import Fraction

from lilyscan.engine.audiveris.omr import OmrBook, OmrSheet, OmrSystem, Stack
from lilyscan.ir.models import (
    Barline,
    Event,
    KeySignature,
    Measure,
    NoteHead,
    Part,
    Pitch,
    Score,
    Staff,
    Voice,
)
from lilyscan.ir.ops import merge_scores
from lilyscan.repair.keys import consistent_keys, key_alter


def book(*systems: int, movements: tuple[int, ...] = (0,)) -> OmrBook:
    """Systems of the given measure counts, on one sheet."""
    return OmrBook(
        None,
        [
            OmrSheet(
                1,
                1000,
                1400,
                20.0,
                [
                    OmrSystem(
                        stacks=[Stack(0, 1, {}) for _ in range(n)],
                        parts={},
                        inters=[],
                        starts_movement=i in movements,
                    )
                    for i, n in enumerate(systems)
                ],
            )
        ],
        {},
    )


def measure(index: int, fifths: int | None, *heads: tuple[str, int, bool]) -> Measure:
    """A measure of quarter notes (step, alter, accidental printed)."""
    events = [
        Event(
            kind="note",
            offset=Fraction(i),
            duration=Fraction(1),
            notes=[
                NoteHead(pitch=Pitch(step=step, alter=alter, octave=5), accidental_shown=shown)  # type: ignore[arg-type]
            ],
        )
        for i, (step, alter, shown) in enumerate(heads)
    ]
    return Measure(
        index=index,
        number=str(index + 1),
        key=KeySignature(fifths=fifths) if fifths is not None else None,
        voices=[Voice(number=1, events=events)],
    )


def score(*measures: Measure) -> Score:
    return Score(parts=[Part(id="P1", staves=[Staff(number=1, measures=list(measures))])])


def keys(s: Score) -> list[tuple[int, int]]:
    return [(m.index, m.key.fifths) for m in s.parts[0].staves[0].measures if m.key]


def pitches(m: Measure) -> list[str]:
    return [p.label for e in m.voices[0].events for p in e.pitches]


def test_key_alter() -> None:
    assert key_alter(3, "G") == 1 and key_alter(2, "G") == 0
    assert key_alter(-2, "E") == -1 and key_alter(-1, "E") == 0


def test_a_system_read_with_a_sharp_missing_takes_the_key_of_the_others() -> None:
    # A major on every system but the second, read as G major: its C sharps came out
    # as C naturals. Two measures per system.
    s = score(
        measure(0, 3, ("C", 1, False)),
        measure(1, None, ("G", 1, False)),
        measure(2, 1, ("C", 0, False), ("G", 0, False)),
        measure(3, None, ("C", 0, False), ("F", 1, False)),
        measure(4, 3, ("C", 1, False)),
        measure(5, None, ("C", 1, False)),
    )
    log = consistent_keys(s, book(2, 2, 2))
    assert keys(s) == [(0, 3)]
    staff = s.parts[0].staves[0]
    assert pitches(staff.measures[2]) == ["C#5", "G#5"]
    assert pitches(staff.measures[3]) == ["C#5", "F#5"]
    assert [r.measures for r in log] == [["3", "4"]]
    assert staff.measures[2].voices[0].events[0].provenance[0].before == {"pitches": ["C5"]}


def test_printed_accidentals_are_kept() -> None:
    # A natural printed on C holds to the end of the measure, whatever the key.
    s = score(
        measure(0, 2),
        measure(1, 0, ("C", 0, True), ("C", 0, False), ("F", 0, False)),
        measure(2, 2),
        measure(3, None),
    )
    consistent_keys(s, book(1, 1, 2))
    assert pitches(s.parts[0].staves[0].measures[1]) == ["C5", "C5", "F#5"]


def test_a_change_after_a_double_bar_is_kept() -> None:
    s = score(
        measure(0, 1, ("F", 1, False)),
        measure(1, None),
        measure(2, -1, ("B", -1, False)),
        measure(3, None),
    )
    s.parts[0].staves[0].measures[1].right_barline = Barline(style="light-light")
    assert consistent_keys(s, book(2, 1, 1)) == []
    assert keys(s) == [(0, 1), (2, -1)]


def test_a_change_in_mid_system_is_kept() -> None:
    s = score(measure(0, 0), measure(1, 2), measure(2, None), measure(3, None))
    assert consistent_keys(s, book(2, 1, 1)) == []
    assert keys(s) == [(0, 0), (1, 2)]


def test_each_piece_has_its_own_key() -> None:
    # Two pieces on the page, in D and in F; each is consistent.
    s = score(measure(0, 2), measure(1, None), measure(2, -1), measure(3, None))
    assert consistent_keys(s, book(1, 1, 1, 1, movements=(0, 2))) == []
    assert keys(s) == [(0, 2), (2, -1)]


def test_a_tie_goes_to_the_key_with_more_accidentals() -> None:
    s = score(measure(0, 3), measure(1, 2, ("G", 0, False)))
    consistent_keys(s, book(1, 1))
    assert keys(s) == [(0, 3)]
    assert pitches(s.parts[0].staves[0].measures[1]) == ["G#5"]


def test_a_movement_stating_no_key_has_none() -> None:
    first = score(measure(0, 3), measure(1, None))
    second = score(measure(0, None, ("C", 0, False)), measure(1, None))
    merged = merge_scores([first, second])
    assert keys(merged) == [(0, 3), (2, 0)]


def in_force(staff: Staff) -> list[int]:
    """The key in force in each measure."""
    out, fifths = [], 0
    for m in staff.measures:
        fifths = m.key.fifths if m.key else fifths
        out.append(fifths)
    return out


def ensemble(*reads: list[int]) -> Score:
    """One single-staff part per list: the key it was read in on each system (one measure
    per system), with a G on every system."""
    return Score(
        parts=[
            Part(
                id=f"P{i + 1}",
                staves=[
                    Staff(
                        number=1,
                        measures=[
                            measure(j, k, ("G", key_alter(k, "G"), False))
                            for j, k in enumerate(keys)
                        ],
                    )
                ],
            )
            for i, keys in enumerate(reads)
        ]
    )


def test_the_parts_of_a_piece_vote_together() -> None:
    # The viola's alto-clef key is misread on three systems of four; the violin and cello
    # read four sharps throughout, as does the viola's first system.
    s = ensemble([4, 4, 4, 4], [4, 2, 2, 2], [4, 4, 4, 4])
    log = consistent_keys(s, book(1, 1, 1, 1))
    assert [r.part for r in log] == ["P2"]
    viola = s.parts[1].staves[0]
    assert [m.key.fifths for m in viola.measures if m.key] == [4]
    assert all(pitches(m) == ["G#5"] for m in viola.measures)


def test_a_transposing_part_keeps_its_own_key() -> None:
    # A clarinet reads two sharps more than the strings, a horn no key at all.
    s = ensemble([1, 1, 1], [3, 3, 3], [0, 0, 0], [1, 0, 1])
    log = consistent_keys(s, book(1, 1, 1))
    assert [r.part for r in log] == ["P4"]
    assert [in_force(p.staves[0]) for p in s.parts] == [[1] * 3, [3] * 3, [0] * 3, [1] * 3]
