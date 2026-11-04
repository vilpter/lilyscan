package org.lilyscan.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.lilyscan.core.Pipeline;
import org.lilyscan.core.Versions;
import org.lilyscan.core.ingest.Book;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.lilypond.Compile;
import org.lilyscan.core.omr.Geometry;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.omr.OmrException;
import org.lilyscan.core.prepare.Prepare;
import org.lilyscan.core.repair.Confidence;
import org.lilyscan.core.repair.Repair;
import org.lilyscan.core.repair.Repairs;
import org.lilyscan.core.runtime.Config;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.util.Py;
import org.lilyscan.core.vector.VectorOracle;
import org.lilyscan.engine.audiveris.AudiverisRun;
import org.lilyscan.engine.audiveris.AudiverisRunner;
import org.lilyscan.engine.audiveris.OmrReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The evaluation harness (port of {@code lilyscan/evaluation/harness.py}): the engine over a
 * built corpus (cached per item and variant, in the Python cache format), then geometry,
 * repairs, Lilyscan's confidence and the pipeline's QA, scored against the ground truth.
 */
public final class Harness {
    private Harness() {}

    private static final Logger log = LoggerFactory.getLogger(Harness.class);
    private static final ObjectMapper MAPPER = IrJson.mapper();
    /** Input variants prepared before the engine with {@code prepare}. */
    static final List<String> PREPARED_VARIANTS = List.of("pdf", "scan", "photo");

    /** Options of a run ({@code lilyscan eval run}). */
    public record Options(boolean reuse, boolean lilypond, boolean repair, boolean prepare) {}

    /** One item and variant, scored. */
    public static final class ItemResult {
        final Corpus.Item item;
        final String variant;
        Compare.Comparison comparison;
        Double wallS;
        List<String> engineErrors;
        boolean cached;
        Map<String, Boolean> qa;
        int located, mappable;
        List<Compare.Scored> correctness = new ArrayList<>();
        List<Map<String, Object>> repairs = new ArrayList<>();
        Map<String, Object> prepare;

