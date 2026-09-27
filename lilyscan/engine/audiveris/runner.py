"""Invoke the pinned Audiveris release in batch mode."""

from __future__ import annotations

import re
import subprocess
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from lilyscan.runtime.config import AUDIVERIS_OCR_LANGUAGES_KEY, Settings, parse_ocr_languages

_CONSTANT_KEY = re.compile(r"^[A-Za-z_][\w.$]*$")

# Audiveris keeps going when Tesseract is unusable and simply skips text
# recognition, which silently drops lyrics, titles, and chord names.
_OCR_PROBLEMS = (
    # No traineddata files found.
    "The collection of supported languages is empty",
    # LSTM-only ("fast") models; Audiveris needs the legacy engine components.
    "Tesseract (legacy) engine requested, but components are not present",
    # A requested language has no traineddata installed.
    "Missing support for",
)
# A step that gave up ("StepException: No system found") or crashed ("Error in reaching
# step PAGE", after an internal exception), or a crash after the steps, when the book's
# scores are assembled ("Exception occurred java.lang.NullPointerException: ...").
_STEP_ERROR = re.compile(
    r"StepException: (.+)$|java\.lang\.Exception: (Error in reaching step \w+)"
    r"|Exception occurred java\.lang\.((?!Exception:)\w+: .+)$"
)


def ocr_problems(log: str) -> list[str]:
    """Log lines showing that Audiveris ran without working OCR."""
    return [line.strip() for line in log.splitlines() if any(p in line for p in _OCR_PROBLEMS)]


def step_errors(log: str) -> list[str]:
    """Distinct Audiveris step failure messages, e.g. ``No system found``."""
    found = (
        (m[1] or m[2] or m[3]).strip()
        for line in log.splitlines()
        if (m := _STEP_ERROR.search(line))
    )
    return list(dict.fromkeys(found))


@dataclass
class AudiverisRun:
    command: list[str]
    returncode: int
    wall_s: float
    log: str
    omr_files: list[Path] = field(default_factory=list)
    mxl_files: list[Path] = field(default_factory=list)
    timed_out: bool = False

    @property
    def ok(self) -> bool:
        return self.returncode == 0 and not self.timed_out and bool(self.mxl_files)

    @property
    def ocr_problems(self) -> list[str]:
        return ocr_problems(self.log)

    @property
    def step_errors(self) -> list[str]:
        return step_errors(self.log)


def build_command(
    audiveris_bin: str,
    inputs: Sequence[Path],
    out_dir: Path,
    constants: Mapping[str, str] | None = None,
    sheets: str | None = None,
) -> list[str]:
    """Batch transcription that saves the ``.omr`` book and exports MusicXML."""
    if not inputs:
        raise ValueError("at least one input file is required")
    cmd = [audiveris_bin, "-batch", "-transcribe", "-export", "-save", "-output", str(out_dir)]
    for key, value in sorted((constants or {}).items()):
        if not _CONSTANT_KEY.match(key):
            raise ValueError(f"invalid Audiveris constant key: {key!r}")
        cmd += ["-constant", f"{key}={value}"]
    if sheets:
        cmd += ["-sheets", sheets]
    cmd.append("--")
    cmd += [str(p) for p in inputs]
    return cmd


def engine_timeout(settings: Settings, pages: int | None) -> float:
    """Seconds an engine run on ``pages`` pages may take."""
    return max(settings.audiveris_timeout_s, (pages or 0) * settings.audiveris_timeout_per_page_s)


def run_audiveris(
    inputs: Sequence[Path],
    out_dir: Path,
    constants: Mapping[str, str] | None = None,
    sheets: str | None = None,
    settings: Settings | None = None,
    ocr_languages: str | None = None,
    pages: int | None = None,
) -> AudiverisRun:
    """Transcribe ``inputs``; ``ocr_languages`` defaults to the configured spec.

    ``pages`` (when known) extends the time limit for long books: the run gets the
    larger of the configured minimum and a per-page allowance.
    """
    s = settings or Settings.from_env()
    merged = dict(constants or {})
    if AUDIVERIS_OCR_LANGUAGES_KEY in merged:
        raise ValueError("set OCR languages with ocr_languages, not as a raw constant")
    merged[AUDIVERIS_OCR_LANGUAGES_KEY] = parse_ocr_languages(ocr_languages or s.ocr_languages)
    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = build_command(s.audiveris_bin, inputs, out_dir, merged, sheets)
    start = time.monotonic()
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=engine_timeout(s, pages),
            check=False,
        )
        returncode, log, timed_out = proc.returncode, proc.stdout + proc.stderr, False
    except subprocess.TimeoutExpired as exc:
        partial = exc.stdout or ""
        text = partial.decode("utf-8", "replace") if isinstance(partial, bytes) else partial
        returncode, log, timed_out = -1, text, True
    wall = time.monotonic() - start

    (out_dir / "audiveris.log").write_text(log, encoding="utf-8")
    return AudiverisRun(
        command=cmd,
        returncode=returncode,
        wall_s=wall,
        log=log,
        omr_files=sorted(out_dir.rglob("*.omr")),
        mxl_files=sorted(out_dir.rglob("*.mxl")),
        timed_out=timed_out,
    )


def audiveris_version(settings: Settings | None = None) -> str | None:
    s = settings or Settings.from_env()
    try:
        proc = subprocess.run(
            [s.audiveris_bin, "-batch", "-version"],
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"\b(\d+\.\d+\.\d+)\b", proc.stdout + proc.stderr)
    return m[1] if m else None
