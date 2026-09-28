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
    sheet_ranges,
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
    assert sheet_ranges([1, 3, 4, 5, 7, 8]) == "1,3-5,7-8"
    assert sheet_ranges([4]) == "4"


def test_a_book_is_read_again_without_the_sheet_audiveris_rejected(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from lilyscan_app import tasks
    from lilyscan_app.jobs import JobStore, job_dir

    monkeypatch.setenv("LILYSCAN_DATA_DIR", str(tmp_path))
    store = JobStore(tmp_path)
    job = store.create(["00-parts.pdf"], {"prepare": True})
    root = job_dir(tmp_path, job.id)
    (root / "prepared").mkdir(parents=True)
    (root / "prepared" / "pages.tif").write_bytes(b"II*\x00")
    pages = [{"input": "00-parts.pdf", "page": n, "kind": "vector"} for n in range(8)]
    (root / "prepared" / "report.json").write_text(json.dumps(pages), encoding="utf-8")

    calls: list[str | None] = []

    def fake_run(inputs: list[Path], out_dir: Path, **kw: Any) -> AudiverisRun:
        calls.append(kw.get("sheets"))
        out_dir.mkdir(parents=True, exist_ok=True)
        if kw.get("sheets") is None:
            log = "Sheet pages#6 flagged as invalid.\nError in reaching step PAGE\n"
            return AudiverisRun([], 1, 1.0, log)
        mxl = out_dir / "pages.mxl"
        mxl.write_bytes(b"PK")
        return AudiverisRun([], 0, 1.0, "", mxl_files=[mxl])

    monkeypatch.setattr(tasks, "run_audiveris", fake_run)
    summary = tasks.engine_transcribe(job.id)

    assert calls == [None, "1-5,7-8"]
    assert summary["skipped_sheets"] == [6]
    assert summary["mxl_files"] == ["engine/pages.mxl"]
