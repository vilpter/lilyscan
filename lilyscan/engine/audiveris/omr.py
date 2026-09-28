"""Stage 3: read Audiveris ``.omr`` projects and attach geometry and confidence to the IR.

Written against Audiveris 5.11.0 (D7). An ``.omr`` file is a zip holding
``book.xml`` and ``sheet#N/sheet#N.xml``. Each sheet has systems; a system has
*stacks* (measures, with *slots* mapping time offsets to x positions), parts with
staves, and the interpretation graph (``sig``): interpretations with a shape,
``grade``, ``ctx-grade`` and pixel ``bounds``, plus relations between them.

MusicXML measure *i* is the *i*-th stack in sheet/system order (the measures the reader
adds for a multi-measure rest share its stack). An event's onset
selects a slot, hence an x position; the notehead on the event's staff near that x
whose staff step matches the event's pitch supplies the bbox and confidence.
"""

from __future__ import annotations

import zipfile
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path
from xml.etree import ElementTree as ET

from lilyscan.ir.models import BBox, Clef, Event, Pitch, Score
from lilyscan.ir.musicxml import added_rest, written_measures
from lilyscan.ir.ops import merge_scores


class OmrError(ValueError):
    pass


@dataclass(frozen=True)
class Box:
    x: float
    y: float
    w: float
    h: float

    @property
    def cx(self) -> float:
        return self.x + self.w / 2

    def union(self, other: Box) -> Box:
        x0, y0 = min(self.x, other.x), min(self.y, other.y)
        x1 = max(self.x + self.w, other.x + other.w)
        y1 = max(self.y + self.h, other.y + other.h)
        return Box(x0, y0, x1 - x0, y1 - y0)


@dataclass(frozen=True)
class Inter:
    id: int
    kind: str  # element name: head, rest, head-chord, clef, ...
    shape: str | None
    grade: float | None
    ctx_grade: float | None
    staff: int | None  # sheet-level staff id
    box: Box | None
    step: int | None  # heads: staff steps below the middle line
    value: str | None = None  # text items (lyric-item, word): the text read
    role: str | None = None  # the element's own kind, e.g. "Syllable" or "Hyphen" for lyrics

    @property
    def confidence(self) -> float | None:
        return self.ctx_grade if self.ctx_grade is not None else self.grade


@dataclass
class Stack:
    left: float
    right: float
    slots: dict[Fraction, float]  # time offset (whole notes) -> absolute x


@dataclass
class OmrStaff:
    id: int
    top: float
    bottom: float


@dataclass
class OmrSystem:
    stacks: list[Stack]
    parts: dict[int, list[OmrStaff]]  # logical part id -> staves in order
    inters: list[Inter]
    chord_of: dict[int, Inter] = field(default_factory=dict)  # head/rest id -> its chord
    starts_movement: bool = False  # first system of a piece (Audiveris indents it)

    def on_staff(self, staff_id: int, kinds: tuple[str, ...]) -> list[Inter]:
        return [i for i in self.inters if i.staff == staff_id and i.kind in kinds and i.box]


@dataclass
class OmrSheet:
    number: int
    width: int
    height: int
    interline: float
    systems: list[OmrSystem]


@dataclass
class OmrBook:
    software_version: str | None
    sheets: list[OmrSheet]
    logical_parts: dict[int, str]  # id -> name


def _float(el: ET.Element, name: str) -> float | None:
    v = el.get(name)
    return float(v) if v not in (None, "") else None


def _box(el: ET.Element) -> Box | None:
    b = el.find("bounds")
    if b is None:
        return None
    return Box(
        float(b.get("x", 0)), float(b.get("y", 0)), float(b.get("w", 0)), float(b.get("h", 0))
    )


def _inter(el: ET.Element) -> Inter:
    staff = el.get("staff")
    step = el.get("pitch") if el.tag == "head" else None
    return Inter(
        id=int(el.get("id", "0")),
        kind=el.tag,
        shape=el.get("shape"),
        grade=_float(el, "grade"),
        ctx_grade=_float(el, "ctx-grade"),
        staff=int(staff) if staff else None,
        box=_box(el),
        step=round(float(step)) if step not in (None, "") else None,
        value=el.get("value"),
        role=el.get("kind"),
    )


