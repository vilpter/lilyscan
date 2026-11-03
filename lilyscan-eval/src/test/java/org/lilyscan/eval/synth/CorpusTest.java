package org.lilyscan.eval.synth;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.MusicXmlWriter;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.TimeSignature;
import org.lilyscan.core.lilypond.Compile;
import org.lilyscan.core.runtime.Settings;

/** The corpus builder's parts: excerpts, beaming, the scan and photo simulation, a whole build. */
class CorpusTest {
    @TempDir Path dir;

    private static CorpusSpec.Piece piece(String id, String category, int seed, int measures) {
        return new CorpusSpec.Piece(id, category, seed, measures, null, null, 1, "musicxml2ly", Sources.MUSIC21, null);
    }

    @Test
    void excerptsRestateTheContext() {
        Score full = Generator.build(piece("piano-01", "piano", 100, 12));
        Score ex = Excerpt.of(full, 4, 3);
        for (Staff s : ex.parts.get(0).staves) {
            assertEquals(List.of("4", "5", "6"), s.measures.stream().map(m -> m.number).toList());
            assertEquals(List.of(0, 1, 2), s.measures.stream().map(m -> m.index).toList());
            Measure head = s.measures.get(0);
            Measure source = full.parts.get(0).staves.get(s.number - 1).measures.get(0);
            assertEquals(source.clefs.get(0), head.clefs.get(0), "clef in force");
            assertEquals(source.key, head.key, "key in force");
            assertEquals(source.time, head.time, "meter in force");
        }
        assertEquals(full.title, ex.title);
    }

    private static Event note(String type, Frac offset, Frac duration) {
        Event e = new Event(Event.NOTE, offset, duration);
        e.notes.add(new NoteHead(new Pitch("C", 0, 5)));
        e.noteType = type;
        return e;
    }

    @Test
    void beamsByBeat() {
        Frac e = Frac.of(1, 2), s = Frac.of(1, 4), de = Frac.of(3, 4);
        // 4/4: two eighths | four sixteenths | dotted eighth + sixteenth | an eighth, an eighth rest
        List<Event> events = List.of(
                note("eighth", Frac.ZERO, e), note("eighth", e, e),
                note("16th", Frac.ONE, s), note("16th", Frac.of(5, 4), s), note("16th", Frac.of(3, 2), s),
                note("16th", Frac.of(7, 4), s),
                note("eighth", Frac.of(2), de), note("16th", Frac.of(11, 4), s),
                note("eighth", Frac.of(3), e), new Event(Event.REST, Frac.of(7, 2), e));
        events.get(6).dots = 1;
        var beams = MusicXmlWriter.beams(events, new TimeSignature(4, 4, "normal"));
        assertEquals(List.of("begin"), beams.get(events.get(0)));
        assertEquals(List.of("end"), beams.get(events.get(1)));
        assertEquals(List.of("begin", "begin"), beams.get(events.get(2)));
        assertEquals(List.of("continue", "continue"), beams.get(events.get(3)));
        assertEquals(List.of("end", "end"), beams.get(events.get(5)));
        assertEquals(List.of("begin"), beams.get(events.get(6)));
        assertEquals(List.of("end", "backward hook"), beams.get(events.get(7)));
        assertNull(beams.get(events.get(8)), "a lone eighth before a rest keeps its flag");

        // 6/8: beams span the dotted-quarter beat.
        List<Event> six = List.of(note("eighth", Frac.ZERO, e), note("eighth", e, e), note("eighth", Frac.ONE, e),
                note("eighth", Frac.of(3, 2), e), note("eighth", Frac.of(2), e), note("eighth", Frac.of(5, 2), e));
        var b6 = MusicXmlWriter.beams(six, new TimeSignature(6, 8, "normal"));
        assertEquals(List.of("begin"), b6.get(six.get(0)));
        assertEquals(List.of("continue"), b6.get(six.get(1)));
        assertEquals(List.of("end"), b6.get(six.get(2)));
        assertEquals(List.of("begin"), b6.get(six.get(3)));
    }

