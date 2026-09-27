"""Stages 3 and 6-8 for one job: engine output -> IR -> LilyPond project -> QA report."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from lilyscan.engine.audiveris.omr import OmrError, attach_geometry, read_omr
from lilyscan.ir.models import Score
from lilyscan.ir.musicxml import load_musicxml
from lilyscan.ir.ops import counts, merge_scores
from lilyscan.lilypond.generate import LOW_CONFIDENCE, write_project
from lilyscan.qa.checks import run_checks
from lilyscan.runtime.config import Settings
from lilyscan.runtime.device import get_device


def import_musicxml_files(paths: list[Path], source: str) -> Score:
    if not paths:
        raise ValueError("no MusicXML files to import")
    return merge_scores([load_musicxml(p, source) for p in paths])


def import_engine_output(root: Path, engine: dict[str, Any]) -> tuple[Score, dict[str, Any] | None]:
    """Stage 3: the engine's MusicXML as IR, with ``.omr`` boxes and confidence attached.

    Returns the score and a geometry report (None when the run saved no ``.omr``).
    Overlays are written to ``root/overlays`` when the ``vision`` extra is installed.
    """
    score = import_musicxml_files([root / p for p in engine["mxl_files"]], "audiveris")
    omr_files = engine.get("omr_files") or []
    if not omr_files:
        return score, None
    omr_path = root / omr_files[0]
    try:
        book = read_omr(omr_path)
    except OmrError as exc:
        return score, {"source": omr_files[0], "error": str(exc)}
    stats = attach_geometry(score, book)
    low = sum(
        1
        for _, s in score.staves()
        for m in s.measures
        for v in m.voices
        for e in v.events
        if e.confidence is not None and e.confidence < LOW_CONFIDENCE
    )
    geometry: dict[str, Any] = {
        "source": omr_files[0],
        "audiveris": book.software_version,
        "pages": [
            {"sheet": s.number, "width": s.width, "height": s.height, "interline": s.interline}
            for s in book.sheets
        ],
        "events": stats.events,
        "mappable_events": stats.mappable,
        "located_events": stats.located,
        "located_rate": round(stats.located_rate, 4),
        "pitch_mismatches": stats.pitch_mismatch,
        "measures": stats.measures,
        "located_measures": stats.measures_located,
        "unmapped_staves": stats.unmapped_staves,
        "low_confidence_events": low,
        "overlays": [],
    }
    try:
        from lilyscan.overlay import render_overlays
    except ImportError:  # vision extra not installed
        return score, geometry
    written = render_overlays(score, book, omr_path, root / "overlays")
    geometry["overlays"] = [p.relative_to(root).as_posix() for p in written]
    return score, geometry


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
