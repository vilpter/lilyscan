package org.lilyscan.core.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lilyscan.core.ir.Barline;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Lyric;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.TimeSignature;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.lilypond.Generate;
import org.lilyscan.core.lilypond.LyProject;

/** Port of the unit tests in tests/test_qa.py. */
class ChecksTest {

    static Event note(String step, int octave, int offset, int dur) {
        Event e = new Event(Event.NOTE, Frac.of(offset), Frac.of(dur));
        e.notes.add(new NoteHead(new Pitch(step, 0, octave)));
        return e;
    }

    @SafeVarargs
    static Staff staff(boolean implicitFirst, List<Event>... measures) {
        List<Measure> ms = new ArrayList<>();
        for (int i = 0; i < measures.length; i++) {
            Measure m = new Measure(i, Integer.toString(i + 1), implicitFirst && i == 0);
            if (i == 0) {
                m.time = new TimeSignature(4, 4, "normal");
            }
            Voice v = new Voice(1);
            v.events.addAll(measures[i]);
            m.voices.add(v);
            ms.add(m);
        }
        return new Staff(1, ms);
    }

    static Part part(String id, String name, String abbr, Staff... staves) {
        Part p = new Part(id, name, abbr);
        p.staves.addAll(List.of(staves));
        return p;
    }

    static Score score(Part... parts) {
        Score s = new Score();
        s.parts.addAll(List.of(parts));
        return s;
    }

    @Test
    void rhythmFlagsShortMeasureButAllowsMarkedPickup() {
        Score ok = score(part("P1", "Flute", null, staff(true, List.of(note("C", 5, 0, 1)),
                List.of(note("C", 5, 0, 4)), List.of(note("C", 5, 0, 3)))));
        Checks.CheckResult r = Checks.rhythmCheck(ok);
        assertTrue(r.passed(), r.details().toString()); // pickup of 1 + final measure of 3
        Score bad = score(part("P1", "Flute", null, staff(false, List.of(note("C", 5, 0, 4)),
                List.of(note("C", 5, 0, 3)), List.of(note("C", 5, 0, 4)))));
        r = Checks.rhythmCheck(bad);
        assertFalse(r.passed());
        assertEquals("2", r.details().get(0).get("measure"));
        assertEquals("3", r.details().get(0).get("actual"));
    }

    @Test
    void rangeUsesInstrumentNames() {
        Part violin = part("P1", "Violin I", null, staff(false, List.of(note("E", 3, 0, 4))));
        assertEquals(55, Checks.instrumentRange(violin).lo());
        assertEquals(103, Checks.instrumentRange(violin).hi());
        Checks.CheckResult r = Checks.rangeCheck(score(violin));
        assertFalse(r.passed());
        assertEquals("E3", r.details().get(0).get("pitch"));
        assertTrue(Checks.rangeCheck(score(part("P1", "Theremin", null, staff(false, List.of(note("E", 3, 0, 4)))))).passed());
    }

    @Test
    void alignmentReportsCountAndBarlineMismatches() {
        Score s = score(part("P1", "A", null, staff(false, List.of(note("C", 5, 0, 4)), List.of(note("C", 5, 0, 4)))),
                part("P2", "B", null, staff(false, List.of(note("C", 5, 0, 4)))));
        assertEquals(List.of("measure-count"), Checks.alignmentCheck(s).details().stream().map(d -> d.get("kind")).toList());
        Score t = score(part("P1", "A", null, staff(false, List.of(note("C", 5, 0, 4)))),
                part("P2", "B", null, staff(false, List.of(note("C", 5, 0, 4)))));
        t.parts.get(1).staves.get(0).measures.get(0).rightBarline = new Barline("light-heavy", null, null, null);
        assertEquals(List.of("barline"), Checks.alignmentCheck(t).details().stream().map(d -> d.get("kind")).toList());
    }

    static Part sung(String name, String id, String abbr) {
        Event e = note("C", 5, 0, 4);
        e.lyrics.add(new Lyric("Allegro"));
        return part(id, name, abbr, staff(false, List.of(note("C", 5, 0, 4)), List.of(e)));
    }

    @Test
    void lyricsCheckFlagsInstrumentsThatDoNotSing() {
        Score s = score(sung("Violin I", "P1", null), sung("Voice", "P2", null), sung("Piano", "P3", null),
                sung("Double Bass", "P4", null), sung("Bass", "P5", null), sung("", "P6", "Vla."),
                part("P7", "Viola", null, staff(false, List.of(note("C", 4, 0, 4)))));
        LyProject project = new LyProject(Map.of(), Map.of(), new java.util.LinkedHashMap<>(Map.of(
                "violinIMusic", new LyProject.StaffRef("P1", 1), "doubleBassMusic", new LyProject.StaffRef("P4", 1))));
        Checks.CheckResult r = Checks.lyricsCheck(s, project);
        assertFalse(r.passed());
        assertEquals(List.of("P1", "P4", "P6"), r.details().stream().map(d -> d.get("part")).toList());
        Map<String, Object> first = r.details().get(0);
        assertEquals("Violin I", first.get("name"));
        assertEquals(1, first.get("events"));
        assertEquals(List.of("2"), first.get("measures"));
        assertEquals(List.of("violinIVoice"), first.get("voices"));
        assertTrue(Checks.isInstrumental(new Part("X", "Vc.", null)));
        assertFalse(Checks.isInstrumental(new Part("X", "Alto", null)));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
        "Celio,NULL,true", "Vrolin 2,NULL,true", "Violin 2,Voice,true", "Voice,Vln.,true", "Voice,Voice,false",
        "Bass,Bs,false", "Double Bass,NULL,true", "Piano,Pno.,false", "Theremin,NULL,false"})
    void instrumentNames(String name, String abbreviation, boolean instrumental) {
        assertEquals(instrumental, Checks.isInstrumental(new Part("X", name, abbreviation)));
    }

    @Test
    void lyricsCheckAfterTheLyricsAreDropped() {
        List<Map<String, Object>> found = List.of(new java.util.LinkedHashMap<>(Map.of("part", "P1", "name", "Violin",
                "voices", List.of("violinVoice"))));
        String layout = "    \\new Lyrics \\lyricsto \"violinVoice\" \\violinVerseOne\n";
        Checks.CheckResult check = new Checks.CheckResult("Q9", "lyrics in instrumental parts", false, found, "");
        assertFalse(Checks.lyricsAfterEdit(check, Map.of("layout/score.ly", layout)).passed());
        Map<String, String> edited = Map.of("layout/score.ly", Generate.dropLyrics(layout, java.util.Set.of("violinVoice")));
        assertEquals("", edited.get("layout/score.ly"));
        assertTrue(Checks.lyricsAfterEdit(check, edited).passed());
    }
}
