"""The engine task: a book with a page Audiveris cannot read is read without it."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from lilyscan.engine.audiveris.runner import (
    AudiverisRun,
    failed_sheets,
    invalid_sheets,
)


def test_invalid_sheets_and_ranges() -> None:
    log = (
        "INFO [pages#6] SheetStub.java:1194 | Sheet pages#6 flagged as invalid.\n"
        "WARN [pages#6] Book.java:2044 | No regularly spaced lines found\n"
        "INFO [pages#2] SheetStub.java:1194 | Sheet pages#2 flagged as invalid.\n"
    )
    assert invalid_sheets(log) == [2, 6]
    crashed = (
        "WARN [pages#3] Book.java:2044 | Error processing stub java.lang.NullPointerException\n"
        "Caused by: java.lang.NullPointerException: ... Measure.purgeVoices() ...\n"
    )
    assert failed_sheets(log + crashed) == [2, 3, 6]
    # The same on the console, which the runner captures: no ".java:" there.
    console = "WARN  [pages#4]                      Book 2044 | Error processing stub java.lang.X\n"
    assert failed_sheets(console) == [4]


def test_a_book_is_read_again_without_the_sheet_audiveris_rejected(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    cv2 = pytest.importorskip("cv2")
    import numpy as np

    from lilyscan_app import tasks
    from lilyscan_app.jobs import JobStore, job_dir

    monkeypatch.setenv("LILYSCAN_DATA_DIR", str(tmp_path))
    store = JobStore(tmp_path)
    job = store.create(["00-parts.pdf"], {"prepare": True})
    root = job_dir(tmp_path, job.id)
    (root / "prepared").mkdir(parents=True)
    # Page n is filled with the grey level 10 n.
    book = [np.full((8, 8), 10 * n, np.uint8) for n in range(1, 9)]
    assert cv2.imwritemulti(str(root / "prepared" / "pages.tif"), book)
    pages = [{"input": "00-parts.pdf", "page": n, "kind": "vector"} for n in range(8)]
    (root / "prepared" / "report.json").write_text(json.dumps(pages), encoding="utf-8")

    calls: list[tuple[Path, list[int], str | None]] = []

    def fake_run(inputs: list[Path], out_dir: Path, **kw: Any) -> AudiverisRun:
        _, read = cv2.imreadmulti(str(inputs[0]), flags=cv2.IMREAD_GRAYSCALE)
        calls.append((inputs[0], [int(p[0, 0]) // 10 for p in read], kw.get("sheets")))
        out_dir.mkdir(parents=True, exist_ok=True)
        if len(calls) == 1:
            log = "Sheet pages#6 flagged as invalid.\nError in reaching step PAGE\n"
            return AudiverisRun([], 1, 1.0, log)
        mxl = out_dir / "pages.mvt1.mxl"
        mxl.write_bytes(b"PK")
        return AudiverisRun([], 0, 1.0, "", mxl_files=[mxl])

    monkeypatch.setattr(tasks, "run_audiveris", fake_run)
    summary = tasks.engine_transcribe(job.id)

    # Read again from a copy without page 6, not with -sheets (which makes Audiveris
    # export every movement to one file).
    assert calls[0] == (root / "prepared" / "pages.tif", [1, 2, 3, 4, 5, 6, 7, 8], None)
    assert calls[1] == (root / "engine" / "kept" / "pages.tif", [1, 2, 3, 4, 5, 7, 8], None)
    assert summary["skipped_sheets"] == [6]
    assert summary["mxl_files"] == ["engine/pages.mvt1.mxl"]
    # The sheets no longer match the PDF's pages: no vector repairs.
    assert tasks._born_digital_pdf(root, summary) is None


def test_a_pdf_without_some_pages(tmp_path: Path) -> None:
    pymupdf = pytest.importorskip("pymupdf")
    from lilyscan.ingest.book import without_pages

    src = tmp_path / "parts.pdf"
    with pymupdf.open() as doc:
        for n in range(1, 5):
            doc.new_page().insert_text((72, 72), f"page {n}")
        doc.save(src)
    kept = without_pages(src, [1, 3, 4], tmp_path / "kept")
    assert kept == tmp_path / "kept" / "parts.pdf"
    with pymupdf.open(kept) as doc:
        assert [page.get_text().strip() for page in doc] == ["page 1", "page 3", "page 4"]
