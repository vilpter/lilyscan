"""Stage 5 repair rules: part merging and octave clefs."""

from __future__ import annotations

from fractions import Fraction
from pathlib import Path

import pytest

from lilyscan.ir.models import (
    BBox,
    Clef,
    Event,
    Lyric,
    Measure,
    NoteHead,
    Part,
    Pitch,
    Score,
    Staff,
    TimeSignature,
    Voice,
)
from lilyscan.ir.musicxml import load_musicxml
from lilyscan.lilypond.generate import generate_project
from lilyscan.qa.checks import QaReport
from lilyscan.repair import apply_repairs
from lilyscan.repair.chords import parse_chord_name
from lilyscan.repair.clefs import octave_clefs
from lilyscan.repair.lyrics import clean_lyrics
from lilyscan.repair.parts import merge_split_parts, names_compatible
from lilyscan.repair.rhythm import TYPES as RHYTHM_TYPES
from lilyscan.repair.rhythm import repair_rhythm
from lilyscan.review import build_review
from lilyscan.runtime.config import AUDIVERIS_VERSION

FIXTURES = Path(__file__).parents[1] / "eval" / "fixtures" / "omr" / AUDIVERIS_VERSION
MEASURES = 4


def note(step: str, octave: int, offset: int = 0) -> Event:
    return Event(
        kind="note",
        offset=Fraction(offset),
        duration=Fraction(1),
        notes=[NoteHead(pitch=Pitch(step=step, octave=octave))],  # type: ignore[arg-type]
    )


def filler() -> Event:
    return Event(kind="rest", offset=Fraction(0), duration=Fraction(2), measure_rest=True)


def part(
    pid: str,
    name: str,
    present: range,
    clef: Clef,
    pitch: tuple[str, int] = ("C", 5),
) -> Part:
    """A single-staff part in 2/4 whose notes are only in the measures ``present``."""
    measures = []
    for i in range(MEASURES):
        events = [note(*pitch), note(*pitch, 1)] if i in present else [filler()]
        measures.append(
            Measure(
                index=i,
                number=str(i + 1),
                clefs=[clef] if i == 0 else [],
                time=TimeSignature(beats=2, beat_type=4) if i == 0 else None,
                voices=[Voice(number=1, events=events)],
            )
        )
    return Part(id=pid, name=name, abbreviation=name, staves=[Staff(number=1, measures=measures)])


TREBLE = Clef(sign="G", line=2)
BASS = Clef(sign="F", line=4)
FIRST, LATER = range(0, 2), range(2, MEASURES)


def split_choir(bass_abbreviation: str = "B.") -> Score:
    """What Audiveris exports when later systems use abbreviations: eight half-parts."""
    return Score(
        parts=[
            part("P1", "S.", LATER, TREBLE),
            part("P2", "A.", LATER, TREBLE),
            part("P3", "T.", LATER, TREBLE),
            part("P4", bass_abbreviation, LATER, BASS, ("C", 3)),
            part("P5", "Soprano", FIRST, TREBLE),
            part("P6", "Alto", FIRST, TREBLE),
            part("P7", "Tenor", FIRST, TREBLE),
            part("P8", "Bass", FIRST, BASS, ("C", 3)),
        ]
    )


@pytest.mark.parametrize(
    ("a", "b", "same"),
    [
        ("Fl.", "Flute", True),
        ("F1.", "Flute", True),  # OCR read the l as a 1
        ("FI.", "Flute", True),  # ... or as a capital I
        ("Vc.", "Cello", True),  # an abbreviation of another name for the instrument
        ("Vln. I", "Violin I", True),
        ("Vln. I", "Violin II", False),
        ("Vln.", "Violin II", True),  # number not read
        ("Vla.", "Viola", True),
        ("Ob.", "Flute", False),
        ("S.", "Alto", False),
        ("13.", "Bass", False),  # no letters: unreadable
    ],
)
def test_names_compatible(a: str, b: str, same: bool) -> None:
    assert names_compatible(a, b) is same


