"""Fit Lilyscan's confidence model (Stage 5, item 9) on a corpus's cached engine output.

Every engine event, after geometry and repairs, is labelled right or wrong against
the ground truth and described by ``lilyscan.repair.confidence.FEATURES``. A
logistic regression (L2-regularized, fitted by Newton's method) maps features to a
score, and an isotonic map fitted on out-of-fold scores turns it into the probability
that the event is right. Fitting needs numpy (the ``vision`` extra);
applying the model does not.
"""

from __future__ import annotations

import json
import logging
from collections import Counter
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from lilyscan.engine.audiveris.omr import OmrError, attach_geometry, read_omr
from lilyscan.evaluation.compare import _measure_lengths, _token, align, measure_sig, staff_sigs
from lilyscan.evaluation.harness import _engine_output
from lilyscan.ir.models import Score
from lilyscan.ir.musicxml import load_musicxml
from lilyscan.repair import apply_repairs
from lilyscan.repair.confidence import FEATURES, event_features, predict
from lilyscan.runtime.config import Settings
from lilyscan.synth.corpus import VARIANTS, CorpusItem, Variant

log = logging.getLogger(__name__)

L2 = 1e-2
ECE_BINS = 10
ISOTONIC_BINS = 40


def correct_events(gt: Score, pred: Score) -> dict[int, bool]:
    """``id(event) -> right?`` for every predicted event (as in ``event_correctness``)."""
    gt_sigs = staff_sigs(gt)
    out: dict[int, bool] = {}
    for k, (_, staff) in enumerate(pred.staves()):
        lengths = _measure_lengths(staff)
        sigs = [measure_sig(m, n) for m, n in zip(staff.measures, lengths, strict=True)]
        reference = gt_sigs[k] if k < len(gt_sigs) else []
        for gi, pi in align(reference, sigs):
            if pi is None:
                continue
            available = Counter(reference[gi].events) if gi is not None else Counter()
            for v in staff.measures[pi].voices:
                for e in v.events:
                    token = _token(e, lengths[pi])
                    out[id(e)] = available[token] > 0
                    if out[id(e)]:
                        available[token] -= 1
    return out


@dataclass
class Sample:
    group: str  # piece id: cross-validation folds never split a piece
    x: list[float]
    y: bool
    grade: float | None


@dataclass
class Dataset:
    samples: list[Sample] = field(default_factory=list)

    def add(self, group: str, pred: Score, gt: Score) -> None:
        labels = correct_events(gt, pred)
        for e, x in event_features(pred):
            self.samples.append(Sample(group, x, labels.get(id(e), False), e.confidence))


def collect(
    items: list[CorpusItem],
    work: Path,
    variants: tuple[Variant, ...] = VARIANTS,
    settings: Settings | None = None,
) -> Dataset:
    """Features and labels from cached engine output (engine runs are not repeated)."""
    s = settings or Settings.from_env()
    data = Dataset()
    for item in items:
        gt = load_musicxml(item.ground_truth, "ground-truth")
        for variant in variants:
            pred, _, _, _ = _engine_output(item, variant, work, s, reuse=True)
            if pred is None:
                continue
            omr = next(iter(sorted((work / item.spec.id / variant).glob("*.omr"))), None)
            book = None
            if omr is not None:
                try:
                    book = read_omr(omr)
                    attach_geometry(pred, book)
                except OmrError as exc:
                    log.warning("%s/%s: %s", item.spec.id, variant, exc)
            apply_repairs(pred, book=book)
            data.add(item.spec.id, pred, gt)
    return data


def fit_logistic(samples: list[Sample], l2: float = L2) -> dict[str, Any]:
    """Logistic regression by Newton's method, weights in raw feature units."""
    import numpy as np

    x = np.array([s.x for s in samples], dtype=float)
    y = np.array([s.y for s in samples], dtype=float)
    mu, sd = x.mean(axis=0), x.std(axis=0)
    sd[sd == 0] = 1.0
    z = np.hstack([np.ones((len(x), 1)), (x - mu) / sd])
    w = np.zeros(z.shape[1])
    penalty = l2 * len(x) * np.eye(z.shape[1])
    penalty[0, 0] = 0.0  # no penalty on the intercept
    for _ in range(50):
        p = 1.0 / (1.0 + np.exp(-np.clip(z @ w, -30, 30)))
        gradient = z.T @ (p - y) + penalty @ w
        hessian = (z * (p * (1 - p))[:, None]).T @ z + penalty
        step = np.linalg.solve(hessian, gradient)
        w -= step
        if np.abs(step).max() < 1e-8:
            break
    # Fold the standardization into the weights so applying needs no numpy.
    weights = w[1:] / sd
    bias = w[0] - float((w[1:] * mu / sd).sum())
    return {
        "features": FEATURES,
        "weights": [round(float(v), 6) for v in weights],
        "bias": round(float(bias), 6),
    }


