"""Staves from a PDF's vector lines: five evenly spaced horizontal lines drawn together."""

from __future__ import annotations

from dataclasses import dataclass

from lilyscan.vector.extract import Line, VectorPage

# Staff lines are at least this share of the page width (a staff spans most of a system).
_MIN_LENGTH = 0.15
# Lines of one staff: spacing equal to within this share of the interline.
_SPACING_TOLERANCE = 0.15


@dataclass(frozen=True)
class VectorStaff:
    lines: tuple[float, float, float, float, float]  # y of each line, top to bottom
    x0: float
    x1: float

    @property
    def interline(self) -> float:
        return (self.lines[4] - self.lines[0]) / 4

    @property
    def top(self) -> float:
        return self.lines[0]

    @property
    def bottom(self) -> float:
        return self.lines[4]

    def step(self, y: float) -> int:
        """Staff position of ``y`` in half spaces above the bottom line (0 = bottom line,
        8 = top line, negative below the staff)."""
        return round((self.bottom - y) / (self.interline / 2))


def find_staves(page: VectorPage) -> list[VectorStaff]:
    """Staves on a page, top to bottom (then left to right)."""
    long = sorted(
        (
            ln
            for ln in page.lines
            if ln.horizontal and abs(ln.x1 - ln.x0) >= _MIN_LENGTH * page.width
        ),
        key=lambda ln: ((ln.y0 + ln.y1) / 2, min(ln.x0, ln.x1)),
    )
    staves: list[VectorStaff] = []
    used: set[int] = set()
    for i in range(len(long)):
        if i in used:
            continue
        group = [i]
        for j in range(i + 1, len(long)):
            if j in used or len(group) == 5:
                continue
            if not _overlap(long[group[0]], long[j]):
                continue
            ys = [(long[k].y0 + long[k].y1) / 2 for k in group]
            y = (long[j].y0 + long[j].y1) / 2
            gap = y - ys[-1]
            if gap <= 0.1:
                continue  # the same line drawn twice
            if len(group) >= 2:
                spacing = ys[1] - ys[0]
                if abs(gap - spacing) > _SPACING_TOLERANCE * spacing:
                    if gap > spacing:
                        break
                    continue
            elif gap > 0.05 * page.height:
                break
            group.append(j)
        if len(group) == 5:
            used.update(group)
            ys5 = tuple(sorted((long[k].y0 + long[k].y1) / 2 for k in group))
            x0 = max(min(long[k].x0, long[k].x1) for k in group)
            x1 = min(max(long[k].x0, long[k].x1) for k in group)
            staves.append(VectorStaff(ys5, x0, x1))  # type: ignore[arg-type]
    return sorted(staves, key=lambda s: (s.top, s.x0))


def _overlap(a: Line, b: Line) -> bool:
    a0, a1 = sorted((a.x0, a.x1))
    b0, b1 = sorted((b.x0, b.x1))
    return min(a1, b1) - max(a0, b0) > 0.5 * min(a1 - a0, b1 - b0)