def _system(el: ET.Element, starts_movement: bool = False) -> OmrSystem:
    stacks = []
    for s in el.findall("stack"):
        left = float(s.get("left", 0))
        slots = {
            Fraction(sl.get("time-offset", "0")): left + float(sl.get("x-offset", 0))
            for sl in s.findall("slot")
        }
        stacks.append(Stack(left=left, right=float(s.get("right", left)), slots=slots))
    parts: dict[int, list[OmrStaff]] = {}
    for p in el.findall("part"):
        staves = []
        for st in p.findall("staff"):
            ys = [
                float(pt.get("y", 0))
                for line in st.findall("lines/line")
                for pt in line.findall("point")
            ]
            staves.append(
                OmrStaff(int(st.get("id", "0")), min(ys, default=0.0), max(ys, default=0.0))
            )
        parts[int(p.get("id", "0"))] = staves
    inters = [_inter(i) for i in el.findall("sig/inters/*")]
    by_id = {i.id: i for i in inters}
    chord_of: dict[int, Inter] = {}
    for rel in el.findall("sig/relations/relation"):
        if rel.find("containment") is None:
            continue
        source = by_id.get(int(rel.get("source", "0")))
        if source is not None and source.kind in ("head-chord", "rest-chord"):
            chord_of[int(rel.get("target", "0"))] = source
    return OmrSystem(
        stacks=stacks,
        parts=parts,
        inters=inters,
        chord_of=chord_of,
        starts_movement=starts_movement,
    )


def read_omr(path: Path) -> OmrBook:
    try:
        z = zipfile.ZipFile(path)
    except zipfile.BadZipFile as exc:
        raise OmrError(f"{path.name} is not an .omr project") from exc
    with z:
        names = set(z.namelist())
        if "book.xml" not in names:
            raise OmrError(f"{path.name}: no book.xml")
        book = ET.fromstring(z.read("book.xml"))
        logical = {
            int(lp.get("id", "0")): lp.get("name", "") for lp in book.findall("score/logical-part")
        }
        sheets = []
        for sheet_el in book.findall("sheet"):
            n = int(sheet_el.get("number", "0"))
            name = f"sheet#{n}/sheet#{n}.xml"
            if name not in names:
                continue  # sheet not processed (e.g. failed); its measures are absent from MusicXML
            sheet = ET.fromstring(z.read(name))
            picture = sheet.find("picture")
            interline = sheet.find("scale/interline")
            sheets.append(
                OmrSheet(
                    number=n,
                    width=int(picture.get("width", "0")) if picture is not None else 0,
                    height=int(picture.get("height", "0")) if picture is not None else 0,
                    interline=float(interline.get("main", "20")) if interline is not None else 20.0,
                    systems=[
                        _system(s, starts_movement=s.get("indented") == "true")
                        for s in sheet.findall("page/system")
                    ],
                )
            )
    return OmrBook(book.get("software-version"), sheets, logical)


# --- attaching geometry to the IR ------------------------------------------------

# Reference pitch of each clef sign on its line (diatonic number, C4 = 28).
_CLEF_REFERENCE = {"G": 32, "F": 24, "C": 28}  # G4, F3, C4
_DEFAULT_LINE = {"G": 2, "F": 4, "C": 3}


def middle_line_diatonic(clef: Clef) -> int:
    """Diatonic number of the staff's middle line under ``clef``."""
    if clef.sign not in _CLEF_REFERENCE:
        return 34  # treat percussion/none as treble
    line = clef.line or _DEFAULT_LINE[clef.sign]
    return _CLEF_REFERENCE[clef.sign] + 2 * (3 - line) + 7 * clef.octave_change


def staff_step(pitch: Pitch, clef: Clef) -> int:
    """Audiveris head 'pitch': staff steps below the middle line."""
    return middle_line_diatonic(clef) - pitch.diatonic


