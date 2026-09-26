"""Stages 3 and 6-8 for one job: engine output -> IR -> LilyPond project -> QA report."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from lilyscan.ir.models import Score
from lilyscan.ir.musicxml import load_musicxml
from lilyscan.ir.ops import counts, merge_scores
from lilyscan.lilypond.generate import write_project
from lilyscan.qa.checks import run_checks
from lilyscan.runtime.config import Settings
from lilyscan.runtime.device import get_device


def import_musicxml_files(paths: list[Path], source: str) -> Score:
    if not paths:
        raise ValueError("no MusicXML files to import")
    return merge_scores([load_musicxml(p, source) for p in paths])


def produce(score: Score, root: Path, settings: Settings | None = None) -> dict[str, Any]:
    """Write ``ir/score.json`` and the ``ly/`` project under ``root``; return the report."""
    ir_path = root / "ir" / "score.json"
    ir_path.parent.mkdir(parents=True, exist_ok=True)
    ir_path.write_text(score.model_dump_json(indent=1), encoding="utf-8")

    ly_root = root / "ly"
    project = write_project(score, ly_root)
    qa = run_checks(score, ly_root, project, settings)
    return {
        "ir": "ir/score.json",
        "lilypond": {
            "project": "ly",
            "files": [f"ly/{name}" for name in project.files],
            "outputs": [f"ly/{name}" for name in qa.outputs],
        },
        "qa": qa.to_dict(),
        "counts": counts(score),
        "device": get_device().describe(),
    }


def convert_file(musicxml: Path, out_dir: Path, settings: Settings | None = None) -> dict[str, Any]:
    """Standalone conversion of a MusicXML file (CLI and tests)."""
    report = produce(import_musicxml_files([musicxml], "musicxml"), out_dir, settings)
    (out_dir / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report
