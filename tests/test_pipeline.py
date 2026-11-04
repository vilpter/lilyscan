from __future__ import annotations

import json
from pathlib import Path

import pytest

from lilyscan.pipeline import convert_file
from lilyscan.runtime.config import AUDIVERIS_VERSION

FIXTURE = Path(__file__).parent / "fixtures" / "features.musicxml"
OMR_FIXTURE = Path(__file__).parents[1] / "eval" / "fixtures" / "omr" / AUDIVERIS_VERSION
pytestmark = pytest.mark.lilypond


def test_convert_file_writes_ir_project_and_report(tmp_path: Path) -> None:
    report = convert_file(FIXTURE, tmp_path)
    assert report["qa"]["passed"], report["qa"]
    # Voice 11 notes (incl. one grace); piano upper 8, lower 5.
    assert report["counts"] == {"parts": 2, "staves": 3, "measures": 4, "notes": 24}
    assert (tmp_path / "ir" / "score.json").is_file()
    assert (tmp_path / "ly" / "main.ly").is_file()
    assert (
        json.loads((tmp_path / "report.json").read_text(encoding="utf-8"))["ir"] == "ir/score.json"
    )
