"""Stage 7: IR -> an editable LilyPond project.

Layout (design doc, Stage 7):

    main.ly               \\version, \\header, includes, the full score
    parts/<name>.ly       one music variable per staff, lyrics variables
    chords.ly             harmonies = \\chordmode { ... }   (when there are chord symbols)
    layout/score.ly       \\score { ... } for the full score
    layout/part-<name>.ly a single-part layout reusing the same variables

Pitches are absolute (D4). Every measure sits on its own line with a bar check and
a ``% m. N`` comment. Output is deterministic: the same IR gives the same bytes.
"""

from __future__ import annotations

import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path

from lilyscan.ir.models import Barline, ChordSymbol, Clef, Event, Measure, NoteHead, Part, Score
from lilyscan.lilypond.notation import (
    CHORD_MODIFIERS,
    absolute_pitch,
    chord_root,
    clef_command,
    key_command,
    lily_string,
    single_duration,
    split_duration,
    time_command,
    transposition_pitch,
    written_duration,
)
from lilyscan.runtime.config import LILYPOND_VERSION

# Events below this confidence get an inline "%{ ?? ... %}" marker.
LOW_CONFIDENCE = 0.5

_VOICE_COMMANDS = ["\\voiceOne", "\\voiceTwo", "\\voiceThree", "\\voiceFour"]
_NUMBER_WORDS = ["Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine"]
_VOCAL = re.compile(
    r"soprano|alto|tenor|bass|voice|vocal|choir|chorus|cantus|s\.|a\.|t\.|b\.", re.I
)


@dataclass
class StaffVars:
    music: str  # variable name, e.g. "violinIMusic"
    voice: str  # context name of the staff's main voice, for \lyricsto
    lyrics: list[str] = field(default_factory=list)  # one variable per verse


@dataclass
class PartVars:
    part: Part
    slug: str  # file name stem, e.g. "violin-i"
    base: str  # identifier stem, e.g. "violinI"
    staves: list[StaffVars] = field(default_factory=list)


@dataclass
class LyProject:
    """Generated files, keyed by relative path, plus a measure map for bar-check reports."""

    files: dict[str, str]
    # (relative file, 1-based line) -> (part id, staff number, measure number)
    measure_lines: dict[tuple[str, int], tuple[str, int, str]]

    def write(self, root: Path) -> None:
        for rel, text in self.files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8", newline="\n")


def _identifier(text: str) -> str:
    """LilyPond identifiers are letters only: 'Violin II' -> 'violinII', 'P1' -> 'pOne'."""
    words = re.findall(r"[A-Za-z]+|\d", text)
    out = "".join(
        _NUMBER_WORDS[int(w)]
        if w.isdigit()
        else (w if w.isupper() and len(w) <= 4 else w.capitalize())
        for w in words
    )
    out = out[:1].lower() + out[1:] if out else "part"
    return out or "part"


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-") or "part"


def _unique(name: str, used: set[str], sep: str = "") -> str:
    if name not in used:
        used.add(name)
        return name
    for letter in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
        candidate = f"{name}{sep}{letter}"
        if candidate not in used:
            used.add(candidate)
            return candidate
    raise ValueError(f"too many parts named {name!r}")


def _staff_suffix(number: int, count: int) -> str:
    if count == 1:
        return ""
    if count == 2:
        return "Upper" if number == 1 else "Lower"
    return "Staff" + _NUMBER_WORDS[number % 10]


# --- music ---------------------------------------------------------------------


def _note_token(head: NoteHead, in_chord: bool) -> str:
    return absolute_pitch(head.pitch) + ("~" if head.tie_start and in_chord else "")


# MusicXML articulation / notation names -> LilyPond post-events.
_ARTICULATIONS = {
    "staccato": "-.",
    "staccatissimo": "-!",
    "accent": "->",
    "strong-accent": "-^",
    "tenuto": "--",
    "detached-legato": "-_",
    "spiccato": "\\staccatissimo",
    "fermata": "\\fermata",
}


