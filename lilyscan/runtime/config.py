"""Single home for versions, paths, and thresholds.

Every recognition threshold lives here and is expressed in staff-space units,
never pixels. Environment-dependent settings are read once via ``Settings.from_env``.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

# Pinned toolchain versions (decisions D5 and D7). Bumping either one is its own
# change: update the matching Dockerfile build arg and re-run the eval harness.
LILYPOND_VERSION = "2.26.0"
AUDIVERIS_VERSION = "5.11.0"

# Queue names.
PIPELINE_QUEUE = "pipeline"
ENGINE_QUEUE = "engine"


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    redis_url: str
    lilypond_bin: str
    audiveris_bin: str
    lilypond_timeout_s: float
    audiveris_timeout_s: float

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> Settings:
        e = os.environ if env is None else env
        return cls(
            data_dir=Path(e.get("LILYSCAN_DATA_DIR", "data")).resolve(),
            redis_url=e.get("REDIS_URL", "redis://localhost:6379/0"),
            lilypond_bin=e.get("LILYPOND_BIN", "lilypond"),
            audiveris_bin=e.get("AUDIVERIS_BIN", "audiveris"),
            lilypond_timeout_s=float(e.get("LILYPOND_TIMEOUT_S", "120")),
            audiveris_timeout_s=float(e.get("AUDIVERIS_TIMEOUT_S", "900")),
        )
