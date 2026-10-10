"""The review model and recompiling after edits."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from lilyscan.ir.musicxml import load_musicxml
from lilyscan.lilypond.generate import drop_lyrics, generate_project, scan_measure_lines
from lilyscan.pipeline import convert_file, recompile
from lilyscan.qa.checks import CheckResult, QaReport
from lilyscan.review import build_review

FIXTURE = Path(__file__).parent / "fixtures" / "features.musicxml"


def test_every_measure_is_listed_with_its_source_line() -> None:
    score = load_musicxml(FIXTURE)
    project = generate_project(score)
    review = build_review(score, project, QaReport(checks=[]))
    staves = score.staves()
    assert len(review["measures"]) == sum(len(s.measures) for _, s in staves)
    for m in review["measures"]:
        assert m["file"].startswith("parts/") and m["line"] > 0
        assert project.files[m["file"]].splitlines()[m["line"] - 1].rstrip().endswith(("|", '"'))
    assert review["review"] == []  # nothing wrong with clean input


def test_issues_rank_measures() -> None:
    score = load_musicxml(FIXTURE)
    project = generate_project(score)
    qa = QaReport(
        checks=[
            CheckResult(
                "Q3",
                "rhythm",
                False,
                [
                    {
                        "part": "P1",
                        "staff": 1,
                        "measure": "2",
                        "voice": 1,
                        "actual": "2",
                        "expected": "3",
                    }
                ],
            ),
            CheckResult(
                "Q4", "range", False, [{"part": "P2", "staff": 2, "measure": "3", "pitch": "F2"}]
            ),
        ]
    )
    review = build_review(score, project, qa)
    first, second = (next(m for m in review["measures"] if m["id"] == i) for i in review["review"])
    assert (first["part"], first["number"], first["issues"][0]["kind"]) == ("P1", "2", "rhythm")
    assert (second["part"], second["number"], second["issues"][0]["kind"]) == ("P2", "3", "range")


def test_scan_follows_edits_that_shift_lines() -> None:
    project = generate_project(load_musicxml(FIXTURE))
    files = dict(project.files)
    files["parts/voice.ly"] = files["parts/voice.ly"].replace(
        "voiceMusic = {", "voiceMusic = {\n  % a note from the editor\n", 1
    )
    remapped = scan_measure_lines(files, project.staff_vars)
    shifted = {
        (rel, line + (2 if rel == "parts/voice.ly" else 0)): where
        for (rel, line), where in project.measure_lines.items()
    }
    assert remapped == shifted


@pytest.mark.lilypond
def test_recompile_after_edit_updates_checks_and_review(tmp_path: Path) -> None:
    convert_file(FIXTURE, tmp_path)
    voice = tmp_path / "ly" / "parts" / "voice.ly"
    # Insert a line (shifting everything) and break measure 2's rhythm.
    text = voice.read_text(encoding="utf-8").replace(
        "voiceMusic = {", "voiceMusic = {\n  % edited", 1
    )
    voice.write_text(text.replace("d''4.~ d''8 b'4", "d''4.~ d''8 b'2"), encoding="utf-8")

    report = recompile(tmp_path)

    checks = {c["id"]: c for c in report["qa"]["checks"]}
    assert checks["Q1"]["passed"] and not checks["Q2"]["passed"]
    assert checks["Q3"]["passed"]  # Q3-Q5 describe the IR, which the edit did not change
    review = json.loads((tmp_path / "review.json").read_text(encoding="utf-8"))
    flagged = next(m for m in review["measures"] if m["id"] == review["review"][0])
    assert (flagged["part"], flagged["number"]) == ("P1", "2")
    assert "b'2" in voice.read_text(encoding="utf-8").splitlines()[flagged["line"] - 1]
    assert report["lilypond"]["svg"] and (tmp_path / report["lilypond"]["svg"][0]).is_file()


@pytest.mark.lilypond
def test_dropping_an_instruments_lyrics_clears_the_lyrics_check(tmp_path: Path) -> None:
    violin = tmp_path / "violin.musicxml"
    violin.write_text(
        FIXTURE.read_text(encoding="utf-8").replace(
            "<part-name>Voice</part-name><part-abbreviation>Vo.</part-abbreviation>",
            "<part-name>Violin</part-name><part-abbreviation>Vln.</part-abbreviation>",
        ),
        encoding="utf-8",
    )
    out = tmp_path / "job"
    report = convert_file(violin, out)
    q9 = next(c for c in report["qa"]["checks"] if c["id"] == "Q9")
    assert not q9["passed"] and q9["details"][0]["voices"] == ["violinVoice"]
    for path in (out / "ly" / "layout").glob("*.ly"):
        path.write_text(drop_lyrics(path.read_text(encoding="utf-8"), {"violinVoice"}))

    report = recompile(out)

    checks = {c["id"]: c for c in report["qa"]["checks"]}
    assert checks["Q1"]["passed"] and checks["Q9"]["passed"]
    assert "lyricsto" not in (out / "ly" / "layout" / "score.ly").read_text(encoding="utf-8")
    assert "violinVerseOne = " in (out / "ly" / "parts" / "violin.ly").read_text(encoding="utf-8")
