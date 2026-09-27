"""Copy an Audiveris run into eval/fixtures/omr/<version>/<name>/ for regression tests.

Usage: uv run python scripts/make_omr_fixture.py <engine-run-dir> <name>

book.xml records the absolute input path; it is reduced to the bare file name so
fixtures never carry local paths.
"""

from __future__ import annotations

import json
import re
import shutil
import sys
import zipfile
from pathlib import Path

from lilyscan.runtime.config import AUDIVERIS_VERSION


def sanitize_book(xml: str) -> str:
    def base(match: re.Match[str]) -> str:
        return match.group(1) + re.split(r"[\\/]", match.group(2))[-1] + match.group(3)

    xml = re.sub(r'(path=")([^"]*)(")', base, xml)
    return re.sub(r"(<path>)([^<]*)(</path>)", base, xml)


def main() -> None:
    run_dir, name = Path(sys.argv[1]), sys.argv[2]
    out = Path("eval/fixtures/omr") / AUDIVERIS_VERSION / name
    out.mkdir(parents=True, exist_ok=True)
    summary = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
    for mxl in summary["mxl_files"]:
        shutil.copy(run_dir / mxl, out / "output.mxl")
    source = next(run_dir.glob("*.omr"))
    with (
        zipfile.ZipFile(source) as zin,
        zipfile.ZipFile(out / "book.omr", "w", zipfile.ZIP_DEFLATED) as zout,
    ):
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "book.xml":
                data = sanitize_book(data.decode("utf-8")).encode("utf-8")
            zout.writestr(item.filename, data)
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
