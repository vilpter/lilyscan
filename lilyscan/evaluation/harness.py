"""Run the Audiveris baseline over a built corpus and record metrics."""

from __future__ import annotations

import contextlib
import json
import logging
import platform
from collections import Counter, defaultdict
from collections.abc import Iterable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from statistics import mean
from typing import Any

from lilyscan import __version__
from lilyscan.engine.audiveris.omr import OmrError, attach_movements, read_omr
from lilyscan.engine.audiveris.runner import audiveris_version, run_audiveris
from lilyscan.evaluation.compare import Comparison, calibration, compare, event_correctness
from lilyscan.ir.models import Score
from lilyscan.ir.musicxml import MusicXMLError, load_musicxml
from lilyscan.lilypond.compile import lilypond_version
from lilyscan.pipeline import expected_right, produce
from lilyscan.repair import apply_repairs
from lilyscan.repair.confidence import calibrate_confidence
from lilyscan.runtime.config import Settings
from lilyscan.synth.corpus import VARIANTS, CorpusItem, Variant

log = logging.getLogger(__name__)


@dataclass
class ItemResult:
    item: CorpusItem
    variant: Variant
    comparison: Comparison
    wall_s: float | None
    engine_errors: list[str]
    cached: bool
    # Q1-Q5 outcome of the full pipeline on the engine output (None when not run).
    qa: dict[str, bool] | None = None
    # .omr geometry: events that could be located vs. those that could be mapped.
    located: int = 0
    mappable: int = 0
    # (confidence, correct) per engine event, for calibration; not serialized per item.
    correctness: list[tuple[float | None, bool]] = field(default_factory=list)
    # Stage 5 repairs applied to the engine output (only with ``repair=True``).
    repairs: list[dict[str, Any]] = field(default_factory=list)
    # Stage 1 report for the page given to the engine (only with ``prepare=True``).
    prepare: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.item.spec.id,
            "category": self.item.spec.category,
            "language": self.item.spec.language,
            "variant": self.variant,
            "wall_s": self.wall_s,
            "engine_errors": self.engine_errors,
            "qa": self.qa,
            "located_events": self.located,
            "mappable_events": self.mappable,
            "repairs": self.repairs,
            "prepare": self.prepare,
            **self.comparison.to_dict(),
        }


# Input variants prepared before the engine with ``prepare=True``: rasters of paper go
# through Stage 1, born-digital PDFs are rendered (Stage 0).
PREPARED_VARIANTS = ("pdf", "scan", "photo")


def engine_dir(work: Path, item: CorpusItem, variant: Variant, prepare: bool = False) -> Path:
    """Where the engine output for this item and variant is cached."""
    prepared = prepare and variant in PREPARED_VARIANTS
    return work / item.spec.id / (f"{variant}-prepared" if prepared else variant)


def _prepared_source(out_dir: Path, variant: Variant) -> Path:
    return out_dir / ("prepared.tif" if variant == "pdf" else "prepared.png")


def _prepared_input(item: CorpusItem, variant: Variant, out_dir: Path) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)
    report: dict[str, Any]
    if variant == "pdf":
        from lilyscan.ingest.pdf import render_pdf  # needs the vector and vision extras

        report = render_pdf(item.input(variant), _prepared_source(out_dir, variant)).to_dict()
    else:
        from lilyscan.prepare import prepare_image  # needs the vision extra

        report = prepare_image(item.input(variant), _prepared_source(out_dir, variant)).to_dict()
    (out_dir / "prepare.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8", newline="\n"
    )
    return report


