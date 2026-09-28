"""The job store under concurrent use (the API and worker processes share it)."""

from __future__ import annotations

from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

from lilyscan_app.jobs import JobStatus, JobStore


def _open_and_write(args: tuple[str, int]) -> int:
    data_dir, n = Path(args[0]), args[1]
    for i in range(n):
        store = JobStore(data_dir)
        job = store.create([f"{i}.png"], {"i": i})
        store.update(job.id, status=JobStatus.RUNNING, stage="engine", report={"x": "y" * 5000})
        store.close()
    return n


def test_many_processes_open_and_write_at_once(tmp_path: Path) -> None:
    with ProcessPoolExecutor(4) as pool:
        done = sum(pool.map(_open_and_write, [(str(tmp_path), 25)] * 4))
    store = JobStore(tmp_path)
    assert done == 100 and len(store.list(limit=200)) == 100
    assert store._db.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
