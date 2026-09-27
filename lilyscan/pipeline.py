"""Stages 3 and 5-8 for one job: engine output -> IR -> repairs -> LilyPond -> QA report."""

from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any

from lilyscan.engine.audiveris.omr import OmrError, attach_geometry, read_omr
from lilyscan.ir.models import Score
from lilyscan.ir.musicxml import load_musicxml
from lilyscan.ir.ops import counts, merge_scores
from lilyscan.lilypond.compile import compile_ly
from lilyscan.lilypond.generate import LOW_CONFIDENCE, LyProject, scan_measure_lines, write_project
from lilyscan.qa.checks import CheckResult, QaReport, compile_checks, run_checks
from lilyscan.repair import apply_repairs
from lilyscan.repair.confidence import calibrate_confidence
from lilyscan.review import build_review
from lilyscan.runtime.config import Settings
from lilyscan.runtime.device import get_device


def import_musicxml_files(paths: list[Path], source: str) -> Score:
    if not paths:
        raise ValueError("no MusicXML files to import")
    return merge_scores([load_musicxml(p, source) for p in paths])


def import_engine_output(root: Path, engine: dict[str, Any]) -> tuple[Score, dict[str, Any] | None]:
    """Stage 3: the engine's MusicXML as IR, with ``.omr`` boxes and confidence attached.

    Returns the score and a geometry report (None when the run saved no ``.omr``).
    Page overlays are drawn later, by ``repair_engine_output``.
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
        "confidence": "audiveris",
        "low_confidence_events": _low_confidence(score),
        "overlays": [],
    }
    return score, geometry


def _low_confidence(score: Score) -> int:
    return sum(
        1
        for _, s in score.staves()
        for m in s.measures
        for v in m.voices
        for e in v.events
        if e.confidence is not None and e.confidence < LOW_CONFIDENCE
    )


def repair_engine_output(
    score: Score, root: Path, geometry: dict[str, Any] | None
) -> list[dict[str, Any]]:
    """Stage 5: the repair rules, then Lilyscan's calibrated confidence; returns the
    repair log. Then draws the page overlays (``root/overlays``, needs the ``vision``
    extra), so their colours show the final confidence.
    """
    repairs = [r.to_dict() for r in apply_repairs(score)]
    calibrated = calibrate_confidence(score)
    if geometry is None or "error" in geometry:
        return repairs
    geometry["confidence"] = "lilyscan" if calibrated else "audiveris"
    geometry["low_confidence_events"] = _low_confidence(score)
    try:
        from lilyscan.overlay import render_overlays
    except ImportError:  # vision extra not installed
        return repairs
    omr_path = root / geometry["source"]
    written = render_overlays(score, read_omr(omr_path), omr_path, root / "overlays")
    geometry["overlays"] = [p.relative_to(root).as_posix() for p in written]
    return repairs


def produce(score: Score, root: Path, settings: Settings | None = None) -> dict[str, Any]:
    """Write ``ir/score.json`` and the ``ly/`` project under ``root``; return the report."""
    ir_path = root / "ir" / "score.json"
    ir_path.parent.mkdir(parents=True, exist_ok=True)
    ir_path.write_text(score.model_dump_json(indent=1), encoding="utf-8", newline="\n")

    ly_root = root / "ly"
    project = write_project(score, ly_root)
    (ly_root / SOURCE_MAP).write_text(
        json.dumps({"staff_vars": project.staff_vars}, indent=1), encoding="utf-8", newline="\n"
    )
    qa = run_checks(score, ly_root, project, settings)
    return {
        "ir": "ir/score.json",
        **_render(root, score, project, qa, settings),
        "counts": counts(score),
        "device": get_device().describe(),
    }


# Maps music variables to (part, staff) so edited files can be re-mapped to measures.
SOURCE_MAP = "lilyscan-map.json"


def _render(
    root: Path, score: Score, project: LyProject, qa: QaReport, settings: Settings | None
) -> dict[str, Any]:
    """Point-and-click SVG pages and the review model; the report's LilyPond/QA sections."""
    ly_root = root / "ly"
    svg_dir = ly_root / "svg"
    if svg_dir.exists():
        shutil.rmtree(svg_dir)
    svg = compile_ly(
        ly_root / "main.ly", svg_dir, ("svg",), point_and_click=True, settings=settings
    )
    review = build_review(score, project, qa)
    (root / "review.json").write_text(json.dumps(review), encoding="utf-8", newline="\n")
    return {
        "lilypond": {
            "project": "ly",
            "files": [f"ly/{name}" for name in sorted(project.files)],
            "outputs": [f"ly/{name}" for name in qa.outputs],
            "svg": [f"ly/svg/{p.name}" for p in sorted(svg.outputs, key=_page_order)],
        },
        "qa": qa.to_dict(),
        "review": "review.json",
    }


def _page_order(path: Path) -> tuple[int, str]:
    tail = path.stem.rsplit("-", 1)
    return (int(tail[1]) if len(tail) == 2 and tail[1].isdigit() else 0, path.name)


def recompile(root: Path, settings: Settings | None = None) -> dict[str, Any]:
    """After the user edits ``ly/``: recompile (Q1, Q2), re-render, refresh the review.

    Q3-Q5 describe the recognized music (the IR), which editing the LilyPond source
    does not change, so their last results are kept.
    """
    ly_root = root / "ly"
    score = Score.model_validate_json((root / "ir" / "score.json").read_text(encoding="utf-8"))
    staff_vars = {
        name: (part, staff)
        for name, (part, staff) in json.loads((ly_root / SOURCE_MAP).read_text(encoding="utf-8"))[
            "staff_vars"
        ].items()
    }
    files = {
        p.relative_to(ly_root).as_posix(): p.read_text(encoding="utf-8")
        for p in sorted(ly_root.rglob("*.ly"))
        if "svg" not in p.relative_to(ly_root).parts
    }
    project = LyProject(files, scan_measure_lines(files, staff_vars), staff_vars)
    q1, q2, compiled = compile_checks(ly_root, project, settings)
    report_path = root / "report.json"
    report: dict[str, Any] = json.loads(report_path.read_text(encoding="utf-8"))
    kept = [CheckResult(**c) for c in report["qa"]["checks"] if c["id"] not in ("Q1", "Q2")]
    outputs = [p.relative_to(ly_root.resolve()).as_posix() for p in compiled.outputs]
    outputs += [name for name in ("main.midi", "main.mid") if (ly_root / name).is_file()]
    qa = QaReport(checks=sorted([q1, q2, *kept], key=lambda c: c.id), outputs=outputs)
    report.update(_render(root, score, project, qa, settings))
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8", newline="\n")
    return report


def convert_file(musicxml: Path, out_dir: Path, settings: Settings | None = None) -> dict[str, Any]:
    """Standalone conversion of a MusicXML file (CLI and tests)."""
    report = produce(import_musicxml_files([musicxml], "musicxml"), out_dir, settings)
    (out_dir / "report.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8", newline="\n"
    )
    return report
