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

    def submit(self, job_id: str) -> None:
        self.submitted.append(job_id)


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
    assert job["options"] == {"ocr_languages": "eng+ita"}
    assert client.post("/api/jobs", files=pdf).json()["options"] == {}


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
