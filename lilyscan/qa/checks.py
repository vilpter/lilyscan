"""Quality checks Q1-Q5 (design doc, Stage 8).

Q1 compiles       the generated project compiles without errors
Q2 bar checks     no bar-check warnings, each mapped back to part/staff/measure
Q3 rhythm         every voice fills its measure (pickup and its complement excepted)
Q4 pitch range    notes inside the practical range of the part's instrument
Q5 alignment      all staves have the same measures and barline structure
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass, field
from fractions import Fraction
from pathlib import Path
from typing import Any

from lilyscan.ir.models import Part, Score
from lilyscan.lilypond.compile import CompileResult, compile_ly
from lilyscan.lilypond.generate import LyProject
from lilyscan.runtime.config import Settings


@dataclass
class CheckResult:
    id: str
    name: str
    passed: bool
    details: list[dict[str, Any]] = field(default_factory=list)
    summary: str = ""

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class QaReport:
    checks: list[CheckResult]
    outputs: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return all(c.passed for c in self.checks)

    def check(self, check_id: str) -> CheckResult:
        return next(c for c in self.checks if c.id == check_id)

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "checks": [c.to_dict() for c in self.checks],
            "outputs": self.outputs,
        }


# --- Q1 / Q2 -------------------------------------------------------------------


def _relative(file: str | None, root: Path) -> str | None:
    if file is None:
        return None
    path = Path(file)
    if path.is_absolute():
        try:
            return path.resolve().relative_to(root.resolve()).as_posix()
        except ValueError:
            return path.as_posix()
    return path.as_posix().removeprefix("./")


def compile_checks(
    root: Path, project: LyProject, settings: Settings | None = None
) -> tuple[CheckResult, CheckResult, CompileResult]:
    result = compile_ly(root / "main.ly", root, ("pdf",), settings=settings)
    errors = [
        {"file": _relative(d.file, root), "line": d.line, "message": d.message}
        for d in result.errors
    ]
    internal = [d for d in result.warnings if d.is_internal]
    details = errors + [
        {"file": _relative(d.file, root), "line": d.line, "message": d.message, "internal": True}
        for d in internal
    ]
    summary = "compiled" if result.ok else f"{len(errors)} error(s)"
    if internal:
        summary += f" ({len(internal)} LilyPond internal warning(s))"
    q1 = CheckResult("Q1", "compiles", passed=result.ok, details=details, summary=summary)
    failures = []
    for d in result.warnings:
        if not d.is_barcheck:
            continue
        rel = _relative(d.file, root)
        where = project.measure_lines.get((rel, d.line)) if rel and d.line else None
        failures.append(
            {
                "file": rel,
                "line": d.line,
                "message": d.message,
                "part": where[0] if where else None,
                "staff": where[1] if where else None,
                "measure": where[2] if where else None,
            }
        )
    q2 = CheckResult(
        "Q2",
        "bar checks",
        passed=result.ok and not failures,
        details=failures,
        summary=f"{len(failures)} failing bar check(s)",
    )
    return q1, q2, result


# --- Q3 ------------------------------------------------------------------------


def rhythm_check(score: Score) -> CheckResult:
    problems = []
    for part, staff in score.staves():
        length: Fraction | None = None
        pickup = Fraction(0)
        for m in staff.measures:
            if m.time is not None:
                length = m.time.measure_length
            if length is None:
                continue
            is_last = m is staff.measures[-1]
            for v in m.voices:
                actual = v.duration()
                if actual == length:
                    continue
                if m.index == 0 and actual < length and (m.implicit or m.number == "0"):
                    pickup = actual  # anacrusis, marked as such in the source
                    continue
                if is_last and pickup and actual + pickup == length:
                    continue  # final measure completing the pickup
                problems.append(
                    {
                        "part": part.id,
                        "staff": staff.number,
                        "measure": m.number or str(m.index + 1),
                        "voice": v.number,
                        "expected": str(length),
                        "actual": str(actual),
                    }
                )
    return CheckResult(
        "Q3",
        "rhythmic integrity",
        passed=not problems,
        details=problems,
        summary=f"{len(problems)} voice-measure(s) not matching the time signature",
    )


# --- Q4 ------------------------------------------------------------------------

# Practical written ranges as MIDI numbers (lowest, highest), matched on part names.
RANGES: list[tuple[str, tuple[int, int]]] = [
    (r"piccolo", (74, 108)),
    (r"flute|fl\.", (59, 98)),
    (r"oboe|ob\.", (58, 93)),
    (r"clarinet|cl\.", (52, 96)),
    (r"bassoon|fag|bsn", (34, 76)),
    (r"horn|cor\b|hn\.", (42, 84)),
    (r"trumpet|tpt|trp", (54, 86)),
    (r"trombone|tbn", (40, 77)),
    (r"tuba", (26, 65)),
    (r"violin|vln|vn\.", (55, 103)),
    (r"viola|vla", (48, 88)),
    (r"cello|vc\.|vlc", (36, 81)),
    (r"contrabass|double bass|kontrabass|cb\.|db\.", (28, 67)),
    (r"guitar|gtr", (52, 88)),
    (r"soprano|^s\.?$", (59, 84)),
    (r"alto|^a\.?$", (53, 77)),
    (r"tenor|^t\.?$", (47, 72)),
    (r"bass|^b\.?$", (40, 65)),
    (r"piano|pno|keyboard|organ|harp", (21, 108)),
]
DEFAULT_RANGE = (21, 108)


def instrument_range(part: Part) -> tuple[tuple[int, int], str]:
    for label in (part.name or "", part.abbreviation or ""):
        for pattern, rng in RANGES:
            if re.search(pattern, label.strip(), re.I):
                return rng, pattern
    return DEFAULT_RANGE, "default"


def range_check(score: Score) -> CheckResult:
    suspects = []
    for part, staff in score.staves():
        (lo, hi), matched = instrument_range(part)
        for m in staff.measures:
            for v in m.voices:
                for e in v.events:
                    for p in e.pitches:
                        if not lo <= p.midi <= hi:
                            suspects.append(
                                {
                                    "part": part.id,
                                    "staff": staff.number,
                                    "measure": m.number or str(m.index + 1),
                                    "offset": str(e.offset),
                                    "pitch": p.label,
                                    "range": [lo, hi],
                                    "rule": matched,
                                }
                            )
    return CheckResult(
        "Q4",
        "pitch range",
        passed=not suspects,
        details=suspects,
        summary=f"{len(suspects)} note(s) outside the instrument's practical range",
    )


# --- Q5 ------------------------------------------------------------------------


def alignment_check(score: Score) -> CheckResult:
    staves = score.staves()
    problems: list[dict[str, Any]] = []
    if staves:
        counts = {f"{p.id}/{s.number}": len(s.measures) for p, s in staves}
        if len(set(counts.values())) > 1:
            problems.append({"kind": "measure-count", "counts": counts})
        reference_part, reference = staves[0]
        for part, staff in staves[1:]:
            for a, b in zip(reference.measures, staff.measures, strict=False):
                bar_a = a.right_barline.model_dump() if a.right_barline else None
                bar_b = b.right_barline.model_dump() if b.right_barline else None
                if bar_a != bar_b:
                    problems.append(
                        {
                            "kind": "barline",
                            "measure": a.number or str(a.index + 1),
                            "staves": [
                                f"{reference_part.id}/{reference.number}",
                                f"{part.id}/{staff.number}",
                            ],
                        }
                    )
    return CheckResult(
        "Q5",
        "cross-part alignment",
        passed=not problems,
        details=problems,
        summary=f"{len(problems)} alignment problem(s)",
    )


def run_checks(
    score: Score, root: Path, project: LyProject, settings: Settings | None = None
) -> QaReport:
    q1, q2, compiled = compile_checks(root, project, settings)
    outputs = [p.relative_to(root.resolve()).as_posix() for p in compiled.outputs]
    # LilyPond writes main.midi on Linux and main.mid on Windows.
    outputs += [name for name in ("main.midi", "main.mid") if (root / name).is_file()]
    return QaReport(
        checks=[q1, q2, rhythm_check(score), range_check(score), alignment_check(score)],
        outputs=outputs,
    )
