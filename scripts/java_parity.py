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
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from lilyscan.ir.musicxml import load_musicxml  # noqa: E402
from lilyscan.synth.corpus import load_spec  # noqa: E402
from lilyscan.synth.generate import write_ground_truth  # noqa: E402

OUT = ROOT / "tests" / "parity"

# MusicXML inputs for the reader (Stage 3 baseline import), relative to the repo root.
MUSICXML_INPUTS = {
    "features": ("tests/fixtures/features.musicxml", "musicxml"),
    "piano-two-voices": ("eval/fixtures/omr/5.11.0/piano-two-voices/output.mxl", "audiveris"),
    "split-flute": ("eval/fixtures/omr/5.11.0/split-flute/output.mxl", "audiveris"),
}

# Python repr() of floats, for the JSON writer's float formatting.
FLOATS = [
    0.0, -0.0, 1.0, -1.0, 0.1, 0.5, 0.95, 812.0, 340.5, 2480.0, 1e-4, 1e-5, 0.0005, 1.5e-7,
    12345678.0, 1e15, 1e16, 1.2345678901234567e17, 1e100, 1.5e-300, 123.456, 0.30000000000000004,
    2 / 3, 1 / 3, 99999999999999.9, 0.012345678, 5e-324, 1.7976931348623157e308, 7.0e-4,
    0.07142857142857142, 3.141592653589793,
]


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

    if args.sweep is not None:
        sweep = args.sweep.resolve()
        (sweep / "inputs").mkdir(parents=True, exist_ok=True)
        entries = []
        for spec_file in ("seed.json", "repertoire.json"):
            for spec in load_spec(ROOT / "eval" / "corpus" / spec_file):
                path = write_ground_truth(spec, sweep / "inputs")
                _write_reference(sweep, spec.id, path, "musicxml")
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
