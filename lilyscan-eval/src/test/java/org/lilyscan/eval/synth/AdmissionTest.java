package org.lilyscan.eval.synth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.TimeSignature;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.lilypond.Compile;
import org.lilyscan.core.lilypond.Generate;
import org.lilyscan.core.runtime.Settings;

/** The admission checks: a malformed ground truth, or an engraving that differs from it, is rejected. */
class AdmissionTest {
    @TempDir Path dir;

    private static Event note(String step, Frac offset, Frac duration) {
        Event e = new Event(Event.NOTE, offset, duration);
        e.notes.add(new NoteHead(new Pitch(step, 0, 4)));
        return e;
    }

    /** One staff of 2/4 measures, each with one voice holding {@code events}. */
    private static Score score(List<List<Event>> measures) {
        Staff staff = new Staff(1, new java.util.ArrayList<>());
        for (int i = 0; i < measures.size(); i++) {
            Measure m = new Measure(i, String.valueOf(i + 1));
            if (i == 0) m.time = new TimeSignature(2, 4, null);
            Voice v = new Voice(1);
            v.events.addAll(measures.get(i));
            m.voices.add(v);
            staff.measures.add(m);
        }
        Part p = new Part("P1", "Flute", null);
        p.staves.add(staff);
        Score s = new Score();
        s.parts.add(p);
        return s;
    }

    private static List<String> checks(List<Map<String, Object>> findings) {
        return findings.stream().map(f -> (String) f.get("check")).toList();
    }

    @Test
    void wellFormedGroundTruthPasses() {
        Score s = score(List.of(List.of(note("C", Frac.ZERO, Frac.ONE), note("D", Frac.ONE, Frac.ONE))));
        assertEquals(List.of(), Admission.groundTruthFindings(s));
    }

    @Test
    void overlappingEventsInOneVoiceAreFound() {
        // The second note starts while the first (a half note) still sounds: the quilter case.
        Score s = score(List.of(List.of(note("C", Frac.ZERO, Frac.of(2)), note("D", Frac.ONE, Frac.ONE))));
        assertEquals(List.of("overlap"), checks(Admission.groundTruthFindings(s)));
    }

    @Test
    void overfullVoiceIsFound() {
        Score s = score(List.of(List.of(note("C", Frac.ZERO, Frac.ONE), note("D", Frac.ONE, Frac.of(2)))));
        assertEquals(List.of("overfull"), checks(Admission.groundTruthFindings(s)));
    }

    @Test
    void listenerLinesAreComparedInWholeNotes() {
        Score s = score(List.of(List.of(note("C", Frac.ZERO, Frac.ONE), note("D", Frac.ONE, Frac.ONE))));
        // LilyPond: staff, kind, bar, position and duration in whole notes, MIDI pitch.
        List<String> same = List.of("1\tnote\t1\t0\t1/4\t60", "1\tnote\t1\t1/4\t1/4\t62");
        assertEquals(List.of(), Admission.compare(s, same));
        List<String> wrong = List.of("1\tnote\t1\t0\t1/4\t60", "1\tnote\t1\t1/4\t1/4\t64");
        List<Map<String, Object>> found = Admission.compare(s, wrong);
        assertEquals(List.of("engraving"), checks(found));
        assertTrue(((String) found.get(0).get("message")).contains("note 62 in m. 1 at 1 (1)"), found.toString());
        assertEquals(List.of("engraving"), checks(Admission.compare(s, List.of())), "no staff engraved");
    }

    @Test
    void rejectedItemsAreNotLoadedForScoring() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("manifest.json"), """
                {"items": [{"id": "a", "category": "song", "admission": {"status": "pass", "findings": []}},
                           {"id": "b", "category": "song", "admission": {"status": "rejected", "findings": []}},
                           {"id": "c", "category": "song"}]}
                """);
        assertEquals(List.of("a", "c"), org.lilyscan.eval.Corpus.load(dir).stream().map(i -> i.id()).toList());
        assertEquals(3, org.lilyscan.eval.Corpus.load(dir, true).size());
    }

    @Test
    void engravingIsReadBackWithLilyPond() throws Exception {
        Settings settings = Settings.fromEnv();
        Assumptions.assumeTrue(Compile.lilypondVersion(settings) != null, "LilyPond not found (set LILYPOND_BIN)");
        CorpusSpec.Piece piece = new CorpusSpec.Piece("solo-01", "solo", 100, 8, null, null, 1, "lilyscan",
                Sources.MUSIC21, null);
        Score truth = Generator.build(piece);
        Path root = dir.resolve("ly");
        Generate.writeProject(truth, root);
        Path ly = root.resolve("main.ly");
        assertEquals(List.of(), Admission.engravingFindings(truth, ly, settings));

        // The same engraving judged against a ground truth with one pitch changed.
        Score changed = Generator.build(piece);
        Event first = changed.parts.get(0).staves.get(0).measures.get(0).voices.get(0).events.stream()
                .filter(e -> !e.isRest() && !e.grace).findFirst().orElseThrow();
        first.notes.get(0).pitch.octave += 1;
        List<Map<String, Object>> found = Admission.engravingFindings(changed, ly, settings);
        assertEquals(List.of("engraving"), checks(found), found.toString());
        assertTrue(((String) found.get(0).get("message")).startsWith("1 written but not engraved, 1 engraved"),
                found.toString());
    }
}