def test_merges_parts_split_between_systems() -> None:
    score = split_choir()
    repairs = merge_split_parts(score)

    assert [p.name for p in score.parts] == ["Soprano", "Alto", "Tenor", "Bass"]
    assert [p.abbreviation for p in score.parts] == ["S.", "A.", "T.", "B."]
    for p in score.parts:
        m = p.staves[0].measures
        assert all(e.kind == "note" for x in m for v in x.voices for e in v.events)
        moved = [e for x in m[2:] for v in x.voices for e in v.events]
        assert all(
            pr.stage == "repair" and pr.rule == "part-merge" for e in moved for pr in e.provenance
        )
    assert repairs[0].detail == "merged P1 (S.) into P5 (Soprano)"
    assert repairs[0].measures == ["3", "4"]
    assert [pr.rule for pr in score.provenance] == ["part-merge"] * 4


def test_a_system_of_same_named_parts_is_matched_top_to_bottom() -> None:
    # Three violins read "Violin" on the first system and "Vln." after it. The later
    # parts come in another order than on the page; their boxes give the order.
    pitches = [("C", 6), ("A", 5), ("F", 5)]
    first = [part(f"P{k + 1}", "Violin", FIRST, TREBLE, pitches[k]) for k in range(3)]
    later = [part(f"P{k + 4}", "Vln.", LATER, TREBLE, pitches[k]) for k in range(3)]
    for k, p in enumerate(first + later):
        present = FIRST if p in first else LATER
        p.staves[0].measures[present[0]].bbox = BBox(page=0, x=0, y=100.0 * (k % 3), w=10, h=10)
    score = Score(parts=[later[2], later[0], later[1], *first])
    merge_split_parts(score)
    assert len(score.parts) == 3
    for p, (step, octave) in zip(score.parts, pitches, strict=True):
        heads = [
            h.pitch
            for m in p.staves[0].measures
            for v in m.voices
            for e in v.events
            for h in e.notes
        ]
        assert {(h.step, h.octave) for h in heads} == {(step, octave)}


def test_unreadable_name_is_placed_by_its_neighbours() -> None:
    score = split_choir(bass_abbreviation="13.")
    merge_split_parts(score)
    assert [p.name for p in score.parts] == ["Soprano", "Alto", "Tenor", "Bass"]


def test_does_not_merge_overlapping_or_different_clefs() -> None:
    overlapping = Score(
        parts=[part("P1", "Fl.", range(1, 4), TREBLE), part("P2", "Flute", FIRST, TREBLE)]
    )
    assert merge_split_parts(overlapping) == []
    assert len(overlapping.parts) == 2

    other_clef = Score(parts=[part("P1", "Vc.", LATER, TREBLE), part("P2", "Cello", FIRST, BASS)])
    assert merge_split_parts(other_clef) == []


def test_a_rest_with_a_page_box_is_present() -> None:
    # With geometry, a measure rest the engine read (it has a box) is real music, so
    # both parts sit in the same system and are not merged.
    score = Score(parts=[part("P1", "Fl.", LATER, TREBLE), part("P2", "Flute", FIRST, TREBLE)])
    score.parts[0].staves[0].measures[0].bbox = BBox(page=0, x=0, y=0, w=10, h=10)
    assert merge_split_parts(score) == []


def test_single_line_score_merges_a_placeholder_name() -> None:
    # A lead sheet: Audiveris named the first system's staff "Voice" (its placeholder)
    # and read the later abbreviation as "E.Pno"; one staff per system, so one part.
    score = Score(parts=[part("P1", "E.Pno", LATER, TREBLE), part("P2", "Voice", FIRST, TREBLE)])
    assert [r.detail for r in merge_split_parts(score)] == ["merged P1 (E.Pno) into P2 (Voice)"]