def _engine_output(
    item: CorpusItem,
    variant: Variant,
    work: Path,
    settings: Settings,
    reuse: bool,
    prepare: bool = False,
) -> tuple[Score | None, float | None, list[str], bool, dict[str, Any] | None]:
    """The engine's score for one input (cached), with the Stage 1 report if prepared."""
    out_dir = engine_dir(work, item, variant, prepare)
    summary_path = out_dir / "run.json"
    cached = reuse and summary_path.is_file()
    prepared = prepare and variant in PREPARED_VARIANTS
    report: dict[str, Any] | None = None
    if prepared and cached and (out_dir / "prepare.json").is_file():
        report = json.loads((out_dir / "prepare.json").read_text(encoding="utf-8"))
    elif prepared:
        report = _prepared_input(item, variant, out_dir)
        cached = False
    if cached:
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
    else:
        source = _prepared_source(out_dir, variant) if prepared else item.input(variant)
        run = run_audiveris([source], out_dir, settings=settings)
        summary = {
            "ok": run.ok,
            "wall_s": round(run.wall_s, 2),
            "mxl_files": [p.name for p in run.mxl_files],
            "errors": run.step_errors + run.ocr_problems,
            "timed_out": run.timed_out,
        }
        summary_path.write_text(json.dumps(summary, indent=2), encoding="utf-8", newline="\n")

    errors: list[str] = list(summary["errors"])
    score: Score | None = None
    if summary["ok"]:
        try:
            parts = [load_musicxml(out_dir / name, "audiveris") for name in summary["mxl_files"]]
        except (MusicXMLError, OSError) as exc:
            errors.append(f"cannot read engine MusicXML: {exc}")
        else:
            # Stage 3: page boxes and grades from the .omr, attached movement by movement.
            omr = next(iter(sorted(out_dir.glob("*.omr"))), None)
            book = None
            if omr is not None:
                try:
                    book = read_omr(omr)
                except OmrError as exc:
                    errors.append(f"cannot read .omr: {exc}")
            score = attach_movements(parts, book)[0] if parts else None
    return score, summary["wall_s"], errors, cached, report


@dataclass
class _Processed:
    """An engine output after Stage 3 geometry and (optionally) Stage 5."""

    pred: Score | None
    out_dir: Path
    omr: Path | None
    located: int = 0
    mappable: int = 0
    repairs: list[dict[str, Any]] = field(default_factory=list)


def _process(
    pred: Score | None,
    out_dir: Path,
    repair: bool,
    errors: list[str],
    pdf: Path | None = None,
) -> _Processed:
    omr = next(iter(sorted(out_dir.glob("*.omr"))), None)
    done = _Processed(pred, out_dir, omr)
    book = None
    if pred is not None and omr is not None:
        with contextlib.suppress(OmrError):  # reported when the engine output was loaded
            book = read_omr(omr)
        # Boxes were attached on loading; events in a measure found on the page count.
        for _, staff in pred.staves():
            for m in staff.measures:
                if m.bbox is not None:
                    events = [e for v in m.voices for e in v.events]
                    done.mappable += len(events)
                    done.located += sum(e.bbox is not None for e in events)
    if repair and pred is not None:
        if pdf is not None and book is not None:
            from lilyscan.vector import apply_vector_oracle  # needs the vector extra

            done.repairs += [r.to_dict() for r in apply_vector_oracle(pred, pdf, book)]
        done.repairs += [r.to_dict() for r in apply_repairs(pred, book=book)]
        calibrate_confidence(pred)
    return done


def evaluate_item(
    item: CorpusItem,
    variant: Variant,
    work: Path,
    settings: Settings,
    reuse: bool,
    lilypond: bool = False,
    repair: bool = False,
    prepare: bool = False,
) -> ItemResult:
    gt = load_musicxml(item.ground_truth, "ground-truth")
    pred, wall_s, errors, cached, prepared = _engine_output(
        item, variant, work, settings, reuse, prepare
    )
    # Stage 4 reads the born-digital PDF the engine's pages came from.
    pdf = item.input(variant) if prepare and variant == "pdf" else None
    done = _process(pred, engine_dir(work, item, variant, prepare), repair, errors, pdf)
    # As in a job: when Stage 1 found no page edges (a scan), the page as uploaded is
    # transcribed too, and the run Lilyscan expects to have more right is kept.
    if repair and prepared is not None and prepared.get("page_found") is False:
        alt, alt_wall, alt_errors, alt_cached, _ = _engine_output(
            item, variant, work, settings, reuse, False
        )
        other = _process(alt, engine_dir(work, item, variant, False), repair, alt_errors)
        mine = expected_right(pred) if pred is not None else -1.0
        theirs = expected_right(alt) if alt is not None else -1.0
        prepared = {
            **prepared,
            "expected_right": {"prepared": round(mine, 2), "uploaded": round(theirs, 2)},
            "chosen": "uploaded" if theirs > mine else "prepared",
        }
        wall_s = (wall_s or 0.0) + (alt_wall or 0.0)
        if theirs > mine:
            pred, errors, cached, done = alt, alt_errors, alt_cached and cached, other
    result = ItemResult(item, variant, compare(gt, pred), wall_s, errors, cached)
    result.located, result.mappable, result.repairs = done.located, done.mappable, done.repairs
    result.prepare = prepared
    if pred is not None and done.omr is not None:
        result.correctness = event_correctness(gt, pred)
    if lilypond and pred is not None:
        out = "lilyscan-repaired" if repair else "lilyscan"
        report = produce(pred, done.out_dir / out, settings)
        result.qa = {c["id"]: c["passed"] for c in report["qa"]["checks"]}
    log.info(
        "%s/%s: measures %.0f%%, note F1 %.2f%s",
        item.spec.id,
        variant,
        100 * result.comparison.measure_accuracy,
        result.comparison.note_prf[2],
        " (cached)" if cached else "",
    )
    return result


