"""Measure alignment: parts transcribed separately, lined up measure by measure.

The engine reads each part on its own, and now and then drops a measure (a missed
barline) or adds one (a stray barline), so the parts of one piece seldom have exactly
the same measures. Each part is aligned to a reference part (the one whose measure count
most parts share, then the most confident) by dynamic programming over its measures:

- measures match cheaply when they have the same onsets (Jaccard distance over the
  onset positions of all voices), both rest throughout, and agree on the landmarks all
  parts share: double, final and repeat barlines, and changes of key or time;
- skipping a measure in either part costs a fixed amount.

Where a part has no measure for a column of the combined score, it gets a whole-measure
rest with no confidence, so the review list shows it. A part that read no time
signature at all takes the reference's.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from fractions import Fraction

from lilyscan.ir.models import Barline, Event, Measure, Part, Staff, Voice

GAP = 1.0
_LANDMARK = frozenset({"light-light", "light-heavy", "heavy-light", "heavy-heavy"})


@dataclass
class Alignment:
    """What alignment changed, per part (by position in the list given)."""

    measures: int  # columns of the combined score
    filled: list[list[int]] = field(default_factory=list)  # column indices given a rest
    reference: int = 0

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


def _pairs(ref: list[Measure], other: list[Measure]) -> list[tuple[int | None, int | None]]:
    """Needleman-Wunsch: (reference measure, other measure) pairs, None for a gap."""
    n, m = len(ref), len(other)
    score = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        score[i][0] = i * GAP
    for j in range(1, m + 1):
        score[0][j] = j * GAP
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            score[i][j] = min(
                score[i - 1][j - 1] + _cost(ref[i - 1], other[j - 1]),
                score[i - 1][j] + GAP,
                score[i][j - 1] + GAP,
            )
    out: list[tuple[int | None, int | None]] = []
    i, j = n, m
    while i or j:
        if i and j and score[i][j] == score[i - 1][j - 1] + _cost(ref[i - 1], other[j - 1]):
            out.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif i and score[i][j] == score[i - 1][j] + GAP:
            out.append((i - 1, None))
            i -= 1
        else:
            out.append((None, j - 1))
            j -= 1
    return out[::-1]


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
    counts = [len(p.staves[0].measures) for p in parts]
    common = Counter(counts).most_common(1)[0][0]
    candidates = [k for k, c in enumerate(counts) if c == common]
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
    for k, part in enumerate(parts):
        if k == ref_k:
            continue
        # Merge this part's pairs into the columns in order. Columns other parts added
        # (measures the reference lacks) stay where they are, empty for this part.
        grown: list[list[int | None]] = []
        c = 0
        for r, o in _pairs(ref, part.staves[0].measures):
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
    return aligned, Alignment(len(columns), filled, ref_k)
