"""A LilyPond project as one file: the score and each part."""

from __future__ import annotations

from pathlib import Path

import pytest

from lilyscan.ir.musicxml import parse_musicxml
from lilyscan.lilypond.compile import compile_ly
from lilyscan.lilypond.generate import generate_project
from lilyscan.lilypond.single import single_file


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
