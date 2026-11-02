"""Score-combiner references for the Java port, from engine runs of the corpus. Local only (D11):

    uv run python scripts/java_combine_sweep.py /tmp/combine-sweep --runs /tmp/engine-sweep/runs
    ./gradlew test -PcombineSweep=/tmp/combine-sweep

Each multi-part piece read by the engine from both its PDF and its degraded scan becomes two
finished jobs (``jobs/<piece>-pdf``, ``jobs/<piece>-scan``: the repaired score through
``produce``); the two readings often differ in measures and keys, as separate uploads of one
piece do. One part's LilyPond source in the PDF job is edited, as a user would in the review.
Then, per piece, ``combine`` runs on three requests (``cases/<piece>-<case>/case.json``):
``mix`` (parts alternating between the jobs), ``transpose`` (intervals and a new name) and
``piano`` (the first part with the others reduced to a piano). The combined job, or the error,
is the reference.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from lilyscan.combine import Selection, combine  # noqa: E402
from lilyscan.ir.models import Score  # noqa: E402
from lilyscan.pipeline import produce  # noqa: E402
from lilyscan.runtime.config import Settings  # noqa: E402

EDIT = "% edited in the review\n"


def _mutated(run: Path, out: Path, settings: Settings, change: str, parts: list[str]) -> Path | None:
    """A job from the engine's reading, changed as other readings go wrong: ``drop`` (the second
    part lost a measure, a missed barline), ``extra`` (the second part gained one, a stray
    barline), ``rekey`` (the third part's key misread for a stretch), ``short`` (the second
    part lost 40% of its measures)."""
    if (out / "ir" / "score.json").is_file():
        return out
    score = Score.model_validate_json((run / "final.score.json").read_text(encoding="utf-8"))
    by_id = {p.id: p for p in score.parts}
    if change in ("drop", "extra", "short"):
        part = by_id[parts[1]]
        n = len(part.staves[0].measures)
        if n < 6:
            return None
        for staff in part.staves:
            ms = staff.measures
            if change == "drop":
                del ms[n // 2]
            elif change == "extra":
                ms.insert(n // 3, ms[n // 3].model_copy(deep=True))
            else:
                del ms[n // 3 : n // 3 + max(2, (n * 4) // 10)]
            for k, m in enumerate(ms):
                m.index = k
    elif change == "rekey":
        if len(parts) < 4:
            return None
        part = by_id[parts[2]]
        ms = part.staves[0].measures
        n = len(ms)
        start, end = n // 3, (2 * n) // 3
        fifths = 0
        for m in ms[: end + 1]:
            if m.key is not None:
                fifths = m.key.fifths
        for staff in part.staves:
            sm = staff.measures
            if start < len(sm) and end < len(sm):
                keep = sm[end].key
                sm[start].key = sm[0].key.model_copy(update={"fifths": fifths + 1}) if sm[0].key else None
                if sm[start].key is None:
                    return None
                sm[end].key = keep or sm[0].key.model_copy(update={"fifths": fifths})
    out.mkdir(parents=True, exist_ok=True)
    produce(score, out, settings)
    return out


def _job(run: Path, out: Path, settings: Settings) -> Path:
    if (out / "ir" / "score.json").is_file():
        return out
    score = Score.model_validate_json((run / "final.score.json").read_text(encoding="utf-8"))
    out.mkdir(parents=True, exist_ok=True)
    produce(score, out, settings)
    return out


def _cases(pdf: Path, scan: Path, parts: list[str]) -> dict[str, dict[str, object]]:
    def sel(job: Path, part: str, transpose: str | None = None, name: str | None = None) -> dict[str, object]:
        return {"job": str(job), "part": part, "transpose": transpose, "name": name}

    jobs = [pdf, scan]
    return {
        "mix": {"title": None, "parts": [sel(jobs[k % 2], p) for k, p in enumerate(parts)], "piano": []},
        "transpose": {
            "title": "Transposed",
            "parts": [
                sel(pdf, parts[0], "M2", "Violin 1"),
                sel(scan, parts[1], "-m3"),
                *[sel(pdf, p) for p in parts[2:]],
            ],
            "piano": [],
        },
        "piano": {
            "title": "With piano",
            "parts": [sel(pdf, parts[0])],
            "piano": [sel(scan, p) for p in parts[1:]],
        },
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    ap.add_argument("--runs", type=Path, required=True)
    args = ap.parse_args()
    out = args.out.resolve()
    settings = Settings.from_env()
    manifest = []
    pieces = sorted({p.name.rsplit("-", 1)[0] for p in args.runs.iterdir() if (p / "final.score.json").is_file()})
    for piece in pieces:
        runs = {v: args.runs / f"{piece}-{v}" for v in ("pdf", "scan")}
        if not all((r / "final.score.json").is_file() for r in runs.values()):
            continue
        parts = [p.id for p in Score.model_validate_json((runs["pdf"] / "final.score.json").read_text()).parts]
        other = {p.id for p in Score.model_validate_json((runs["scan"] / "final.score.json").read_text()).parts}
        parts = [p for p in parts if p in other]
        if len(parts) < 2:
            continue
        pdf = _job(runs["pdf"], out / "jobs" / f"{piece}-pdf", settings)
        scan = _job(runs["scan"], out / "jobs" / f"{piece}-scan", settings)
        first = sorted((pdf / "ly" / "parts").glob("*.ly"))[0]
        if not first.read_text(encoding="utf-8").endswith(EDIT):
            first.write_text(first.read_text(encoding="utf-8") + EDIT, encoding="utf-8", newline="\n")
        requests = _cases(pdf, scan, parts)

        def sel(job: Path, part: str, transpose: str | None = None) -> dict[str, object]:
            return {"job": str(job), "part": part, "transpose": transpose, "name": None}

        drop = _mutated(runs["scan"], out / "jobs" / f"{piece}-drop", settings, "drop", parts)
        extra = _mutated(runs["scan"], out / "jobs" / f"{piece}-extra", settings, "extra", parts)
        if drop is not None and extra is not None:
            requests["drop"] = {"title": None, "parts": [sel(pdf, parts[0]), sel(drop, parts[1]), *[sel(pdf, p) for p in parts[2:]]], "piano": []}
            requests["extra"] = {"title": None, "parts": [sel(pdf, parts[0]), sel(extra, parts[1]), *[sel(pdf, p) for p in parts[2:]]], "piano": []}
            requests["drop-piano"] = {"title": None, "parts": [sel(pdf, parts[0])], "piano": [sel(drop, parts[1]), *[sel(scan, p) for p in parts[2:]]]}
        short = _mutated(runs["scan"], out / "jobs" / f"{piece}-short", settings, "short", parts)
        if short is not None:
            requests["short"] = {"title": None, "parts": [sel(pdf, parts[0]), sel(short, parts[1])], "piano": []}
        rekey = _mutated(runs["pdf"], out / "jobs" / f"{piece}-rekey", settings, "rekey", parts)
        if rekey is not None:
            requests["rekey"] = {"title": None, "parts": [sel(rekey if k == 2 else pdf, p) for k, p in enumerate(parts)], "piano": []}
        requests["bad-key"] = {"title": None, "parts": [sel(pdf, parts[0], "d2"), sel(pdf, parts[1], "-A7")], "piano": []}
        for case, request in requests.items():
            name = f"{piece}-{case}"
            d = out / "cases" / name
            if d.exists():
                shutil.rmtree(d)
            d.mkdir(parents=True)
            (d / "case.json").write_text(json.dumps(request, indent=1) + "\n")

            def selections(items: list[dict[str, object]]) -> list[Selection]:
                return [
                    Selection(Path(str(s["job"])), str(s["part"]), s["transpose"], s["name"])  # type: ignore[arg-type]
                    for s in items
                ]

            try:
                combine(
                    selections(request["parts"]),  # type: ignore[arg-type]
                    d / "job",
                    request["title"],  # type: ignore[arg-type]
                    settings,
                    piano=selections(request["piano"]) or None,  # type: ignore[arg-type]
                )
                result = "ok"
            except ValueError as exc:
                (d / "error.txt").write_text(f"{type(exc).__name__}: {exc}")
                result = f"error: {exc}"
            manifest.append(name)
            print(name, result, flush=True)
    (out / "manifest.json").write_text(json.dumps(manifest, indent=1) + "\n")
    print(f"{len(manifest)} cases in {out}")


if __name__ == "__main__":
    main()
