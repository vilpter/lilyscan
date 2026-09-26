from __future__ import annotations

from pathlib import Path

import pytest

from lilyscan.engine.audiveris.runner import build_command, ocr_problems


def test_ocr_problems_detects_missing_and_fast_models() -> None:
    log = (
        "INFO  StepMonitoring | TEXTS\n"
        "WARN [x] TesseractOCR.java:335 | The collection of supported languages is empty\n"
        "Error: Tesseract (legacy) engine requested, but components are not present in /t!!\n"
        "INFO  StepMonitoring | MEASURES\n"
    )
    found = ocr_problems(log)
    assert len(found) == 2
    assert ocr_problems("INFO  StepMonitoring | TEXTS\n") == []


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
