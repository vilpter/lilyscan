from __future__ import annotations

from fractions import Fraction

import pytest

from lilyscan.ir.models import Clef, KeySignature, Pitch
from lilyscan.lilypond.notation import (
    absolute_pitch,
    chord_root,
    clef_command,
    key_command,
    pitch_name,
    single_duration,
    split_duration,
    transposition_pitch,
    written_duration,
)


@pytest.mark.parametrize(
    ("step", "alter", "name"),
    [
        ("C", 0, "c"),
        ("C", 1, "cis"),
        ("D", -1, "des"),
        ("E", -1, "es"),
        ("E", -2, "eses"),
        ("A", -1, "as"),
        ("A", -2, "ases"),
        ("B", -1, "bes"),
        ("F", 2, "fisis"),
    ],
)
def test_pitch_names(step: str, alter: int, name: str) -> None:
    assert pitch_name(step, alter) == name


@pytest.mark.parametrize(
    ("octave", "text"), [(0, "c,,,"), (2, "c,"), (3, "c"), (4, "c'"), (6, "c'''")]
)
def test_absolute_octaves(octave: int, text: str) -> None:
    assert absolute_pitch(Pitch(step="C", octave=octave)) == text


@pytest.mark.parametrize(
    ("length", "token"),
    [
        (Fraction(4), "1"),
        (Fraction(3), "2."),
        (Fraction(7, 2), "2.."),
        (Fraction(3, 2), "4."),
        (Fraction(1, 4), "16"),
        (Fraction(8), "\\breve"),
        (Fraction(5, 4), None),
    ],
)
def test_single_duration(length: Fraction, token: str | None) -> None:
    assert single_duration(length) == token


def test_split_duration_sums_exactly() -> None:
    assert split_duration(Fraction(5)) == ["1", "4"]
    assert split_duration(Fraction(5, 4)) == ["4", "16"]


def test_written_duration_prefers_notated_type() -> None:
    assert written_duration("eighth", 1, Fraction(3, 4), None) == "8."
    assert written_duration(None, 0, Fraction(1, 3), (3, 2)) == "8"
    assert written_duration(None, 0, Fraction(3, 2), None) == "4."


def test_key_and_clef_commands() -> None:
    assert key_command(KeySignature(fifths=-3, mode="minor")) == "\\key c \\minor"
    assert key_command(KeySignature(fifths=2)) == "\\key d \\major"
    assert clef_command(Clef(sign="C", line=3)) == "\\clef alto"
    assert clef_command(Clef(sign="G", line=2, octave_change=-1)) == '\\clef "treble_8"'
    assert clef_command(Clef(sign="F", line=4)) == "\\clef bass"


def test_chord_root_and_transposition() -> None:
    assert chord_root("F#") == "fis"
    assert chord_root("Bb") == "bes"
    assert chord_root("Eb") == "es"
    assert transposition_pitch(0) == "c'"
    assert transposition_pitch(-2) == "bes"
    assert transposition_pitch(-9) == "es"
    assert transposition_pitch(12) == "c''"
