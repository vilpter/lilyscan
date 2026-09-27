"""Page geometry, orientation and light: find the page in a photo, turn it upright, flatten
the light.

Images are grayscale float32 arrays, 0 = black and 1 = white.
"""

from __future__ import annotations

from itertools import pairwise
from pathlib import Path
from typing import Any

import cv2
import numpy as np
from numpy.typing import NDArray

from lilyscan.runtime.config import PREPARE_MAX_CUT_LINES, PREPARE_MIN_PAGE_AREA

Image = NDArray[np.float32]

# Pages detected in a downscaled copy of this size (longest side, pixels).
_DETECT_SIZE = 1000
# A page filling more than this share of the frame is already cropped (a scan).
_FULL_FRAME = 0.95


def f32(a: object) -> Image:
    return np.asarray(a, dtype=np.float32)


def load_gray(path: Path) -> Image:
    raw = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if raw is None:
        raise ValueError(f"cannot read image {path}")
    return f32(raw) / np.float32(255.0)


def save_png(img: Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    out = np.clip(img * 255.0 + 0.5, 0, 255).astype(np.uint8)
    if not cv2.imwrite(str(path), out):
        raise OSError(f"cannot write {path}")


def _order(quad: NDArray[np.float32]) -> NDArray[np.float32]:
    """Corners as top-left, top-right, bottom-right, bottom-left."""
    s = quad.sum(axis=1)
    d = quad[:, 1] - quad[:, 0]
    return np.array(
        [quad[np.argmin(s)], quad[np.argmin(d)], quad[np.argmax(s)], quad[np.argmax(d)]],
        dtype=np.float32,
    )


def find_page(gray: Image) -> NDArray[np.float32] | None:
    """The page's corners in a photo, or None when the page fills the frame or none is found.

    The paper is the largest bright region: Otsu's threshold separates it from the
    background, and its outline is simplified to four corners. When the paper runs off
    the frame, a shadow or a stain can bend that outline through the music; an outline
    that cuts staff lines is not used.
    """
    h, w = gray.shape
    scale = _DETECT_SIZE / max(h, w)
    small = cv2.resize(gray, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    u8 = cv2.GaussianBlur(np.clip(small * 255, 0, 255).astype(np.uint8), (5, 5), 0)
    _, mask = cv2.threshold(u8, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return None
    outline = max(contours, key=cv2.contourArea)
    share = cv2.contourArea(outline) / float(mask.shape[0] * mask.shape[1])
    if not PREPARE_MIN_PAGE_AREA <= share <= _FULL_FRAME:
        return None
    hull = cv2.convexHull(outline)
    perimeter = cv2.arcLength(hull, True)
    quad = None
    for eps in (0.01, 0.02, 0.03, 0.05):
        approx = cv2.approxPolyDP(hull, eps * perimeter, True)
        if len(approx) == 4:
            quad = approx.reshape(4, 2).astype(np.float32)
            break
    if quad is None:
        quad = cv2.boxPoints(cv2.minAreaRect(hull)).astype(np.float32)
    if _cut_lines(ink_mask(small), quad) > PREPARE_MAX_CUT_LINES:
        return None
    return _order(quad / np.float32(scale))


def _cut_lines(mask: NDArray[np.uint8], quad: NDArray[np.float32]) -> float:
    """Share of the staff lines mostly inside ``quad`` that the outline cuts off."""
    inside = np.zeros(mask.shape, dtype=np.uint8)
    cv2.fillPoly(inside, [np.round(quad).astype(np.int32)], 1)
    lines = (_lines(mask, horizontal=True) > 0).astype(np.uint8)
    n, labels = cv2.connectedComponents(lines, connectivity=8)
    total = np.bincount(labels.ravel(), minlength=n)[1:]
    kept = np.bincount(labels.ravel(), weights=inside.ravel(), minlength=n)[1:]
    held = kept > total / 2  # lines of this page, not of a facing page
    whole = float(total[held].sum())
    return float((total[held] - kept[held]).sum()) / whole if whole else 0.0


def warp_page(gray: Image, corners: NDArray[np.float32], trim: float = 0.004) -> Image:
    """The page seen head-on, trimmed slightly so no background remains at the edges."""
    tl, tr, br, bl = corners
    width = round(float(max(np.linalg.norm(tr - tl), np.linalg.norm(br - bl))))
    height = round(float(max(np.linalg.norm(bl - tl), np.linalg.norm(br - tr))))
    target = np.array(
        [[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]], dtype=np.float32
    )
    matrix = cv2.getPerspectiveTransform(corners, target)
    page = f32(
        cv2.warpPerspective(gray, matrix, (width, height), flags=cv2.INTER_CUBIC, borderValue=1.0)
    )
    dy, dx = int(height * trim), int(width * trim)
    return page[dy : height - dy, dx : width - dx]


def flatten_light(gray: Image) -> Image:
    """Divide out uneven lighting: the paper's brightness, estimated by removing the ink
    with a max filter wider than any stroke and smoothing, becomes white everywhere."""
    h, w = gray.shape
    factor = 4
    small = cv2.resize(
        gray, (max(1, w // factor), max(1, h // factor)), interpolation=cv2.INTER_AREA
    )
    k = max(3, (small.shape[1] // 40) | 1)
    paper = cv2.dilate(small, cv2.getStructuringElement(cv2.MORPH_RECT, (k, k)))
    paper = cv2.GaussianBlur(paper, (0, 0), float(k))
    paper = cv2.resize(paper, (w, h), interpolation=cv2.INTER_LINEAR)
    out = gray / np.maximum(paper, np.float32(0.05))
    ink = float(np.percentile(out, 0.5))
    out = (out - ink) / max(1e-3, 1.0 - ink)
    return f32(np.clip(out, 0.0, 1.0))


def ink_mask(gray: Image) -> NDArray[np.uint8]:
    """Ink as 1, paper as 0 (Otsu's threshold on a light-flattened page)."""
    u8 = np.clip(gray * 255, 0, 255).astype(np.uint8)
    _, mask = cv2.threshold(u8, 0, 1, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    return np.asarray(mask, dtype=np.uint8)


def sharpen(gray: Image, interline: float) -> Image:
    """Remove sensor noise and JPEG speckle, then restore edges the camera blurred.

    Non-local means denoising keeps stroke edges; an unsharp mask with a radius of a
    fifth of a staff space sharpens stems, flags and staff lines.
    """
    u8 = np.clip(gray * 255 + 0.5, 0, 255).astype(np.uint8)
    clean = f32(cv2.fastNlMeansDenoising(u8, None, h=12, templateWindowSize=7, searchWindowSize=21))
    clean /= np.float32(255.0)
    soft = cv2.GaussianBlur(clean, (0, 0), max(0.8, interline / 5))
    return f32(np.clip(clean + 1.0 * (clean - soft), 0.0, 1.0))


def binarize(gray: Image, interline: float) -> Image:
    """Black and white by a local threshold over a window of about three staff spaces."""
    u8 = np.clip(gray * 255 + 0.5, 0, 255).astype(np.uint8)
    block = max(3, int(3 * interline) | 1)
    out = cv2.adaptiveThreshold(
        u8, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, block, 15
    )
    return f32(out) / np.float32(255.0)


def _lines(mask: NDArray[np.uint8], horizontal: bool) -> NDArray[np.uint8]:
    """Long straight strokes in one direction (staff lines, or stems and barlines)."""
    length = max(mask.shape) // 12
    size = (length, 1) if horizontal else (1, length)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, size)
    return np.asarray(cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel), dtype=np.uint8)


def _staves(lines: NDArray[np.uint8]) -> list[tuple[int, int, int, int]]:
    """Staves as (top, bottom, left, right) from the rows of long horizontal strokes:
    five lines, evenly spaced."""
    width = lines.shape[1]
    rows = np.flatnonzero(lines.sum(axis=1) > 0.25 * width)
    centres: list[float] = []
    run: list[int] = []
    for r in rows:
        if run and r != run[-1] + 1:
            centres.append(sum(run) / len(run))
            run = []
        run.append(int(r))
    if run:
        centres.append(sum(run) / len(run))
    staves = []
    i = 0
    while i + 4 < len(centres):
        five = centres[i : i + 5]
        gaps = np.diff(five)
        if gaps.min() > 1 and gaps.max() <= 1.35 * gaps.min():
            top, bottom = int(five[0]), round(five[-1])
            cols = np.flatnonzero(lines[top : bottom + 1].any(axis=0))
            if cols.size:
                staves.append((top, bottom, int(cols.min()), int(cols.max())))
            i += 5
        else:
            i += 1
    return staves


def _start_weight(mask: NDArray[np.uint8]) -> float:
    """Evidence that the page is the right way round: staves open with a clef, and a
    treble clef reaches well above and below its staff, while a staff ends with a
    barline that stays within it. Positive when more ink sticks out beyond the staves at
    their left ends than at their right ends."""
    lines = _lines(mask, horizontal=True)
    symbols = (mask > 0) & (lines == 0)
    left = right = 0.0
    for top, bottom, x0, x1 in _staves(lines):
        space = (bottom - top) / 4
        reach = int(2.5 * space)
        zone = max(1, int(3 * space))
        above = slice(max(0, top - reach), max(0, top - 1))
        below = slice(bottom + 2, bottom + reach)
        for rows in (above, below):
            left += float(symbols[rows, x0 : x0 + zone].sum())
            right += float(symbols[rows, max(0, x1 - zone) : x1 + 1].sum())
    return (left - right) / max(1.0, left + right)


def _small_mask(gray: Image, size: int = 2000) -> NDArray[np.uint8]:
    scale = size / max(gray.shape)
    small = gray
    if scale < 1:
        small = f32(cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA))
    return ink_mask(small)


def _rotate(img: NDArray[Any], degrees: float, fill: float) -> NDArray[Any]:
    """Rotate counterclockwise by ``degrees`` on a canvas that keeps every corner."""
    h, w = img.shape[:2]
    matrix = cv2.getRotationMatrix2D((w / 2, h / 2), degrees, 1.0)
    cos, sin = abs(matrix[0, 0]), abs(matrix[0, 1])
    size = (int(h * sin + w * cos + 0.5), int(h * cos + w * sin + 0.5))
    matrix[0, 2] += size[0] / 2 - w / 2
    matrix[1, 2] += size[1] / 2 - h / 2
    return np.asarray(cv2.warpAffine(img, matrix, size, flags=cv2.INTER_LINEAR, borderValue=fill))


def _sharpness(mask: NDArray[np.uint8], degrees: float) -> float:
    """How spiky the ink per row is after rotating: staff lines lined up with the rows
    make a few rows very dark."""
    rows = _rotate(mask.astype(np.float32), degrees, 0.0).sum(axis=1)
    return float((rows**2).sum())


def staff_angle(gray: Image) -> float:
    """Degrees (counterclockwise) that make the staff lines horizontal, in [-90, 90)."""
    mask = _small_mask(gray, 1000)
    coarse = max(range(-90, 90, 2), key=lambda a: _sharpness(mask, a))
    fine = [coarse + d / 4 for d in range(-8, 9)]
    best = max(fine, key=lambda a: _sharpness(mask, a))
    return float((best + 90) % 180 - 90)


def level_staves(gray: Image) -> tuple[Image, float]:
    """The page turned so its staff lines run across it (possibly upside down), and the
    angle turned. Small tilts are left to dewarping."""
    angle = staff_angle(gray)
    if abs(angle) < 2.0:
        return gray, 0.0
    if abs(abs(angle) - 90) < 0.5:
        return f32(np.rot90(gray, 1 if angle > 0 else 3)), 90.0 if angle > 0 else -90.0
    return f32(_rotate(gray, angle, 1.0)), angle


# Text evidence counts once a page has this many letters and a clear lean either way.
_MIN_LETTERS = 40
_MIN_TEXT_LEAN = 0.01


def _text_lean(mask: NDArray[np.uint8]) -> tuple[float, int]:
    """How upright the page's text reads, and how many letters that rests on.

    In Latin script the tops of letters vary (x-height letters, ascenders, capitals,
    digits) while their bottoms line up on the baseline (descenders are rare); upside
    down, it is the other way round. Positive when the tops vary more.
    """
    strokes = _lines(mask, horizontal=True) | _lines(mask, horizontal=False)
    symbols = ((mask > 0) & (strokes == 0)).astype(np.uint8)
    _, _, stats, _ = cv2.connectedComponentsWithStats(symbols, connectivity=8)
    boxes = stats[1:]
    h, w = boxes[:, cv2.CC_STAT_HEIGHT], boxes[:, cv2.CC_STAT_WIDTH]
    boxes = boxes[(h >= 6) & (h <= 45) & (w >= 2) & (w <= 45)]  # letter-sized
    if len(boxes) == 0:
        return 0.0, 0
    top = boxes[:, cv2.CC_STAT_TOP].astype(float)
    left = boxes[:, cv2.CC_STAT_LEFT].astype(float)
    height = boxes[:, cv2.CC_STAT_HEIGHT].astype(float)
    width = boxes[:, cv2.CC_STAT_WIDTH].astype(float)
    centre = top + height / 2
    used = np.zeros(len(boxes), dtype=bool)
    lean, letters = 0.0, 0
    for i in np.argsort(centre):
        if used[i]:
            continue
        row = np.flatnonzero(~used & (np.abs(centre - centre[i]) < 0.6 * height[i]))
        row = row[np.argsort(left[row])]
        # Split the row into words and phrases: letters close side by side.
        runs: list[list[int]] = [[int(row[0])]]
        for a, b in pairwise(row):
            if left[b] - (left[a] + width[a]) <= 1.5 * height[i]:
                runs[-1].append(int(b))
            else:
                runs.append([int(b)])
        for run in runs:
            if len(run) < 5:
                continue
            used[run] = True
            size = float(np.median(height[run]))
            tops, bottoms = top[run], top[run] + height[run]
            spread_top = float(np.mean(np.abs(tops - np.median(tops)))) / size
            spread_bottom = float(np.mean(np.abs(bottoms - np.median(bottoms)))) / size
            lean += (spread_top - spread_bottom) * len(run)
            letters += len(run)
    return (lean / letters if letters else 0.0), letters


def upside_down(gray: Image) -> bool:
    """On a page with straight, horizontal staves: its text reads upside down or, when
    there is too little text to tell, the clefs are at the right ends of the staves."""
    mask = _small_mask(gray)
    lean, letters = _text_lean(mask)
    if letters >= _MIN_LETTERS and abs(lean) >= _MIN_TEXT_LEAN:
        return lean < 0
    return _start_weight(mask) < -0.2
