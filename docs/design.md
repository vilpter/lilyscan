# Lilyscan: Design and Build Plan

Sheet music (PDF, scans, phone photos) → editable LilyPond, via optical music recognition built around Audiveris.

**Project:** Repository `github.com/vilpter/lilyscan` (public, AGPL-3.0-or-later; see D8). Python package `lilyscan`, CLI `lilyscan`.
**Document purpose:** the design and build plan. Work proceeds in the milestone order in §10. Each pipeline stage is independently testable and emits a debuggable intermediate artifact.
**Revision 2 (2026-09-26):** re-scoped from a mostly custom recognition pipeline to **wrap-and-extend Audiveris**. Audiveris is the recognition engine for raster input. This project owns everything around it: input preparation, the born-digital path, the intermediate representation (IR), semantic repair, LilyPond generation, QA, and the web review UI. Custom recognition stages are built only where metrics show Audiveris is weak (§6, Stage 9).
**Revision 3 (2026-09-26):**
- Closed D5 (LilyPond 2.26.0) and added D8 (name, public repo, project management).
- Recorded what M0 measured and confirmed about Audiveris 5.11.0 (§5, Stage 2, Stage 3).
- Opened and closed D9: the implementation stack stays Python around the Audiveris CLI (§5.2).
- Added the synthetic seed corpus (§9.1) and a status log (§14).

---

## 1. Goal

Ingest sheet music (born-digital PDF, scanned print, phone photo) and produce **editable, well-structured LilyPond source**. The source should compile cleanly, round-trip visually against the original, and be organized so parts can be edited individually or combined into new scores.

**Success is measured by edit distance, not by perfection.** OMR is never 100% accurate, so the product must:
1. Get most symbols right.
2. Tell the user **exactly where it is unsure**.
3. Make correction fast.

## 2. Confirmed Requirements

| Item | Decision |
|---|---|
| Inputs | Born-digital PDF, scanned printed scores, phone photos. **Handwritten scores are out of scope.** |
| Notation scope (end state) | Full scores: multi-staff systems, polyphony (multiple voices per staff), lyrics, chord symbols |
| Recognition approach | **Wrap-and-extend Audiveris** (D6). Audiveris does core recognition; custom stages prepare its input, repair and enrich its output, and replace parts of it only when metrics justify it |
| Delivery | Self-hosted web app on the homelab (Docker Compose, Ubuntu 24.04 host, no snap packages) |
| Compute | CPU baseline; NVIDIA GPU used automatically when present (D1) |
| Output | LilyPond `.ly` (primary); MusicXML (secondary, via internal model) |

## 3. Non-Goals (v1)

- Handwritten manuscripts
- Early / mensural notation, tablature, percussion-specific notation beyond basic unpitched staves
- Real-time or mobile-native capture app (browser upload from a phone is fine)
- Multi-user accounts or auth beyond reverse-proxy protection on the LAN
- **Forking or modifying Audiveris source** (see D6). Also out of scope: using Audiveris's desktop GUI as part of the product

## 4. Resolved Decisions

| # | Topic | Decision |
|---|---|---|
| D1 | Compute | **The GPU is optional.** Auto-detect CUDA at worker startup and use it when present; otherwise run everything on CPU. No feature may require a GPU. Audiveris itself is CPU-only. The GPU only matters for this project's own models (photo dewarping, any custom detectors). See §5.1. |
| D2 | Licensing | **AGPL tools are approved** (Audiveris, PyMuPDF). The project itself is licensed **AGPL-3.0-or-later**, and its source is public (D8), which satisfies AGPL's source-availability terms for anyone who runs it. Anything bundled or linked must be AGPL-compatible. |
| D3 | Transposing instruments | Output written pitch as printed, with `\transposition` declared per part. Concert pitch is available as an export-time option. |
| D4 | Pitch entry | Absolute / `\fixed c'` entry. Never `\relative`. |
| D5 | LilyPond version | **Pin 2.26.0** (current stable since 2026-04-21) in the pipeline worker Dockerfile. The pin is reproducible but not hard-wired:<br>• The generator writes `\version` from a single constant in the config module and uses only the stable core syntax: notes, `\key`, `\time`, `\clef`, `\repeat`, `\lyricmode`, `\chordmode`, variables, `\include`.<br>• CI compiles the corpus output against the next LilyPond release as a non-blocking job.<br>• An upgrade is its own change: bump the constant and the Dockerfile, run `convert-ly` on stored projects, re-run the eval harness, and refresh the golden files.<br>• Diagnostic parsing is version-sensitive. For example, 2.26 prints `bar check failed` where 2.24 printed `barcheck failed`. Q1/Q2 parsing has tests for every wording it accepts. |
| D6 | Recognition engine | **Wrap Audiveris; do not fork it.** Talk to it only through its public surfaces: batch CLI, `-constant key=value` overrides, exported MusicXML, and saved `.omr` project files. Send bugs and fixes upstream as issues or PRs with minimal repros from the corpus. Keep a local patch only as a last resort, and only with explicit approval. |
| D7 | Audiveris version | Pin one release (currently **5.11.0**) in the Audiveris worker image. An upgrade is a deliberate change: run the full eval harness on the new release and bump only if nothing regresses. The `.omr` reader is versioned against the pinned release. |
| D8 | Name, repository, management | **Lilyscan**, at `github.com/vilpter/lilyscan`, public.<br>• Commits use the GitHub no-reply address and conventional-commit messages, one or more per milestone.<br>• Milestones M0–M10 are GitHub Milestones, with one issue per deliverable and exit criterion, labeled by pipeline stage and tracked on a GitHub Projects board.<br>• CI publishes the `api`, `worker`, and `audiveris` images to `ghcr.io/vilpter/lilyscan-*`, so the homelab pulls images instead of building them.<br>• The CUDA image builds only on manual dispatch or a weekly schedule. |
| D9 | Implementation stack | **Python around the Audiveris CLI (option A)**, decided 2026-09-26. Option D (a small JVM tool that exports the `.omr` model to project-owned JSON) is the fallback if the Python `.omr` reader proves too fragile at M3. The analysis is in §5.2. |
| D10 | OCR languages | Lyrics and text are recognized in **English, Latin, German, and French by default** (`eng+lat+deu+fra`).<br>• `LILYSCAN_OCR_LANGUAGES` (Tesseract codes joined with `+`) sets the deployment default. The Audiveris container downloads any listed language it doesn't have at startup, from the same pinned `tessdata` commit, into a persistent cache volume. Custom `*.traineddata` models dropped into that volume are picked up too.<br>• A job can override the languages at upload (`ocr_languages` form field). A requested language that isn't installed fails the job with a clear message.<br>• More languages slow OCR down somewhat, so the default stays at four. |
| D11 | Real repertoire in the evaluation corpus | **Approved** by the owner (2026-09-26): music21's bundled corpus and Mutopia sources may be used as evaluation material.<br>• Each piece's source and licence are recorded in the corpus spec and manifest.<br>• Encodings are fetched or generated at build time and never committed (built corpora are git-ignored), so the public repository does not redistribute them.<br>• Results for real repertoire are reported separately from generated pieces. |
| D12 | Model and data licenses | **Decided by the owner (2026-09-27):** everything Lilyscan ships, bundles, depends on or trains on must be AGPL-compatible, so the project's license stays consistent with Audiveris's.<br>• Allowed: MIT, BSD, Apache-2.0, LGPL, GPL and AGPL for code and weights; CC BY, CC0 and public domain for data and weights (with attribution where required).<br>• Not allowed: non-commercial (NC), research-only, no-derivatives (ND), custom or unstated terms.<br>• Exception: **oemer as an opt-in extra** for the Q7 second engine. Its code is MIT, but it downloads its own pretrained weights on first use, and its first model is trained on CVC-MUSCIMA (CC BY-NC-SA 4.0, non-commercial research only). It is never installed by default, and the caveat is shown where it is enabled.<br>• DeepScoresV2 (CC BY 4.0) may be used to train Stage 9 models, with attribution. |

## 5. System Architecture

```
┌──────────────┐  upload   ┌────────────┐  enqueue  ┌──────────────────────────┐
│  Web UI      │ ────────▶ │  API       │ ────────▶ │  Pipeline worker (Py)    │
│  (review /   │ ◀──────── │  (FastAPI) │ ◀──────── │  ingest, preprocess,     │
│  correction) │  results  └─────┬──────┘  status   │  import, repair, IR,     │
└──────────────┘                 │                  │  LilyPond, QA            │
                                 │                  └──────┬──────────▲────────┘
                           ┌─────▼──────┐      enqueue     │          │ .omr + .mxl
                           │ Job DB     │      (engine q)  ▼          │
                           │ (SQLite →  │           ┌─────────────────┴────────┐
                           │  Postgres) │           │ Audiveris worker          │
                           └────────────┘           │ (pinned Audiveris .deb,   │
                                                    │  bundled Java runtime,    │
                                                    │  thin Python RQ shim)     │
                                                    └──────────────────────────┘
                     all stages read/write ─▶ shared artifact store (per-stage JSON, images, .omr, .mxl, .ly)
```