def test_single_line_score_keeps_differently_named_parts() -> None:
    score = Score(parts=[part("P1", "Alto", LATER, TREBLE), part("P2", "Soprano", FIRST, TREBLE)])
    assert merge_split_parts(score) == []


def test_parts_first_seen_later_keep_their_place_in_the_system() -> None:
    # Clarinet only appears from the second system, between flute and bassoon.
    score = Score(
        parts=[
            part("P1", "Fl.", LATER, TREBLE),
            part("P2", "Cl.", LATER, TREBLE),
            part("P3", "Fg.", LATER, BASS),
            part("P4", "Flute", FIRST, TREBLE),
            part("P5", "Bassoon", FIRST, BASS),
        ]
    )
    merge_split_parts(score)
    assert [p.name for p in score.parts] == ["Flute", "Cl.", "Bassoon"]


def test_octave_clef_lowers_a_tenor_read_in_plain_treble() -> None:
    score = split_choir()
    repairs = apply_repairs(score)  # merges first, then sees the whole tenor line

    assert [r.rule for r in repairs] == ["part-merge"] * 4 + ["octave-clef"]
    tenor = score.parts[2].staves[0]
    assert tenor.measures[0].clefs == [Clef(sign="G", line=2, octave_change=-1)]
    heads = [h for m in tenor.measures for v in m.voices for e in v.events for h in e.notes]
    assert {h.pitch.octave for h in heads} == {4}
    first = tenor.measures[0].voices[0].events[0]
    assert first.provenance[-1].rule == "octave-clef"
    assert first.provenance[-1].before == {"pitches": ["C5"]}
    # The other voices are untouched.
    assert {
        h.pitch.octave
        for m in score.parts[0].staves[0].measures
        for v in m.voices
        for e in v.events
        for h in e.notes
    } == {5}


def test_octave_clef_leaves_a_tenor_already_in_range() -> None:
    score = Score(
        parts=[
            part("P1", "Soprano", range(MEASURES), TREBLE),
            part("P2", "Alto", range(MEASURES), TREBLE),
            part("P3", "Tenor", range(MEASURES), TREBLE, ("A", 3)),
            part("P4", "Bass", range(MEASURES), BASS, ("C", 3)),
        ]
    )
    assert octave_clefs(score) == []
    assert octave_clefs(Score(parts=[part("P1", "Flute", range(MEASURES), TREBLE)])) == []


def test_generator_notes_staff_repairs_once() -> None:
    score = split_choir()
    apply_repairs(score)
    project = generate_project(score)
    tenor = project.files["parts/tenor.ly"]
    assert tenor.count("% fix: octave-clef: treble clef read without its 8") == 1
    assert tenor.count("% fix: part-merge: merged P3 (T.) into P7 (Tenor)") == 1
    assert "%{ fix:" not in tenor  # no per-note markers for staff-wide repairs
    assert '\\clef "treble_8"' in tenor


def test_review_lists_repairs() -> None:
    score = split_choir()
    apply_repairs(score)
    review = build_review(score, generate_project(score), QaReport(checks=[]))
    assert [r["rule"] for r in review["repairs"]] == ["part-merge"] * 4 + ["octave-clef"]
    assert review["repairs"][-1]["part"] == "P7"
    assert review["review"] == []  # staff-wide repairs do not flag every measure


def test_recorded_engine_output_split_flute() -> None:
    score = load_musicxml(FIXTURES / "split-flute" / "output.mxl", "audiveris")
    repairs = apply_repairs(score)
    assert [r.detail for r in repairs] == ["merged P1 (F1.) into P2 (Flute)"]
    assert [p.name for p in score.parts] == ["Flute"]


