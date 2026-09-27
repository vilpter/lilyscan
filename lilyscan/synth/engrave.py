"""Engrave ground-truth MusicXML with LilyPond: born-digital PDF plus a 300 DPI PNG."""

from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path

from lilyscan.lilypond.compile import CompileResult, compile_ly, lilypond_tool
from lilyscan.runtime.config import Settings

PNG_DPI = 300

# Seed pieces are sized to fit one page, so each raster variant is a single image.
_ONE_PAGE = "\n\\paper { page-count = #1 }\n"


class EngraveError(RuntimeError):
    pass


@dataclass
class Engraving:
    ly: Path
    pdf: Path
    png: Path
    compile: CompileResult


def engrave(musicxml: Path, out_dir: Path, settings: Settings | None = None) -> Engraving:
    s = settings or Settings.from_env()
    out_dir.mkdir(parents=True, exist_ok=True)
    ly = out_dir / "score.ly"
    proc = subprocess.run(
        [*lilypond_tool("musicxml2ly", s), "--output", str(ly), str(musicxml)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=s.lilypond_timeout_s,
        check=False,
    )
    if proc.returncode != 0 or not ly.is_file():
        raise EngraveError(f"musicxml2ly failed for {musicxml.name}:\n{proc.stderr[-2000:]}")
    ly.write_text(ly.read_text(encoding="utf-8") + _ONE_PAGE, encoding="utf-8")

    result = compile_ly(
        ly, out_dir, ("pdf", "png"), extra_args=[f"-dresolution={PNG_DPI}"], settings=s
    )
    pngs = sorted(p for p in result.outputs if p.suffix == ".png")
    pdfs = [p for p in result.outputs if p.suffix == ".pdf"]
    if not result.ok or len(pngs) != 1 or len(pdfs) != 1:
        raise EngraveError(
            f"LilyPond did not produce one PDF and one PNG page for {musicxml.name} "
            f"(pdf={len(pdfs)}, png={len(pngs)}):\n{result.log[-2000:]}"
        )
    return Engraving(ly=ly, pdf=pdfs[0], png=pngs[0], compile=result)
