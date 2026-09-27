"""Debug overlays: IR boxes drawn on the page images the engine analysed.

Measures get a thin grey outline; events are boxed by confidence: green (>= 0.8),
amber (0.5-0.8), red (< 0.5), grey when unknown. Needs the ``vision`` extra;
callers import this module only when it is installed.
"""

from __future__ import annotations

import zipfile
from pathlib import Path

import cv2
import numpy as np
from numpy.typing import NDArray

from lilyscan.engine.audiveris.omr import OmrBook
from lilyscan.ir.models import BBox, Score

Colour = tuple[int, int, int]  # BGR
HIGH: Colour = (40, 160, 40)
MID: Colour = (0, 165, 255)
LOW: Colour = (30, 30, 220)
UNKNOWN: Colour = (150, 150, 150)
MEASURE: Colour = (200, 200, 200)

HIGH_CONFIDENCE = 0.8
LOW_CONFIDENCE = 0.5


def confidence_colour(confidence: float | None) -> Colour:
    if confidence is None:
        return UNKNOWN
    if confidence >= HIGH_CONFIDENCE:
        return HIGH
    if confidence >= LOW_CONFIDENCE:
        return MID
    return LOW


def _rect(canvas: NDArray[np.uint8], box: BBox, colour: Colour, thickness: int) -> None:
    pad = 2
    top_left = (int(box.x) - pad, int(box.y) - pad)
    bottom_right = (int(box.x + box.w) + pad, int(box.y + box.h) + pad)
    cv2.rectangle(canvas, top_left, bottom_right, colour, thickness)


def _legend(canvas: NDArray[np.uint8]) -> None:
    # Sized relative to the page so it stays readable on 300-400 DPI scans.
    s = max(1.0, canvas.shape[1] / 1000)
    entries = [(">= 0.8", HIGH), ("0.5 - 0.8", MID), ("< 0.5", LOW), ("unknown", UNKNOWN)]
    for k, (label, colour) in enumerate(entries):
        x, y = int(20 * s), int((30 + 28 * k) * s)
        box = int(18 * s)
        cv2.rectangle(canvas, (x, y - box + int(4 * s)), (x + box, y + int(4 * s)), colour, -1)
        cv2.putText(
            canvas,
            label,
            (x + box + int(10 * s), y),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.6 * s,
            (60, 60, 60),
            max(1, int(s)),
            cv2.LINE_AA,
        )


def render_overlays(score: Score, book: OmrBook, omr_path: Path, out_dir: Path) -> list[Path]:
    """One ``page-N.png`` per sheet, drawn on the sheet's binarized image from the ``.omr``."""
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    with zipfile.ZipFile(omr_path) as z:
        names = set(z.namelist())
        for page, sheet in enumerate(book.sheets):
            name = f"sheet#{sheet.number}/BINARY.png"
            if name not in names:
                continue
            gray = cv2.imdecode(np.frombuffer(z.read(name), np.uint8), cv2.IMREAD_GRAYSCALE)
            if gray is None:
                continue
            # Fade the ink so the coloured boxes stand out.
            faded = (255 - (255 - gray.astype(np.float32)) * 0.45).astype(np.uint8)
            canvas: NDArray[np.uint8] = np.asarray(
                cv2.cvtColor(faded, cv2.COLOR_GRAY2BGR), np.uint8
            )
            for _, staff in score.staves():
                for m in staff.measures:
                    if m.bbox is not None and m.bbox.page == page:
                        _rect(canvas, m.bbox, MEASURE, 1)
                    for v in m.voices:
                        for e in v.events:
                            if e.bbox is not None and e.bbox.page == page:
                                _rect(canvas, e.bbox, confidence_colour(e.confidence), 2)
            _legend(canvas)
            path = out_dir / f"page-{page + 1}.png"
            cv2.imwrite(str(path), canvas)
            written.append(path)
    return written
