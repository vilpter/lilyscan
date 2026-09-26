#!/usr/bin/env bash
# M0 exit check: the worker image engraves a page with LilyPond, and the Audiveris
# image transcribes it to .omr + .mxl with working OCR.
# Usage: scripts/engine_smoke.sh [worker-image] [audiveris-image]
set -euo pipefail

worker_image="${1:-lilyscan-worker:cpu}"
engine_image="${2:-lilyscan-audiveris}"

work="$(mktemp -d)"
chmod 777 "$work"
cleanup() {
  # The containers run as root, so remove what they wrote from inside a container.
  docker run --rm -v "$work:/work" "$worker_image" find /work -mindepth 1 -delete || true
  rm -rf "$work" || true
}
trap cleanup EXIT

docker run --rm -v "$work:/work" "$worker_image" lilyscan selftest --out /work
test -f "$work/hello.pdf"

docker run --rm -v "$work:/work" "$engine_image" \
  audiveris -batch -transcribe -export -save -output /work/out -- /work/hello.pdf \
  2>&1 | tee "$work/engine.log"

omr="$(find "$work/out" -name '*.omr' | head -n1)"
mxl="$(find "$work/out" -name '*.mxl' | head -n1)"
if [[ -z "$omr" || -z "$mxl" ]]; then
  echo "engine smoke FAILED: missing .omr or .mxl" >&2
  find "$work/out" -maxdepth 3 >&2 || true
  exit 1
fi

# Audiveris skips text recognition silently when Tesseract data is missing or
# lacks the legacy engine; treat that as a failure.
if cat "$work/engine.log" "$work"/out/*.log 2>/dev/null \
    | grep -E "supported languages is empty|Tesseract \(legacy\) engine requested"; then
  echo "engine smoke FAILED: OCR is not working in the Audiveris image" >&2
  exit 1
fi

echo "engine smoke ok: $(basename "$omr"), $(basename "$mxl"), OCR available"
