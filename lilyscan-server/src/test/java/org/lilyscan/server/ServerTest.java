package org.lilyscan.server;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.lilypond.Compile;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.omr.OmrBookJson;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.testing.Repo;
import org.lilyscan.engine.audiveris.AudiverisRun;
import org.lilyscan.server.jobs.Tasks;

/**
 * The API end to end with a stand-in engine that "transcribes" any upload into a committed
 * Audiveris project (piano-two-voices): upload, poll, review, edit, recompile, download.
 */
class ServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURE = Repo.root().resolve("eval/fixtures/omr/5.11.0/piano-two-voices");

    @TempDir Path data;
    private LilyscanServer server;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();

    /** Copies the fixture's project into the run folder, as Audiveris would write it. */
    static final class FakeEngine implements Tasks.Engine {
        @Override
        public AudiverisRun transcribe(List<Path> inputs, Path outDir, Map<String, String> constants,
                String ocrLanguages, Integer pages) {
            try {
                Files.createDirectories(outDir);
                Path omr = Files.copy(FIXTURE.resolve("book.omr"), outDir.resolve("book.omr"));
                Path mxl = Files.copy(FIXTURE.resolve("output.mxl"), outDir.resolve("book.mxl"));
                return new AudiverisRun(List.of("audiveris", "-batch"), 0, 1.0, "", List.of(omr), List.of(mxl), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public OmrBook readOmr(Path omr) {
            return OmrBookJson.load(Repo.readJson(Repo.parity("omr/piano-two-voices.book.json")));
        }

        @Override
        public double timeout(Integer pages) {
            return 60;
        }
    }

    @BeforeEach
    void start() {
        Settings env = Settings.fromEnv();
        Assumptions.assumeTrue(Compile.lilypondVersion(env) != null, "LilyPond not found (set LILYPOND_BIN)");
        Settings s = new Settings(data, env.lilypondBin(), null, env.lilypondTimeoutS(), 60, 1, 60, "eng",
                "inline", null, "1G");
        server = new LilyscanServer(s, new FakeEngine());
        base = "http://127.0.0.1:" + server.start(0);
    }

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.timeout(Duration.ofSeconds(120)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)));
    }

    private static JsonNode json(HttpResponse<byte[]> r) throws Exception {
        return JSON.readTree(r.body());
    }

    private HttpResponse<byte[]> upload(String filename, byte[] content, Map<String, String> fields) throws Exception {
        String boundary = "lilyscanBoundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var f : fields.entrySet()) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey()
                    + "\"\r\n\r\n" + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(URI.create(base + "/api/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    }

    private JsonNode waitFor(String id) throws Exception {
        long until = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < until) {
            JsonNode job = json(get("/api/jobs/" + id));
            String status = job.get("status").asText();
            if (status.equals("done") || status.equals("failed")) return job;
            Thread.sleep(200);
        }
        fail("job " + id + " did not finish");
        return null;
    }

    @Test
    void healthAndWebApp() throws Exception {
        JsonNode h = json(get("/api/health"));
        assertEquals("ok", h.get("status").asText());
        assertEquals("eng", h.get("ocr_languages").asText());
        var index = get("/");
        assertEquals(200, index.statusCode());
        assertTrue(new String(index.body(), StandardCharsets.UTF_8).contains("app.js"));
        assertEquals(200, get("/app.js").statusCode());
    }

    @Test
    void rejectsBadUploadsWithoutCreatingAJob() throws Exception {
        var r = upload("notes.txt", new byte[] {1}, Map.of());
        assertEquals(415, r.statusCode());
        assertEquals("unsupported file type: 'notes.txt'", json(r).get("detail").asText());
        assertEquals(0, json(get("/api/jobs")).size());
        assertEquals(404, get("/api/jobs/nope").statusCode());
        assertEquals("job not found", json(get("/api/jobs/nope")).get("detail").asText());
    }

    @Test
    void uploadTranscribeReviewEditDownload() throws Exception {
        byte[] pdf = Files.readAllBytes(Repo.root().resolve("docs/upstream/audiveris/repro/satb-01.pdf"));
        var created = upload("My Score!.pdf", pdf, Map.of("straighten", "true"));
        assertEquals(201, created.statusCode(), new String(created.body(), StandardCharsets.UTF_8));
        JsonNode job = json(created);
        String id = job.get("id").asText();
        assertEquals("[\"00-My_Score_.pdf\"]", job.get("inputs").toString());
        assertEquals("{\"prepare\":true}", job.get("options").toString());

        job = waitFor(id);
        assertEquals("done", job.get("status").asText(), job.toString());
        JsonNode report = job.get("report");
        assertEquals(List.of("counts", "device", "engine", "geometry", "ir", "lilypond", "musicxml", "prepare", "qa",
                "repairs", "review"), JSON.convertValue(report, Map.class).keySet().stream().sorted().toList());
        assertEquals("vector", report.at("/prepare/0/kind").asText());
        assertEquals("[\"overlays/page-1.png\"]", report.at("/geometry/overlays").toString());
        assertTrue(report.at("/lilypond/svg").size() >= 1);

        JsonNode review = json(get("/api/jobs/" + id + "/review"));
        assertTrue(review.get("measures").size() > 0);
        assertEquals(1, review.get("pages").size());
        assertEquals(200, get("/api/jobs/" + id + "/pages/1.png").statusCode());
        assertEquals(404, get("/api/jobs/" + id + "/pages/2.png").statusCode());
        var svg = get("/api/jobs/" + id + "/files/" + report.at("/lilypond/svg/0").asText());
        assertEquals("image/svg+xml", svg.headers().firstValue("Content-Type").orElse(""));
        assertEquals(404, get("/api/jobs/" + id + "/files/../../jobs.sqlite").statusCode());

        // Edit the source, recompile, and the edit is in the download.
        String main = new String(get("/api/jobs/" + id + "/ly/main.ly").body(), StandardCharsets.UTF_8);
        assertTrue(main.startsWith("\\version"));
        var put = send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/" + id + "/ly/main.ly"))
                .PUT(HttpRequest.BodyPublishers.ofString(main + "% edited\n")));
        assertEquals(204, put.statusCode());
        assertEquals(404, get("/api/jobs/" + id + "/ly/svg/main.ly").statusCode());
        var recompiled = send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/" + id + "/recompile"))
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(202, recompiled.statusCode());
        assertEquals("recompile", json(recompiled).get("stage").asText());
        assertEquals("done", waitFor(id).get("status").asText());

        var ly = get("/api/jobs/" + id + "/download/ly");
        assertEquals(200, ly.statusCode());
        assertTrue(ly.headers().firstValue("Content-Disposition").orElse("").contains(".ly\""));
        var zip = get("/api/jobs/" + id + "/download/ly-project");
        boolean sawMain = false, sawMap = false;
        try (var z = new ZipInputStream(new java.io.ByteArrayInputStream(zip.body()))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                sawMain |= e.getName().equals("lilyscan-" + id + "/main.ly");
                sawMap |= e.getName().endsWith("lilyscan-map.json");
                if (e.getName().endsWith("/main.ly")) {
                    assertTrue(new String(z.readAllBytes(), StandardCharsets.UTF_8).endsWith("% edited\n"));
                }
            }
        }
        assertTrue(sawMain);
        assertFalse(sawMap);
        for (String kind : List.of("pdf", "musicxml", "omr")) {
            assertEquals(200, get("/api/jobs/" + id + "/download/" + kind).statusCode(), kind);
        }
        assertEquals(404, get("/api/jobs/" + id + "/download/nope").statusCode());

        // A rerun is a new job from the same uploads.
        var rerun = send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/" + id + "/rerun"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"straighten\": false}")));
        assertEquals(201, rerun.statusCode());
        JsonNode again = json(rerun);
        assertEquals("{\"prepare\":false,\"rerun_of\":\"" + id + "\"}", again.get("options").toString());
        assertEquals("done", waitFor(again.get("id").asText()).get("status").asText());
        assertEquals(2, json(get("/api/jobs")).size());
    }

    @Test
    void combinePartsOfFinishedJobs() throws Exception {
        byte[] pdf = Files.readAllBytes(Repo.root().resolve("docs/upstream/audiveris/repro/satb-01.pdf"));
        String a = json(upload("a.pdf", pdf, Map.of())).get("id").asText();
        String b = json(upload("b.pdf", pdf, Map.of())).get("id").asText();
        assertEquals("done", waitFor(a).get("status").asText());
        assertEquals("done", waitFor(b).get("status").asText());
        String part = json(get("/api/jobs/" + a + "/parts")).get(0).get("id").asText();

        var bad = send(HttpRequest.newBuilder(URI.create(base + "/api/scores")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"parts\": [{\"job\": \"" + a + "\", \"part\": \"" + part
                        + "\", \"transpose\": \"X9\"}]}")));
        assertEquals(422, bad.statusCode());
        assertEquals("not an interval: 'X9' (expected e.g. M2, -m3, P5)", json(bad).get("detail").asText());
        var missing = send(HttpRequest.newBuilder(URI.create(base + "/api/scores")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"parts\": [{\"job\": \"nope\", \"part\": \"P1\"}]}")));
        assertEquals(404, missing.statusCode());

        String body = "{\"title\": \"Two pianos\", \"parts\": [{\"job\": \"" + a + "\", \"part\": \"" + part
                + "\", \"name\": \"Piano I\"}, {\"job\": \"" + b + "\", \"part\": \"" + part + "\", \"transpose\": \"M2\"}]}";
        var created = send(HttpRequest.newBuilder(URI.create(base + "/api/scores")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(201, created.statusCode(), new String(created.body(), StandardCharsets.UTF_8));
        JsonNode job = json(created);
        assertEquals("[]", job.get("inputs").toString());
        assertEquals("Two pianos", job.at("/options/combine/title").asText());
        job = waitFor(job.get("id").asText());
        assertEquals("done", job.get("status").asText(), job.toString());
        assertEquals(2, job.at("/report/combined_from").size());
        assertEquals("M2", job.at("/report/combined_from/1/transpose").asText());
        String id = job.get("id").asText();
        assertEquals(2, json(get("/api/jobs/" + id + "/parts")).size());
        assertEquals("Piano I", json(get("/api/jobs/" + id + "/parts")).get(0).get("name").asText());
        assertEquals(200, get("/api/jobs/" + id + "/download/pdf").statusCode());
        // A combined score has no uploads to transcribe again.
        var rerun = send(HttpRequest.newBuilder(URI.create(base + "/api/jobs/" + id + "/rerun"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertEquals(409, rerun.statusCode());
    }
}
