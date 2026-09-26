"""End-to-end against the real Audiveris binary (set AUDIVERIS_BIN and TESSDATA_PREFIX)."""

from __future__ import annotations

from pathlib import Path

import pytest

from lilyscan.engine.audiveris.runner import audiveris_version, run_audiveris
from lilyscan.lilypond.compile import HELLO_WORLD, compile_ly
from lilyscan.runtime.config import AUDIVERIS_VERSION

pytestmark = [pytest.mark.engine, pytest.mark.lilypond]


def test_installed_version_matches_pin() -> None:
    assert audiveris_version() == AUDIVERIS_VERSION


def test_transcribes_engraved_page_with_working_ocr(tmp_path: Path) -> None:
    src = tmp_path / "hello.ly"
    src.write_text(HELLO_WORLD, encoding="utf-8")
    engraved = compile_ly(src, tmp_path / "engraved", ("pdf",))
    assert engraved.ok, engraved.log

    run = run_audiveris(engraved.outputs, tmp_path / "engine")

    assert run.ok, run.log[-3000:]
    assert [p.suffix for p in run.omr_files] == [".omr"]
    assert [p.suffix for p in run.mxl_files] == [".mxl"]
    assert run.ocr_problems == []