def run_evaluation(
    items: Iterable[CorpusItem],
    work: Path,
    variants: Iterable[Variant] = VARIANTS,
    jobs: int = 1,
    reuse: bool = True,
    settings: Settings | None = None,
    lilypond: bool = False,
    repair: bool = False,
    prepare: bool = False,
) -> list[ItemResult]:
    """Evaluate every item/variant; ``lilypond`` also runs the pipeline and checks Q1-Q5,
    ``repair`` applies the Stage 5 rules and Lilyscan's confidence before scoring, and
    ``prepare`` gives scans and photos to the engine through Stage 1."""
    s = settings or Settings.from_env()
    tasks = [(item, v) for item in items for v in variants]
    with ThreadPoolExecutor(max_workers=max(1, jobs)) as pool:
        futures = [
            pool.submit(evaluate_item, item, v, work, s, reuse, lilypond, repair, prepare)
            for item, v in tasks
        ]
        return [f.result() for f in futures]


def _mean(values: list[float]) -> float | None:
    return round(mean(values), 4) if values else None


def summarize(results: list[ItemResult]) -> dict[str, dict[str, Any]]:
    """Micro-averaged measure accuracy and edit rate; macro-averaged F1 scores."""
    groups: dict[str, list[ItemResult]] = defaultdict(list)
    for r in results:
        groups[f"variant:{r.variant}"].append(r)
        if r.prepare is not None and r.prepare.get("passed"):
            groups[f"variant:{r.variant}, gate passed"].append(r)
        groups[f"category:{r.item.spec.category}"].append(r)
        groups["all"].append(r)
    out: dict[str, dict[str, Any]] = {}
    for key, rs in sorted(groups.items()):
        c = [r.comparison for r in rs]
        gt_measures = sum(x.gt_measures for x in c)
        gt_events = sum(x.gt_events for x in c)
        out[key] = {
            "items": len(rs),
            "engine_ok": sum(x.engine_ok for x in c),
            "measure_accuracy": round(sum(x.exact_measures for x in c) / gt_measures, 4)
            if gt_measures
            else None,
            "edit_rate": round(sum(x.event_edits for x in c) / gt_events, 4) if gt_events else None,
            "note_f1": _mean([x.note_prf[2] for x in c]),
            "onset_f1": _mean([x.onset_prf[2] for x in c]),
            "lyric_accuracy": _mean([x.lyric_accuracy for x in c if x.lyric_accuracy is not None]),
            "chord_accuracy": _mean([x.chord_accuracy for x in c if x.chord_accuracy is not None]),
            "mean_wall_s": _mean([r.wall_s for r in rs if r.wall_s is not None]),
            # Share of pipeline runs (engine output -> LilyPond) passing Q1 / Q2.
            "compiles": _rate([r.qa["Q1"] for r in rs if r.qa is not None]),
            "bar_checks_clean": _rate([r.qa["Q2"] for r in rs if r.qa is not None]),
            # Share of mappable engine events that got a box from the .omr (Stage 3).
            "box_coverage": round(sum(r.located for r in rs) / mappable, 4)
            if (mappable := sum(r.mappable for r in rs))
            else None,
            "calibration": calibration([pair for r in rs for pair in r.correctness]),
            "repairs": dict(sorted(Counter(x["rule"] for r in rs for x in r.repairs).items())),
        }
    return out


