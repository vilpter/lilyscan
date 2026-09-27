"""Stage 0 for PDFs: tell born-digital pages from scanned ones, render born-digital pages.

Audiveris renders a PDF itself (PDFBox, 300 DPI). Rendering born-digital pages with
MuPDF at 400 DPI gave it clearly better input (see ``PDF_RENDER_DPI``). The pages go to
the engine as one multi-page TIFF, so a multi-page PDF stays one book.

Needs the ``vector`` extra (PyMuPDF) and the ``vision`` extra (OpenCV, for the TIFF).
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Literal

from lilyscan.runtime.config import PDF_RASTER_COVER, PDF_RENDER_DPI

Kind = Literal["vector", "raster", "empty"]


@dataclass
class PdfReport:
    source: str
    pages: int
    kinds: list[str]  # per page: "vector", "raster" (a scanned image) or "empty"
    rendered_dpi: int | None = None  # set when the pages were rendered for the engine

    @property
    def born_digital(self) -> bool:
        return (
            bool(self.kinds) and all(k != "raster" for k in self.kinds) and "vector" in self.kinds
        )

    def to_dict(self) -> dict[str, Any]:
        return {**asdict(self), "born_digital": self.born_digital, "passed": True}


def _page_kind(page: Any) -> Kind:
    area = abs(page.rect)
    for info in page.get_image_info():
        x0, y0, x1, y1 = info["bbox"]
        if area and (x1 - x0) * (y1 - y0) / area >= PDF_RASTER_COVER:
            return "raster"
    if page.get_drawings() or page.get_text("text").strip():
        return "vector"
    return "empty"


def inspect_pdf(path: Path) -> PdfReport:
    import pymupdf

    with pymupdf.open(path) as doc:
        kinds: list[str] = [_page_kind(page) for page in doc]
    return PdfReport(source=path.name, pages=len(kinds), kinds=kinds)


def render_pdf(path: Path, dst: Path, dpi: int = PDF_RENDER_DPI) -> PdfReport:
    """Render every page, grayscale, into the multi-page TIFF ``dst``."""
    import cv2
    import numpy as np
    import pymupdf

    report = inspect_pdf(path)
    pages = []
    with pymupdf.open(path) as doc:
        for page in doc:
            pix = page.get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY, alpha=False)
            pages.append(
                np.frombuffer(pix.samples, np.uint8)
                .reshape(pix.height, pix.stride)[:, : pix.width]
                .copy()
            )
    dst.parent.mkdir(parents=True, exist_ok=True)
    if not pages or not cv2.imwritemulti(str(dst), pages):
        raise OSError(f"cannot write {dst}")
    report.rendered_dpi = dpi
    return report