- **Backend:** Python 3.12, FastAPI, RQ on Valkey, which speaks the Redis protocol (D9).
  - A job is a chain of RQ jobs linked with `depends_on`: `engine_transcribe` on the `engine` queue, then `pipeline_finish` on the `pipeline` queue.
  - Job records live in SQLite (`jobs.sqlite` in the data volume), with a Postgres-compatible schema.
- **Pipeline worker image:** includes LilyPond, OpenCV, PyMuPDF, music21, and ONNX Runtime. PyTorch is included only in the CUDA variant or once a custom model is added. Built in CPU and CUDA variants (§5.1).
- **Audiveris worker image:** a separate container built from Ubuntu 24.04 plus the official `Audiveris-<ver>-ubuntu24.04-x86_64.deb`. A small Python RQ shim listens on the `engine` queue, runs the Audiveris batch CLI, and writes `.omr` and `.mxl` to the artifact store. This avoids mounting the Docker socket or running `docker exec` from another container.
  - **No separate JDK is needed.** The Audiveris package is a jpackage bundle with its own Java runtime (Java 25 in 5.11.0), Tesseract 5.5.2 (through JavaCPP), PDFBox, and a pure-Java glyph classifier. It has no ML framework dependency.
  - **Install by unpacking, not `apt install`.** The package's post-install script only registers desktop menus and MIME types. In a headless container it exits with status 3 ("No writable system menu directory found"). The image unpacks the package with `dpkg-deb -x` and installs its declared runtime dependencies, minus `xdg-utils`.
  - **A headless start needs `sun.java2d.uiScale`.** On Linux, Audiveris probes GTK for HiDPI scaling in a static initializer. It catches `Exception` but not the `UnsatisfiedLinkError` thrown when `libgtk-3` is absent, so it crashes even in batch mode. Adding `-Dsun.java2d.uiScale=1` to the launcher config skips the probe without pulling GTK and X11 into the image. The image build runs `audiveris -batch -version`, so any startup failure fails the build. This is a candidate for an upstream report (D6).
  - **Tesseract language data must be supplied, and it must be the full `tessdata` models.**
    - Audiveris looks in `TESSDATA_PREFIX` first, then in its user config folder.
    - It uses Tesseract's **legacy** engine. The LSTM-only `tessdata_fast` models, which Ubuntu's `tesseract-ocr-*` packages ship, fail with "Tesseract (legacy) engine requested, but components are not present".
    - With no data at all, it logs "The collection of supported languages is empty".
    - Either way, text recognition is skipped silently: no lyrics, titles, or chord names.
    - The image bakes in the default language models (`eng`, `lat`, `deu`, `fra`) from `tesseract-ocr/tessdata` (Apache-2.0) at a pinned commit and verifies them against `services/audiveris-worker/tessdata.sha256`. Other languages are handled as D10 describes.
    - The runner detects these failure messages, plus "Missing support for ..." when a requested language has no model. An engine job with broken OCR fails, and so does the smoke test, which also requires all four default languages.
  - **Heap cap.** The launcher config (`lib/app/Audiveris.cfg`) hard-codes `-Xms512m -Xmx8G`. Command-line options override `JAVA_TOOL_OPTIONS`, so an environment variable cannot lower the heap that way. Instead, the container entrypoint rewrites the `-Xmx` line from `AUDIVERIS_MAX_HEAP` (default `3G`, validated) at each start. This is package configuration, not a source change, so it is allowed under D6.
  - The shim runs one book at a time per container; scale by adding replicas. Each job is a fresh Audiveris process, so a crash or out-of-memory error loses only that job.
  - Per-job Audiveris options (for example, forcing the interline or disabling a recognition step) are passed as `-constant key=value` and stored with the job for reproducibility.
- **Frontend:** lightweight SPA. Core views:
  - Source page with a detection overlay (color-coded by confidence), built from `.omr` bounding boxes.
  - Rendered LilyPond output (SVG) side by side, aligned per system or measure.
  - Code editor for the `.ly` source, with recompile-on-save.
  - Click a measure in any pane to highlight it in the other two, using LilyPond point-and-click links plus the IR bbox map.
- **Storage:** every stage writes a versioned artifact, so any stage can be re-run or inspected in isolation. The raw Audiveris outputs (`.omr`, `.mxl`, log) are kept per job.

### 5.1 Compute: CPU/GPU Selection

- **Single device abstraction.** One module, `lilyscan/runtime/device.py`, decides the device. Nothing else in the codebase calls `torch.cuda` or picks an ONNX execution provider directly.
  - Resolution order:
    1. The `LILYSCAN_DEVICE` environment variable (`auto` | `cpu` | `cuda`; default `auto`).
    2. Auto-detect (`torch.cuda.is_available()` when torch is installed, else the ONNX Runtime CUDA provider).
    3. Fall back to CPU.
  - Log the selected device and GPU name at worker startup. Show the active device in the job report.
- **Graceful degradation.** A CUDA out-of-memory error or a CUDA init failure retries that stage on CPU, logs a warning, and never fails the job.
- **Two pipeline worker image variants** from one Dockerfile, via a build argument:
  - `worker-cpu`: the default in `docker-compose.yml`.
  - `worker-cuda`: enabled by a Compose profile (`docker compose --profile gpu up`).
    - Uses the NVIDIA Container Toolkit, installed from NVIDIA's apt repository.
    - Uses the `deploy.resources.reservations.devices` block.
- **The Audiveris worker is CPU-only** and has no GPU variant.
- **CPU performance path.** Export any custom models to ONNX and run them through ONNX Runtime on CPU. Tile large pages to bound memory.
- **Equivalence requirement.** When a GPU is present, the eval harness runs the corpus on both devices. Measure-level results must match. Allow only tiny floating-point confidence drift (for example, less than 0.01).
- **Performance targets.**
  - End-to-end under 60 s per scanned page at 300 DPI on a modern 8-core desktop CPU, **including Audiveris time**. Measure the Audiveris share separately at M1.
  - **First measurement (M0, Windows dev machine):**
    - One-page SATB chorale (3 systems × 4 staves, 2480×3508 px at 300 DPI) took **~25 s** in Audiveris, from either PNG or PDF input.
    - JVM startup is ~1.4 s of that. The rest is recognition, dominated by the HEADS (~6 s) and BEAMS (~4 s) steps.
    - That leaves ~35 s per page for everything else.
  - These are initial targets; revise them after the M1 measurements on the homelab CPU.
- **CI runs CPU-only.** GPU tests are marked (`@pytest.mark.gpu`) and skipped when no GPU is present. Audiveris-dependent tests are marked (`@pytest.mark.engine`) and run in a CI job that uses the Audiveris worker image.

### 5.2 Stack Alignment with Audiveris (D9, decided: option A)

Audiveris is a Java application: a Gradle build, a bundled Java 25 runtime, a Swing GUI, JAXB for `.omr` and MusicXML (proxymusic), PDFBox, and Tesseract and Leptonica through JavaCPP. It is **not published as a library**; only `org.audiveris:proxymusic` is on Maven Central. Any in-process use means taking jars from the release package or building from source.

**Options**

| | Option | What it means |
|---|---|---|
| A | **Python + Audiveris CLI** (current) | Audiveris runs as a separate process per job. Python owns everything else. |
| B | Python + in-process Audiveris via JPype | Python embeds a JVM and calls Audiveris classes directly. |
| C | JVM stack throughout (Java 25 or Kotlin) | Audiveris jars on the classpath. The API, pipeline, IR, and generator are all JVM code. |
| D | **A, plus a small JVM export tool** (fallback) | Python as in A. The Audiveris container also carries a small Java CLI, built against the pinned Audiveris jars, that loads a saved `.omr` through Audiveris's own classes and writes a project-owned JSON export. It is used only if M3 shows that parsing `.omr` XML from Python is too fragile. |

**Where each stage's best tools live**

| Concern | Python | JVM | Edge |
|---|---|---|---|
| Stage 1 photo correction (OpenCV, dewarping models) | OpenCV, scikit-image, ONNX Runtime; most research code | JavaCV/OpenCV bindings, ONNX Runtime Java; few examples | Python |
| Stage 3 reading Audiveris results | Re-implement the `.omr` XML schema (published per release as `Audiveris_Schemas_Doc`) | Unmarshal with Audiveris's own classes; full access to the interpretation graph | **JVM** |
| Stage 4 vector oracle | PyMuPDF (AGPL) | PDFBox (Apache-2.0; already inside Audiveris) | Tie |
| Stage 5 repair (key-mode finding, analysis helpers) | music21 (key analysis, pitch and interval utilities) | Nothing comparable | Python |
| Stage 6–7 IR, MusicXML, LilyPond | pydantic, music21, python-ly; LilyPond's own tools (`convert-ly`, `musicxml2ly`) are Python | Records, proxymusic; no LilyPond tooling | Python |
| Eval harness, synthetic data, any Stage 9 training | The whole OMR research ecosystem | Little | Python |
| API, queue, web | FastAPI, RQ | Javalin/Spring, JobRunr | Tie |
| Upstream contributions to Audiveris | Needs Java anyway | Same language | JVM |