def _event_body(e: Event) -> str:
    duration = written_duration(e.note_type, e.dots, e.duration, e.tuplet)
    marks = "".join(_ARTICULATIONS.get(a, "") for a in e.articulations)
    # \breathe is a command that follows the note, not a post-event.
    breathe = " \\breathe" if "breath-mark" in e.articulations else ""
    if e.kind == "rest" or not e.notes:
        return "r" + duration + ("\\fermata" if "fermata" in e.articulations else "") + breathe
    if len(e.notes) == 1:
        tie = "~" if e.notes[0].tie_start else ""
        return absolute_pitch(e.notes[0].pitch) + duration + marks + tie + breathe
    heads = " ".join(_note_token(h, in_chord=True) for h in e.notes)
    return f"<{heads}>{duration}{marks}{breathe}"


def _markers(e: Event) -> list[str]:
    out = []
    if e.confidence is not None and e.confidence < LOW_CONFIDENCE:
        where = ""
        if e.bbox is not None:
            where = f" bbox=p{e.bbox.page + 1}:({round(e.bbox.x)},{round(e.bbox.y)})"
        out.append(f"%{{ ?? conf={e.confidence:.2f}{where} %}}")
    for prov in e.provenance:
        if prov.stage == "repair":
            out.append(f"%{{ fix: {prov.rule or 'repair'} %}}")
    return out


def _full_rest(length: Fraction) -> str:
    """A whole-measure rest lasting ``length`` quarters."""
    token = single_duration(length)
    return f"R{token}" if token else f"R1*{length / 4}"


def _measure_rest(e: Event, length: Fraction | None) -> str:
    if length is not None:
        return _full_rest(length)
    return "R" + written_duration(e.note_type, e.dots, e.duration, None)


def _voice_tokens(
    events: Sequence[Event],
    length: Fraction | None,
    clefs: Sequence[Clef] = (),
) -> list[str]:
    """Tokens for one voice; ``clefs`` are mid-measure clef changes to interleave.

    A voice need not start on the downbeat or be contiguous (inner voices often
    enter mid-measure); gaps become invisible spacer rests so every event keeps its
    onset.
    """
    tokens: list[str] = []
    pending = sorted(clefs, key=lambda c: c.offset)
    position = Fraction(0)
    i = 0
    while i < len(events):
        e = events[i]
        if e.offset > position:
            tokens += ["s" + d for d in split_duration(e.offset - position)]
            position = e.offset
        while pending and pending[0].offset <= e.offset:
            tokens.append(clef_command(pending.pop(0)))
        if e.measure_rest and len(events) == 1:
            tokens.append(_measure_rest(e, length))
            position += length or e.duration
            i += 1
            continue
        if e.grace:
            group = []
            while i < len(events) and events[i].grace:
                group.append(_event_body(events[i]))
                i += 1
            tokens.append(
                "\\grace " + (group[0] if len(group) == 1 else "{ " + " ".join(group) + " }")
            )
            continue
        if e.tuplet:
            ratio = e.tuplet
            group_events: list[Event] = []
            while i < len(events) and events[i].tuplet == ratio and not events[i].grace:
                group_events.append(events[i])
                i += 1
            inner = " ".join(
                _event_body(x) + "".join(" " + m for m in _markers(x)) for x in group_events
            )
            span = _tuplet_span(group_events[0], ratio)
            span_token = f" {span}" if span else ""
            tokens.append(f"\\tuplet {ratio[0]}/{ratio[1]}{span_token} {{ {inner} }}")
            position += sum((x.duration for x in group_events), Fraction(0))
            continue
        tokens.append(_event_body(e))
        tokens += _markers(e)
        position += e.duration
        i += 1
    tokens += [clef_command(c) for c in pending]  # a clef change right before the barline
    return tokens


def _tuplet_span(first: Event, ratio: tuple[int, int]) -> str | None:
    """Group length for automatic tuplet brackets: ``normal`` written notes of the first's type."""
    written = first.duration * Fraction(ratio[0], ratio[1])
    return single_duration(written * ratio[1])


def _measure_music(m: Measure, length: Fraction | None) -> str:
    voices = [v for v in m.voices if v.events]
    mid_clefs = [c for c in m.clefs if c.offset > 0]
    if not voices:
        return _full_rest(length) if length else "s1"
    if len(voices) == 1:
        return " ".join(_voice_tokens(voices[0].events, length, mid_clefs))
    parts = []
    for k, v in enumerate(voices[:4]):
        clefs = mid_clefs if k == 0 else ()
        body = " ".join(_voice_tokens(v.events, length, clefs))
        command = _VOICE_COMMANDS[k]
        parts.append(f"{{ {command} {body} }}" if k == 0 else f"\\new Voice {{ {command} {body} }}")
    return "<< " + " ".join(parts) + " >> \\oneVoice"


