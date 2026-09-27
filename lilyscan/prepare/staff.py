"""Staff-based measurements and dewarping.

Staff lines are the page's straightness prior. The page is cut into vertical strips;
in each strip the ink per row (a horizontal projection) peaks at the staff lines.
Neighbouring strips are aligned window by window down the page, which gives the
vertical displacement of the lines across the page. A smooth displacement field
fitted to those samples is then removed by remapping, straightening curl and skew.
"""

from __future__ import annotations

from dataclasses import dataclass
from itertools import pairwise

import cv2
import numpy as np
from numpy.typing import NDArray

from lilyscan.prepare.image import Image, f32

STRIPS = 24  # vertical strips across the page
WINDOW = 6.0  # alignment window height, staff spaces
STEP = 2.0  # window spacing, staff spaces
MIN_MATCH = 0.6  # normalized correlation needed to trust a window's shift
MIN_SAMPLES = 20  # fewer aligned windows than this: no dewarp


@dataclass(frozen=True)
class Scale:
    thickness: float  # staff line thickness, pixels
    interline: float  # distance between staff lines, pixels


def _mode(values: NDArray[np.int64], low: int, high: int) -> int | None:
    values = values[(values >= low) & (values <= high)]
    if values.size == 0:
        return None
    return int(np.bincount(values).argmax())