**Trade-offs**
- **Performance:** a resident JVM would save only the ~1.4 s startup out of ~25 s per page (§5.1), about 5–10%. This is not a deciding factor.
- **Isolation:** option A runs a fresh process per job, so an Audiveris crash, memory leak, or global state from one book cannot affect another. Options B and C put Audiveris, which was built as a single-user application with global state, inside a long-running server. They would need their own per-job process isolation to get the same safety.
- **Stability of the interface:** Audiveris's internal classes are no more stable than the `.omr` format. But a breaking change in them fails at compile time (C, D) instead of at parse time (A). That is the real advantage of D.
- **D6 compatibility:** options B and C vendor the Audiveris jars. That amounts to linking rather than wrapping, and would need D6 amended. Option D confines this to one small, replaceable tool.
- **Existing work:** the M0 skeleton (~700 lines of Python) is small; this is not a deciding factor either.

**Decided (2026-09-26): option A, with D held in reserve for M3.** Everything this project owns is either Python-leaning (CV, ML, music analysis, LilyPond tooling, evaluation) or neutral (web, queue). The one place the JVM clearly wins is reading Audiveris's model, and D gets that benefit without moving the whole stack. **Choose C only if you would rather maintain Java than Python**, for example if contributing upstream to Audiveris becomes a major goal. Maintainer fluency matters more than any row in the tables above.

## 6. Recognition Pipeline (core of the project)

Each stage has typed input and output, an artifact, a debug visualization where meaningful (a PNG overlay), and tests.

**Division of labor:**

| Concern | Owner |
|---|---|
| Input routing, photo correction, quality gate | This project (Stages 0–1) |
| Staff, layout, symbol, and text recognition; first-pass rhythm, voices, keys | **Audiveris** (Stage 2) |
| Importing Audiveris results with bounding boxes and confidence | This project (Stage 3) |
| Born-digital vector symbols used as a correction oracle | This project (Stage 4) |
| Repair and enrichment: constraint repair, key mode, part mapping, chords, lyrics cleanup, transposition | This project (Stage 5) |
| IR, LilyPond, QA | This project (Stages 6–8) |
| Replacing an Audiveris capability | This project, **conditional** (Stage 9) |

### Stage 0 — Ingest and Routing
- Detect the input type: vector PDF, raster PDF, or image. Classify images as scan-like or photo-like (page border visible, perspective, uneven lighting).
- **Every input goes through Audiveris.** Born-digital PDFs are rendered at 400 DPI, which gives Audiveris ideal input. Their embedded vector and font data is extracted separately for Stage 4.
- Routing summary:
  - Vector PDF → render 400 DPI → Stage 2; vector extraction → Stage 4.
  - Raster PDF / scan → Stage 1 (light) → Stage 2.
  - Photo → Stage 1 (full) → Stage 2.

*Done (M7), in `lilyscan/ingest/pdf.py`:* each PDF page is classified: raster when one image covers at least 80% of it, vector when it has drawings or text. Born-digital PDFs are rendered with PyMuPDF at 400 DPI, in grayscale, into one multi-page TIFF (one engine book). Scanned PDFs still go to the engine as uploaded; giving their page images to Stage 1 is left for later.
- **Measured (A/B on both corpora, same pieces):** Audiveris rendering the PDF itself (PDFBox, 300 DPI) gave 87.4% exact measures. Our 400 DPI rendering gave 90.5% (16 pieces better, 4 worse). Audiveris's own rendering at 400 DPI (`ImageLoading.pdfResolution`) gave 81.6%, so the renderer matters, not only the resolution.

### Stage 1 — Input Preparation (front-end for Audiveris)
Audiveris expects clean, flat, scan-like pages. This stage turns every raster into one.
- **Photos (full):**
  - Page boundary detection, then perspective correction (homography).
  - Page-curl dewarping, using detected staff lines as a straightness prior.
  - Illumination flattening.
- **Scans (light):** deskew, crop margins, remove speckles. Leave binarization to Audiveris unless an A/B test in the eval harness shows that our binarization improves its accuracy.
- **Fundamental scale and quality gate:**
  - Estimate staff line thickness (modal black vertical run length) and staff space (modal white run length).
  - Rescale to a target interline that Audiveris handles well (determine this empirically at M1).
  - Reject or warn if the staff space is too small, or if the curvature residual after dewarping is too high. Tell the user to rescan or reshoot.
- All thresholds are in staff-space units, stored in the single config module.

