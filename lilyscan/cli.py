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
