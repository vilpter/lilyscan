"""Stage 4: symbols and lines from a born-digital PDF (one that embeds a music font).

Music fonts in PDFs usually carry no Unicode mapping, so glyphs are identified by name
from the embedded font program: ``noteheads.s2`` (Emmentaler, LilyPond) or SMuFL
names such as ``noteheadBlack`` (Bravura, MuseScore, Dorico). Vector lines give the
staff lines, stems, barlines and ledger lines.

Coordinates are PDF points (1/72 inch) from the page's top-left corner. Needs the
``vector`` extra (PyMuPDF, fontTools).
"""

from __future__ import annotations

import io
from dataclasses import dataclass, field
from pathlib import Path

# Embedded music fonts recognized by name (a subset prefix like "EDSDRQ+" is ignored).
MUSIC_FONTS = ("Emmentaler", "Bravura", "Petaluma", "Leland", "Finale Maestro", "Opus")


@dataclass(frozen=True)
class Rect:
    x0: float
    y0: float
    x1: float
    y1: float

    @property
    def cx(self) -> float:
        return (self.x0 + self.x1) / 2

    @property
    def cy(self) -> float:
        return (self.y0 + self.y1) / 2

    @property
    def w(self) -> float:
        return self.x1 - self.x0

    @property
    def h(self) -> float:
        return self.y1 - self.y0


@dataclass(frozen=True)
class Glyph:
    name: str  # glyph name in its font, e.g. "noteheads.s2"
    font: str
    rect: Rect  # the glyph's box
    origin: tuple[float, float]  # pen position: noteheads sit on it vertically


@dataclass(frozen=True)
class Line:
    x0: float
    y0: float
    x1: float
    y1: float
    width: float

    @property
    def horizontal(self) -> bool:
        return abs(self.y1 - self.y0) <= 0.1 * max(1e-6, abs(self.x1 - self.x0))

    @property
    def vertical(self) -> bool:
        return abs(self.x1 - self.x0) <= 0.1 * max(1e-6, abs(self.y1 - self.y0))


@dataclass
class VectorPage:
    number: int  # 0-based
    width: float  # points
    height: float
    glyphs: list[Glyph] = field(default_factory=list)
    lines: list[Line] = field(default_factory=list)
    music_font: str | None = None  # None: no known music font on the page


def _music_font(basefont: str) -> str | None:
    name = basefont.split("+", 1)[-1]
    return next((f for f in MUSIC_FONTS if name.startswith(f)), None)


def _glyph_names(doc: object, xref: int) -> list[str] | None:
    """Glyph names by glyph id, from the embedded font program (CFF or TrueType/OpenType)."""
    from fontTools.cffLib import CFFFontSet
    from fontTools.ttLib import TTFont

    _, ext, _, buf = doc.extract_font(xref)  # type: ignore[attr-defined]
    if not buf:
        return None
    try:
        if ext == "cff":
            cff = CFFFontSet()
            cff.decompile(io.BytesIO(buf), None)
            return list(cff[cff.fontNames[0]].charset)
        return list(TTFont(io.BytesIO(buf)).getGlyphOrder())
    except Exception:  # an unreadable font program: treat the font as unmapped
        return None


def _lines(page: object) -> list[Line]:
    out: list[Line] = []
    for path in page.get_drawings():  # type: ignore[attr-defined]
        width = float(path.get("width") or 0.0)
        for item in path["items"]:
            if item[0] == "l":
                a, b = item[1], item[2]
                out.append(Line(a.x, a.y, b.x, b.y, width))
            elif item[0] == "re":
                r = item[1]
                # A thin filled rectangle is a line drawn as a box (LilyPond's staff lines,
                # stems and barlines).
                if r.height <= r.width * 0.2:
                    out.append(Line(r.x0, r.y0 + r.height / 2, r.x1, r.y0 + r.height / 2, r.height))
                elif r.width <= r.height * 0.2:
                    out.append(Line(r.x0 + r.width / 2, r.y0, r.x0 + r.width / 2, r.y1, r.width))
    return out


def read_pdf(path: Path) -> list[VectorPage]:
    """Every page's music glyphs and lines (empty glyph list when no music font is known)."""
    import pymupdf

    pages: list[VectorPage] = []
    with pymupdf.open(path) as doc:
        for number, page in enumerate(doc):
            vp = VectorPage(number, float(page.rect.width), float(page.rect.height))
            names: dict[str, list[str] | None] = {}
            for xref, _, _, basefont, *_ in page.get_fonts(full=True):
                known = _music_font(basefont)
                if known is not None:
                    names[basefont.split("+", 1)[-1]] = _glyph_names(doc, xref)
                    vp.music_font = vp.music_font or known
            for span in page.get_texttrace():
                table = names.get(span["font"])
                if not table:
                    # Italic digits in a text font: tuplet numbers, or the 8 of an octave
                    # clef (LilyPond sets both in its italic text font).
                    if "Italic" in span["font"] or "Oblique" in span["font"]:
                        for code, _, origin, bbox in span["chars"]:
                            if chr(code).isdigit():
                                vp.glyphs.append(
                                    Glyph(
                                        f"italic.{chr(code)}",
                                        span["font"],
                                        Rect(*bbox),
                                        (origin[0], origin[1]),
                                    )
                                )
                    continue
                for _, gid, origin, bbox in span["chars"]:
                    if 0 < gid < len(table):
                        vp.glyphs.append(
                            Glyph(table[gid], span["font"], Rect(*bbox), (origin[0], origin[1]))
                        )
            vp.lines = _lines(page)
            pages.append(vp)
    return pages