*Done (M6), in `lilyscan/prepare/`:*
- **Page and perspective:** Otsu's threshold on a downscaled copy separates paper from background; the largest bright region, simplified to four corners, is warped to a rectangle. A page filling more than 95% of the frame is taken to be a scan and left uncropped. When the paper runs off the frame, a shadow or stain can bend the outline through the music; an outline that leaves more than 2% of the staff lines it holds outside is not used, and the photo stays whole (on the real photos, one outline in ten did this and lost the start of three systems).
- **Light:** a max filter wider than any stroke removes the ink, and the smoothed result (the paper's brightness) is divided out.
- **Orientation:** the staff angle is the rotation that makes the ink's row profile spikiest (a 2° search over half a turn, refined to 0.25°). On dense pages, such as a choir score with lyrics under every staff, a quarter turn can score spikier, both because the same ink spread over fewer rows scores higher and because noteheads line up down the page. So the best angle and the one a quarter turn from it are both refined, and the one showing more five-line staves is kept. Pages more than 2° off are turned by it, quarter turns exactly; smaller tilts are left to dewarping. Once the lines are straight, two cues say whether the page is upside down. Text: in Latin script the tops of letters vary while their bottoms line up on the baseline, and upside down it is the reverse (counted once there are 60 letters). Clefs: ink overhangs the staff lines at the start of each staff, not the end. Each cue is measured in units of its usual strength (a text lean of 0.01, a clef overhang of 0.2). A page that came sideways is turned over when their sum points that way, since its quarter turn was as likely to leave it upside down as not. A page that came upright usually is upright, and either cue can be fooled on its own (chord slashes and fingerings read as letters; an alto clef), so it is turned over only when neither cue says upright by 1.5 units and both lean the other way by 0.5, or one does by 3.5. Tested end to end on every corpus scan and photo and on 31 real phone photos, each given upright, sideways and upside down (375 cases): no upright page is turned over, every sideways page comes back upright, and 6 of the 125 upside-down cases stay upside down (the previous rules turned 6 choir pages on their side and 3 upright photos over). Three upright scans in a real library of string parts that the summed rule still turned over are right now.
- **Scale:** staff line thickness and interline are the modal black and white vertical runs. Pages outside an interline of 16-32 px are rescaled to 24 px.
- **Dewarping:** the page is cut into 24 vertical strips. Their horizontal ink profiles (which peak at staff lines) are aligned window by window down the page, outward from the centre strip. A smooth displacement field (cubic in x, quadratic in y) fitted to those shifts is removed by remapping, which straightens curl and skew. Lines straighter than 0.1 staff spaces are left alone. Photos are then denoised (non-local means) and sharpened (unsharp mask), which helped Audiveris in an A/B test on the seed photos; our own binarization did not.
- **Quality gate:** a warning when the interline is below 9 px or the lines stay curved by more than 0.2 staff spaces after dewarping. Every simulated photo passes, so the thresholds still need real photos (#11).
- **Scans:** Stage 1 alone is a coin flip on scans (seed A/B: 10 better, 12 worse; Audiveris already deskews, and small changes to a page swing its output both ways). For a page with no edges found, the job transcribes both the prepared and the uploaded page (turned upright by quarter turns, since Audiveris cannot read a page on its side), and keeps the run with more events expected to be right after repair (the sum of calibrated confidences).
- **Result** (`eval/results/m6-prepare`, `eval/results/m6-repertoire`, with Stage 5): seed photos 12.4% → 73.5% exact measures, scans 72.5% → 83.4%; repertoire photos 12.8% → 72.4%, scans 83.1% → 83.1%. Overall 64.7% → 82.7% (seed) and 66.8% → 81.7% (repertoire).

### Stage 2 — Recognition Engine (Audiveris)
- Invoke the pinned Audiveris release in batch mode (confirmed against 5.11.0 at M0):
  `audiveris -batch -transcribe -export -save -output <dir> [-constant key=value ...] [-sheets 1,3-4] -- <inputs...>`
- Inputs: the prepared page images (or 400 DPI renders), plus per-job `-constant` overrides. PDF input is accepted directly; Audiveris rasterizes it with PDFBox.
- OCR languages are passed on every run as `-constant org.audiveris.omr.text.Language.defaultSpecification=<spec>` (D10).
- The time one step may take on one sheet is passed as `-constant org.audiveris.omr.Main.sheetStepTimeOut=<s>` (`AUDIVERIS_STEP_TIMEOUT_S`, default 300; Audiveris's own default is 120). On a busy machine, pages mixing small and normal noteheads ran past 120 s in HEADS and the sheet was dropped (4 of 32 runs on the accompaniment corpus). The key is proven by a one-second limit stopping an ordinary page.
- **Audiveris silently ignores unknown `-constant` keys.** This was verified: the `Language$Constants.defaultSpecification` spelling is accepted without complaint and does nothing. Every key the project relies on therefore has a behavioural test against the pinned release. For example, the OCR key is proven by requesting an uninstalled language and checking that Audiveris complains.
- Failure messages from Audiveris steps (for example `StepException: No system found` on inputs with no recognizable staff) are extracted from the log into the job error.
- Outputs, written flat into `<dir>`: `<book>.omr`, `<book>.mxl` (per-movement files may appear for multi-movement books), and `<book>-<timestamp>.log`. The runner also records the command, wall time, and exit status in `engine/run.json`. Everything is stored in the artifact store.
- **Failure handling:** a crash or timeout on one sheet must not fail the whole book. Record which sheets failed and surface them in the job report.
- Never modify Audiveris behavior except through documented options (D6).

### Stage 3 — Engine Import (`.omr` + MusicXML → IR)
- **MusicXML → IR** is the baseline import (built in M1). It gives notes, durations, voices, lyrics and harmony, but no geometry or confidence.
- **`.omr` reader** (M3): the `.omr` file is a zip of JAXB-written XML documents: a book document plus `sheet#N/sheet#N.xml` per sheet, holding staves, systems, measures and interpretations with bounds and grades. Audiveris publishes the schema documentation with each release (`Audiveris_Schemas_Doc-<ver>.zip`), which lowers the format risk. Parse it to attach to every IR event:
  - Source bbox (page and pixel coordinates in the *original* upload, mapped back through the Stage 1 transforms).
  - Confidence, taken from the Audiveris grade (normalized and calibrated against the corpus).
  - Staff, system and measure geometry, for the overlay and click-to-sync.
- **Join strategy:** match MusicXML events to `.omr` interpretations by page, system, measure, staff, and onset order. Anything that cannot be joined gets a bbox from its measure and a confidence penalty.
- **What the 5.11.0 format provides** (inspected at M1 on the seed corpus):
  - `book.xml`: software version and build, the sheet list, each system's part names, and the *logical parts* the score was built from (which is where a split part such as `Flute`/`F1.` shows up).
  - `sheet#N/sheet#N.xml`: the scale (interline, line thickness, the music font Audiveris matched), and per system the *stacks* (measures), each with *slots* giving the x-offset of every time offset. It also has the staves with their line polylines, and the interpretation graph (`sig`).
  - Interpretations carry `shape`, `grade` (intrinsic confidence), `ctx-grade` (confidence in context), `staff`, and `bounds` in page pixels. Noteheads also carry a staff-relative `pitch` step. `head-chord` elements contain their heads through `containment` relations; `head-stem`, `beam-stem`, `alter-head`, `augmentation`, `slur-head` and `chord-tuplet` relations link the rest.
  - **Join plan:** MusicXML measure *i* is the *i*-th stack in page/system order. Within a stack, an event's onset selects a slot (and so an x-position). The head-chord on that staff nearest that x, whose heads' staff steps match the event's pitches under the active clef, supplies the bbox (the union of its heads) and the confidence (the minimum `ctx-grade` of the chord and its heads).
- **Implemented (M3):** `lilyscan/engine/audiveris/omr.py`.
  - An event's notehead is searched near its slot's x-position. A head at exactly the expected staff step may be up to 2.5 interlines away; any other head only 1 interline. This is needed because the engine's MusicXML onsets can disagree with its own slots after a rhythm misread. No head is given to two events.
  - Whole-measure rests are looked up anywhere in the measure, since they are drawn centred.
  - An event whose pitch matched no head at the expected step gets half the confidence.
  - Engines pad a part that is absent from a system with invented rests. Those measures have nothing on the page to locate and are excluded from the coverage rate.
  - On the seed corpus, 98% of locatable events get a box.
  - **Movements** (found on real photos): Audiveris starts a new movement at every indented system and exports one MusicXML file per movement, numbering its parts afresh. A misread first system can become a movement of its own with fewer parts (a violin line alone, above a piano accompaniment). Boxes are therefore attached per movement, while its parts still have the book's numbering. The movements are then merged by matching parts in order (same staves, same name); a part missing from a movement gets measure rests there, as Audiveris pads a part missing from a system, so `part-merge` can join parts split between movements. Merging staff by staff had dropped every staff beyond the first movement's: 39% of the notes on the real photos. A movement that states no key signature has none, rather than the previous movement's.
- **Confidence is not calibrated (M3 finding).** Audiveris grades measure how well a *symbol* was recognized. Most errors, though, are interpretation errors: octave clefs, rhythm, part splits, which a confident notehead does not reveal. On the seed corpus, events graded 0.9-1.0 (mean ~0.95) are right only about three times in four, and final interpretations are never graded below 0.5. So a raw grade never trips the `% ??` marker, and the review list must not rely on grades alone (see Stage 5, item 9). Per-band accuracy and the expected calibration error are reported in `eval/results/`. Since M5, Stage 5 replaces the grade with Lilyscan's calibrated confidence (Stage 5, item 9), and the overlays and markers use it.
- **Format risk:** `.omr` is an internal format, not a stable API. The reader is versioned against the pinned Audiveris release. It is guarded by golden-file tests built from saved `.omr` files, and it degrades to MusicXML-only import (with a warning) if parsing fails. Verify the exact schema against the pinned release's schema docs at M3. If the Python reader proves too fragile, switch to option D (§5.2): a small JVM tool that exports the model to project-owned JSON.

### Stage 4 — Born-Digital Vector Oracle
- Extract glyphs, codepoints and positions with PyMuPDF from PDFs that embed music fonts (Emmentaler, Bravura and other SMuFL fonts; Sibelius and Finale fonts). Also extract vector lines for staves, stems, beams and barlines.
- Maintain a glyph → symbol mapping table per known font.
- Map vector symbols into the 400 DPI render coordinates and match them to IR events by bbox.
- **Use the vector data as a correction oracle**, not as a separate recognition path. Where it disagrees with Audiveris on notehead count or position, accidentals, flags or beams, dots, rests, or clefs, correct the IR event, record provenance `vector-oracle`, and set a high confidence.
- Skip this stage when fonts are unmapped or outlined to paths; the raster result stands.
- Only if M7 shows the oracle cannot reach its target should a full vector-native semantic path be considered.

*Done (M7), in `lilyscan/vector/`:* glyphs are identified by name from the embedded font program (fontTools), because music fonts in PDFs carry no Unicode mapping. Staves come from groups of five evenly spaced vector lines. Italic digits in text fonts are tuplet numbers (LilyPond also sets an octave clef's 8 this way).
- **Tuplets (kept):** for each printed 3, the run of plain notes under it in the nearest voice becomes a triplet, when that makes the voice fill its measure. Measures with several missed triplets are handled together.
- **Noteheads (measured and dropped):** Audiveris's noteheads on PDF input already agree with the PDF's glyphs 99.5% of the time (5,341 of 5,367). Correcting from glyphs mostly added other voices' heads and grace notes, and lowered exact measures.
- **Remaining PDF errors** are rhythm the engine lost entirely (missed notes and rests inside tuplets). Rebuilding those from the vector symbols is the vector-native path this design defers.
- **Result** (`eval/results/m7-vector`, `eval/results/m7-repertoire`, with Stages 0, 4 and 5): PDF exact measures 85.3% → 89.0% (seed) and 88.4% → 91.9% (repertoire). The M7 target of 95% is not reached.

### Stage 5 — Semantic Repair and Enrichment
Pure functions over the IR, with heavy unit testing. This is where this project adds most of its accuracy on top of Audiveris.

1. **Measure duration validation and constraint repair.**
   - Check that each voice's durations sum to the time signature. The exceptions are a pickup (`\partial`), a final measure that complements the pickup, and cadenzas (`\cadenzaOn`).
   - For a failing measure, search the lowest-confidence events for a minimal change that makes it sum correctly (a missed dot, a flag count off by one, a misread rest, a missed tuplet). Apply it only if the resulting confidence is above threshold; otherwise flag the measure.
2. **Key mode.** MusicXML from Audiveris may lack a reliable mode. Infer it from:
   - Final bass note and first/last melody note (tonic evidence).
   - Frequency of the raised leading tone of the relative minor.
   - Otherwise default to major and flag low confidence.
3. **Accidental sanity.** Re-run the accidental state machine (per staff position and octave until the barline, ties carry across barlines, the key signature is the baseline) and flag events whose sounding pitch disagrees with the engine's.
4. **Part mapping across systems.** Audiveris's part detection is weak on full scores that hide empty staves (French scoring). Solve the staff-to-part mapping per system using instrument names and abbreviations, staff counts, clefs, and ranges. Low-confidence mappings are flagged for user confirmation.
5. **Chord symbols.** Normalize the engine's harmony output and any raw text through a chord grammar into LilyPond `\chordmode` (e.g. `F#m7b5` → `fis:m7.5-`). Attach each chord to a beat position.
6. **Lyrics cleanup.** Fix syllable-to-note attachment by x-alignment, handle hyphens and extenders, and handle verse numbering and stacked verse lines.
7. **Transposing instruments.** Tag parts by instrument name so the generator can emit `\transposition`.
8. **Structure.** Normalize repeats and voltas into `\repeat volta N { } \alternative { }`, and multi-measure rests into `R1*N`.

9. **Lilyscan's own confidence.** Combine the engine grade with the evidence Lilyscan already has, and calibrate the result against the corpus (for example isotonic regression per feature set), so that `% ??` markers and review ranking mean what they say. The evidence: the pitch/step mismatch from Stage 3, measures failing Q3 (rhythm), notes flagged by Q4 (range), and parts flagged by Q5 (alignment). Target: expected calibration error below 0.05 on the corpus. *Done (M5):* a logistic model over the grade and that evidence (plus measure density and staff and page quality), followed by an isotonic map fitted on out-of-fold predictions, stored as `lilyscan/repair/confidence.json` and refitted with `lilyscan eval calibrate`.

Each repair records its provenance and before/after values in the IR, so the UI can show what changed and why.

**Concrete targets found by the M1 baseline, and the M5 repairs** (Audiveris 5.11.0; see `eval/results/m1-baseline` and `eval/results/m5-repair`):
- **Octave clefs are read as plain clefs.** The small 8 under a tenor's treble clef is ignored, so every note comes out an octave high. Q4 (pitch range) flags it. Repair: when a part named or ranged like a tenor (or guitar) sits consistently an octave above its range under a G clef, set `octave_change=-1` and transpose. *Done (`octave-clef`):* a single-staff part named like a tenor (or the third of four voices) under a plain treble clef throughout, with a median note at or above D4, gets `treble_8` and is lowered an octave.
- **One part split in two by its abbreviation.** The short name on later systems (`Fl.`, read as `F1.`) becomes a second part, each half-empty. Q5 (alignment) flags it. Repair: merge parts whose names are abbreviation variants of each other and whose measures are complementary (one has only rests where the other has notes). This is a special case of part mapping (item 4). *Done (`part-merge`):* parts are merged when they are never present in the same measure (a measure is absent when it holds only a measure rest and has no page box), have the same staves and the same clefs where they first appear, and their names match: one abbreviates the other, allowing instrument aliases (`Vc.` for Cello), OCR confusions (`F1.` or `FI.` for `Fl.`), and numbers that were not read. A part with an unreadable name (`13.` for `B.`, or Audiveris's placeholder `Voice`) is placed by the staves above and below it in its system. Merged parts are ordered by their position within each system, so a part that enters later keeps its place. In a score with one staff per system (a lead sheet or solo), complementary parts with the same staff are also merged when one name is unreadable, such as the placeholder `Voice` next to `E.Pno`.
- **Page text read as lyrics.** The engraved footer was OCR'd as syllables under the lowest staff. Repair: drop lyric text that sits outside the vertical band of its staff's lyric lines, or that matches known non-lyric patterns (version strings, page numbers). The generator already quotes any non-word syllable, so such text can never break compilation. *Done (`lyric-text`, `lyric-verse`):* Audiveris reads every text line under a staff as lyrics and numbers the lines per system. A lyric line (one verse of one staff within one system) is dropped when it contains page text (version strings, URLs, copyright) or only stray marks (no letters, or a lone consonant). A line of chord names becomes chord symbols. Verse numbers then restart at 1 in each system, because one stray line above the lyrics otherwise turns verse 1 into verse 2 for that system only.
- **Missed triplets** (found during M5; about 90% of the wrong durations on clean input). *Done (`rhythm`, item 1):* a voice that does not fill its measure gets the fewest edits that make it fit: three plain notes of one type become a triplet, or one dot or flag is added or removed. Tied candidates are not applied. A dot or flag edit must line the voice up with other voices that fill the measure. A short voice is left alone when no other voice fills the measure (a pickup, or a phrase-end measure in a chorale). On clean input, 138 of 149 triplet repairs increased the matched notes and 12 of 14 dot or flag repairs did.
- **Key signatures misread on single systems** (found on real photos). Audiveris reads the key afresh at every system and on photos often misses an accidental there, which puts the system's notes a semitone off. *Done (`key-signature`):* within a piece, the key read on most systems wins (on a tie, the one with more accidentals, since missed accidentals are more common than invented ones). Where no staff changes key, the staves of a piece vote together, and a staff takes the piece's key when it reads it on its first system or on most of them; transposing parts and horns without a key keep their own vote. A change of key counts as real in mid-system or after a double bar. Notes the misread key had altered are respelled unless an accidental printed earlier in the measure governs them. On the real photos, 4 of the first 8 pieces had a system misread. On the seed corpus it repairs the alto-clef viola in six quartet scans and photos (exact measures: scans 83.4% → 84.2%, photos 73.5% → 74.5%); the repertoire is unchanged.
- **Glued syllables** (found during M5; about 260 cases on clean input). When two syllables sit close together, the engraver leaves out the hyphen, the OCR reads one word (`mazing` for `maz-ing`), and Audiveris attaches it to one note. *Done (`lyric-split`, item 6):* when the word's box from the `.omr` spans the heads of neighbouring notes that have no syllable of their own, the word is split so each piece sits under its note, centred or, before a melisma, left-aligned (glyph widths are approximated per character). Every piece needs a vowel, diphthongs are never split, and a poor fit leaves the word alone.

**M5 result so far** (same cached engine output, `lilyscan eval run --repair`):
- Seed corpus, exact measures 40.6% → 64.7% (PDF 49.9% → 85.3%, PNG 59.8% → 88.4%, scan 44.7% → 72.5%, photo 7.9% → 12.4%). Lyrics 43.0% → 64.8% (PDF 54.3% → 81.4%, PNG 54.7% → 79.3%, scan 51.8% → 75.4%, photo 11.0% → 23.3%), chords 29.5% → 31.2%. Engine outputs that compile: 100.0%.
- Repertoire, exact measures 58.8% → 66.8% (PDF 79.7% → 88.4%, PNG 78.5% → 82.8%, scan 67.4% → 83.1%, photo 9.6% → 12.8%). Lyrics 34.9% → 47.6%.
- Lilyscan's confidence (item 9): piece-grouped out-of-fold ECE 0.0086 (seed) and 0.0388 (repertoire), against 0.0629 and 0.0731 for the engine grades after repairs. Target met.
- Not done yet: key mode (item 2), accidental sanity (3), normalizing the engine's own harmony output (5), OCR misreads and hyphens and extenders in lyrics (6), transposing instruments (7), structure (8), and part mapping for staves Audiveris assigns to the wrong part within a system (4).

**Left for other stages (M5 findings):**
- Chord symbols printed with music-font glyphs (♭, Δ, °) are mostly not detected as text by Audiveris: about 150 chord names were found across both corpora, against about 360 in the ground truth. Only 3 had a superscript read as a separate word. Chord accuracy therefore needs chord-symbol recognition of our own (Stage 9), not repair.
- Accidentals and key signatures are nearly clean on scan-quality input: 0.3% of notes are wrong by their accidental alone (mostly double sharps read as sharps), and no key signature was misread. Items 2 and 3 have little to gain on this corpus.
- Of the measures still wrong on PDF and PNG input, most miss notes the engine did not see (dense piano chords especially). That is recognition work (Stage 9) rather than repair.

### Stage 6 — Internal Score Model (IR)
Keep a **project-owned IR**, a typed pydantic model. Don't generate LilyPond directly from engine output.

- **Hierarchy:** Score → Parts → Staves → Measures → Voices → Events (note/chord/rest/grace).
- **Attachments:** articulations, dynamics, lyrics, chord symbols.
- **Every event carries:**
  - Source bbox (page, coordinates in the original upload)
  - Confidence
  - Provenance: which stage created or modified it (`audiveris`, `vector-oracle`, `repair:<rule>`, `user`), including before/after values
- **Serializers:**
  - IR → LilyPond (primary, Stage 7).
  - IR → MusicXML (via music21 or a direct writer), for interop.
  - MusicXML → IR, used for Audiveris import, ground truth, and any second engine.

### Stage 7 — LilyPond Generation
Output conventions, optimized for **editing and combining**:

```
project/
  main.ly                 % \version, \header, \paper, \score assembly
  parts/
    violin-I.ly           % one music variable per part: violinIMusic = { ... }
    cello.ly
    soprano-lyrics.ly     % sopranoVerseOne = \lyricmode { ... }
  chords.ly               % harmonies = \chordmode { ... }
  layout/
    score.ly              % full-score layout (StaffGroup / ChoirStaff / PianoStaff)
    part-violin-I.ly      % individual part layouts reusing the same variables
```

- Pin `\version` at the top of every file.
- **Absolute / `\fixed` pitch entry** (D4).
- **Bar checks `|` after every measure**, plus `% m. 12` comments every measure (or every 4), so a human editor can navigate.
- Emit the `\key`, `\time`, `\clef`, `\partial`, `\tempo`, `\transposition`, and `\set Staff.instrumentName` commands.
- **Low-confidence events are marked inline** with a comment, e.g. `% ?? conf=0.42 bbox=p2:(812,340)`. **Repaired events are marked** with `% fix: <rule>`. Both are greppable.
- Deterministic output: the same IR always produces byte-identical `.ly`, so diffs of the output stay meaningful.
- Run the generated text through a formatter (python-ly, if suitable) for consistent indentation.

### Stage 8 — Quality Checks (automated, per job)

| # | Check | Method | Output |
|---|---|---|---|
| Q1 | Compiles | Run `lilypond` on the generated files | Hard fail on any error; capture warnings |
| Q2 | Bar checks pass | Parse LilyPond warnings for bar check failures (wording varies by version; see D5) | List of failing measures |
| Q3 | Rhythmic integrity | IR-level duration sum per voice per measure (repeated after generation) | Measures ≠ time signature |
| Q4 | Pitch sanity | Per-instrument range table; flag notes outside the practical range | Suspect notes |
| Q5 | Cross-part alignment | Measure counts equal across all parts; same barline structure | Mismatch report |
| Q6 | Visual round-trip | Render the generated `.ly` to PNG, **run Audiveris on the rendering**, import it to IR, and diff per measure against the job IR. Engraved LilyPond output is near-ideal input, so a disagreement usually points to a real source error rather than an engine error | Per-measure diff score |
| Q7 | Engine agreement (optional) | Run a second engine (oemer, an opt-in extra under D12, or a transformer OMR model whose license meets D12) and compare IRs measure by measure | Disagreement heat map |
| Q8 | Audio spot-check | Generate MIDI via `\midi {}`; playback in the UI | For human review (not automated) |

*M8 finding: Q6 measured and not shipped.* On the scan corpus (1,017 measures, 157 wrong against the ground truth), re-reading the engraved output with Audiveris flagged the wrong measures less well than the review list already does. The review list flags measures with low calibrated confidence, a failing rhythm or range check, or a repair.

| Flags | Recall | Precision | Measures flagged |
|---|---|---|---|
| Review list | 80.9% | 39.7% | 31.5% |
| Q6 alone | 72.6% | 20.8% | 53.9% |
| Review list or Q6 | 91.1% | 21.2% | 66.2% |
| Review list and Q6 | 62.4% | 50.3% | 19.2% |

Audiveris misreads even clean engravings of our output often enough that Q6 alone is noisy, and it costs a second engine run per job. The M8 exit criterion (at least 80% of wrong measures flagged on scans) is met by the review list. Ranked by confidence, the top 10% of measures hold 62% of the wrong ones. On photos the review list flags 74%.

- **Job report:** each job produces a report with an overall score, the Audiveris version and options used, failed sheets, applied repairs, and a **ranked list of measures to review**.
- **UI coloring:** flagged and repaired measures are colored in both the source image and the rendered output.

### Stage 9 — Custom Recognition Replacements (conditional)
Build nothing in this stage by default. Enter it only when the eval harness shows a **persistent, category-specific weakness** in Audiveris that Stages 1, 4 and 5 cannot fix. Escalate in this order and stop at the first step that works:

1. **Tune:** adjust Audiveris `-constant` values per input class and validate on the corpus.
2. **Retrain Audiveris's glyph classifier:** use its supported training workflow and custom shape sets (5.11+), fed by the synthetic data engine (§9.3). This improves the engine without forking it.
3. **Upstream:** file an issue or PR with a minimal repro from the corpus.
4. **Replace:** build a custom stage (for example, a DeepScoresV2-trained detector for a symbol class, or an end-to-end transformer model for a notation type). Merge its output into the IR at Stage 5 with its own provenance. It must beat Audiveris on that category in the eval harness and meet the CPU performance target (D1).

## 7. Web App Functional Requirements

1. Upload a PDF or images (multi-page, drag-and-drop, phone-browser camera upload).
2. Job progress by stage, including Audiveris progress; view any stage's debug overlay.
3. Three-pane review: source image, rendered output, and `.ly` editor. Click-to-sync measures across panes.
4. Recompile after edits and re-run Q1–Q6 on the edited source.
5. Show the repair log per measure (what Stage 4/5 changed and why), with one-click revert.
6. Download:
   - `.ly` project as a zip
   - PDF
   - MIDI
   - MusicXML
   - The raw Audiveris `.omr` (so a power user can open it in the Audiveris desktop GUI)
7. **Score combiner:**
   - Select parts or excerpts from multiple completed jobs.
   - Generate a new `main.ly` that `\include`s the parts, with transposition and key normalization where requested.

*Done (M9):* `lilyscan/combine.py` builds the combined score from each part's own LilyPond source, so review-UI edits carry over. The generator plans the new score from the parts' IR (variable names, staff layout), and each part's file is copied from its job with its variables renamed. Transposition is LilyPond's `\transpose` in the layout, so copied sources stay as written; the combined IR is transposed for the checks (`lilyscan/ir/transpose.py`, spelled by interval, with key signatures and chord symbols). A transposing part can be taken at concert pitch (D3), which drops its `\transposition`. Parts must have the same measures. API: `GET /api/jobs/{id}/parts`, `POST /api/scores`; UI: a "Combine parts" panel. Key normalization to a target key is left to per-part transposition.
*Done since:* **piano accompaniment** (`lilyscan/arrange.py`). Parts chosen for the piano (`POST /api/scores` field `piano`; UI: "in the piano accompaniment") are reduced onto a grand staff below the others: each goes to the staff for the register it sounds in (median pitch from middle C up on the upper staff), a double bass an octave below written; on each staff, parts with the same rhythm in a measure merge into chords, others keep their own voices (up to four), and a part resting through a measure drops out there. Clefs are treble over bass; key and time signatures and barlines follow the first part. The piano part is generated from the IR, so it has no review edits of its own to carry over.
8. Job history and re-run with different settings (e.g. different Audiveris options, force a key mode, skip the vector oracle).

## 8. Risks (wrap-and-extend specific)

| Risk | Mitigation |
|---|---|
| `.omr` format changes between Audiveris releases | Pin the version (D7); version the reader; golden `.omr` fixtures; fall back to MusicXML-only import |
| Upstream depends on one lead maintainer | Pinned releases keep working indefinitely. If upstream stalls, the pinned version remains usable, and Stage 9 can take over one category at a time. A hard fork is only a last resort |
| Audiveris is weak on phone photos | Stage 1 photo front-end; quality gate |
| Audiveris is weak on dense polyphony and hidden-staff scores | Stage 5 repair and part mapping; Stage 9 if metrics demand it |
| JVM memory spikes on large books | Heap cap rewritten in the launcher config (§5), one book per container, page-level splitting for very large uploads |
| Silent loss of text recognition (missing or LSTM-only Tesseract data) | Checksum-pinned full `tessdata` models in the image; the runner, the engine job, and the smoke test all fail loudly on either OCR failure message |
| Audiveris is not a published library | Stay on the CLI and saved files (option A). Any JVM-side tool (option D) builds against the jars of the pinned release package only |
| Coordinate drift between original upload, Stage 1 output, and Audiveris geometry | Store every Stage 1 transform; test the inverse mapping on the corpus |

## 9. Data, Testing, and Evaluation

### 9.1 Test corpus (build first, Milestone 1)
- 20–40 pieces spanning:
  - Solo line
  - Piano
  - SATB with lyrics
  - Lead sheet with chord symbols
  - String quartet
  - Orchestral score with hidden staves
- Each piece exists as a born-digital PDF, a scan, and a phone photo, **with ground truth** in MusicXML or LilyPond.
- **Seed corpus (M1, generated).** music21's bundled corpus was considered and rejected for now. Its licence file says some encodings restrict commercial use and many files state no licence, which needs owner approval under §13. The seed corpus is therefore **generated**. It is license-clean, exact and reproducible, and it is AGPL project content; lyric texts are short public-domain liturgical and folk lines.
  - `eval/corpus/seed.json` lists 30 pieces: 6 solo lines, 6 piano (with occasional second voices), 8 SATB (lyrics in Latin, German, French, English), 4 lead sheets (lyrics and chord symbols), 6 string quartets.
  - Each piece is seeded random-but-valid music written as MusicXML ground truth (music21). It is engraved with `musicxml2ly` and LilyPond 2.26.0 into a born-digital PDF and a 300 DPI PNG, then degraded into a simulated scan and a simulated phone photo (§9.3). Rebuilding from the spec is byte-identical.
  - Build with `lilyscan corpus build`; built corpora are not committed.
  - **Real repertoire is approved (D11)** and will be added alongside the generated pieces: music21's bundled corpus and Mutopia. Real-repertoire items are reported separately from generated ones.
- **Real inputs** (the owner's own scans and phone photos, with hand-checked ground truth) go into the same layout as they become available. They replace synthetic items as the headline numbers. Metrics are always reported separately for synthetic and real inputs.

### 9.2 Metrics
- **Measure-level:** % of measures exactly correct (pitch + duration + voice). This is the headline number.
- **Edit cost:** estimated number of edits to reach ground truth (sequence edit distance on the IR per measure).
- **Symbol-level:** precision and recall per symbol class, computed from the `.omr` import against ground truth.
- **Attribution:** every metric is reported as *raw Audiveris* vs *after Stage 4/5*, so the value each custom stage adds is visible.
- **Calibration:** reliability of confidence values (is `conf=0.4` wrong about 60% of the time?), because the review UI depends on it.
- Track all metrics per milestone and per Audiveris version in `eval/results/`, so regressions are visible.

### 9.3 Synthetic data engine
LilyPond is the ground-truth generator. It serves three purposes: regression tests, calibration, and (only when Stage 9 is entered) classifier retraining.
- Source material:
  - Mutopia Project `.ly` sources (verify each file's license).
  - Programmatically generated random-but-valid music.
- Pipeline:
  1. Render with varied fonts and layouts.
  2. Degrade to simulate scans and photos (blur, noise, JPEG artifacts, perspective warp, page curl, uneven lighting).
  3. Produce perfectly labeled image/IR pairs.

### 9.4 Code quality
- `pytest`, with golden-file tests for `.omr` import, Stage 5 repairs, and Stage 7 generation.
- `ruff`, `mypy --strict` on core packages.
- Each stage can be run standalone from a CLI:
  `lilyscan stage <name> --in artifact --out artifact`

## 10. Milestones (build order)

| M | Deliverable | Exit criteria |
|---|---|---|
| **M0** | Repo skeleton; Docker Compose (API, pipeline worker, Audiveris worker, Redis); LilyPond in the pipeline worker; device abstraction (§5.1); CI | `docker compose up` works; hello-world `.ly` compiles; the Audiveris worker transcribes a sample page to `.omr` + `.mxl`; the worker logs the selected device; the `gpu` profile builds |
| **M1** | Test corpus; eval harness; Audiveris wrapper; minimal MusicXML → IR import | Audiveris runs end-to-end on the whole corpus; **raw Audiveris baseline metrics** recorded per input type. This is the bar every custom stage must beat |
| **M2** | IR + LilyPond generator (Stages 6–7) + Q1–Q5 | Ground-truth MusicXML → IR → `.ly` round-trips with zero compile errors; Audiveris output produces compiling `.ly` for the whole corpus. **First usable product** |
| **M3** | `.omr` reader: bboxes, confidence and geometry in the IR; overlay PNGs; inline `% ??` markers | ≥ 98% of IR events carry a bbox; confidence calibration measured |
| **M4** | Web review UI (three-pane, click-to-sync, recompile, repair log) | A user can correct a flagged measure and re-export in under a minute |
| **M5** | Stage 5 repair and enrichment: constraint repair, key mode, accidental sanity, part mapping, chords, lyrics | Beats raw Audiveris on measure-level accuracy for scans; SATB and lead-sheet items produce correct lyrics and chords |
| **M6** | Stage 1 photo front-end: dewarp, perspective, illumination, quality gate | Photos that pass the gate reach measure accuracy within 10 points of the same piece's scan |
| **M7** | Stage 4 born-digital vector oracle | ≥ 95% measure accuracy on the born-digital corpus with known fonts |
| **M8** | Q6 visual round-trip via Audiveris; optional Q7 second engine | Q6 flags ≥ 80% of the measures that are actually wrong, on the scan corpus |
| **M9** | Score combiner + transposition tools | Combine parts from two jobs into one compiling score |
| **M10** | *Conditional:* Stage 9 escalation for categories that are still weak | Each replacement or retrain beats Audiveris on its category without regressing others |

Build strategy: M2 delivers a working product built on Audiveris. Each later milestone adds a layer whose value is **measured against raw Audiveris** in the eval harness. A layer that doesn't improve the metrics is not shipped.

## 11. Proposed Repo Layout

Option A (D9): Python throughout, with Audiveris as an external process.

```
lilyscan/
  README.md
  LICENSE                 # AGPL-3.0-or-later
  docker-compose.yml
  docs/design.md          # public copy of this document
  .github/workflows/      # CI, image publishing to GHCR, on-demand CUDA build
  scripts/                # smoke tests (engine_smoke.sh)
  services/
    api/Dockerfile
    worker/Dockerfile     # pipeline worker, cpu/cuda variants
    audiveris-worker/Dockerfile   # pinned Audiveris .deb + RQ shim
    lilyscan_app/         # Python service layer: api, jobs (SQLite), dispatch, tasks, worker
    web/                  # frontend (M4)
  lilyscan/               # core Python package (no web dependencies)
    ingest/               # Stage 0
    prepare/              # Stage 1 (photo/scan front-end, quality gate)
    engine/
      audiveris/          # Stage 2 runner (CLI, options) + Stage 3 .omr reader (versioned)
    vector/               # Stage 4 born-digital oracle, font glyph tables
    repair/               # Stage 5 repair + enrichment rules
    ir/                   # Stage 6 models, MusicXML reader, whole-score operations
    lilypond/             # Stage 7 generator (notation.py, generate.py) + compile/diagnostics
    qa/                   # Stage 8 checks
    custom/               # Stage 9 (empty until justified by metrics)
    runtime/              # device.py, config (all thresholds, in staff-space units)
    synth/                # synthetic ground truth: generator, engraver, degradations, corpus builder
    evaluation/           # measure-aligned comparison and the corpus harness
    pipeline.py           # engine output -> IR -> LilyPond project -> QA report (one job)
    cli.py                # lilyscan convert | corpus build | eval run | selftest | device | versions
  eval/
    corpus/seed.json      # seed corpus spec (committed); built corpora (eval/corpus/*/) are ignored
    fixtures/omr/         # golden .omr files per pinned Audiveris version (M3)
    results/<label>/      # summary.md + results.json per recorded run (committed)
    work/                 # engine output cache (ignored)
  tests/
    fixtures/             # hand-written MusicXML covering generator features
    golden/               # expected generator output (LILYSCAN_UPDATE_GOLDEN=1 to refresh)
```

## 12. Dependencies (verify versions and licenses at build time)

| Component | Candidate | Note |
|---|---|---|
| Recognition engine | **Audiveris 5.11.0** (pinned, D7), official Ubuntu 24.04 `.deb` | AGPL-3.0, approved (D2). Actively developed. Bundles its own Java 25 runtime (GPLv2 + Classpath Exception), Tesseract 5.5.2, Leptonica, and PDFBox. No separate JDK image is needed |
| OCR language data | `eng`, `lat`, `deu`, `fra` from `tesseract-ocr/tessdata` at a pinned commit (full models with the legacy engine), checksum-verified; other languages fetched on demand (D10) | Apache-2.0. Required, or Audiveris skips text recognition silently. The `tessdata_fast` models and Ubuntu's `tesseract-ocr-*` packages do **not** work (§5) |
| API / queue | FastAPI, uvicorn, RQ, **Valkey 8** as the Redis-protocol server | MIT / BSD / BSD / BSD-3. Not Redis 7.4+, which is RSALv2/SSPL (source-available, not approved under D2). Valkey is the Linux Foundation's BSD-3 fork and a drop-in replacement for RQ and redis-py |
| Job store | SQLite (stdlib), Postgres later | Public domain / PostgreSQL License |
| Tooling | uv, ruff, mypy, pytest; GitHub Actions; GHCR | Dev and CI only |
| Engraving | LilyPond 2.26.0 (D5) | GPL. Includes `convert-ly` (the upgrade path) and `musicxml2ly` (a second opinion in tests only) |
| LilyPond tooling | python-ly | GPL-3.0; slow but live release cadence (0.9.10, Apr 2026). Formats generated output; tokenizes the `.ly` editor pane for highlighting and cursor → measure lookup (click-to-sync) |
| Browser rendering (optional) | Verovio | LGPL-3.0; active. Renders MusicXML to SVG in the browser (WebAssembly) to preview raw Audiveris output or show an instant preview while LilyPond compiles. Cannot read `.ly` |
| Rejected | Abjad | MIT and active, but each release follows LilyPond closely (currently ≥ 2.25.26), and its object model would duplicate the IR. The generator is a small in-house serializer instead |
| PDF | PyMuPDF | AGPL/commercial dual license; acceptable for private use. Alternative: pypdfium2 |
| Font programs | fontTools | MIT; reads glyph names from music fonts embedded in PDFs (Stage 4) |
| CV | OpenCV, scikit-image | Stage 1 front-end, Stage 4 matching |
| ML (optional) | ONNX Runtime; PyTorch only for training or the CUDA variant | Needed for the dewarping model and any Stage 9 work |
| Music model | music21 | BSD-3; very active. MusicXML IO and analysis helpers. Don't use its LilyPond export (old and weak) |
| Second engine (optional) | oemer | Q7 only, opt-in extra (D12): MIT code; its first model's weights are trained on non-commercial data (CVC-MUSCIMA, CC BY-NC-SA 4.0) |
| Datasets | Mutopia sources; DeepScoresV2 (CC BY 4.0) and OLiMPiC/GrandStaff (licenses not yet checked) only if Stage 9 is entered | Must meet D12 |

## 13. Contributor Guidelines

- Commit messages are conventional-commit style and describe the change only.
- Work milestone by milestone. At the end of each milestone, run the eval harness and record the results before starting the next.
- **Do not modify, patch, or vendor Audiveris source** (D6). Use only its CLI, options, and exported and saved files. If a problem can only be fixed inside Audiveris, write a minimal repro and get maintainer approval before any workaround.
- Pin the Audiveris version. An upgrade is its own change, with a full eval run attached (D7).
- Before adding any third-party model, dataset, or library, **report its license**.
  - It must be AGPL-compatible (D2, D12): MIT, BSD, Apache, LGPL, GPL or AGPL for code and weights; CC BY, CC0 or public domain for data and weights.
  - Non-commercial, research-only, no-derivatives, custom or unstated terms are not used. This applies especially to datasets and pretrained weights. The only exception is the opt-in oemer extra (D12); any other exception needs the maintainer's approval first.
- Never add a hard GPU dependency. Every model path must run on CPU (§5.1).
- Prefer root-cause fixes over threshold tweaking. When a metric regresses, identify the failing stage from the per-stage artifacts (including the raw Audiveris output) before changing code.
- Keep every threshold in staff-space units and in a single config module. No magic pixel numbers.
- The host is Ubuntu 24.04. Install everything via apt, pip/uv, or Docker images, never via snap.
- Ask before making architectural changes to the IR schema once M2 is complete; the schema is the contract between all stages.
- The repository is public (D8). Never commit secrets, tokens, personal paths, or private scans. Real-user corpus material stays out of git unless its license allows publication. Update this document when a decision changes.

## 14. Status Log

| Date | Milestone | State |
|---|---|---|
| 2026-09-26 | M0 | Implemented locally and renamed to Lilyscan: package `lilyscan`, service package `lilyscan_app`, CLI `lilyscan`, images `lilyscan-*`, env vars `LILYSCAN_*`. Redis replaced by Valkey 8.<br>• Python package with config, device abstraction, LilyPond runner, and Audiveris runner.<br>• FastAPI job API, SQLite job store, RQ dispatch, and worker entry points.<br>• Dockerfiles (api, worker cpu/cuda, audiveris) with OCI labels; Compose file pointing at GHCR images; CI (tests, forward-compatible LilyPond check, image build, smoke test, GHCR publish on `main`); separate on-demand/weekly CUDA image workflow.<br>• Audiveris heap cap via an entrypoint that rewrites the launcher config; checksum-pinned full `tessdata` English model; OCR-failure detection in the runner, the engine job, and the smoke test.<br>• AGPL-3.0 `LICENSE`; `docs/design.md`.<br>• 27 tests pass, including real-Audiveris integration tests with working OCR; ruff and `mypy --strict` pass.<br>• Verified on the dev machine: LilyPond 2.26.0 compile; Audiveris 5.11.0 transcription (PNG and PDF → `.omr` + `.mxl`, OCR on); entrypoint heap rewrite and validation.<br>• CI green on GitHub: all three images build, the engine smoke test passes in Docker (`.omr` + `.mxl`, OCR languages: eng), and images are published to `ghcr.io/vilpter/lilyscan-{api,worker,audiveris}`. The first CI runs surfaced and fixed the two container-install issues in §5.<br>**Done since:** CUDA image built and published (#3); GHCR packages confirmed publicly pullable (#4); D9 decided: Python (#5).<br>**Open:** `docker compose up` on the homelab (#2). |
| 2026-09-26 | M1 | **Done.** 30-piece generated seed corpus (§9.1) plus a 9-piece real-repertoire corpus (D11); measure-aligned comparison and a caching harness. Raw Audiveris 5.11.0 baseline recorded in `eval/results/m1-baseline` and `eval/results/m1-repertoire` (dev laptop, 2 parallel runs).<br>• Seed, exact measures: PNG 59.8%, PDF 49.9%, scan 44.7%, photo 7.9% (40.6% overall); 11/120 engine runs produced nothing (mostly photos).<br>• Repertoire, exact measures: PDF 79.7%, PNG 78.5%, scan 67.4%, photo 9.6% (58.8% overall). Italian lyrics (no Italian OCR model enabled): 18%.<br>• Main Audiveris weaknesses found: octave clefs, parts split by abbreviation, page text read as lyrics, rhythm misreads (Stage 5). |
| 2026-09-26 | M2 | **Done.** IR, LilyPond generator (golden-file tested), Q1-Q5, job pipeline, `lilyscan convert`. Exit criteria met: all 30 ground-truth pieces round-trip with zero compile errors, and 100% of engine outputs on both corpora compile. The measure grid got there from 67%: each source measure has one length across staves, with a `?? rhythm` marker where it disagrees with the time signature. Bar checks (Q2) pass on 76% of seed and 61% of repertoire outputs; the rest are type/duration disagreements inside engine output, which Q2 exists to flag. |
| 2026-09-26 | M3 | `.omr` reader, geometry and confidence in the job pipeline, confidence overlays, calibration in the harness.<br>• Box coverage (≥ 98% target): met on clean input (seed PNG 99.0%, repertoire PDF 99.6%, PNG 99.9%). Not met on photos (~83%), where Audiveris's own output is poor; that input is M6's job.<br>• Calibration measured: ECE 0.389 (seed), 0.170 (repertoire). Audiveris grades overstate correctness; Lilyscan's own confidence model is Stage 5 item 9. |
| 2026-09-26 | M4 | Web review UI: a build-free single-page app served by the API (upload including phone camera, job list, three-pane review with click-to-sync, ranked review list, editor with save and recompile, downloads). Backend adds `review.json`, point-and-click SVG, page images from the engine project, and recompiling edited sources (measure map rebuilt from `% m. N` comments). `lilyscan serve` runs everything on one machine (`LILYSCAN_DISPATCH=inline`).<br>• Exit criterion met on the dev laptop with a scanned Bach chorale: select a flagged measure, fix it in the editor, save and recompile (about 10 s), download. Well under a minute.<br>• Not yet: Q3-Q5 are not re-run on edited sources (they describe the IR; LilyPond cannot export MusicXML to rebuild it); MusicXML download is the engine's, not the edited score. |
| 2026-09-27 | M5 | In progress. Stage 5 rules `part-merge`, `octave-clef`, `rhythm` (triplets, dots, flags), `lyric-text` and `lyric-verse` (page text, stray marks, chord names read as lyrics, verse numbering), `lyric-split` (syllables read as one word), and Lilyscan's calibrated confidence. Repairs are logged in the job report, listed in the review UI, and noted in the LilyPond source (`% fix:`). Results in `eval/results/m5-repair` and `eval/results/m5-repertoire`.<br>• Exact measures, seed: 40.6% → 64.7% (scans 44.7% → 72.5%); repertoire: 58.8% → 66.8% (scans 67.4% → 83.1%). First exit criterion (beat raw Audiveris on scans) met.<br>• Confidence: out-of-fold ECE 0.0086 (seed), 0.0388 (repertoire); target < 0.05 met.<br>• Lyrics, seed 43.0% → 64.8% (scans 75.4%); chords 29.5% → 31.2%. Second exit criterion (correct lyrics and chords on SATB and lead sheets) not yet met. Next: the engine's harmony output, lyric OCR misreads, key mode and accidentals. |
| 2026-09-27 | M6 | Stage 1 front-end: page detection and perspective, light flattening, staff-line dewarping, scale, and a quality gate; a job step before the engine and a `--prepare` harness mode. Scans are transcribed prepared and as uploaded, keeping the run expected to be better.<br>• Photos, exact measures: seed 12.4% → 73.5%, repertoire 12.8% → 72.4%. Scans: seed 72.5% → 83.4%, repertoire 83.1% → 83.1%.<br>• Exit criterion (gated photos within 10 points of scans): seed 9.9 points, repertoire 10.8 points. The gate passes every simulated photo; its thresholds need real photos (#11). |
| 2026-09-27 | M7 | Stage 0 renders born-digital PDFs at 400 DPI (PyMuPDF) for the engine. Stage 4 reads glyph names, italic digits and staves from the PDF, and applies its printed tuplet numbers; correcting noteheads from glyphs was tried and dropped.<br>• PDF exact measures: seed 85.3% → 89.0%, repertoire 88.4% → 91.9%. Overall: seed 82.7% → 83.6%, repertoire 81.7% → 82.6%.<br>• Exit criterion (≥ 95% on born-digital PDFs) not met. The remaining errors are rhythm the engine lost entirely, which a vector-native reading of those measures would be needed to fix. |
| 2026-09-27 | M8 | Measured, not built: Q6 (re-reading the engraved output with Audiveris) flags wrong scan measures with 72.6% recall and 20.8% precision, while the review list already reaches 80.9% recall at 39.7% precision (the exit criterion). Q6 would also cost a second engine run per job. Q7 (second engine) not started. |
| 2026-09-27 | M9 | **Done.** Score combiner (parts of finished jobs as one score, edits carried over, per-part transposition, concert pitch for transposing parts) with API and UI. Exit criterion met: parts from two jobs combine into one compiling score (tests; checked in the UI with a Bach chorale's soprano and bass, the bass up an octave). Also: every job's pages now go to the engine as one book, and scanned PDFs get Stage 1 (#41). |
