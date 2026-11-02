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

from lilyscan.combine import Selection, combine  # noqa: E402
from lilyscan.engine.audiveris.omr import OmrBook, attach_movements, read_omr  # noqa: E402
from lilyscan.ir.models import Score  # noqa: E402
from lilyscan.ir.musicxml import load_musicxml  # noqa: E402
from lilyscan.ir.to_musicxml import to_musicxml  # noqa: E402
from lilyscan.lilypond.compile import lilypond_version  # noqa: E402
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


def _combine_job(root: Path, score: Score) -> None:
    """A finished job as the combiner reads it: its IR and its LilyPond parts."""
    (root / "ir").mkdir(parents=True, exist_ok=True)
    (root / "ir" / "score.json").write_text(
        score.model_dump_json(indent=1), encoding="utf-8", newline="\n"
    )
    project = generate_project(score)
    project.write(root / "ly")
    for path in (root / "ly").rglob("*.ly"):
        if "parts" not in path.relative_to(root / "ly").parts:
            path.unlink()  # only the parts are read


def _write_combine(out: Path) -> None:
    import shutil

    if out.exists():
        shutil.rmtree(out)
    jobs = out / "jobs"
    quartet = Score.model_validate_json(
        (OUT / "musicxml" / "quartet-01.score.json").read_text(encoding="utf-8")
    )
    satb = Score.model_validate_json(
        (OUT / "musicxml" / "satb-01.score.json").read_text(encoding="utf-8")
    )
    _combine_job(jobs / "quartet", quartet)
    _combine_job(jobs / "satb", satb)
    # A reading that lost measure 4 of the second violin (a missed barline).
    lost = quartet.model_copy(deep=True)
    for staff in lost.parts[1].staves:
        del staff.measures[3]
        for k, m in enumerate(staff.measures):
            m.index = k
    _combine_job(jobs / "quartet-lost", lost)
    # A reading with an extra measure in the alto (a stray barline).
    extra = satb.model_copy(deep=True)
    for staff in extra.parts[1].staves:
        staff.measures.insert(2, staff.measures[2].model_copy(deep=True))
        for k, m in enumerate(staff.measures):
            m.index = k
    _combine_job(jobs / "satb-extra", extra)
    # A reading of the viola with its key misread (a sharp too many) in measures 3-5.
    rekey = quartet.model_copy(deep=True)
    ms = rekey.parts[2].staves[0].measures
    first = ms[0].key
    assert first is not None
    ms[2].key = first.model_copy(update={"fifths": first.fifths + 1})
    ms[5].key = first.model_copy()
    _combine_job(jobs / "quartet-rekey", rekey)
    # A part that lines up with too little of the score.
    short = quartet.model_copy(deep=True)
    for staff in short.parts[0].staves:
        del staff.measures[2:6]
    _combine_job(jobs / "quartet-short", short)

    def sel(
        job: str, part: str, transpose: str | None = None, name: str | None = None
    ) -> dict[str, object]:
        return {"job": f"combine/jobs/{job}", "part": part, "transpose": transpose, "name": name}

    cases = {
        "quartet": {
            "title": "Quartet, combined",
            "parts": [
                sel("quartet", "P1", "M2", "Violin 1"),
                sel("quartet-lost", "P2"),
                sel("quartet-rekey", "P3"),
                sel("quartet", "P4"),
            ],
            "piano": [],
        },
        "satb-piano": {
            "title": None,
            "parts": [sel("satb", "P1", None, "Soprano solo")],
            "piano": [sel("satb-extra", "P2"), sel("satb", "P3"), sel("satb", "P4", "-P8")],
        },
        "short": {
            "title": None,
            "parts": [sel("quartet-short", "P1"), sel("quartet", "P2")],
            "piano": [],
        },
        "bad-key": {
            "title": None,
            "parts": [sel("quartet", "P1", "d2"), sel("quartet", "P2")],
            "piano": [],
        },
    }
    for name, request in cases.items():
        d = out / "cases" / name
        d.mkdir(parents=True)
        (d / "case.json").write_text(
            json.dumps(request, indent=1) + "\n", encoding="utf-8", newline="\n"
        )

        def selections(items: list[dict[str, object]]) -> list[Selection]:
            return [
                Selection(OUT / str(s["job"]), str(s["part"]), s["transpose"], s["name"])  # type: ignore[arg-type]
                for s in items
            ]

        try:
            combine(
                selections(request["parts"]),  # type: ignore[arg-type]
                d / "job",
                request["title"],  # type: ignore[arg-type]
                piano=selections(request["piano"]) or None,  # type: ignore[arg-type]
            )
        except ValueError as exc:
            (d / "error.txt").write_text(
                f"{type(exc).__name__}: {exc}", encoding="utf-8", newline="\n"
            )
            continue
        job = d / "job"
        for p in sorted(job.rglob("*")):  # keep the text outputs only
            rel = p.relative_to(job).as_posix()
            if p.is_file() and (rel.startswith("ly/svg/") or p.suffix in (".pdf", ".midi", ".mid")):
                p.unlink()
        shutil.rmtree(job / "ly" / "svg", ignore_errors=True)
    _write_manifest(out, sorted(cases))


