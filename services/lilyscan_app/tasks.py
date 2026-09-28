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

from lilyscan.engine.audiveris.runner import (
    AudiverisRun,
    engine_timeout,
    run_audiveris,
    sheet_ranges,
)
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


def prepare_inputs(job_id: str) -> dict[str, Any]:
    """Stages 0 and 1: every page of the job, in order, as one book for the engine
    (``prepared/pages.tif``): born-digital PDF pages rendered, photos and scans (images,
    or pages of a scanned PDF) found, straightened and cleaned. When some page is a scan,
    ``prepared/uploaded.tif`` holds the pages as uploaded too.

    A page that cannot be prepared is left out and the report says why; when nothing
    could be prepared, the engine reads the uploads as they are.
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
            from lilyscan.ingest.book import assemble  # vision and vector extras

            book = assemble([root / "input" / name for name in job.inputs], root / "prepared")
            pages = book.pages
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
        pages, as_uploaded = root / "prepared" / "pages.tif", root / "prepared" / "uploaded.tif"
        if pages.is_file():
            candidates = [("prepared", [pages], "engine")]
            if as_uploaded.is_file() and _scan_like(root):
                candidates.append(("uploaded", [as_uploaded], "engine/uploaded"))
        else:
            candidates = [("uploaded", [root / "input" / name for name in job.inputs], "engine")]
        constants = {str(k): str(v) for k, v in job.options.get("audiveris_constants", {}).items()}
        report = root / "prepared" / "report.json"
        page_count = (
            len(json.loads(report.read_text(encoding="utf-8"))) if report.is_file() else None
        ) or len(job.inputs)

        def transcribe(inputs: list[Path], out: str, sheets: str | None = None) -> AudiverisRun:
            return run_audiveris(
                inputs,
                root / out,
                constants=constants,
                settings=settings,
                ocr_languages=job.options.get("ocr_languages"),
                pages=page_count,
                sheets=sheets,
            )

        runs = []
        for label, inputs, out in candidates:
            run = transcribe(inputs, out)
            skipped: list[int] = []
            # One page Audiveris cannot read (no staves found) or fails on fails the whole
            # book: read the others without it.
            if not run.ok and not run.timed_out and len(inputs) == 1 and run.failed_sheets:
                keep = [n for n in range(1, page_count + 1) if n not in run.failed_sheets]
                if keep:
                    skipped = run.failed_sheets
                    run = transcribe(inputs, out, sheet_ranges(keep))
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
                "skipped_sheets": skipped,
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
                reason = f"timed out after {engine_timeout(settings, page_count):.0f}s"
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
    """The job's PDF, when it is the only input, every page of it is born-digital, and the
    kept engine run read those pages as rendered (so sheet n is the PDF's page n)."""
    report = root / "prepared" / "report.json"
    if not report.is_file() or engine.get("pages") != "prepared":
        return None
    pages = json.loads(report.read_text(encoding="utf-8"))
    inputs: set[str] = {str(p["input"]) for p in pages}
    numbers = [p.get("page") for p in pages]
    if len(inputs) != 1 or not all(p.get("born_digital") for p in pages):
        return None
    if numbers != list(range(len(pages))):
        return None  # empty pages were left out: sheets and PDF pages no longer line up
    return root / "input" / inputs.pop()


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


def combine_job(job_id: str) -> dict[str, Any]:
    """M9: a new score from parts of finished jobs (``options["combine"]``)."""
    from lilyscan.combine import Selection, combine

    settings = Settings.from_env()
    store = _store(settings)
    try:
        job = store.get(job_id)
        if job is None:
            raise LookupError(f"unknown job {job_id}")
        store.update(job_id, status=JobStatus.RUNNING, stage="combine")
        spec = job.options["combine"]

        def selection(p: dict[str, Any]) -> Selection:
            return Selection(
                job_dir(settings.data_dir, p["job"]),
                p["part"],
                transpose=p.get("transpose"),
                name=p.get("name"),
            )

        selections = [selection(p) for p in spec["parts"]]
        piano = [selection(p) for p in spec.get("piano") or []]
        root = job_dir(settings.data_dir, job_id)
        report = combine(selections, root, spec.get("title"), settings, piano=piano)
        store.update(job_id, status=JobStatus.DONE, stage="done", report=report)
        return report
    except Exception as exc:
        store.update(job_id, status=JobStatus.FAILED, error=f"combine: {exc}")
        log.error("combine_job %s failed:\n%s", job_id, traceback.format_exc())
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
