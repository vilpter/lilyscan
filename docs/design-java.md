# Lilyscan Desktop: Java Port — Design and Build Plan

Sheet music (PDF, scans, phone photos) → editable LilyPond, via optical music recognition
built around Audiveris. This document plans the port of Lilyscan from Python to a **Java 25**
application whose primary delivery is a **self-hosted web server, installed with Docker**, with an
optional native desktop front end. The move to Java was decided by the owner (GitHub user
`vilpter`); the Docker-first delivery was chosen over per-OS desktop installers to keep installing
the whole stack as simple as `docker compose up`.

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

One Java core that runs two ways. The **primary** product is the server; the desktop app is an
**optional** second front end over the same core.

1. **A self-hosted web server (primary), installed with Docker** — the same core serving the
   existing browser UI and the `/api/jobs` API. Install is `docker compose up`: one image carries
   the JVM, Audiveris (and its native OCR), LilyPond and the OCR models, so there is no Python, no
   Redis, no per-tool `PATH` setup, and nothing to install on the host but Docker. One machine can
   serve a browser or the homelab, as the Docker deployment does today — but now from a single
   image instead of four.
2. **A native desktop application (optional)** — a Swing window (as Audiveris uses) over the same
   `lilyscan-core`, for a user who wants a local app with no browser. It is a secondary deliverable,
   not on the critical path; its per-OS `jpackage` installers are built last and may be deferred or
   dropped without affecting the server product.

Both front ends run on **one core module with no duplicated pipeline logic** — thin shells over the
same `lilyscan-core` library.

**Success criteria** (in addition to the accuracy bar above):
- `docker compose up` brings up the browser UI and the API on one container, with every dependency
  (Audiveris, LilyPond, OCR models) already inside the image — nothing to install on the host but
  Docker. No Redis, no multi-container orchestration.
- `lilyscan serve` runs the same server directly (for development, or a non-Docker host) on one
  process.
- The three-pane review (source · engraved · LilyPond source) with click-to-sync, recompile and the
  ranked review list works in the browser UI (and, optionally, in the Swing desktop app).
- The eval harness reproduces the Python baselines within measurement noise on the same corpora.
- *(Optional, last)* `jpackage` produces a working desktop installer that needs nothing else
  installed.

## 2. What changes, and what does not

