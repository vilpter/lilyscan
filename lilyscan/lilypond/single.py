"""A LilyPond project as one file: the score and, when there are several parts, each part.

The editable project (``generate.py``) keeps each part's music in a file of its own, so
that edits map back to measures and the score combiner can reuse a part's source. To
share the music, the same project is written as one file: the header, every part's
variables, then a ``\\book`` for the score and one for each part, each with its own
``\\bookOutputSuffix``. Compiling ``piece.ly`` gives ``piece-score.pdf`` (and its MIDI),
``piece-violin-1.pdf``, and so on. A project with a single part is just its score.

Parts are engraved as players read them: consecutive whole-measure rests are shown as
one multi-measure rest. The music writes each measure on a line of its own (``R1 | R1 |``),
which LilyPond's ``skipBars`` does not join, so a part's book first merges such runs into
one rest (``R1*2``) with ``\\mergeFullBarRests``, defined once at the top of the file.
"""

from __future__ import annotations

import re
from collections.abc import Mapping

_INCLUDE = re.compile(r'^[ \t]*\\include[ \t]+"([^"]+)"[ \t]*\n?', re.M)
_VERSION = re.compile(r'^[ \t]*\\version[ \t]+"[^"]*"[ \t]*\n?', re.M)
_HEADER = re.compile(r"^\\header \{\n.*?^\}\n", re.M | re.S)
_STRING = r'"(?:[^"\\]|\\.)*"'
# A name as generated: a string, or a long one wrapped into a column of strings.
_INSTRUMENT = re.compile(
    r"\binstrumentName = (" + _STRING + r"|\\markup \\center-column \{ (?:" + _STRING + r" )+\})"
)
_PART_LAYOUT = re.compile(r"^layout/part-(.+)\.ly$")

# A part's layout: consecutive whole-measure rests as one multi-measure rest.
PART_LAYOUT = "\\layout { \\context { \\Score skipBars = ##t } }"

# Whole-measure rests of the same length that follow one another (only bar checks
# between them) as one rest of their total length: R1 | R1 | R1 | becomes R1*3 |. Any
# other music between them (a clef, key or time change, a \bar, a mark) ends the run.
MERGE_RESTS = r"""mergeFullBarRests =
#(define-music-function (music) (ly:music?)
   (define (name-of m) (ly:music-property m 'name))
   (define (plain-rest? m)
     (and (eq? (name-of m) 'MultiMeasureRestMusic)
          (null? (ly:music-property m 'articulations))))
   (define (bar-check? m) (memq (name-of m) '(BarCheck BarCheckEvent)))
   (define (merge elements)
     (let loop ((in elements) (out '()))
       (cond
        ((null? in) (reverse out))
        ((plain-rest? (car in))
         (let* ((first (car in)) (d (ly:music-property first 'duration)))
           (let run ((rest (cdr in)) (n 1))
             (let ((next (and (pair? rest) (bar-check? (car rest)) (cdr rest))))
               (if (and (pair? next) (plain-rest? (car next))
                        (equal? (ly:music-property (car next) 'duration) d))
                   (run (cdr next) (1+ n))
                   (begin
                     (if (> n 1)
                         (set! (ly:music-property first 'duration)
                               (ly:make-duration (ly:duration-log d) (ly:duration-dot-count d)
                                                 (* n (ly:duration-scale d)))))
                     (loop rest (cons first out))))))))
        (else (loop (cdr in) (cons (car in) out))))))
   (music-map
    (lambda (m)
      (if (eq? (name-of m) 'SequentialMusic)
          (set! (ly:music-property m 'elements) (merge (ly:music-property m 'elements))))
      m)
    (ly:music-deep-copy music)))"""

_VOICE_MUSIC = re.compile(r'(\\new Voice = "[^"]*" )\\([A-Za-z]+)')


def part_score(layout: str) -> str:
    """A part's ``\\score`` (a layout file's body) as players read it: a run of
    whole-measure rests is one multi-measure rest. Only for a one-staff part: in a piano
    part one hand may rest while the other plays, and its rests stay measure by measure.
    The file must define ``\\mergeFullBarRests`` (:data:`MERGE_RESTS`)."""
    score = layout.replace("\\layout { }", PART_LAYOUT, 1)
    if "PianoStaff" not in score:
        score = _VOICE_MUSIC.sub(r"\1\\mergeFullBarRests \\\2", score)
    return score


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


def _one_line(name: str) -> str:
    """A generated instrument name as one string, for a part book's header: a wrapped
    name's lines joined by spaces."""
    if not name.startswith("\\markup"):
        return name
    return '"' + " ".join(line[1:-1] for line in re.findall(_STRING, name)) + '"'


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
        instrument = f"instrument = {_one_line(name[1])}" if name else None
        books.append(book(slug, part_score(_body(layout)), instrument))
    return "\n".join([*out, MERGE_RESTS, "", "\n\n".join(books), ""])
