from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from lilyscan.runtime.config import Settings
from lilyscan_app import api
from lilyscan_app.api import create_app


class RecordingDispatcher:
    def __init__(self) -> None:
        self.submitted: list[str] = []
        self.recompiled: list[str] = []
        self.combined: list[str] = []

    def submit(self, job_id: str) -> None:
        self.submitted.append(job_id)

    def recompile(self, job_id: str) -> None:
        self.recompiled.append(job_id)

    def combine(self, job_id: str) -> None:
        self.combined.append(job_id)


@pytest.fixture
def dispatcher() -> RecordingDispatcher:
    return RecordingDispatcher()


@pytest.fixture
def client(tmp_path: Path, dispatcher: RecordingDispatcher) -> Iterator[TestClient]:
    settings = Settings.from_env({"LILYSCAN_DATA_DIR": str(tmp_path)})
    with TestClient(create_app(settings, dispatcher)) as c:
        yield c


def test_health(client: TestClient) -> None:
    body = client.get("/api/health").json()
    assert body["status"] == "ok"


def test_upload_creates_and_dispatches_job(
    client: TestClient, dispatcher: RecordingDispatcher, tmp_path: Path
) -> None:
    files = [
        ("files", ("My Score (p1).pdf", b"%PDF-1.4", "application/pdf")),
        ("files", ("page2.PNG", b"\x89PNG", "image/png")),
    ]
    r = client.post("/api/jobs", files=files)
    assert r.status_code == 201
    job = r.json()
    assert job["status"] == "queued"
    assert job["inputs"] == ["00-My_Score_p1_.pdf", "01-page2.png"]
    assert dispatcher.submitted == [job["id"]]
    assert (tmp_path / "jobs" / job["id"] / "input" / "01-page2.png").read_bytes() == b"\x89PNG"

    assert client.get(f"/api/jobs/{job['id']}").json()["id"] == job["id"]
    assert [j["id"] for j in client.get("/api/jobs").json()] == [job["id"]]
    got = client.get(f"/api/jobs/{job['id']}/files/input/01-page2.png")
    assert got.status_code == 200 and got.content == b"\x89PNG"


def test_rejects_unsupported_type(client: TestClient, dispatcher: RecordingDispatcher) -> None:
    r = client.post("/api/jobs", files=[("files", ("notes.txt", b"hi", "text/plain"))])
    assert r.status_code == 415
    assert dispatcher.submitted == []


def test_per_job_ocr_languages(client: TestClient) -> None:
    pdf = [("files", ("a.pdf", b"x", "application/pdf"))]
    job = client.post("/api/jobs", files=pdf, data={"ocr_languages": "ENG+ita"}).json()
    assert job["options"] == {"ocr_languages": "eng+ita", "prepare": True}
    assert client.post("/api/jobs", files=pdf).json()["options"] == {"prepare": True}


def test_straightening_can_be_turned_off(client: TestClient) -> None:
    png = [("files", ("a.png", b"\x89PNG", "image/png"))]
    job = client.post("/api/jobs", files=png, data={"straighten": "false"}).json()
    assert job["options"] == {"prepare": False}


def test_rejects_bad_ocr_languages(client: TestClient, dispatcher: RecordingDispatcher) -> None:
    pdf = [("files", ("a.pdf", b"x", "application/pdf"))]
    r = client.post("/api/jobs", files=pdf, data={"ocr_languages": "eng+../etc"})
    assert r.status_code == 422
    assert dispatcher.submitted == []
    assert client.get("/api/jobs").json() == []


