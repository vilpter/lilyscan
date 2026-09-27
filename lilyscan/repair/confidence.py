"""Lilyscan's own confidence (Stage 5, item 9).

Audiveris grades say how well a symbol was recognized, not whether the event is
right: an octave clef, a missed triplet, or a photo's warped staff lines leave the
noteheads confidently graded. This combines the grade with evidence the pipeline
already has (does the voice fill its measure, was the event repaired, is the pitch in
the instrument's range, how dense is the measure, how well did the whole page read)
in a logistic model fitted on the evaluation corpus (``lilyscan eval calibrate``),
followed by a monotone recalibration map fitted on held-out predictions.

The model is a JSON file of feature weights shipped with the package; applying it
needs nothing beyond the standard library. Without a model the grades are kept.
"""

from __future__ import annotations

import json
import math
from fractions import Fraction
from itertools import pairwise
from pathlib import Path
from statistics import mean
from typing import Any

from lilyscan.ir.models import Event, Score
from lilyscan.qa.checks import instrument_range

MODEL_PATH = Path(__file__).with_name("confidence.json")

FEATURES = [
    "grade",  # engine grade (0 when unknown)
    "no_grade",  # the event was not located in the .omr
    "grade_below_0_5",  # the pitch did not match a notehead at the expected step
    "grade_below_0_8",
    "grade_below_0_9",
    "voice_fills",  # the event's voice adds up to the measure (after repair)
    "rhythm_repaired",
    "clef_repaired",
    "out_of_range",
    "rest",
    "chord",
    "grace",
    "tuplet",
    "altered",  # any pitch with a sharp or flat
    "density",  # events per quarter note in the measure
    "voices",  # voices in the measure beyond the first
    "measure_min_grade",
    "column_fills",  # share of the other staves' voices in this measure that add up
    "staff_fill_rate",  # share of this staff's voice-measures that add up
    "staff_mean_grade",
    "score_fill_rate",  # share of voice-measures in the score that add up
    "score_mean_grade",
    "score_located",  # share of events located in the .omr
]


def _lengths(score: Score) -> dict[int, Fraction | None]:
    out: dict[int, Fraction | None] = {}
    for _, staff in score.staves():
        length: Fraction | None = None
        for m in staff.measures:
            if m.time is not None:
                length = m.time.measure_length
            out[id(m)] = length
    return out


def event_features(score: Score) -> list[tuple[Event, list[float]]]:
    """Every event with its feature vector (in ``FEATURES`` order)."""
    lengths = _lengths(score)
    events = [
        (part, m, v, e)
        for part, staff in score.staves()
        for m in staff.measures
        for v in m.voices
        for e in v.events
    ]
    if not events:
        return []
    voice_measures = [
        v.duration() == lengths[id(m)]
        for _, staff in score.staves()
        for m in staff.measures
        for v in m.voices
        if v.events
    ]
    grades = [e.confidence for *_, e in events if e.confidence is not None]
    score_fill = sum(voice_measures) / len(voice_measures) if voice_measures else 0.0
    score_grade = mean(grades) if grades else 0.0
    score_located = sum(1 for *_, e in events if e.bbox is not None) / len(events)
    ranges = {id(part): instrument_range(part)[0] for part in score.parts}
    staff_of: dict[int, int] = {}
    staff_fill: dict[int, float] = {}
    staff_grade: dict[int, float] = {}
    column: dict[int, list[bool]] = {}
    for _, staff in score.staves():
        fills = []
        staff_grades = []
        for i, m in enumerate(staff.measures):
            staff_of[id(m)] = id(staff)
            for v in m.voices:
                if v.events:
                    ok = v.duration() == lengths[id(m)]
                    fills.append(ok)
                    column.setdefault(i, []).append(ok)
                staff_grades += [e.confidence for e in v.events if e.confidence is not None]
        staff_fill[id(staff)] = sum(fills) / len(fills) if fills else 0.0
        staff_grade[id(staff)] = mean(staff_grades) if staff_grades else score_grade

    out: list[tuple[Event, list[float]]] = []
    for part, m, v, e in events:
        g = e.confidence
        length = lengths[id(m)] or Fraction(4)
        measure_grades = [
            x.confidence for w in m.voices for x in w.events if x.confidence is not None
        ]
        low, high = ranges[id(part)]
        rules = {p.rule for p in e.provenance if p.stage == "repair"}
        own = [v.duration() == lengths[id(m)] for v in m.voices if v.events]
        others = list(column.get(m.index, []))
        for ok in own:
            others.remove(ok)
        values = {
            "grade": g or 0.0,
            "no_grade": float(g is None),
            "grade_below_0_5": float(g is not None and g < 0.5),
            "grade_below_0_8": float(g is not None and g < 0.8),
            "grade_below_0_9": float(g is not None and g < 0.9),
            "voice_fills": float(v.duration() == lengths[id(m)]),
            "rhythm_repaired": float("rhythm" in rules),
            "clef_repaired": float("octave-clef" in rules),
            "out_of_range": float(any(not low <= p.midi <= high for p in e.pitches)),
            "rest": float(e.kind == "rest"),
            "chord": float(len(e.notes) > 1),
            "grace": float(e.grace),
            "tuplet": float(e.tuplet is not None),
            "altered": float(any(p.alter for p in e.pitches)),
            "density": sum(len(w.events) for w in m.voices) / float(length),
            "voices": float(max(0, len([w for w in m.voices if w.events]) - 1)),
            "measure_min_grade": min(measure_grades) if measure_grades else 0.0,
            "column_fills": sum(others) / len(others) if others else 1.0,
            "staff_fill_rate": staff_fill[staff_of[id(m)]],
            "staff_mean_grade": staff_grade[staff_of[id(m)]],
            "score_fill_rate": score_fill,
            "score_mean_grade": score_grade,
            "score_located": score_located,
        }
        out.append((e, [values[f] for f in FEATURES]))
    return out


def load_model(path: Path = MODEL_PATH) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    model: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    if model.get("features") != FEATURES:
        return None  # trained on another feature set; refit with `lilyscan eval calibrate`
    return model


def predict(model: dict[str, Any], x: list[float]) -> float:
    z = model["bias"] + sum(w * v for w, v in zip(model["weights"], x, strict=True))
    p = 1.0 / (1.0 + math.exp(-max(-30.0, min(30.0, z))))
    return _recalibrate(model.get("calibration"), p)


def _recalibrate(points: list[list[float]] | None, p: float) -> float:
    """Piecewise-linear monotone map fitted on held-out predictions (isotonic)."""
    if not points:
        return p
    if p <= points[0][0]:
        return points[0][1]
    for (x0, y0), (x1, y1) in pairwise(points):
        if p <= x1:
            return y0 if x1 == x0 else y0 + (y1 - y0) * (p - x0) / (x1 - x0)
    return points[-1][1]


def calibrate_confidence(score: Score, model: dict[str, Any] | None = None) -> bool:
    """Replace each event's confidence with the calibrated probability that it is right.

    Returns False (and changes nothing) when no model is available.
    """
    model = model or load_model()
    if model is None:
        return False
    for e, x in event_features(score):
        e.confidence = round(predict(model, x), 4)
    return True
