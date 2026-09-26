"""HTTP API: ``uvicorn lilyscan_app.api:app``."""

# No ``from __future__ import annotations`` here: FastAPI must resolve the
# dependency aliases defined inside ``create_app`` at runtime.

import re
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Any

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse

from lilyscan.runtime.config import (
    AUDIVERIS_VERSION,
    LILYPOND_VERSION,
    Settings,
    parse_ocr_languages,
)

from .dispatch import Dispatcher, RqDispatcher
from .jobs import JobStore, job_dir

ALLOWED_SUFFIXES = {".pdf", ".png", ".jpg", ".jpeg", ".tif", ".tiff"}
MAX_UPLOAD_BYTES = 200 * 1024 * 1024
_SAFE = re.compile(r"[^A-Za-z0-9._-]+")


def create_app(settings: Settings | None = None, dispatcher: Dispatcher | None = None) -> FastAPI:
    s = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.store = JobStore(s.data_dir)
        app.state.dispatcher = dispatcher or RqDispatcher(s)
        yield
        app.state.store.close()

    app = FastAPI(title="lilyscan", lifespan=lifespan)

    def store(request: Request) -> JobStore:
        st: JobStore = request.app.state.store
        return st

    def disp(request: Request) -> Dispatcher:
        d: Dispatcher = request.app.state.dispatcher
        return d

    Store = Annotated[JobStore, Depends(store)]
    Disp = Annotated[Dispatcher, Depends(disp)]

    @app.get("/api/health")
    def health() -> dict[str, Any]:
        return {
            "status": "ok",
            "lilypond": LILYPOND_VERSION,
            "audiveris": AUDIVERIS_VERSION,
            "ocr_languages": s.ocr_languages,
        }

    @app.post("/api/jobs", status_code=201)
    async def create_job(
        files: Annotated[list[UploadFile], File()],
        jobs: Store,
        dispatch: Disp,
        ocr_languages: Annotated[str | None, Form()] = None,
    ) -> dict[str, Any]:
        options: dict[str, Any] = {}
        if ocr_languages:
            try:
                options["ocr_languages"] = parse_ocr_languages(ocr_languages)
            except ValueError as exc:
                raise HTTPException(422, str(exc)) from exc

        # Validate every file before creating the job, so a rejected upload
        # never leaves a half-written job behind.
        uploads: list[tuple[str, bytes]] = []
        for i, f in enumerate(files):
            suffix = Path(f.filename or "").suffix.lower()
            if suffix not in ALLOWED_SUFFIXES:
                raise HTTPException(415, f"unsupported file type: {f.filename!r}")
            data = await f.read()
            if len(data) > MAX_UPLOAD_BYTES:
                raise HTTPException(413, f"{f.filename!r} exceeds the upload limit")
            stem = _SAFE.sub("_", Path(f.filename or "upload").stem)[:60] or "upload"
            uploads.append((f"{i:02d}-{stem}{suffix}", data))

        job = jobs.create([name for name, _ in uploads], options)
        input_dir = job_dir(s.data_dir, job.id) / "input"
        input_dir.mkdir(parents=True)
        for name, data in uploads:
            (input_dir / name).write_bytes(data)
        dispatch.submit(job.id)
        return job.to_dict()

    @app.get("/api/jobs")
    def list_jobs(jobs: Store) -> list[dict[str, Any]]:
        return [j.to_dict() for j in jobs.list()]

    @app.get("/api/jobs/{job_id}")
    def get_job(job_id: str, jobs: Store) -> dict[str, Any]:
        job = jobs.get(job_id)
        if job is None:
            raise HTTPException(404, "job not found")
        return job.to_dict()

    @app.get("/api/jobs/{job_id}/files/{path:path}")
    def get_file(job_id: str, path: str, jobs: Store) -> FileResponse:
        if jobs.get(job_id) is None:
            raise HTTPException(404, "job not found")
        root = job_dir(s.data_dir, job_id).resolve()
        target = (root / path).resolve()
        if not target.is_relative_to(root) or not target.is_file():
            raise HTTPException(404, "file not found")
        return FileResponse(target)

    return app


def __getattr__(name: str) -> Any:
    # Lazily build the module-level app for ``uvicorn lilyscan_app.api:app`` so importing
    # this module in tests does not require Redis.
    if name == "app":
        return create_app()
    raise AttributeError(name)
