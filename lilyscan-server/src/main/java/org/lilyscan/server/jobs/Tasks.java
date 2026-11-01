package org.lilyscan.server.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.lilyscan.core.Pipeline;
import org.lilyscan.core.ingest.Book;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.lilypond.Generate;
import org.lilyscan.core.omr.Geometry;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.omr.OmrException;
import org.lilyscan.core.omr.Overlay;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.util.Py;
import org.lilyscan.engine.audiveris.AudiverisRun;
import org.lilyscan.engine.audiveris.AudiverisRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A job's steps (port of {@code services/lilyscan_app/tasks.py} and the job half of
 * {@code lilyscan/pipeline.py}): prepare the inputs, transcribe them, then import, generate and
 * check. Each step records its own failure on the job.
 *
 * <p>Not yet ported: the Stage 4 and 5 corrections (the PDF oracle, the repair rules and the
 * calibrated confidence, JM4), so {@code repairs} is empty and the confidence is Audiveris's;
 * and Stage 1 page preparation (JM5), see {@link Book}.
 */
public final class Tasks {

    /** The engine, as the steps use it (a seam for tests). */
    public interface Engine {
        AudiverisRun transcribe(List<Path> inputs, Path outDir, Map<String, String> constants, String ocrLanguages,
                Integer pages);

        OmrBook readOmr(Path omr);

        double timeout(Integer pages);
    }

    /** Audiveris in forked worker JVMs (D16). */
    public static Engine audiveris(Settings s) {
        return new Engine() {
            @Override
            public AudiverisRun transcribe(List<Path> inputs, Path outDir, Map<String, String> constants,
                    String ocrLanguages, Integer pages) {
                return AudiverisRunner.run(inputs, outDir, constants, null, s, ocrLanguages, pages);
            }

            @Override
            public OmrBook readOmr(Path omr) {
                return AudiverisRunner.readOmr(omr, s);
            }

            @Override
            public double timeout(Integer pages) {
                return AudiverisRunner.engineTimeout(s, pages);
            }
        };
    }

    private static final Logger log = LoggerFactory.getLogger(Tasks.class);
    private static final ObjectMapper MAPPER = IrJson.mapper();

    private final Settings settings;
    private final JobStore store;
    private final Engine engine;

    public Tasks(Settings settings, JobStore store, Engine engine) {
        this.settings = settings;
        this.store = store;
        this.engine = engine;
    }

    /** The steps of a new job, in order. */
    public List<Dispatcher.Step> transcription() {
        return List.of(this::prepareInputs, this::engineTranscribe, this::pipelineFinish);
    }

    private interface Body {
        void run(JobStore.Job job, Path root) throws Exception;
    }

    private void step(String jobId, String name, Body body) throws Exception {
        try {
            JobStore.Job job = store.get(jobId);
            if (job == null) throw new IllegalArgumentException("unknown job " + jobId);
            body.run(job, store.jobDir(jobId));
        } catch (Exception | Error e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            store.update(jobId, JobStore.Status.FAILED, null, name + ": " + msg, null);
            log.error("{} {} failed", name, jobId, e);
            throw e;
        }
    }

