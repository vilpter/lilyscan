"""Score combiner (M9): parts from finished jobs as one new score.

The combined project is built from each part's own LilyPond source, so edits made in
the review UI carry over: the generator plans the new score (collision-free variable
names, the staff layout) from the parts' IR, then each part's file is copied from its
job with its variables renamed. A part can be transposed; LilyPond's ``\\transpose``
does that in the layout, so the copied source is left as it was written.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path
from typing import Any

from lilyscan.arrange import reduce_to_piano
from lilyscan.ir.models import Pitch, Score
from lilyscan.ir.transpose import Interval, transpose_part, transpose_pitch
from lilyscan.lilypond.generate import (
    LyProject,
    PartVars,
    _plan,
    generate_project,
    scan_measure_lines,
)
from lilyscan.lilypond.notation import absolute_pitch
from lilyscan.pipeline import produce
from lilyscan.runtime.config import Settings


class CombineError(ValueError):
    pass


@dataclass(frozen=True)
class Selection:
    job: Path  # a finished job's folder (ir/score.json and ly/)
    part: str  # part id in that job's IR
    # An interval ("M2", "-m3"), or "concert" for a transposing part at sounding pitch.
    transpose: str | None = None
    name: str | None = None  # a new instrument name


def _load(job: Path) -> Score:
    path = job / "ir" / "score.json"
    if not path.is_file():
        raise CombineError(f"{job.name} has no finished score")
    return Score.model_validate_json(path.read_text(encoding="utf-8"))


def _lengths(score: Score, part_id: str) -> list[Fraction | None]:
    part = next(p for p in score.parts if p.id == part_id)
    out: list[Fraction | None] = []
    length: Fraction | None = None
    for m in part.staves[0].measures:
        if m.time is not None:
            length = m.time.measure_length
        out.append(length)
    return out


def _rename(text: str, names: dict[str, str]) -> str:
    """Rename LilyPond identifiers (whole words only)."""
    if not names:
        return text
    pattern = re.compile(r"(?<![A-Za-z])(" + "|".join(map(re.escape, names)) + r")(?![A-Za-z])")
    return pattern.sub(lambda m: names[m.group(1)], text)


def _transposed(layout: str, variables: list[str], to: str) -> str:
    """Wrap each use of these music variables in ``\\transpose c' <to>``."""
    for var in variables:
        wrapped = "\\transpose c' " + to + " \\" + var
        layout = re.sub(r"\\" + var + r"(?![A-Za-z])", wrapped.replace("\\", "\\\\"), layout)
    return layout


_TRANSPOSITION = re.compile(r"^[ \t]*\\transposition\s+\S+[ \t]*\n", re.MULTILINE)


def _interval(sel: Selection, part: Any) -> Interval | None:
    if not sel.transpose:
        return None
    if sel.transpose == "concert":
        # Written to sounding: a part in B-flat (-2) sounds a major second lower.
        semitones = part.transpose_semitones or 0
        return Interval.from_semitones(semitones) if semitones else None
    return Interval.parse(sel.transpose)


def _names(old: PartVars, new: PartVars) -> dict[str, str]:
    out: dict[str, str] = {}
    for a, b in zip(old.staves, new.staves, strict=True):
        out[a.music] = b.music
        out.update(zip(a.lyrics, b.lyrics, strict=False))
    return {k: v for k, v in out.items() if k != v}


def combine(
    selections: list[Selection],
    out: Path,
    title: str | None = None,
    settings: Settings | None = None,
    piano: list[Selection] | None = None,
) -> dict[str, Any]:
    """Write the combined job (IR, LilyPond project, QA report) under ``out``.

    ``piano``: parts reduced onto a piano grand staff below the chosen parts, as the
    accompaniment to go with them (see ``lilyscan.arrange``).
    """
    if len(selections) < 1:
        raise CombineError("choose at least one part")
    sources = [(sel, _load(sel.job)) for sel in selections]
    reduced = [(sel, _load(sel.job)) for sel in piano or []]
    combined = Score(title=title or "Combined score", source="combined")
    intervals: list[Interval | None] = []
    for k, (sel, score) in enumerate(sources, 1):
        part = next((p for p in score.parts if p.id == sel.part), None)
        if part is None:
            raise CombineError(f"{sel.job.name} has no part {sel.part}")
        interval = _interval(sel, part)
        if interval is not None:
            part = transpose_part(part, interval)
        if sel.transpose == "concert":
            part = part.model_copy(update={"transpose_semitones": None})
        intervals.append(interval)
        part = part.model_copy(deep=True, update={"id": f"P{k}", "name": sel.name or part.name})
        for staff in part.staves:
            for m in staff.measures:
                m.bbox = None  # boxes belong to the source job's pages
                for v in m.voices:
                    for e in v.events:
                        e.bbox = None
        combined.parts.append(part)
    accompanying = []
    for sel, score in reduced:
        part = next((p for p in score.parts if p.id == sel.part), None)
        if part is None:
            raise CombineError(f"{sel.job.name} has no part {sel.part}")
        interval = _interval(sel, part)
        moved = transpose_part(part, interval) if interval is not None else part
        accompanying.append(moved.model_copy(update={"name": sel.name or part.name}))
    reference = _lengths(sources[0][1], selections[0].part)
    checked = list(zip(sources, combined.parts, strict=True))
    checked += [
        ((sel, score), part) for (sel, score), part in zip(reduced, accompanying, strict=True)
    ]
    for (sel, score), part in checked:
        lengths = _lengths(score, sel.part)
        if len(lengths) != len(reference):
            name = part.name or part.id
            raise CombineError(
                f"{name} has {len(lengths)} measures, the first part {len(reference)}"
            )
        for i, (a, b) in enumerate(zip(reference, lengths, strict=True)):
            if a != b:
                name = part.name or part.id
                raise CombineError(
                    f"measure {i + 1}: {name} is {b} quarters long, the first part {a}"
                )

    if accompanying:
        combined.parts.append(reduce_to_piano(accompanying, combined.parts[0]))
    generated = generate_project(combined)
    plans = _plan(combined)
    files = dict(generated.files)
    # The piano part, if any, is generated from the IR: it has no source of its own.
    for (sel, score), plan, interval in zip(sources, plans[: len(sources)], intervals, strict=True):
        old = next(pv for pv in _plan(score) if pv.part.id == sel.part)
        source = sel.job / "ly" / "parts" / f"{old.slug}.ly"
        if not source.is_file():
            continue  # keep the part generated from the (already transposed) IR
        # The part as the user left it, in written pitch; the layout transposes it.
        text = _rename(source.read_text(encoding="utf-8"), _names(old, plan))
        if sel.transpose == "concert":
            text = _TRANSPOSITION.sub("", text)  # it now sounds as written
        files[f"parts/{plan.slug}.ly"] = text
        if interval is not None:
            c4 = Pitch(step="C", octave=4)
            to = absolute_pitch(transpose_pitch(c4, interval))
            for rel in ("layout/score.ly", f"layout/part-{plan.slug}.ly"):
                files[rel] = _transposed(files[rel], [sv.music for sv in plan.staves], to)
    project = LyProject(
        files, scan_measure_lines(files, generated.staff_vars), generated.staff_vars
    )
    report = produce(combined, out, settings, project)
    report["combined_from"] = [
        {"job": sel.job.name, "part": sel.part, "transpose": sel.transpose} for sel in selections
    ]
    if piano:
        report["piano_from"] = [
            {"job": sel.job.name, "part": sel.part, "transpose": sel.transpose} for sel in piano
        ]
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8", newline="\n")
    return report
