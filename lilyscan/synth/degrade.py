"""Deterministic degradations that turn a clean engraving into a simulated scan or photo."""

from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np
from numpy.typing import NDArray

Image = NDArray[np.float32]  # grayscale, 0 = black, 1 = white


def _f32(a: object) -> Image:
    """OpenCV returns loosely typed arrays; pin them to float32."""
    return np.asarray(a, dtype=np.float32)


def load_gray(path: Path) -> Image:
    raw = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if raw is None:
        raise ValueError(f"cannot read image {path}")
    return _f32(raw) / np.float32(255.0)


def save_png(img: Image, path: Path) -> None:
    out = np.clip(img * 255.0 + 0.5, 0, 255).astype(np.uint8)
    if not cv2.imwrite(str(path), out):
        raise OSError(f"cannot write {path}")


def _jpeg(img: Image, quality: int) -> Image:
    u8 = np.clip(img * 255.0 + 0.5, 0, 255).astype(np.uint8)
    ok, buf = cv2.imencode(".jpg", u8, [cv2.IMWRITE_JPEG_QUALITY, quality])
    decoded = cv2.imdecode(buf, cv2.IMREAD_GRAYSCALE) if ok else None
    if decoded is None:
        raise RuntimeError("JPEG round trip failed")
    return _f32(decoded) / np.float32(255.0)


def _noise(img: Image, rng: np.random.Generator, sigma: float) -> Image:
    return _f32(img + rng.normal(0.0, sigma, img.shape))


def scan(img: Image, seed: int) -> Image:
    """Flatbed scan: small skew, soft focus, grey paper, noise, specks, JPEG."""
    rng = np.random.default_rng(seed)
    h, w = img.shape
    angle = float(rng.uniform(-1.2, 1.2))
    rot = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
    out = _f32(cv2.warpAffine(img, rot, (w, h), flags=cv2.INTER_LINEAR, borderValue=1.0))
    out = _f32(cv2.GaussianBlur(out, (0, 0), float(rng.uniform(0.4, 0.9))))
    out = _f32(out * float(rng.uniform(0.85, 1.0)) + float(rng.uniform(0.0, 0.08)))
    out = _noise(out, rng, float(rng.uniform(0.02, 0.05)))
    specks = rng.random(out.shape) < 2e-4
    out[specks] = 0.0
    out = np.clip(out, 0.0, 1.0).astype(np.float32)
    return _jpeg(out, int(rng.integers(70, 91)))


def photo(img: Image, seed: int) -> Image:
    """Phone photo: page curl, perspective on a darker background, uneven light, blur, JPEG."""
    rng = np.random.default_rng(seed)
    h, w = img.shape

    # Page curl: vertical displacement that bows the page, strongest mid-page.
    amp = float(rng.uniform(0.004, 0.012)) * h
    xs = np.arange(w, dtype=np.float32)
    ys = np.arange(h, dtype=np.float32)
    map_x, map_y = np.meshgrid(xs, ys)
    bow = amp * np.sin(np.pi * map_x / w) * (0.5 + 0.5 * map_y / h)
    curled = _f32(cv2.remap(img, map_x, _f32(map_y - bow), cv2.INTER_LINEAR, borderValue=1.0))

    # Place on a background with margins, then a random perspective warp.
    margin = int(0.06 * max(h, w))
    bg = float(rng.uniform(0.2, 0.4))
    canvas = np.full((h + 2 * margin, w + 2 * margin), bg, dtype=np.float32)
    canvas[margin : margin + h, margin : margin + w] = curled
    ch, cw = canvas.shape
    src = np.array([[0, 0], [cw, 0], [cw, ch], [0, ch]], dtype=np.float32)
    jitter = rng.uniform(-0.04, 0.04, size=(4, 2)) * np.array([cw, ch])
    dst = (src + jitter).astype(np.float32)
    matrix = cv2.getPerspectiveTransform(src, dst)
    out = _f32(
        cv2.warpPerspective(canvas, matrix, (cw, ch), flags=cv2.INTER_LINEAR, borderValue=bg)
    )

    # Uneven lighting: a tilted plane times a soft vignette.
    gx, gy = np.meshgrid(np.linspace(-1, 1, cw), np.linspace(-1, 1, ch))
    tilt = 1.0 + float(rng.uniform(-0.15, 0.15)) * gx + float(rng.uniform(-0.15, 0.15)) * gy
    vignette = 1.0 - float(rng.uniform(0.1, 0.25)) * (gx**2 + gy**2) / 2
    out = _f32(out * (tilt * vignette))

    out = _f32(cv2.GaussianBlur(out, (0, 0), float(rng.uniform(0.8, 1.6))))
    out = _noise(out, rng, float(rng.uniform(0.02, 0.04)))
    out = np.clip(out, 0.0, 1.0).astype(np.float32)
    return _jpeg(out, int(rng.integers(60, 81)))
