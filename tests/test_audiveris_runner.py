from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Any

import pytest

from lilyscan.engine.audiveris import runner
from lilyscan.engine.audiveris.runner import build_command, ocr_problems, step_errors
from lilyscan.runtime.config import (
    AUDIVERIS_OCR_LANGUAGES_KEY,
    AUDIVERIS_STEP_TIMEOUT_KEY,
    Settings,
)


def test_ocr_problems_detects_missing_and_fast_models() -> None:
    log = (
        "INFO  StepMonitoring | TEXTS\n"
        "WARN [x] TesseractOCR.java:335 | The collection of supported languages is empty\n"
        "Error: Tesseract (legacy) engine requested, but components are not present in /t!!\n"
        "WARN [x] TesseractOCR 341  | Language 'ita' is not supported\n"
        "WARN [x] OcrUtil 106  | Missing support for 'eng+ita' language(s)\n"
        "INFO  StepMonitoring | MEASURES\n"
    )
    found = ocr_problems(log)
    assert len(found) == 3
    assert "Missing support for 'eng+ita'" in found[2]
    assert ocr_problems("INFO  StepMonitoring | TEXTS\n") == []


def test_step_errors_are_distinct_messages() -> None:
    log = (
        "WARN PeakGraph 305  | No system found\n"
        "Book 2044 | Error processing stub org.audiveris.omr.step.StepException: No system found\n"
        "Caused by: org.audiveris.omr.step.StepException: No system found\n"
    )
    assert step_errors(log) == ["No system found"]


def test_step_crashes_are_reported() -> None:
    log = (
        "WARN  [p] Book 2044 | Error processing stub java.lang.NullPointerException: ...\n"
        "WARN  [p] CLI 956  | Exception occurred java.lang.Exception: Error in reaching step PAGE\n"
        "java.lang.Exception: Error in reaching step PAGE\n"
    )
    assert step_errors(log) == ["Error in reaching step PAGE"]


def test_a_crash_after_the_steps_is_reported() -> None:
    # Audiveris 5.11.0 on some photos: every step passes, then assembling the scores
    # fails and no MusicXML is written.
    log = (
        "WARN  [p] CLI 956  | Exception occurred java.lang.NullPointerException: Cannot invoke"
        ' "org.audiveris.omr.sheet.Sheet.getPages()" because "sheet" is null\n'
        "java.lang.NullPointerException: Cannot invoke ...\n"
        "\tat org.audiveris.omr.score.Score.getPage(Score.java:485)\n"
    )
    assert step_errors(log) == [
        'NullPointerException: Cannot invoke "org.audiveris.omr.sheet.Sheet.getPages()"'
        ' because "sheet" is null'
    ]


class _Captured:
    cmd: list[str]


def _fake_run(captured: _Captured) -> Any:
    def run(cmd: list[str], **_: Any) -> subprocess.CompletedProcess[str]:
        captured.cmd = cmd
        return subprocess.CompletedProcess(cmd, 0, "", "")

    return run


def test_run_passes_configured_ocr_languages(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    captured = _Captured()
    monkeypatch.setattr(runner.subprocess, "run", _fake_run(captured))
    settings = Settings.from_env({"LILYSCAN_OCR_LANGUAGES": "eng+lat+deu+fra"})

    runner.run_audiveris([tmp_path / "a.pdf"], tmp_path / "out", settings=settings)
    assert f"{AUDIVERIS_OCR_LANGUAGES_KEY}=eng+lat+deu+fra" in captured.cmd

    runner.run_audiveris(
        [tmp_path / "a.pdf"], tmp_path / "out", settings=settings, ocr_languages="ENG+ita"
    )
    assert f"{AUDIVERIS_OCR_LANGUAGES_KEY}=eng+ita" in captured.cmd


def test_run_passes_the_step_timeout(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    captured = _Captured()
    monkeypatch.setattr(runner.subprocess, "run", _fake_run(captured))
    settings = Settings.from_env({"AUDIVERIS_STEP_TIMEOUT_S": "600"})
    runner.run_audiveris([tmp_path / "a.pdf"], tmp_path / "out", settings=settings)
    assert f"{AUDIVERIS_STEP_TIMEOUT_KEY}=600" in captured.cmd
    # A caller may still set it explicitly.
    runner.run_audiveris(
        [tmp_path / "a.pdf"],
        tmp_path / "out",
        settings=settings,
        constants={AUDIVERIS_STEP_TIMEOUT_KEY: "30"},
    )
    assert f"{AUDIVERIS_STEP_TIMEOUT_KEY}=30" in captured.cmd


def test_run_rejects_ocr_languages_as_raw_constant(tmp_path: Path) -> None:
    with pytest.raises(ValueError):
        runner.run_audiveris(
            [tmp_path / "a.pdf"], tmp_path / "out", constants={AUDIVERIS_OCR_LANGUAGES_KEY: "eng"}
        )


def test_build_command_orders_options_before_inputs() -> None:
    cmd = build_command(
        "audiveris",
        [Path("a.pdf"), Path("b.png")],
        Path("out"),
        constants={"z.Key": "1", "a.Key": "2"},
        sheets="1 3-4",
    )
    assert cmd[:7] == ["audiveris", "-batch", "-transcribe", "-export", "-save", "-output", "out"]
    assert cmd[7:11] == ["-constant", "a.Key=2", "-constant", "z.Key=1"]
    assert cmd[11:13] == ["-sheets", "1 3-4"]
    assert cmd[13:] == ["--", "a.pdf", "b.png"]


def test_build_command_rejects_injection_in_keys() -> None:
    with pytest.raises(ValueError):
        build_command("audiveris", [Path("a.pdf")], Path("o"), constants={"-print x": "1"})


def test_build_command_requires_inputs() -> None:
    with pytest.raises(ValueError):
        build_command("audiveris", [], Path("o"))


def test_engine_timeout_grows_with_pages() -> None:
    from lilyscan.engine.audiveris.runner import engine_timeout
    from lilyscan.runtime.config import Settings

    s = Settings.from_env({"AUDIVERIS_TIMEOUT_S": "900", "AUDIVERIS_TIMEOUT_PER_PAGE_S": "240"})
    assert engine_timeout(s, None) == 900
    assert engine_timeout(s, 2) == 900  # short books get the minimum
    assert engine_timeout(s, 20) == 4800
