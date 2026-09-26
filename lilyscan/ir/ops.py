"""Whole-score operations on the IR."""

from __future__ import annotations

from lilyscan.ir.models import Score


def merge_scores(scores: list[Score]) -> Score:
    """Concatenate scores staff by staff (e.g. one engine output file per movement)."""
    if not scores:
        raise ValueError("nothing to merge")
    base = scores[0].model_copy(deep=True)
    for extra in scores[1:]:
        for (_, staff), (_, more) in zip(base.staves(), extra.staves(), strict=False):
            offset = len(staff.measures)
            for m in more.measures:
                staff.measures.append(m.model_copy(update={"index": m.index + offset}))
    return base


def counts(score: Score) -> dict[str, int]:
    staves = score.staves()
    return {
        "parts": len(score.parts),
        "staves": len(staves),
        "measures": max((len(s.measures) for _, s in staves), default=0),
        "notes": sum(
            len(e.notes) for _, s in staves for m in s.measures for v in m.voices for e in v.events
        ),
    }