def _rate(flags: list[bool]) -> float | None:
    return round(sum(flags) / len(flags), 4) if flags else None


def _fmt(v: Any, pct: bool = True) -> str:
    if v is None:
        return "-"
    return f"{100 * v:.1f}%" if pct else f"{v:.1f}"


def write_results(results: list[ItemResult], out_dir: Path, label: str, notes: str = "") -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    summary = summarize(results)
    env = {
        "label": label,
        "created": datetime.now(UTC).isoformat(timespec="seconds"),
        "lilyscan": __version__,
        "audiveris": audiveris_version(),
        "lilypond": lilypond_version(),
        "platform": platform.platform(),
        "processor": platform.processor(),
        "notes": notes,
    }
    (out_dir / "results.json").write_text(
        json.dumps(
            {"environment": env, "summary": summary, "items": [r.to_dict() for r in results]},
            indent=2,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )

    with_qa = any(r.qa is not None for r in results)
    qa_head = " Compiles (Q1) | Bar checks (Q2) |" if with_qa else ""
    qa_head += " Boxes | ECE |"
    lines = [
        f"# Evaluation: {label}",
        "",
        f"Audiveris {env['audiveris']}, LilyPond {env['lilypond']}, lilyscan {__version__}, "
        f"{env['platform']}.",
        "",
        notes,
        "",
        "| Group | Items | Engine OK | Exact measures | Edit rate | Note F1 | Onset F1 "
        f"| Lyrics | Chords | s/page |{qa_head}",
        "|---|---|---|---|---|---|---|---|---|---|" + ("---|---|" if with_qa else "") + "---|---|",
    ]
    for key, row in summary.items():
        qa_cells = (
            f" {_fmt(row['compiles'])} | {_fmt(row['bar_checks_clean'])} |" if with_qa else ""
        )
        ece = row["calibration"]["ece"]
        qa_cells += f" {_fmt(row['box_coverage'])} | {'-' if ece is None else f'{ece:.3f}'} |"
        lines.append(
            f"| {key} | {row['items']} | {row['engine_ok']} | {_fmt(row['measure_accuracy'])} "
            f"| {_fmt(row['edit_rate'])} | {_fmt(row['note_f1'])} | {_fmt(row['onset_f1'])} "
            f"| {_fmt(row['lyric_accuracy'])} | {_fmt(row['chord_accuracy'])} "
            f"| {_fmt(row['mean_wall_s'], pct=False)} |{qa_cells}"
        )
    lines += [
        "",
        "Exact measures: share of ground-truth measures reproduced exactly (pitch, duration, "
        "voices). Edit rate: event edits per ground-truth event (lower is better). "
        "Onset F1 ignores durations. Boxes: engine events located on the page from the .omr "
        "(Stage 3). ECE: expected calibration error of the event confidence, the engine's grades "
        "or, with repairs, Lilyscan's calibrated confidence (lower is better).",
        "",
    ]
    repaired = [r for r in results if r.repairs]
    if repaired:
        lines += ["## Repairs", "", "| Item | Rule | Detail |", "|---|---|---|"]
        for r in repaired:
            for rep in r.repairs:
                lines.append(f"| {r.item.spec.id}/{r.variant} | {rep['rule']} | {rep['detail']} |")
        lines.append("")
    bands = summary.get("all", {}).get("calibration", {}).get("bands", [])
    if any(b["events"] for b in bands):
        lines += [
            "## Confidence calibration (all items)",
            "",
            "| Confidence | Events | Accuracy | Mean confidence |",
            "|---|---|---|---|",
        ]
        for b in bands:
            if b["events"]:
                lines.append(
                    f"| {b['band']} | {b['events']} | {_fmt(b['accuracy'])} "
                    f"| {_fmt(b['mean_confidence'])} |"
                )
        lines.append("")
    path = out_dir / "summary.md"
    path.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    return path
