"""End-to-end against the real Audiveris binary (set AUDIVERIS_BIN and TESSDATA_PREFIX)."""

from __future__ import annotations

from pathlib import Path

import pytest

from lilyscan.engine.audiveris.runner import audiveris_version, run_audiveris
from lilyscan.lilypond.compile import HELLO_WORLD, compile_ly
from lilyscan.runtime.config import AUDIVERIS_STEP_TIMEOUT_KEY, AUDIVERIS_VERSION

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


def test_ocr_language_constant_is_honoured(tmp_path: Path) -> None:
    """Audiveris ignores unknown -constant keys silently, so prove this one is read:
    asking for a language that is not installed must produce a complaint."""
    src = tmp_path / "hello.ly"
    src.write_text(HELLO_WORLD, encoding="utf-8")
    engraved = compile_ly(src, tmp_path / "engraved", ("pdf",))

    run = run_audiveris(engraved.outputs, tmp_path / "engine", ocr_languages="eng+zzz")

    assert any("zzz" in p for p in run.ocr_problems), run.log[-3000:]


def test_step_timeout_constant_is_honoured(tmp_path: Path) -> None:
    """A one-second limit per step must stop Audiveris on an ordinary page, with its own
    timeout message: proof that the key is read."""
    src = tmp_path / "hello.ly"
    src.write_text(HELLO_WORLD, encoding="utf-8")
    engraved = compile_ly(src, tmp_path / "engraved", ("pdf",))

    run = run_audiveris(
        engraved.outputs, tmp_path / "engine", constants={AUDIVERIS_STEP_TIMEOUT_KEY: "1"}
    )

    assert "Timeout 1 seconds for step" in run.log, run.log[-3000:]
