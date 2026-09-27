"""Stage 4: the born-digital PDF as a correction oracle (needs the ``vector`` extra).

So far the oracle reads the tuplet numbers the PDF prints (``tuplets``). Measured and
dropped: correcting noteheads, since Audiveris's noteheads on PDF input already agree
with the PDF's glyphs 99.5% of the time.
"""

from __future__ import annotations

from pathlib import Path

from lilyscan.engine.audiveris.omr import OmrBook
from lilyscan.ir.models import Score
from lilyscan.repair import Repair


def apply_vector_oracle(score: Score, pdf: Path, book: OmrBook) -> list[Repair]:
    """Correct the engine's score from the PDF it was read from; returns the changes."""
    from lilyscan.vector.extract import read_pdf
    from lilyscan.vector.tuplets import apply_tuplets

    repairs, _ = apply_tuplets(score, read_pdf(pdf), book)
    return repairs