    @Test
    void engravingOptionsKeepTheDefaultExportUnchanged() {
        Score score = Generator.build(piece("leadsheet-01", "leadsheet", 100, 12));
        String plain = MusicXmlWriter.toMusicXml(score), engraving = MusicXmlWriter.toMusicXml(score,
                MusicXmlWriter.Options.ENGRAVING);
        assertFalse(plain.contains("<beam"));
        assertTrue(plain.contains("<offset>"), "the default places mid-measure chord symbols by offset");
        assertTrue(engraving.contains("<beam number=\"1\">begin</beam>"));
        // Both read back as the same music.
        assertEquals(SynthParityTest.summary(MusicXml.parse(plain.getBytes(), "x")),
                SynthParityTest.summary(MusicXml.parse(engraving.getBytes(), "x")));
    }

    @Test
    void degradationsAreDeterministic() {
        int w = 400, h = 300;
        float[] px = new float[w * h];
        Arrays.fill(px, 1f);
        for (int y = 100; y < 105; y++) for (int x = 20; x < 380; x++) px[y * w + x] = 0f;
        Degrade.Image clean = new Degrade.Image(w, h, px);
        Degrade.Image a = Degrade.scan(clean, 1001), b = Degrade.scan(clean, 1001), c = Degrade.scan(clean, 1011);
        assertArrayEquals(a.px(), b.px());
        assertFalse(Arrays.equals(a.px(), c.px()));
        assertEquals(w, a.width());
        Degrade.Image p = Degrade.photo(clean, 1002);
        assertArrayEquals(p.px(), Degrade.photo(clean, 1002).px());
        int margin = (int) (0.06 * Math.max(w, h));
        assertEquals(w + 2 * margin, p.width(), "the page lies on a background");
        double ink = 0;
        for (float v : a.px()) ink += v < 0.5 ? 1 : 0;
        assertTrue(ink > 1000 && ink < 4000, "the line survives the scan: " + ink);
    }

    @Test
    void buildsACorpus() throws Exception {
        Settings s = Settings.fromEnv();
        Assumptions.assumeTrue(Compile.lilypondVersion(s) != null, "LilyPond not found (set LILYPOND_BIN)");
        Path spec = Files.writeString(dir.resolve("tiny.json"), """
                {"pieces": [{"id": "solo-01", "category": "solo", "seed": 100, "measures": 16},
                            {"id": "satb-01", "category": "satb", "seed": 100, "measures": 8, "language": "lat"}]}
                """);
        Path out = dir.resolve("tiny");
        var items = CorpusBuilder.build(spec, out, null, false, dir.resolve("cache"), s, line -> { });
        assertEquals(2, items.size());
        for (String f : List.of("ground_truth.musicxml", "score.pdf", "score.png", "scan.png", "photo.png")) {
            assertTrue(Files.size(out.resolve("solo-01").resolve(f)) > 1000, f);
        }
        JsonNode manifest = new ObjectMapper().readTree(out.resolve("manifest.json").toFile());
        assertEquals("tiny.json", manifest.get("spec").asText());
        JsonNode satb = manifest.get("items").get(1);
        assertEquals("lat", satb.get("language").asText());
        assertEquals("generated", satb.get("source").asText());
        assertEquals("satb-01/scan.png", satb.get("inputs").get("scan").asText());
        // The harness reads it as a corpus.
        assertEquals(2, org.lilyscan.eval.Corpus.load(out).size());
        // Complete pieces are kept, not rebuilt.
        long before = Files.getLastModifiedTime(out.resolve("solo-01/scan.png")).toMillis();
        CorpusBuilder.build(spec, out, null, false, dir.resolve("cache"), s, line -> fail("rebuilt " + line));
        assertEquals(before, Files.getLastModifiedTime(out.resolve("solo-01/scan.png")).toMillis());
        // The ground truth is the generator's piece, with beams for the engraver.
        Score truth = MusicXml.load(out.resolve("solo-01/ground_truth.musicxml"), "x");
        assertEquals(SynthParityTest.summary(Generator.build(piece("solo-01", "solo", 100, 16))).get("staves"),
                SynthParityTest.summary(truth).get("staves"));
        assertTrue(Files.readString(out.resolve("solo-01/ground_truth.musicxml")).contains("<beam"));
        Part p = truth.parts.get(0);
        assertEquals("Flute", p.name);
    }
}
