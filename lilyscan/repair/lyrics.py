"""Lyrics cleanup.

Audiveris reads every text line under a staff as a lyric line and numbers the lines
per system, so stray marks, page text (a footer, a version string), and chord names
become lyrics, and one stray line above the real lyrics turns verse 1 into verse 2
for that system only. A lyric line here is one verse of one staff within one system.

- Lines with page text are dropped; lines of chord names become chord symbols; lines
  made only of stray marks (no letters, or a lone consonant) are dropped.
- Syllables with no letters (page numbers, punctuation) are dropped.
- The remaining verses of each system are renumbered from 1, keeping their order.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from lilyscan.ir.models import ChordSymbol, Event, Lyric, Measure, Part, Provenance, Score, Staff
from lilyscan.repair import Repair
from lilyscan.repair.chords import ChordName, parse_chord_name

TEXT_RULE = "lyric-text"
VERSE_RULE = "lyric-verse"

_PAGE_TEXT = re.compile(
    r"^(v?\d+(\.\d+)+|www\..+|.+\.(org|com|net)|©.*|\(c\).*|copyright.*)$", re.IGNORECASE
)
_VOWELS = set("aeiouyAEIOUYàáâèéêìíòóôùú")
# Share of a line's syllables that must read as chord names for it to be a chord line.
_CHORD_LINE = 0.75


def _stray(text: str) -> bool:
    letters = [c for c in text if c.isalpha()]
    return not letters or (len(letters) == 1 and letters[0] not in _VOWELS)


def _systems(staff: Staff) -> list[list[Measure]]:
    """Measures grouped by system, from their page boxes (one group without boxes)."""
    groups: list[list[Measure]] = [[]]
    last = None
    for m in staff.measures:
        b = m.bbox
        if b is not None and last is not None and (b.page != last.page or b.x < last.x):
            groups.append([])
        last = b or last
        groups[-1].append(m)
    return [g for g in groups if g]


@dataclass
class _Line:
    verse: int
    entries: list[tuple[Measure, Event, Lyric]] = field(default_factory=list)

    @property
    def texts(self) -> list[str]:
        return [ly.text.strip() for _, _, ly in self.entries]


def _chord_line(line: _Line) -> list[ChordName | None] | None:
    names = [parse_chord_name(t) for t in line.texts]
    found = [n for n in names if n is not None]
    if len(found) < _CHORD_LINE * len(names) or not any(n.decorated for n in found):
        return None
    return names


@dataclass
class _Log:
    measures: dict[str, list[str]] = field(default_factory=dict)

    def add(self, what: str, m: Measure) -> None:
        number = m.number or str(m.index + 1)
        seen = self.measures.setdefault(what, [])
        if number not in seen:
            seen.append(number)


def _note_change(e: Event, before: list[Lyric]) -> None:
    if not any(p.rule == TEXT_RULE for p in e.provenance):
        e.provenance.append(
            Provenance(
                stage="repair",
                rule=TEXT_RULE,
                before={"lyrics": [[ly.verse, ly.text] for ly in before]},
            )
        )


def _clean_system(measures: list[Measure], log: _Log) -> bool:
    """Clean one staff's lyrics within one system; True if verses were renumbered."""
    lines: dict[int, _Line] = {}
    for m in measures:
        for v in m.voices:
            for e in v.events:
                for ly in e.lyrics:
                    lines.setdefault(ly.verse, _Line(ly.verse)).entries.append((m, e, ly))
    drop: set[int] = set()  # id() of dropped Lyric objects
    for line in lines.values():
        texts = line.texts
        chords = _chord_line(line)
        if any(_PAGE_TEXT.match(t) for t in texts):
            what = "page text"
        elif chords is not None:
            what = "chord names"
            for (m, e, _), name in zip(line.entries, chords, strict=True):
                if name is not None and not any(c.offset == e.offset for c in m.chord_symbols):
                    m.chord_symbols.append(
                        ChordSymbol(offset=e.offset, root=name.root, kind=name.kind, bass=name.bass)
                    )
                    m.chord_symbols.sort(key=lambda c: c.offset)
        elif all(_stray(t) for t in texts):
            what = "stray marks"
        else:
            for m, _, ly in line.entries:
                if not any(c.isalpha() for c in ly.text):
                    drop.add(id(ly))
                    log.add("stray marks", m)
            continue
        for m, _, ly in line.entries:
            drop.add(id(ly))
            log.add(what, m)

    changed: dict[int, tuple[Event, list[Lyric]]] = {}
    for line in lines.values():
        for _, e, _ in line.entries:
            changed.setdefault(id(e), (e, list(e.lyrics)))
    for e, before in changed.values():
        kept = [ly for ly in e.lyrics if id(ly) not in drop]
        if len(kept) != len(e.lyrics):
            _note_change(e, before)
            e.lyrics = kept

    remaining = sorted({ly.verse for e, _ in changed.values() for ly in e.lyrics})
    renumber = {old: new for new, old in enumerate(remaining, start=1) if old != new}
    if not renumber:
        return False
    for e, _ in changed.values():
        for ly in e.lyrics:
            ly.verse = renumber.get(ly.verse, ly.verse)
    return True


def _staff_repairs(part: Part, staff: Staff, score: Score) -> list[Repair]:
    log = _Log()
    renumbered: list[list[str]] = []
    for system in _systems(staff):
        if _clean_system(system, log):
            renumbered.append([m.number or str(m.index + 1) for m in system])
    name = part.name or part.id
    repairs = [
        Repair(TEXT_RULE, part.id, staff.number, f"{name}: dropped {what} read as lyrics", ms)
        for what, ms in log.measures.items()
        if what != "chord names"
    ]
    if "chord names" in log.measures:
        repairs.append(
            Repair(
                TEXT_RULE,
                part.id,
                staff.number,
                f"{name}: chord names read as lyrics, moved to chord symbols",
                log.measures["chord names"],
            )
        )
    if renumbered:
        spans = ", ".join(ms[0] if len(ms) == 1 else f"{ms[0]}-{ms[-1]}" for ms in renumbered)
        detail = f"verse numbers restarted at 1 in measures {spans}"
        score.provenance.append(
            Provenance(
                stage="repair",
                rule=VERSE_RULE,
                before={"part": part.id, "staff": staff.number, "detail": detail},
            )
        )
        measures = [n for ms in renumbered for n in ms]
        repairs.append(Repair(VERSE_RULE, part.id, staff.number, f"{name}: {detail}", measures))
    return repairs


def clean_lyrics(score: Score) -> list[Repair]:
    repairs: list[Repair] = []
    for part in score.parts:
        for staff in part.staves:
            repairs += _staff_repairs(part, staff, score)
    return repairs
