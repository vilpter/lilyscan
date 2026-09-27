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


def test_pipeline_finish_repairs_engine_output(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("LILYSCAN_DATA_DIR", str(tmp_path))
    store = JobStore(tmp_path)
    job = store.create(["00-score.png"])
    root = job_dir(tmp_path, job.id)
    (root / "engine").mkdir(parents=True)
    run = OMR_FIXTURE / "split-flute"
    shutil.copy(run / "output.mxl", root / "engine" / "score.mxl")
    shutil.copy(run / "book.omr", root / "engine" / "score.omr")
    (root / "engine" / "run.json").write_text(
        json.dumps({"mxl_files": ["engine/score.mxl"], "omr_files": ["engine/score.omr"]}),
        encoding="utf-8",
    )

    report = tasks.pipeline_finish(job.id)

    assert [r["rule"] for r in report["repairs"]] == ["part-merge"]
    assert report["counts"]["parts"] == 1
    review = json.loads((root / "review.json").read_text(encoding="utf-8"))
    assert review["repairs"][0]["detail"] == "merged P1 (F1.) into P2 (Flute)"
    # Moved measures keep the boxes the engine gave them on the later systems.
    assert all(m["bbox"] is not None for m in review["measures"])
    store.close()


def test_engine_keeps_the_run_expected_to_be_better(tmp_path: Path) -> None:
    from lilyscan.engine.audiveris.runner import AudiverisRun
    from lilyscan.pipeline import assess_engine_run

    runs = []
    for label, fixture in (("prepared", "split-flute"), ("uploaded", "piano-two-voices")):
        out = tmp_path / "engine" / label
        out.mkdir(parents=True)
        shutil.copy(OMR_FIXTURE / fixture / "output.mxl", out / "score.mxl")
        shutil.copy(OMR_FIXTURE / fixture / "book.omr", out / "score.omr")
        run = AudiverisRun([], 0, 1.0, "", [out / "score.omr"], [out / "score.mxl"])
        summary = {
            "pages": label,
            "mxl_files": [f"engine/{label}/score.mxl"],
            "omr_files": [f"engine/{label}/score.omr"],
        }
        runs.append((run, summary))
    expected = {s["pages"]: assess_engine_run(tmp_path, s) for _, s in runs}

    run, chosen = tasks._choose(tmp_path, runs)

    best = max(expected, key=lambda k: expected[k])
    assert chosen["pages"] == best and run is runs[[s["pages"] for _, s in runs].index(best)][0]
    assert [a["chosen"] for a in chosen["alternatives"]] == [k == best for k in expected]
    assert {a["pages"]: a["expected_right"] for a in chosen["alternatives"]} == expected
