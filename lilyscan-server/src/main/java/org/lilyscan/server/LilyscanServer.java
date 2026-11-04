package org.lilyscan.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.config.SizeUnit;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.lilyscan.core.Pipeline;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.lilypond.Generate;
import org.lilyscan.core.lilypond.SingleFile;
import org.lilyscan.core.runtime.Config;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.server.jobs.Dispatcher;
import org.lilyscan.server.jobs.JobStore;
import org.lilyscan.server.jobs.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The web server (D17), port of {@code services/lilyscan_app/api.py}: the {@code /api/jobs} API
 * and the {@code services/web/} single-page app, on one process with the SQLite job store (D18)
 * and the in-process dispatcher. Errors are {@code {"detail": ...}} as FastAPI sends them.
 *
 */
public final class LilyscanServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LilyscanServer.class);
    private static final ObjectMapper MAPPER = IrJson.mapper();

    static final Set<String> ALLOWED_SUFFIXES = Set.of(".pdf", ".png", ".jpg", ".jpeg", ".tif", ".tiff");
    static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;
    static final int MAX_LY_BYTES = 5 * 1024 * 1024;
    private static final Pattern LY_FILE = Pattern.compile("[A-Za-z0-9_./-]+\\.ly");
    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9._-]+");
    private static final Pattern SHEET_IMAGE = Pattern.compile("sheet#(\\d+)/BINARY\\.png");

    /** A FastAPI {@code HTTPException}. */
    static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String detail) {
            super(detail);
            this.status = status;
        }
    }

    private final Settings settings;
    private final JobStore store;
    private final Dispatcher dispatcher = new Dispatcher();
    private final Tasks tasks;
    private Javalin app;

    public LilyscanServer(Settings settings) {
        this(settings, Tasks.audiveris(settings));
    }

    public LilyscanServer(Settings settings, Tasks.Engine engine) {
        this.settings = settings;
        this.store = new JobStore(settings.dataDir());
        this.tasks = new Tasks(settings, store, engine);
    }

    /** Builds and starts the app on {@code port} (0: any free port); returns the port. */
    public int start(int port) {
        Path web = settings.webDir();
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.maxRequestSize = MAX_LY_BYTES + 1;
            config.jetty.multipartConfig.maxFileSize(MAX_UPLOAD_BYTES + 1, SizeUnit.BYTES);
            config.jetty.multipartConfig.maxTotalRequestSize(4 * MAX_UPLOAD_BYTES, SizeUnit.BYTES);
            config.jetty.multipartConfig.maxInMemoryFileSize(1, SizeUnit.MB);
            if (web != null && Files.isRegularFile(web.resolve("index.html"))) {
                config.staticFiles.add(sf -> {
                    sf.hostedPath = "/";
                    sf.directory = web.toString();
                    sf.location = Location.EXTERNAL;
                });
            } else {
                config.staticFiles.add(sf -> {
                    sf.hostedPath = "/";
                    sf.directory = "/web";
                    sf.location = Location.CLASSPATH;
                });
            }
        });
        app.exception(HttpError.class, (e, ctx) -> detail(ctx, e.status, e.getMessage()));
        app.exception(Exception.class, (e, ctx) -> {
            log.error("{} {} failed", ctx.method(), ctx.path(), e);
            detail(ctx, 500, "Internal Server Error");
        });
        routes(app);
        app.start(port);
        log.info("Lilyscan server listening on port {} (data dir {})", app.port(), settings.dataDir());
        return app.port();
    }

    @Override
    public void close() {
        if (app != null) app.stop();
        dispatcher.close();
        store.close();
    }

    private static void detail(Context ctx, int status, String detail) {
        ctx.status(status).json(Map.of("detail", detail));
    }

    private void routes(Javalin app) {
        app.get("/api/health", ctx -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("status", "ok");
            h.put("lilypond", Config.LILYPOND_VERSION);
            h.put("audiveris", Config.AUDIVERIS_VERSION);
            h.put("ocr_languages", settings.ocrLanguages());
            ctx.json(h);
        });
        app.get("/api/jobs/{id}/parts", ctx -> ctx.json(parts(jobRoot(ctx.pathParam("id")))));
        app.post("/api/jobs/{id}/rerun", this::rerun);
        app.post("/api/scores", this::createScore);
        app.post("/api/jobs", this::createJob);
        app.get("/api/jobs", ctx -> {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            store.list(100).forEach(j -> out.add(j.toJson()));
            ctx.json(out);
        });
        app.get("/api/jobs/{id}", ctx -> ctx.json(job(ctx.pathParam("id")).toJson()));
        app.get("/api/jobs/{id}/files/<path>", this::getFile);
        app.get("/api/jobs/{id}/pages/{page}.png", this::getPage);
        app.get("/api/jobs/{id}/review", this::getReview);
        app.get("/api/jobs/{id}/ly/<path>", ctx -> {
            Path target = lyPath(jobRoot(ctx.pathParam("id")), ctx.pathParam("path"));
            if (!Files.isRegularFile(target)) throw new HttpError(404, "file not found");
            ctx.contentType("text/plain; charset=utf-8").result(Files.readString(target, StandardCharsets.UTF_8));
        });
        app.put("/api/jobs/{id}/ly/<path>", this::writeLy);
        app.post("/api/jobs/{id}/recompile", ctx -> {
            String id = ctx.pathParam("id");
            Path root = jobRoot(id);
            notBusy(id, "the job is busy");
            if (!Files.isRegularFile(root.resolve("report.json"))) throw new HttpError(409, "nothing to recompile yet");
            queueRecompile(ctx, id);
        });
        app.post("/api/jobs/{id}/drop-lyrics", this::dropLyrics);
        app.get("/api/jobs/{id}/download/{kind}", this::download);
    }

    // --- helpers -------------------------------------------------------------------------

    private JobStore.Job job(String id) {
        JobStore.Job job = store.get(id);
        if (job == null) throw new HttpError(404, "job not found");
        return job;
    }

    private Path jobRoot(String id) {
        job(id);
        return store.jobDir(id).toAbsolutePath().normalize();
    }

    private void notBusy(String id, String message) {
        JobStore.Job job = store.get(id);
        if (job != null && (job.status() == JobStore.Status.QUEUED || job.status() == JobStore.Status.RUNNING)) {
            throw new HttpError(409, message);
        }
    }

    private static JsonNode readJson(Path p) {
        try {
            return MAPPER.readTree(p.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Map<String, Object>> parts(Path root) {
        Path path = root.resolve("ir/score.json");
        if (!Files.isRegularFile(path)) throw new HttpError(404, "no finished score for this job");
        var score = IrJson.readScore(path);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var p : score.parts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id);
            m.put("name", p.name == null || p.name.isEmpty() ? p.id : p.name);
            m.put("staves", p.staves.size());
            m.put("measures", p.staves.isEmpty() ? 0 : p.staves.get(0).measures.size());
            m.put("transpose_semitones", p.transposeSemitones);
            out.add(m);
        }
        return out;
    }

    private static String suffix(String name) {
        String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        int dot = base.lastIndexOf('.');
        return dot <= 0 ? "" : base.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String stem(String name) {
        String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        int dot = base.lastIndexOf('.');
        return dot <= 0 ? base : base.substring(0, dot);
    }

    /** Pydantic's lax bool parsing of a form field. */
    static boolean formBool(String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "1", "true", "t", "yes", "y", "on" -> true;
            case "0", "false", "f", "no", "n", "off" -> false;
            default -> throw new HttpError(422, "straighten: input should be a valid boolean");
        };
    }

    private static String ocrLanguages(String spec) {
        try {
            return Config.parseOcrLanguages(spec);
        } catch (IllegalArgumentException e) {
            throw new HttpError(422, e.getMessage());
        }
    }

    private void submit(String id) {
        dispatcher.run(id, tasks.transcription());
    }

    // --- endpoints -----------------------------------------------------------------------

    private void createJob(Context ctx) throws IOException {
        ObjectNode options = JsonNodeFactory.instance.objectNode();
        String straighten = ctx.formParam("straighten");
        options.put("prepare", straighten == null || formBool(straighten));
        String ocr = ctx.formParam("ocr_languages");
        if (ocr != null && !ocr.isEmpty()) options.put("ocr_languages", ocrLanguages(ocr));

        List<UploadedFile> files = ctx.uploadedFiles("files");
        if (files.isEmpty()) throw new HttpError(422, "files: field required");
        // Validate every file before creating the job, so a rejected upload never leaves a
        // half-written job behind.
        List<String> names = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            UploadedFile f = files.get(i);
            String filename = f.filename() == null ? "" : f.filename();
            String suffix = suffix(filename);
            if (!ALLOWED_SUFFIXES.contains(suffix)) throw new HttpError(415, "unsupported file type: '" + filename + "'");
            if (f.size() > MAX_UPLOAD_BYTES) throw new HttpError(413, "'" + filename + "' exceeds the upload limit");
            String s = SAFE.matcher(filename.isEmpty() ? "upload" : stem(filename)).replaceAll("_");
            s = s.substring(0, Math.min(60, s.length()));
            names.add(String.format(Locale.ROOT, "%02d-%s%s", i, s.isEmpty() ? "upload" : s, suffix));
        }
        JobStore.Job job = store.create(names, options);
        Path input = store.jobDir(job.id()).resolve("input");
        Files.createDirectories(input);
        for (int i = 0; i < files.size(); i++) {
            try (InputStream in = files.get(i).content()) {
                Files.copy(in, input.resolve(names.get(i)));
            }
        }
        submit(job.id());
        ctx.status(201).json(job.toJson());
    }

    /** A new score from parts of finished jobs, built by the dispatcher (the combiner). */
    private void createScore(Context ctx) throws IOException {
        JsonNode body = ctx.body().isBlank() ? JsonNodeFactory.instance.objectNode() : MAPPER.readTree(ctx.body());
        if (!body.path("parts").isArray()) throw new HttpError(422, "parts: field required");
        if (body.get("parts").isEmpty()) throw new HttpError(422, "choose at least one part");
        ObjectNode spec = JsonNodeFactory.instance.objectNode();
        JsonNode title = body.get("title");
        if (title == null || title.isNull()) spec.putNull("title"); else spec.put("title", title.asText());
        ArrayNode parts = spec.putArray("parts"), piano = spec.putArray("piano");
        List<JsonNode> all = new ArrayList<>();
        body.get("parts").forEach(all::add);
        int count = all.size();
        body.path("piano").forEach(all::add);
        for (int i = 0; i < all.size(); i++) {
            JsonNode p = all.get(i);
            if (!p.path("job").isTextual() || !p.path("part").isTextual()) throw new HttpError(422, "job and part: field required");
            String job = p.get("job").asText(), part = p.get("part").asText();
            JobStore.Job source = store.get(job);
            if (source == null) throw new HttpError(404, "job " + job + " not found");
            if (source.status() != JobStore.Status.DONE) throw new HttpError(409, "job " + job + " is not finished");
            boolean has = parts(store.jobDir(job).toAbsolutePath().normalize()).stream().anyMatch(x -> part.equals(x.get("id")));
            if (!has) throw new HttpError(422, "job " + job + " has no part " + part);
            String transpose = p.path("transpose").isTextual() ? p.get("transpose").asText() : null;
            if (transpose != null && !transpose.isEmpty() && !transpose.equals("concert")) {
                try {
                    org.lilyscan.core.ir.Transpose.Interval.parse(transpose);
                } catch (IllegalArgumentException e) {
                    throw new HttpError(422, e.getMessage());
                }
            }
            ObjectNode sel = (i < count ? parts : piano).addObject();
            sel.put("job", job);
            sel.put("part", part);
            if (transpose == null) sel.putNull("transpose"); else sel.put("transpose", transpose);
            if (p.path("name").isTextual()) sel.put("name", p.get("name").asText()); else sel.putNull("name");
        }
        ObjectNode options = JsonNodeFactory.instance.objectNode();
        options.set("combine", spec);
        JobStore.Job job = store.create(List.of(), options);
        dispatcher.run(job.id(), List.of(tasks::combineJob));
        ctx.status(201).json(job.toJson());
    }

    private void rerun(Context ctx) throws IOException {
        String id = ctx.pathParam("id");
        Path root = jobRoot(id);
        JobStore.Job old = job(id);
        if (old.options().path("combine").isObject() || old.inputs().isEmpty()) {
            throw new HttpError(409, "a combined score has no uploads to transcribe again");
        }
        JsonNode body = ctx.body().isBlank() ? JsonNodeFactory.instance.objectNode() : MAPPER.readTree(ctx.body());
        ObjectNode options = old.options().deepCopy();
        options.remove("rerun_of");
        JsonNode ocr = body.get("ocr_languages");
        if (ocr != null && !ocr.isNull()) options.put("ocr_languages", ocrLanguages(ocr.asText()));
        JsonNode straighten = body.get("straighten");
        if (straighten != null && !straighten.isNull()) options.put("prepare", straighten.asBoolean());
        options.put("rerun_of", id);
        JobStore.Job job = store.create(old.inputs(), options);
        Path target = store.jobDir(job.id()).resolve("input");
        Files.createDirectories(target);
        for (String name : old.inputs()) Files.copy(root.resolve("input").resolve(name), target.resolve(name));
        submit(job.id());
        ctx.status(201).json(job.toJson());
    }

    private static String contentType(Path p) {
        return switch (suffix(p.getFileName().toString())) {
            case ".json" -> "application/json";
            case ".svg" -> "image/svg+xml";
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".tif", ".tiff" -> "image/tiff";
            case ".pdf" -> "application/pdf";
            case ".midi", ".mid" -> "audio/midi";
            case ".ly", ".log", ".txt" -> "text/plain; charset=utf-8";
            case ".musicxml", ".xml" -> "application/xml";
            case ".mxl", ".omr", ".zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    private void getFile(Context ctx) throws IOException {
        String id = ctx.pathParam("id");
        job(id);
        Path root = store.jobDir(id).toAbsolutePath().normalize();
        Path target = root.resolve(ctx.pathParam("path")).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) throw new HttpError(404, "file not found");
        ctx.contentType(contentType(target)).result(Files.newInputStream(target));
    }

    private static JsonNode engineRun(Path root) {
        Path path = root.resolve("engine/run.json");
        return Files.isRegularFile(path) ? readJson(path) : JsonNodeFactory.instance.objectNode();
    }

    private void getPage(Context ctx) throws IOException {
        Path root = jobRoot(ctx.pathParam("id"));
        int page;
        try {
            page = Integer.parseInt(ctx.pathParam("page"));
        } catch (NumberFormatException e) {
            throw new HttpError(422, "page: input should be a valid integer");
        }
        JsonNode omr = engineRun(root).get("omr_files");
        if (omr == null || omr.isEmpty()) throw new HttpError(404, "no engine project for this job");
        byte[] data;
        try (ZipFile z = new ZipFile(root.resolve(omr.get(0).asText()).toFile())) {
            List<Integer> sheets = new ArrayList<>();
            z.stream().forEach(e -> {
                Matcher m = SHEET_IMAGE.matcher(e.getName());
                if (m.matches()) sheets.add(Integer.parseInt(m.group(1)));
            });
            sheets.sort(null);
            if (page < 1 || page > sheets.size()) throw new HttpError(404, "no such page");
            try (InputStream in = z.getInputStream(z.getEntry("sheet#" + sheets.get(page - 1) + "/BINARY.png"))) {
                data = in.readAllBytes();
            }
        }
        ctx.header("Cache-Control", "max-age=3600").contentType("image/png").result(data);
    }

    private void getReview(Context ctx) {
        Path root = jobRoot(ctx.pathParam("id"));
        Path reviewPath = root.resolve("review.json"), reportPath = root.resolve("report.json");
        if (!Files.isRegularFile(reviewPath) || !Files.isRegularFile(reportPath)) throw new HttpError(404, "no review yet");
        JsonNode report = readJson(reportPath);
        ObjectNode review = (ObjectNode) readJson(reviewPath);
        review.set("qa", report.get("qa"));
        review.set("lilypond", report.get("lilypond"));
        JsonNode geometry = report.get("geometry");
        review.set("pages", geometry != null && geometry.has("pages") ? geometry.get("pages")
                : JsonNodeFactory.instance.arrayNode());
        review.set("prepare", report.has("prepare") ? report.get("prepare") : JsonNodeFactory.instance.arrayNode());
        JsonNode engine = report.get("engine");
        review.set("alternatives", engine != null && engine.has("alternatives") ? engine.get("alternatives")
                : JsonNodeFactory.instance.arrayNode());
        review.set("combined_from", report.get("combined_from"));
        ctx.json(review);
    }

    private static Path lyPath(Path root, String path) {
        Path lyRoot = root.resolve("ly").normalize();
        Path target = lyRoot.resolve(path).normalize();
        boolean editable = LY_FILE.matcher(path).matches() && target.startsWith(lyRoot);
        if (editable) {
            for (Path part : lyRoot.relativize(target)) if (part.toString().equals("svg")) editable = false;
        }
        if (!editable) throw new HttpError(404, "not an editable LilyPond file");
        return target;
    }

    private void writeLy(Context ctx) throws IOException {
        String id = ctx.pathParam("id");
        Path root = jobRoot(id);
        notBusy(id, "the job is busy; try again when it has finished");
        Path target = lyPath(root, ctx.pathParam("path"));
        if (!Files.isRegularFile(target)) throw new HttpError(404, "file not found");
        byte[] body = ctx.bodyAsBytes();
        if (body.length > MAX_LY_BYTES) throw new HttpError(413, "file too large");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            throw new HttpError(422, "LilyPond source must be UTF-8");
        }
        // Python's write_text(newline="\n") writes the text as is.
        Files.writeString(target, text, StandardCharsets.UTF_8);
        ctx.status(204);
    }

    private void queueRecompile(Context ctx, String id) {
        store.update(id, JobStore.Status.QUEUED, "recompile", null, null);
        dispatcher.run(id, List.of(tasks::recompileJob));
        ctx.status(202).json(job(id).toJson());
    }

    private void dropLyrics(Context ctx) throws IOException {
        String id = ctx.pathParam("id");
        Path root = jobRoot(id);
        notBusy(id, "the job is busy");
        Path reportPath = root.resolve("report.json");
        if (!Files.isRegularFile(reportPath)) throw new HttpError(409, "nothing to edit yet");
        JsonNode body = ctx.body().isBlank() ? JsonNodeFactory.instance.objectNode() : MAPPER.readTree(ctx.body());
        if (!body.path("part").isTextual()) throw new HttpError(422, "part: field required");
        String part = body.get("part").asText();
        JsonNode found = null;
        for (JsonNode c : readJson(reportPath).path("qa").path("checks")) {
            if (c.path("id").asText().equals("Q9")) {
                for (JsonNode d : c.path("details")) {
                    if (d.path("part").asText().equals(part)) {
                        found = d;
                        break;
                    }
                }
                break;
            }
        }
        if (found == null) throw new HttpError(404, "no lyrics to drop in this part");
        Set<String> voices = new HashSet<>();
        found.path("voices").forEach(v -> voices.add(v.asText()));
        Path layout = root.resolve("ly/layout");
        if (Files.isDirectory(layout)) {
            try (var list = Files.list(layout)) {
                for (Path p : list.filter(p -> p.getFileName().toString().endsWith(".ly")).sorted().toList()) {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    String edited = Generate.dropLyrics(text, voices);
                    if (!edited.equals(text)) Files.writeString(p, edited, StandardCharsets.UTF_8);
                }
            }
        }
        queueRecompile(ctx, id);
    }

    /** The score title as a plain file name (LilyPond names its output files after it). */
    static String fileName(String title) {
        String name = (title == null ? "" : title).replaceAll("[^A-Za-z0-9 ._'-]+", "");
        int a = 0, b = name.length();
        while (a < b && (name.charAt(a) == ' ' || name.charAt(a) == '.')) a++;
        while (b > a && (name.charAt(b - 1) == ' ' || name.charAt(b - 1) == '.')) b--;
        name = name.substring(a, b).replaceAll("\\s+", " ");
        name = name.substring(0, Math.min(80, name.length()));
        return name.isEmpty() ? null : name;
    }

    private static String title(Path root) {
        Path path = root.resolve("ir/score.json");
        if (!Files.isRegularFile(path)) return null;
        JsonNode t = readJson(path).get("title");
        return t != null && t.isTextual() ? t.asText() : null;
    }

    /** Files under {@code ly/} outside {@code svg/}, sorted as Python sorts paths. */
    private static TreeMap<String, Path> lyTree(Path lyRoot, boolean onlyLy) throws IOException {
        TreeMap<String, Path> out = new TreeMap<>(Comparator.comparing((String s) -> List.of(s.split("/")),
                (x, y) -> {
                    for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                        int c = x.get(i).compareTo(y.get(i));
                        if (c != 0) return c;
                    }
                    return Integer.compare(x.size(), y.size());
                }));
        if (!Files.isDirectory(lyRoot)) return out;
        try (var walk = Files.walk(lyRoot)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path rel = lyRoot.relativize(p);
                boolean svg = false;
                for (Path part : rel) if (part.toString().equals("svg")) svg = true;
                if (svg || (onlyLy && !p.getFileName().toString().endsWith(".ly"))) continue;
                out.put(rel.toString().replace('\\', '/'), p);
            }
        }
        return out;
    }

    private void download(Context ctx) throws IOException {
        String id = ctx.pathParam("id");
        String kind = ctx.pathParam("kind");
        Path root = jobRoot(id);
        Path lyRoot = root.resolve("ly");
        if (kind.equals("ly")) {
            // One file engraving the score and, when there are several, each part: built from
            // the project as it is now, so edits made in the review are in it.
            Map<String, String> files = new LinkedHashMap<>();
            for (var e : lyTree(lyRoot, true).entrySet()) files.put(e.getKey(), Files.readString(e.getValue(), StandardCharsets.UTF_8));
            if (!files.containsKey("main.ly")) throw new HttpError(404, "no LilyPond source for this job");
            String name = fileName(title(root));
            if (name == null) name = "lilyscan-" + id;
            ctx.header("Content-Disposition", "attachment; filename=\"" + name + ".ly\"")
                    .contentType("text/x-lilypond; charset=utf-8").result(SingleFile.singleFile(files));
            return;
        }
        if (kind.equals("ly-project")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(buffer)) {
                for (var e : lyTree(lyRoot, false).entrySet()) {
                    if (e.getValue().getFileName().toString().equals(Pipeline.SOURCE_MAP)) continue;
                    z.putNextEntry(new ZipEntry("lilyscan-" + id + "/" + e.getKey()));
                    Files.copy(e.getValue(), z);
                    z.closeEntry();
                }
            }
            ctx.header("Content-Disposition", "attachment; filename=\"lilyscan-" + id + ".zip\"")
                    .contentType("application/zip").result(buffer.toByteArray());
            return;
        }
        List<Path> candidates = new ArrayList<>();
        JsonNode run = engineRun(root);
        switch (kind) {
            case "pdf" -> candidates.add(lyRoot.resolve("main.pdf"));
            case "midi" -> candidates.addAll(List.of(lyRoot.resolve("main.midi"), lyRoot.resolve("main.mid")));
            case "musicxml" -> {
                // The score as transcribed and repaired, else the engine's own export.
                candidates.add(root.resolve("score.musicxml"));
                run.path("mxl_files").forEach(n -> candidates.add(root.resolve(n.asText())));
            }
            // The Audiveris project, to open in the Audiveris desktop application.
            case "omr" -> run.path("omr_files").forEach(n -> candidates.add(root.resolve(n.asText())));
            default -> throw new HttpError(404, "unknown download '" + kind + "'");
        }
        Path found = candidates.stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new HttpError(404, "no " + kind + " for this job"));
        String filename = "lilyscan-" + id + suffix(found.getFileName().toString());
        ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .contentType(contentType(found)).result(Files.newInputStream(found));
    }
}
