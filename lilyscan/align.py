"""Measure alignment: parts transcribed separately, lined up measure by measure.

The engine reads each part on its own, and now and then drops a measure (a missed
barline) or adds one (a stray barline), so the parts of one piece seldom have exactly
the same measures. Each part is aligned to a reference part: the longest measure count
that at least two parts share (a part more often misses a measure than gains one), then
the most confident.

A part as long as the reference pairs with it measure for measure. Otherwise only the
shorter of the two gets gaps, exactly as many as it is short: a part misses measures or
has extra ones, not both, and different instruments' rhythms differ too much to trade a
pair of gaps for a mismatch. Where the gaps go is found by dynamic programming: measures
match cheaply when they have the same onsets (Jaccard distance over the onset positions
of all voices), both rest throughout, and agree on the landmarks all parts share:
double, final and repeat barlines, and changes of key or time.

Where a part has no measure for a column of the combined score, it gets a whole-measure
rest with no confidence, so the review list shows it. A part that read no time
signature at all takes the reference's.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from fractions import Fraction

from lilyscan.ir.models import Barline, Event, Measure, Part, Staff, Voice

_LANDMARK = frozenset({"light-light", "light-heavy", "heavy-light", "heavy-heavy"})


@dataclass
class Alignment:
    """What alignment changed, per part (by position in the list given)."""

    measures: int  # columns of the combined score
    filled: list[list[int]] = field(default_factory=list)  # column indices given a rest
    reference: int = 0
    # Per part, column ranges (first, last) where a rest it was given, or a measure the
    # others lack, could have gone at about the same cost: the part's measures there may
    # be a column off.
    unsure: list[list[tuple[int, int]]] = field(default_factory=list)

    @property
    def changed(self) -> bool:
        return any(self.filled)


def _onsets(m: Measure) -> frozenset[Fraction]:
    return frozenset(e.offset for v in m.voices for e in v.events if e.notes)


def _landmark(b: Barline | None) -> bool:
    return b is not None and (b.style in _LANDMARK or b.repeat is not None)


def _cost(a: Measure, b: Measure) -> float:
    """How unlike two measures of different parts are (0 to about 2)."""
    x, y = _onsets(a), _onsets(b)
    if not x and not y:
        rhythm = 0.0
    elif not x or not y:
        rhythm = 0.6  # one part rests while the other plays: common, not damning
    else:
        rhythm = 1.0 - len(x & y) / len(x | y)
    marks = 0.0
    if _landmark(a.right_barline) != _landmark(b.right_barline):
        marks += 0.5
    if (a.key is not None) != (b.key is not None) and a.index and b.index:
        marks += 0.25
    if (a.time is not None) != (b.time is not None) and a.index and b.index:
        marks += 0.25
    return rhythm + marks


# Two placements of a gap whose costs differ by less than this are as good as each other.
_UNSURE = 0.1


def _fit(
    long: list[Measure], short: list[Measure]
) -> tuple[list[tuple[int, int | None]], list[tuple[int, int]]]:
    """``short`` matched in order to ``len(short)`` measures of ``long`` at the least cost:
    (``long`` measure, ``short`` measure or None) for every measure of ``long``.

    Of placements that cost the same, the gaps go as late as possible: a part read short
    has most often lost its last measure. Also returns, for each gap, the first and last
    measure of ``long`` where it costs about as little."""
    n, m = len(long), len(short)
    inf = float("inf")
    cost = {
        (i, j): _cost(long[i], short[j])
        for i in range(n)
        for j in range(max(0, i - (n - m)), min(i, m - 1) + 1)
    }
    # best[i][j]: long[:i] with short[:j]; rest[i][j]: long[i:] with short[j:].
    best = [[inf] * (m + 1) for _ in range(n + 1)]
    best[0][0] = 0.0
    for i in range(1, n + 1):
        for j in range(max(0, m - (n - i)), min(i, m) + 1):
            take = best[i - 1][j - 1] + cost[(i - 1, j - 1)] if j else inf
            best[i][j] = min(best[i - 1][j], take)  # skip: long[i - 1] has no partner
    rest = [[inf] * (m + 1) for _ in range(n + 1)]
    rest[n][m] = 0.0
    for i in range(n - 1, -1, -1):
        for j in range(max(0, i - (n - m)), min(i, m) + 1):
            take = cost[(i, j)] + rest[i + 1][j + 1] if j < m else inf
            rest[i][j] = min(rest[i + 1][j], take)
    out: list[tuple[int, int | None]] = []
    j = 0
    for i in range(n):  # matching as early as the cost allows puts the gaps late
        if j < m and rest[i][j] == cost[(i, j)] + rest[i + 1][j + 1]:
            out.append((i, j))
            j += 1
        else:
            out.append((i, None))
    gaps = [i for i, s in out if s is None]
    spans = []
    for k, chosen in enumerate(gaps):  # gap k at long[p] leaves p - k measures before it
        near = [
            p
            for p in range(k, n - (len(gaps) - k) + 1)
            if best[p][p - k] + rest[p + 1][p - k] <= rest[0][0] + _UNSURE
        ]
        spans.append((min(near, default=chosen), max(near, default=chosen)))
    return out, spans


def _pairs(
    ref: list[Measure], other: list[Measure]
) -> tuple[list[tuple[int | None, int | None]], list[tuple[int, int]]]:
    """(reference measure, other measure) pairs, None for a gap; only the shorter of the
    two has gaps. Also, for each gap, the measures it could go between at about the same
    cost, as indices of the longer part's measures."""
    if len(ref) == len(other):
        return [(i, i) for i in range(len(ref))], []
    if len(ref) > len(other):
        pairs, spans = _fit(ref, other)
        return list(pairs), spans
    pairs, spans = _fit(other, ref)
    return [(r, o) for o, r in pairs], spans