        ItemResult(Corpus.Item item, String variant) {
            this.item = item;
            this.variant = variant;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.id());
            m.put("category", item.category());
            m.put("language", item.language());
            m.put("variant", variant);
            m.put("wall_s", wallS);
            m.put("engine_errors", engineErrors);
            m.put("qa", qa);
            m.put("located_events", located);
            m.put("mappable_events", mappable);
            m.put("repairs", repairs);
            m.put("prepare", prepare);
            m.putAll(comparison.toMap());
            return m;
        }
    }

    /** Where the engine output for this item and variant is cached. */
    static Path engineDir(Path work, Corpus.Item item, String variant, boolean prepare) {
        boolean prepared = prepare && PREPARED_VARIANTS.contains(variant);
        return work.resolve(item.id()).resolve(prepared ? variant + "-prepared" : variant);
    }

    private static Path preparedSource(Path outDir, String variant) {
        return outDir.resolve(variant.equals("pdf") ? "prepared.tif" : "prepared.png");
    }

    private static Map<String, Object> preparedInput(Corpus.Item item, String variant, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        Map<String, Object> report = variant.equals("pdf")
                ? Book.renderPdf(item.input(variant), preparedSource(outDir, variant), Config.PDF_RENDER_DPI)
                : Prepare.prepareImage(item.input(variant), preparedSource(outDir, variant)).toMap();
        write(outDir.resolve("prepare.json"), PyJson.write(MAPPER.valueToTree(report), PyJson.Style.jsonDumps(2)));
        return report;
    }

    /** Books read in this process, by .omr path (reading is deterministic, and slow). */
    private static final Map<Path, Object> BOOKS = new ConcurrentHashMap<>();

    static OmrBook readOmr(Path omr) {
        Object b = BOOKS.computeIfAbsent(omr.toAbsolutePath(), p -> {
            try {
                return OmrReader.read(p);
            } catch (OmrException e) {
                return e;
            }
        });
        if (b instanceof OmrException e) throw e;
        return (OmrBook) b;
    }

    private static Path firstOmr(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        try (var list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().endsWith(".omr")).sorted().findFirst().orElse(null);
        }
    }

    private record Engine(Score score, Double wallS, List<String> errors, boolean cached, Map<String, Object> report) {}

    @SuppressWarnings("unchecked")
    private static Engine engineOutput(Corpus.Item item, String variant, Path work, Settings settings, boolean reuse,
            boolean prepare) throws IOException {
        Path outDir = engineDir(work, item, variant, prepare);
        Path summaryPath = outDir.resolve("run.json");
        boolean cached = reuse && Files.isRegularFile(summaryPath);
        boolean prepared = prepare && PREPARED_VARIANTS.contains(variant);
        Map<String, Object> report = null;
        if (prepared && cached && Files.isRegularFile(outDir.resolve("prepare.json"))) {
            report = MAPPER.readValue(outDir.resolve("prepare.json").toFile(), LinkedHashMap.class);
        } else if (prepared) {
            report = preparedInput(item, variant, outDir);
            cached = false;
        }
        Map<String, Object> summary;
        if (cached) {
            summary = MAPPER.readValue(summaryPath.toFile(), LinkedHashMap.class);
        } else {
            Path source = prepared ? preparedSource(outDir, variant) : item.input(variant);
            AudiverisRun run = AudiverisRunner.run(List.of(source), outDir, null, null, settings, null, null);
            summary = new LinkedHashMap<>();
            summary.put("ok", run.ok());
            summary.put("wall_s", Py.round(run.wallS(), 2));
            summary.put("mxl_files", run.mxlFiles().stream().map(p -> p.getFileName().toString()).toList());
            List<String> errors = new ArrayList<>(run.stepErrors());
            errors.addAll(run.ocrProblems());
            summary.put("errors", errors);
            summary.put("timed_out", run.timedOut());
            write(summaryPath, PyJson.write(MAPPER.valueToTree(summary), PyJson.Style.jsonDumps(2)));
        }
        List<String> errors = new ArrayList<>((List<String>) summary.get("errors"));
        Score score = null;
        if (Boolean.TRUE.equals(summary.get("ok"))) {
            List<Score> parts = new ArrayList<>();
            boolean readable = true;
            try {
                for (Object name : (List<Object>) summary.get("mxl_files")) parts.add(MusicXml.load(outDir.resolve((String) name), "audiveris"));
            } catch (MusicXml.MusicXmlException | UncheckedIOException e) {
                errors.add("cannot read engine MusicXML: " + e.getMessage());
                readable = false;
            }
            if (readable) {
                // Stage 3: page boxes and grades from the .omr, attached movement by movement.
                Path omr = firstOmr(outDir);
                OmrBook book = null;
                if (omr != null) {
                    try {
                        book = readOmr(omr);
                    } catch (OmrException e) {
                        errors.add("cannot read .omr: " + e.getMessage());
                    }
                }
                score = parts.isEmpty() ? null : Geometry.attachMovements(parts, book).score();
            }
        }
        Object wall = summary.get("wall_s");
        return new Engine(score, wall == null ? null : ((Number) wall).doubleValue(), errors, cached, report);
    }

    /** The engine's score for an item and variant, unprepared and cached (as calibration reads it). */
    static Score engineScore(Corpus.Item item, String variant, Path work, Settings settings) throws IOException {
        return engineOutput(item, variant, work, settings, true, false).score();
    }

    static Path firstOmrIn(Path dir) throws IOException {
        return firstOmr(dir);
    }

    private record Processed(Score pred, Path outDir, Path omr, int located, int mappable, List<Map<String, Object>> repairs) {}

    private static Processed process(Score pred, Path outDir, boolean repair, Path pdf) throws IOException {
        Path omr = firstOmr(outDir);
        OmrBook book = null;
        int located = 0, mappable = 0;
        if (pred != null && omr != null) {
            try {
                book = readOmr(omr);
            } catch (OmrException e) {
                // reported when the engine output was loaded
            }
            // Boxes were attached on loading; events in a measure found on the page count.
            for (var ps : pred.staves()) {
                for (Measure m : ps.staff().measures) {
                    if (m.bbox == null) continue;
                    for (Voice v : m.voices) for (Event e : v.events) {
                        mappable++;
                        if (e.bbox != null) located++;
                    }
                }
            }
        }
        List<Map<String, Object>> repairs = new ArrayList<>();
        if (repair && pred != null) {
            List<Repair> log = new ArrayList<>();
            if (pdf != null && book != null) log.addAll(VectorOracle.apply(pred, pdf, book));
            log.addAll(Repairs.apply(pred, book));
            for (Repair r : log) repairs.add(r.toMap());
            Confidence.calibrate(pred);
        }
        return new Processed(pred, outDir, omr, located, mappable, repairs);
    }

    static double expectedRight(Score score) {
        Py.Sum sum = new Py.Sum(); // Python's sum() of floats is compensated
        for (var ps : score.staves()) for (Measure m : ps.staff().measures) for (Voice v : m.voices) {
            for (Event e : v.events) if (e.confidence != null) sum.add(e.confidence);
        }
        return sum.value();
    }

    public static ItemResult evaluateItem(Corpus.Item item, String variant, Path work, Settings settings, Options o) throws IOException {
        Score gt = MusicXml.load(item.groundTruth(), "ground-truth");
        Engine eng = engineOutput(item, variant, work, settings, o.reuse(), o.prepare());
        Score pred = eng.score();
        Double wallS = eng.wallS();
        List<String> errors = eng.errors();
        boolean cached = eng.cached();
        Map<String, Object> prepared = eng.report();
        // Stage 4 reads the born-digital PDF the engine's pages came from.
        Path pdf = o.prepare() && variant.equals("pdf") ? item.input(variant) : null;
        Processed done = process(pred, engineDir(work, item, variant, o.prepare()), o.repair(), pdf);
        // As in a job: when Stage 1 found no page edges (a scan), the page as uploaded is
        // transcribed too, and the run Lilyscan expects to have more right is kept.
        if (o.repair() && prepared != null && Boolean.FALSE.equals(prepared.get("page_found"))) {
            Engine alt = engineOutput(item, variant, work, settings, o.reuse(), false);
            Processed other = process(alt.score(), engineDir(work, item, variant, false), o.repair(), null);
            double mine = pred != null ? expectedRight(pred) : -1.0;
            double theirs = alt.score() != null ? expectedRight(alt.score()) : -1.0;
            Map<String, Object> p = new LinkedHashMap<>(prepared);
            Map<String, Object> er = new LinkedHashMap<>();
            er.put("prepared", Py.round(mine, 2));
            er.put("uploaded", Py.round(theirs, 2));
            p.put("expected_right", er);
            p.put("chosen", theirs > mine ? "uploaded" : "prepared");
            prepared = p;
            wallS = (wallS == null ? 0.0 : wallS) + (alt.wallS() == null ? 0.0 : alt.wallS());
            if (theirs > mine) {
                pred = alt.score();
                errors = alt.errors();
                cached = alt.cached() && cached;
                done = other;
            }
        }
        ItemResult result = new ItemResult(item, variant);
        result.comparison = Compare.compare(gt, pred);
        result.wallS = wallS;
        result.engineErrors = errors;
        result.cached = cached;
        result.located = done.located();
        result.mappable = done.mappable();
        result.repairs = done.repairs();
        result.prepare = prepared;
        if (pred != null && done.omr() != null) result.correctness = Compare.eventCorrectness(gt, pred);
        if (o.lilypond() && pred != null) {
            Map<String, Object> report = Pipeline.produce(pred, done.outDir().resolve(o.repair() ? "lilyscan-repaired" : "lilyscan"),
                    settings, null);
            Map<String, Boolean> qa = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> checks = (List<Map<String, Object>>) ((Map<String, Object>) report.get("qa")).get("checks");
            for (Map<String, Object> c : checks) qa.put((String) c.get("id"), (Boolean) c.get("passed"));
            result.qa = qa;
        }
        log.info("{}/{}: measures {}%, note F1 {}{}", item.id(), variant, Py.fixed(100 * result.comparison.measureAccuracy(), 0),
                Py.fixed(result.comparison.notePrf()[2], 2), cached ? " (cached)" : "");
        return result;
    }

    /** Every item and variant, {@code jobs} at a time, in order. */
    public static List<ItemResult> run(List<Corpus.Item> items, Path work, List<String> variants, int jobs, Settings settings,
            Options o) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, jobs));
        try {
            List<Future<ItemResult>> futures = new ArrayList<>();
            for (Corpus.Item item : items) for (String v : variants) futures.add(pool.submit(() -> evaluateItem(item, v, work, settings, o)));
            List<ItemResult> out = new ArrayList<>();
            for (Future<ItemResult> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
        }
    }

    private static Double mean(List<Double> values) {
        return values.isEmpty() ? null : Py.round(Py.mean(values), 4);
    }

    private static Double rate(List<Boolean> flags) {
        if (flags.isEmpty()) return null;
        int n = 0;
        for (boolean f : flags) if (f) n++;
        return Py.round((double) n / flags.size(), 4);
    }

    /** Micro-averaged measure accuracy and edit rate; macro-averaged F1 scores. */
    public static Map<String, Map<String, Object>> summarize(List<ItemResult> results) {
        TreeMap<String, List<ItemResult>> groups = new TreeMap<>();
        for (ItemResult r : results) {
            groups.computeIfAbsent("variant:" + r.variant, k -> new ArrayList<>()).add(r);
            if (r.prepare != null && truthy(r.prepare.get("passed"))) {
                groups.computeIfAbsent("variant:" + r.variant + ", gate passed", k -> new ArrayList<>()).add(r);
            }
            groups.computeIfAbsent("category:" + r.item.category(), k -> new ArrayList<>()).add(r);
            groups.computeIfAbsent("all", k -> new ArrayList<>()).add(r);
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        groups.forEach((key, rs) -> {
            int gtMeasures = 0, gtEvents = 0, exact = 0, edits = 0, ok = 0, located = 0, mappable = 0;
            List<Double> noteF1 = new ArrayList<>(), onsetF1 = new ArrayList<>(), lyrics = new ArrayList<>(), chords = new ArrayList<>(),
                    walls = new ArrayList<>();
            List<Boolean> q1 = new ArrayList<>(), q2 = new ArrayList<>();
            List<Compare.Scored> pairs = new ArrayList<>();
            TreeMap<String, Integer> repairs = new TreeMap<>();
            for (ItemResult r : rs) {
                Compare.Comparison c = r.comparison;
                gtMeasures += c.gtMeasures;
                gtEvents += c.gtEvents;
                exact += c.exactMeasures;
                edits += c.eventEdits;
                if (c.engineOk) ok++;
                noteF1.add(c.notePrf()[2]);
                onsetF1.add(c.onsetPrf()[2]);
                if (c.lyricAccuracy() != null) lyrics.add(c.lyricAccuracy());
                if (c.chordAccuracy() != null) chords.add(c.chordAccuracy());
                if (r.wallS != null) walls.add(r.wallS);
                if (r.qa != null) {
                    q1.add(r.qa.get("Q1"));
                    q2.add(r.qa.get("Q2"));
                }
                located += r.located;
                mappable += r.mappable;
                pairs.addAll(r.correctness);
                for (Map<String, Object> x : r.repairs) repairs.merge((String) x.get("rule"), 1, Integer::sum);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("items", rs.size());
            row.put("engine_ok", ok);
            row.put("measure_accuracy", gtMeasures != 0 ? Py.round((double) exact / gtMeasures, 4) : null);
            row.put("edit_rate", gtEvents != 0 ? Py.round((double) edits / gtEvents, 4) : null);
            row.put("note_f1", mean(noteF1));
            row.put("onset_f1", mean(onsetF1));
            row.put("lyric_accuracy", mean(lyrics));
            row.put("chord_accuracy", mean(chords));
            row.put("mean_wall_s", mean(walls));
            row.put("compiles", rate(q1));
            row.put("bar_checks_clean", rate(q2));
            row.put("box_coverage", mappable != 0 ? Py.round((double) located / mappable, 4) : null);
            row.put("calibration", Compare.calibration(pairs));
            row.put("repairs", new LinkedHashMap<>(repairs));
            out.put(key, row);
        });
        return out;
    }

    private static boolean truthy(Object o) {
        return o != null && !Boolean.FALSE.equals(o) && !(o instanceof Number n && n.doubleValue() == 0);
    }

    private static String fmt(Object v, boolean pct) {
        if (v == null) return "-";
        double d = ((Number) v).doubleValue();
        return pct ? Py.fixed(100 * d, 1) + "%" : Py.fixed(d, 1);
    }

    /** Writes {@code results.json} and {@code summary.md}; returns the summary's path. */
    @SuppressWarnings("unchecked")
    public static Path writeResults(List<ItemResult> results, Path outDir, String label, String notes, Settings settings) throws IOException {
        Files.createDirectories(outDir);
        Map<String, Map<String, Object>> summary = summarize(results);
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("label", label);
        env.put("created", ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+00:00'")));
        env.put("lilyscan", Versions.APP);
        env.put("audiveris", AudiverisRunner.audiverisVersion(settings));
        env.put("lilypond", Compile.lilypondVersion(settings));
        env.put("platform", System.getProperty("os.name") + "-" + System.getProperty("os.version") + "-" + System.getProperty("os.arch")
                + "-with-java-" + System.getProperty("java.version"));
        env.put("processor", "");
        env.put("notes", notes);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("environment", env);
        doc.put("summary", summary);
        List<Object> items = new ArrayList<>();
        for (ItemResult r : results) items.add(r.toMap());
        doc.put("items", items);
        write(outDir.resolve("results.json"), PyJson.write(MAPPER.valueToTree(doc), PyJson.Style.jsonDumps(2)) + "\n");

        boolean withQa = results.stream().anyMatch(r -> r.qa != null);
        String qaHead = (withQa ? " Compiles (Q1) | Bar checks (Q2) |" : "") + " Boxes | ECE |";
        List<String> lines = new ArrayList<>();
        lines.add("# Evaluation: " + label);
        lines.add("");
        lines.add("Audiveris " + Py.str(env.get("audiveris")) + ", LilyPond " + Py.str(env.get("lilypond")) + ", lilyscan " + Versions.APP
                + ", " + env.get("platform") + ".");
        lines.add("");
        lines.add(notes);
        lines.add("");
        lines.add("| Group | Items | Engine OK | Exact measures | Edit rate | Note F1 | Onset F1 | Lyrics | Chords | s/page |" + qaHead);
        lines.add("|---|---|---|---|---|---|---|---|---|---|" + (withQa ? "---|---|" : "") + "---|---|");
        summary.forEach((key, row) -> {
            String qaCells = withQa ? " " + fmt(row.get("compiles"), true) + " | " + fmt(row.get("bar_checks_clean"), true) + " |" : "";
            Object ece = ((Map<String, Object>) row.get("calibration")).get("ece");
            qaCells += " " + fmt(row.get("box_coverage"), true) + " | " + (ece == null ? "-" : Py.fixed(((Number) ece).doubleValue(), 3)) + " |";
            lines.add("| " + key + " | " + row.get("items") + " | " + row.get("engine_ok") + " | " + fmt(row.get("measure_accuracy"), true)
                    + " | " + fmt(row.get("edit_rate"), true) + " | " + fmt(row.get("note_f1"), true) + " | " + fmt(row.get("onset_f1"), true)
                    + " | " + fmt(row.get("lyric_accuracy"), true) + " | " + fmt(row.get("chord_accuracy"), true)
                    + " | " + fmt(row.get("mean_wall_s"), false) + " |" + qaCells);
        });
        lines.add("");
        lines.add("Exact measures: share of ground-truth measures reproduced exactly (pitch, duration, voices). Edit rate: event edits "
                + "per ground-truth event (lower is better). Onset F1 ignores durations. Boxes: engine events located on the page from "
                + "the .omr (Stage 3). ECE: expected calibration error of the event confidence, the engine's grades or, with repairs, "
                + "Lilyscan's calibrated confidence (lower is better).");
        lines.add("");
        List<ItemResult> repaired = results.stream().filter(r -> !r.repairs.isEmpty()).toList();
        if (!repaired.isEmpty()) {
            lines.addAll(List.of("## Repairs", "", "| Item | Rule | Detail |", "|---|---|---|"));
            for (ItemResult r : repaired) {
                for (Map<String, Object> rep : r.repairs) lines.add("| " + r.item.id() + "/" + r.variant + " | " + rep.get("rule") + " | " + rep.get("detail") + " |");
            }
            lines.add("");
        }
        Map<String, Object> all = summary.get("all");
        List<Map<String, Object>> bands = all == null ? List.of()
                : (List<Map<String, Object>>) ((Map<String, Object>) all.get("calibration")).get("bands");
        if (bands.stream().anyMatch(b -> ((Number) b.get("events")).intValue() != 0)) {
            lines.addAll(List.of("## Confidence calibration (all items)", "", "| Confidence | Events | Accuracy | Mean confidence |", "|---|---|---|---|"));
            for (Map<String, Object> b : bands) {
                if (((Number) b.get("events")).intValue() != 0) {
                    lines.add("| " + b.get("band") + " | " + b.get("events") + " | " + fmt(b.get("accuracy"), true) + " | "
                            + fmt(b.get("mean_confidence"), true) + " |");
                }
            }
            lines.add("");
        }
        Path path = outDir.resolve("summary.md");
        write(path, String.join("\n", lines));
        return path;
    }

    static void write(Path p, String text) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
