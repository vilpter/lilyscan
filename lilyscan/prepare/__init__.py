"""Stage 1: turn a photo or scan into the flat, scan-like page Audiveris expects.

1. Find the page in a photo and correct the perspective (a scan fills the frame and
   is left as it is).
2. Flatten uneven lighting.
3. Measure the staff line thickness and interline; rescale when the interline is
   outside the range Audiveris reads well.
4. Straighten curled or skewed staff lines (see ``staff``).
5. Quality gate: warn when the page is too coarse or the lines stay curved.

Needs the ``vision`` extra (OpenCV, numpy).
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from lilyscan.prepare.image import (
    Image,
    binarize,
    f32,
    find_page,
    flatten_light,
    ink_mask,
    level_staves,
    load_gray,
    save_png,
    sharpen,
    upside_down,
    warp_page,
)
from lilyscan.prepare.staff import (
    curvature,
    dewarp,
    displacement_samples,
    fit_field,
    measure_scale,
)
from lilyscan.runtime.config import (
    PREPARE_INTERLINE_RANGE,
    PREPARE_MAX_CURVATURE,
    PREPARE_MIN_INTERLINE,
)

# Staff lines already straighter than this (RMS displacement, interlines) are left alone.
_FLAT_ENOUGH = 0.1


@dataclass
class PrepareReport:
    source: str
    page_found: bool
    input_size: tuple[int, int]
    output_size: tuple[int, int] = (0, 0)
    interline: float | None = None  # pixels, in the output
    rescaled: float = 1.0
    curvature_before: float | None = None  # interlines
    curvature_after: float | None = None
    dewarped: bool = False
    rotated: float = 0.0  # degrees counterclockwise the page was turned to stand upright
    cleaned: str | None = None  # "sharpen" or "binarize" when applied
    warnings: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        """The quality gate: nothing found that makes the page hard to read."""
        return not self.warnings

    def to_dict(self) -> dict[str, Any]:
        return {**asdict(self), "passed": self.passed}


def prepare_image(
    src: Path,
    dst: Path,
    light: bool = True,
    straighten: bool = True,
    clean: str | None = "auto",
) -> PrepareReport:
    """Write the prepared page for ``src`` to ``dst`` (PNG) and report what was done.

    See ``prepare_page`` for the options.
    """
    gray, report = prepare_page(load_gray(src), src.name, light, straighten, clean)
    save_png(gray, dst)
    return report


def prepare_page(
    gray: Image,
    source: str,
    light: bool = True,
    straighten: bool = True,
    clean: str | None = "auto",
) -> tuple[Image, PrepareReport]:
    """Prepare one page (grayscale, 0 = black) and report what was done.

    ``light`` and ``straighten`` switch the light flattening and the dewarping.
    ``clean`` is ``"sharpen"`` (denoise and sharpen), ``"binarize"`` (denoise, then a
    local threshold), None, or ``"auto"``: sharpen photos (pages found in the frame),
    which helped Audiveris on 23 of 30 seed photos and hurt 4, and leave scans as they are.
    """
    h, w = gray.shape
    corners = find_page(gray)
    report = PrepareReport(source=source, page_found=corners is not None, input_size=(w, h))
    if corners is not None:
        gray = warp_page(gray, corners)
    if light:
        gray = flatten_light(gray)
    # Staff lines across the page; whether it is upside down is settled once they are
    # straight.
    gray, angle = level_staves(gray)
    report.rotated = round(angle, 1)
    mask = ink_mask(gray)

    scale = measure_scale(mask)
    if scale is None:
        report.warnings.append("no staff lines found")
        return _finish(report, gray)
    interline = scale.interline
    low, high = PREPARE_INTERLINE_RANGE
    if not low <= interline <= high:
        factor = (low + high) / 2 / interline
        size = (round(gray.shape[1] * factor), round(gray.shape[0] * factor))
        method = cv2.INTER_CUBIC if factor > 1 else cv2.INTER_AREA
        gray = f32(cv2.resize(gray, size, interpolation=method))
        mask = ink_mask(gray)
        interline *= factor
        report.rescaled = round(factor, 4)
    report.interline = round(interline, 2)

    samples = displacement_samples(mask, interline)
    before = curvature(samples) / interline
    report.curvature_before = round(before, 4)
    after = before
    if straighten and before > _FLAT_ENOUGH:
        displacement = fit_field(samples, gray.shape[1], gray.shape[0])
        if displacement is not None:
            gray = dewarp(gray, displacement)
            report.dewarped = True
            after = curvature(displacement_samples(ink_mask(gray), interline)) / interline
    report.curvature_after = round(after, 4)
    if upside_down(gray):
        gray = f32(np.rot90(gray, 2))
        report.rotated = round((report.rotated + 360) % 360 - 180, 1)
    if clean == "auto":
        clean = "sharpen" if report.page_found else None
    report.cleaned = clean
    if clean in ("sharpen", "binarize"):
        gray = sharpen(gray, interline)
        if clean == "binarize":
            gray = binarize(gray, interline)

    if scale.interline < PREPARE_MIN_INTERLINE:
        report.warnings.append(
            f"staff lines only {scale.interline:.0f} px apart; "
            "photograph closer or scan at a higher resolution"
        )
    if after > PREPARE_MAX_CURVATURE:
        report.warnings.append(
            f"staff lines still curved by {after:.2f} staff spaces; flatten the page and reshoot"
        )
    return _finish(report, gray)


def _finish(report: PrepareReport, gray: Image) -> tuple[Image, PrepareReport]:
    report.output_size = (int(gray.shape[1]), int(gray.shape[0]))
    return gray, report
