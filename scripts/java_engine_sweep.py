"""Engine runs on the corpus and the Python Stage 4-5 results on them, for the Java port's
repair, vector-oracle and confidence tests (design-java.md §10). Local only: the engine
outputs are large and are re-created from the corpus, so nothing here is committed (D11).

    uv run python scripts/java_engine_sweep.py /tmp/engine-sweep --jar lilyscan-cli/build/libs/lilyscan.jar
    ./gradlew test -PengineSweep=/tmp/engine-sweep

For each piece and input (the born-digital PDF and the degraded scan) it runs Stage 0/1
(``ingest.book.assemble``) and Audiveris (the Java engine worker, so both implementations
read the same project), then writes into ``<out>/runs/<piece>-<variant>/``:

- ``input.score.json``: the imported score with ``.omr`` geometry (before any correction);
- ``book.json``: the ``.omr`` book model as the Python reader sees it;
- ``rules.score.json`` / ``rules.repairs.json``: after ``apply_repairs`` alone;
- ``final.score.json`` / ``final.repairs.json``: after the job's whole Stage 4-5 chain (the
  vector oracle on born-digital PDFs, the repair rules, the calibrated confidence).
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
import stat
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))

from java_parity import book_dump  # noqa: E402

from lilyscan.engine.audiveris.omr import OmrError, attach_movements, read_omr  # noqa: E402
from lilyscan.engine.audiveris.runner import run_audiveris  # noqa: E402
from lilyscan.ingest.book import assemble  # noqa: E402
from lilyscan.ir.musicxml import load_musicxml  # noqa: E402
from lilyscan.repair import apply_repairs  # noqa: E402
from lilyscan.repair.confidence import calibrate_confidence  # noqa: E402
from lilyscan.runtime.config import Settings  # noqa: E402
from lilyscan.synth.corpus import build_corpus  # noqa: E402
from lilyscan.vector import apply_vector_oracle  # noqa: E402

VARIANTS = ("pdf", "scan")


def _launcher(out: Path, jar: Path) -> Path:
    path = out / "audiveris.sh"
    path.write_text(
        "#!/bin/sh\n"
        f'exec "{os.environ.get("JAVA", "java")}" -Xmx3G -Djava.awt.headless=true '
        f'-Dsun.java2d.uiScale=1 -cp "{jar}" org.lilyscan.engine.audiveris.EngineWorker audiveris "$@"\n',
        encoding="utf-8",
    )
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return path


def vector_dump(pdf: Path) -> list[dict[str, object]]:
    """What the vector oracle reads from a PDF: the staves and the italic digits per page."""
    from lilyscan.vector.extract import read_pdf
    from lilyscan.vector.staves import find_staves

    return [
        {
            "width": page.width,
            "height": page.height,
            "staves": [[list(s.lines), s.x0, s.x1] for s in find_staves(page)],
            "italic": [
                [g.name, g.rect.cx, g.origin[1]] for g in page.glyphs if g.name.startswith("italic.")
            ],
        }
        for page in read_pdf(pdf)
    ]


def _dump(path: Path, data: object) -> None:
    path.write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8", newline="\n")


def _run(item_root: Path, variant: str, run_dir: Path, settings: Settings) -> str:
    source = item_root / ("score.pdf" if variant == "pdf" else "scan.png")  # corpus._FILES
    if (run_dir / "final.score.json").is_file():
        return f"{run_dir.name}: kept"
    book_files = assemble([source], run_dir / "prepared")
    if book_files.prepared is None:
        return f"{run_dir.name}: nothing prepared"
    run = run_audiveris([book_files.prepared], run_dir / "engine", settings=settings)
    if not run.ok or not run.omr_files:
        return f"{run_dir.name}: engine failed ({run.returncode})"
    omr = run.omr_files[0]
    try:
        book = read_omr(omr)
    except OmrError as exc:
        return f"{run_dir.name}: {exc}"
    movements = [load_musicxml(p, "audiveris") for p in run.mxl_files]

    def imported():  # type: ignore[no-untyped-def]
        score, _ = attach_movements(movements, book)
        return score

    _dump(run_dir / "book.json", book_dump(book))
    meta = {
        "omr": omr.relative_to(run_dir).as_posix(),
        "mxl": [p.relative_to(run_dir).as_posix() for p in run.mxl_files],
        "pdf": source.as_posix() if variant == "pdf" else None,
    }
    _dump(run_dir / "run.json", meta)
    score = imported()
    (run_dir / "input.score.json").write_text(score.model_dump_json(indent=1), encoding="utf-8", newline="\n")

    score = imported()
    repairs = apply_repairs(score, book=book)
    (run_dir / "rules.score.json").write_text(score.model_dump_json(indent=1), encoding="utf-8", newline="\n")
    _dump(run_dir / "rules.repairs.json", [r.to_dict() for r in repairs])

    score = imported()
    log = []
    if variant == "pdf":
        log += [r.to_dict() for r in apply_vector_oracle(score, source, book)]
    log += [r.to_dict() for r in apply_repairs(score, book=book)]
    calibrated = calibrate_confidence(score)
    (run_dir / "final.score.json").write_text(score.model_dump_json(indent=1), encoding="utf-8", newline="\n")
    _dump(run_dir / "final.repairs.json", {"calibrated": calibrated, "repairs": log})
    return f"{run_dir.name}: {len(log)} repair(s)"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    ap.add_argument("--jar", type=Path, required=True)
    ap.add_argument("--jobs", type=int, default=3)
    ap.add_argument("--only", nargs="*")
    args = ap.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    settings = dataclasses.replace(
        Settings.from_env(), audiveris_bin=str(_launcher(out, args.jar.resolve()))
    )
    only = set(args.only) if args.only else None
    items = []
    for spec in ("seed.json", "repertoire.json"):
        items += build_corpus(ROOT / "eval" / "corpus" / spec, out / "corpus", only=only, settings=settings)
    for it in items:
        _dump(it.root / "vector.json", vector_dump(it.root / "score.pdf"))
    tasks = [(it.root, v, out / "runs" / f"{it.spec.id}-{v}") for it in items for v in VARIANTS]
    with ThreadPoolExecutor(args.jobs) as pool:
        for line in pool.map(lambda t: _run(*t, settings), tasks):
            print(line, flush=True)
    runs = sorted(p.name for p in (out / "runs").iterdir() if (p / "final.score.json").is_file())
    _dump(out / "manifest.json", runs)
    print(f"{len(runs)} runs in {out}")


if __name__ == "__main__":
    main()