| Area | Python (today) | Java (this plan) |
|---|---|---|
| Language / build | Python 3.12, uv | **Java 25, Gradle** (as Audiveris) |
| Primary delivery | 4 Docker images on GHCR (api/worker/audiveris/redis) | **one Docker image**, `docker compose up`, all deps baked in |
| Browser UI | FastAPI + static SPA | **same static SPA**, served by an embedded HTTP server |
| Desktop UI | none (browser only) | **optional** Swing + FlatLaf native window (secondary) |
| Audiveris coupling | separate process, CLI + `.omr`/`.mxl` files (option A) | **in-process, jars on the classpath** (old option C/D) |
| `.omr` import | re-parse the JAXB XML in Python (`engine/audiveris/omr.py`) | read via **Audiveris's own model classes** — no XML re-parsing |
| Job queue | RQ on Valkey (Redis), 4 containers | **in-process executor** (desktop) / forked engine workers (server); **no Redis** |
| Job store | SQLite | **SQLite** (unchanged schema), via JDBC |
| IR | pydantic models, `score.json` | **Java records + Jackson**, same `score.json` schema |
| CV (Stage 1) | OpenCV (Python), NumPy | **OpenCV via bytedeco/JavaCPP**, JAMA/EJML for the fits |
| Vector oracle (Stage 4) | PyMuPDF + fontTools | **PDFBox + FontBox** (already inside Audiveris) |
| Music theory (Stage 5) | music21 helpers | **ported helpers** (no music21 equivalent on the JVM) |
| Synthetic corpus | music21 + `musicxml2ly` | Java music generator + our own IR→LilyPond engraver |
| Packaging | 4 Docker images on GHCR | **one slim Docker image** (primary); optional jpackage desktop installer (last) |
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
| D5 | LilyPond 2.26.0, pinned, invoked as a subprocess. | **Kept.** LilyPond stays an external program invoked as a subprocess, installed into the Docker image (and bundled by the optional jpackage installer). |
| D7 | Audiveris 5.11.0 pinned; the reader is versioned to it. | **Kept and strengthened:** the pin is now a build-time source dependency, so a version mismatch fails at compile time, not at parse time. |
| D8 | Name, public repo, conventional commits, GitHub milestones. | **Kept**, in `vilpter/lilyscan-desktop`. GHCR publishing is reduced to the one server image (D22). |
| D10 | OCR languages `eng+lat+deu+fra`, overridable. | **Kept.** tessdata is baked into the Docker image (and the optional desktop installer); languages resolve the same way. |
| D11 | Real repertoire in the eval corpus, reported separately. | **Kept**, but the repertoire fetch (music21's bundled corpus) is re-sourced — see D20. |

### 3.2 New decisions (D13–D23)

| # | Topic | Decision |
|---|---|---|
| **D13** | **Stack** | **JVM throughout (option C of `design.md` §5.2).** Java 25, Gradle, mirroring Audiveris's own toolchain. Kotlin is not used; staying in Java keeps the core in the same language as the upstream we now link, which was the tie-breaker (`design.md` §5.2: "choose C only if you would rather maintain Java than Python"). |
| **D14** | **Desktop UI toolkit (optional front end)** | The desktop app is an **optional** second front end; the primary product is the web server (D22). When built, it is **Swing + FlatLaf.** Audiveris itself is Swing + FlatLaf (`com.formdev:flatlaf`), so we inherit a known-good HiDPI, dark-mode and cross-platform story, and can reuse its look. JavaFX was considered and rejected: it would add a second UI runtime to bundle, duplicate Audiveris's toolkit, and buy little for a document-review UI that is mostly images, an SVG view and a text editor. If the desktop app is dropped, the `lilyscan-desktop` module and milestones JM9–JM10 fall away with no effect on the server. |
| **D15** | **Audiveris linkage** | **Consume Audiveris's `:app` module as a Gradle composite build** (`includeBuild`) against a git submodule pinned at the 5.11.0 tag. Audiveris publishes no library artifact (only `org.audiveris:proxymusic` is on Maven Central), so we build it from the pinned source. This gives compile-time coupling to its model classes (the real advantage of the old option D), satisfies AGPL source-availability, and keeps the pin reproducible. No Audiveris source is modified; per-job `-constant` overrides are applied through its API. |
| **D16** | **Engine isolation** | **Each transcription runs in a forked child JVM** (`lilyscan --engine-worker`), re-using our own jars. Audiveris was built as a single-user GUI with global/static state; option A got per-job crash/OOM isolation for free by running a fresh process. We keep that: the child JVM transcribes the book **and** imports the `.omr` in-process via Audiveris classes, then writes our IR JSON to the artifact store for the parent to read. The parent never holds Audiveris state. Heap is set on the child's command line (no launcher-config rewrite). `-Djava.awt.headless=true -Dsun.java2d.uiScale=1` are set to avoid the GTK HiDPI probe crash (`design.md` §5). A desktop user may opt into in-parent transcription for small trusted books. |
| **D17** | **Embedded HTTP server** | **Javalin** (on Jetty; Apache-2.0) serves the existing `services/web/` SPA and the `/api/jobs` + `/api/scores` endpoints. Lightweight, embeddable, no annotation framework. Spring Boot was rejected as too heavy for a bundled desktop app; plain Jetty was rejected for more boilerplate. |
| **D18** | **Job store & dispatch** | **SQLite via `org.xerial:sqlite-jdbc`** (same Postgres-compatible schema as today). Dispatch is an **in-process `ExecutorService`** for the desktop and for single-machine `serve`; the heavy engine step forks a worker JVM (D16). **Redis/Valkey and the four-container split are dropped.** For future multi-machine scaling, **JobRunr** (LGPL-3.0, backed by the same SQL DB — no Redis) is the sanctioned option; it is not needed for v1. |
| **D19** | **IR serialization & migration** | **Java classes serialized with Jackson** — mutable, with public fields and value equality like pydantic models, because the repairs edit the IR in place (records would fight that port); deep copies keep shared references as Python's `deepcopy` does — keeping the on-disk `score.json`, `report.json` and `review.json` schemas **byte-compatible** with the Python version, so existing jobs and golden files load unchanged and the two implementations can be cross-checked during the port. Deterministic LilyPond output (the byte-identical requirement, `design.md` Stage 7) is preserved; existing `tests/golden/` are reused as-is. |
| **D20** | **Music theory helpers** | **Port the specific helpers Lilyscan uses from music21** (pitch spelling, interval arithmetic, key/mode inference by tonic evidence + relative-minor leading-tone frequency, accidental respelling, transposition by interval). There is no mature JVM equivalent to music21, and `proxymusic` is a MusicXML binding only. We do not depend on a music-theory library; the needed surface is small and is covered by unit tests against the Python behaviour. |
| **D21** | **Synthetic corpus** | **Reimplement the generator in Java** (`synth/`): produce random-but-valid music as MusicXML ground truth via `proxymusic`, engrave it through **our own IR→LilyPond generator** (not `musicxml2ly`) + LilyPond, and degrade to simulated scans/photos via bytedeco OpenCV. The "byte-identical rebuild from the spec" guarantee is re-baselined (different RNG and engraving path); corpus **specs** stay committed, built corpora stay git-ignored (D11). |
| **D22** | **Packaging (Docker-first)** | **The primary, and simplest, install is one Docker image**, run with `docker compose up`. The image is a single server container built `FROM eclipse-temurin:25-jre`, carrying: our fat application jar (which includes the Audiveris `:app` classes and the bytedeco JavaCPP natives for Tesseract, Leptonica and OpenCV, pulled at build time — no apt packages for OCR), the pinned tessdata models, and the LilyPond 2.26.0 binary (installed into the image; launched as a subprocess). The engine runs in a forked worker JVM *inside the same container* (D16), so there is **one image and one service** — no api/worker/audiveris/redis split, no Redis. `docker compose up` needs nothing on the host but Docker. The image is published to GHCR (D8) and runs `lilyscan serve`; CI builds the fat jar (it needs the Audiveris submodule, a Java 25 JVM and a UTF-8 locale) and the image copies it in, with the tessdata models pinned to the Python image's commit and checksum-verified. Jobs persist to a Docker volume (SQLite). **The jpackage desktop installer is optional and built last** (JM10): per-OS installers (msi/exe, dmg/pkg, deb/rpm) that jlink a Java 25 runtime and bundle the same jars, natives, tessdata and LilyPond as app content; it may be deferred or dropped without affecting the server. |
| **D23** | **Dependency licences** | Every Java dependency is AGPL-compatible (D2/D12). Verified set in §9. LilyPond (GPL) is bundled as a separate program invoked as a subprocess (aggregation, not linking). `jgrapht` is taken under its LGPL option (it is dual EPL-2.0 / LGPL-2.1). JobRunr is taken under LGPL-3.0. |

## 4. Architecture

```
   Docker image: lilyscan serve ──┐     ┌───────────────────────────────┐
   (PRIMARY: docker compose up)   ├────▶│                               │
   embedded HTTP server (Javalin) ┘     │        lilyscan-core          │
     serves services/web/ SPA           │  (one library, no UI, no web) │
     + /api/jobs, /api/scores           │                               │
                                        └──────────────┬────────────────┘
   optional Swing desktop app ──────────────▲          │
   (lilyscan-desktop, secondary)                        │
                                        per-job fork ────▼──── lilyscan --engine-worker (child JVM)
                                   Audiveris :app on classpath (in-process, pinned 5.11.0),
                                   in the SAME container: transcribe → read .omr via
                                   Audiveris classes → write IR JSON

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
- **`lilyscan-server`** (the primary front end) — the Javalin app (D17): serves `services/web/` and
  the JSON API. Depends on `-core` and `-engine`. `lilyscan serve` and the Docker image use this.
- **`lilyscan-desktop`** (optional, secondary) — the Swing application (D14): the main window, the
  three-pane review, the upload/combine dialogs, the job list. Depends on `-core` and `-engine`.
  Can be dropped without affecting the server.
- **`lilyscan-engine`** — the Audiveris adapter: the composite-build dependency on Audiveris
  `:app`, the forked-worker launcher (D16), transcription driving, and the in-process `.omr`→IR
  reader. Split from `-core` so only this module carries the Audiveris classpath, and so the
  forked worker can start with a minimal classpath.
- **`lilyscan-cli`** — picocli entry point (`serve`, `convert`, `versions`, `selftest`, `device`,
  `corpus`, `eval`, the optional `desktop`, and the hidden `--engine-worker`). One main class
  dispatches by argument, so one binary does everything (as `audiveris` does); `serve` is the
  Docker image's entry point.
- **`lilyscan-eval`** — the evaluation harness and the synthetic-corpus generator (D21). Test/dev
  scope; not shipped in the image or the installer.

Gradle multi-project build; Java toolchain 25; `application` + Shadow (fat-jar) plugins for the
server launcher and the Docker image; the optional `org.beryx.jlink`/`jpackage` wiring in a
`packaging` subproject (mirroring Audiveris's own `packaging` module) builds the desktop installer
only when requested.

## 5. Audiveris in-process (the crux)

The reason Java was chosen. `design.md` §5.2 lays out why the JVM wins exactly one row — reading
Audiveris's model — and D15/D16 claim that benefit without the fragility that killed the Python
`.omr` reader.

- **Linkage (D15).** A git submodule `third_party/audiveris` pinned at tag `5.11.0`; our
  `settings.gradle` does `includeBuild("third_party/audiveris")` with an explicit dependency
  substitution (`org.audiveris:audiveris-app` → Audiveris's `:app` project), so we need not track
  Audiveris's own coordinates. A breaking change in Audiveris's model now fails our compile, which
  is precisely the compile-time safety `design.md` §5.2 wanted from option D. The submodule is
  unmodified upstream (D6). *Built (JM1).* Making this work required, all now encoded in the build:
  our Gradle at Audiveris's version (9.4.1) so one Gradle builds both; a Java 25 JVM launching
  Gradle (Audiveris has no toolchain of its own); Audiveris's JBoss repository for its non-free JAI
  dependency (scoped to `javax.media`); the few Audiveris API types we touch (JGraphT, JAXB
  annotations) on our compile classpath, since `:app` declares its dependencies `implementation`;
  and a UTF-8 locale, without which Shadow cannot unzip a dependency with non-ASCII class names.
- **Model access (replaces `engine/audiveris/omr.py`).** *Built (JM1):* `OmrReader` loads a saved
  `.omr` with `Book.loadBook` and `SheetStub.getSheet`, and walks Audiveris's typed model as live
  objects: `SystemInfo` → `MeasureStack`/`Slot`, `Part` → `Staff` → staff lines, and the
  interpretation graph (`SIGraph` vertices and its containment `Relation` edges). Values are read as
  the project saved them through a small JAXB-metadata view (`JaxbView`: the bound attribute names,
  type adapters, enum values and getter-bound attributes), because some Audiveris getters do more
  than return stored data — `Inter.getBounds()` falls back to, and caches, glyph bounds when none
  were saved, which would silently change which events get boxes and so the calibrated confidence
  inputs. The book model and the attaching logic (`Geometry`: the join plan — MusicXML measure *i*
  ↔ the *i*-th stack, onset→slot, nearest head by x and staff step — plus movements and
  multi-measure rests) live in `lilyscan-core` with no Audiveris dependency.
  **One documented difference:** Audiveris re-derives the bounds of line-based symbols (the
  horizontal and vertical line families — stems, beams, barlines, connectors — ledgers, music words,
  octave shifts) from their median line when it reloads a sheet, moving their edges by up to a
  pixel from the saved rectangle. No stage uses those boxes (geometry is attached from heads, rests
  and chords; the syllable repair uses lyric items), and the parity test checks that every kind a
  stage does use is exact.
- **Transcription.** *Built (JM1):* rather than driving Audiveris's step API directly, the worker
  runs Audiveris's own batch CLI (`org.audiveris.omr.Main`) in-process, with exactly the arguments
  the Python runner gave the Audiveris launcher (`-batch -transcribe -export -save -output … 
  -constant k=v … -- inputs`). Every option, constant, output file and log line is therefore the
  one the Python pipeline was tuned against, and the Python log analysis (OCR problems, step errors,
  invalid and crashed sheets) ports unchanged. `AUDIVERIS_BIN` can still name an external launcher.
  The job step around it — the failed-sheet retry without those pages (not `-sheets`, upstream bug
  6), the choice between the prepared and the uploaded reading of a scan, and `engine/run.json` —
  needs the job store, Stage 1's pages and the confidence model, so it is ported with the job steps
  (JM3).
- **Isolation (D16).** *Built (JM1):* each run forks `EngineWorker` — a JVM on Lilyscan's own
  classpath (the fat jar in the image), `-Xmx` from `AUDIVERIS_MAX_HEAP` (default `3G`),
  `-Djava.awt.headless=true -Dsun.java2d.uiScale=1` — for transcription (`audiveris <args>`) and
  for reading a saved project (`read-omr`, which writes the book model as JSON for the parent). A
  crash or out-of-memory error loses only that run, exactly as the Python per-process model did, and
  the parent never loads Audiveris's model or global state.
- **OCR / natives.** Audiveris pulls Tesseract 5.5.2 and Leptonica through bytedeco/JavaCPP, so the
  OCR engine comes in on the classpath with no separate Tesseract install (verified from the fat
  jar alone). `TESSDATA_PREFIX` points at the models, pinned to the Python image's tessdata commit
  and checksum-verified (D10, D22); the OCR-failure detection fails loudly on the known messages.
- **Golden fixtures.** The `eval/fixtures/omr/` projects are the parity tests for the reader (§10).

## 6. Stage-by-stage port map

Behaviour is defined by `design.md`; this maps each stage to its Java realization and flags the
hard parts. "Direct" = logic port with no ecosystem dependency.

| Stage | Python today | Java realization | Difficulty |
|---|---|---|---|
| 0 Ingest | `ingest/pdf.py`, PyMuPDF render 400 DPI | **PDFBox** `PDFRenderer` at 400 DPI grayscale → multi-page TIFF; page classification (raster ≥80% image vs vector) from PDFBox content/resources | Medium |
| 1 Prepare | `prepare/` OpenCV (Otsu, homography, max-filter illumination, orientation cues, strip dewarp, NLM denoise, unsharp) + NumPy fits | **bytedeco OpenCV** for all CV ops; **JAMA/EJML** for the cubic-x/quadratic-y displacement-field least-squares fit and the orientation scoring; thresholds (staff-space units) copied verbatim into the config module | **Hard** — largest mechanical port; pixel-exact parity needs care |
| 2 Engine | `engine/audiveris/runner.py` CLI | Audiveris's own batch CLI, in-process in the forked worker (§5) | **Done (JM1)**; the job step around it with JM3 |
| 3 Import | `engine/audiveris/omr.py` XML re-parse; `ir/musicxml.py` | In-process model walk (§5); MusicXML→IR as a JDK DOM port of the Python ElementTree reader (it never used music21, so neither does the port; proxymusic stays available for the MusicXML writer) | **Done (JM1)** — byte-identical IR |
| 4 Vector oracle | `vector/` PyMuPDF glyphs + fontTools names | **PDFBox** content stream for glyph positions + vector paths (staff/stem/beam/barline); **FontBox** + the embedded font program for glyph *names*; italic-digit tuplet detection direct | **Hard** — font-name extraction and path parsing are fiddly |
| 5 Repair | `repair/` pure rules + music21 + `confidence.json` | **Direct** port of every rule (`part-merge`, `octave-clef`, `rhythm`, `lyric-*`, `key-signature`); **ported music-theory helpers** (D20); **ported confidence scorer** reading the same `confidence.json` | Medium — rules direct; the music21 surface is the cost |
| 6 IR | `ir/models.py` pydantic | Mutable classes + Jackson, same schema (D19); provenance and before/after values kept | **Done (JM1)** — `score.json` byte-compatible |
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

## 7. Server, Docker and deployment (primary delivery)

The whole point of the Docker-first delivery is that **installing the full stack is one command**.
Everything a job needs — the JVM, Audiveris and its native OCR, LilyPond, the OCR models — lives in
one image, so the host needs nothing but Docker.

- **The image.** A single `Dockerfile`, `FROM eclipse-temurin:25-jre`, that:
  1. installs LilyPond 2.26.0 (the pinned tarball from lilypond.org, into `/opt`, symlinked onto the
     `PATH` — not the distro package, which is too old; same as `design.md` README);
  2. copies the pinned tessdata models and sets `TESSDATA_PREFIX`;
  3. copies our Shadow **fat jar**, which already contains the Audiveris `:app` classes and the
     bytedeco JavaCPP natives for Tesseract, Leptonica and OpenCV (resolved at Gradle build time, so
     there are **no apt packages for OCR or OpenCV** and nothing to download at first run);
  4. `ENTRYPOINT ["java","-jar","/app/lilyscan.jar","serve"]`.
  There is **one image and one service**. The engine runs in a forked worker JVM *inside the same
  container* (D16), so the api/worker/audiveris/redis split and Redis are gone.
- **`docker-compose.yml`.** One service, one named volume for `data/` (jobs persist across restarts),
  port 8000 published. The whole user story is:
  ```
  docker compose up -d      # pulls the image, starts the server
  # open http://localhost:8000/
  ```
  No `.env` to fill in to get started; `LILYSCAN_*` variables (below) stay optional overrides.
- **Build vs pull.** CI publishes the image to `ghcr.io/vilpter/lilyscan-desktop` (D8); the compose
  file points at it, so `docker compose up` pulls rather than builds. `docker compose up --build`
  builds locally. The CUDA variant is a build arg / second tag, enabled by a compose profile; the
  default image is CPU-only (D1), and Audiveris is CPU-only regardless.
- **Non-Docker / development.** `lilyscan serve` runs the same server directly on a host that has
  the JVM (and LilyPond on the `PATH`), on one process with the in-process executor and forked engine
  workers — the dev loop, and a fallback for hosts without Docker.
- **GPU & config.** The GPU path is ONNX Runtime provider selection (D1). The `LILYSCAN_*`
  environment variables (`design.md` README "Configuration") are kept; `LILYPOND_BIN`/`AUDIVERIS_*`
  default to the copies inside the image unless overridden. `LILYSCAN_DISPATCH` loses `rq`; the
  values become `inline` (default) and, later, `jobrunr`.

## 8. Desktop application (optional, Swing)

A second front end for users who want a local app and no browser. It is **optional** (D14): it adds
no pipeline code, shares `lilyscan-core`, and can be deferred or dropped (with milestone JM10 and the
`lilyscan-desktop` module) without touching the server product.

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
- **Installer (optional, last).** When built, `jpackage` produces msi/exe (Windows), dmg/pkg (macOS)
  and deb/rpm (Linux), each jlinking a Java 25 runtime and bundling the same jars, JavaCPP/OpenCV
  natives, tessdata and the LilyPond binary as app content (note jpackage's signing caveat for extra
  content; verify per OS). Audiveris's own `packaging` module is the reference.

The desktop app calls `lilyscan-core` directly (no HTTP); the web UI reaches the same core through
`lilyscan-server`. There is one pipeline implementation.

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

- **Python references (`tests/parity/`).** `scripts/java_parity.py` runs the Python implementation
  on the committed fixtures and writes what it produces; the Java tests compare against those files
  without Python installed. *In place since JM1:* `score.json` written by Python and re-written by
  Java (byte-identical), the MusicXML reader on 8 committed inputs (the three fixtures and one
  generated seed piece per category), the `.omr` book model and the attached IR on the two
  Audiveris projects, and Python's float formatting. `--sweep DIR` adds every seed and repertoire
  corpus piece locally (`-PparitySweep=DIR`); repertoire encodings are not committed (D11).
  Re-run the script whenever the Python behaviour changes.
- **Golden files reused.** `tests/golden/` (generator output) and `eval/fixtures/omr/` (saved
  `.omr`) are the acceptance tests for the generator and the new reader. The Java generator must
  reproduce the golden `.ly` byte-for-byte (D19); the Java reader must reproduce the Python reader's
  IR on the fixtures (measures, boxes, grades) — met in JM1 (§5).
- **Corpus parity.** `lilyscan eval run` on the seed and repertoire corpora must land within
  measurement noise of `eval/results/base-1031-seed` / `-rep` (Linux baselines). Results stay in
  `eval/results/<label>/` in the same `summary.md` + `results.json` format, so the port's progress
  is measured against the Python baselines item by item.
- **Confidence.** The ported scorer must reproduce the stored `confidence.json`'s outputs; refitting
  (`eval calibrate`) comes with the eval module and must hit the same ECE targets (< 0.05).
- **JUnit 5** for unit tests; tags for `engine` (needs Audiveris, now in-process), `lilypond`,
  `gpu`, mirroring the pytest marks. Engine tests skip without `TESSDATA_PREFIX`; CI runs them in
  their own job with the pinned, checksum-verified models. CI runs CPU-only.

## 11. Risks

| Risk | Mitigation |
|---|---|
| Stage 1 CV parity (pixel-exact thresholds) | Port against the corpus with per-page A/B vs Python; keep thresholds in one config module in staff-space units |
| No music21 on the JVM | Port only the helpers used, tested against Python behaviour (D20); the surface is small |
| Audiveris global/static state in-process | Forked worker per job (D16) restores per-process isolation; parent never holds engine state |
| Audiveris model classes are internal (no API stability guarantee) | Pinned source submodule (D15); a breaking change fails our compile, caught at build; golden `.omr` tests |
| Loss of the simple multi-container scale-out | One image + in-process forked workers covers one machine (the common case); JobRunr-on-SQL (D18) is the sanctioned multi-machine path, no Redis |
| bytedeco/JavaCPP natives must match the image platform | Resolve the `linux-x86_64` native classifiers at build time into the fat jar; the CI `docker build` + `lilyscan selftest` in the container is the guard (JM0/JM3). JavaCPP extracts natives to a temp dir at runtime, which works in the container |
| jpackage bundling of LilyPond + natives across 3 OSes (optional) | Only if the desktop installer is built (JM10); follow Audiveris's `packaging` module and verify the app image and signing per OS then. Not on the critical path |
| Synthetic corpus not byte-reproducible after the port | Re-baseline (D21); keep specs committed; report synthetic vs real separately (D11) |
| Determinism of generated `.ly` | Preserve exact formatting; reuse `tests/golden/` as the guard (D19) |

## 12. Milestones (build order)

Each milestone keeps the eval harness honest: every stage's value is measured against the Python
baseline, and a Java stage ships only when it matches it.

| JM | Deliverable | Exit criteria |
|---|---|---|
| **JM0** *(done)* | Gradle multi-project skeleton (`core`, `engine`, `server`, `cli`, `eval`; `desktop` stub); Java 25 toolchain; Audiveris submodule + composite build resolving; picocli launcher; Shadow fat jar; CI (Gradle build, tests, **Docker image build**) | `./gradlew build` works; `lilyscan versions`/`selftest` run; `serve` launches; the fat jar builds and a `docker build` of the server image succeeds; Audiveris `:app` is on the classpath |
| **JM1** | Engine adapter: forked worker (D16), in-process transcription + `.omr`→IR reader; MusicXML→IR; the IR model | Transcribes a sample page in-process to our IR; reproduces the Python reader's IR on `eval/fixtures/omr/`; ≥98% box coverage on clean input. **Done** |
| **JM2** | IR (records + Jackson, D19) + LilyPond generator + compiler + Q1–Q5 | `tests/golden/` reproduced byte-for-byte; all corpus engine outputs compile; ground-truth MusicXML→IR→`.ly` round-trips with zero compile errors. **First usable core** |
| **JM3** | `lilyscan-server` (Javalin) serving `services/web/` + `/api/jobs` + `/api/scores`; SQLite job store; the job steps (including the engine step: failed-sheet retry, prepared-vs-uploaded choice, `engine/run.json`); **the Docker image + `docker-compose.yml`** (D22) | `docker compose up` gives the existing browser review UI end to end — upload → transcribe → review → download — with nothing installed on the host but Docker. **First usable product** |
| **JM4** | Stage 5 repair + ported music21 helpers + confidence scorer | Reproduces `eval/results/base-1031-*` repair gains within noise; confidence ECE < 0.05 |
| **JM5** | Stage 0 + Stage 1 (PDFBox render; bytedeco OpenCV prepare, quality gate) | Photo/scan accuracy within noise of the Python M6/M7 results on the corpus |
| **JM6** | Stage 4 vector oracle (PDFBox + FontBox) | PDF exact-measure accuracy matches the Python M7 results |
| **JM7** | Combiner (`combine`/`align`/`arrange`) + transposition | Parts from two jobs combine into one compiling score; piano reduction matches Python output |
| **JM8** | Eval harness + synthetic corpus (D21) parity | `lilyscan eval run` reproduces the seed and repertoire baselines within noise; `eval calibrate` hits the ECE targets. **Full server parity with the Python product** |
| **JM9** | *(Optional)* Swing desktop review UI (three-pane, click-to-sync, recompile, review list, downloads) | Correct a flagged measure and re-export in under a minute, in the native app |
| **JM10** | *(Optional)* jpackage desktop installers (Win/Linux/macOS) | One installer installs a working desktop app with nothing else needed |

Build strategy mirrors `design.md` §10: JM2 is the first working core and **JM3 is the first usable
product** (`docker compose up`); each later milestone is measured against the Python baseline and
shipped only if it matches. **Full server parity is reached at JM8**, at which point the Java server
can replace the Python one. The optional desktop app (JM9) and its installer (JM10) follow only if
wanted. Nothing in the Python *server* feature set is dropped.

## 13. Repo layout (target)

```
lilyscan-desktop/
  settings.gradle              # includeBuild third_party/audiveris; the subprojects below
  build.gradle                 # Java 25 toolchain, shared config
  gradle/libs.versions.toml    # pinned dependency versions
  third_party/audiveris/       # git submodule, pinned tag 5.11.0 (unmodified)
  lilyscan-core/               # pipeline, IR, generator, repair, confidence, combine, config, device
  lilyscan-engine/             # Audiveris adapter, forked worker, .omr→IR reader
  lilyscan-server/             # Javalin app serving services/web/ + API (primary front end)
  lilyscan-cli/                # picocli launcher (serve | convert | eval | desktop | --engine-worker)
  lilyscan-eval/               # eval harness + synthetic corpus generator
  lilyscan-desktop/            # optional Swing app (FlatLaf)
  packaging/                   # optional jpackage / jlink wiring (mirrors Audiveris's packaging module)
  docker/                      # Dockerfile (server image) — new, replaces the Python images
  docker-compose.yml           # one service, one volume — the primary install (rewritten for Java)
  services/web/                # the existing SPA, reused unchanged
  eval/                        # corpus specs (committed), fixtures, committed results
  tests/ (per module)          # JUnit 5; golden files reused from the Python tree
  tests/parity/                # Python-generated references the Java tests compare against
  scripts/java_parity.py       # regenerates tests/parity/ from the Python implementation
  docs/design.md               # the pipeline design (authority on behaviour)
  docs/design-java.md          # this document (authority on the Java build)
```

The Python tree (`lilyscan/`, `services/lilyscan_app/`, `pyproject.toml`, Dockerfiles,
`docker-compose.yml`) stays in place during the port as the reference implementation and the source
of golden/baseline data, and is removed only once the Java build meets the accuracy bar end to end.

## 14. Status log

| Date | Milestone | State |
|---|---|---|
| 2026-11-01 | — | Design drafted. Decisions: D13 (JVM stack, overturns D9), D14 (Swing+FlatLaf, optional front end), D15 (Audiveris in-process via composite build, amends D6), D16 (forked engine worker for isolation), D17 (Javalin), D18 (SQLite + in-process dispatch, drops Redis), D19 (Jackson IR, schema-compatible), D20 (ported music21 helpers), D21 (Java synthetic corpus), D22 (jpackage installers), D23 (licences verified). Milestones JM0–JM10 defined. No code yet. |
| 2026-11-01 | — | **Revised to Docker-first delivery.** The primary product is the self-hosted web server, installed with `docker compose up` from one image with every dependency (Audiveris + native OCR, LilyPond, tessdata) baked in — no host install but Docker, no Redis, no 4-container split. The Swing desktop app and its jpackage installer are now optional and last (JM9–JM10). D22 rewritten (Docker-first; installer optional); D5/D8/D10/D14 adjusted; milestones reordered so JM3 (`docker compose up` works end to end) is the first usable product and JM8 reaches full server parity. |
| 2026-11-01 | JM0 | **Done** (PR #2). Gradle multi-project build on Java 25 (`core`, `engine`, `server`, `cli`, `eval`, optional `desktop`), picocli launcher, Shadow fat jar, Docker image and compose, CI. |
| 2026-11-01 | JM1 | **Done** (PR #3). Exit criteria met.<br>• **Audiveris in-process (D15):** pinned submodule + composite build; Audiveris compiles from source with our build; the fat jar (~67 MB) carries it with Tesseract 5.5.2 via JavaCPP and runs transcription on its own.<br>• **IR (D19):** `score.json` written by Python is read and re-written byte-for-byte; Python float formatting reproduced.<br>• **MusicXML → IR:** byte-identical IR to the Python reader on 8 committed inputs and on all 39 seed + repertoire corpus pieces.<br>• **`.omr` → IR:** the reader walks Audiveris's model; on the two fixture projects the attached `score.json` is byte-identical to Python's, and the book model matches exactly except line-symbol boxes Audiveris re-derives on reload (≤ 1 px, unused by any stage; §5).<br>• **Transcription (D16):** forked worker running Audiveris's batch CLI in-process; the bundled engine reports 5.11.0; the OCR-language and step-timeout constants are proven honoured; a clean page transcribed and read back to IR locates **102/102 events (100%)**, in ~18 s.<br>• Also: `merge_scores`, `Config`/`Settings`, the runner's log analysis, CLI `versions` (Python's format) and `selftest`. 76 tests (5 need the OCR models and run in CI's engine job).<br>• Deviations recorded above: mutable IR classes (D19), MusicXML import via the JDK DOM, the worker runs Audiveris's CLI rather than its step API, Gradle 9.4.1 to match Audiveris, the image copies the CI-built jar; the engine job step moves to JM3. |