def _barline_command(b: Barline | None, left: bool) -> str | None:
    if b is None:
        return None
    if b.repeat == "forward":
        return '\\bar ".|:"'
    if b.repeat == "backward":
        return '\\bar ":|."'
    if left:
        return None
    return {
        "light-heavy": '\\bar "|."',
        "light-light": '\\bar "||"',
        "heavy-light": '\\bar ".|"',
        "heavy-heavy": '\\bar ".."',
        "dashed": '\\bar "!"',
        "none": '\\bar ""',
    }.get(b.style)


def _grace_leads(score: Score) -> dict[int, list[str]]:
    """Per measure index, the longest run of grace notes that opens the measure in any staff.

    Grace notes at a measure start in one staff put that staff out of step with
    the others (a time or key change prints twice), so every other staff and the
    chord names get matching invisible grace skips.
    """
    leads: dict[int, list[str]] = {}
    for _, staff in score.staves():
        for m in staff.measures:
            for v in m.voices:
                durations = []
                for e in v.events:
                    if not (e.grace and e.offset == 0):
                        break
                    durations.append(written_duration(e.note_type, e.dots, e.duration, None))
                if len(durations) > len(leads.get(m.index, [])):
                    leads[m.index] = durations
    return leads


def _grace_skip(durations: list[str]) -> str:
    skips = [f"s{d}" for d in durations]
    return "\\grace " + (skips[0] if len(skips) == 1 else "{ " + " ".join(skips) + " }")


def _opens_with_grace(m: Measure) -> bool:
    return any(v.events and v.events[0].grace and v.events[0].offset == 0 for v in m.voices)


def _measure_grid(score: Score) -> dict[int, Fraction]:
    """Per measure index, the length every staff gives that measure.

    It is the longest voice present in any staff. Engine output often has measures
    whose notes do not add up to the time signature; LilyPond would carry that error
    into every later measure, misplacing barlines and whole-measure rests and letting
    staves drift apart. Giving each source measure one length across all staves keeps
    every barline where the source had it. Measures empty in every staff are absent.
    """
    grid: dict[int, Fraction] = {}
    for _, staff in score.staves():
        for m in staff.measures:
            actual = max((v.duration() for v in m.voices), default=Fraction(0))
            if actual > grid.get(m.index, Fraction(0)):
                grid[m.index] = actual
    return grid


def _measure_length(length: Fraction) -> str:
    """``Timing.measureLength`` value: a rational in whole notes (LilyPond 2.26 syntax;
    2.24 used ``ly:make-moment``)."""
    whole = length / 4
    return (
        f"#{whole.numerator}"
        if whole.denominator == 1
        else f"#{whole.numerator}/{whole.denominator}"
    )


