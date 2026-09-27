"""Queue tasks. Referenced by dotted name so each worker image imports only what it runs.

Job flow (one RQ job per step, chained with ``depends_on``):
    engine queue:   engine_transcribe  (Stage 2, Audiveris worker)
    pipeline queue: pipeline_finish    (Stages 3+, pipeline worker)
"""

from __future__ import annotations

import json
import logging
import traceback
from pathlib import Path
from typing import Any

from lilyscan.engine.audiveris.runner import run_audiveris
from lilyscan.pipeline import import_engine_output, produce, recompile
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


def engine_transcribe(job_id: str) -> dict[str, Any]:
    """Stage 2: run Audiveris on the job's inputs."""
    settings = Settings.from_env()
    store = _store(settings)
    try:
        job = store.get(job_id)
        if job is None:
            raise LookupError(f"unknown job {job_id}")
        store.update(job_id, status=JobStatus.RUNNING, stage="engine")
        root = job_dir(settings.data_dir, job_id)
        inputs = [root / "input" / name for name in job.inputs]
        constants = {str(k): str(v) for k, v in job.options.get("audiveris_constants", {}).items()}
        run = run_audiveris(
            inputs,
            root / "engine",
            constants=constants,
            settings=settings,
            ocr_languages=job.options.get("ocr_languages"),
        )
        summary = {
            "command": run.command,
            "returncode": run.returncode,
            "timed_out": run.timed_out,
            "wall_s": round(run.wall_s, 2),
            "omr_files": [str(p.relative_to(root)) for p in run.omr_files],
            "mxl_files": [str(p.relative_to(root)) for p in run.mxl_files],
            "ocr_problems": run.ocr_problems,
            "step_errors": run.step_errors,
        }
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


def pipeline_finish(job_id: str) -> dict[str, Any]:
    """Stages 3 and 6-8: import the engine's MusicXML, generate LilyPond, run QA.

    QA findings do not fail the job; they are the review list for the user.
    """
    settings = Settings.from_env()
    store = _store(settings)
    try:
        store.update(job_id, status=JobStatus.RUNNING, stage="import")
        root = job_dir(settings.data_dir, job_id)
        engine = json.loads((root / "engine" / "run.json").read_text(encoding="utf-8"))
        score, geometry = import_engine_output(root, engine)
        store.update(job_id, stage="lilypond")
        report = {"engine": engine, "geometry": geometry, **produce(score, root, settings)}
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