    /** {@code json.dumps(data, indent=2, sort_keys=True, default=str)}. */
    static void writeJson(Path path, Object data) {
        write(path, PyJson.write(sortKeys(MAPPER.valueToTree(data)), PyJson.Style.jsonDumps(2)));
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node instanceof ObjectNode o) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            o.fields().forEachRemaining(e -> sorted.put(e.getKey(), sortKeys(e.getValue())));
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node instanceof ArrayNode a) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            a.forEach(n -> out.add(sortKeys(n)));
            return out;
        }
        return node;
    }

    private static void write(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode readJson(Path path) {
        try {
            return MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String rel(Path root, Path p) {
        return root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** Stage 0 (and Stage 1 from JM5): every page of the job, in order, as one book for the engine. */
    public void prepareInputs(String jobId) throws Exception {
        step(jobId, "prepare", (job, root) -> {
            store.update(jobId, JobStore.Status.RUNNING, "prepare", null, null);
            List<Map<String, Object>> pages = new ArrayList<>();
            if (job.options().path("prepare").asBoolean(true)) {
                List<Path> inputs = job.inputs().stream().map(n -> root.resolve("input").resolve(n)).toList();
                pages = Book.assemble(inputs, root.resolve("prepared")).pages;
            }
            writeJson(root.resolve("prepared").resolve("report.json"), pages);
        });
    }

    private static boolean scanLike(Path root) {
        Path report = root.resolve("prepared").resolve("report.json");
        if (!Files.isRegularFile(report)) return false;
        for (JsonNode p : readJson(report)) {
            if (p.has("page_found") && p.get("page_found").isBoolean() && !p.get("page_found").asBoolean()) return true;
        }
        return false;
    }

    private record Candidate(String label, List<Path> inputs, String out) {}

    private record Ran(AudiverisRun run, Map<String, Object> summary) {}

    /** Stage 2: Audiveris on the job's pages (both forms of a scan, keeping the better run). */
    public void engineTranscribe(String jobId) throws Exception {
        step(jobId, "engine", (job, root) -> {
            store.update(jobId, JobStore.Status.RUNNING, "engine", null, null);
            Path pages = root.resolve("prepared/pages.tif"), asUploaded = root.resolve("prepared/uploaded.tif");
            List<Candidate> candidates = new ArrayList<>();
            if (Files.isRegularFile(pages)) {
                candidates.add(new Candidate("prepared", List.of(pages), "engine"));
                if (Files.isRegularFile(asUploaded) && scanLike(root)) {
                    candidates.add(new Candidate("uploaded", List.of(asUploaded), "engine/uploaded"));
                }
            } else {
                candidates.add(new Candidate("uploaded",
                        job.inputs().stream().map(n -> root.resolve("input").resolve(n)).toList(), "engine"));
            }
            Map<String, String> constants = new LinkedHashMap<>();
            job.options().path("audiveris_constants").fields()
                    .forEachRemaining(e -> constants.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText()
                            : e.getValue().toString()));
            JsonNode ocr = job.options().get("ocr_languages");
            String ocrLanguages = ocr == null || ocr.isNull() ? null : ocr.asText();
            Path report = root.resolve("prepared/report.json");
            int counted = Files.isRegularFile(report) ? readJson(report).size() : 0;
            int pageCount = counted > 0 ? counted : job.inputs().size();

            List<Ran> runs = new ArrayList<>();
            for (Candidate c : candidates) {
                AudiverisRun run = engine.transcribe(c.inputs(), root.resolve(c.out()), constants, ocrLanguages, pageCount);
                List<Integer> skipped = new ArrayList<>();
                // One page Audiveris cannot read fails the whole book: read the others without it.
                if (!run.ok() && !run.timedOut() && c.inputs().size() == 1 && !run.failedSheets().isEmpty()) {
                    List<Integer> keep = new ArrayList<>();
                    for (int n = 1; n <= pageCount; n++) if (!run.failedSheets().contains(n)) keep.add(n);
                    if (!keep.isEmpty()) {
                        skipped = run.failedSheets();
                        Path kept = Book.withoutPages(c.inputs().get(0), keep, root.resolve(c.out()).resolve("kept"));
                        run = engine.transcribe(List.of(kept), root.resolve(c.out()), constants, ocrLanguages, pageCount);
                    }
                }
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("pages", c.label());
                summary.put("command", run.command());
                summary.put("returncode", run.returncode());
                summary.put("timed_out", run.timedOut());
                summary.put("wall_s", Py.round(run.wallS(), 2));
                summary.put("omr_files", run.omrFiles().stream().map(p -> rel(root, p)).toList());
                summary.put("mxl_files", run.mxlFiles().stream().map(p -> rel(root, p)).toList());
                summary.put("ocr_problems", run.ocrProblems());
                summary.put("step_errors", run.stepErrors());
                summary.put("skipped_sheets", skipped);
                runs.add(new Ran(run, summary));
            }
            Ran chosen = choose(root, runs);
            writeJson(root.resolve("engine/run.json"), chosen.summary());
            AudiverisRun run = chosen.run();
            if (!run.ocrProblems().isEmpty()) {
                // A deployment fault: the output would silently lack lyrics and text.
                throw new IllegalStateException("Audiveris ran without working OCR for the requested languages ("
                        + String.join("; ", run.ocrProblems()) + "); check LILYSCAN_OCR_LANGUAGES and "
                        + "TESSDATA_PREFIX, and that the models include the legacy engine");
            }
            if (!run.ok()) {
                String reason = run.stepErrors().isEmpty() ? "exit " + run.returncode() : String.join("; ", run.stepErrors());
                if (run.timedOut()) reason = String.format(java.util.Locale.ROOT, "timed out after %.0fs", engine.timeout(pageCount));
                throw new IllegalStateException("Audiveris produced no MusicXML (" + reason + "); see engine/audiveris.log");
            }
        });
    }

    /** The run expected to have the most events right; the others are listed in it. */
    private Ran choose(Path root, List<Ran> runs) {
        if (runs.size() == 1) return runs.get(0);
        double best = Double.NEGATIVE_INFINITY;
        Ran chosen = null;
        List<Double> expected = new ArrayList<>();
        for (Ran r : runs) {
            double e = r.run().ok() ? assessEngineRun(root, r.summary()) : -1.0;
            expected.add(e);
            if (e > best) {
                best = e;
                chosen = r;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>(chosen.summary());
        List<Map<String, Object>> alternatives = new ArrayList<>();
        for (int k = 0; k < runs.size(); k++) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("pages", runs.get(k).summary().get("pages"));
            a.put("ok", runs.get(k).run().ok());
            a.put("expected_right", expected.get(k));
            a.put("chosen", runs.get(k) == chosen);
            alternatives.add(a);
        }
        summary.put("alternatives", alternatives);
        return new Ran(chosen.run(), summary);
    }

    /** The events Lilyscan expects to be right in a run: the sum of its confidences. */
    private double assessEngineRun(Path root, Map<String, Object> engineRun) {
        Imported imported = importEngineOutput(root, MAPPER.valueToTree(engineRun));
        double sum = 0;
        for (Score.PartStaff ps : imported.score().staves()) {
            for (Measure m : ps.staff().measures) {
                for (Voice v : m.voices) {
                    for (Event e : v.events) if (e.confidence != null) sum += e.confidence;
                }
            }
        }
        return Py.round(sum, 2);
    }

    /** The imported score, its geometry report (null without an {@code .omr}) and the book read. */
    record Imported(Score score, Map<String, Object> geometry, OmrBook book) {}

    /** Stage 3: the engine's MusicXML as IR, with {@code .omr} boxes and confidence attached. */
    Imported importEngineOutput(Path root, JsonNode engineRun) {
        List<Path> paths = new ArrayList<>();
        engineRun.path("mxl_files").forEach(p -> paths.add(root.resolve(p.asText())));
        List<String> omrFiles = new ArrayList<>();
        engineRun.path("omr_files").forEach(p -> omrFiles.add(p.asText()));
        if (omrFiles.isEmpty()) return new Imported(Pipeline.importMusicXmlFiles(paths, "audiveris"), null, null);
        OmrBook book;
        try {
            book = engine.readOmr(root.resolve(omrFiles.get(0)));
        } catch (OmrException e) {
            Map<String, Object> geometry = new LinkedHashMap<>();
            geometry.put("source", omrFiles.get(0));
            geometry.put("error", e.getMessage());
            return new Imported(Pipeline.importMusicXmlFiles(paths, "audiveris"), geometry, null);
        }
        if (paths.isEmpty()) throw new IllegalArgumentException("no MusicXML files to import");
        List<Score> movements = new ArrayList<>();
        for (Path p : paths) movements.add(MusicXml.load(p, "audiveris"));
        Geometry.Attached attached = Geometry.attachMovements(movements, book);
        var stats = attached.stats();
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("source", omrFiles.get(0));
        geometry.put("audiveris", book.softwareVersion());
        List<Map<String, Object>> pages = new ArrayList<>();
        for (OmrBook.OmrSheet s : book.sheets()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("sheet", s.number());
            p.put("width", s.width());
            p.put("height", s.height());
            p.put("interline", s.interline());
            pages.add(p);
        }
        geometry.put("pages", pages);
        geometry.put("events", stats.events);
        geometry.put("mappable_events", stats.mappable);
        geometry.put("located_events", stats.located);
        geometry.put("located_rate", Py.round(stats.locatedRate(), 4));
        geometry.put("pitch_mismatches", stats.pitchMismatch);
        geometry.put("measures", stats.measures);
        geometry.put("located_measures", stats.measuresLocated);
        geometry.put("unmapped_staves", stats.unmappedStaves);
        geometry.put("confidence", "audiveris");
        geometry.put("low_confidence_events", lowConfidence(attached.score()));
        geometry.put("overlays", List.of());
        return new Imported(attached.score(), geometry, book);
    }

    private static int lowConfidence(Score score) {
        int n = 0;
        for (Score.PartStaff ps : score.staves()) {
            for (Measure m : ps.staff().measures) {
                for (Voice v : m.voices) {
                    for (Event e : v.events) if (e.confidence != null && e.confidence < Generate.LOW_CONFIDENCE) n++;
                }
            }
        }
        return n;
    }

    /**
     * Stages 4 and 5 (the corrections arrive with JM4): returns the repair log, then draws the
     * page overlays so their colours show the final confidence.
     */
    List<Map<String, Object>> repairEngineOutput(Imported imported, Path root) {
        List<Map<String, Object>> repairs = new ArrayList<>();
        Map<String, Object> geometry = imported.geometry();
        if (geometry == null || geometry.containsKey("error") || imported.book() == null) return repairs;
        Path omr = root.resolve((String) geometry.get("source"));
        List<Path> written = Overlay.render(imported.score(), imported.book(), omr, root.resolve("overlays"));
        geometry.put("overlays", written.stream().map(p -> rel(root, p)).toList());
        return repairs;
    }

    /** Stages 3 and 5-8: import the engine's output, repair it, generate LilyPond, run QA. */
    public void pipelineFinish(String jobId) throws Exception {
        step(jobId, "pipeline", (job, root) -> {
            store.update(jobId, JobStore.Status.RUNNING, "import", null, null);
            JsonNode engineRun = readJson(root.resolve("engine/run.json"));
            Imported imported = importEngineOutput(root, engineRun);
            store.update(jobId, null, "repair", null, null);
            List<Map<String, Object>> repairs = repairEngineOutput(imported, root);
            store.update(jobId, null, "lilypond", null, null);
            Path prepared = root.resolve("prepared/report.json");
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("prepare", Files.isRegularFile(prepared) ? readJson(prepared) : List.of());
            report.put("engine", engineRun);
            report.put("geometry", imported.geometry());
            report.put("repairs", repairs);
            report.putAll(Pipeline.produce(imported.score(), root, settings, null));
            writeJson(root.resolve("report.json"), report);
            store.update(jobId, JobStore.Status.DONE, "done", null, MAPPER.valueToTree(report));
        });
    }

    /** After the user edits the LilyPond project: recompile, re-render, refresh the review. */
    public void recompileJob(String jobId) throws Exception {
        step(jobId, "recompile", (job, root) -> {
            store.update(jobId, JobStore.Status.RUNNING, "recompile", null, null);
            Map<String, Object> report = Pipeline.recompile(root, settings);
            store.update(jobId, JobStore.Status.DONE, "done", null, MAPPER.valueToTree(report));
        });
    }
}
