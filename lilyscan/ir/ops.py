"""Whole-score operations on the IR."""

from __future__ import annotations

from lilyscan.ir.models import KeySignature, Score, Staff


def _key_in_force(staff: Staff) -> int:
    for m in reversed(staff.measures):
        if m.key is not None:
            return m.key.fifths
    return 0


def merge_scores(scores: list[Score]) -> Score:
    """Concatenate scores staff by staff (e.g. one engine output file per movement).

    A movement that states no key signature has none, rather than the previous one's.
    """
    if not scores:
        raise ValueError("nothing to merge")
    base = scores[0].model_copy(deep=True)
    for extra in scores[1:]:
        for (_, staff), (_, more) in zip(base.staves(), extra.staves(), strict=False):
            offset = len(staff.measures)
            carried = _key_in_force(staff)
            for i, m in enumerate(more.measures):
                m = m.model_copy(update={"index": m.index + offset})
                if i == 0 and m.key is None and carried != 0:
                    m.key = KeySignature(fifths=0)
                staff.measures.append(m)
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
