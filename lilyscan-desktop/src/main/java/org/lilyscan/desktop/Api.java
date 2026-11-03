package org.lilyscan.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A client of the Lilyscan HTTP API: the same calls the web UI ({@code services/web/app.js})
 * makes, so the desktop app does exactly what the browser does, against the embedded server or
 * a remote one. Errors carry the server's {@code {"detail": ...}} message.
 */
public final class Api {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FILENAME = Pattern.compile("filename=\"([^\"]+)\"");

    /** An HTTP error from the server: {@code "404 job not found"}. */
    public static final class ApiError extends IOException {
        public final int status;

        ApiError(int status, String detail) {
            super(status + " " + detail);
            this.status = status;
        }
    }

    /** A downloaded file and the name the server gave it. */
    public record Download(String filename, byte[] content) {}

    private final String base;
    private final HttpClient http;

    /** {@code base} is the server's root URL, e.g. {@code http://127.0.0.1:8000}. */
    public Api(String base) {
        this.base = base.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public String base() {
        return base;
    }

    // --- endpoints ----------------------------------------------------------------------

    public JsonNode health() throws IOException {
        return json(get("/api/health"));
    }

    public JsonNode jobs() throws IOException {
        return json(get("/api/jobs"));
    }

    public JsonNode job(String id) throws IOException {
        return json(get("/api/jobs/" + id));
    }

    public JsonNode parts(String id) throws IOException {
        return json(get("/api/jobs/" + id + "/parts"));
    }

    public JsonNode review(String id) throws IOException {
        return json(get("/api/jobs/" + id + "/review"));
    }

    /** A job file by its path in the job folder (the engraved SVG pages). */
    public byte[] file(String id, String path) throws IOException {
        return get("/api/jobs/" + id + "/files/" + encodePath(path) + "?v=" + System.nanoTime());
    }

    /** Page {@code page} (from 1) as the engine read it. */
    public byte[] page(String id, int page) throws IOException {
        return get("/api/jobs/" + id + "/pages/" + page + ".png");
    }

    public String ly(String id, String path) throws IOException {
        return new String(get("/api/jobs/" + id + "/ly/" + encodePath(path)), StandardCharsets.UTF_8);
    }

    public void putLy(String id, String path, String text) throws IOException {
        send(request("/api/jobs/" + id + "/ly/" + encodePath(path))
                .header("Content-Type", "text/plain; charset=utf-8")
                .PUT(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8)));
    }

    public JsonNode recompile(String id) throws IOException {
        return json(send(request("/api/jobs/" + id + "/recompile").POST(HttpRequest.BodyPublishers.noBody())));
    }

    public JsonNode dropLyrics(String id, String part) throws IOException {
        ObjectNode body = JSON.createObjectNode().put("part", part);
        return json(postJson("/api/jobs/" + id + "/drop-lyrics", body));
    }

    /** A new job from the same uploads; {@code ocrLanguages} null keeps the server default. */
    public JsonNode rerun(String id, String ocrLanguages, boolean straighten) throws IOException {
        ObjectNode body = JSON.createObjectNode();
        if (ocrLanguages == null || ocrLanguages.isBlank()) body.putNull("ocr_languages");
        else body.put("ocr_languages", ocrLanguages.strip());
        body.put("straighten", straighten);
        return json(postJson("/api/jobs/" + id + "/rerun", body));
    }

    /** {@code spec} is the web UI's body: {@code {"title", "parts": [...], "piano": [...]}}. */
    public JsonNode createScore(JsonNode spec) throws IOException {
        return json(postJson("/api/scores", spec));
    }

    /** Uploads files as a new job (multipart, as the web form posts them). */
    public JsonNode createJob(List<Path> files, String ocrLanguages, boolean straighten) throws IOException {
        String boundary = "lilyscan-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        List<String[]> fields = new ArrayList<>();
        if (ocrLanguages != null && !ocrLanguages.isBlank()) fields.add(new String[] {"ocr_languages", ocrLanguages.strip()});
        fields.add(new String[] {"straighten", straighten ? "true" : "false"});
        for (Path f : files) {
            String name = f.getFileName().toString().replace("\"", "_").replace("\r", "_").replace("\n", "_");
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                    + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(Files.readAllBytes(f));
            body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        for (String[] field : fields) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field[0]
                    + "\"\r\n\r\n" + field[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return json(send(request("/api/jobs").timeout(Duration.ofMinutes(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))));
    }

    /** {@code kind}: ly, ly-project, pdf, midi, musicxml or omr. */
    public Download download(String id, String kind) throws IOException {
        HttpResponse<byte[]> r = exchange(request("/api/jobs/" + id + "/download/" + kind).GET());
        String name = r.headers().firstValue("Content-Disposition").map(h -> {
            Matcher m = FILENAME.matcher(h);
            return m.find() ? m.group(1) : null;
        }).orElse(null);
        return new Download(name == null ? "lilyscan-" + id + "-" + kind : name, r.body());
    }

    // --- plumbing -----------------------------------------------------------------------

    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/", -1)) {
            if (!out.isEmpty()) out.append('/');
            out.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(2));
    }

    private byte[] get(String path) throws IOException {
        return send(request(path).GET());
    }

    private byte[] postJson(String path, JsonNode body) throws IOException {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8)));
    }

    private byte[] send(HttpRequest.Builder b) throws IOException {
        return exchange(b).body();
    }

    private HttpResponse<byte[]> exchange(HttpRequest.Builder b) throws IOException {
        HttpResponse<byte[]> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (r.statusCode() >= 400) {
            String detail = "error";
            try {
                JsonNode d = JSON.readTree(r.body()).get("detail");
                if (d != null) detail = d.isTextual() ? d.asText() : d.toString();
            } catch (IOException ignored) {
                // not JSON
            }
            throw new ApiError(r.statusCode(), detail);
        }
        return r;
    }

    private static JsonNode json(byte[] body) throws IOException {
        return JSON.readTree(body);
    }
}
