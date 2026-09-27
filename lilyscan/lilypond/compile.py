"""Run the LilyPond binary and parse its diagnostics."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path

from lilyscan.runtime.config import LILYPOND_VERSION, Settings

_LOCATED = re.compile(
    r"^(?P<file>.+?):(?P<line>\d+):(?P<column>\d+): "
    r"(?P<severity>fatal error|programming error|error|warning): (?P<message>.*)$"
)
_UNLOCATED = re.compile(
    r"^(?P<severity>fatal error|programming error|error|warning): (?P<message>.*)$"
)

FORMATS = ("pdf", "svg", "png")


@dataclass(frozen=True)
class Diagnostic:
    severity: str
    message: str
    file: str | None = None
    line: int | None = None
    column: int | None = None

    @property
    def is_error(self) -> bool:
        return self.severity != "warning"

    @property
    def is_barcheck(self) -> bool:
        # 2.26 says "bar check failed"; 2.24 said "barcheck failed".
        return self.message.startswith(("bar check failed", "barcheck failed"))


@dataclass
class CompileResult:
    returncode: int
    log: str
    diagnostics: list[Diagnostic] = field(default_factory=list)
    outputs: list[Path] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.returncode == 0 and not self.errors

    @property
    def errors(self) -> list[Diagnostic]:
        return [d for d in self.diagnostics if d.is_error]

    @property
    def warnings(self) -> list[Diagnostic]:
        return [d for d in self.diagnostics if not d.is_error]


def parse_diagnostics(log: str) -> list[Diagnostic]:
    out: list[Diagnostic] = []
    for raw in log.splitlines():
        line = raw.rstrip()
        if m := _LOCATED.match(line):
            out.append(
                Diagnostic(
                    severity=m["severity"],
                    message=m["message"],
                    file=m["file"],
                    line=int(m["line"]),
                    column=int(m["column"]),
                )
            )
        elif m := _UNLOCATED.match(line):
            out.append(Diagnostic(severity=m["severity"], message=m["message"]))
    return out


def compile_ly(
    source: Path,
    out_dir: Path,
    formats: Sequence[str] = ("pdf",),
    *,
    point_and_click: bool = False,
    extra_args: Sequence[str] = (),
    settings: Settings | None = None,
) -> CompileResult:
    """Compile ``source`` into ``out_dir``; output files share the source's stem.

    ``extra_args`` are passed to every run, e.g. ``["-dresolution=300"]`` for PNGs.
    """
    unknown = set(formats) - set(FORMATS)
    if unknown:
        raise ValueError(f"unsupported formats: {sorted(unknown)}")
    s = settings or Settings.from_env()
    # LilyPond runs from the source's directory, so relative paths must be resolved first.
    source = source.resolve()
    out_dir = out_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    base = out_dir / source.stem

    # The PostScript backend (PDF/PNG) and the SVG backend need separate runs.
    runs = [[f"--{f}" for f in formats if f != "svg"]]
    if "svg" in formats:
        runs.append(["--svg"])
    runs = [r for r in runs if r]

    returncode, log = 0, ""
    for flags in runs:
        cmd = [s.lilypond_bin, "--loglevel=WARNING", f"--output={base}", *flags, *extra_args]
        if not point_and_click:
            cmd.append("-dno-point-and-click")
        cmd.append(str(source))
        try:
            proc = subprocess.run(
                cmd,
                cwd=source.parent,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=s.lilypond_timeout_s,
                check=False,
            )
        except subprocess.TimeoutExpired:
            timeout_msg = f"lilypond timed out after {s.lilypond_timeout_s:.0f}s"
            return CompileResult(-1, timeout_msg, [Diagnostic("fatal error", timeout_msg)])
        returncode = returncode or proc.returncode
        log += proc.stderr + proc.stdout
        if proc.returncode != 0:
            break

    # Each run reports the same source diagnostics; keep each one once.
    diagnostics = list(dict.fromkeys(parse_diagnostics(log)))
    outputs = sorted(p for p in out_dir.glob(f"{source.stem}*") if p.suffix.lstrip(".") in formats)
    return CompileResult(returncode, log, diagnostics, outputs)


def lilypond_tool(tool: str, settings: Settings | None = None) -> list[str]:
    """Command prefix for a script shipped with LilyPond, such as ``musicxml2ly``.

    Linux builds ship executable scripts next to ``lilypond``; Windows builds ship
    ``<tool>.py`` plus a bundled ``python.exe``.
    """
    s = settings or Settings.from_env()
    exe = shutil.which(s.lilypond_bin)
    if exe is None:
        raise FileNotFoundError(f"LilyPond not found ({s.lilypond_bin})")
    bin_dir = Path(exe).resolve().parent
    script = bin_dir / tool
    if script.is_file() and os.name != "nt":
        return [str(script)]
    py_script = bin_dir / f"{tool}.py"
    if py_script.is_file():
        for name in ("python.exe", "python3", "python"):
            if (bin_dir / name).is_file():
                return [str(bin_dir / name), str(py_script)]
        return [sys.executable, str(py_script)]
    if script.is_file():
        return [sys.executable, str(script)]
    raise FileNotFoundError(f"{tool} not found next to {exe}")


def lilypond_version(settings: Settings | None = None) -> str | None:
    s = settings or Settings.from_env()
    try:
        proc = subprocess.run(
            [s.lilypond_bin, "--version"], capture_output=True, text=True, timeout=30, check=False
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"GNU LilyPond (\S+)", proc.stdout)
    return m[1] if m else None


HELLO_WORLD = f"""\\version "{LILYPOND_VERSION}"

\\header {{ title = "lilyscan self-test" tagline = ##f }}

\\fixed c' {{
  \\key g \\major
  \\time 3/4
  g4 a b | c'2. | b4 a g | d'2. \\bar "|."
}}
"""