def test_oversize_upload_leaves_no_job(
    client: TestClient, dispatcher: RecordingDispatcher, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(api, "MAX_UPLOAD_BYTES", 4)
    files = [
        ("files", ("ok.pdf", b"x", "application/pdf")),
        ("files", ("big.pdf", b"123456", "application/pdf")),
    ]
    assert client.post("/api/jobs", files=files).status_code == 413
    assert client.get("/api/jobs").json() == []
    assert dispatcher.submitted == []


def test_health_reports_ocr_languages(client: TestClient) -> None:
    assert client.get("/api/health").json()["ocr_languages"] == "eng+lat+deu+fra"


def test_file_download_blocks_traversal(client: TestClient) -> None:
    job = client.post("/api/jobs", files=[("files", ("a.pdf", b"x", "application/pdf"))]).json()
    r = client.get(f"/api/jobs/{job['id']}/files/../../jobs.sqlite")
    assert r.status_code == 404


def test_unknown_job(client: TestClient) -> None:
    assert client.get("/api/jobs/nope").status_code == 404


# --- review UI endpoints (M4) --------------------------------------------------------

OMR = Path(__file__).parents[1] / "eval" / "fixtures" / "omr" / "5.11.0" / "piano-two-voices"


@pytest.fixture
def finished_job(client: TestClient, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> str:
    """A job carried through the real pipeline, with an Audiveris run standing in."""
    import json
    import shutil

    from lilyscan_app import tasks
    from lilyscan_app.jobs import job_dir

    monkeypatch.setenv("LILYSCAN_DATA_DIR", str(tmp_path))
    job = client.post("/api/jobs", files=[("files", ("p.png", b"x", "image/png"))]).json()
    root = job_dir(tmp_path, job["id"])
    (root / "engine").mkdir()
    shutil.copy(OMR / "output.mxl", root / "engine" / "score.mxl")
    shutil.copy(OMR / "book.omr", root / "engine" / "score.omr")
    (root / "engine" / "run.json").write_text(
        json.dumps({"mxl_files": ["engine/score.mxl"], "omr_files": ["engine/score.omr"]}),
        encoding="utf-8",
    )
    tasks.pipeline_finish(job["id"])
    return str(job["id"])


@pytest.mark.lilypond
def test_review_and_page_images(client: TestClient, finished_job: str) -> None:
    review = client.get(f"/api/jobs/{finished_job}/review").json()
    assert review["measures"] and review["pages"][0]["width"] == 2480
    assert review["lilypond"]["svg"] and {c["id"] for c in review["qa"]["checks"]} >= {"Q1", "Q5"}
    assert review["prepare"] == [] and review["alternatives"] == []  # no Stage 1 in this job
    page = client.get(f"/api/jobs/{finished_job}/pages/1.png")
    assert page.status_code == 200 and page.content.startswith(b"\x89PNG")
    assert client.get(f"/api/jobs/{finished_job}/pages/2.png").status_code == 404


@pytest.mark.lilypond
def test_read_and_write_lilypond_sources(client: TestClient, finished_job: str) -> None:
    url = f"/api/jobs/{finished_job}/ly/parts/piano.ly"
    original = client.get(url).text
    assert "pianoUpperMusic" in original
    assert client.put(url, content="% edited\n" + original).status_code == 204
    assert client.get(url).text.startswith("% edited")
    report = client.get(f"/api/jobs/{finished_job}/files/report.json").content
    for bad in ("../report.json", "svg/main.svg", "parts/../../report.json", "lilyscan-map.json"):
        # 404 from the editor route, or 405 when the client normalizes ".." away first.
        status = client.put(f"/api/jobs/{finished_job}/ly/{bad}", content="x").status_code
        assert status in (404, 405), bad
    assert client.get(f"/api/jobs/{finished_job}/files/report.json").content == report
    assert client.put(url, content=b"\xff\xfe").status_code == 422


@pytest.mark.lilypond
def test_edits_and_recompile_wait_for_a_busy_job(
    client: TestClient, finished_job: str, tmp_path: Path, dispatcher: RecordingDispatcher
) -> None:
    from lilyscan_app.jobs import JobStatus, JobStore

    store = JobStore(tmp_path)
    store.update(finished_job, status=JobStatus.RUNNING)
    assert client.put(f"/api/jobs/{finished_job}/ly/main.ly", content="x").status_code == 409
    assert client.post(f"/api/jobs/{finished_job}/recompile").status_code == 409
    store.update(finished_job, status=JobStatus.DONE)
    store.close()

    response = client.post(f"/api/jobs/{finished_job}/recompile")
    assert response.status_code == 202 and response.json()["stage"] == "recompile"
    assert dispatcher.recompiled == [finished_job]


@pytest.mark.lilypond
def test_downloads(client: TestClient, finished_job: str) -> None:
    import io
    import zipfile

    base = f"/api/jobs/{finished_job}/download"
    archive = client.get(f"{base}/ly")
    assert archive.status_code == 200
    names = zipfile.ZipFile(io.BytesIO(archive.content)).namelist()
    assert f"lilyscan-{finished_job}/main.ly" in names
    assert not [n for n in names if "/svg/" in n or n.endswith("lilyscan-map.json")]
    for kind in ("pdf", "midi", "musicxml"):
        assert client.get(f"{base}/{kind}").status_code == 200, kind
    assert client.get(f"{base}/nope").status_code == 404


def test_web_app_is_served(client: TestClient) -> None:
    page = client.get("/")
    assert page.status_code == 200 and "<title>Lilyscan</title>" in page.text


@pytest.mark.lilypond
def test_combine_parts_of_finished_jobs(
    client: TestClient, finished_job: str, dispatcher: RecordingDispatcher, tmp_path: Path
) -> None:
    from lilyscan_app import tasks

    parts = client.get(f"/api/jobs/{finished_job}/parts").json()
    assert parts and {"id", "name", "staves", "measures"} <= set(parts[0])
    body = {
        "title": "Duo",
        "parts": [
            {"job": finished_job, "part": parts[0]["id"]},
            {"job": finished_job, "part": parts[0]["id"], "transpose": "-P8", "name": "Low"},
        ],
    }
    r = client.post("/api/scores", json=body)
    assert r.status_code == 201, r.text
    job = r.json()
    assert dispatcher.combined == [job["id"]] and job["inputs"] == []
    assert job["options"]["combine"]["title"] == "Duo"

    report = tasks.combine_job(job["id"])  # what the pipeline worker would run
    assert report["qa"]["checks"][0]["passed"]
    done = client.get(f"/api/jobs/{job['id']}").json()
    assert done["status"] == "done"
    assert client.get(f"/api/jobs/{job['id']}/download/pdf").status_code == 200


@pytest.mark.lilypond
def test_combine_rejects_bad_requests(client: TestClient, finished_job: str) -> None:
    part = client.get(f"/api/jobs/{finished_job}/parts").json()[0]["id"]
    assert client.post("/api/scores", json={"parts": []}).status_code == 422
    missing = {"parts": [{"job": "nope", "part": part}]}
    assert client.post("/api/scores", json=missing).status_code == 404
    no_part = {"parts": [{"job": finished_job, "part": "P99"}]}
    assert client.post("/api/scores", json=no_part).status_code == 422
    bad = {"parts": [{"job": finished_job, "part": part, "transpose": "M4"}]}
    assert client.post("/api/scores", json=bad).status_code == 422


def test_combine_needs_finished_jobs(client: TestClient) -> None:
    job = client.post("/api/jobs", files=[("files", ("p.png", b"x", "image/png"))]).json()
    body = {"parts": [{"job": job["id"], "part": "P1"}]}
    assert client.post("/api/scores", json=body).status_code == 409
