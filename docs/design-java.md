# Lilyscan Desktop: Java Port — Design and Build Plan

Sheet music (PDF, scans, phone photos) → editable LilyPond, via optical music recognition
built around Audiveris. This document plans the port of Lilyscan from Python to a standalone
**Java 25 desktop application** that keeps the existing browser/server mode, as decided by the
owner (GitHub user `vilpter`).

**Repository:** `github.com/vilpter/lilyscan-desktop` — a full-history copy of
`github.com/vilpter/lilyscan`. AGPL-3.0-or-later (unchanged). The two repos share history so
either can later merge into or replace the other; that is the owner's call. Never push to
`vilpter/lilyscan`.

**Read first:** [`README.md`](../README.md) and [`docs/design.md`](design.md). The Python design
doc holds decisions D1–D12 and the M0–M9 status log, and it remains the authority on *what each
stage does and why*. This document does not restate the pipeline semantics; it plans how to
realize the same behaviour on the JVM. Where the two disagree, this document governs the Java
build and `design.md` governs the algorithm.

**Relationship to `design.md`.** The pipeline (Stages 0–9, Q1–Q9), the metrics, the corpora, the
confidence model, the repair rules, and the combiner are all carried over unchanged in behaviour.
This port changes the *implementation stack and the delivery*, not the recognition design. The
headline accuracy numbers (≈83% exact measures; see `eval/results/`) are the bar the Java build
must meet before it replaces the Python one.

---

## 1. Goal

One Java core that runs two ways:

1. **A native desktop application** — a Swing window (as Audiveris uses), installed from a single
   `jpackage` installer that bundles the Java runtime, Audiveris, LilyPond and the OCR models, so
   a user installs one package and is done. No Docker, no Python, no `PATH` setup.
2. **A headless server** — the same core, serving the existing browser UI and the `/api/jobs` API,
   so one machine can serve a browser or the homelab, as the Docker deployment does today.

Both front ends run on **one core module with no duplicated pipeline logic**. The desktop app and
the server are thin shells over the same `lilyscan-core` library.

**Success criteria** (in addition to the accuracy bar above):
- A Swing three-pane review (source · engraved · LilyPond source) with click-to-sync, recompile,
  and the ranked review list — the desktop equivalent of the M4 web UI.
- `lilyscan serve` brings up the browser UI and API unchanged, on one process, no Redis.
- `jpackage` produces working installers for Windows, Linux and macOS that need nothing else
  installed.
- The eval harness reproduces the Python baselines within measurement noise on the same corpora.

## 2. What changes, and what does not

| Area | Python (today) | Java desktop (this plan) |
|---|---|---|
| Language / build | Python 3.12, uv | **Java 25, Gradle** (as Audiveris) |
| Desktop UI | none (browser only) | **Swing + FlatLaf** native window |
| Browser UI | FastAPI + static SPA | **same static SPA**, served by an embedded HTTP server |
| Audiveris coupling | separate process, CLI + `.omr`/`.mxl` files (option A) | **in-process, jars on the classpath** (old option C/D) |
| `.omr` import | re-parse the JAXB XML in Python (`engine/audiveris/omr.py`) | read via **Audiveris's own model classes** — no XML re-parsing |
| Job queue | RQ on Valkey (Redis), 4 containers | **in-process executor** (desktop) / forked engine workers (server); **no Redis** |
| Job store | SQLite | **SQLite** (unchanged schema), via JDBC |
| IR | pydantic models, `score.json` | **Java records + Jackson**, same `score.json` schema |
| CV (Stage 1) | OpenCV (Python), NumPy | **OpenCV via bytedeco/JavaCPP**, JAMA/EJML for the fits |
| Vector oracle (Stage 4) | PyMuPDF + fontTools | **PDFBox + FontBox** (already inside Audiveris) |
| Music theory (Stage 5) | music21 helpers | **ported helpers** (no music21 equivalent on the JVM) |
| Synthetic corpus | music21 + `musicxml2ly` | Java music generator + our own IR→LilyPond engraver |
| Packaging | Docker images on GHCR | **jpackage installers** + one optional slim server image |
| Confidence model | `confidence.json` (logistic + isotonic) | **same `confidence.json`**, ported scorer (pure arithmetic) |

