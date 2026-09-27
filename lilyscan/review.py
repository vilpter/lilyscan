"""The review model behind the web UI: every measure with its page box, its line in
the LilyPond source, its events' boxes and confidence, and its problems, ranked so
the measures most likely to need a fix come first.
"""

from __future__ import annotations

from typing import Any

from lilyscan.ir.models import Score
from lilyscan.lilypond.generate import LOW_CONFIDENCE, LyProject
from lilyscan.qa.checks import QaReport

# How much each kind of problem pushes a measure up the review list.
WEIGHTS = {
    "compile": 5.0,  # Q1: LilyPond error on this measure's line
    "bar-check": 3.0,  # Q2
    "rhythm": 3.0,  # Q3
    "range": 2.0,  # Q4
    "low-confidence": 1.0,  # per event below LOW_CONFIDENCE
}


def _bbox(b: Any) -> dict[str, float] | None:
    if b is None:
        return None
    return {
        "page": b.page,
        "x": round(b.x, 1),
        "y": round(b.y, 1),
        "w": round(b.w, 1),
        "h": round(b.h, 1),
    }


def build_review(score: Score, project: LyProject, qa: QaReport) -> dict[str, Any]:
    """Measures in reading order plus the ids of the ones to review, most urgent first."""
    # (part, staff, measure number) -> (file, line) of the measure's music line.
    where: dict[tuple[str, int, str], tuple[str, int]] = {}
    for (rel, line), (part_id, staff_no, number) in project.measure_lines.items():
        where.setdefault((part_id, staff_no, number), (rel, line))

    issues: dict[tuple[str, int, str], list[dict[str, Any]]] = {}

    def add(part: Any, staff: Any, measure: Any, kind: str, detail: str) -> None:
        if part is None or staff is None or measure is None:
            return
        issues.setdefault((str(part), int(staff), str(measure)), []).append(
            {"kind": kind, "detail": detail}
        )

    by_line = {v: k for k, v in where.items()}
    for check in qa.checks:
        for d in check.details:
            if check.id == "Q1" and not d.get("internal"):
                key = by_line.get((d.get("file"), d.get("line")))  # type: ignore[arg-type]
                if key:
                    add(*key, "compile", d.get("message", ""))
            elif check.id == "Q2":
                add(
                    d.get("part"),
                    d.get("staff"),
                    d.get("measure"),
                    "bar-check",
                    d.get("message", ""),
                )
            elif check.id == "Q3":
                add(
                    d.get("part"),
                    d.get("staff"),
                    d.get("measure"),
                    "rhythm",
                    f"voice {d.get('voice')}: {d.get('actual')} of {d.get('expected')} quarters",
                )
            elif check.id == "Q4":
                add(
                    d.get("part"),
                    d.get("staff"),
                    d.get("measure"),
                    "range",
                    f"{d.get('pitch')} out of range",
                )

    measures: list[dict[str, Any]] = []
    for part, staff in score.staves():
        for m in staff.measures:
            number = m.number or str(m.index + 1)
            key = (part.id, staff.number, number)
            events = []
            low = 0
            confidences = []
            for v in m.voices:
                for e in v.events:
                    if e.confidence is not None:
                        confidences.append(e.confidence)
                        if e.confidence < LOW_CONFIDENCE:
                            low += 1
                    events.append(
                        {
                            "kind": e.kind,
                            "offset": str(e.offset),
                            "pitches": [p.label for p in e.pitches],
                            "confidence": None if e.confidence is None else round(e.confidence, 3),
                            "bbox": _bbox(e.bbox),
                        }
                    )
            found = list(issues.get(key, []))
            if low:
                found.append(
                    {"kind": "low-confidence", "detail": f"{low} event(s) below {LOW_CONFIDENCE}"}
                )
            priority = sum(WEIGHTS[i["kind"]] for i in found if i["kind"] != "low-confidence")
            priority += WEIGHTS["low-confidence"] * low
            if confidences:
                priority += 1.0 - min(confidences)
            location = where.get(key)
            src_file = location[0] if location else None
            src_line = location[1] if location else None
            measures.append(
                {
                    "id": f"{part.id}/{staff.number}/{m.index}",
                    "part": part.id,
                    "part_name": part.name or part.id,
                    "staff": staff.number,
                    "index": m.index,
                    "number": number,
                    "bbox": _bbox(m.bbox),
                    "file": src_file,
                    "line": src_line,
                    "events": events,
                    "issues": found,
                    "min_confidence": round(min(confidences), 3) if confidences else None,
                    "priority": round(priority, 3),
                }
            )
    ranked = sorted(
        (m for m in measures if m["issues"]), key=lambda m: (-m["priority"], m["index"])
    )
    return {"measures": measures, "review": [m["id"] for m in ranked]}
