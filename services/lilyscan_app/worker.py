"""Worker entry point: ``python -m lilyscan_app.worker --role pipeline|engine``."""

from __future__ import annotations

import argparse
import logging

from lilyscan.engine.audiveris.runner import audiveris_version
from lilyscan.lilypond.compile import lilypond_version
from lilyscan.runtime.config import (
    AUDIVERIS_VERSION,
    ENGINE_QUEUE,
    LILYPOND_VERSION,
    PIPELINE_QUEUE,
    Settings,
)
from lilyscan.runtime.device import get_device

log = logging.getLogger("lilyscan_app.worker")


def _check_version(tool: str, pinned: str, found: str | None) -> None:
    if found is None:
        log.error("%s not found; expected %s", tool, pinned)
    elif found != pinned:
        log.warning("%s version %s does not match pinned %s", tool, found, pinned)
    else:
        log.info("%s %s", tool, found)


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--role", choices=["pipeline", "engine"], required=True)
    args = p.parse_args(argv)
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
    )

    settings = Settings.from_env()
    if args.role == "pipeline":
        log.info("compute device: %s", get_device().describe())
        _check_version("LilyPond", LILYPOND_VERSION, lilypond_version(settings))
        queue_name = PIPELINE_QUEUE
    else:
        _check_version("Audiveris", AUDIVERIS_VERSION, audiveris_version(settings))
        queue_name = ENGINE_QUEUE

    from redis import Redis
    from rq import Queue, Worker

    conn = Redis.from_url(settings.redis_url)
    Worker([Queue(queue_name, connection=conn)], connection=conn).work()


if __name__ == "__main__":
    main()
