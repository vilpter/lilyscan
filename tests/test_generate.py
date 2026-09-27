"""Golden-file tests for the LilyPond generator.

After an intentional change to the output, regenerate the golden files with
LILYSCAN_UPDATE_GOLDEN=1 and review the diff.
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from lilyscan.ir.models import BBox, Provenance
from lilyscan.ir.musicxml import load_musicxml
from lilyscan.lilypond.generate import generate_project, write_project
from lilyscan.qa.checks import run_checks

FIXTURE = Path(__file__).parent / "fixtures" / "features.musicxml"
GOLDEN = Path(__file__).parent / "golden" / "features"


def test_matches_golden_files() -> None:
    project = generate_project(load_musicxml(FIXTURE))
    if os.environ.get("LILYSCAN_UPDATE_GOLDEN") == "1":
        for rel, text in project.files.items():
            path = GOLDEN / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8", newline="\n")
    expected = {
        p.relative_to(GOLDEN).as_posix(): p.read_text(encoding="utf-8")
        for p in sorted(GOLDEN.rglob("*.ly"))
    }
    assert project.files == expected


def test_output_is_deterministic() -> None:
    score = load_musicxml(FIXTURE)
    assert generate_project(score).files == generate_project(score.model_copy(deep=True)).files


def test_every_measure_line_is_mapped() -> None:
    project = generate_project(load_musicxml(FIXTURE))
    mapped = {(rel, line) for rel, line in project.measure_lines}
    for rel, text in project.files.items():
        if not rel.startswith("parts/"):
            continue
        for n, line in enumerate(text.splitlines(), 1):
            if line.rstrip().endswith("|") or " | \\bar" in line:
                assert (rel, n) in mapped, (rel, n, line)


def test_low_confidence_and_repair_markers() -> None:
    score = load_musicxml(FIXTURE)
    event = score.parts[0].staves[0].measures[1].voices[0].events[0]
    event.confidence = 0.42
    event.bbox = BBox(page=1, x=812.4, y=340.2, w=10, h=10)
    event.provenance.append(Provenance(stage="repair", rule="missing-dot"))
    text = generate_project(score).files["parts/voice.ly"]
    assert "g'2 %{ ?? conf=0.42 bbox=p2:(812,340) %} %{ fix: missing-dot %}" in text


HOSTILE_LYRICS = ["V2.26.0", "__", "--", "{x}", "a\\b", 'say "hi"', "2nd", "#t", "l'amour", "Kö-"]


@pytest.mark.lilypond
def test_engine_text_in_lyrics_always_compiles(tmp_path: Path) -> None:
    """OCR can put page footers or noise into lyrics; the project must still compile."""
    score = load_musicxml(FIXTURE)
    events = [e for m in score.parts[0].staves[0].measures for v in m.voices for e in v.events]
    sung = [e for e in events if e.lyrics]
    for event, text in zip(sung, HOSTILE_LYRICS, strict=False):
        event.lyrics[0].text = text
    project = write_project(score, tmp_path)
    report = run_checks(score, tmp_path, project)
    assert report.check("Q1").passed, report.check("Q1").details
    assert "l'amour" in project.files["parts/voice.ly"]  # plain words stay unquoted
    assert '"V2.26.0"' in project.files["parts/voice.ly"]


@pytest.mark.lilypond
def test_generated_project_passes_all_checks(tmp_path: Path) -> None:
    score = load_musicxml(FIXTURE)
    project = write_project(score, tmp_path)
    report = run_checks(score, tmp_path, project)
    assert report.passed, report.to_dict()
    assert "main.pdf" in report.outputs