**Unchanged:** the AGPL licence and the D12 compatibility rule; the pinned versions (Audiveris
5.11.0, LilyPond 2.26.0, tessdata commit); the pipeline stages and their artifacts; the metrics,
corpora and `eval/results/` layout; the public web SPA in `services/web/` (reused byte-for-byte).

## 3. Decisions

### 3.1 Changes to existing decisions (D1–D12)

| # | Was | Now |
|---|---|---|
| **D6** | *Wrap Audiveris; do not fork; talk to it only through the batch CLI, `-constant`, exported MusicXML and `.omr`.* | **Amended.** Audiveris is now linked **in-process** (its jars on our classpath), built from the pinned 5.11.0 source. We still do not *fork or modify* Audiveris source: we depend on it as an unmodified upstream pin via a Gradle composite build, and read its model through its public classes. Per-`-constant` reproducibility is kept. The "no fork" principle stands; "CLI-only" is dropped. See D13, D15. |
| **D9** | *Implementation stack: Python around the Audiveris CLI (option A), with option D in reserve.* | **Overturned.** The stack is **JVM throughout** (option C): Java 25 for the core, the API/server, the IR and the generator, with Audiveris jars on the classpath. This is the decision the owner made when choosing Java over Python, and it is why D6 is amended. See D13. |
| D1 | GPU optional; CPU baseline; single device module. | **Kept.** The device abstraction moves to `Device.java`; our models run through ONNX Runtime (Java) on CPU or CUDA; Audiveris stays CPU-only. |
| D2, D12 | AGPL; everything AGPL-compatible. | **Kept and re-verified** for every Java dependency (D23). |
| D3, D4 | Transposing instruments; absolute `\fixed` pitch. | **Kept** (generator behaviour, unchanged). |
| D5 | LilyPond 2.26.0, pinned, invoked as a subprocess. | **Kept.** LilyPond stays an external program invoked as a subprocess and bundled by jpackage. |
| D7 | Audiveris 5.11.0 pinned; the reader is versioned to it. | **Kept and strengthened:** the pin is now a build-time source dependency, so a version mismatch fails at compile time, not at parse time. |
| D8 | Name, public repo, conventional commits, GitHub milestones. | **Kept**, in `vilpter/lilyscan-desktop`. GHCR image publishing is reduced to one optional server image (D22). |
| D10 | OCR languages `eng+lat+deu+fra`, overridable. | **Kept.** tessdata is bundled by the installer; languages resolve the same way. |
| D11 | Real repertoire in the eval corpus, reported separately. | **Kept**, but the repertoire fetch (music21's bundled corpus) is re-sourced — see D20. |

### 3.2 New decisions (D13–D23)

| # | Topic | Decision |
|---|---|---|
| **D13** | **Stack** | **JVM throughout (option C of `design.md` §5.2).** Java 25, Gradle, mirroring Audiveris's own toolchain. Kotlin is not used; staying in Java keeps the core in the same language as the upstream we now link, which was the tie-breaker (`design.md` §5.2: "choose C only if you would rather maintain Java than Python"). |
| **D14** | **Desktop UI toolkit** | **Swing + FlatLaf.** Audiveris itself is Swing + FlatLaf (`com.formdev:flatlaf`), so we inherit a known-good HiDPI, dark-mode and cross-platform story, and can reuse its look. JavaFX was considered and rejected: it would add a second UI runtime to bundle, duplicate Audiveris's toolkit, and buy little for a document-review UI that is mostly images, an SVG view and a text editor. |
| **D15** | **Audiveris linkage** | **Consume Audiveris's `:app` module as a Gradle composite build** (`includeBuild`) against a git submodule pinned at the 5.11.0 tag. Audiveris publishes no library artifact (only `org.audiveris:proxymusic` is on Maven Central), so we build it from the pinned source. This gives compile-time coupling to its model classes (the real advantage of the old option D), satisfies AGPL source-availability, and keeps the pin reproducible. No Audiveris source is modified; per-job `-constant` overrides are applied through its API. |
| **D16** | **Engine isolation** | **Each transcription runs in a forked child JVM** (`lilyscan --engine-worker`), re-using our own jars. Audiveris was built as a single-user GUI with global/static state; option A got per-job crash/OOM isolation for free by running a fresh process. We keep that: the child JVM transcribes the book **and** imports the `.omr` in-process via Audiveris classes, then writes our IR JSON to the artifact store for the parent to read. The parent never holds Audiveris state. Heap is set on the child's command line (no launcher-config rewrite). `-Djava.awt.headless=true -Dsun.java2d.uiScale=1` are set to avoid the GTK HiDPI probe crash (`design.md` §5). A desktop user may opt into in-parent transcription for small trusted books. |
| **D17** | **Embedded HTTP server** | **Javalin** (on Jetty; Apache-2.0) serves the existing `services/web/` SPA and the `/api/jobs` + `/api/scores` endpoints. Lightweight, embeddable, no annotation framework. Spring Boot was rejected as too heavy for a bundled desktop app; plain Jetty was rejected for more boilerplate. |
| **D18** | **Job store & dispatch** | **SQLite via `org.xerial:sqlite-jdbc`** (same Postgres-compatible schema as today). Dispatch is an **in-process `ExecutorService`** for the desktop and for single-machine `serve`; the heavy engine step forks a worker JVM (D16). **Redis/Valkey and the four-container split are dropped.** For future multi-machine scaling, **JobRunr** (LGPL-3.0, backed by the same SQL DB — no Redis) is the sanctioned option; it is not needed for v1. |
| **D19** | **IR serialization & migration** | **Java records serialized with Jackson**, keeping the on-disk `score.json`, `report.json` and `review.json` schemas **byte-compatible** with the Python version, so existing jobs and golden files load unchanged and the two implementations can be cross-checked during the port. Deterministic LilyPond output (the byte-identical requirement, `design.md` Stage 7) is preserved; existing `tests/golden/` are reused as-is. |
| **D20** | **Music theory helpers** | **Port the specific helpers Lilyscan uses from music21** (pitch spelling, interval arithmetic, key/mode inference by tonic evidence + relative-minor leading-tone frequency, accidental respelling, transposition by interval). There is no mature JVM equivalent to music21, and `proxymusic` is a MusicXML binding only. We do not depend on a music-theory library; the needed surface is small and is covered by unit tests against the Python behaviour. |
| **D21** | **Synthetic corpus** | **Reimplement the generator in Java** (`synth/`): produce random-but-valid music as MusicXML ground truth via `proxymusic`, engrave it through **our own IR→LilyPond generator** (not `musicxml2ly`) + LilyPond, and degrade to simulated scans/photos via bytedeco OpenCV. The "byte-identical rebuild from the spec" guarantee is re-baselined (different RNG and engraving path); corpus **specs** stay committed, built corpora stay git-ignored (D11). |
| **D22** | **Packaging** | **`jpackage` per-OS installers** (msi/exe, dmg/pkg, deb/rpm) that bundle a jlinked Java 25 runtime, our jars, the Audiveris `:app` jars and their JavaCPP natives (Tesseract, Leptonica), the bytedeco OpenCV natives, the pinned tessdata models, and the LilyPond 2.26.0 binary as app content. LilyPond is launched as a subprocess (it is a separate program, not a Java library). One optional **slim server image** remains for the homelab (`serve`, headless); the api/worker/audiveris/redis images are retired. |
| **D23** | **Dependency licences** | Every Java dependency is AGPL-compatible (D2/D12). Verified set in §9. LilyPond (GPL) is bundled as a separate program invoked as a subprocess (aggregation, not linking). `jgrapht` is taken under its LGPL option (it is dual EPL-2.0 / LGPL-2.1). JobRunr is taken under LGPL-3.0. |

## 4. Architecture

```
                         ┌───────────────────────────────┐
   Swing desktop app ───▶│                               │
   (lilyscan-desktop)    │        lilyscan-core          │◀── embedded HTTP server
                         │  (one library, no UI, no web) │    (lilyscan-server, Javalin)
   headless serve ──────▶│                               │        serves services/web/ SPA
                         └──────────────┬────────────────┘        + /api/jobs, /api/scores
                                        │
              per-job fork ─────────────▼───────────────── lilyscan --engine-worker (child JVM)
                                   Audiveris :app on classpath (in-process, pinned 5.11.0)
                                   transcribe → read .omr via Audiveris classes → write IR JSON

   all stages read/write ─▶ artifact store (per-stage JSON, images, .omr, .mxl, .ly) under data/jobs/<id>/
```

- **`lilyscan-core`** — the entire pipeline (Stages 0–9, Q1–Q9), the IR, the LilyPond generator
  and compiler driver, the repair rules, the confidence scorer, the combiner, the config and the
  device abstraction. No Swing, no HTTP, no web dependencies. This is where the single copy of the
  pipeline lives.
- **`lilyscan-engine`** — the Audiveris adapter: the composite-build dependency on Audiveris
  `:app`, the forked-worker launcher (D16), transcription driving, and the in-process `.omr`→IR
  reader. Split from `-core` so only this module carries the Audiveris classpath, and so the
  forked worker can start with a minimal classpath.
- **`lilyscan-desktop`** — the Swing application (D14): the main window, the three-pane review, the
  upload/combine dialogs, the job list. Depends on `-core` and `-engine`.
- **`lilyscan-server`** — the Javalin app (D17): serves `services/web/` and the JSON API. Depends
  on `-core` and `-engine`. `lilyscan serve` and the slim Docker image use this.
- **`lilyscan-cli`** — picocli entry point (`convert`, `serve`, `versions`, `selftest`, `device`,
  `corpus`, `eval`, and the hidden `--engine-worker`). The desktop launcher and the CLI share a
  main class that dispatches by argument, so one installed binary does everything (as `audiveris`
  does).
- **`lilyscan-eval`** — the evaluation harness and the synthetic-corpus generator (D21). Test/dev
  scope; not shipped in the installer.

Gradle multi-project build; Java toolchain 25; `application` plugin for the launcher; the
`org.beryx.jlink`/`jpackage` wiring in a `packaging` subproject that mirrors Audiveris's own
`packaging` module.

## 5. Audiveris in-process (the crux)

The reason Java was chosen. `design.md` §5.2 lays out why the JVM wins exactly one row — reading
Audiveris's model — and D15/D16 claim that benefit without the fragility that killed the Python
`.omr` reader.

- **Linkage (D15).** A git submodule `third_party/audiveris` pinned at tag `5.11.0`; our
  `settings.gradle` does `includeBuild("third_party/audiveris")`; `lilyscan-engine` declares
  `implementation("org.audiveris:audiveris-app")` resolved to the included build. A breaking change
  in Audiveris's model now fails our compile, which is precisely the compile-time safety `design.md`
  §5.2 wanted from option D. The submodule is unmodified upstream (D6).
- **Model access (replaces `engine/audiveris/omr.py`).** We load a saved `.omr` with Audiveris's
  `Book`/`BookManager`, then walk `SheetStub` → `Sheet` → `SystemInfo` → `MeasureStack`/`Slot` and
  the symbol-interpretation graph (`SIGraph`, `Inter` subclasses `HeadInter`, `HeadChordInter`,
  `StemInter`, …, and the `Relation` edges `HeadStemRelation`, `ChordTupletRelation`, …) **as live
  objects**. Grades (`getGrade`, `getContextualGrade`), bounds, staff-relative pitch and the
  containment/relation graph come straight from the classes — no schema re-parsing, no version
  drift in our parser. The join plan (`design.md` Stage 3: MusicXML measure *i* ↔ the *i*-th stack,
  onset→slot, nearest head-chord by x and staff step) ports directly but now reads typed objects.
  The movement-merging and multi-measure-rest handling (`design.md` Stage 3, M9) port as logic.
- **Transcription.** The child worker calls Audiveris's book API to run the batch steps
  (`LOAD … PAGE … EXPORT`), applies per-job `-constant` overrides through its `Constant`/
  `ConstantManager` API, exports `.mxl`, saves `.omr`, and records the command/wall-time/exit in
  `engine/run.json` (unchanged schema). The failed-sheet handling (`design.md` Stage 2) — re-read
  the book from a copy without invalid/failing sheets, record `skipped_sheets`, do **not** use
  `-sheets` (upstream bug 6) — ports as orchestration around the API.
- **Isolation (D16).** The parent forks `lilyscan --engine-worker <job>`; the child does
  transcription + `.omr`→IR and writes `ir/score.json`. A crash or OOM loses only that job, exactly
  as the Python per-process model did. The child sets `-Xmx` from `AUDIVERIS_MAX_HEAP`,
  `-Djava.awt.headless=true` and `-Dsun.java2d.uiScale=1`.
- **OCR / natives.** Audiveris pulls Tesseract 5.5.2 and Leptonica through bytedeco/JavaCPP, so the
  OCR engine comes in on the classpath with no separate Tesseract install. `TESSDATA_PREFIX` points
  at the bundled models (D22); the legacy-engine / full-`tessdata` requirement and the OCR-failure
  detection (`design.md` §5) port into the worker, which fails loudly on the known messages.
- **Golden fixtures.** The `eval/fixtures/omr/` golden `.omr` files are reused to test the new
  in-process reader against the Python reader's IR output, measure by measure, during the port.

## 6. Stage-by-stage port map

Behaviour is defined by `design.md`; this maps each stage to its Java realization and flags the
hard parts. "Direct" = logic port with no ecosystem dependency.

| Stage | Python today | Java realization | Difficulty |
|---|---|---|---|
| 0 Ingest | `ingest/pdf.py`, PyMuPDF render 400 DPI | **PDFBox** `PDFRenderer` at 400 DPI grayscale → multi-page TIFF; page classification (raster ≥80% image vs vector) from PDFBox content/resources | Medium |
| 1 Prepare | `prepare/` OpenCV (Otsu, homography, max-filter illumination, orientation cues, strip dewarp, NLM denoise, unsharp) + NumPy fits | **bytedeco OpenCV** for all CV ops; **JAMA/EJML** for the cubic-x/quadratic-y displacement-field least-squares fit and the orientation scoring; thresholds (staff-space units) copied verbatim into the config module | **Hard** — largest mechanical port; pixel-exact parity needs care |
| 2 Engine | `engine/audiveris/runner.py` CLI | In-process via Audiveris book API in the forked worker (§5) | Medium (new model) |
| 3 Import | `engine/audiveris/omr.py` XML re-parse; `ir/musicxml.py` | In-process model walk (§5); MusicXML→IR via **proxymusic** | Medium — simpler and sturdier than today |
| 4 Vector oracle | `vector/` PyMuPDF glyphs + fontTools names | **PDFBox** content stream for glyph positions + vector paths (staff/stem/beam/barline); **FontBox** + the embedded font program for glyph *names*; italic-digit tuplet detection direct | **Hard** — font-name extraction and path parsing are fiddly |
| 5 Repair | `repair/` pure rules + music21 + `confidence.json` | **Direct** port of every rule (`part-merge`, `octave-clef`, `rhythm`, `lyric-*`, `key-signature`); **ported music-theory helpers** (D20); **ported confidence scorer** reading the same `confidence.json` | Medium — rules direct; the music21 surface is the cost |
| 6 IR | `ir/models.py` pydantic | **Java records + Jackson**, same schema (D19); provenance and before/after values kept | Direct |
| 7 LilyPond | `lilypond/` generator, `single.py`, compiler, diagnostics; `to_musicxml.py` | **Direct** string generation (byte-identical output preserved); LilyPond invoked as subprocess; MusicXML export via proxymusic; python-ly reformat pass dropped | Direct |
| 8 QA | `qa/` Q1–Q9 | **Direct**; Q1/Q2 parse LilyPond stderr (version-sensitive wording — keep the tests, `design.md` D5); Q6/Q7 optional engine runs | Direct |
| 9 Custom | empty until justified | unchanged; ONNX Runtime (Java) available if entered | n/a |
| Combine | `combine.py`, `align.py`, `arrange.py` | **Direct** port (DP alignment, key voting, piano reduction over the IR) | Direct |
| Editor sync | python-ly tokenizer | **RSyntaxTextArea** highlighting (Swing) / existing JS highlighter (browser); measure map from `% m. N` comments (already) | Direct |

**The three genuine porting costs**, called out so they are planned, not discovered:
1. **Stage 1 CV (prepare).** ~several hundred lines of OpenCV/NumPy with pixel-exact thresholds.
   bytedeco OpenCV covers the ops; the risk is parity on the dewarp fit and the orientation cues.
   Mitigation: port against the corpus with per-page A/B checks versus the Python output.
2. **music21 helpers (Stage 5 item 2, accidentals, transpose; D20).** No JVM equivalent. We port
   only the helpers actually called, tested against the Python results on the corpus.
3. **Synthetic corpus (D21).** New generator + engraver + degradation. Re-baselined, not
   byte-reproduced.

Everything else is either a direct logic port or *simpler* on the JVM than it was in Python
(notably Stage 3, which stops re-parsing XML).

## 7. Desktop application (Swing)

- **Main window**: a job list / home (upload, combine parts) and a review workspace, FlatLaf-themed
  to match Audiveris.
- **Upload**: file picker (PDF or images), per-upload OCR languages and a "prepare pages" toggle;
  drag-and-drop. The phone-camera upload is a browser-only affordance and stays in the web UI.
- **Three-pane review** (the M4 UI, native): **Source** (the page image with confidence-coloured
  note boxes and flagged-measure outlines, drawn from the IR boxes), **Engraved** (the LilyPond SVG,
  rendered with an SVG component — `jsvg` or batik, Apache/BSD), **LilyPond** (RSyntaxTextArea with
  a bar-check-aware measure map). Clicking a measure in any pane highlights it in the other two via
  the IR bbox map + LilyPond point-and-click links.
- **Checks & review list**: the Q1–Q5/Q9 badges and the ranked review list as a side panel;
  **Drop these lyrics** (Q9) and **Save & recompile** as actions.
- **Downloads**: `.ly` (single file), `.ly` project (zip), PDF, MIDI, MusicXML, `.omr`.
- **Progress**: stage-by-stage job progress on a background thread; the engine step shows the forked
  worker's progress.

The desktop app calls `lilyscan-core` directly (no HTTP). The web UI reaches the same core through
`lilyscan-server`. There is one pipeline implementation.

## 8. Packaging, server and deployment

- **Installers (D22).** `jpackage` builds msi/exe (Windows), dmg/pkg (macOS), deb/rpm (Linux),
  each bundling: a jlinked Java 25 runtime, all Lilyscan + Audiveris jars, the JavaCPP natives
  (Tesseract, Leptonica) and bytedeco OpenCV natives, the pinned tessdata models, and the LilyPond
  2.26.0 binary under the app image (via `--app-content`/resource dir; note jpackage's signing
  caveat for extra content and verify per OS). The app launcher dispatches desktop vs `serve` vs CLI
  by argument. Audiveris's own `packaging` module is the reference for the layout.
- **Server mode.** `lilyscan serve` starts Javalin on port 8000, serves `services/web/` and the API,
  runs jobs on the in-process executor with forked engine workers. One optional **slim Docker image**
  (`eclipse-temurin:25` + LilyPond + the app, no Redis) covers the homelab; it replaces the four
  images. The GPU path is ONNX Runtime provider selection (D1); Audiveris stays CPU-only.
- **Config.** The `LILYSCAN_*` environment variables (`design.md` README "Configuration") are kept;
  `LILYPOND_BIN`/`AUDIVERIS_*` become "use the bundled copy unless overridden". `LILYSCAN_DISPATCH`
  loses `rq`; the values become `inline` (default) and, later, `jobrunr`.

## 9. Dependencies and licences (all AGPL-compatible, D23)

| Purpose | Library | Licence |
|---|---|---|
| Recognition engine | Audiveris `:app` (pinned 5.11.0, composite build) | AGPL-3.0 |
| MusicXML binding | `org.audiveris:proxymusic:4.0.3` | AGPL-3.0 |
| CV (Stage 1, degradation) | `org.bytedeco:opencv-platform` (JavaCPP) | Apache-2.0 |
| Native OCR (via Audiveris) | `org.bytedeco:tesseract`, `:leptonica` | Apache-2.0 |
| Linear algebra | JAMA (already an Audiveris dep) or EJML | public domain / Apache-2.0 |
| PDF render + vector (Stages 0, 4) | `org.apache.pdfbox:pdfbox` 3.0.6, FontBox | Apache-2.0 |
| JSON (IR, API) | Jackson | Apache-2.0 |
| Desktop look-and-feel | `com.formdev:flatlaf` (as Audiveris) | Apache-2.0 |
| Editor component | RSyntaxTextArea | modified BSD |
| SVG view | jsvg (or Apache Batik) | BSD / Apache-2.0 |
| HTTP server | Javalin + Jetty | Apache-2.0 |
| Job store | `org.xerial:sqlite-jdbc` | Apache-2.0 |
| Distributed jobs (optional, later) | JobRunr | LGPL-3.0 |
| ML (optional, Stage 9 / dewarp model) | ONNX Runtime (Java) | MIT |
| Graph model (via Audiveris) | jgrapht-core | taken under LGPL-2.1 (dual EPL/LGPL) |
| CLI | picocli | Apache-2.0 |
| Engraving (subprocess, bundled) | LilyPond 2.26.0 | GPL (separate program, aggregated) |
| Tests | JUnit 5 | EPL-2.0 |

Every third-party model/dataset/library is still reported and checked before use (`design.md` §13).
The owner's D12 rule (no NC/ND/research-only/unstated terms) carries over unchanged.

## 10. Testing and evaluation parity

- **Golden files reused.** `tests/golden/` (generator output) and `eval/fixtures/omr/` (saved
  `.omr`) are the acceptance tests for the generator and the new reader. The Java generator must
  reproduce the golden `.ly` byte-for-byte (D19); the Java reader must reproduce the Python reader's
  IR on the fixtures (measures, boxes, grades).
- **Corpus parity.** `lilyscan eval run` on the seed and repertoire corpora must land within
  measurement noise of `eval/results/base-1031-seed` / `-rep` (Linux baselines). Results stay in
  `eval/results/<label>/` in the same `summary.md` + `results.json` format, so the port's progress
  is measured against the Python baselines item by item.
- **Confidence.** The ported scorer must reproduce the stored `confidence.json`'s outputs; refitting
  (`eval calibrate`) comes with the eval module and must hit the same ECE targets (< 0.05).
- **JUnit 5** for unit tests; tags for `engine` (needs Audiveris, now in-process), `lilypond`,
  `gpu`, mirroring the pytest marks. CI runs CPU-only; GPU and engine jobs are separate.

## 11. Risks

| Risk | Mitigation |
|---|---|
| Stage 1 CV parity (pixel-exact thresholds) | Port against the corpus with per-page A/B vs Python; keep thresholds in one config module in staff-space units |
| No music21 on the JVM | Port only the helpers used, tested against Python behaviour (D20); the surface is small |
| Audiveris global/static state in-process | Forked worker per job (D16) restores per-process isolation; parent never holds engine state |
| Audiveris model classes are internal (no API stability guarantee) | Pinned source submodule (D15); a breaking change fails our compile, caught at build; golden `.omr` tests |
| Loss of the simple `docker compose` multi-worker scale-out | In-process + forked workers covers one machine; JobRunr-on-SQL (D18) is the sanctioned multi-machine path, no Redis |
| jpackage bundling of an external binary (LilyPond) + natives across 3 OSes | Follow Audiveris's `packaging` module; verify the app image and signing per OS early (JM1) |
| Synthetic corpus not byte-reproducible after the port | Re-baseline (D21); keep specs committed; report synthetic vs real separately (D11) |
| Determinism of generated `.ly` | Preserve exact formatting; reuse `tests/golden/` as the guard (D19) |

## 12. Milestones (build order)

Each milestone keeps the eval harness honest: every stage's value is measured against the Python
baseline, and a Java stage ships only when it matches it.

| JM | Deliverable | Exit criteria |
|---|---|---|
| **JM0** | Gradle multi-project skeleton (`core`, `engine`, `desktop`, `server`, `cli`, `eval`); Java 25 toolchain; Audiveris submodule + composite build resolving; picocli launcher; CI (Gradle build, tests) | `./gradlew build` works; `lilyscan versions`/`selftest` run; a Swing window and `serve` both launch and share the core; Audiveris `:app` is on the classpath |
| **JM1** | Engine adapter: forked worker (D16), in-process transcription + `.omr`→IR reader; MusicXML→IR | Transcribes a sample page in-process to our IR; reproduces the Python reader's IR on `eval/fixtures/omr/`; ≥98% box coverage on clean input |
| **JM2** | IR (records + Jackson, D19) + LilyPond generator + compiler + Q1–Q5 | `tests/golden/` reproduced byte-for-byte; all corpus engine outputs compile; ground-truth MusicXML→IR→`.ly` round-trips with zero compile errors. **First usable core** |
| **JM3** | Stage 5 repair + ported music21 helpers + confidence scorer | Reproduces `eval/results/base-1031-*` repair gains within noise; confidence ECE < 0.05 |
| **JM4** | Swing desktop review UI (three-pane, click-to-sync, recompile, review list, downloads) | Correct a flagged measure and re-export in under a minute, in the native app |
| **JM5** | `lilyscan-server` (Javalin) serving `services/web/` + `/api/jobs` + `/api/scores`; job store | The existing browser UI works unchanged against the Java server on one process |
| **JM6** | Stage 0 + Stage 1 (PDFBox render; bytedeco OpenCV prepare, quality gate) | Photo/scan accuracy within noise of the Python M6/M7 results on the corpus |
| **JM7** | Stage 4 vector oracle (PDFBox + FontBox) | PDF exact-measure accuracy matches the Python M7 results |
| **JM8** | Combiner (`combine`/`align`/`arrange`) + transposition | Parts from two jobs combine into one compiling score; piano reduction matches Python output |
| **JM9** | Eval harness + synthetic corpus (D21) parity | `lilyscan eval run` reproduces the seed and repertoire baselines within noise; `eval calibrate` hits the ECE targets |
| **JM10** | jpackage installers (Win/Linux/macOS) + slim server image | One installer installs a working app with nothing else needed; `serve` image runs on the homelab |

Build strategy mirrors `design.md` §10: JM2 is the first working core; each later milestone is
measured against the Python baseline and shipped only if it matches. Full feature parity is reached
by JM10; nothing in the Python feature set is dropped.

## 13. Repo layout (target)

```
lilyscan-desktop/
  settings.gradle              # includeBuild third_party/audiveris; the subprojects below
  build.gradle                 # Java 25 toolchain, shared config
  gradle/libs.versions.toml    # pinned dependency versions
  third_party/audiveris/       # git submodule, pinned tag 5.11.0 (unmodified)
  lilyscan-core/               # pipeline, IR, generator, repair, confidence, combine, config, device
  lilyscan-engine/             # Audiveris adapter, forked worker, .omr→IR reader
  lilyscan-desktop/            # Swing app (FlatLaf)
  lilyscan-server/             # Javalin app serving services/web/ + API
  lilyscan-cli/                # picocli launcher (desktop | serve | convert | eval | --engine-worker)
  lilyscan-eval/               # eval harness + synthetic corpus generator
  packaging/                   # jpackage / jlink wiring (mirrors Audiveris's packaging module)
  services/web/                # the existing SPA, reused unchanged
  eval/                        # corpus specs (committed), fixtures, committed results
  tests/ (per module)          # JUnit 5; golden files reused from the Python tree
  docs/design.md               # the pipeline design (authority on behaviour)
  docs/design-java.md          # this document (authority on the Java build)
```

The Python tree (`lilyscan/`, `services/lilyscan_app/`, `pyproject.toml`, Dockerfiles,
`docker-compose.yml`) stays in place during the port as the reference implementation and the source
of golden/baseline data, and is removed only once the Java build meets the accuracy bar end to end.

## 14. Status log

| Date | Milestone | State |
|---|---|---|
| 2026-11-01 | — | Design drafted. Decisions: D13 (JVM stack, overturns D9), D14 (Swing+FlatLaf), D15 (Audiveris in-process via composite build, amends D6), D16 (forked engine worker for isolation), D17 (Javalin), D18 (SQLite + in-process dispatch, drops Redis), D19 (Jackson IR, schema-compatible), D20 (ported music21 helpers), D21 (Java synthetic corpus), D22 (jpackage installers), D23 (licences verified). Milestones JM0–JM10 defined. No code yet. |
