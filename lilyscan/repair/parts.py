"""Part mapping across systems: merge parts the engine split between systems.

Audiveris matches a system's staves to the parts of earlier systems by name. When the
name changes from system to system (full name first, abbreviation after, or an OCR
misread such as ``F1.`` for ``Fl.``), it starts a new part, and the exported parts are
each filled with measure rests in the systems they are absent from.

Two parts are merged when they are never present in the same measure, have the same
staves and clefs, and either their names are compatible (one abbreviates the other)
or one name is unreadable and its neighbours were merged in the same order. A measure
counts as absent when it holds only measure rests and has no page box.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from itertools import pairwise

from lilyscan.ir.models import Measure, Part, Provenance, Score
from lilyscan.repair import Repair

RULE = "part-merge"

# Digits that OCR reads for letters inside a name ("F1." for "Fl.", "C0r." for "Cor.").
_OCR_DIGITS = str.maketrans({"1": "l", "0": "o", "5": "s", "8": "b"})
_ROMAN = {"i": 1, "ii": 2, "iii": 3, "iv": 4, "v": 5, "vi": 6}
# Other names of an instrument, whose abbreviations do not abbreviate the name used.
_ALIASES = {
    "cello": ("violoncello",),
    "bassoon": ("fagotto", "fagott"),
    "horn": ("corno",),
    "doublebass": ("contrabass", "contrabasso", "kontrabass"),
    "timpani": ("pauken",),
}


# The name Audiveris gives a part whose name it could not read. It is still matched
# by name, but a part so named may also be placed by its neighbours.
_PLACEHOLDERS = {"voice"}


def _label(name: str | None) -> tuple[str, int | None] | None:
    """Letters and instrument number of a part name; None when there are no letters."""
    words = re.findall(r"[A-Za-z0-9]+", name or "")
    # An upper-case I inside a word is usually a misread l ("FI." for "Fl.").
    words = [w if w.lower() in _ROMAN else w[0] + w[1:].replace("I", "l") for w in words]
    words = [w.lower() for w in words]
    number = None
    if len(words) > 1 and (words[-1].isdigit() or words[-1] in _ROMAN):
        last = words.pop()
        number = int(last) if last.isdigit() else _ROMAN[last]
    letters = "".join(w.translate(_OCR_DIGITS) if re.search("[a-z]", w) else w for w in words)
    return (letters, number) if re.fullmatch("[a-z]+", letters or "0") else None


def _abbreviates(short: str, long: str) -> bool:
    """``short`` is ``long`` or an abbreviation of it: same first letter, then a subsequence."""
    if not short or short[0] != long[0]:
        return False
    rest = iter(long[1:])
    return all(ch in rest for ch in short[1:])


def names_compatible(a: str | None, b: str | None) -> bool:
    """One name abbreviates the other; a name read without its number matches any number."""
    la, lb = _label(a), _label(b)
    if la is None or lb is None or (None not in (la[1], lb[1]) and la[1] != lb[1]):
        return False
    forms_a = (la[0], *_ALIASES.get(la[0], ()))
    forms_b = (lb[0], *_ALIASES.get(lb[0], ()))
    return any(_abbreviates(*sorted((x, y), key=len)) for x in forms_a for y in forms_b)


def _absent(m: Measure) -> bool:
    events = [e for v in m.voices for e in v.events]
    return m.bbox is None and all(e.kind == "rest" and e.measure_rest for e in events)


def _presence(part: Part) -> list[bool]:
    rows = [[not _absent(m) for m in s.measures] for s in part.staves]
    return [any(col) for col in zip(*rows, strict=True)] if rows else []


def _clefs(part: Part, present: list[bool]) -> list[tuple[str, int | None] | None]:
    """Clef (sign, line) of each staff where the part first appears; octave marks ignored.

    MusicXML states a clef only when it changes, so this is the last clef before the
    first present measure, or one at the very start of it.
    """
    first = next((i for i, x in enumerate(present) if x), 0)
    out: list[tuple[str, int | None] | None] = []
    for s in part.staves:
        current = None
        for m in s.measures[: first + 1]:
            for c in m.clefs:
                if m.index < first or c.offset == 0:
                    current = c
        current = current or next((c for m in s.measures for c in m.clefs), None)
        out.append(None if current is None else (current.sign, current.line))
    return out


@dataclass
class _Cluster:
    parts: list[Part]
    present: list[bool]
    clefs: list[tuple[str, int | None] | None]
    members: list[int] = field(default_factory=list)  # indexes into score.parts

    def accepts(
        self, part: Part, present: list[bool], clefs: list[tuple[str, int | None] | None]
    ) -> bool:
        rep = self.parts[0]
        return (
            len(part.staves) == len(rep.staves)
            and len(present) == len(self.present)
            and not any(a and b for a, b in zip(present, self.present, strict=True))
            and all(
                a is None or b is None or a == b for a, b in zip(clefs, self.clefs, strict=True)
            )
        )

    def named_like(self, part: Part) -> bool:
        return any(
            names_compatible(x, y)
            for p in self.parts
            for x in (p.name, p.abbreviation)
            for y in (part.name, part.abbreviation)
        )

    def add(self, index: int, part: Part, present: list[bool]) -> None:
        self.parts.append(part)
        self.members.append(index)
        self.present = [a or b for a, b in zip(self.present, present, strict=True)]


def _readable(part: Part) -> bool:
    labels = [_label(part.name), _label(part.abbreviation)]
    return any(x is not None and x[0] not in _PLACEHOLDERS for x in labels)


def _cluster(score: Score) -> list[_Cluster]:
    parts = score.parts
    presence = [_presence(p) for p in parts]
    clefs = [_clefs(p, pr) for p, pr in zip(parts, presence, strict=True)]
    first = [next((i for i, x in enumerate(pr) if x), len(pr)) for pr in presence]
    clusters: list[_Cluster] = []
    home: dict[int, _Cluster] = {}

    def start(k: int) -> None:
        home[k] = _Cluster([parts[k]], presence[k], clefs[k], [k])
        clusters.append(home[k])

    def join(k: int, c: _Cluster) -> None:
        c.add(k, parts[k], presence[k])
        home[k] = c

    def by_neighbour(k: int) -> _Cluster | None:
        """The cluster next to the one holding the staff above or below, in k's system."""
        system = [n for n, pr in enumerate(presence) if first[k] < len(pr) and pr[first[k]]]
        pos = system.index(k)
        for step in (-1, 1):
            if 0 <= pos + step < len(system) and system[pos + step] in home:
                j = clusters.index(home[system[pos + step]]) - step
                if 0 <= j < len(clusters) and clusters[j].accepts(parts[k], presence[k], clefs[k]):
                    return clusters[j]
        return None

    deferred: dict[int, list[_Cluster]] = {}
    for k in sorted(range(len(parts)), key=lambda k: (first[k], k)):
        if not any(presence[k]):
            continue
        fits = [
            c
            for c in clusters
            if c.accepts(parts[k], presence[k], clefs[k]) and c.named_like(parts[k])
        ]
        if len(fits) == 1:
            join(k, fits[0])
        elif fits or (clusters and not _readable(parts[k])):
            deferred[k] = fits
        else:
            start(k)
    # Ambiguous or unreadable names: place by the staves above and below, repeating
    # while that places more parts.
    placed = True
    while deferred and placed:
        placed = False
        for k, fits in list(deferred.items()):
            target = by_neighbour(k)
            if target is not None and (not fits or target in fits):
                join(k, target)
                del deferred[k]
                placed = True
    for k in deferred:
        start(k)
    return clusters


