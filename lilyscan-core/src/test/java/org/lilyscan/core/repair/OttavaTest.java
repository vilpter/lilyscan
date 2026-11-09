package org.lilyscan.core.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lilyscan.core.ir.BBox;
import org.lilyscan.core.ir.Direction;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.MusicXmlWriter;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.lilypond.Generate;

/** Octave shifts: import, the missing-end repair, LilyPond and MusicXML output (issue #97). */
class OttavaTest {

    private static final String START = "<direction placement=\"above\"><direction-type>"
            + "<octave-shift type=\"down\" number=\"1\" size=\"8\"/></direction-type></direction>";
    private static final String STOP = "<direction placement=\"above\"><direction-type>"
            + "<octave-shift type=\"stop\" number=\"1\"/></direction-type></direction>";

    /** Four quarter notes C5 D5 E5 F5, with {@code before[i]} put before note i (index 4: after
     *  the last). */
    private static String measure(int number, String... before) {
        String[] steps = {"C", "D", "E", "F"};
        StringBuilder sb = new StringBuilder("<measure number=\"" + number + "\">");
        if (number == 1) {
            sb.append("<attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time>"
                    + "<clef><sign>G</sign><line>2</line></clef></attributes>");
        }
        for (int i = 0; i <= 4; i++) {
            if (i < before.length && before[i] != null) sb.append(before[i]);
            if (i < 4) {
                sb.append("<note><pitch><step>").append(steps[i]).append("</step><octave>5</octave></pitch>"
                        + "<duration>1</duration><voice>1</voice><type>quarter</type></note>");
            }
        }
        return sb.append("</measure>").toString();
    }

    private static Score score(String... measures) {
        String xml = "<?xml version=\"1.0\"?><score-partwise version=\"4.0\"><part-list>"
                + "<score-part id=\"P1\"><part-name>Piano</part-name></score-part></part-list><part id=\"P1\">"
                + String.join("", measures) + "</part></score-partwise>";
        return MusicXml.parse(xml.getBytes(StandardCharsets.UTF_8), "test.musicxml");
    }

    private static List<Measure> measures(Score s) {
        return s.parts.get(0).staves.get(0).measures;
    }

    /** Puts measures on systems: {@code systems[i]} is measure i's system (one page). */
    private static void layout(Score s, int... systems) {
        List<Measure> ms = measures(s);
        for (int i = 0; i < ms.size(); i++) ms.get(i).bbox = new BBox(0, 100.0 * i, 500.0 * systems[i], 100.0, 100.0);
    }

    private static List<Integer> octaves(Measure m) {
        List<Integer> out = new ArrayList<>();
        for (Voice v : m.voices) for (Event e : v.events) if (!e.notes.isEmpty()) out.add(e.notes.get(0).pitch.octave);
        return out;
    }

    private static String music(Score s) {
        return String.join("\n", Generate.generateProject(s).files().values());
    }

    @Test
    void importsStartAndStopAtTheirOffsets() {
        Score s = score(measure(1, null, START, null, STOP));
        List<Direction> ds = measures(s).get(0).directions;
        assertEquals(2, ds.size());
        assertEquals("down", ds.get(0).type);
        assertEquals(8, ds.get(0).size);
        assertEquals(1, ds.get(0).octaves());
        assertEquals("1", ds.get(0).offset.toString());
        assertEquals("stop", ds.get(1).type);
        assertEquals("3", ds.get(1).offset.toString());
    }

    @Test
    void completeShiftIsLeftAloneAndEngraved() {
        Score s = score(measure(1, null, START, null, STOP));
        layout(s, 0);
        assertTrue(Ottava.missingEnds(s).isEmpty());
        assertEquals(List.of(5, 5, 5, 5), octaves(measures(s).get(0)));
        assertTrue(music(s).contains("c''4 \\ottava #1 d''4 e''4 \\ottava #0 f''4"), music(s));
    }

    @Test
    void startWithoutStopEndsWithItsSystem() {
        Score s = score(measure(1, null, null, START), measure(2), measure(3));
        layout(s, 0, 0, 1);
        List<Repair> log = Ottava.missingEnds(s);
        assertEquals(1, log.size());
        assertEquals(List.of("1", "2"), log.get(0).measures());
        assertEquals(List.of(5, 5, 6, 6), octaves(measures(s).get(0)));
        assertEquals(List.of(6, 6, 6, 6), octaves(measures(s).get(1)));
        assertEquals(List.of(5, 5, 5, 5), octaves(measures(s).get(2)));
        Direction stop = measures(s).get(1).directions.get(0);
        assertTrue(stop.isOctaveStop());
        assertEquals("4", stop.offset.toString());
        String ly = music(s);
        assertTrue(ly.contains("d''4 \\ottava #1 e'''4 %{ fix: ottava %}"), ly);
        assertTrue(ly.contains("f'''4 %{ fix: ottava %} \\ottava #0 |"), ly);
    }

    @Test
    void stopWithoutStartBeginsWithItsSystem() {
        Score s = score(measure(1), measure(2), measure(3, null, null, STOP));
        layout(s, 0, 1, 1);
        List<Repair> log = Ottava.missingEnds(s);
        assertEquals(1, log.size());
        assertEquals(List.of("2", "3"), log.get(0).measures());
        assertEquals(List.of(5, 5, 5, 5), octaves(measures(s).get(0)));
        assertEquals(List.of(6, 6, 6, 6), octaves(measures(s).get(1)));
        assertEquals(List.of(6, 6, 5, 5), octaves(measures(s).get(2)));
        assertTrue(measures(s).get(1).directions.get(0).isOctaveStart());
    }

    @Test
    void shiftContinuedOnTheNextSystemIsComplete() {
        Score s = score(measure(1, null, START), measure(2, null, STOP));
        layout(s, 0, 1);
        assertTrue(Ottava.missingEnds(s).isEmpty());
        assertEquals(List.of(5, 5, 5, 5), octaves(measures(s).get(1)));
    }

    @Test
    void withoutPageBoxesAShiftIsKeptToItsMeasure() {
        Score s = score(measure(1, null, null, START), measure(2));
        assertEquals(1, Ottava.missingEnds(s).size());
        assertEquals(List.of(5, 5, 6, 6), octaves(measures(s).get(0)));
        assertEquals(List.of(5, 5, 5, 5), octaves(measures(s).get(1)));
    }

    @Test
    void musicXmlRoundTripKeepsTheShift() {
        Score s = score(measure(1, null, START, null, STOP));
        Score back = MusicXml.parse(MusicXmlWriter.toMusicXml(s).getBytes(StandardCharsets.UTF_8), "back.musicxml");
        assertEquals(measures(s).get(0).directions, measures(back).get(0).directions);
    }

    @Test
    void irJsonOmitsDirectionsWhenThereAreNone() {
        assertFalse(IrJson.toJson(score(measure(1))).contains("directions"));
        Score s = score(measure(1, START));
        String json = IrJson.toJson(s);
        assertTrue(json.contains("\"directions\""), json);
        assertEquals(measures(s).get(0).directions, measures(IrJson.readScore(json)).get(0).directions);
    }
}
