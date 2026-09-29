from __future__ import annotations

from pathlib import Path

import pytest

from lilyscan.lilypond.compile import HELLO_WORLD, compile_ly, lilypond_version, parse_diagnostics
from lilyscan.runtime.config import LILYPOND_VERSION

LOG = """\
Processing `/tmp/x/score.ly'
/tmp/x/score.ly:7:14: warning: bar check failed at: 1/4
  g4 a b c
/tmp/x/old.ly:2:1: warning: barcheck failed at: 3/4
C:\\Users\\me\\score.ly:12:3: error: syntax error, unexpected '}'
warning: no \\version statement found
fatal error: failed files: "score.ly"
"""


def test_parse_diagnostics() -> None:
    d = parse_diagnostics(LOG)
    assert [x.severity for x in d] == ["warning", "warning", "error", "warning", "fatal error"]
    assert d[0].is_barcheck and d[0].line == 7 and d[0].column == 14
    assert d[1].is_barcheck  # 2.24 wording
    assert d[2].file == "C:\\Users\\me\\score.ly" and d[2].line == 12
    assert d[3].file is None and not d[3].is_barcheck
    assert d[4].is_error


def test_hello_world_pins_version() -> None:
    assert f'\\version "{LILYPOND_VERSION}"' in HELLO_WORLD


@pytest.mark.lilypond
def test_installed_version_matches_pin() -> None:
    assert lilypond_version() == LILYPOND_VERSION


@pytest.mark.lilypond
def test_compile_hello_world(tmp_path: Path) -> None:
    src = tmp_path / "hello.ly"
    src.write_text(HELLO_WORLD, encoding="utf-8")
    result = compile_ly(src, tmp_path / "out", ("pdf", "svg"))
    assert result.ok, result.log
    assert {p.suffix for p in result.outputs} == {".pdf", ".svg"}


@pytest.mark.lilypond
def test_compile_with_relative_paths(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    Path("src").mkdir()
    Path("src/hello.ly").write_text(HELLO_WORLD, encoding="utf-8")
    result = compile_ly(Path("src/hello.ly"), Path("build/out"))
    assert result.ok, result.log
    assert (tmp_path / "build" / "out" / "hello.pdf").is_file()


@pytest.mark.lilypond
def test_compile_reports_barcheck(tmp_path: Path) -> None:
    src = tmp_path / "bad.ly"
    src.write_text(
        f'\\version "{LILYPOND_VERSION}"\n\\fixed c\' {{ \\time 4/4 c4 d e | f1 | }}\n',
        encoding="utf-8",
    )
    result = compile_ly(src, tmp_path / "out")
    assert result.ok  # a failed bar check is a warning, not an error
    assert any(d.is_barcheck for d in result.warnings)


@pytest.mark.lilypond
def test_compile_reports_syntax_error(tmp_path: Path) -> None:
    src = tmp_path / "broken.ly"
    src.write_text(f'\\version "{LILYPOND_VERSION}"\n{{ c4 d e }} }}\n', encoding="utf-8")
    result = compile_ly(src, tmp_path / "out")
    assert not result.ok
    assert result.errors


@pytest.mark.lilypond
def test_a_run_that_writes_no_pdf_leaves_no_old_one(tmp_path: Path) -> None:
    out = tmp_path / "out"
    out.mkdir()
    (out / "empty.pdf").write_bytes(b"%PDF from an earlier run")
    (out / "empty.mid").write_bytes(b"MThd")
    src = tmp_path / "empty.ly"
    src.write_text(f'\\version "{LILYPOND_VERSION}"\n', encoding="utf-8")  # no music
    result = compile_ly(src, out, ("pdf",))
    assert not result.ok and result.outputs == []
    assert [d.message for d in result.errors] == ["LilyPond wrote no PDF file"]
    assert not (out / "empty.pdf").exists() and not (out / "empty.mid").exists()


def test_programming_errors_are_warnings() -> None:
    [d] = parse_diagnostics("programming error: Multi measure rest seems misplaced.")
    assert d.is_internal and not d.is_error
