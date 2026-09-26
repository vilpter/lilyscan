"""Command-line entry point: ``lilyscan <command>``."""

from __future__ import annotations

import argparse
import json
import logging
import sys
import tempfile
from pathlib import Path

from lilyscan.engine.audiveris.runner import audiveris_version
from lilyscan.lilypond.compile import HELLO_WORLD, compile_ly, lilypond_version
from lilyscan.runtime.config import AUDIVERIS_VERSION, LILYPOND_VERSION
from lilyscan.runtime.device import get_device


def _cmd_device(_: argparse.Namespace) -> int:
    info = get_device()
    print(
        json.dumps(
            {
                "device": info.device.value,
                "backend": info.backend,
                "gpu_name": info.gpu_name,
                "reason": info.reason,
            }
        )
    )
    return 0


def _cmd_versions(_: argparse.Namespace) -> int:
    print(
        json.dumps(
            {
                "lilypond": {"pinned": LILYPOND_VERSION, "found": lilypond_version()},
                "audiveris": {"pinned": AUDIVERIS_VERSION, "found": audiveris_version()},
            }
        )
    )
    return 0


def _cmd_selftest(args: argparse.Namespace) -> int:
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "hello.ly"
        src.write_text(HELLO_WORLD, encoding="utf-8")
        out = Path(args.out) if args.out else Path(tmp) / "out"
        result = compile_ly(src, out, ("pdf",))
        for d in result.diagnostics:
            print(f"{d.severity}: {d.message}", file=sys.stderr)
        print("lilypond self-test:", "ok" if result.ok else "FAILED")
        return 0 if result.ok else 1


def _cmd_corpus_build(args: argparse.Namespace) -> int:
    # Imported here: corpus generation needs the musicxml and vision extras.
    from lilyscan.synth.corpus import build_corpus

    items = build_corpus(
        Path(args.spec), Path(args.out), only=set(args.only or ()) or None, force=args.force
    )
    print(f"corpus: {len(items)} pieces in {args.out}")
    return 0


def _cmd_eval_run(args: argparse.Namespace) -> int:
    from lilyscan.evaluation.harness import run_evaluation, write_results
    from lilyscan.synth.corpus import load_corpus

    items = load_corpus(Path(args.corpus))
    if args.only:
        items = [i for i in items if i.spec.id in set(args.only)]
    if args.category:
        items = [i for i in items if i.spec.category in set(args.category)]
    results = run_evaluation(
        items, Path(args.work), variants=args.variants, jobs=args.jobs, reuse=not args.no_reuse
    )
    out = Path(args.out) if args.out else Path("eval/results") / args.label
    path = write_results(results, out, args.label, notes=args.notes)
    print(path.read_text(encoding="utf-8"))
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="lilyscan")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("device", help="print the selected compute device").set_defaults(fn=_cmd_device)
    sub.add_parser("versions", help="pinned vs installed tool versions").set_defaults(
        fn=_cmd_versions
    )
    st = sub.add_parser("selftest", help="compile a hello-world score with LilyPond")
    st.add_argument("--out", help="keep the output PDF in this directory")
    st.set_defaults(fn=_cmd_selftest)

    corpus = sub.add_parser("corpus", help="synthetic evaluation corpus").add_subparsers(
        dest="corpus_command", required=True
    )
    cb = corpus.add_parser("build", help="generate ground truth and input variants")
    cb.add_argument("--spec", default="eval/corpus/seed.json")
    cb.add_argument("--out", default="eval/corpus/seed")
    cb.add_argument("--only", nargs="*", help="piece ids to (re)build")
    cb.add_argument("--force", action="store_true", help="rebuild existing pieces")
    cb.set_defaults(fn=_cmd_corpus_build)

    ev = sub.add_parser("eval", help="evaluation harness").add_subparsers(
        dest="eval_command", required=True
    )
    er = ev.add_parser("run", help="run Audiveris on the corpus and score the output")
    er.add_argument("--corpus", default="eval/corpus/seed")
    er.add_argument("--work", default="eval/work", help="cache of engine output")
    er.add_argument(
        "--variants",
        nargs="+",
        default=["pdf", "png", "scan", "photo"],
        choices=["pdf", "png", "scan", "photo"],
    )
    er.add_argument("--only", nargs="*", help="piece ids")
    er.add_argument("--category", nargs="*", help="piece categories")
    er.add_argument("--jobs", type=int, default=1, help="parallel Audiveris processes")
    er.add_argument("--no-reuse", action="store_true", help="ignore cached engine output")
    er.add_argument("--label", default="latest")
    er.add_argument("--out", help="results directory (default eval/results/<label>)")
    er.add_argument("--notes", default="", help="free text recorded with the results")
    er.set_defaults(fn=_cmd_eval_run)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(name)s: %(message)s",
    )
    rc: int = args.fn(args)
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