@pytest.mark.parametrize(
    ("text", "root", "kind", "bass"),
    [
        ("C", "C", "major", None),
        ("F#m7b5", "F#", "half-diminished", None),
        ("Bb/D", "Bb", "major", "D"),
        ("Cm7", "C", "minor-seventh", None),
        ("CM7", "C", "major-seventh", None),
        ("FSUS4", "F", "suspended-fourth", None),  # OCR upper case
        ("E♭°7", "Eb", "diminished-seventh", None),
    ],
)
def test_parse_chord_name(text: str, root: str, kind: str, bass: str | None) -> None:
    name = parse_chord_name(text)
    assert name is not None and (name.root, name.kind, name.bass) == (root, kind, bass)


@pytest.mark.parametrize("text", ["Gott", "Ag", "H", "GQ", "Cmaj7x", ""])
def test_parse_chord_name_rejects_words(text: str) -> None:
    assert parse_chord_name(text) is None


def sung(words: list[str], verses: dict[int, list[str]] | None = None) -> Score:
    """One staff, one measure per syllable; ``verses`` adds lines by verse number."""
    lines = verses or {1: words}
    measures = []
    for i in range(max(len(v) for v in lines.values())):
        e = note("C", 5)
        e.lyrics = [Lyric(text=v[i], verse=n) for n, v in lines.items() if i < len(v) and v[i]]
        measures.append(
            Measure(
                index=i,
                number=str(i + 1),
                clefs=[TREBLE] if i == 0 else [],
                time=TimeSignature(beats=1, beat_type=4) if i == 0 else None,
                voices=[Voice(number=1, events=[e])],
            )
        )
    return Score(parts=[Part(id="P1", name="Voice", staves=[Staff(number=1, measures=measures)])])


def lyrics_of(score: Score) -> dict[int, list[str]]:
    out: dict[int, list[str]] = {}
    for m in score.parts[0].staves[0].measures:
        for ly in m.voices[0].events[0].lyrics:
            out.setdefault(ly.verse, []).append(ly.text)
    return out


def test_stray_line_above_the_lyrics_is_dropped_and_verses_renumbered() -> None:
    score = sung([], {1: ["x,", "", "m"], 2: ["Ky", "ri", "e"]})
    repairs = clean_lyrics(score)
    assert lyrics_of(score) == {1: ["Ky", "ri", "e"]}
    assert [r.rule for r in repairs] == ["lyric-text", "lyric-verse"]
    first = score.parts[0].staves[0].measures[0].voices[0].events[0]
    assert first.provenance[-1].before == {"lyrics": [[1, "x,"], [2, "Ky"]]}
    assert score.provenance[-1].rule == "lyric-verse"


def test_page_text_line_is_dropped() -> None:
    score = sung([], {1: ["Ky", "ri", "e"], 2: ["LilyPond", "V2.26.0", ""]})
    clean_lyrics(score)
    assert lyrics_of(score) == {1: ["Ky", "ri", "e"]}


def test_a_scanner_watermark_is_page_text() -> None:
    # "Scanned by CamScanner" at the foot of a scanned part, read as one line of lyrics.
    score = sung(["A", "B", "C"], {1: ["", "Scanned", "by CamScanner"]})
    clean_lyrics(score)
    assert lyrics_of(score) == {}


def test_chord_names_become_chord_symbols() -> None:
    score = sung([], {1: ["A", "ma", "zing"], 2: ["Cm7", "", "E7"]})
    clean_lyrics(score)
    assert lyrics_of(score) == {1: ["A", "ma", "zing"]}
    chords = [
        (m.index, c.root, c.kind)
        for m in score.parts[0].staves[0].measures
        for c in m.chord_symbols
    ]
    assert chords == [(0, "C", "minor-seventh"), (2, "E", "dominant")]


def test_real_lyrics_are_left_alone() -> None:
    words = ["O", "Tan", "nen", "baum", "A", "Da", "Em"]
    score = sung(words)
    assert clean_lyrics(score) == []
    assert lyrics_of(score) == {1: words}