def _write_compare(out: Path) -> None:
    """The evaluation's scoring on committed scores read "wrong" (the combiner's jobs)."""
    from lilyscan.evaluation.compare import calibration, compare, event_correctness

    out.mkdir(parents=True, exist_ok=True)
    jobs = OUT / "combine" / "jobs"

    def load(job: str) -> Score:
        return Score.model_validate_json(
            (jobs / job / "ir" / "score.json").read_text(encoding="utf-8")
        )

    cases = []
    for gt_job, pred_job in [
        ("quartet", "quartet"),
        ("quartet", "quartet-lost"),
        ("quartet", "quartet-rekey"),
        ("quartet", "quartet-short"),
        ("satb", "satb-extra"),
        ("satb", "quartet"),
    ]:
        gt, pred = load(gt_job), load(pred_job)
        # Confidences as a calibrated run would carry them.
        calibrate_confidence(pred)
        cases.append(
            {
                "gt": gt_job,
                "pred": pred_job,
                "comparison": compare(gt, pred).to_dict(),
                "calibration": calibration(event_correctness(gt, pred)),
            }
        )
    cases.append(
        {
            "gt": "quartet",
            "pred": None,
            "comparison": compare(load("quartet"), None).to_dict(),
            "calibration": None,
        }
    )
    (out / "compare.json").write_text(
        json.dumps(cases, indent=1) + "\n", encoding="utf-8", newline="\n"
    )


def _write_calibrate(out: Path) -> None:
    """The confidence model's fit (Newton logistic, out-of-fold isotonic, ECE) on a fixed
    synthetic sample set, so the numerics are checked without engine runs."""
    import random

    from lilyscan.evaluation.calibrate import Sample, cross_validate, ece, fit
    from lilyscan.repair.confidence import FEATURES, predict

    rng = random.Random(7)
    samples = []
    for i in range(600):
        group = f"piece-{i % 23}"
        grade = rng.random()
        x = [rng.random() if k % 3 else float(rng.random() < 0.3) for k in range(len(FEATURES))]
        x[0] = grade
        right = rng.random() < 0.2 + 0.7 * grade
        samples.append(Sample(group, x, right, grade if i % 5 else None))
    model = fit(samples)
    oof = cross_validate(samples)
    result = {
        "samples": [[s.group, s.x, s.y, s.grade] for s in samples],
        "model": model,
        "ece_in_sample": ece([(predict(model, s.x), s.y) for s in samples]),
        "ece_out_of_fold": ece([(p, s.y) for p, s in zip(oof, samples, strict=True)]),
    }
    (out / "calibrate.json").write_text(
        json.dumps(result, indent=1) + "\n", encoding="utf-8", newline="\n"
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
        repairs = apply_repairs(score, book=book)
        calibrate_confidence(score)
        (om / f"{name}.repairs.json").write_text(
            json.dumps([r.to_dict() for r in repairs], indent=1) + "\n",
            encoding="utf-8",
            newline="\n",
        )
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

    # The score combiner (M9): small jobs from committed scores, some read "wrong" (a lost or
    # an extra measure, a misread key), combined as a user would. Needs LilyPond (QA).
    if with_lilypond:
        _write_combine(OUT / "combine")
        _write_compare(OUT / "eval")
        _write_calibrate(OUT / "eval")

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