def measure_scale(mask: NDArray[np.uint8]) -> Scale | None:
    """Staff line thickness and interline from vertical run lengths (the modal black and
    white runs), sampled on every eighth column."""
    h, w = mask.shape
    cols = mask[:, :: max(1, w // 400)].astype(np.int8)
    black: list[NDArray[np.int64]] = []
    white: list[NDArray[np.int64]] = []
    for col in cols.T:
        edges = np.flatnonzero(np.diff(np.concatenate(([0], col, [0]))))
        runs = np.diff(edges)
        # Runs alternate black, white, black, ... from the first black run.
        black.append(runs[0::2])
        white.append(runs[1::2])
    thickness = _mode(np.concatenate(black), 1, max(2, h // 150))
    if thickness is None:
        return None
    space = _mode(np.concatenate(white), 2 * thickness, max(3 * thickness, h // 25))
    if space is None:
        return None
    return Scale(float(thickness), float(space + thickness))


def _profiles(mask: NDArray[np.uint8]) -> tuple[NDArray[np.float32], NDArray[np.float32]]:
    """Ink per row in each strip (strips x rows), and the strips' centre x."""
    w = mask.shape[1]
    bounds = np.linspace(0, w, STRIPS + 1).astype(int)
    ink = f32(np.stack([mask[:, a:b].mean(axis=1) for a, b in pairwise(bounds)]))
    # Smooth along each profile (not across strips) for a sub-pixel peak.
    rows = f32(cv2.GaussianBlur(ink, (7, 1), sigmaX=1.2, sigmaY=0.01))
    centres = f32((bounds[:-1] + bounds[1:]) / 2)
    return rows, centres


def _shift(
    a: NDArray[np.float32], b: NDArray[np.float32], y: int, half: int, reach: int
) -> tuple[float, float]:
    """The shift s maximizing the correlation of a[y-half:y+half] with b[y+s-half:y+s+half],
    and the correlation there."""
    ref = a[y - half : y + half]
    ref = ref - ref.mean()
    norm_ref = float(np.sqrt((ref**2).sum()))
    best, best_s = -1.0, 0.0
    scores = []
    for s in range(-reach, reach + 1):
        seg = b[y + s - half : y + s + half]
        seg = seg - seg.mean()
        denom = norm_ref * float(np.sqrt((seg**2).sum()))
        score = float((ref * seg).sum() / denom) if denom > 0 else -1.0
        scores.append(score)
        if score > best:
            best, best_s = score, float(s)
    # Sub-pixel peak by a parabola through the best score and its neighbours.
    k = int(best_s) + reach
    if 0 < k < len(scores) - 1:
        left, mid, right = scores[k - 1], scores[k], scores[k + 1]
        curve = left - 2 * mid + right
        if curve < 0:
            best_s += 0.5 * (left - right) / curve
    return best_s, best


def displacement_samples(mask: NDArray[np.uint8], interline: float) -> NDArray[np.float64]:
    """(x, y, d) samples: at page position (x, y), the staff lines sit d pixels below where
    they sit in the centre strip. Neighbouring strips are aligned outward from the centre."""
    h, _ = mask.shape
    rows, centres = _profiles(mask)
    half = int(WINDOW * interline / 2)
    reach = max(2, int(interline * 0.45))
    step = max(1, int(STEP * interline))
    ys = np.arange(half + reach + 1, h - half - reach - 1, step)
    mid = STRIPS // 2
    samples: list[tuple[float, float, float]] = []
    for y in ys:
        # Skip windows without staff-line structure in the centre strip.
        if rows[mid][y - half : y + half].std() < 0.02:
            continue
        for direction in (-1, 1):
            offset = 0.0
            prev = mid
            for s in range(mid + direction, STRIPS if direction > 0 else -1, direction):
                at = round(y + offset)
                if at - half - reach < 0 or at + half + reach >= h:
                    break
                shift, score = _shift(rows[prev], rows[s], at, half, reach)
                if score < MIN_MATCH:
                    break
                offset += shift
                samples.append((float(centres[s]), float(y), offset))
                prev = s
        samples.append((float(centres[mid]), float(y), 0.0))
    return np.array(samples, dtype=np.float64).reshape(-1, 3)


def _terms(x: NDArray[np.float64], y: NDArray[np.float64]) -> NDArray[np.float64]:
    """Polynomial terms of the displacement field: x^i y^j, i = 1..3, j = 0..2 (constant-in-x
    terms are left out: shifting whole rows up or down changes nothing)."""
    return np.stack([x**i * y**j for i in range(1, 4) for j in range(3)], axis=1)


@dataclass(frozen=True)
class Field:
    coefficients: NDArray[np.float64]
    width: int
    height: int
    residual: float  # RMS misfit of the samples, pixels

    def at(self, x: NDArray[np.float64], y: NDArray[np.float64]) -> NDArray[np.float64]:
        xn = x / self.width - 0.5
        yn = y / self.height - 0.5
        return _terms(xn, yn) @ self.coefficients


def fit_field(samples: NDArray[np.float64], width: int, height: int) -> Field | None:
    """Least-squares displacement field, refitted once without the worst outliers."""
    if len(samples) < MIN_SAMPLES:
        return None
    keep = np.ones(len(samples), dtype=bool)
    coefficients = np.zeros(9)
    residual = 0.0
    for _ in range(2):
        s = samples[keep]
        a = _terms(s[:, 0] / width - 0.5, s[:, 1] / height - 0.5)
        coefficients, *_ = np.linalg.lstsq(a, s[:, 2], rcond=None)
        misfit = (
            samples[:, 2]
            - _terms(samples[:, 0] / width - 0.5, samples[:, 1] / height - 0.5) @ coefficients
        )
        residual = float(np.sqrt(np.mean(misfit[keep] ** 2)))
        keep = np.abs(misfit) <= max(3.0 * residual, 1.0)
    return Field(coefficients, width, height, residual)


def dewarp(gray: Image, field: Field) -> Image:
    """Remove the displacement: each output row is read from where its line actually lies."""
    h, w = gray.shape
    xs = np.arange(w, dtype=np.float64)
    # Evaluate on a coarse grid and interpolate: the field is smooth.
    grid_x = np.linspace(0, w - 1, 64)
    grid_y = np.linspace(0, h - 1, 64)
    gx, gy = np.meshgrid(grid_x, grid_y)
    d = field.at(gx.ravel(), gy.ravel()).reshape(gy.shape)
    full = cv2.resize(f32(d), (w, h), interpolation=cv2.INTER_CUBIC)
    map_x, map_y = np.meshgrid(xs.astype(np.float32), np.arange(h, dtype=np.float32))
    return f32(
        cv2.remap(gray, map_x, f32(map_y + full), cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    )


def curvature(samples: NDArray[np.float64]) -> float:
    """RMS vertical displacement of the staff lines across the page, pixels (0 when flat)."""
    if len(samples) == 0:
        return 0.0
    return float(np.sqrt(np.mean(samples[:, 2] ** 2)))
