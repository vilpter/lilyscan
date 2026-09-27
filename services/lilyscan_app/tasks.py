"""Queue tasks. Referenced by dotted name so each worker image imports only what it runs.

Job flow (one RQ job per step, chained with ``depends_on``):
    pipeline queue: prepare_inputs     (Stage 1, pipeline worker)
    engine queue:   engine_transcribe  (Stage 2, Audiveris worker)
    pipeline queue: pipeline_finish    (Stages 3+, pipeline worker)
"""

from __future__ import annotations

import json
import logging
import traceback
from pathlib import Path
from typing import Any

from lilyscan.engine.audiveris.runner import AudiverisRun, run_audiveris
from lilyscan.pipeline import (
    assess_engine_run,
    import_engine_output,
    produce,
    recompile,
    repair_engine_output,
)
from lilyscan.runtime.config import Settings

from .jobs import JobStatus, JobStore, job_dir

log = logging.getLogger(__name__)


def _store(settings: Settings) -> JobStore:
    return JobStore(settings.data_dir)


def _write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, indent=2, sort_keys=True, default=str), encoding="utf-8", newline="\n"
    )


# Inputs prepared before the engine: raster pages (Stage 1) and PDFs (Stage 0). TIFFs,
# possibly multi-page, go to the engine as they are.
PREPARED_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".pdf"}


def _prepared(root: Path, name: str) -> Path:
    """Where Stage 0/1 put its version of an input: a page image, or a born-digital PDF
    rendered to a multi-page TIFF."""
    stem = root / "prepared" / Path(name).stem
    tif = stem.with_suffix(".tif")
    return tif if tif.is_file() else stem.with_suffix(".png")


def _prepare_one(root: Path, name: str) -> dict[str, Any]:
    src = root / "input" / name
    if src.suffix.lower() == ".pdf":
        from lilyscan.ingest.pdf import inspect_pdf, render_pdf  # vector and vision extras

        info = inspect_pdf(src)
        if not info.born_digital:
            return info.to_dict()  # scanned pages: the engine renders them as they are
        target = root / "prepared" / f"{Path(name).stem}.tif"
        return render_pdf(src, target).to_dict()
    from lilyscan.prepare import prepare_image  # needs the vision extra

    return prepare_image(src, _prepared(root, name)).to_dict()


def prepare_inputs(job_id: str) -> dict[str, Any]:
    """Stages 0 and 1: render born-digital PDFs for the engine, and find, straighten and
    clean each photo or scan.

    An input that cannot be prepared goes to the engine as uploaded; the report says why.
    """
    settings = Settings.from_env()
    store = _store(settings)
    try:
        job = store.get(job_id)
        if job is None:
            raise LookupError(f"unknown job {job_id}")
        store.update(job_id, status=JobStatus.RUNNING, stage="prepare")
        root = job_dir(settings.data_dir, job_id)
        pages: list[dict[str, Any]] = []
        if job.options.get("prepare", True):
            for name in job.inputs:
                if Path(name).suffix.lower() not in PREPARED_SUFFIXES:
                    continue
                try:
                    pages.append({"input": name, **_prepare_one(root, name)})
                except Exception as exc:  # the raw page is still worth transcribing
                    log.warning("prepare %s/%s failed: %s", job_id, name, exc)
                    pages.append({"input": name, "error": str(exc), "passed": False})
        _write_json(root / "prepared" / "report.json", pages)
        return {"pages": pages}
    except Exception as exc:
        store.update(job_id, status=JobStatus.FAILED, error=f"prepare: {exc}")
        log.error("prepare_inputs %s failed:\n%s", job_id, traceback.format_exc())
        raise
    finally:
        store.close()


def _scan_like(root: Path) -> bool:
    """Stage 1 prepared a page it found no edges for: a scan, not a photo of a page."""
    report = root / "prepared" / "report.json"
    if not report.is_file():
        return False
    pages = json.loads(report.read_text(encoding="utf-8"))
    return any(p.get("page_found") is False for p in pages)


