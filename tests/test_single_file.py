"""A LilyPond project as one file: the score and each part."""

from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from lilyscan.ir.musicxml import parse_musicxml
from lilyscan.lilypond.compile import compile_ly
from lilyscan.lilypond.generate import generate_project
from lilyscan.lilypond.single import MERGE_RESTS, PART_LAYOUT, part_score, single_file
from lilyscan.runtime.config import Settings


def score(*names: str) -> bytes:
    parts = "".join(
        f'<score-part id="P{k}"><part-name>{n}</part-name></score-part>'
        for k, n in enumerate(names, 1)
    )
    body = ""
    for k in range(1, len(names) + 1):
        body += f'<part id="P{k}">'
        body += (
            '<measure number="1"><attributes><divisions>1</divisions>'
            "<time><beats>4</beats><beat-type>4</beat-type></time>"
            "<clef><sign>G</sign><line>2</line></clef></attributes>"
            "<note><pitch><step>C</step><octave>5</octave></pitch><duration>4</duration></note></measure>"
        )
        for n in (2, 3, 4):  # three measures' rest: one multi-measure rest in the part
            rest = '<note><rest measure="yes"/><duration>4</duration></note>'
            body += f'<measure number="{n}">{rest}</measure>'
        body += "</part>"
    return (
        '<?xml version="1.0"?><score-partwise version="4.0">'
        "<work><work-title>Duet</work-title></work>"
        f"<part-list>{parts}</part-list>{body}</score-partwise>"
    ).encode()


def test_one_file_with_a_book_for_the_score_and_each_part() -> None:
    text = single_file(generate_project(parse_musicxml(score("Violin 1", "Viola"))).files)
    assert "\\include" not in text
    assert text.count("\\book {") == 3
    assert '\\bookOutputSuffix "score"' in text
    assert '\\bookOutputSuffix "violin-1"' in text and '\\bookOutputSuffix "viola"' in text
    assert 'instrument = "Viola"' in text
    assert text.count("skipBars = ##t") == 2  # the parts, not the score
    assert text.index("violinOneMusic = {") < text.index("\\book {")
    # The parts join their runs of whole-measure rests; the score keeps every measure.
    assert text.count("mergeFullBarRests =") == 1
    assert text.index("mergeFullBarRests =") < text.index("\\book {")
    score_book, *part_books = text.split("\\book {")[1:]
    assert "\\mergeFullBarRests" not in score_book
    assert "\\mergeFullBarRests \\violinOneMusic" in part_books[0]
    assert "\\mergeFullBarRests \\violaMusic" in part_books[1]


def test_a_piano_part_keeps_its_rests_measure_by_measure() -> None:
    layout = (
        "\\score {\n  <<\n    \\new PianoStaff <<\n"
        '      \\new Staff \\new Voice = "pianoUpperVoice" \\pianoUpperMusic\n'
        '      \\new Staff \\new Voice = "pianoLowerVoice" \\pianoLowerMusic\n'
        "    >>\n  >>\n  \\layout { }\n}"
    )
    assert part_score(layout) == layout.replace("\\layout { }", PART_LAYOUT)


@pytest.mark.lilypond
def test_runs_of_whole_measure_rests_are_merged(tmp_path: Path) -> None:
    src = tmp_path / "rests.ly"
    music = (
        r"\time 4/4 R1 | R1 | R1 | c'1 | R1 | \bar " + '"||"' + r" R1 | R1 | "
        r"\time 3/4 R2. | R2. | R2. -\fermata |"
    )
    display = f"\\displayLilyMusic \\mergeFullBarRests {{ {music} }}"
    src.write_text(f'\\version "2.26.0"\n{MERGE_RESTS}\n{display}\n', encoding="utf-8")
    lilypond = Settings.from_env().lilypond_bin
    run = subprocess.run(
        [lilypond, "--loglevel=WARNING", "-dbackend=null", "-o", str(tmp_path / "rests"), str(src)],
        capture_output=True,
        text=True,
        check=False,
    )
    assert run.returncode == 0, run.stderr
    shown = " ".join(run.stdout.split())
    # Three measures as one; the lone one stays; the \bar and the time change end runs;
    # a rest with a mark (a fermata) is not merged into the run.
    assert "R1*3 | c'1 | R1 |" in shown
    assert "R1*2 |" in shown
    assert "R2.*2 | R2.\\fermata |" in shown.replace("R2. \\fermata", "R2.\\fermata")


def test_a_single_part_is_its_score() -> None:
    text = single_file(generate_project(parse_musicxml(score("Flute"))).files)
    assert "\\book" not in text and "\\include" not in text
    assert text.count("\\score {") == 1


@pytest.mark.lilypond
def test_it_compiles_to_the_score_and_each_part(tmp_path: Path) -> None:
    src = tmp_path / "duet.ly"
    src.write_text(
        single_file(generate_project(parse_musicxml(score("Violin 1", "Viola"))).files),
        encoding="utf-8",
    )
    result = compile_ly(src, tmp_path / "out", ("pdf",))
    assert result.ok, result.log
    assert sorted(p.name for p in result.outputs) == [
        "duet-score.pdf",
        "duet-viola.pdf",
        "duet-violin-1.pdf",
    ]


def test_a_long_name_is_wrapped_on_the_staff_and_whole_in_the_header() -> None:
    # "Violin 1 (melody)" does not fit the indent before the first system: it runs past the
    # page's left edge. It is wrapped into a column; the part book's header keeps it whole.
    text = single_file(
        generate_project(parse_musicxml(score("Violin 1 (melody)", "Cello and Bass"))).files
    )
    assert r'instrumentName = \markup \center-column { "Violin 1" "(melody)" }' in text
    assert r'instrumentName = \markup \center-column { "Cello and" "Bass" }' in text
    assert 'instrument = "Violin 1 (melody)"' in text and 'instrument = "Cello and Bass"' in text


@pytest.mark.lilypond
def test_wrapped_names_compile(tmp_path: Path) -> None:
    src = tmp_path / "duet.ly"
    src.write_text(
        single_file(
            generate_project(parse_musicxml(score("Violin 3 (Fiddle 2 harmony)", "Viola"))).files
        ),
        encoding="utf-8",
    )
    result = compile_ly(src, tmp_path / "out", ("pdf",))
    assert result.ok, result.log
