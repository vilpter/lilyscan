"""Lilyscan's calibrated confidence (Stage 5, item 9)."""

from __future__ import annotations

import json
import random
from pathlib import Path

import pytest

from lilyscan.ir.musicxml import load_musicxml
from lilyscan.repair.confidence import (
    FEATURES,
    MODEL_PATH,
    calibrate_confidence,
    event_features,
    load_model,
    predict,
)
from lilyscan.runtime.config import AUDIVERIS_VERSION

FIXTURES = Path(__file__).parents[1] / "eval" / "fixtures" / "omr" / AUDIVERIS_VERSION


def engine_score():  # type: ignore[no-untyped-def]
    from lilyscan.engine.audiveris.omr import attach_geometry, read_omr

    score = load_musicxml(FIXTURES / "piano-two-voices" / "output.mxl", "audiveris")
    attach_geometry(score, read_omr(FIXTURES / "piano-two-voices" / "book.omr"))
    return score


def test_shipped_model_matches_the_features_and_met_its_target() -> None:
    model = load_model()
    assert model is not None, f"{MODEL_PATH.name} missing or trained on other features"
    assert len(model["weights"]) == len(FEATURES)
    xs = [p[0] for p in model["calibration"]]
    ys = [p[1] for p in model["calibration"]]
    assert xs == sorted(xs) and ys == sorted(ys)  # monotone recalibration map
    for row in model["trained"]["evaluation"]:
        assert row["ece_out_of_fold"] < 0.05, row


def test_features_cover_every_event() -> None:
    score = engine_score()
    rows = event_features(score)
    events = [e for _, s in score.staves() for m in s.measures for v in m.voices for e in v.events]
    assert len(rows) == len(events)
    assert all(len(x) == len(FEATURES) for _, x in rows)


def test_calibrated_confidence_replaces_grades() -> None:
    score = engine_score()
    assert calibrate_confidence(score)
    values = [
        e.confidence
        for _, s in score.staves()
        for m in s.measures
        for v in m.voices
        for e in v.events
    ]
    assert all(v is not None and 0.0 <= v <= 1.0 for v in values)


def test_no_model_keeps_the_grades(tmp_path: Path) -> None:
    stale = tmp_path / "confidence.json"
    stale.write_text(json.dumps({"features": ["grade"], "weights": [1.0], "bias": 0.0}))
    assert load_model(stale) is None
    assert load_model(tmp_path / "missing.json") is None


def test_predict_applies_the_recalibration_map() -> None:
    model = {"features": FEATURES, "weights": [0.0] * len(FEATURES), "bias": 0.0}
    assert predict(model, [0.0] * len(FEATURES)) == pytest.approx(0.5)
    model["calibration"] = [[0.2, 0.1], [0.6, 0.3]]
    assert predict(model, [0.0] * len(FEATURES)) == pytest.approx(0.25)


def test_fit_recovers_a_calibrated_model() -> None:
    np = pytest.importorskip("numpy")  # fitting needs the vision extra
    from lilyscan.evaluation.calibrate import Sample, cross_validate, ece, fit

    rng = random.Random(0)
    samples = []
    for i in range(3000):
        grade = rng.random()
        x = [0.0] * len(FEATURES)
        x[0] = grade
        right = rng.random() < grade**2  # the engine overstates its confidence
        samples.append(Sample(f"piece-{i % 20}", x, right, grade))
    model = fit(samples)
    assert np.isfinite(model["bias"])
    oof = cross_validate(samples)
    assert ece([(p, s.y) for p, s in zip(oof, samples, strict=True)]) < 0.05
    assert ece([(s.x[0], s.y) for s in samples]) > 0.1
