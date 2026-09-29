"""A LilyPond project as one file: the score and, when there are several parts, each part.

The editable project (``generate.py``) keeps each part's music in a file of its own, so
that edits map back to measures and the score combiner can reuse a part's source. To
share the music, the same project is written as one file: the header, every part's
variables, then a ``\\book`` for the score and one for each part, each with its own
``\\bookOutputSuffix``. Compiling ``piece.ly`` gives ``piece-score.pdf`` (and its MIDI),
``piece-violin-1.pdf``, and so on. A project with a single part is just its score.

Parts are engraved as players read them: consecutive whole-measure rests are shown as
one multi-measure rest.
"""

from __future__ import annotations

import re
from collections.abc import Mapping

_INCLUDE = re.compile(r'^[ \t]*\\include[ \t]+"([^"]+)"[ \t]*\n?', re.M)
_VERSION = re.compile(r'^[ \t]*\\version[ \t]+"[^"]*"[ \t]*\n?', re.M)
_HEADER = re.compile(r"^\\header \{\n.*?^\}\n", re.M | re.S)
_INSTRUMENT = re.compile(r'\binstrumentName = ("(?:[^"\\]|\\.)*")')
_PART_LAYOUT = re.compile(r"^layout/part-(.+)\.ly$")

# A part's layout: consecutive whole-measure rests as one multi-measure rest.
PART_LAYOUT = "\\layout { \\context { \\Score skipBars = ##t } }"


def _body(text: str) -> str:
    """A project file without its ``\\version`` and ``\\include`` lines."""
    return _INCLUDE.sub("", _VERSION.sub("", text, count=1)).strip("\n")


def _indented(text: str) -> str:
    return "\n".join(f"  {line}" if line else line for line in text.split("\n"))


def book(suffix: str, score: str, header: str | None = None) -> str:
    """A ``\\book`` holding one score, written to ``<file>-<suffix>.pdf``."""
    lines = ["\\book {", f'  \\bookOutputSuffix "{suffix}"']
    if header:
        lines.append(f"  \\header {{ {header} }}")
    lines += [_indented(score), "}"]
    return "\n".join(lines)


def single_file(files: Mapping[str, str]) -> str:
    """The project in ``files`` (relative path -> text, as generated) as one file."""
    main = files["main.ly"]
    version = _VERSION.search(main)
    header = _HEADER.search(main)
    included = [m[1] for m in _INCLUDE.finditer(main)]
    out = [version[0].strip() if version else "", ""]
    if header:
        out += [header[0].strip("\n"), ""]
    # The music, in the order main.ly includes it: each part's variables, then chords.
    out += [f"{_body(files[rel])}\n" for rel in included if not rel.startswith("layout/")]
    score = _body(files["layout/score.ly"])
    slugs = [rel[len("parts/") : -len(".ly")] for rel in included if rel.startswith("parts/")]
    parts = [s for s in slugs if f"layout/part-{s}.ly" in files]
    parts += sorted(  # layouts main.ly does not name (edited projects): keep them too
        m[1] for rel in files if (m := _PART_LAYOUT.match(rel)) and m[1] not in parts
    )
    if len(parts) < 2:
        return "\n".join([*out, score, ""])
    books = [book("score", score)]
    for slug in parts:
        layout = files[f"layout/part-{slug}.ly"]
        name = _INSTRUMENT.search(layout)
        score = _body(layout).replace("\\layout { }", PART_LAYOUT, 1)
        books.append(book(slug, score, f"instrument = {name[1]}" if name else None))
    return "\n".join([*out, "\n\n".join(books), ""])