def test_verses_are_renumbered_per_system() -> None:
    score = sung([], {1: ["la", "la", "", ""], 2: ["", "", "lo", "lo"]})
    for i, m in enumerate(score.parts[0].staves[0].measures):
        # Two systems: measures 1-2 and 3-4 (x restarts at the left margin).
        m.bbox = BBox(page=0, x=100.0 + 200 * (i % 2), y=100.0 + 300 * (i // 2), w=150, h=80)
    clean_lyrics(score)
    assert lyrics_of(score) == {1: ["la", "la", "lo", "lo"]}


def ev(note_type: str, offset: Fraction, dots: int = 0, pitch: tuple[str, int] = ("C", 5)) -> Event:
    base = RHYTHM_TYPES[note_type]
    return Event(
        kind="note",
        offset=offset,
        duration=base * (2 - Fraction(1, 2**dots)),
        note_type=note_type,
        dots=dots,
        notes=[NoteHead(pitch=Pitch(step=pitch[0], octave=pitch[1]))],  # type: ignore[arg-type]
    )


def line(*types: str) -> list[Event]:
    """Sequential events from type names ("quarter", "eighth", "quarter." for dotted)."""
    out, pos = [], Fraction(0)
    for t in types:
        e = ev(t.rstrip("."), pos, dots=len(t) - len(t.rstrip(".")))
        out.append(e)
        pos += e.duration
    return out


def rhythm_score(*staves: list[list[Event]], beats: int = 4) -> Score:
    """One part per staff; each staff is a list of measures (events of one voice)."""
    parts = []
    for k, measures in enumerate(staves):
        ms = [
            Measure(
                index=i,
                number=str(i + 1),
                time=TimeSignature(beats=beats, beat_type=4) if i == 0 else None,
                voices=[Voice(number=1, events=events)],
            )
            for i, events in enumerate(measures)
        ]
        parts.append(
            Part(id=f"P{k + 1}", name=f"Part {k + 1}", staves=[Staff(number=1, measures=ms)])
        )
    return Score(parts=parts)


FULL = ["quarter"] * 4


def test_missed_triplet_is_restored() -> None:
    # The middle measure: a triplet read as three plain eighths overfills 4/4.
    score = rhythm_score(
        [
            line(*FULL),
            line("eighth", "eighth", "eighth", "quarter", "quarter", "quarter"),
            line(*FULL),
        ]
    )
    repairs = repair_rhythm(score)
    events = score.parts[0].staves[0].measures[1].voices[0].events
    assert [e.tuplet for e in events] == [(3, 2)] * 3 + [None] * 3
    assert [e.offset for e in events] == [0, Fraction(1, 3), Fraction(2, 3), 1, 2, 3]
    assert events[0].provenance[-1].before == {
        "duration": "1/2",
        "note_type": "eighth",
        "dots": 0,
        "tuplet": None,
    }
    assert [(r.rule, r.detail, r.measures) for r in repairs] == [
        ("rhythm", "Part 1: triplet to fill the measure", ["2"])
    ]
    assert "\\tuplet 3/2" in generate_project(score).files["parts/part-1.ly"]


def test_missed_dot_is_restored_when_another_staff_confirms_it() -> None:
    # Upper staff read as q e q q (3.5 beats); the lower staff has q. e h.
    upper = [line(*FULL), line("quarter", "eighth", "quarter", "quarter"), line(*FULL)]
    lower = [line(*FULL), line("quarter.", "eighth", "half"), line(*FULL)]
    score = rhythm_score(upper, lower)
    repair_rhythm(score)
    # Only a dot on the first quarter lines the upper staff up with the lower one.
    events = score.parts[0].staves[0].measures[1].voices[0].events
    assert [e.dots for e in events] == [1, 0, 0, 0]
    assert [e.offset for e in events] == [0, Fraction(3, 2), 2, 3]


def test_tied_candidates_are_not_applied() -> None:
    # One eighth short, and a dot on the second quarter or a longer last note fit equally.
    upper = [line(*FULL), line("quarter", "quarter", "quarter", "eighth"), line(*FULL)]
    lower = [line(*FULL), line("quarter", "quarter.", "eighth", "quarter"), line(*FULL)]
    assert repair_rhythm(rhythm_score(upper, lower)) == []


def test_unconfirmed_single_edit_is_left_flagged() -> None:
    # A solo line one eighth short: a dot fits on any of the three quarters.
    score = rhythm_score(
        [line(*FULL), line("quarter", "quarter", "quarter", "eighth"), line(*FULL)]
    )
    assert repair_rhythm(score) == []


def test_short_measure_in_every_voice_is_left_alone() -> None:
    # A phrase-end measure two beats long in every staff is meant to be short.
    short = line("half")
    score = rhythm_score(
        [line(*FULL), short, line(*FULL)], [line(*FULL), line("quarter", "quarter"), line(*FULL)]
    )
    assert repair_rhythm(score) == []


def test_the_same_dot_missed_in_every_staff_is_restored() -> None:
    # Homorhythm read as q e h (3.5 beats) in every staff: half a beat short is no
    # phrase-end measure, and each staff needs the same dot on its first note.
    bars = [line(*FULL), line("quarter", "eighth", "half"), line(*FULL)]
    score = rhythm_score(bars, [list(b) for b in bars], [list(b) for b in bars])
    repairs = repair_rhythm(score)
    assert [r.measures for r in repairs] == [["2"], ["2"], ["2"]]
    for part in score.parts:
        events = part.staves[0].measures[1].voices[0].events
        assert [e.dots for e in events] == [1, 0, 0]
        assert [e.offset for e in events] == [0, Fraction(3, 2), 2]


def test_missed_rests_are_put_where_the_notes_stand() -> None:
    # Pizzicato quarters on beats 1 and 3, their rests not seen: the notes stand where
    # beats 1 and 3 fall across the measure.
    notes = line("quarter", "quarter")
    for e, x in zip(notes, (40.0, 220.0), strict=True):
        e.bbox = BBox(page=0, x=x, y=0, w=12, h=10)
    score = rhythm_score([line(*FULL), notes, line(*FULL)])
    score.parts[0].staves[0].measures[1].bbox = BBox(page=0, x=0, y=0, w=400, h=40)
    repairs = repair_rhythm(score)
    assert [(r.detail, r.measures) for r in repairs] == [
        ("Part 1: rests to fill the measure", ["2"])
    ]
    events = score.parts[0].staves[0].measures[1].voices[0].events
    assert [(e.kind, e.offset) for e in events] == [
        ("note", 0),
        ("rest", 1),
        ("note", 2),
        ("rest", 3),
    ]


def test_rests_are_not_put_in_without_page_positions() -> None:
    score = rhythm_score([line(*FULL), line("quarter", "quarter"), line(*FULL)])
    assert repair_rhythm(score) == []


def test_pickup_is_not_filled() -> None:
    score = rhythm_score(
        [line("half"), line(*FULL), line(*FULL)],
        [line("quarter", "quarter"), line(*FULL), line(*FULL)],
    )
    assert repair_rhythm(score) == []


def test_review_shows_what_a_repair_changed_in_a_measure() -> None:
    score = rhythm_score(
        [
            line(*FULL),
            line("eighth", "eighth", "eighth", "quarter", "quarter", "quarter"),
            line(*FULL),
        ]
    )
    repair_rhythm(score)
    review = build_review(score, generate_project(score), QaReport(checks=[]))
    second = review["measures"][1]
    assert [(r["rule"], r["offset"], r["detail"]) for r in second["repairs"]] == [
        ("rhythm", "0", "was eighth"),
        ("rhythm", "1/3", "was eighth"),
        ("rhythm", "2/3", "was eighth"),
    ]
    assert review["measures"][0]["repairs"] == []
