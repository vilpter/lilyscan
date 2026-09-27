"""Engrave ground-truth MusicXML with LilyPond: born-digital PDF plus a 300 DPI PNG.

Two engravers:
- ``musicxml2ly``: LilyPond's own converter (used for the generated seed corpus).
- ``lilyscan``: this project's IR and generator. More robust on real repertoire, whose
  voices can start mid-measure or overlap in ways ``musicxml2ly`` cannot handle.
"""

from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from lilyscan.ir.musicxml import load_musicxml
from lilyscan.lilypond.compile import CompileResult, compile_ly, lilypond_tool
from lilyscan.lilypond.generate import write_project
from lilyscan.runtime.config import Settings

PNG_DPI = 300
Engraver = Literal["musicxml2ly", "lilyscan"]

# Corpus pieces are sized to fit one page, so each raster variant is a single image.
_ONE_PAGE = "\n\\paper { page-count = #1 }\n"


class EngraveError(RuntimeError):
    pass


@dataclass
class Engraving:
    ly: Path
    pdf: Path
    png: Path
    compile: CompileResult


def _musicxml2ly(musicxml: Path, out_dir: Path, s: Settings) -> Path:
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
    return ly


def _lilyscan(musicxml: Path, out_dir: Path) -> Path:
    root = out_dir / "ly"
    write_project(load_musicxml(musicxml, "ground-truth"), root)
    return root / "main.ly"


def engrave(
    musicxml: Path,
    out_dir: Path,
    settings: Settings | None = None,
    engraver: Engraver = "musicxml2ly",
) -> Engraving:
    """Engrave into ``out_dir/score.pdf`` and ``out_dir/score.png`` (one page each)."""
    s = settings or Settings.from_env()
    out_dir.mkdir(parents=True, exist_ok=True)
    try:
        ly = (
            _musicxml2ly(musicxml, out_dir, s)
            if engraver == "musicxml2ly"
            else _lilyscan(musicxml, out_dir)
        )
    except subprocess.TimeoutExpired as exc:
        raise EngraveError(f"{engraver} timed out on {musicxml.name}") from exc
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
    # Both engravers end up as score.pdf / score.png, which the corpus expects.
    pdf, png = pdfs[0], pngs[0]
    if pdf.name != "score.pdf":
        pdf = pdf.replace(out_dir / "score.pdf")
    if png.name != "score.png":
        png = png.replace(out_dir / "score.png")
    return Engraving(ly=ly, pdf=pdf, png=png, compile=result)
