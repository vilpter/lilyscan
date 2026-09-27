"""Page geometry and light: find the page in a photo, straighten it, flatten the light.

Images are grayscale float32 arrays, 0 = black and 1 = white.
"""

from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np
from numpy.typing import NDArray

from lilyscan.runtime.config import PREPARE_MIN_PAGE_AREA

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
    background, and its outline is simplified to four corners.
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
    return _order(quad / np.float32(scale))


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
