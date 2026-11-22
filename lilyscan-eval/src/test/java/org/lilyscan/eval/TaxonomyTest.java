package org.lilyscan.eval;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
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

class TaxonomyTest {
    /** A note: "C4" etc., or several pitches for a chord ("C4 E4 G4"); "r" for a rest. */
    private static Event ev(String pitches, Frac offset, Frac duration) {
        if (pitches.equals("r")) return new Event(Event.REST, offset, duration);
        String[] ps = pitches.split(" ");
        Event e = new Event(ps.length > 1 ? Event.CHORD : Event.NOTE, offset, duration);
        for (String p : ps) {
            String step = p.substring(0, 1);
            int alter = p.contains("#") ? 1 : p.contains("b") && p.length() > 2 ? -1 : 0;
            int octave = Integer.parseInt(p.substring(p.length() - 1));
            e.notes.add(new NoteHead(new Pitch(step, alter, octave)));
        }
        return e;
    }

    /** One staff of 4/4 measures, each with one voice holding its list of events. */
    @SafeVarargs
    private static Score score(List<Event>... measures) {
        Staff staff = new Staff(1, new ArrayList<>());
        for (int i = 0; i < measures.length; i++) {
            Measure m = new Measure(i, String.valueOf(i + 1));
            if (i == 0) m.time = new TimeSignature(4, 4, "normal");
            Voice v = new Voice(1);
            v.events.addAll(measures[i]);
            m.voices.add(v);
            staff.measures.add(m);
        }
        Part p = new Part("P1", "Flute", null);
        p.staves.add(staff);
        Score s = new Score();
        s.parts.add(p);
        return s;
    }

    private static final Frac Q = Frac.ONE;

    private static Frac at(int beat) {
        return Frac.of(beat);
    }

    private static List<String> cats(Score gt, Score pred) {
        return Taxonomy.classify("x", "png", gt, pred).stream().map(Taxonomy.Instance::category).sorted().toList();
    }

    private static List<Event> four(String a, String b, String c, String d) {
        return List.of(ev(a, at(0), Q), ev(b, at(1), Q), ev(c, at(2), Q), ev(d, at(3), Q));
    }

    @Test
    void identicalScoresHaveNoDifferences() {
        assertEquals(List.of(), cats(score(four("C4", "D4", "E4", "F4")), score(four("C4", "D4", "E4", "F4"))));
    }

    @Test
    void wrongPitchAndWrongAccidental() {
        assertEquals(List.of("accidental", "pitch"),
                cats(score(four("C4", "D4", "E4", "F4")), score(four("C#4", "D4", "G4", "F4"))));
    }

    @Test
    void aWholeMeasureMovedTheSameWayIsAConsistentShift() {
        // Bass clef read as treble: every note two steps up.
        assertEquals(List.of("consistent-shift"), cats(score(four("C4", "D4", "E4", "F4")), score(four("E4", "F4", "G4", "A4"))));
    }

    @Test
    void wrongDurationAndTuplet() {
        Score gt = score(List.of(ev("C4", at(0), Frac.of(2)), ev("D4", at(2), Frac.of(2))));
        Score dur = score(List.of(ev("C4", at(0), Q), ev("r", at(1), Q), ev("D4", at(2), Frac.of(2))));
        assertEquals(List.of("duration", "rest-extra"), cats(gt, dur));
        Score tup = score(List.of(ev("C4", at(0), Frac.of(2, 3)), ev("D4", at(2), Frac.of(2))));
        assertEquals(List.of("tuplet"), cats(gt, tup));
    }

    @Test
    void chordSizeMissingAndExtraNotes() {
        Score gt = score(List.of(ev("C4 E4 G4", at(0), Frac.of(2)), ev("D4", at(2), Frac.of(2))));
        Score pred = score(List.of(ev("C4 G4", at(0), Frac.of(2)), ev("r", at(2), Frac.of(2))));
        assertEquals(List.of("chord-size", "note-missing", "rest-extra"), cats(gt, pred));
    }

    @Test
    void extraVoice() {
        Score gt = score(four("C4", "D4", "E4", "F4"));
        Score pred = score(four("C4", "D4", "E4", "F4"));
        Voice v2 = new Voice(2);
        v2.events.add(ev("r", at(0), Frac.of(1, 2)));
        v2.events.add(ev("A3", Frac.of(1, 2), Frac.of(1, 2)));
        pred.parts.get(0).staves.get(0).measures.get(0).voices.add(v2);
        assertEquals(List.of("note-extra", "rest-extra", "voice-extra"), cats(gt, pred));
    }

    @Test
    void missingMeasureAndEngineFailure() {
        Score gt = score(four("C4", "D4", "E4", "F4"), four("G4", "A4", "B4", "C5"));
        assertEquals(List.of("measure-missing"), cats(gt, score(four("C4", "D4", "E4", "F4"))));
        assertEquals(List.of("engine-failure"), cats(gt, null));
    }

    @Test
    void rankingIsByPiecesAffected() {
        List<Taxonomy.Instance> all = new ArrayList<>();
        // "pitch" in 1 piece many times; "rest-missing" in 2 pieces once each.
        for (int i = 0; i < 5; i++) all.add(new Taxonomy.Instance("pitch", "a", "png", 0, "P1/1", "1", 0, 1, "", null));
        all.add(new Taxonomy.Instance("rest-missing", "a", "png", 0, "P1/1", "1", 0, 1, "", null));
        all.add(new Taxonomy.Instance("rest-missing", "b", "png", 0, "P1/1", "1", 0, 1, "", null));
        List<Taxonomy.Rank> r = Taxonomy.rank(all);
        assertEquals("rest-missing", r.get(0).category());
        assertEquals(2, r.get(0).pieces());
        assertEquals(1.0, r.get(1).topThreeShare());
    }
}
