from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest

from lilyscan.pipeline import convert_file
from lilyscan.runtime.config import AUDIVERIS_VERSION
from lilyscan_app import tasks
from lilyscan_app.jobs import JobStatus, JobStore, job_dir

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


def test_pipeline_finish_completes_job(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LILYSCAN_DATA_DIR", str(tmp_path))
    store = JobStore(tmp_path)
    job = store.create(["00-score.pdf"])
    root = job_dir(tmp_path, job.id)
    (root / "engine").mkdir(parents=True)
    # Stand in for Audiveris: its MusicXML output plus the runner's summary.
    shutil.copy(FIXTURE, root / "engine" / "score.musicxml")
    (root / "engine" / "run.json").write_text(
        json.dumps({"mxl_files": ["engine/score.musicxml"], "returncode": 0}), encoding="utf-8"
    )

    report = tasks.pipeline_finish(job.id)

    done = store.get(job.id)
    assert done is not None and done.status is JobStatus.DONE and done.stage == "done"
    assert report["qa"]["passed"]
    assert report["geometry"] is None  # this stand-in run saved no .omr
    assert "ly/main.pdf" in report["lilypond"]["outputs"]
    assert (root / "report.json").is_file()
    store.close()


def test_pipeline_finish_with_omr_reports_geometry(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("LILYSCAN_DATA_DIR", str(tmp_path))
    store = JobStore(tmp_path)
    job = store.create(["00-score.png"])
    root = job_dir(tmp_path, job.id)
    (root / "engine").mkdir(parents=True)
    run = OMR_FIXTURE / "piano-two-voices"
    shutil.copy(run / "output.mxl", root / "engine" / "score.mxl")
    shutil.copy(run / "book.omr", root / "engine" / "score.omr")
    (root / "engine" / "run.json").write_text(
        json.dumps({"mxl_files": ["engine/score.mxl"], "omr_files": ["engine/score.omr"]}),
        encoding="utf-8",
    )

    report = tasks.pipeline_finish(job.id)

    assert report["geometry"]["located_rate"] >= 0.98
    assert (root / report["geometry"]["overlays"][0]).is_file()
    ir = json.loads((root / "ir" / "score.json").read_text(encoding="utf-8"))
    assert '"bbox"' in json.dumps(ir) and '"confidence"' in json.dumps(ir)
    store.close()
