"""Generate the reference outputs the Java port is tested against (tests/parity/).

The Java build must reproduce the Python implementation byte for byte (design-java.md D19).
This script runs the Python code on the committed fixtures and writes what it produces, so the
Java tests can compare against it without Python installed. Re-run it whenever the Python
behaviour changes:

    uv run python scripts/java_parity.py

It also commits one generated seed-corpus piece per category as a MusicXML input (the seed
corpus is generated, license-clean project content). ``--sweep DIR`` additionally writes every
seed and repertoire piece with its reference into ``DIR`` for a local full sweep; repertoire
encodings come from music21's corpus and are never committed (D11):

    uv run python scripts/java_parity.py --sweep /tmp/parity-sweep
    ./gradlew test -Dlilyscan.paritySweep=/tmp/parity-sweep
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from lilyscan.engine.audiveris.omr import OmrBook, attach_movements, read_omr  # noqa: E402
from lilyscan.ir.models import Score  # noqa: E402
from lilyscan.ir.musicxml import load_musicxml  # noqa: E402
from lilyscan.lilypond.compile import lilypond_version  # noqa: E402
from lilyscan.ir.to_musicxml import to_musicxml  # noqa: E402
from lilyscan.lilypond.generate import LyProject, generate_project  # noqa: E402
from lilyscan.lilypond.single import single_file  # noqa: E402
from lilyscan.qa.checks import run_checks  # noqa: E402
from lilyscan.repair import apply_repairs  # noqa: E402
from lilyscan.repair.confidence import calibrate_confidence  # noqa: E402
from lilyscan.review import build_review  # noqa: E402
from lilyscan.synth.corpus import load_spec  # noqa: E402
from lilyscan.synth.generate import write_ground_truth  # noqa: E402

OUT = ROOT / "tests" / "parity"

# MusicXML inputs for the reader (Stage 3 baseline import), relative to the repo root.
MUSICXML_INPUTS = {
    "features": ("tests/fixtures/features.musicxml", "musicxml"),
    "piano-two-voices": ("eval/fixtures/omr/5.11.0/piano-two-voices/output.mxl", "audiveris"),
    "split-flute": ("eval/fixtures/omr/5.11.0/split-flute/output.mxl", "audiveris"),
}

# Audiveris projects (book.omr + its exported MusicXML) for the .omr reader (Stage 3).
OMR_INPUTS = {
    "piano-two-voices": "eval/fixtures/omr/5.11.0/piano-two-voices",
    "split-flute": "eval/fixtures/omr/5.11.0/split-flute",
}


def book_dump(book: OmrBook) -> dict[str, object]:
    """Everything read_omr extracts, in a form the Java reader reproduces exactly."""

    def box(b: object) -> list[float] | None:
        return None if b is None else [b.x, b.y, b.w, b.h]  # type: ignore[attr-defined]

    return {
        "software_version": book.software_version,
        "logical_parts": [[k, v] for k, v in book.logical_parts.items()],
        "sheets": [
            {
                "number": sh.number,
                "width": sh.width,
                "height": sh.height,
                "interline": sh.interline,
                "systems": [
                    {
                        "starts_movement": sy.starts_movement,
                        "stacks": [
                            {
                                "left": st.left,
                                "right": st.right,
                                "slots": [[str(t), x] for t, x in st.slots.items()],
                            }
                            for st in sy.stacks
                        ],
                        "parts": [
                            [pid, [[s.id, s.top, s.bottom] for s in staves]]
                            for pid, staves in sy.parts.items()
                        ],
                        "inters": [
                            [
                                i.id,
                                i.kind,
                                i.shape,
                                i.grade,
                                i.ctx_grade,
                                i.staff,
                                box(i.box),
                                i.step,
                                i.value,
                                i.role,
                            ]
                            for i in sy.inters
                        ],
                        "chord_of": [[k, v.id] for k, v in sy.chord_of.items()],
                    }
                    for sy in sh.systems
                ],
            }
            for sh in book.sheets
        ],
    }


# Python repr() of floats, for the JSON writer's float formatting.
FLOATS = [
    0.0,
    -0.0,
    1.0,
    -1.0,
    0.1,
    0.5,
    0.95,
    812.0,
    340.5,
    2480.0,
    1e-4,
    1e-5,
    0.0005,
    1.5e-7,
    12345678.0,
    1e15,
    1e16,
    1.2345678901234567e17,
    1e100,
    1.5e-300,
    123.456,
    0.30000000000000004,
    2 / 3,
    1 / 3,
    99999999999999.9,
    0.012345678,
    5e-324,
    1.7976931348623157e308,
    7.0e-4,
    0.07142857142857142,
    3.141592653589793,
]


def project_dump(project: LyProject) -> dict[str, object]:
    """A generated LilyPond project: its files and measure map, as the Java test compares them."""
    return {
        "files": project.files,
        "measure_lines": [[f, n, *ref] for (f, n), ref in project.measure_lines.items()],
        "staff_vars": [[var, *ref] for var, ref in project.staff_vars.items()],
    }


def _write_project(out_dir: Path, name: str, score_json: Path) -> None:
    """The generator's output for a reference score.json (so the generator is tested alone),
    and the other files written from the same score: the MusicXML export and the project as
    one file."""
    score = Score.model_validate_json(score_json.read_text(encoding="utf-8"))
    project = generate_project(score)
    (out_dir / f"{name}.project.json").write_text(
        json.dumps(project_dump(project), indent=1) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    (out_dir / f"{name}.musicxml").write_bytes(to_musicxml(score))
    (out_dir / f"{name}.single.ly").write_text(
        single_file(project.files), encoding="utf-8", newline="\n"
    )


def _write_qa(out_dir: Path, name: str, score_json: Path) -> None:
    """The QA report (Q1-Q5, Q9) of the project generated from a reference score.json; needs
    LilyPond (LILYPOND_BIN), since Q1 and Q2 compile the project."""
    score = Score.model_validate_json(score_json.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        project = generate_project(score)
        project.write(root)
        report = run_checks(score, root, project)
    (out_dir / f"{name}.qa.json").write_text(
        json.dumps(report.to_dict(), indent=1) + "\n", encoding="utf-8", newline="\n"
    )
    # The review model, as the web UI reads it (review.json).
    (out_dir / f"{name}.review.json").write_text(
        json.dumps(build_review(score, project, report)), encoding="utf-8", newline="\n"
    )


def _write_reference(out_dir: Path, name: str, musicxml: Path, source: str) -> None:
    score = load_musicxml(musicxml, source=source)
    (out_dir / f"{name}.score.json").write_text(
        score.model_dump_json(indent=1), encoding="utf-8", newline="\n"
    )


def _write_manifest(out_dir: Path, manifest: list[dict[str, str]]) -> None:
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=1) + "\n", encoding="utf-8", newline="\n"
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sweep", type=Path, help="also write every corpus piece here (local only)")
    args = ap.parse_args()

    mx = OUT / "musicxml"
    (mx / "inputs").mkdir(parents=True, exist_ok=True)
    manifest = []
    for name, (rel, source) in MUSICXML_INPUTS.items():
        _write_reference(mx, name, ROOT / rel, source)
        manifest.append({"name": name, "input": rel, "source": source})
    # One generated seed piece per category, committed as inputs.
    seen: set[str] = set()
    for spec in load_spec(ROOT / "eval" / "corpus" / "seed.json"):
        if spec.category in seen:
            continue
        seen.add(spec.category)
        path = write_ground_truth(spec, mx / "inputs")
        _write_reference(mx, spec.id, path, "musicxml")
        manifest.append(
            {"name": spec.id, "input": str(path.relative_to(ROOT)), "source": "musicxml"}
        )
    _write_manifest(mx, manifest)

    om = OUT / "omr"
    om.mkdir(parents=True, exist_ok=True)
    omr_manifest = []
    for name, rel in OMR_INPUTS.items():
        book = read_omr(ROOT / rel / "book.omr")
        (om / f"{name}.book.json").write_text(
            json.dumps(book_dump(book), indent=1) + "\n", encoding="utf-8", newline="\n"
        )
        movements = [load_musicxml(ROOT / rel / "output.mxl", "audiveris")]
        score, stats = attach_movements(movements, book)
        assert stats is not None
        (om / f"{name}.score.json").write_text(
            score.model_dump_json(indent=1), encoding="utf-8", newline="\n"
        )
        (om / f"{name}.stats.json").write_text(
            json.dumps(
                {
                    "events": stats.events,
                    "mappable": stats.mappable,
                    "located": stats.located,
                    "pitch_mismatch": stats.pitch_mismatch,
                    "measures": stats.measures,
                    "measures_located": stats.measures_located,
                    "unmapped_staves": stats.unmapped_staves,
                },
                indent=1,
            )
            + "\n",
            encoding="utf-8",
            newline="\n",
        )
        # The same score after the repair rules and the calibrated confidence: provenance,
        # staff-level fix notes and low confidences for the generator (and Stage 5 later).
        apply_repairs(score, book=book)
        calibrate_confidence(score)
        (om / f"{name}.repaired.score.json").write_text(
            score.model_dump_json(indent=1), encoding="utf-8", newline="\n"
        )
        omr_manifest.append({"name": name, "dir": rel, "movements": ["output.mxl"]})
    _write_manifest(om, omr_manifest)

    # Stage 7: the LilyPond project generated from every reference score above.
    ly = OUT / "lilypond"
    ly.mkdir(parents=True, exist_ok=True)
    ly_manifest = []
    for entry in manifest:
        ly_manifest.append({"name": entry["name"], "score": f"musicxml/{entry['name']}.score.json"})
    for entry in omr_manifest:
        for kind in ("score", "repaired.score"):
            ly_manifest.append(
                {
                    "name": f"{entry['name']}.{kind.split('.')[0]}",
                    "score": f"omr/{entry['name']}.{kind}.json",
                }
            )
    with_lilypond = lilypond_version() is not None
    if not with_lilypond:
        print("LilyPond not found: QA references (Q1, Q2) left as they are")
    for entry in ly_manifest:
        _write_project(ly, entry["name"], OUT / entry["score"])
        if with_lilypond:
            _write_qa(ly, entry["name"], OUT / entry["score"])
    _write_manifest(ly, ly_manifest)

    if args.sweep is not None:
        sweep = args.sweep.resolve()
        (sweep / "inputs").mkdir(parents=True, exist_ok=True)
        entries = []
        for spec_file in ("seed.json", "repertoire.json"):
            for spec in load_spec(ROOT / "eval" / "corpus" / spec_file):
                path = write_ground_truth(spec, sweep / "inputs")
                _write_reference(sweep, spec.id, path, "musicxml")
                _write_project(sweep, spec.id, sweep / f"{spec.id}.score.json")
                if with_lilypond:
                    _write_qa(sweep, spec.id, sweep / f"{spec.id}.score.json")
                entries.append({"name": spec.id, "input": str(path), "source": "musicxml"})
        _write_manifest(sweep, entries)
        print(f"wrote {len(entries)} sweep pieces to {sweep}")
    (OUT / "floats.json").write_text(
        json.dumps([[f.hex(), repr(f)] for f in FLOATS], indent=1) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"wrote references to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
