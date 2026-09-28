"""Stage 0: all of a job's pages, in order, as one book for the engine.

Audiveris makes one book per input file, and a job's page geometry comes from one
book, so every page (each image, each TIFF page, each PDF page) goes to the engine in
one multi-page TIFF:

- ``pages.tif``: the pages as prepared. Born-digital PDF pages are rendered at 400 DPI;
  scanned PDF pages are rendered at their image's own resolution and, like photos and
  image scans, go through Stage 1.
- ``uploaded.tif``: the same pages as uploaded (born-digital pages rendered, the rest
  untouched but for the quarter turns that stand them upright), written only when some
  page is a scan, so the engine can also read it and the better run be kept.

Needs the ``vision`` extra, and the ``vector`` extra for PDFs.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
from numpy.typing import NDArray

from lilyscan.runtime.config import PDF_RENDER_DPI

U8 = NDArray[np.uint8]
IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tif", ".tiff"}


@dataclass
class Book:
    pages: list[dict[str, Any]] = field(default_factory=list)  # one report per page
    prepared: Path | None = None
    uploaded: Path | None = None  # only when some page is a scan

    @property
    def scan_like(self) -> bool:
        return any(p.get("page_found") is False for p in self.pages)


def _gray(img: Any) -> U8:
    import cv2

    arr = np.asarray(img)
    if arr.ndim == 3:
        arr = cv2.cvtColor(arr, cv2.COLOR_BGR2GRAY)
    return np.asarray(arr, dtype=np.uint8)


def _image_pages(path: Path) -> list[U8]:
    import cv2

    if path.suffix.lower() in (".tif", ".tiff"):
        ok, pages = cv2.imreadmulti(str(path), flags=cv2.IMREAD_GRAYSCALE)
        if not ok or not pages:
            raise ValueError(f"cannot read image {path.name}")
        return [_gray(p) for p in pages]
    img = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if img is None:
        raise ValueError(f"cannot read image {path.name}")
    return [_gray(img)]


def _pixmap(page: Any, dpi: int) -> U8:
    import pymupdf

    pix = page.get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY, alpha=False)
    return (
        np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.stride)[:, : pix.width].copy()
    )


def _native_dpi(page: Any) -> int:
    """The resolution of the page's largest image (a scanned page), 150 to 600 DPI."""
    best = 0.0
    for info in page.get_image_info():
        x0, _, x1, _ = info["bbox"]
        if x1 > x0 and info.get("width"):
            best = max(best, info["width"] / ((x1 - x0) / 72.0))
    return round(max(150.0, min(best or 300.0, 600.0)))


def _pdf_pages(path: Path) -> list[tuple[str, U8]]:
    """(kind, pixels) per page: born-digital pages at 400 DPI, scans at their own."""
    import pymupdf

    from lilyscan.ingest.pdf import _page_kind

    out: list[tuple[str, U8]] = []
    with pymupdf.open(path) as doc:
        for page in doc:
            kind = _page_kind(page)
            if kind == "empty":
                continue
            dpi = _native_dpi(page) if kind == "raster" else PDF_RENDER_DPI
            out.append((kind, _pixmap(page, dpi)))
    return out


def assemble(inputs: list[Path], out_dir: Path) -> Book:
    """Prepare every page of ``inputs`` (in order) into ``out_dir``; see the module doc."""
    import cv2

    from lilyscan.prepare import prepare_page

    book = Book()
    prepared: list[U8] = []
    uploaded: list[U8] = []
    for path in inputs:
        suffix = path.suffix.lower()
        try:
            if suffix == ".pdf":
                pages = _pdf_pages(path)
            elif suffix in IMAGE_SUFFIXES:
                pages = [("image", p) for p in _image_pages(path)]
            else:
                raise ValueError(f"unsupported input {path.name}")
        except Exception as exc:
            book.pages.append({"input": path.name, "error": str(exc), "passed": False})
            continue
        for number, (kind, pixels) in enumerate(pages):
            entry: dict[str, Any] = {"input": path.name, "page": number, "kind": kind}
            if kind == "vector":
                entry.update(born_digital=True, rendered_dpi=PDF_RENDER_DPI, passed=True)
                prepared.append(pixels)
                uploaded.append(pixels)
            else:
                gray, report = prepare_page(
                    pixels.astype(np.float32) / np.float32(255.0), path.name
                )
                prepared.append(np.clip(gray * 255 + 0.5, 0, 255).astype(np.uint8))
                # Audiveris cannot read a page lying on its side, as uploaded or not.
                turns = round(report.rotated / 90) % 4
                uploaded.append(np.ascontiguousarray(np.rot90(pixels, turns)))
                entry.update(report.to_dict())
            book.pages.append(entry)
    if not prepared:
        return book
    out_dir.mkdir(parents=True, exist_ok=True)
    book.prepared = out_dir / "pages.tif"
    if not cv2.imwritemulti(str(book.prepared), prepared):
        raise OSError(f"cannot write {book.prepared}")
    if book.scan_like:
        book.uploaded = out_dir / "uploaded.tif"
        if not cv2.imwritemulti(str(book.uploaded), uploaded):
            raise OSError(f"cannot write {book.uploaded}")
    return book


def without_pages(path: Path, keep: list[int], out_dir: Path) -> Path:
    """A copy of the multi-page TIFF or PDF ``path`` holding only the pages ``keep``
    (numbered from 1), under the same name in ``out_dir``."""
    out_dir.mkdir(parents=True, exist_ok=True)
    dst = out_dir / path.name
    if path.suffix.lower() == ".pdf":
        import pymupdf

        with pymupdf.open(path) as doc:
            doc.select([n - 1 for n in keep])
            doc.save(dst)
        return dst
    import cv2

    pages = _image_pages(path)
    kept = [pages[n - 1] for n in keep if 0 < n <= len(pages)]
    if not kept or not cv2.imwritemulti(str(dst), kept):
        raise OSError(f"cannot write {dst}")
    return dst
