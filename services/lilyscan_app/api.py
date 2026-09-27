"""HTTP API: ``uvicorn lilyscan_app.api:app``."""

# No ``from __future__ import annotations`` here: FastAPI must resolve the
# dependency aliases defined inside ``create_app`` at runtime.

import io
import json
import re
import zipfile
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Any

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, PlainTextResponse, Response
from fastapi.staticfiles import StaticFiles

from lilyscan.pipeline import SOURCE_MAP
from lilyscan.runtime.config import (
    AUDIVERIS_VERSION,
    LILYPOND_VERSION,
    Settings,
    parse_ocr_languages,
)

from .dispatch import Dispatcher, make_dispatcher
from .jobs import JobStatus, JobStore, job_dir

ALLOWED_SUFFIXES = {".pdf", ".png", ".jpg", ".jpeg", ".tif", ".tiff"}
MAX_UPLOAD_BYTES = 200 * 1024 * 1024
MAX_LY_BYTES = 5 * 1024 * 1024
_LY_FILE = re.compile(r"[A-Za-z0-9_./-]+\.ly")
_SAFE = re.compile(r"[^A-Za-z0-9._-]+")


def create_app(settings: Settings | None = None, dispatcher: Dispatcher | None = None) -> FastAPI:
    s = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.store = JobStore(s.data_dir)
        app.state.dispatcher = dispatcher or make_dispatcher(s)
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
        straighten: Annotated[bool, Form()] = True,
    ) -> dict[str, Any]:
        options: dict[str, Any] = {"prepare": straighten}
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

    def _job_root(job_id: str, jobs: JobStore) -> Path:
        if jobs.get(job_id) is None:
            raise HTTPException(404, "job not found")
        return job_dir(s.data_dir, job_id).resolve()

    @app.get("/api/jobs/{job_id}/pages/{page}.png")
    def get_page(job_id: str, page: int, jobs: Store) -> Response:
        """Page ``page`` (1-based) as the engine analysed it; review boxes use its pixels."""
        root = _job_root(job_id, jobs)
        run_path = root / "engine" / "run.json"
        omr = (
            json.loads(run_path.read_text(encoding="utf-8")).get("omr_files")
            if run_path.is_file()
            else None
        )
        if not omr:
            raise HTTPException(404, "no engine project for this job")
        with zipfile.ZipFile(root / omr[0]) as z:
            sheets = sorted(
                int(m.group(1))
                for name in z.namelist()
                if (m := re.fullmatch(r"sheet#(\d+)/BINARY\.png", name))
            )
            if not 1 <= page <= len(sheets):
                raise HTTPException(404, "no such page")
            data = z.read(f"sheet#{sheets[page - 1]}/BINARY.png")
        return Response(data, media_type="image/png", headers={"Cache-Control": "max-age=3600"})

    @app.get("/api/jobs/{job_id}/review")
    def get_review(job_id: str, jobs: Store) -> dict[str, Any]:
        root = _job_root(job_id, jobs)
        review_path = root / "review.json"
        report_path = root / "report.json"
        if not review_path.is_file() or not report_path.is_file():
            raise HTTPException(404, "no review yet")
        report = json.loads(report_path.read_text(encoding="utf-8"))
        review: dict[str, Any] = json.loads(review_path.read_text(encoding="utf-8"))
        review["qa"] = report.get("qa")
        review["lilypond"] = report.get("lilypond")
        review["pages"] = (report.get("geometry") or {}).get("pages", [])
        return review

    def _ly_path(root: Path, path: str) -> Path:
        ly_root = (root / "ly").resolve()
        target = (ly_root / path).resolve()
        editable = (
            _LY_FILE.fullmatch(path) is not None
            and target.is_relative_to(ly_root)
            and "svg" not in target.relative_to(ly_root).parts
        )
        if not editable:
            raise HTTPException(404, "not an editable LilyPond file")
        return target

    @app.get("/api/jobs/{job_id}/ly/{path:path}", response_class=PlainTextResponse)
    def read_ly(job_id: str, path: str, jobs: Store) -> str:
        target = _ly_path(_job_root(job_id, jobs), path)
        if not target.is_file():
            raise HTTPException(404, "file not found")
        return target.read_text(encoding="utf-8")

    @app.put("/api/jobs/{job_id}/ly/{path:path}", status_code=204)
    async def write_ly(job_id: str, path: str, request: Request, jobs: Store) -> Response:
        root = _job_root(job_id, jobs)
        job = jobs.get(job_id)
        if job is not None and job.status in (JobStatus.QUEUED, JobStatus.RUNNING):
            raise HTTPException(409, "the job is busy; try again when it has finished")
        target = _ly_path(root, path)
        if not target.is_file():
            raise HTTPException(404, "file not found")
        body = await request.body()
        if len(body) > MAX_LY_BYTES:
            raise HTTPException(413, "file too large")
        try:
            text = body.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise HTTPException(422, "LilyPond source must be UTF-8") from exc
        target.write_text(text, encoding="utf-8", newline="\n")
        return Response(status_code=204)

    @app.post("/api/jobs/{job_id}/recompile", status_code=202)
    def recompile_job(job_id: str, jobs: Store, dispatch: Disp) -> dict[str, Any]:
        root = _job_root(job_id, jobs)
        job = jobs.get(job_id)
        if job is not None and job.status in (JobStatus.QUEUED, JobStatus.RUNNING):
            raise HTTPException(409, "the job is busy")
        if not (root / "report.json").is_file():
            raise HTTPException(409, "nothing to recompile yet")
        jobs.update(job_id, status=JobStatus.QUEUED, stage="recompile")
        dispatch.recompile(job_id)
        refreshed = jobs.get(job_id)
        assert refreshed is not None
        return refreshed.to_dict()

    @app.get("/api/jobs/{job_id}/download/{kind}")
    def download(job_id: str, kind: str, jobs: Store) -> Response:
        root = _job_root(job_id, jobs)
        ly_root = root / "ly"
        if kind == "ly":
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
                for p in sorted(ly_root.rglob("*")):
                    rel = p.relative_to(ly_root)
                    if p.is_file() and "svg" not in rel.parts and p.name != SOURCE_MAP:
                        z.write(p, f"lilyscan-{job_id}/{rel.as_posix()}")
            return Response(
                buffer.getvalue(),
                media_type="application/zip",
                headers={"Content-Disposition": f'attachment; filename="lilyscan-{job_id}.zip"'},
            )
        candidates = {
            "pdf": [ly_root / "main.pdf"],
            "midi": [ly_root / "main.midi", ly_root / "main.mid"],
            "musicxml": sorted((root / "engine").glob("*.mxl")),
        }.get(kind)
        if candidates is None:
            raise HTTPException(404, f"unknown download {kind!r}")
        found = next((p for p in candidates if p.is_file()), None)
        if found is None:
            raise HTTPException(404, f"no {kind} for this job")
        filename = f"lilyscan-{job_id}{found.suffix}"
        return FileResponse(found, filename=filename)

    web = s.web_dir
    if web is not None and (web / "index.html").is_file():
        app.mount("/", StaticFiles(directory=web, html=True), name="web")

    return app


def __getattr__(name: str) -> Any:
    # Lazily build the module-level app for ``uvicorn lilyscan_app.api:app`` so importing
    # this module in tests does not require Redis.
    if name == "app":
        return create_app()
    raise AttributeError(name)
