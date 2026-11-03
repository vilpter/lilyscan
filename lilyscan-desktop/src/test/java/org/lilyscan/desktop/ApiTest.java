package org.lilyscan.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every call the desktop app makes, against the server it embeds: upload, follow, review, the
 * engraved pages and their links, edit and recompile, re-run, combine, every download, errors.
 */
class ApiTest {
    @TempDir Path data;
    @TempDir Path uploads;
    private Connection.Local local;
    private Api api;

    @BeforeEach
    void start() {
        local = FakeServer.start(data);
        api = local.api();
    }

    @AfterEach
    void stop() {
        if (local != null) local.close();
    }

    private String transcribe(String name) throws Exception {
        Path pdf = Files.copy(FakeServer.PDF, uploads.resolve(name));
        JsonNode job = api.createJob(List.of(pdf), "eng+lat", true);
        assertEquals("{\"prepare\":true,\"ocr_languages\":\"eng+lat\"}", job.get("options").toString());
        job = FakeServer.waitFor(api, job.get("id").asText());
        assertEquals("done", job.get("status").asText(), job.toString());
        return job.get("id").asText();
    }

    @Test
    void wholeJob() throws Exception {
        assertEquals("ok", api.health().get("status").asText());
        String id = transcribe("My Score.pdf");
        assertEquals("[\"00-My_Score.pdf\"]", api.job(id).get("inputs").toString());
        assertEquals(1, api.jobs().size());

        JsonNode review = api.review(id);
        assertFalse(review.get("measures").isEmpty());
        assertEquals(1, review.get("pages").size());
        assertTrue(api.page(id, 1).length > 1000);
        Api.ApiError noPage = assertThrows(Api.ApiError.class, () -> api.page(id, 9));
        assertEquals(404, noPage.status);

        // The engraved pages' links lead to measures of the review's measure map.
        java.util.Set<String> lines = new java.util.HashSet<>();
        review.get("measures").forEach(m -> {
            if (m.path("file").isTextual()) lines.add(m.get("file").asText() + ":" + m.get("line").asInt());
        });
        int linked = 0;
        for (JsonNode p : review.get("lilypond").get("svg")) {
            ScorePane.Page page = ScorePane.Page.load(api.file(id, p.asText()));
            Rectangle2D vb = page.links().viewBox();
            for (LilySvg.Link l : page.links().links()) {
                if (lines.contains(Labels.linkTarget(l.href()))) linked++;
                // Every note's box is note-sized and level with the page (an overfull line, as
                // a measure with a broken rhythm makes, may run past the right margin).
                assertTrue(l.box().getWidth() < 20 && l.box().getHeight() < 20, l.toString());
                assertTrue(l.box().getMinY() >= vb.getMinY() && l.box().getMaxY() <= vb.getMaxY() && l.box().getMinX() >= 0, l.toString());
            }
        }
        assertTrue(linked > 10, "only " + linked + " notes link to a measure");

        // Edit a part and recompile: the edit is kept and engraved.
        String file = null;
        for (JsonNode f : review.get("lilypond").get("files")) {
            if (f.asText().startsWith("ly/parts/")) file = f.asText().substring(3);
        }
        assertNotNull(file);
        String text = api.ly(id, file);
        api.putLy(id, file, text + "\n% edited in the desktop app\n");
        assertEquals("queued", api.recompile(id).get("status").asText());
        assertEquals("done", FakeServer.waitFor(api, id).get("status").asText());
        assertTrue(api.ly(id, file).endsWith("% edited in the desktop app\n"));

        // Downloads carry the server's file names.
        Api.Download ly = api.download(id, "ly");
        assertEquals("Lilyscan piano-01.ly", ly.filename()); // named after the score title
        assertTrue(new String(ly.content(), StandardCharsets.UTF_8).contains("% edited in the desktop app"));
        assertEquals("lilyscan-" + id + ".zip", api.download(id, "ly-project").filename());
        assertTrue(new String(api.download(id, "pdf").content(), 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF"));
        assertTrue(api.download(id, "midi").filename().endsWith(".midi") || api.download(id, "midi").filename().endsWith(".mid"));
        assertEquals("lilyscan-" + id + ".musicxml", api.download(id, "musicxml").filename());
        assertEquals("lilyscan-" + id + ".omr", api.download(id, "omr").filename());
        Api.ApiError unknown = assertThrows(Api.ApiError.class, () -> api.download(id, "wav"));
        assertEquals("404 unknown download 'wav'", unknown.getMessage());

        // No lyrics to drop in a piano piece: the server's message comes through.
        Api.ApiError drop = assertThrows(Api.ApiError.class, () -> api.dropLyrics(id, "P1"));
        assertEquals(404, drop.status);

        // Re-run: a new job with the same upload and other settings.
        JsonNode again = api.rerun(id, null, false);
        assertNotEquals(id, again.get("id").asText());
        assertEquals(false, again.get("options").get("prepare").asBoolean());
        assertEquals(id, again.get("options").get("rerun_of").asText());
        assertEquals("done", FakeServer.waitFor(api, again.get("id").asText()).get("status").asText());
    }

    @Test
    void combineParts() throws Exception {
        String a = transcribe("a.pdf");
        String b = transcribe("b.pdf");
        JsonNode parts = api.parts(a);
        assertFalse(parts.isEmpty());
        String part = parts.get(0).get("id").asText();
        ObjectMapper json = new ObjectMapper();
        ObjectNode spec = json.createObjectNode().put("title", "Duo");
        spec.putArray("parts").add(json.createObjectNode().put("job", a).put("part", part))
                .add(json.createObjectNode().put("job", b).put("part", part).put("transpose", "P8"));
        spec.putArray("piano");
        JsonNode job = api.createScore(spec);
        assertEquals("Combined: Duo", Labels.jobLabel(job));
        assertEquals("done", FakeServer.waitFor(api, job.get("id").asText()).get("status").asText());
        assertFalse(api.review(job.get("id").asText()).get("measures").isEmpty());

        ObjectNode none = json.createObjectNode();
        none.putArray("parts");
        Api.ApiError e = assertThrows(Api.ApiError.class, () -> api.createScore(none));
        assertEquals("422 choose at least one part", e.getMessage());
    }

    @Test
    void rejectedUploadAndUnknownJob() throws Exception {
        Path txt = Files.writeString(uploads.resolve("notes.txt"), "x");
        Api.ApiError e = assertThrows(Api.ApiError.class, () -> api.createJob(List.of(txt), null, true));
        assertEquals("415 unsupported file type: 'notes.txt'", e.getMessage());
        assertEquals("404 job not found", assertThrows(Api.ApiError.class, () -> api.job("nope")).getMessage());
    }

    @Test
    void remoteNeedsALilyscanServer() {
        assertThrows(java.io.IOException.class, () -> Connection.remote("http://127.0.0.1:1"));
        assertDoesNotThrow(() -> Connection.remote(api.base().replace("http://", "")));
    }
}