def _mean_confidence(part: Part) -> float:
    values = [
        e.confidence
        for st in part.staves
        for m in st.measures
        for v in m.voices
        for e in v.events
        if e.confidence is not None
    ]
    return sum(values) / len(values) if values else 0.0


def choose_reference(parts: list[Part]) -> int:
    """The longest measure count at least two parts share (the median one when none is
    shared), then the most confident part of that length."""
    counts = [len(p.staves[0].measures) for p in parts]
    shared = [c for c, n in Counter(counts).items() if n > 1]
    length = max(shared) if shared else sorted(counts)[len(counts) // 2]
    candidates = [k for k, c in enumerate(counts) if c == length]
    return max(candidates, key=lambda k: _mean_confidence(parts[k]))


def _rest(template: Measure, index: int) -> Measure:
    """A whole-measure rest as long as ``template``, flagged for review (no confidence)."""
    length = template.time.measure_length if template.time else None
    if length is None:
        length = max(
            (
                sum((e.duration for e in v.events if not e.grace), Fraction(0))
                for v in template.voices
            ),
            default=Fraction(4),
        ) or Fraction(4)
    rest = Event(
        kind="rest", offset=Fraction(0), duration=length, measure_rest=True, confidence=0.0
    )
    return Measure(index=index, number=str(index + 1), voices=[Voice(number=1, events=[rest])])


def align_parts(parts: list[Part]) -> tuple[list[Part], Alignment]:
    """The parts on one grid of measures, and where rests were put in."""
    if len(parts) < 2:
        n = len(parts[0].staves[0].measures) if parts else 0
        return parts, Alignment(n, [[] for _ in parts])
    ref_k = choose_reference(parts)
    ref = parts[ref_k].staves[0].measures
    # columns[c][k]: index of part k's measure in column c, or None.
    columns: list[list[int | None]] = [[None] * len(parts) for _ in ref]
    for c in range(len(ref)):
        columns[c][ref_k] = c
    # Per part, unsure spans in the longer part's measures: the reference's when the part
    # is short, the part's own when it has measures the reference lacks.
    spans: dict[int, tuple[bool, list[tuple[int, int]]]] = {}
    for k, part in enumerate(parts):
        if k == ref_k:
            continue
        # Merge this part's pairs into the columns in order. Columns other parts added
        # (measures the reference lacks) stay where they are, empty for this part.
        grown: list[list[int | None]] = []
        c = 0
        pairs, near = _pairs(ref, part.staves[0].measures)
        spans[k] = (len(ref) > len(part.staves[0].measures), near)
        for r, o in pairs:
            if r is None:  # a measure the reference lacks
                column: list[int | None] = [None] * len(parts)
                column[k] = o
                grown.append(column)
                continue
            while columns[c][ref_k] != r:
                grown.append(columns[c])
                c += 1
            columns[c][k] = o
            grown.append(columns[c])
            c += 1
        grown.extend(columns[c:])
        columns = grown
    aligned: list[Part] = []
    filled: list[list[int]] = [[] for _ in parts]
    for k, part in enumerate(parts):
        staves = []
        has_time = any(m.time for st in part.staves for m in st.measures)
        for st in part.staves:
            measures = []
            for c, column in enumerate(columns):
                own, at = column[k], column[ref_k]
                number = ref[at].number if at is not None else ""
                if own is not None and own < len(st.measures):
                    m = st.measures[own].model_copy(update={"index": c, "number": number})
                else:
                    template = next(
                        parts[j].staves[0].measures[x]
                        for j, x in enumerate(column)
                        if x is not None
                    )
                    m = _rest(template, c)
                    m.number = number
                    if st.number == part.staves[0].number:
                        filled[k].append(c)
                if not has_time and at is not None:
                    m.time = ref[at].time  # a part that read no time signature
                measures.append(m)
            if st.measures and measures:
                # The part's opening clef, key and time belong on its first column, even
                # when that column is one the part had no measure for.
                first, head = st.measures[0], measures[0]
                head.clefs = head.clefs or [c.model_copy() for c in first.clefs if c.offset == 0]
                head.key = head.key or first.key
                head.time = head.time or first.time
            staves.append(Staff(number=st.number, measures=measures))
        aligned.append(part.model_copy(update={"staves": staves}))
    unsure = _unsure_columns(columns, ref_k, spans, len(parts))
    for k, ranges in enumerate(unsure):
        for start, end in ranges:
            _doubt(aligned[k], start, end, filled[k])
    return aligned, Alignment(len(columns), filled, ref_k, unsure)


def _unsure_columns(
    columns: list[list[int | None]],
    ref_k: int,
    spans: dict[int, tuple[bool, list[tuple[int, int]]]],
    count: int,
) -> list[list[tuple[int, int]]]:
    """The spans of more than one measure as column ranges, overlapping ones merged (the
    rests of a part that is short by several measures can share their places)."""
    out: list[list[tuple[int, int]]] = [[] for _ in range(count)]
    for k, (short, near) in spans.items():
        owner = ref_k if short else k
        column_of = {col[owner]: c for c, col in enumerate(columns) if col[owner] is not None}
        ranges = sorted(
            (column_of[first], column_of[last])
            for first, last in near
            if last > first and first in column_of and last in column_of
        )
        for first, last in ranges:
            if out[k] and first <= out[k][-1][1]:
                out[k][-1] = (out[k][-1][0], max(out[k][-1][1], last))
            else:
                out[k].append((first, last))
    return out


def _doubt(part: Part, first: int, last: int, filled: list[int]) -> None:
    """The part's notes in columns ``first``..``last`` are in the right column only if the
    gap among them is where alignment put it, one place of ``last - first + 1``: their
    confidence is at most that chance."""
    chance = 1.0 / (last - first + 1)
    for st in part.staves:
        for m in st.measures[first : last + 1]:
            if m.index in filled:
                continue
            # The measure is the part's own copy; its voices and events are still shared
            # with the part as read.
            m.voices = [
                v.model_copy(
                    update={
                        "events": [
                            e.model_copy(
                                update={
                                    "confidence": chance
                                    if e.confidence is None
                                    else min(e.confidence, chance)
                                }
                            )
                            for e in v.events
                        ]
                    }
                )
                for v in m.voices
            ]
