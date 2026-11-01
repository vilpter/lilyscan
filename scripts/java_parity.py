"""Generate the reference outputs the Java port is tested against (tests/parity/).

The Java build must reproduce the Python implementation byte for byte (design-java.md D19).
This script runs the Python code on the committed fixtures and writes what it produces, so the
Java tests can compare against it without Python installed. Re-run it whenever the Python
behaviour changes:

    uv run python scripts/java_parity.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from lilyscan.ir.musicxml import load_musicxml  # noqa: E402

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


def main() -> None:
    (OUT / "musicxml").mkdir(parents=True, exist_ok=True)
    manifest = []
    for name, (rel, source) in MUSICXML_INPUTS.items():
        score = load_musicxml(ROOT / rel, source=source)
        (OUT / "musicxml" / f"{name}.score.json").write_text(
            score.model_dump_json(indent=1), encoding="utf-8", newline="\n"
        )
        manifest.append({"name": name, "input": rel, "source": source})
    (OUT / "musicxml" / "manifest.json").write_text(
        json.dumps(manifest, indent=1) + "\n", encoding="utf-8", newline="\n"
    )
    (OUT / "floats.json").write_text(
        json.dumps([[f.hex(), repr(f)] for f in FLOATS], indent=1) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"wrote references to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
