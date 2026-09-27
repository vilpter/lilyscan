"""Compare a predicted score with ground truth, measure by measure.

Staves are matched by their order across the whole score (engines sometimes split
or merge parts, but rarely reorder staves). Within a staff, measures are aligned
with a Needleman-Wunsch alignment, so one missed or extra barline costs one
measure instead of shifting everything after it.

A measure is *exact* when its voices, as a multiset of event sequences, match:
same onsets, durations, spelled pitches, and grace flags. Voice numbers are
ignored, and rest-only filler voices are dropped when the measure has notes.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Sequence
from dataclasses import asdict, dataclass, field
from fractions import Fraction
from typing import Any

from lilyscan.ir.models import Event, Measure, Score, Staff

PitchKey = tuple[str, int, int]
Token = tuple[Fraction, Fraction, tuple[PitchKey, ...] | None, bool]


def levenshtein[T](a: Sequence[T], b: Sequence[T]) -> int:
    if len(a) < len(b):
        a, b = b, a
    previous = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        current = [i]
        for j, y in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (x != y)))
        previous = current
    return previous[-1]


def _token(e: Event, measure_length: Fraction | None) -> Token:
    pitches = None if e.kind == "rest" else tuple(sorted(p.key() for p in e.pitches))
    duration = e.duration
    if e.measure_rest and measure_length is not None:
        duration = measure_length
    return (e.offset, duration, pitches, e.grace)


@dataclass(frozen=True)
class MeasureSig:
    voices: tuple[tuple[Token, ...], ...]
    events: tuple[Token, ...]
    notes: Counter[tuple[Fraction, PitchKey, Fraction]]
    onsets: Counter[tuple[Fraction, PitchKey]]
    lyrics: tuple[str, ...]


def measure_sig(m: Measure, measure_length: Fraction | None) -> MeasureSig:
    voices = [tuple(_token(e, measure_length) for e in v.events) for v in m.voices]
    voices = [v for v in voices if v]
    if any(any(t[2] is not None for t in v) for v in voices):
        voices = [v for v in voices if any(t[2] is not None for t in v)]
    events = tuple(sorted((t for v in voices for t in v), key=lambda t: (t[0], str(t[2]))))
    notes: Counter[tuple[Fraction, PitchKey, Fraction]] = Counter()
    onsets: Counter[tuple[Fraction, PitchKey]] = Counter()
    for t in events:
        for p in t[2] or ():
            notes[(t[0], p, t[1])] += 1
            onsets[(t[0], p)] += 1
    lyrics = tuple(
        ly.text.strip().rstrip("-").strip()
        for v in m.voices
        for e in v.events
        for ly in e.lyrics
        if ly.verse == 1
    )
    # Order voices canonically (voice numbers are ignored). Rests carry None instead of
    # pitches, so sort on a key where they compare as empty.
    ordered = sorted(voices, key=lambda v: [(t[0], t[1], t[2] or (), t[3]) for t in v])
    return MeasureSig(tuple(ordered), events, notes, onsets, lyrics)


def _measure_lengths(staff: Staff) -> list[Fraction | None]:
    """The time-signature length in force at each measure."""
    lengths: list[Fraction | None] = []
    length: Fraction | None = None
    for m in staff.measures:
        if m.time is not None:
            length = m.time.measure_length
        lengths.append(length)
    return lengths


def staff_sigs(score: Score) -> list[list[MeasureSig]]:
    return [
        [
            measure_sig(m, length)
            for m, length in zip(staff.measures, _measure_lengths(staff), strict=True)
        ]
        for _, staff in score.staves()
    ]


def _sub_cost(a: MeasureSig, b: MeasureSig) -> float:
    if a.voices == b.voices:
        return 0.0
    return min(1.0, levenshtein(a.events, b.events) / max(len(a.events), len(b.events), 1))


def align(gt: list[MeasureSig], pred: list[MeasureSig]) -> list[tuple[int | None, int | None]]:
    n, m = len(gt), len(pred)
    cost = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        cost[i][0] = float(i)
    for j in range(1, m + 1):
        cost[0][j] = float(j)
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            cost[i][j] = min(
                cost[i - 1][j - 1] + _sub_cost(gt[i - 1], pred[j - 1]),
                cost[i - 1][j] + 1.0,
                cost[i][j - 1] + 1.0,
            )
    pairs: list[tuple[int | None, int | None]] = []
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and cost[i][j] == cost[i - 1][j - 1] + _sub_cost(gt[i - 1], pred[j - 1]):
            pairs.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif i > 0 and cost[i][j] == cost[i - 1][j] + 1.0:
            pairs.append((i - 1, None))
            i -= 1
        else:
            pairs.append((None, j - 1))
            j -= 1
    pairs.reverse()
    return pairs


@dataclass
class Comparison:
    engine_ok: bool
    gt_staves: int = 0
    pred_staves: int = 0
    gt_measures: int = 0
    pred_measures: int = 0
    exact_measures: int = 0
    gt_events: int = 0
    event_edits: int = 0
    gt_notes: int = 0
    pred_notes: int = 0
    matched_notes: int = 0
    matched_onsets: int = 0
    gt_lyrics: int = 0
    lyric_edits: int = 0
    gt_chords: int = 0
    chord_edits: int = 0
    per_staff_exact: list[float] = field(default_factory=list)

    @property
    def measure_accuracy(self) -> float:
        return self.exact_measures / self.gt_measures if self.gt_measures else 0.0

    @property
    def edit_rate(self) -> float:
        """Event edits needed per ground-truth event (lower is better)."""
        return self.event_edits / self.gt_events if self.gt_events else 0.0

    @staticmethod
    def _f1(matched: int, gt: int, pred: int) -> tuple[float, float, float]:
        p = matched / pred if pred else 0.0
        r = matched / gt if gt else 0.0
        return p, r, (2 * p * r / (p + r) if p + r else 0.0)

    @property
    def note_prf(self) -> tuple[float, float, float]:
        return self._f1(self.matched_notes, self.gt_notes, self.pred_notes)

    @property
    def onset_prf(self) -> tuple[float, float, float]:
        """Pitch + onset only (durations ignored)."""
        return self._f1(self.matched_onsets, self.gt_notes, self.pred_notes)

    @property
    def lyric_accuracy(self) -> float | None:
        if not self.gt_lyrics:
            return None
        return max(0.0, 1 - self.lyric_edits / self.gt_lyrics)

    @property
    def chord_accuracy(self) -> float | None:
        if not self.gt_chords:
            return None
        return max(0.0, 1 - self.chord_edits / self.gt_chords)

    def to_dict(self) -> dict[str, Any]:
        p, r, f1 = self.note_prf
        return {
            **asdict(self),
            "measure_accuracy": round(self.measure_accuracy, 4),
            "edit_rate": round(self.edit_rate, 4),
            "note_precision": round(p, 4),
            "note_recall": round(r, 4),
            "note_f1": round(f1, 4),
            "onset_f1": round(self.onset_prf[2], 4),
            "lyric_accuracy": None
            if self.lyric_accuracy is None
            else round(self.lyric_accuracy, 4),
            "chord_accuracy": None
            if self.chord_accuracy is None
            else round(self.chord_accuracy, 4),
        }


def _chords(score: Score) -> list[tuple[str, str]]:
    """All chord symbols in reading order (measure index, then offset)."""
    found: list[tuple[int, Fraction, str, str]] = []
    for _, staff in score.staves():
        for m in staff.measures:
            found += [(m.index, c.offset, c.root, c.kind) for c in m.chord_symbols]
    return [(root, kind) for _, _, root, kind in sorted(found)]


def compare(gt: Score, pred: Score | None) -> Comparison:
    gt_staves = staff_sigs(gt)
    result = Comparison(engine_ok=pred is not None, gt_staves=len(gt_staves))
    for sigs in gt_staves:
        result.gt_measures += len(sigs)
        result.gt_events += sum(len(s.events) for s in sigs)
        result.gt_notes += sum(s.notes.total() for s in sigs)
        result.gt_lyrics += sum(len(s.lyrics) for s in sigs)
    result.gt_chords = len(_chords(gt))

    if pred is None:
        result.event_edits = result.gt_events
        result.lyric_edits = result.gt_lyrics
        result.chord_edits = result.gt_chords
        result.per_staff_exact = [0.0] * len(gt_staves)
        return result

    pred_staves = staff_sigs(pred)
    result.pred_staves = len(pred_staves)
    for sigs in pred_staves:
        result.pred_measures += len(sigs)
        result.pred_notes += sum(s.notes.total() for s in sigs)

    for k in range(max(len(gt_staves), len(pred_staves))):
        g = gt_staves[k] if k < len(gt_staves) else []
        p = pred_staves[k] if k < len(pred_staves) else []
        exact = 0
        for gi, pi in align(g, p):
            if gi is None and pi is not None:
                result.event_edits += len(p[pi].events)
            elif pi is None and gi is not None:
                result.event_edits += len(g[gi].events)
            elif gi is not None and pi is not None:
                a, b = g[gi], p[pi]
                if a.voices == b.voices:
                    exact += 1
                result.event_edits += levenshtein(a.events, b.events)
                result.matched_notes += (a.notes & b.notes).total()
                result.matched_onsets += (a.onsets & b.onsets).total()
        result.exact_measures += exact
        if g:
            result.per_staff_exact.append(round(exact / len(g), 4))
        g_lyrics = [ly for s in g for ly in s.lyrics]
        p_lyrics = [ly for s in p for ly in s.lyrics]
        if g_lyrics:
            result.lyric_edits += levenshtein(g_lyrics, p_lyrics)

    result.chord_edits = levenshtein(_chords(gt), _chords(pred)) if result.gt_chords else 0
    return result


def event_correctness(gt: Score, pred: Score) -> list[tuple[float | None, bool]]:
    """(confidence, correct) for every predicted event.

    An event is correct when an identical event (onset, duration, spelled pitches,
    grace flag) is still unclaimed in the ground-truth measure it aligns to. Events in
    measures or staves with no ground-truth counterpart are incorrect.
    """
    gt_sigs = staff_sigs(gt)
    out: list[tuple[float | None, bool]] = []
    for k, (_, staff) in enumerate(pred.staves()):
        lengths = _measure_lengths(staff)
        pred_sigs = [measure_sig(m, n) for m, n in zip(staff.measures, lengths, strict=True)]
        reference = gt_sigs[k] if k < len(gt_sigs) else []
        for gi, pi in align(reference, pred_sigs):
            if pi is None:
                continue
            available = Counter(reference[gi].events) if gi is not None else Counter()
            for v in staff.measures[pi].voices:
                for e in v.events:
                    token = _token(e, lengths[pi])
                    correct = available[token] > 0
                    if correct:
                        available[token] -= 1
                    out.append((e.confidence, correct))
    return out


# Confidence bands for calibration reports: [low, high).
CALIBRATION_BANDS = [(0.0, 0.5), (0.5, 0.7), (0.7, 0.8), (0.8, 0.9), (0.9, 1.01)]


def calibration(pairs: list[tuple[float | None, bool]]) -> dict[str, Any]:
    """Accuracy per confidence band and the expected calibration error (ECE).

    A well-calibrated engine is right about 60% of the time on events it scores 0.6.
    ECE is the event-weighted mean gap between band accuracy and band confidence.
    """
    scored = [(c, ok) for c, ok in pairs if c is not None]
    bands = []
    ece = 0.0
    for lo, hi in CALIBRATION_BANDS:
        members = [(c, ok) for c, ok in scored if lo <= c < hi]
        if not members:
            bands.append({"band": f"{lo:.1f}-{min(hi, 1.0):.1f}", "events": 0})
            continue
        accuracy = sum(ok for _, ok in members) / len(members)
        confidence = sum(c for c, _ in members) / len(members)
        ece += len(members) / len(scored) * abs(accuracy - confidence)
        bands.append(
            {
                "band": f"{lo:.1f}-{min(hi, 1.0):.1f}",
                "events": len(members),
                "accuracy": round(accuracy, 4),
                "mean_confidence": round(confidence, 4),
            }
        )
    unscored = [ok for c, ok in pairs if c is None]
    return {
        "events": len(pairs),
        "scored_events": len(scored),
        "ece": round(ece, 4) if scored else None,
        "bands": bands,
        "unscored_accuracy": round(sum(unscored) / len(unscored), 4) if unscored else None,
    }
