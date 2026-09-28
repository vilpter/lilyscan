"""Job records (SQLite for now; the schema is kept Postgres-compatible)."""

from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path
from typing import Any


class JobStatus(StrEnum):
    QUEUED = "queued"
    RUNNING = "running"
    DONE = "done"
    FAILED = "failed"


@dataclass
class Job:
    id: str
    created_at: str
    status: JobStatus
    stage: str
    inputs: list[str]
    options: dict[str, Any] = field(default_factory=dict)
    error: str | None = None
    report: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "created_at": self.created_at,
            "status": self.status.value,
            "stage": self.stage,
            "inputs": self.inputs,
            "options": self.options,
            "error": self.error,
            "report": self.report,
        }


_SCHEMA = """
CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY,
    created_at TEXT NOT NULL,
    status TEXT NOT NULL,
    stage TEXT NOT NULL,
    inputs TEXT NOT NULL,
    options TEXT NOT NULL,
    error TEXT,
    report TEXT
)
"""


_BUSY_TIMEOUT_S = 30.0


def _use_wal(db: sqlite3.Connection) -> None:
    """Switch the database to write-ahead logging, once: the mode is stored in the file.

    Changing the journal mode does not wait for other connections, so a store opened
    while another process is busy with the database could fail to open. Only the first
    store needs to switch it, and a busy switch is retried.
    """
    for attempt in range(50):
        try:
            if str(db.execute("PRAGMA journal_mode").fetchone()[0]).lower() != "wal":
                db.execute("PRAGMA journal_mode=WAL")
            return
        except sqlite3.OperationalError:
            if attempt == 49:
                raise
            time.sleep(0.1)


def job_dir(data_dir: Path, job_id: str) -> Path:
    return data_dir / "jobs" / job_id


class JobStore:
    def __init__(self, data_dir: Path) -> None:
        self.data_dir = data_dir
        data_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        # The API and each worker process open their own store; wait on a busy database
        # rather than fail.
        self._db = sqlite3.connect(
            data_dir / "jobs.sqlite", check_same_thread=False, timeout=_BUSY_TIMEOUT_S
        )
        self._db.row_factory = sqlite3.Row
        _use_wal(self._db)
        self._db.execute(_SCHEMA)
        self._db.commit()

    def close(self) -> None:
        self._db.close()

    def create(self, inputs: list[str], options: dict[str, Any] | None = None) -> Job:
        job = Job(
            id=uuid.uuid4().hex[:12],
            created_at=datetime.now(UTC).isoformat(timespec="seconds"),
            status=JobStatus.QUEUED,
            stage="upload",
            inputs=inputs,
            options=options or {},
        )
        with self._lock:
            self._db.execute(
                "INSERT INTO jobs VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)",
                (
                    job.id,
                    job.created_at,
                    job.status.value,
                    job.stage,
                    json.dumps(job.inputs),
                    json.dumps(job.options),
                ),
            )
            self._db.commit()
        return job

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
        return _row_to_job(row) if row else None

    def list(self, limit: int = 100) -> list[Job]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM jobs ORDER BY created_at DESC, rowid DESC LIMIT ?", (limit,)
            ).fetchall()
        return [_row_to_job(r) for r in rows]

    def update(
        self,
        job_id: str,
        *,
        status: JobStatus | None = None,
        stage: str | None = None,
        error: str | None = None,
        report: dict[str, Any] | None = None,
    ) -> None:
        sets: list[str] = []
        args: list[Any] = []
        if status is not None:
            sets.append("status = ?")
            args.append(status.value)
        if stage is not None:
            sets.append("stage = ?")
            args.append(stage)
        if error is not None:
            sets.append("error = ?")
            args.append(error)
        if report is not None:
            sets.append("report = ?")
            args.append(json.dumps(report))
        if not sets:
            return
        with self._lock:
            self._db.execute(f"UPDATE jobs SET {', '.join(sets)} WHERE id = ?", (*args, job_id))
            self._db.commit()


def _row_to_job(row: sqlite3.Row) -> Job:
    return Job(
        id=row["id"],
        created_at=row["created_at"],
        status=JobStatus(row["status"]),
        stage=row["stage"],
        inputs=json.loads(row["inputs"]),
        options=json.loads(row["options"]),
        error=row["error"],
        report=json.loads(row["report"]) if row["report"] else None,
    )
