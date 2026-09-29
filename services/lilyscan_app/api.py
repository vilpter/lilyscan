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
from pydantic import BaseModel

from lilyscan.ir.models import Score
from lilyscan.ir.transpose import Interval
from lilyscan.lilypond.single import single_file
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


class CombinePart(BaseModel):
    job: str
    part: str  # part id in that job's score
    transpose: str | None = None  # an interval ("M2", "-m3") or "concert"
    name: str | None = None  # a new instrument name


class RerunRequest(BaseModel):
    ocr_languages: str | None = None  # None: keep the job's
    straighten: bool | None = None


class CombineRequest(BaseModel):
    title: str | None = None
    parts: list[CombinePart]
    # Parts reduced onto a piano grand staff below ``parts``: an accompaniment.
    piano: list[CombinePart] = []


def _title(root: Path) -> str | None:
    """The job's score title, if it has one."""
    path = root / "ir" / "score.json"
    if not path.is_file():
        return None
    title = json.loads(path.read_text(encoding="utf-8")).get("title")
    return title if isinstance(title, str) else None


def _file_name(title: str | None) -> str | None:
    """A title as a plain file name (LilyPond names its output files after it)."""
    name = re.sub(r"[^A-Za-z0-9 ._'-]+", "", title or "").strip(" .")
    return re.sub(r"\s+", " ", name)[:80] or None


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

    def _parts(root: Path) -> list[dict[str, Any]]:
        path = root / "ir" / "score.json"
        if not path.is_file():
            raise HTTPException(404, "no finished score for this job")
        score = Score.model_validate_json(path.read_text(encoding="utf-8"))
        return [
            {
                "id": p.id,
                "name": p.name or p.id,
                "staves": len(p.staves),
                "measures": len(p.staves[0].measures) if p.staves else 0,
                "transpose_semitones": p.transpose_semitones,
            }
            for p in score.parts
        ]

    @app.get("/api/jobs/{job_id}/parts")
    def get_parts(job_id: str, jobs: Store) -> list[dict[str, Any]]:
        """The parts of a finished job's score, for the score combiner."""
        return _parts(_job_root(job_id, jobs))

    @app.post("/api/jobs/{job_id}/rerun", status_code=201)
    def rerun_job(job_id: str, body: RerunRequest, jobs: Store, dispatch: Disp) -> dict[str, Any]:
        """A new job from the same uploads with different settings (the old one is kept)."""
        root = _job_root(job_id, jobs)
        old = jobs.get(job_id)
        assert old is not None
        if old.options.get("combine") or not old.inputs:
            raise HTTPException(409, "a combined score has no uploads to transcribe again")
        options = {k: v for k, v in old.options.items() if k != "rerun_of"}
        if body.ocr_languages is not None:
            try:
                options["ocr_languages"] = parse_ocr_languages(body.ocr_languages)
            except ValueError as exc:
                raise HTTPException(422, str(exc)) from exc
        if body.straighten is not None:
            options["prepare"] = body.straighten
        options["rerun_of"] = job_id
        job = jobs.create(list(old.inputs), options)
        target = job_dir(s.data_dir, job.id) / "input"
        target.mkdir(parents=True)
        for name in old.inputs:
            (target / name).write_bytes((root / "input" / name).read_bytes())
        dispatch.submit(job.id)
        return job.to_dict()

    @app.post("/api/scores", status_code=201)
    def create_score(body: CombineRequest, jobs: Store, dispatch: Disp) -> dict[str, Any]:
        """A new score from parts of finished jobs (M9); built by a pipeline worker."""
        if not body.parts:
            raise HTTPException(422, "choose at least one part")
        for p in [*body.parts, *body.piano]:
            source = jobs.get(p.job)
            if source is None:
                raise HTTPException(404, f"job {p.job} not found")
            if source.status is not JobStatus.DONE:
                raise HTTPException(409, f"job {p.job} is not finished")
            if p.part not in {x["id"] for x in _parts(job_dir(s.data_dir, p.job))}:
                raise HTTPException(422, f"job {p.job} has no part {p.part}")
            if p.transpose and p.transpose != "concert":
                try:
                    Interval.parse(p.transpose)
                except ValueError as exc:
                    raise HTTPException(422, str(exc)) from exc
        job = jobs.create([], {"combine": body.model_dump()})
        dispatch.combine(job.id)
        return job.to_dict()

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

    def _engine_run(root: Path) -> dict[str, Any]:
        """The engine run the job kept (its files are listed relative to the job folder)."""
        path = root / "engine" / "run.json"
        run: dict[str, Any] = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
        return run

    def _job_root(job_id: str, jobs: JobStore) -> Path:
        if jobs.get(job_id) is None:
            raise HTTPException(404, "job not found")
        return job_dir(s.data_dir, job_id).resolve()

    @app.get("/api/jobs/{job_id}/pages/{page}.png")
    def get_page(job_id: str, page: int, jobs: Store) -> Response:
        """Page ``page`` (1-based) as the engine analysed it; review boxes use its pixels."""
        root = _job_root(job_id, jobs)
        omr = _engine_run(root).get("omr_files")
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
        review["prepare"] = report.get("prepare", [])
        review["alternatives"] = (report.get("engine") or {}).get("alternatives", [])
        review["combined_from"] = report.get("combined_from")
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
            # One file engraving the score and, when there are several, each part: built
            # from the project as it is now, so edits made in the review are in it.
            files = {
                p.relative_to(ly_root).as_posix(): p.read_text(encoding="utf-8")
                for p in sorted(ly_root.rglob("*.ly"))
                if "svg" not in p.relative_to(ly_root).parts
            }
            if "main.ly" not in files:
                raise HTTPException(404, "no LilyPond source for this job")
            name = _file_name(_title(root)) or f"lilyscan-{job_id}"
            return Response(
                single_file(files),
                media_type="text/x-lilypond; charset=utf-8",
                headers={"Content-Disposition": f'attachment; filename="{name}.ly"'},
            )
        if kind == "ly-project":
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
            # The score as transcribed and repaired (a combined score has only this one),
            # else the engine's own export.
            "musicxml": [
                root / "score.musicxml",
                *(root / name for name in _engine_run(root).get("mxl_files", [])),
            ],
            # The Audiveris project, to open in the Audiveris desktop application.
            "omr": [root / name for name in _engine_run(root).get("omr_files", [])],
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