def _staff_music(
    part_id: str,
    staff_no: int,
    measures: list[Measure],
    grace_leads: dict[int, list[str]] | None = None,
    grid: dict[int, Fraction] | None = None,
) -> tuple[list[str], list[str]]:
    """Lines of the staff's music variable body and the measure number for each line.

    Where ``grid`` gives a measure a length other than its time signature's, the
    measure gets a ``Timing.measureLength`` override (restored afterwards) and a
    ``%{ ?? rhythm %}`` marker; staves with less music are padded with spacers.
    """
    leads = grace_leads or {}
    lengths = grid or {}
    lines: list[str] = []
    numbers: list[str] = []
    length: Fraction | None = None
    overridden = False
    pickup = Fraction(0)
    for m in measures:
        prefix: list[str] = []
        if m.left_barline is not None:
            cmd = _barline_command(m.left_barline, left=True)
            if cmd:
                prefix.append(cmd)
        for clef in m.clefs:
            if clef.offset == 0:
                prefix.append(clef_command(clef))
        if m.key is not None:
            prefix.append(key_command(m.key))
        if m.time is not None:
            length = m.time.measure_length
            if m.time.symbol == "normal" and (m.time.beats, m.time.beat_type) in ((4, 4), (2, 2)):
                prefix.append("\\numericTimeSignature")
            elif m.time.symbol in ("common", "cut"):
                prefix.append("\\defaultTimeSignature")
            prefix.append(time_command(m.time))
        own = max((v.duration() for v in m.voices), default=Fraction(0))
        is_pickup = False
        if m.implicit and m.index == 0 and length is not None and 0 < own < length:
            is_pickup = True
            pickup = own
            partial = single_duration(own)
            prefix.append(f"\\partial {partial}" if partial else "")
        target = length
        if not is_pickup and length is not None:
            target = lengths.get(m.index) or length
            if target != length:
                prefix.append(f"\\set Timing.measureLength = {_measure_length(target)}")
                completes_pickup = m is measures[-1] and pickup and target + pickup == length
                if not completes_pickup:
                    prefix.append(f"%{{ ?? rhythm: {target / 4} of {length / 4} %}}")
                overridden = True
            elif overridden and m.time is None:
                prefix.append(f"\\set Timing.measureLength = {_measure_length(length)}")
                overridden = False
            else:
                overridden = False
        label = m.number or str(m.index + 1)
        lines.append(f"  % m. {label}")
        numbers.append(label)
        body = _measure_music(m, target)
        if target is not None and 0 < own < target and not is_pickup:
            body += " " + " ".join("s" + d for d in split_duration(target - own))
        if m.index in leads and not _opens_with_grace(m):
            body = f"{_grace_skip(leads[m.index])} {body}"
        suffix = _barline_command(m.right_barline, left=False)
        text = " ".join(t for t in [*prefix, body] if t)
        lines.append(f"  {text} |" + (f" {suffix}" if suffix else ""))
        numbers.append(label)
    return lines, numbers


# A bare lyric word: letters, optional apostrophe-joined letters, trailing punctuation.
_APOSTROPHES = "'\N{RIGHT SINGLE QUOTATION MARK}"
_PLAIN_SYLLABLE = re.compile(r"^[^\W\d_]+(?:[" + _APOSTROPHES + r"][^\W\d_]+)*[.,;:!?]*$")


def _lyric_token(text: str) -> str:
    """Quote anything LilyPond's lyric mode could misread (digits read as durations,
    braces, backslashes, the -- and __ operators, OCR noise)."""
    return text if _PLAIN_SYLLABLE.match(text) else lily_string(text)


def _lyrics_lines(measures: list[Measure], verse: int) -> list[str] | None:
    """Syllables of voice 1, one line per measure; None when the verse is empty."""
    lines: list[str] = []
    found = False
    for m in measures:
        voice = min(m.voices, key=lambda v: v.number) if m.voices else None
        tokens: list[str] = []
        for e in voice.events if voice else []:
            if e.kind == "rest" or e.grace or not e.notes:
                continue
            if all(h.tie_stop for h in e.notes):
                continue  # tied continuation: no new syllable
            lyric = next((ly for ly in e.lyrics if ly.verse == verse), None)
            if lyric is None:
                tokens.append("_")
                continue
            found = True
            tokens.append(_lyric_token(lyric.text))
            if lyric.syllabic in ("begin", "middle"):
                tokens.append("--")
            elif lyric.extend:
                tokens.append("__")
        lines.append("  " + " ".join(tokens) if tokens else "")
    if not found:
        return None
    # Drop trailing placeholders: notes after a verse's last syllable need none.
    while lines:
        stripped = lines[-1].rstrip()
        while stripped.endswith(" _") or stripped.strip() == "_":
            stripped = stripped[:-1].rstrip()
        if stripped.strip():
            lines[-1] = stripped
            break
        lines.pop()
    return [line for line in lines if line]


def _verses(measures: list[Measure]) -> list[int]:
    return sorted(
        {ly.verse for m in measures for v in m.voices for e in v.events for ly in e.lyrics}
    )


# --- chords --------------------------------------------------------------------


