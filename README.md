# Lilyscan

Sheet music (PDF, scans, phone photos) to editable, well-structured LilyPond, built
around the [Audiveris](https://github.com/Audiveris/audiveris) OMR engine.
The design is in [docs/design.md](docs/design.md).

**Status:** early development (milestone M0: infrastructure). Not yet usable end to end.

## Pinned toolchain

| Tool | Version | Where |
|---|---|---|
| LilyPond | 2.26.0 | `lilyscan/runtime/config.py`, `services/worker/Dockerfile` |
| Audiveris | 5.11.0 | `lilyscan/runtime/config.py`, `services/audiveris-worker/Dockerfile` |
| Python | 3.12 | `pyproject.toml` |

Changing a pin is its own change: update both places, then re-run the eval harness.

## Run (homelab)

Images are published to GHCR from `main`:

```bash
docker compose pull
```

```bash
docker compose up -d
```

Or build locally with `docker compose up -d --build`. Pin a specific build with
`LILYSCAN_TAG=sha-<commit>`.

The API listens on port 8000 (override with `LILYSCAN_PORT`). With an NVIDIA GPU and the
NVIDIA Container Toolkit installed:

```bash
docker compose --profile gpu up -d --scale worker=0
```

Services:

- `api`: FastAPI, upload and job status (`/api/jobs`).
- `worker`: pipeline worker (LilyPond, OpenCV, ONNX Runtime). `worker-cuda` under the `gpu` profile.
- `audiveris`: Audiveris 5.11.0 behind an RQ worker on the `engine` queue. JVM heap is capped
  by `AUDIVERIS_MAX_HEAP` (default `3G`).
- `redis`: job queue, served by Valkey (BSD-3, Redis-protocol compatible).

### OCR languages

Lyrics and text are recognized in English, Latin, German, and French by default. To add
languages, list their [Tesseract codes](https://github.com/tesseract-ocr/tessdata) in
`LILYSCAN_OCR_LANGUAGES` (for example in a `.env` file next to `docker-compose.yml`):

```bash
LILYSCAN_OCR_LANGUAGES=eng+lat+deu+fra+ita+spa
```

The `audiveris` service downloads missing models at startup into the `tessdata-cache`
volume, where custom `*.traineddata` models can also be placed. A single job can override
the languages with the `ocr_languages` form field when uploading.

## Develop

```bash
uv sync --all-extras
uv run pytest -m "not gpu and not engine"
uv run ruff check . && uv run mypy
uv run lilyscan device
uv run lilyscan selftest
```

Convert a MusicXML file (for example Audiveris output) into an editable LilyPond project
with a QA report (Q1-Q5):

```bash
uv run lilyscan convert score.mxl --out out/score
```

### Evaluation

The seed corpus is generated from `eval/corpus/seed.json` (random-but-valid music with exact
MusicXML ground truth, engraved with LilyPond, then degraded to simulated scans and photos).
Build it, then score Audiveris on every piece and input variant:

```bash
uv run lilyscan corpus build
```

```bash
uv run lilyscan eval run --label my-run --lilypond
```

Results go to `eval/results/<label>/` (`summary.md`, `results.json`); engine output is cached
in `eval/work/`. The summary reports exact-measure accuracy, edit rate, note F1, lyric and
chord accuracy, how many engine events were located on the page, and how well the engine's
confidence is calibrated. `--lilypond` also runs the full pipeline on each engine output and
reports how often it compiles (Q1) and passes bar checks (Q2).

A second corpus holds real repertoire: excerpts of public-domain works from music21's bundled
corpus (`eval/corpus/repertoire.json`). The encodings are not committed; the build fetches them
from the installed music21 package and records each work's rights statement in the manifest.
Evaluate it separately from the generated pieces:

```bash
uv run lilyscan corpus build --spec eval/corpus/repertoire.json --out eval/corpus/repertoire
```

```bash
uv run lilyscan eval run --corpus eval/corpus/repertoire --label my-repertoire-run --lilypond
```

### Job outputs

A finished job's folder holds `ir/score.json` (the internal model, with page boxes and
confidence per event), the LilyPond project in `ly/` (`main.ly`, `parts/`, `layout/`, plus the
compiled PDF and MIDI), `report.json` (engine run, geometry, QA checks Q1-Q5), and
`overlays/page-N.png`: each page as the engine saw it, with every note boxed in green, amber,
or red by confidence. Low-confidence notes are also marked in the LilyPond source as
`%{ ?? conf=... %}`, so `grep "??"` lists them.

Tests marked `lilypond` need LilyPond (`LILYPOND_BIN`, default `lilypond`); `engine` tests
need Audiveris (`AUDIVERIS_BIN`, default `audiveris`); `gpu` tests need a CUDA device. Missing
tools cause a skip, not a failure.

To run the `engine` tests against a local Audiveris install, point `AUDIVERIS_BIN` at its
executable and `TESSDATA_PREFIX` at a folder containing `eng.traineddata` from
[tesseract-ocr/tessdata](https://github.com/tesseract-ocr/tessdata). Audiveris needs the full
models with the legacy engine; `tessdata_fast` and distribution `tesseract-ocr-*` packages do
not work.

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE).