def engine_transcribe(job_id: str) -> dict[str, Any]:
    """Stage 2: run Audiveris on the job's pages.

    Pages Stage 1 prepared are transcribed in their prepared form. For scans, where the
    engine sometimes does better on the page as uploaded, both forms are transcribed
    and the run Lilyscan expects to have more right (after repair) is kept.
    """
    settings = Settings.from_env()
    store = _store(settings)
    try:
        job = store.get(job_id)
        if job is None:
            raise LookupError(f"unknown job {job_id}")
        store.update(job_id, status=JobStatus.RUNNING, stage="engine")
        root = job_dir(settings.data_dir, job_id)
        uploaded = [root / "input" / name for name in job.inputs]
        prepared = [
            _prepared(root, name) if _prepared(root, name).is_file() else root / "input" / name
            for name in job.inputs
        ]
        candidates = [("prepared" if prepared != uploaded else "uploaded", prepared, "engine")]
        if prepared != uploaded and _scan_like(root):
            candidates.append(("uploaded", uploaded, "engine/uploaded"))
        constants = {str(k): str(v) for k, v in job.options.get("audiveris_constants", {}).items()}
        runs = []
        for label, inputs, out in candidates:
            run = run_audiveris(
                inputs,
                root / out,
                constants=constants,
                settings=settings,
                ocr_languages=job.options.get("ocr_languages"),
            )
            summary = {
                "pages": label,
                "command": run.command,
                "returncode": run.returncode,
                "timed_out": run.timed_out,
                "wall_s": round(run.wall_s, 2),
                "omr_files": [p.relative_to(root).as_posix() for p in run.omr_files],
                "mxl_files": [p.relative_to(root).as_posix() for p in run.mxl_files],
                "ocr_problems": run.ocr_problems,
                "step_errors": run.step_errors,
            }
            runs.append((run, summary))
        run, summary = _choose(root, runs)
        _write_json(root / "engine" / "run.json", summary)
        if run.ocr_problems:
            # A deployment fault: the output would silently lack lyrics and text.
            raise RuntimeError(
                "Audiveris ran without working OCR for the requested languages "
                f"({'; '.join(run.ocr_problems)}); check LILYSCAN_OCR_LANGUAGES and "
                "TESSDATA_PREFIX, and that the models include the legacy engine"
            )
        if not run.ok:
            reason = "; ".join(run.step_errors) or f"exit {run.returncode}"
            if run.timed_out:
                reason = f"timed out after {settings.audiveris_timeout_s:.0f}s"
            raise RuntimeError(
                f"Audiveris produced no MusicXML ({reason}); see engine/audiveris.log"
            )
        return summary
    except Exception as exc:
        store.update(job_id, status=JobStatus.FAILED, error=f"engine: {exc}")
        log.error("engine_transcribe %s failed:\n%s", job_id, traceback.format_exc())
        raise
    finally:
        store.close()


def _choose(
    root: Path, runs: list[tuple[AudiverisRun, dict[str, Any]]]
) -> tuple[AudiverisRun, dict[str, Any]]:
    """The run expected to have the most events right; the others are listed in it."""
    if len(runs) == 1:
        return runs[0]
    scored = []
    for run, summary in runs:
        expected = assess_engine_run(root, summary) if run.ok else -1.0
        scored.append((expected, run, summary))
    best = max(scored, key=lambda s: s[0])
    chosen = dict(best[2])
    chosen["alternatives"] = [
        {"pages": s["pages"], "ok": r.ok, "expected_right": e, "chosen": s is best[2]}
        for e, r, s in scored
    ]
    return best[1], chosen


def _born_digital_pdf(root: Path, engine: dict[str, Any]) -> Path | None:
    """The job's one born-digital PDF, when the kept engine run read its rendered pages."""
    report = root / "prepared" / "report.json"
    # One book only: geometry, and so the oracle's page mapping, come from the first one.
    if not report.is_file() or engine.get("pages") != "prepared" or len(engine["omr_files"]) != 1:
        return None
    pdfs = [
        p["input"]
        for p in json.loads(report.read_text(encoding="utf-8"))
        if p.get("born_digital") and p.get("rendered_dpi")
    ]
    return root / "input" / pdfs[0] if len(pdfs) == 1 else None


def pipeline_finish(job_id: str) -> dict[str, Any]:
    """Stages 3 and 5-8: import the engine's output, repair it, generate LilyPond, run QA.

    QA findings do not fail the job; they are the review list for the user.
    """
    settings = Settings.from_env()
    store = _store(settings)
    try:
        store.update(job_id, status=JobStatus.RUNNING, stage="import")
        root = job_dir(settings.data_dir, job_id)
        engine = json.loads((root / "engine" / "run.json").read_text(encoding="utf-8"))
        score, geometry = import_engine_output(root, engine)
        store.update(job_id, stage="repair")
        repairs = repair_engine_output(score, root, geometry, _born_digital_pdf(root, engine))
        store.update(job_id, stage="lilypond")
        prepared = root / "prepared" / "report.json"
        report = {
            "prepare": json.loads(prepared.read_text(encoding="utf-8"))
            if prepared.is_file()
            else [],
            "engine": engine,
            "geometry": geometry,
            "repairs": repairs,
            **produce(score, root, settings),
        }
        _write_json(root / "report.json", report)
        store.update(job_id, status=JobStatus.DONE, stage="done", report=report)
        return report
    except Exception as exc:
        store.update(job_id, status=JobStatus.FAILED, error=f"pipeline: {exc}")
        log.error("pipeline_finish %s failed:\n%s", job_id, traceback.format_exc())
        raise
    finally:
        store.close()


def recompile_job(job_id: str) -> dict[str, Any]:
    """After the user edits the LilyPond project: recompile, re-render, refresh the review."""
    settings = Settings.from_env()
    store = _store(settings)
    try:
        store.update(job_id, status=JobStatus.RUNNING, stage="recompile")
        report = recompile(job_dir(settings.data_dir, job_id), settings)
        store.update(job_id, status=JobStatus.DONE, stage="done", report=report)
        return report
    except Exception as exc:
        store.update(job_id, status=JobStatus.FAILED, error=f"recompile: {exc}")
        log.error("recompile_job %s failed:\n%s", job_id, traceback.format_exc())
        raise
    finally:
        store.close()
