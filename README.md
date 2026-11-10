# Lilyscan

Sheet music in, editable LilyPond out. Lilyscan takes a born-digital PDF, a scan, or a phone
photo of printed music, reads it with the [Audiveris](https://github.com/Audiveris/audiveris)
optical music recognition (OMR) engine, repairs what the engine commonly gets wrong, and
writes a clean LilyPond project with the full score and every part. A browser UI shows the
page, the engraved result and the source side by side, so the measures that need a human
look can be found, fixed and recompiled in a minute.

![Reviewing a transcribed scan: the page as the engine read it, the engraved result, and the LilyPond source with the review list](docs/images/review.png)

**Status:** working end to end on one machine, in daily use on a library of real scanned
string parts. On the evaluation corpora about 83% of measures come out exactly right before
any human review (born-digital PDFs 89-92%, PNGs 83-88%, scans 83%, phone photos 72-74%);
see `eval/results/` and the status log in [docs/design.md](docs/design.md). What Audiveris
misreads is reported and fixed upstream where possible (see
[docs/upstream/audiveris](docs/upstream/audiveris/README.md)).

## What it does

- **Reads any kind of page.** Born-digital PDFs are rendered sharply at 400 DPI, and their
  printed tuplet numbers are used to check the rhythm. Photos and scans are straightened
  first: the page is found and seen head-on, uneven light is evened out, sideways or
  upside-down pages are turned upright, and curled or skewed staff lines are made straight.
  A scan is also read as uploaded, and Lilyscan keeps whichever result it expects to be
  better.
- **Repairs the engine's common mistakes** and says so: parts split between systems,
  key signatures misread on one system or in one part, tenor clefs read without their 8,
  missed triplets, dots and rests, multi-measure rests, page text or chord names read as
  lyrics, syllables read as one word. Each fix is listed in the review and noted in the
  source as a `% fix:` comment.
- **Tells you where to look.** Every note gets a calibrated confidence (a note at 0.7 is
  right about 70% of the time). Notes below 0.5 are marked in the source as `%{ ?? %}`, and a
  review list ranks the measures by what is wrong with them.
- **Writes real LilyPond.** One variable per staff, one line per measure with a bar check
  and a `% m. N` comment, absolute pitches, and layouts for the score and each part. The
  `.ly` download is a single file that engraves the score and each part.
- **Combines parts** read separately (a violin part and a cello part scanned on their own)
  into one score, lined up measure by measure, with transposition and concert pitch for
  transposing instruments. Parts can also be **reduced onto a piano accompaniment** below a
  melody part.
- **Exports** PDF, MIDI, MusicXML (for other notation programs) and the Audiveris project.

## How it works

A job goes through these stages (the design, and why each exists, is in
[docs/design.md](docs/design.md) and [docs/design-java.md](docs/design-java.md)). Paths are
packages under `lilyscan-core/src/main/java/org/lilyscan/core/` unless they say otherwise:

| Stage | What happens | Code |
|---|---|---|
| 0. Ingest | Every page of every upload, in order, becomes one book for the engine | `ingest/` |
| 1. Prepare | Photos and scans are found, straightened, turned upright and cleaned | `prepare/` |
| 2. Engine | Audiveris 5.11.0, with Lilyscan's fixes (fork `vilpter/audiveris` `master`), transcribes the book (batch mode; a failed page is left out and the rest read again) | `lilyscan-engine` (`AudiverisRunner`, a separate worker JVM) |
| 3. Import | The exported MusicXML becomes Lilyscan's score model, with page positions and grades from the Audiveris project | `ir/MusicXml`, `omr/` |
| 4. Vector oracle | Born-digital PDFs: glyphs read from the PDF itself check tuplets | `vector/` |
| 5. Repair | Rule-based repairs, then Lilyscan's calibrated confidence | `repair/` |
| 6. Score model | Parts, staves, measures, voices, events, with provenance for every change | `ir/` |
| 7. LilyPond | The editable project, compiled to PDF, MIDI and SVG | `lilypond/` |
| 8. Checks | Compiles (Q1), bar checks (Q2), measure durations (Q3), ranges (Q4), parts line up (Q5), no lyrics in instrumental parts (Q9) | `qa/` |

Combining parts (`combine/Combine`) aligns the parts measure by measure (`combine/Align`), lets
parts that disagree on the key vote, optionally reduces some of them to a piano accompaniment
(`combine/Arrange`), and produces a new job.

## Project layout

```
lilyscan-core/       the pipeline (stages above), score model, LilyPond writer, repairs, Stage 1
lilyscan-engine/     Audiveris run in-process inside a separate worker JVM
lilyscan-server/     the web server (Javalin), job store (SQLite) and job steps
lilyscan-eval/       corpus scoring and confidence fitting
lilyscan-cli/        the command line (`serve`, `convert`, `eval`, ...); builds lilyscan.jar
lilyscan-desktop/    the desktop app (Swing): the review in a window, on this computer or a server
services/web/        the browser UI (plain HTML, CSS and JavaScript, no build step)
third_party/         Audiveris 5.11.0 + Lilyscan's fixes: fork vilpter/audiveris master (git submodule)
eval/                corpus specs (seed.json, repertoire.json) and committed results
docs/                design.md (design and status log), upstream/audiveris (bug reports and fixes)
tests/               parity references (from the Python implementation) and test fixtures
docker/              the image (Dockerfile, entrypoint, OCR model checksums)
packaging/linux/     the desktop app's .deb and .rpm (package.sh)
docker-compose.yml   the Docker deployment: one service
```

## Install

**Step-by-step guide for beginners: [docs/INSTALL.md](docs/INSTALL.md)** (installing Docker,
keeping Lilyscan private, settings, updates, backups, troubleshooting).

### Web server, on a home server (Docker)

On a 64-bit Intel/AMD Linux machine with [Docker](https://docs.docker.com/engine/install/) and its
Compose plugin, 4 GB of memory or more:

```bash
mkdir -p ~/lilyscan && cd ~/lilyscan
curl -fsSLO https://github.com/vilpter/lilyscan/releases/latest/download/docker-compose.yml
docker compose up -d
```

Then open `http://<the server's address>:8000/` from any browser on your network. The image
(`ghcr.io/vilpter/lilyscan`, about 700 MB) carries everything: the server, Audiveris, LilyPond and
the OCR models. Jobs are kept in the Docker volume `lilyscan-data`. Update with
`docker compose pull && docker compose up -d`.

**Lilyscan has no login:** keep it on your home network, or reach it through a VPN or a reverse
proxy that asks for a password ([how](docs/INSTALL.md#step-6-keep-it-private)).

### Desktop app, on Ubuntu 24.04+ or Debian 13+ (apt)

```bash
sudo install -d -m 0755 /etc/apt/keyrings
sudo curl -fsSLo /etc/apt/keyrings/lilyscan.gpg \
  https://github.com/vilpter/lilyscan/releases/download/apt/lilyscan-archive-keyring.gpg
echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/lilyscan.gpg] https://github.com/vilpter/lilyscan/releases/download/apt ./" \
  | sudo tee /etc/apt/sources.list.d/lilyscan.list
sudo apt update && sudo apt install lilyscan
```

It brings its own Java, Audiveris, LilyPond and OCR models, appears in the applications menu as
**Lilyscan**, and updates with `sudo apt upgrade`. It works on its own, or on your server's jobs
(**Connection → Lilyscan server…**). The [Releases page](https://github.com/vilpter/lilyscan/releases)
also has the `.deb` and an `.rpm` (Fedora, openSUSE) to install directly.

### Building from source

Lilyscan is one Java program, `lilyscan.jar`, with Audiveris built in. Building it needs a JDK 25
and the Audiveris submodule:

```bash
git clone --recurse-submodules https://github.com/vilpter/lilyscan.git
cd lilyscan
LANG=C.UTF-8 ./gradlew :lilyscan-cli:shadowJar
```

The jar is `lilyscan-cli/build/libs/lilyscan.jar` (about 120 MB). Run the build again after
`git pull`. If the clone was made without `--recurse-submodules`, run
`git submodule update --init` first. Then either build the image from it:

```bash
docker compose -f docker-compose.yml -f docker/compose.build.yml up -d --build
```

(tagged `lilyscan:local`; the image is built for x86-64 Linux), or run it natively (below), or
build the desktop packages: `packaging/linux/package.sh deb` (or `rpm`, `app-image`; needs
`fakeroot` for a `.deb`, `rpm` for a `.rpm`). Releases are made by CI ([docs/RELEASING.md](docs/RELEASING.md)).

#### Natively

To run the jar directly, Lilyscan runs on Linux and Windows (macOS should work, but is untested).
You need:

| Tool | Version | Notes | Ubuntu |
|---|---|---|---|
| Java | 25 | a JDK to build the jar; a JRE is enough to run it | [steps](#java) |
| [LilyPond](https://lilypond.org/download.html) | 2.26.0 | `lilypond` on the `PATH`, or `LILYPOND_BIN` | [steps](#lilypond) |
| Tesseract language models | [tessdata](https://github.com/tesseract-ocr/tessdata) (not `tessdata_fast`) | `eng`, `lat`, `deu` and `fra` by default, in the folder named by `TESSDATA_PREFIX`. Without them Audiveris reads no lyrics or titles | [steps](#ocr-models) |

Audiveris is inside the jar; it needs no install of its own. It needs the full OCR models with
the legacy engine: `tessdata_fast` and distribution `tesseract-ocr-*` packages do not work.
The versions are pinned: Lilyscan is tested against exactly these.

##### On Ubuntu

These steps are for Ubuntu 24.04 or later on x86-64. Run each block in a terminal; each one
is safe to run again. First:

```bash
sudo apt install git curl
```

###### Java

```bash
sudo apt install openjdk-25-jdk
java -version
```

If your Ubuntu has no `openjdk-25-jdk` package, install
[Eclipse Temurin 25](https://adoptium.net/installation/linux/) instead.

###### LilyPond

Ubuntu's own `lilypond` package is too old (2.24), so this installs the official 2.26.0 build
in `/opt`. It takes precedence over the Ubuntu package if that is installed too.

```bash
cd /tmp
curl -fLO https://gitlab.com/lilypond/lilypond/-/releases/v2.26.0/downloads/lilypond-2.26.0-linux-x86_64.tar.gz
sudo tar -xzf lilypond-2.26.0-linux-x86_64.tar.gz -C /opt
sudo ln -sf /opt/lilypond-2.26.0/bin/lilypond /usr/local/bin/lilypond
hash -r
lilypond --version
```

The last line should print `GNU LilyPond 2.26.0`.

###### OCR models

```bash
sudo mkdir -p /usr/local/share/tessdata
for lang in eng lat deu fra; do
  sudo curl -fL -o /usr/local/share/tessdata/$lang.traineddata \
    https://github.com/tesseract-ocr/tessdata/raw/main/$lang.traineddata
done
grep -q TESSDATA_PREFIX ~/.profile || echo 'export TESSDATA_PREFIX=/usr/local/share/tessdata' >> ~/.profile
```

Log out and back in, so that `TESSDATA_PREFIX` is set.

Then build the jar (above) and check the tools:

```bash
java -jar lilyscan-cli/build/libs/lilyscan.jar versions
java -jar lilyscan-cli/build/libs/lilyscan.jar selftest
```

`versions` compares the installed LilyPond and the bundled Audiveris with the pinned ones;
`selftest` compiles a small score.

## Start

Natively:

```bash
java -jar lilyscan-cli/build/libs/lilyscan.jar serve
```

Then open <http://localhost:8000/>. `--port` changes the port and `--data` the folder where
jobs are kept (default `./data`). Jobs run inside the server: Audiveris in a separate JVM per
run, so a crash or a runaway page cannot take the server down.

With Docker: `docker compose up -d` ([above](#web-server-on-a-home-server-docker)).

## Use

### Transcribe

![The home page: upload, combine parts, and the job list](docs/images/home.png)

1. **Upload** a PDF or page images on the home page (on a phone this can open the camera).
   Pages are prepared for the engine unless you untick the option. The OCR languages for
   lyrics and text can be changed per upload.
2. **Follow** the job: preparing pages, the engine (about a minute a page), repairs, LilyPond
   and checks. A page Audiveris cannot read is left out, and the job's report says which.
3. **Review** in three panes. **Source** shows the page as the engine read it, every note
   boxed green, amber or red by confidence and the measures that need attention outlined.
   **Engraved** shows the LilyPond output. **LilyPond** is the editable source, with the
   review list of measures ranked by problems. Clicking a measure in any pane, or putting the
   caret on its line, highlights it in the other two. The badges Q1-Q5 and Q9 show the checks.
   When a part named for an instrument has lyrics (Q9), usually a title or a note read as
   lyrics, **Drop these lyrics** stops engraving them.
4. **Edit** the source and press **Save & recompile**.
5. **Download** the result:
   - `.ly`: one file that engraves the score and each part. Compiling `piece.ly` gives
     `piece-score.pdf` and one PDF per part.
   - `.ly project`: the editable project (a file per part), as a zip.
   - PDF and MIDI of the score.
   - MusicXML: the score as transcribed and repaired, for other notation programs. Edits to
     the LilyPond source are not in it.
   - `.omr`: the Audiveris project, to open in the Audiveris application.

**Re-run** repeats a job with other settings (OCR languages, page straightening).

### Combine parts

![A soprano line with the alto, tenor and bass reduced onto a piano accompaniment](docs/images/piano.png)

**Combine parts** on the home page makes a new score from parts of finished jobs: say a
violin part and a cello part scanned separately.

- Each part can be transposed, or taken at concert pitch if it is a transposing instrument.
- Parts read separately seldom have exactly the same measures (the engine misses or adds one
  now and then), so they are lined up measure by measure. Where a part has no measure it gets
  a rest that shows in the review list. Parts that disagree on the key signature (one read
  wrongly throughout) take the key the others read.
- Your edits to each part carry over: the new score includes the parts' own LilyPond sources,
  and transposition is done by LilyPond's `\transpose`, so those sources stay as written.
- A part can go **in the piano accompaniment** instead of on its own staff. The chosen parts
  are reduced onto a piano grand staff below the others, turning an ensemble arrangement into
  a melody with piano. Each part goes to the staff for the register it sounds in (a double
  bass an octave below where it is written). Parts playing the same rhythm merge into chords;
  others keep voices of their own.

The combined score is a job like any other: review it, edit it, download it.

### Desktop app

The same review, as a desktop window instead of a browser tab:

```bash
java -jar lilyscan.jar desktop                                # on this computer
java -jar lilyscan.jar desktop --server http://homelab:8000    # with a Lilyscan server
```

On this computer it starts the Lilyscan engine inside the app, and keeps the jobs in your
user data folder (`~/.local/share/lilyscan` on Linux, `%LOCALAPPDATA%\Lilyscan` on Windows,
`~/Library/Application Support/Lilyscan` on macOS; `--data` sets another). It needs LilyPond
and the OCR models as a native install does ([Natively](#natively)). With `--server`, or
**Connection > Lilyscan server...**, it works on the jobs of a server, such as the Docker one,
and needs nothing else installed. The app remembers the last choice.

**Installers.** On Ubuntu and Debian, install it with apt ([above](#desktop-app-on-ubuntu-2404-or-debian-13-apt)):
it brings its own Java runtime, Audiveris, LilyPond 2.26.0 and the OCR models, installs to
`/opt/lilyscan` with a menu entry **Lilyscan**, and `sudo apt remove lilyscan` removes it, keeping
your jobs. `LILYPOND_BIN` and `TESSDATA_PREFIX` still override the bundled LilyPond and models.

It does what the web UI does: upload (or drop files on the window), follow a job, review in
three panes with click-to-sync, edit and recompile, drop misread lyrics, re-run, combine parts,
and download every format. **Ctrl + mouse wheel** or the **- Fit +** buttons zoom the page and
the engraved score.

### Command line

```bash
java -jar lilyscan.jar convert score.mxl --out out/score
```

`convert` turns a MusicXML file (for example Audiveris output) into a LilyPond project with a
QA report. The other commands: `serve` (above), `desktop` (above), `versions`, `selftest` and
`eval` (below).
`java -jar lilyscan.jar <command> --help` shows the options.

### What a job keeps

A finished job's folder (under `data/jobs/<id>/`) holds:

- `prepared/`: `pages.tif` (every page as given to the engine, in upload order), `uploaded.tif`
  when some page is a scan, and `report.json` (what was done to each page, quality warnings).
- `engine/`: the Audiveris project, its MusicXML and log, and `run.json` (the command, which
  pages were left out and why, and which reading was kept).
- `ir/score.json`: the score model, with page boxes and confidence per event.
- `ly/`: the LilyPond project (`main.ly`, `parts/`, `layout/`) and the compiled PDF and MIDI.
- `score.musicxml`, `overlays/page-N.png` (each page with the notes boxed by confidence), and
  `report.json` (pages, engine run, geometry, repairs, checks Q1-Q5 and Q9).

### Configuration

Settings come from the environment:

| Variable | Default | Meaning |
|---|---|---|
| `LILYSCAN_DATA_DIR` | `data` | where jobs are kept (`serve --data` sets it) |
| `LILYSCAN_OCR_LANGUAGES` | `eng+lat+deu+fra` | Tesseract codes for lyrics and text; the Docker image downloads missing models at startup |
| `LILYPOND_BIN` | `lilypond` | the LilyPond executable |
| `AUDIVERIS_BIN` | | an external Audiveris to run instead of the bundled one (for testing) |
| `AUDIVERIS_MAX_HEAP` | `3G` | the JVM heap of each Audiveris run |
| `TESSDATA_PREFIX` | | the folder with the Tesseract models |
| `AUDIVERIS_TIMEOUT_S`, `AUDIVERIS_TIMEOUT_PER_PAGE_S` | 900, 240 | an engine run may take the larger of the two (the second per page) |
| `AUDIVERIS_STEP_TIMEOUT_S` | 300 | one engine step on one page (Audiveris's own default is 120) |
| `LILYPOND_TIMEOUT_S` | 120 | one LilyPond compile |

## Develop

```bash
LANG=C.UTF-8 ./gradlew build
```

builds every module and runs the tests. The parity tests compare the Java code with references
the original Python implementation of Lilyscan produced (`tests/parity/`). That implementation,
and the scripts that made the references and the larger local sweeps (`-PengineSweep`,
`-PprepareSweep`, `-PcombineSweep`, `-PevalSweep`), are in the history: `git checkout 83e824c`
is the last commit that has them. Comments in the Java code that name a `lilyscan/*.py` file
refer to it.

Tests that need LilyPond (`LILYPOND_BIN`) or the OCR models (`TESSDATA_PREFIX`) are skipped
when the tool is missing, not failed; the window test needs a display (CI runs it under Xvfb).
Each change goes on a branch and a pull request; CI builds the jar, runs the tests, builds the
image and transcribes a page in it, builds the Linux packages, and runs the LilyPond tests
against the next LilyPond release. Changing a pinned tool version is a change of its own:
update the pins, the Dockerfile and the Audiveris submodule, then re-run the evaluation.

### Evaluation

Three corpora measure every change against ground truth, each built from a committed spec:

- **Seed** (`eval/corpus/seed.json`): random but valid music in six categories (solo, piano,
  choir, lead sheet, quartet, song) with exact MusicXML, engraved with LilyPond, then degraded
  to simulated scans and photos.
- **Repertoire** (`eval/corpus/repertoire.json`): excerpts of public-domain works from
  music21's corpus, with each work's rights recorded in the manifest.
- **OpenScore** (`eval/corpus/openscore.json`): excerpts of CC0 works from
  [OpenScore Lieder](https://github.com/OpenScore/Lieder) (songs for voice and piano in German,
  French and English, from where the voice enters) and
  [OpenScore String Quartets](https://github.com/OpenScore/StringQuartets).

Real works are fetched once, at pinned revisions, into `eval/corpus/cache/`:

```bash
java -jar lilyscan.jar corpus build
java -jar lilyscan.jar corpus build --spec eval/corpus/repertoire.json
java -jar lilyscan.jar corpus build --spec eval/corpus/openscore.json
```

The seed pieces are the same as the original Python builder's: the random draws reproduce
Python's exactly, and a test compares every piece note for note with references it made. The
simulated scans and photos use Java's own random numbers, so they differ from Python's in
detail; compare a Java-built corpus with the `java-*` baselines.

```bash
java -jar lilyscan.jar eval run --repair --prepare --label my-run
java -jar lilyscan.jar eval run --corpus eval/corpus/repertoire --repair --prepare --label my-run-rep
```

Results go to `eval/results/<label>/` (`summary.md`, `results.json`), and Audiveris's output is
cached in `eval/work/`, so later runs only re-score. The summary reports exact-measure
accuracy, edit rate, note F1, lyric and chord accuracy, how many engine events were located
on the page, and how well confidence is calibrated.

The current baselines are `base-1031-seed` and `base-1031-rep`: fresh engine runs on Linux
(Ubuntu, x86-64) with `--repair --prepare --lilypond`, on corpora built by the original Python
builder.
`java-1103-seed`, `java-1103-repertoire` and `java-1103-openscore` are the same runs on the
corpora the Java builder makes; compare a Java-built corpus with these. (`base-1014-seed` and
`base-1014-rep` are results on Windows. Audiveris reads a few pages slightly differently there, so
compare with a baseline from the same platform, item by item in `results.json`.)

- `--repair` applies Lilyscan's repairs and confidence before scoring, and lists every repair.
- `--prepare` sends scans and photos through Stage 1 first, as a job does.
- `--lilypond` also generates LilyPond and reports how often it compiles (Q1) and passes bar
  checks (Q2).
- New engine runs need the OCR models (`TESSDATA_PREFIX`).

After changing a repair rule or adding corpus pieces, refit the confidence model (it rewrites
`confidence.json` in `lilyscan-core/src/main/resources/org/lilyscan/core/repair/` and prints the
calibration error per corpus):

```bash
java -jar lilyscan.jar eval calibrate
```

## License

AGPL-3.0-or-later, like Audiveris. See [LICENSE](LICENSE). Every model, dataset and library
Lilyscan uses must be AGPL-compatible ([docs/design.md](docs/design.md), decision D12).
