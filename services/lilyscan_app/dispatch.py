"""Submitting jobs to the queues.

``RqDispatcher`` (default) hands work to the engine and pipeline workers through
Redis. ``InlineDispatcher`` (``LILYSCAN_DISPATCH=inline``) runs the same tasks in a
background thread of the API process, for single-machine use and local development
without Redis or Docker.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Protocol

from lilyscan.runtime.config import ENGINE_QUEUE, PIPELINE_QUEUE, Settings

log = logging.getLogger(__name__)


class Dispatcher(Protocol):
    def submit(self, job_id: str) -> None: ...

    def recompile(self, job_id: str) -> None: ...


class RqDispatcher:
    def __init__(self, settings: Settings) -> None:
        from redis import Redis
        from rq import Queue

        conn = Redis.from_url(settings.redis_url)
        self._engine = Queue(ENGINE_QUEUE, connection=conn)
        self._pipeline = Queue(PIPELINE_QUEUE, connection=conn)
        self._engine_timeout = int(settings.audiveris_timeout_s) + 120

    def submit(self, job_id: str) -> None:
        engine_job = self._engine.enqueue(
            "lilyscan_app.tasks.engine_transcribe", job_id, job_timeout=self._engine_timeout
        )
        self._pipeline.enqueue(
            "lilyscan_app.tasks.pipeline_finish", job_id, depends_on=engine_job, job_timeout=1800
        )

    def recompile(self, job_id: str) -> None:
        self._pipeline.enqueue("lilyscan_app.tasks.recompile_job", job_id, job_timeout=600)


class InlineDispatcher:
    """One job at a time, in-process. Task failures are recorded on the job by the tasks."""

    def __init__(self) -> None:
        self._pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="lilyscan-job")

    def _run(self, *steps: Callable[[str], Any], job_id: str) -> None:
        def chain() -> None:
            for step in steps:
                try:
                    step(job_id)
                except Exception:
                    log.exception("%s failed for job %s", step.__name__, job_id)
                    return

        self._pool.submit(chain)

    def submit(self, job_id: str) -> None:
        from . import tasks

        self._run(tasks.engine_transcribe, tasks.pipeline_finish, job_id=job_id)

    def recompile(self, job_id: str) -> None:
        from . import tasks

        self._run(tasks.recompile_job, job_id=job_id)

    def shutdown(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)


def make_dispatcher(settings: Settings) -> Dispatcher:
    if settings.dispatch == "inline":
        return InlineDispatcher()
    return RqDispatcher(settings)