def _chord_lines(
    score: Score, grace_leads: dict[int, list[str]], grid: dict[int, Fraction]
) -> list[str] | None:
    """One chordmode line per measure, from the first staff that has chord symbols.

    Measures follow the same grid as the staves (see ``_measure_grid``).
    """
    staff = next((s for _, s in score.staves() if any(m.chord_symbols for m in s.measures)), None)
    if staff is None:
        return None
    lines = []
    length: Fraction | None = None
    for m in staff.measures:
        if m.time is not None:
            length = m.time.measure_length
        actual = max((v.duration() for v in m.voices), default=Fraction(0))
        # A pickup measure only lasts as long as its notes.
        if m.implicit and m.index == 0 and actual:
            total = actual
        else:
            total = grid.get(m.index) or length or actual or Fraction(4)
        chords = sorted(m.chord_symbols, key=lambda c: c.offset)
        tokens: list[str] = [_grace_skip(grace_leads[m.index])] if m.index in grace_leads else []
        position = Fraction(0)
        for k, c in enumerate(chords):
            if c.offset > position:
                tokens += ["s" + d for d in split_duration(c.offset - position)]
                position = c.offset
            end = chords[k + 1].offset if k + 1 < len(chords) else total
            tokens += _chord_tokens(c, max(end - position, Fraction(1, 32)))
            position = end
        if position < total:
            tokens += ["s" + d for d in split_duration(total - position)]
        lines.append(f"  {' '.join(tokens)} |  % m. {m.number or m.index + 1}")
    return lines


def _chord_tokens(c: ChordSymbol, length: Fraction) -> list[str]:
    durations = split_duration(length) or ["4"]
    modifier = CHORD_MODIFIERS.get(c.kind, "")
    bass = f"/{chord_root(c.bass)}" if c.bass else ""
    first = f"{chord_root(c.root)}{durations[0]}{modifier}{bass}"
    return [first] + ["s" + d for d in durations[1:]]


# --- project -------------------------------------------------------------------


def _is_vocal(part: Part) -> bool:
    return bool(_VOCAL.search(part.name or "")) or bool(_VOCAL.search(part.abbreviation or ""))


def _plan(score: Score) -> list[PartVars]:
    used_ids: set[str] = set()
    used_slugs: set[str] = set()
    plans = []
    for k, part in enumerate(score.parts, 1):
        label = part.name or f"Part {k}"
        base = _unique(_identifier(label), used_ids)
        slug = _unique(_slug(label), used_slugs, sep="-")
        pv = PartVars(part, slug, base)
        for staff in part.staves:
            suffix = _staff_suffix(staff.number, len(part.staves))
            sv = StaffVars(music=f"{base}{suffix}Music", voice=f"{base}{suffix}Voice")
            for verse in _verses(staff.measures):
                sv.lyrics.append(f"{base}{suffix}Verse{_NUMBER_WORDS[verse % 10]}")
            pv.staves.append(sv)
        plans.append(pv)
    return plans


def _part_file(
    pv: PartVars,
    rel: str,
    line_map: dict[tuple[str, int], tuple[str, int, str]],
    grace_leads: dict[int, list[str]],
    grid: dict[int, Fraction],
) -> str:
    out = [f'\\version "{LILYPOND_VERSION}"', ""]
    for staff, sv in zip(pv.part.staves, pv.staves, strict=True):
        out.append(f"{sv.music} = {{")
        if pv.part.transpose_semitones:
            out.append(f"  \\transposition {transposition_pitch(pv.part.transpose_semitones)}")
        body, numbers = _staff_music(pv.part.id, staff.number, staff.measures, grace_leads, grid)
        for text, number in zip(body, numbers, strict=True):
            out.append(text)
            if not text.lstrip().startswith("%"):
                line_map[(rel, len(out))] = (pv.part.id, staff.number, number)
        out.append("}")
        out.append("")
        for verse_var, verse in zip(sv.lyrics, _verses(staff.measures), strict=True):
            lines = _lyrics_lines(staff.measures, verse)
            if lines is None:
                continue
            out.append(f"{verse_var} = \\lyricmode {{")
            out += lines
            out.append("}")
            out.append("")
    return "\n".join(out).rstrip() + "\n"


