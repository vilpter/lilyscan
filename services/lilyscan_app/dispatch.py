"""Submitting jobs to the queues."""

from __future__ import annotations

from typing import Protocol

from lilyscan.runtime.config import ENGINE_QUEUE, PIPELINE_QUEUE, Settings


class Dispatcher(Protocol):
    def submit(self, job_id: str) -> None: ...


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