def _display(part: Part) -> str:
    return f"{part.id} ({part.name or part.abbreviation or 'unnamed'})"


def merge_split_parts(score: Score) -> list[Repair]:
    repairs: list[Repair] = []
    clusters = [c for c in _cluster(score) if len(c.parts) > 1]
    if not clusters:
        return repairs
    removed: set[str] = set()
    for c in clusters:
        rep, others = c.parts[0], c.parts[1:]
        moved: list[str] = []
        for other in others:
            present = _presence(other)
            for staff, src in zip(rep.staves, other.staves, strict=True):
                for i, m in enumerate(src.measures):
                    if not present[i]:
                        continue
                    for v in m.voices:
                        for e in v.events:
                            e.provenance.append(
                                Provenance(stage="repair", rule=RULE, before={"part": other.id})
                            )
                    staff.measures[i] = m
                    moved.append(m.number or str(m.index + 1))
            removed.add(other.id)
        names = [x for p in c.parts for x in (p.name, p.abbreviation) if x and _label(x)]
        if names:
            rep.name = max(names, key=len)
            rep.abbreviation = min(names, key=len)
        detail = f"merged {', '.join(_display(p) for p in others)} into {_display(rep)}"
        score.provenance.append(
            Provenance(
                stage="repair",
                rule=RULE,
                before={"part": rep.id, "staff": rep.staves[0].number, "detail": detail},
            )
        )
        repairs.append(
            Repair(
                rule=RULE,
                part=rep.id,
                staff=None,
                detail=detail,
                measures=sorted(set(moved), key=moved.index),
            )
        )
    score.parts = _page_order(score, clusters)
    return repairs


def _page_order(score: Score, clusters: list[_Cluster]) -> list[Part]:
    """The merged parts in the order their staves appear down each system.

    Within a measure, the parts present are in the engine's (top-to-bottom) order;
    those orders are combined across systems. Parts first seen in later systems go
    where their neighbours put them, not ahead of the first system's parts.
    """
    home = {k: c.parts[0] for c in clusters for k in c.members}
    presence = [_presence(p) for p in score.parts]
    nodes = {id(p): p for p in (home.get(k, part) for k, part in enumerate(score.parts))}
    key: dict[int, tuple[int, int]] = {}
    for k, part in enumerate(score.parts):
        node = home.get(k, part)
        first = next((i for i, x in enumerate(presence[k]) if x), len(presence[k]))
        key[id(node)] = min(key.get(id(node), (first, k)), (first, k))
    after: dict[int, set[int]] = {n: set() for n in nodes}
    for i in range(max((len(pr) for pr in presence), default=0)):
        column = [
            id(home.get(k, part))
            for k, part in enumerate(score.parts)
            if i < len(presence[k]) and presence[k][i]
        ]
        for a, b in pairwise(column):
            if a != b:
                after[b].add(a)
    ordered: list[Part] = []
    left = set(nodes)
    while left:
        ready = [n for n in left if not (after[n] & left)] or list(left)  # a cycle: break it
        n = min(ready, key=key.__getitem__)
        ordered.append(nodes[n])
        left.remove(n)
    return ordered
