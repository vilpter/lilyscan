# Lilyscan

Sheet music (PDF, scans, phone photos) to editable, well-structured LilyPond, built
around the [Audiveris](https://github.com/Audiveris/audiveris) OMR engine.
The design is in [docs/design.md](docs/design.md).

**Status:** early development, tested on generated and public-domain scores but not yet on
real-world scans and phone photos. It runs end to end on one machine. Done: the engine
wrapper, evaluation harness, LilyPond generator and checks, page geometry, review UI,
repairs of the engine's output with a calibrated confidence, the photo and scan front-end,
born-digital PDF rendering, and the score combiner. On the evaluation corpora about 83% of
measures come out exactly right overall (PDF 89-92%, PNG 83-88%, scans 83%, photos 72-74%);
see `eval/results/` and the status log in [docs/design.md](docs/design.md).

## Pinned toolchain

| Tool | Version | Where |
|---|---|---|
| LilyPond | 2.26.0 | `lilyscan/runtime/config.py`, `services/worker/Dockerfile` |
| Audiveris | 5.11.0 | `lilyscan/runtime/config.py`, `services/audiveris-worker/Dockerfile` |
| Python | 3.12 | `pyproject.toml` |

Changing a pin is its own change: update both places, then re-run the eval harness.

## Review UI

Open the app in a browser (port 8000 by default) to upload a PDF, scans, or phone photos,
follow the job, and review the result in three panes. Pages are prepared for the engine
first (unless you untick the option). Born-digital PDFs are rendered sharply at 400 DPI, and
the triplets they print are used to check the rhythm. Photos and scans are straightened: the
page is found and seen head-on, uneven light is evened out, sideways or upside-down pages
are turned upright, and curled or skewed staff lines are made straight. A scan is also transcribed as uploaded, and Lilyscan keeps
whichever result it expects to be better.

- **Source:** the page as the engine read it, every note boxed by confidence and measures
  that need attention outlined.
- **Engraved:** the LilyPond output.
- **LilyPond:** the editable source, with a review list of measures ranked by problems.

Clicking a measure in any pane (or putting the caret on its line) highlights it in the other
two. Edit the source, press **Save & recompile**, then download the `.ly` project, PDF, or MIDI.
Fixes Lilyscan made on its own (parts the engine split between systems, tenor clefs read
without their 8, missed triplets, page text or chord names read as lyrics, syllables read as
one word) are listed above the review list and noted in the source as `% fix:` comments.

**Combine parts** (on the home page) makes a new score from parts of finished jobs: say a
violin part and a cello part scanned separately. Each part can be transposed, or taken at
concert pitch if it is a transposing instrument. The parts must have the same measures. Your
edits to each part carry over, because the new score includes the parts' own LilyPond
sources (transposition is done by LilyPond's `\transpose`, so those sources stay as
written). The combined score is a job like any other: review it, edit it, download it.

To try it on one machine without Docker or Redis (jobs run inside the server process):

```bash
uv run lilyscan serve
```

It needs LilyPond and Audiveris installed locally; on Windows it finds a standard Audiveris
install and OCR models in `%LOCALAPPDATA%\lilyscan\tessdata`.

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
  by `AUDIVERIS_MAX_HEAP` (default `3G`). An engine run may take the larger of
  `AUDIVERIS_TIMEOUT_S` (default 900) and `AUDIVERIS_TIMEOUT_PER_PAGE_S` (default 240) per
  page, so long scores are not cut off.
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
reports how often it compiles (Q1) and passes bar checks (Q2). `--repair` applies the Stage 5
repair rules and Lilyscan's confidence before scoring, so a rule can be measured against the
same cached engine output; the summary lists every repair made. `--prepare` sends scans and
photos through Stage 1 first (new engine runs, cached separately) and applies the same
choice between prepared and uploaded scans as a job; the summary adds a row per input type
for pages that passed the quality gate. New engine runs need Audiveris and its OCR models
(`AUDIVERIS_BIN`, `TESSDATA_PREFIX`); without the models Audiveris reads no text at all.

Lilyscan's confidence is a small model fitted on both corpora. After changing a repair rule
or adding corpus pieces, refit it (this rewrites `lilyscan/repair/confidence.json` and prints
the out-of-fold calibration error per corpus):

```bash
uv run lilyscan eval calibrate
```

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

A finished job's folder holds `prepared/` (`pages.tif`, every page of the job as given to
the engine, in upload order; `uploaded.tif` when some page is a scan; and `report.json` with
what was done to each page and any quality warnings), `ir/score.json` (the internal model, with page
boxes and confidence per event), the LilyPond project in `ly/` (`main.ly`, `parts/`,
`layout/`, plus the compiled PDF and MIDI), `report.json` (Stage 1 pages, the engine run and
any alternative it was chosen over, geometry, repairs, QA checks Q1-Q5), and
`overlays/page-N.png`: each page as the engine saw it, with every note boxed in green, amber,
or red by confidence. Confidence is Lilyscan's estimate that the note is right, calibrated on
the evaluation corpora (a note at 0.7 is right about 70% of the time), not the engine's raw
grade. Notes below 0.5 are also marked in the LilyPond source as `%{ ?? conf=... %}`, so
`grep "??"` lists them.

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