@dataclass
class AttachStats:
    events: int = 0
    # Events in measures whose staff exists on the page. Engines pad a part that is
    # absent from a system with invented rests; those have nothing to locate.
    mappable: int = 0
    located: int = 0
    pitch_mismatch: int = 0
    measures: int = 0
    measures_located: int = 0
    unmapped_staves: list[str] = field(default_factory=list)

    @property
    def located_rate(self) -> float:
        """Share of mappable events that received a bounding box."""
        return self.located / self.mappable if self.mappable else 0.0


def _slot_x(stack: Stack, offset: Fraction) -> float:
    whole = offset / 4
    if whole in stack.slots:
        return stack.slots[whole]
    if not stack.slots:
        return (stack.left + stack.right) / 2
    return min(stack.slots.items(), key=lambda kv: abs(kv[0] - whole))[1]


# Search windows, in interlines. An engine's MusicXML onsets can disagree with its
# own slot positions after a rhythm misread, so a head at exactly the expected staff
# step is accepted further from the slot than a head at some other step.
EXACT_WINDOW = 2.5
FALLBACK_WINDOW = 1.0
GRACE_WINDOW = 2.0


def _distance(inter: Inter, x: float) -> float:
    return abs(inter.box.cx - x) if inter.box else float("inf")


def _locate(
    e: Event,
    x: float,
    heads: list[Inter],
    rests: list[Inter],
    clef: Clef,
    interline: float,
    chord_of: dict[int, Inter],
    used: set[int],
) -> tuple[Box | None, float | None, bool]:
    """Box, confidence, and whether every pitch matched a head at the expected step.

    ``used`` holds ids already given to other events of the measure; they are skipped
    and the chosen ones are added.
    """
    if e.kind == "rest" or not e.notes:
        found = [
            r for r in rests if r.id not in used and _distance(r, x) <= FALLBACK_WINDOW * interline
        ]
        if not found:
            return None, None, True
        best = min(found, key=lambda r: _distance(r, x))
        used.add(best.id)
        return best.box, best.confidence, True

    free = [h for h in heads if h.box and h.id not in used]
    chosen: list[Inter] = []
    all_matched = True
    for head in e.notes:
        step = staff_step(head.pitch, clef)
        if e.grace:
            window = [h for h in free if h.box and x - GRACE_WINDOW * interline <= h.box.cx < x]
            exact = [h for h in window if h.step == step and h not in chosen]
            fallback = [h for h in window if h not in chosen]
        else:
            exact = [
                h
                for h in free
                if h.step == step
                and h not in chosen
                and _distance(h, x) <= EXACT_WINDOW * interline
            ]
            fallback = [
                h
                for h in free
                if h not in chosen and _distance(h, x) <= FALLBACK_WINDOW * interline
            ]
        pool = exact or fallback
        if not pool:
            continue
        if not exact:
            all_matched = False
        chosen.append(min(pool, key=lambda h: _distance(h, x)))
    if not chosen:
        return None, None, False
    if len(chosen) < len(e.notes):
        all_matched = False
    used.update(h.id for h in chosen)
    box = chosen[0].box
    assert box is not None
    for h in chosen[1:]:
        if h.box:
            box = box.union(h.box)
    confidences = [h.confidence for h in chosen if h.confidence is not None]
    chord = chord_of.get(chosen[0].id)
    if chord is not None and chord.confidence is not None:
        confidences.append(chord.confidence)
    return box, (min(confidences) if confidences else None), all_matched


def _measure_rest(
    stack: Stack, rests: list[Inter], used: set[int]
) -> tuple[Box | None, float | None, bool]:
    centre = (stack.left + stack.right) / 2
    inside = [
        r for r in rests if r.box and r.id not in used and stack.left <= r.box.cx <= stack.right
    ]
    if not inside:
        return None, None, True
    best = min(inside, key=lambda r: _distance(r, centre))
    used.add(best.id)
    return best.box, best.confidence, True