def ece(pairs: list[tuple[float, bool]], bins: int = ECE_BINS) -> float:
    """Expected calibration error over equal-width confidence bins."""
    total = 0.0
    for b in range(bins):
        lo, hi = b / bins, (b + 1) / bins
        members = [(c, ok) for c, ok in pairs if lo <= c < hi or (b == bins - 1 and c == 1.0)]
        if members:
            acc = sum(ok for _, ok in members) / len(members)
            conf = sum(c for c, _ in members) / len(members)
            total += len(members) / len(pairs) * abs(acc - conf)
    return round(total, 4)


def fit_isotonic(
    preds: list[float], ys: list[bool], bins: int = ISOTONIC_BINS
) -> list[list[float]]:
    """Monotone map from prediction to accuracy: pool adjacent violators over bins of
    equal size, as (mean prediction, accuracy) breakpoints."""
    pairs = sorted(zip(preds, ys, strict=True))
    size = max(1, len(pairs) // bins)
    blocks = []  # [sum of predictions, sum of outcomes, count]
    for i in range(0, len(pairs), size):
        chunk = pairs[i : i + size]
        blocks.append([sum(p for p, _ in chunk), float(sum(y for _, y in chunk)), len(chunk)])
    merged: list[list[float]] = []
    for b in blocks:
        merged.append(b)
        while len(merged) > 1 and merged[-2][1] / merged[-2][2] > merged[-1][1] / merged[-1][2]:
            last = merged.pop()
            merged[-1] = [merged[-1][k] + last[k] for k in range(3)]
    return [[round(b[0] / b[2], 6), round(b[1] / b[2], 6)] for b in merged]


def _folds(samples: list[Sample], folds: int) -> list[set[str]]:
    groups = sorted({s.group for s in samples})
    return [set(groups[k::folds]) for k in range(folds)]


def fit(samples: list[Sample], folds: int = 4) -> dict[str, Any]:
    """Logistic model on all samples, recalibrated on its out-of-fold predictions."""
    model = fit_logistic(samples)
    oof = [0.0] * len(samples)
    for held in _folds(samples, folds):
        inner = fit_logistic([s for s in samples if s.group not in held])
        for i, s in enumerate(samples):
            if s.group in held:
                oof[i] = predict(inner, s.x)
    model["calibration"] = fit_isotonic(oof, [s.y for s in samples])
    return model


def cross_validate(samples: list[Sample], folds: int = 5) -> list[float]:
    """Out-of-fold predictions of the whole procedure, in sample order; folds never
    split a piece."""
    out = [0.0] * len(samples)
    for held in _folds(samples, folds):
        model = fit([s for s in samples if s.group not in held])
        for i, s in enumerate(samples):
            if s.group in held:
                out[i] = predict(model, s.x)
    return out


def _summary(
    name: str, samples: list[Sample], fitted: list[float], oof: list[float]
) -> dict[str, Any]:
    graded = [(s.grade, s.y) for s in samples if s.grade is not None]
    return {
        "corpus": name,
        "events": len(samples),
        "accuracy": round(sum(s.y for s in samples) / len(samples), 4),
        "ece_in_sample": ece([(p, s.y) for p, s in zip(fitted, samples, strict=True)]),
        "ece_out_of_fold": ece([(p, s.y) for p, s in zip(oof, samples, strict=True)]),
        "ece_engine_grade": ece(graded) if graded else None,
    }


def calibrate(corpora: dict[str, Dataset], out: Path) -> dict[str, Any]:
    """Fit on every corpus together; report each corpus in-sample and out of fold."""
    samples = [s for d in corpora.values() for s in d.samples]
    model = fit(samples)
    oof = cross_validate(samples)
    results = []
    start = 0
    for name, d in corpora.items():
        n = len(d.samples)
        fitted = [predict(model, s.x) for s in d.samples]
        results.append(_summary(name, d.samples, fitted, oof[start : start + n]))
        start += n
    model["trained"] = {
        "corpora": list(corpora),
        "events": len(samples),
        "created": datetime.now(UTC).isoformat(timespec="seconds"),
        "evaluation": results,
    }
    out.write_text(json.dumps(model, indent=1) + "\n", encoding="utf-8", newline="\n")
    return model