def _staff_block(pv: PartVars, indent: str, with_names: bool = True) -> list[str]:
    part = pv.part
    name = part.name or ""
    short = part.abbreviation or ""
    lines: list[str] = []
    multi = len(pv.staves) > 1
    inner = indent + "  " if multi else indent
    if multi:
        lines.append(f"{indent}\\new PianoStaff \\with {{")
        if with_names and name:
            lines.append(f"{indent}  instrumentName = {lily_string(name)}")
        if with_names and short:
            lines.append(f"{indent}  shortInstrumentName = {lily_string(short)}")
        lines.append(f"{indent}}} <<")
    for staff, sv in zip(part.staves, pv.staves, strict=True):
        with_block = []
        if not multi and with_names and name:
            with_block.append(f"instrumentName = {lily_string(name)}")
        if not multi and with_names and short:
            with_block.append(f"shortInstrumentName = {lily_string(short)}")
        opening = f"{inner}\\new Staff"
        if with_block:
            opening += " \\with { " + " ".join(with_block) + " }"
        lines.append(f'{opening} \\new Voice = "{sv.voice}" \\{sv.music}')
        for verse_var in sv.lyrics:
            if _lyrics_lines(staff.measures, _verse_number(verse_var)) is not None:
                lines.append(f'{inner}\\new Lyrics \\lyricsto "{sv.voice}" \\{verse_var}')
    if multi:
        lines.append(f"{indent}>>")
    return lines


def _verse_number(var: str) -> int:
    for k, word in enumerate(_NUMBER_WORDS):
        if var.endswith("Verse" + word):
            return k
    return 1


def _score_layout(plans: list[PartVars], has_chords: bool) -> str:
    out = [f'\\version "{LILYPOND_VERSION}"', "", "\\score {", "  <<"]
    if has_chords:
        out.append("    \\new ChordNames \\harmonies")
    group = None
    if len(plans) > 1:
        group = "ChoirStaff" if all(_is_vocal(p.part) for p in plans) else "StaffGroup"
        out.append(f"    \\new {group} <<")
    indent = "      " if group else "    "
    for pv in plans:
        out += _staff_block(pv, indent)
    if group:
        out.append("    >>")
    out += ["  >>", "  \\layout { }", "  \\midi { }", "}", ""]
    return "\n".join(out)


def _part_layout(pv: PartVars, has_chords: bool) -> str:
    out = [
        f'\\version "{LILYPOND_VERSION}"',
        "",
        f'\\include "../parts/{pv.slug}.ly"',
    ]
    if has_chords:
        out.append('\\include "../chords.ly"')
    out += ["", "\\score {", "  <<"]
    if has_chords:
        out.append("    \\new ChordNames \\harmonies")
    out += _staff_block(pv, "    ")
    out += ["  >>", "  \\layout { }", "}", ""]
    return "\n".join(out)


def generate_project(score: Score) -> LyProject:
    plans = _plan(score)
    leads = _grace_leads(score)
    grid = _measure_grid(score)
    files: dict[str, str] = {}
    line_map: dict[tuple[str, int], tuple[str, int, str]] = {}
    for pv in plans:
        rel = f"parts/{pv.slug}.ly"
        files[rel] = _part_file(pv, rel, line_map, leads, grid)

    chord_lines = _chord_lines(score, leads, grid)
    if chord_lines:
        files["chords.ly"] = "\n".join(
            [
                f'\\version "{LILYPOND_VERSION}"',
                "",
                "harmonies = \\chordmode {",
                *chord_lines,
                "}",
                "",
            ]
        )
    files["layout/score.ly"] = _score_layout(plans, bool(chord_lines))
    for pv in plans:
        files[f"layout/part-{pv.slug}.ly"] = _part_layout(pv, bool(chord_lines))

    header = ["\\header {"]
    if score.title:
        header.append(f"  title = {lily_string(score.title)}")
    if score.composer:
        header.append(f"  composer = {lily_string(score.composer)}")
    header += ["  tagline = ##f", "}"]
    main = [f'\\version "{LILYPOND_VERSION}"', "", *header, ""]
    main += [f'\\include "parts/{pv.slug}.ly"' for pv in plans]
    if chord_lines:
        main.append('\\include "chords.ly"')
    main += ['\\include "layout/score.ly"', ""]
    files["main.ly"] = "\n".join(main)
    return LyProject(files=dict(sorted(files.items())), measure_lines=line_map)


def write_project(score: Score, root: Path) -> LyProject:
    project = generate_project(score)
    project.write(root)
    return project


def iter_lines(project: LyProject) -> Iterable[tuple[str, int, str]]:
    for rel, text in project.files.items():
        for n, line in enumerate(text.splitlines(), 1):
            yield rel, n, line
