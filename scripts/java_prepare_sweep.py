"""Stage 1 (page preparation) references for the Java port, on the corpus images. Local only,
like the other sweeps (D11):

    uv run python scripts/java_prepare_sweep.py /tmp/prepare-sweep --corpus /tmp/engine-sweep/corpus
    ./gradlew test -PprepareSweep=/tmp/prepare-sweep

For every piece and image (the clean render, the degraded scan, the photo) it writes
``<piece>-<variant>/report.json`` and ``prepared.png`` (Python's ``prepare_page``). For the
first ``--checkpoints`` pieces it also writes intermediate results, so the Java steps can be
compared on identical inputs: ``corners.json`` (find_page), ``flat.f32`` (flatten_light),
``angle.json`` (staff_angle on flat), ``level.f32`` (level_staves on flat) and ``level.json``
(the scale, curvature and orientation evidence measured on it). ``.f32`` files are raw float32
with a ``<name>.json`` shape.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import numpy as np  # noqa: E402

from lilyscan.prepare import prepare_page  # noqa: E402
from lilyscan.prepare.image import (  # noqa: E402
    _small_mask,
    _start_weight,
    _text_lean,
    find_page,
    flatten_light,
    ink_mask,
    level_staves,
    load_gray,
    save_png,
    staff_angle,
    upside_down,
)
from lilyscan.prepare.staff import curvature, displacement_samples, measure_scale  # noqa: E402

VARIANTS = {"png": "score.png", "scan": "scan.png", "photo": "photo.png"}


def _f32(path: Path, a: np.ndarray) -> None:
    np.ascontiguousarray(a, dtype=np.float32).tofile(path.with_suffix(".f32"))
    path.with_suffix(".shape.json").write_text(json.dumps(list(a.shape)))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    ap.add_argument("--corpus", type=Path, required=True)
    ap.add_argument("--checkpoints", type=int, default=6)
    args = ap.parse_args()
    out = args.out.resolve()
    pieces = sorted(p for p in args.corpus.iterdir() if (p / "score.png").is_file())
    entries = []
    for n, piece in enumerate(pieces):
        for variant, name in VARIANTS.items():
            src = piece / name
            if not src.is_file():
                continue
            d = out / f"{piece.name}-{variant}"
            d.mkdir(parents=True, exist_ok=True)
            gray = load_gray(src)
            prepared, report = prepare_page(gray, src.name)
            (d / "report.json").write_text(json.dumps(report.to_dict(), indent=1) + "\n")
            save_png(prepared, d / "prepared.png")
            entry = {"name": d.name, "input": str(src)}
            if n < args.checkpoints and variant != "png":
                corners = find_page(gray)
                (d / "corners.json").write_text(json.dumps(None if corners is None else corners.tolist()))
                flat = flatten_light(gray)
                _f32(d / "flat", flat)
                (d / "angle.json").write_text(json.dumps(staff_angle(flat)))
                level, angle = level_staves(flat)
                _f32(d / "level", level)
                mask = ink_mask(level)
                scale = measure_scale(mask)
                small = _small_mask(level)
                lean, letters = _text_lean(small)
                info = {
                    "angle": angle,
                    "scale": None if scale is None else [scale.thickness, scale.interline],
                    "curvature": None if scale is None else curvature(displacement_samples(mask, scale.interline)),
                    "samples": None if scale is None else len(displacement_samples(mask, scale.interline)),
                    "text_lean": [lean, letters],
                    "start_weight": _start_weight(small),
                    "upside_down": upside_down(level),
                }
                (d / "level.json").write_text(json.dumps(info))
                entry["checkpoints"] = True
            entries.append(entry)
            print(d.name, report.to_dict()["passed"], flush=True)
    (out / "manifest.json").write_text(json.dumps(entries, indent=1) + "\n")
    print(f"{len(entries)} pages in {out}")


if __name__ == "__main__":
    main()
