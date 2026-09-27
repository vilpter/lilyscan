"""Single home for versions, paths, and thresholds.

Every recognition threshold lives here and is expressed in staff-space units,
never pixels. Environment-dependent settings are read once via ``Settings.from_env``.
"""

from __future__ import annotations

import os
import re
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

# OCR languages, as Tesseract codes joined with "+". The defaults cover lyrics in
# English, Latin, German, and French; the Audiveris image bakes these in and
# downloads any others listed in LILYSCAN_OCR_LANGUAGES at startup.
DEFAULT_OCR_LANGUAGES = "eng+lat+deu+fra"
_OCR_SPEC = re.compile(r"^[a-z]{3}(?:_[a-z]+)?(?:\+[a-z]{3}(?:_[a-z]+)?)*$")

# Audiveris silently ignores unknown -constant keys, so every key used here is
# covered by a behavioural test against the pinned release.
AUDIVERIS_OCR_LANGUAGES_KEY = "org.audiveris.omr.text.Language.defaultSpecification"

# Stage 1 (input preparation). Lengths are in staff spaces (interlines) unless noted.
# Target interline in pixels for the page given to Audiveris; pages outside the range
# are rescaled to the middle of it.
PREPARE_INTERLINE_RANGE = (16.0, 32.0)
# Below this interline (pixels, after rescaling is ruled out) a page is too coarse to read.
PREPARE_MIN_INTERLINE = 9.0
# Staff lines left curved after dewarping (RMS deviation from straight): warn above this.
PREPARE_MAX_CURVATURE = 0.2
# Horizontal line segments at least this long (as a share of the page width) are
# candidate staff lines for dewarping.
PREPARE_LINE_MIN_WIDTH = 0.25
# The page must cover at least this share of a photo for perspective correction.
PREPARE_MIN_PAGE_AREA = 0.3


def parse_ocr_languages(spec: str) -> str:
    """Normalize and validate an OCR language spec such as ``eng+lat+deu``."""
    codes = [c for c in spec.strip().lower().replace(",", "+").split("+") if c]
    normalized = "+".join(dict.fromkeys(codes))
    if not _OCR_SPEC.match(normalized):
        raise ValueError(f"invalid OCR language spec {spec!r}; expected e.g. 'eng+lat+deu'")
    return normalized


def _web_dir(value: str | None) -> Path | None:
    """LILYSCAN_WEB_DIR, else services/web next to the service package (source layout)."""
    if value:
        return Path(value).resolve()
    default = Path(__file__).resolve().parents[2] / "services" / "web"
    return default if default.is_dir() else None


def _dispatch(value: str) -> str:
    value = value.strip().lower() or "rq"
    if value not in ("rq", "inline"):
        raise ValueError(f"LILYSCAN_DISPATCH must be rq or inline, got {value!r}")
    return value


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    redis_url: str
    lilypond_bin: str
    audiveris_bin: str
    lilypond_timeout_s: float
    audiveris_timeout_s: float
    ocr_languages: str = DEFAULT_OCR_LANGUAGES
    dispatch: str = "rq"  # "rq" (Redis workers) or "inline" (in the API process)
    web_dir: Path | None = None  # the web UI's static files; None disables it

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
            ocr_languages=parse_ocr_languages(
                e.get("LILYSCAN_OCR_LANGUAGES", DEFAULT_OCR_LANGUAGES)
            ),
            dispatch=_dispatch(e.get("LILYSCAN_DISPATCH", "rq")),
            web_dir=_web_dir(e.get("LILYSCAN_WEB_DIR")),
        )