def attach_geometry(score: Score, book: OmrBook, first_measure: int = 0) -> AttachStats:
    """Fill ``bbox`` and ``confidence`` on events and measures from the ``.omr`` book.

    ``score`` is one movement (one exported MusicXML file) whose measure 0 is the book's
    measure ``first_measure``: its parts are numbered as that movement's parts are in
    the book. Pages are numbered 0-based in sheet order. Events whose pitch disagrees
    with the notehead found at that position get their confidence halved.
    """
    stats = AttachStats()
    # Measure index -> (page index, sheet, system, stack), in reading order.
    placements = [
        (page, sheet, system, stack)
        for page, sheet in enumerate(book.sheets)
        for system in sheet.systems
        for stack in system.stacks
    ]
    for part_index, part in enumerate(score.parts, 1):
        for staff in part.staves:
            clef = Clef(sign="G", line=2)
            for m, written in zip(staff.measures, written_measures(staff.measures), strict=True):
                added = added_rest(m)
                stats.measures += 1
                stats.events += sum(len(v.events) for v in m.voices)
                starting = [c for c in m.clefs if c.offset == 0]
                if starting:
                    clef = starting[-1]
                if written + first_measure >= len(placements):
                    continue
                page, sheet, system, stack = placements[written + first_measure]
                omr_staves = system.parts.get(part_index, [])
                if staff.number > len(omr_staves):
                    label = f"{part.id}/{staff.number}"
                    if label not in stats.unmapped_staves:
                        stats.unmapped_staves.append(label)
                    continue
                omr_staff = omr_staves[staff.number - 1]
                m.bbox = BBox(
                    page=page,
                    x=stack.left,
                    y=omr_staff.top,
                    w=stack.right - stack.left,
                    h=omr_staff.bottom - omr_staff.top,
                )
                stats.measures_located += 1
                if added:
                    continue  # drawn once, as the multi-measure rest
                heads = system.on_staff(omr_staff.id, ("head",))
                rests = system.on_staff(omr_staff.id, ("rest",))
                used: set[int] = set()
                for v in m.voices:
                    for e in v.events:
                        stats.mappable += 1
                        mid = [c for c in m.clefs if 0 < c.offset <= e.offset]
                        active = mid[-1] if mid else clef
                        if e.measure_rest:
                            # Whole-measure rests are drawn centred in the bar.
                            box, confidence, matched = _measure_rest(stack, rests, used)
                        else:
                            x = _slot_x(stack, e.offset)
                            box, confidence, matched = _locate(
                                e, x, heads, rests, active, sheet.interline, system.chord_of, used
                            )
                        if box is None:
                            continue
                        stats.located += 1
                        e.bbox = BBox(page=page, x=box.x, y=box.y, w=box.w, h=box.h)
                        if confidence is not None:
                            e.confidence = confidence if matched else confidence / 2
                        if not matched:
                            stats.pitch_mismatch += 1
                mid_clefs = [c for c in m.clefs if c.offset > 0]
                if mid_clefs:
                    clef = mid_clefs[-1]
    return stats


def attach_movements(
    movements: list[Score], book: OmrBook | None
) -> tuple[Score, AttachStats | None]:
    """The engine's movements merged into one score, geometry attached to each first.

    Each movement numbers its parts afresh, and the merge may place them differently, so
    boxes are attached while every part still has its movement's numbering.
    """
    if book is None:
        return merge_scores(movements), None
    total = AttachStats()
    first = 0
    for movement in movements:
        stats = attach_geometry(movement, book, first)
        for name in ("events", "mappable", "located", "pitch_mismatch", "measures"):
            setattr(total, name, getattr(total, name) + getattr(stats, name))
        total.measures_located += stats.measures_located
        total.unmapped_staves += [
            x for x in stats.unmapped_staves if x not in total.unmapped_staves
        ]
        first += max(
            (len(set(written_measures(st.measures))) for _, st in movement.staves()), default=0
        )
    return merge_scores(movements), total
