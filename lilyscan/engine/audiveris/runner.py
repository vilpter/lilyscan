"""Invoke the pinned Audiveris release in batch mode."""

from __future__ import annotations

import re
import subprocess
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from lilyscan.runtime.config import Settings

_CONSTANT_KEY = re.compile(r"^[A-Za-z_][\w.$]*$")

# Audiveris keeps going when Tesseract is unusable and simply skips text
# recognition, which silently drops lyrics, titles, and chord names.
_OCR_PROBLEMS = (
    # No traineddata files found.
    "The collection of supported languages is empty",
    # LSTM-only ("fast") models; Audiveris needs the legacy engine components.
    "Tesseract (legacy) engine requested, but components are not present",
)


def ocr_problems(log: str) -> list[str]:
    """Log lines showing that Audiveris ran without working OCR."""
    return [line.strip() for line in log.splitlines() if any(p in line for p in _OCR_PROBLEMS)]


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


def run_audiveris(
    inputs: Sequence[Path],
    out_dir: Path,
    constants: Mapping[str, str] | None = None,
    sheets: str | None = None,
    settings: Settings | None = None,
) -> AudiverisRun:
    s = settings or Settings.from_env()
    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = build_command(s.audiveris_bin, inputs, out_dir, constants, sheets)
    start = time.monotonic()
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=s.audiveris_timeout_s,
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
