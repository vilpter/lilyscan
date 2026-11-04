"""Reference draws and pieces for the Java corpus generator's parity tests (design-java.md, JM11).

    uv run python scripts/java_synth_reference.py

Writes tests/parity/synth/random.json: Python's random.Random draws for a few seeds, which
org.lilyscan.eval.synth.PyRandom must reproduce exactly; and tests/parity/synth/seed.json: the
music of every piece of eval/corpus/seed.json as the Python generator writes it (read back from
its ground-truth MusicXML), which org.lilyscan.eval.synth.Generator must reproduce.
"""

from __future__ import annotations

import json
import random
import sys
import tempfile
from fractions import Fraction
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "tests" / "parity" / "synth"
sys.path.insert(0, str(ROOT))

from lilyscan.ir.models import Score  # noqa: E402
from lilyscan.ir.musicxml import load_musicxml  # noqa: E402
from lilyscan.synth.corpus import load_spec  # noqa: E402
from lilyscan.synth.generate import write_ground_truth  # noqa: E402


def _frac(f: Fraction) -> str:
    return f"{f.numerator}/{f.denominator}"


def summary(score: Score) -> dict[str, Any]:
    """The music of a score, as both generators must agree on it (SynthParityTest mirrors this).
    Voices are ranked by number within a measure, since the writers number them differently."""
    staves: list[Any] = []
    for part in score.parts:
        for staff in part.staves:
            measures: list[Any] = []
            for m in staff.measures:
                voices = []
                for v in sorted(m.voices, key=lambda v: v.number):
                    if not v.events:
                        continue
                    voices.append(
                        [
                            [
                                _frac(e.offset),
                                _frac(e.duration),
                                e.kind,
                                [
                                    f"{n.pitch.step}{n.pitch.alter}/{n.pitch.octave}"
                                    + ("~" if n.tie_start else "")
                                    + ("^" if n.tie_stop else "")
                                    for n in e.notes
                                ],
                                list(e.tuplet) if e.tuplet else None,
                                [[ly.text, ly.syllabic] for ly in e.lyrics],
                            ]
                            for e in v.events
                        ]
                    )
                measures.append(
                    {
                        "number": m.number,
                        "clefs": [
                            [c.sign, c.line, c.octave_change, _frac(c.offset)] for c in m.clefs
                        ],
                        "key": [m.key.fifths, m.key.mode] if m.key else None,
                        "time": [m.time.beats, m.time.beat_type] if m.time else None,
                        "barline": m.right_barline.style if m.right_barline else None,
                        "chords": [[_frac(c.offset), c.root, c.kind] for c in m.chord_symbols],
                        "voices": voices,
                    }
                )
            staves.append(
                {"part": part.name, "abbreviation": part.abbreviation, "measures": measures}
            )
    return {"title": score.title, "composer": score.composer, "staves": staves}


def seed_pieces() -> dict[str, Any]:
    out: dict[str, Any] = {}
    with tempfile.TemporaryDirectory() as tmp:
        for spec in load_spec(ROOT / "eval" / "corpus" / "seed.json"):
            path = write_ground_truth(spec, Path(tmp) / spec.id)
            out[spec.id] = summary(load_musicxml(path, "ground-truth"))
    return out


def draws(seed: str | int) -> dict[str, object]:
    rng = random.Random(seed)
    out: list[object] = []
    for i in range(400):
        match i % 5:
            case 0:
                out.append(["random", repr(rng.random())])
            case 1:
                out.append(["randint", -4, 4, rng.randint(-4, 4)])
            case 2:
                out.append(["choice", 11, rng.choice(range(11))])
            case 3:
                pop = ["a", "b", "c", "d"]
                out.append(["choices", rng.choices(pop, weights=[40, 25, 15, 20])[0]])
            case _:
                out.append(["randint", 0, 1000, rng.randint(0, 1000)])
    return {"seed": seed, "draws": out}


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    seeds: list[str | int] = ["solo:100", "piano:105", "satb:103", "", "ü-ñ:7", 0, 12345]
    data = [draws(s) for s in seeds]
    (OUT / "random.json").write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8")
    pieces = seed_pieces()
    (OUT / "seed.json").write_text(
        json.dumps(pieces, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8"
    )
    print(f"wrote {len(data)} seeds and {len(pieces)} pieces")


if __name__ == "__main__":
    main()
