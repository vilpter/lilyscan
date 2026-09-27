"""Stage 5: repair and enrichment rules applied to engine output (pure functions on the IR).

Each rule edits the score in place, records provenance on every event it changes
(``Provenance(stage="repair", rule=..., before=...)``), and returns log entries so the
job report and the review UI can show what changed and why.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import asdict, dataclass, field
from typing import Any

from lilyscan.ir.models import Score


@dataclass
class Repair:
    rule: str
    part: str
    staff: int | None
    detail: str
    measures: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


Rule = Callable[[Score], list[Repair]]


def apply_repairs(score: Score, rules: list[Rule] | None = None) -> list[Repair]:
    """Run the repair rules in order; returns everything they changed.

    Parts are merged first, so later rules see each part's whole line.
    """
    from lilyscan.repair.clefs import octave_clefs
    from lilyscan.repair.parts import merge_split_parts

    log: list[Repair] = []
    for rule in rules if rules is not None else [merge_split_parts, octave_clefs]:
        log += rule(score)
    return log
