package org.lilyscan.core.lilypond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.ir.Direction;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.MusicXmlWriter;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Slur;

/** Slurs, dynamics, hairpins, words, tempo and trills: import, LilyPond, MusicXML (issue #96). */
class MarksTest {

    private static String note(String step, String extra) {
        return "<note><pitch><step>" + step + "</step><octave>5</octave></pitch><duration>1</duration>"
                + "<voice>1</voice><type>quarter</type>" + extra + "</note>";
    }

    private static String note(String step) {
        return note(step, "");
    }

    private static String slur(String type) {
        return "<notations><slur type=\"" + type + "\" number=\"1\"/></notations>";
    }

    private static String direction(String placement, String types) {
        return "<direction placement=\"" + placement + "\">" + types + "</direction>";
    }

    private static String measure(int number, String... content) {
        String attrs = number == 1 ? "<attributes><divisions>1</divisions><time><beats>4</beats>"
                + "<beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes>" : "";
        return "<measure number=\"" + number + "\">" + attrs + String.join("", content) + "</measure>";
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

    private static String music(Score s) {
        return Generate.generateProject(s).files().entrySet().stream()
                .filter(e -> e.getKey().startsWith("parts/")).map(java.util.Map.Entry::getValue)
                .findFirst().orElseThrow();
    }

    /** One measure with everything: tempo, a slur, p, a hairpin, words and a trill. */
    private static Score everything() {
        return score(measure(1,
                direction("above", "<direction-type><words>Allegro.</words></direction-type>"
                        + "<direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>72.</per-minute>"
                        + "</metronome></direction-type>"),
                direction("below", "<direction-type><dynamics><p/></dynamics></direction-type>"),
                direction("below", "<direction-type><words>leggiero</words></direction-type>"),
                note("C", slur("start")),
                direction("below", "<direction-type><wedge type=\"crescendo\" number=\"1\"/></direction-type>"),
                note("D", slur("stop")),
                note("E"),
                direction("below", "<direction-type><wedge type=\"stop\" number=\"1\"/></direction-type>"),
                note("F", "<notations><ornaments><trill-mark/></ornaments></notations>")));
    }

    @Test
    void importsEveryMark() {
        Score s = everything();
        Measure m = measures(s).get(0);
        List<String> kinds = m.directions.stream().map(d -> d.kind).toList();
        assertEquals(List.of("words", "metronome", "dynamics", "words", "wedge", "wedge"), kinds);
        Direction metronome = m.directions.get(1);
        assertEquals("quarter", metronome.type);
        assertEquals("72.", metronome.text);
        assertEquals("p", m.directions.get(2).text);
        assertEquals("crescendo", m.directions.get(4).type);
        assertEquals("1", m.directions.get(4).offset.toString());
        List<Event> events = m.voices.get(0).events;
        assertEquals(List.of(new Slur("start", 1)), events.get(0).slurs);
        assertEquals(List.of(new Slur("stop", 1)), events.get(1).slurs);
        assertEquals(List.of("trill-mark"), events.get(3).articulations);
    }

    @Test
    void engravesEveryMark() {
        String ly = music(everything());
        assertTrue(ly.contains("\\tempo \"Allegro.\" 4 = 72 c''4(\\p_\"leggiero\" d''4)\\< e''4 f''4\\trill\\!")
                || ly.contains("\\tempo \"Allegro.\" 4 = 72 c''4(\\p_\"leggiero\" d''4)\\< e''4 f''4\\!\\trill"), ly);
        assertFalse(ly.contains("^\"Allegro.\""), ly); // the tempo's words are not printed twice
    }

    @Test
    void slursWithoutBothEndsInOneVoiceAreLeftOut() {
        Score s = score(measure(1, note("C", slur("start")), note("D"), note("E"), note("F")),
                measure(2, note("C", slur("stop")), note("D", slur("stop")), note("E"), note("F")));
        String ly = music(s);
        assertTrue(ly.contains("c''4( d''4 e''4 f''4"), ly);
        assertTrue(ly.contains("c''4) d''4 e''4 f''4"), ly); // the second stop has no start
        Score lone = score(measure(1, note("C", slur("start")), note("D"), note("E"), note("F")));
        assertFalse(music(lone).contains("("), music(lone));
    }

    @Test
    void chordKeepsTheSlurOfItsSecondNote() {
        String chordNote = "<note><chord/><pitch><step>E</step><octave>5</octave></pitch><duration>1</duration>"
                + "<voice>1</voice><type>quarter</type>" + slur("start") + "</note>";
        Score s = score(measure(1, note("C"), chordNote, note("D", slur("stop")), note("E"), note("F")));
        assertEquals(List.of(new Slur("start", 1)), measures(s).get(0).voices.get(0).events.get(0).slurs);
    }

    @Test
    void hairpinWithoutAnEndStopsOnTheLastNote() {
        Score s = score(measure(1,
                direction("below", "<direction-type><wedge type=\"diminuendo\"/></direction-type>"),
                note("C"), note("D"), note("E"), note("F")));
        String ly = music(s);
        assertTrue(ly.contains("c''4\\> d''4 e''4 f''4\\!"), ly);
    }

    @Test
    void junkWordsAreDroppedAndQuotesEscaped() {
        Score s = score(measure(1,
                direction("below", "<direction-type><words>_</words></direction-type>"),
                direction("above", "<direction-type><words>sing \"sweetly\"</words></direction-type>"),
                note("C"), note("D"), note("E"), note("F")));
        assertEquals(1, measures(s).get(0).directions.size());
        assertTrue(music(s).contains("c''4^\"sing \\\"sweetly\\\"\""), music(s));
    }

    @Test
    void continuationLinesReadAsDotsAreDropped() {
        Score s = score(measure(1,
                direction("above", "<direction-type><words>8 .......................</words></direction-type>"),
                direction("below", "<direction-type><words>cresc. - - - -</words></direction-type>"),
                direction("below", "<direction-type><words>. . . . .</words></direction-type>"),
                note("C"), note("D"), note("E"), note("F")));
        assertEquals(List.of("8", "cresc."), measures(s).get(0).directions.stream().map(d -> d.text).toList());
    }

    @Test
    void otherDynamicsUseADynamicScript() {
        Score s = score(measure(1,
                direction("below", "<direction-type><dynamics><sfp/></dynamics></direction-type>"),
                note("C"), note("D"), note("E"), note("F")));
        assertTrue(music(s).contains("c''4-#(make-dynamic-script \"sfp\")"), music(s));
    }

    @Test
    void musicXmlRoundTripKeepsEveryMark() {
        Score s = everything();
        Score back = MusicXml.parse(MusicXmlWriter.toMusicXml(s).getBytes(StandardCharsets.UTF_8), "back.musicxml");
        assertEquals(measures(s).get(0).directions, measures(back).get(0).directions);
        List<Event> a = measures(s).get(0).voices.get(0).events, b = measures(back).get(0).voices.get(0).events;
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).slurs, b.get(i).slurs);
            assertEquals(a.get(i).articulations, b.get(i).articulations);
        }
    }

    @Test
    void irJsonKeepsSlursAndOmitsThemWhenNone() {
        Score s = everything();
        String json = IrJson.toJson(s);
        assertTrue(json.contains("\"slurs\""), json);
        Score back = IrJson.readScore(json);
        assertEquals(measures(s).get(0).voices.get(0).events, measures(back).get(0).voices.get(0).events);
        assertFalse(IrJson.toJson(score(measure(1, note("C"), note("D"), note("E"), note("F")))).contains("slurs"));
    }

    @Test
    @Tag("lilypond")
    void compilesWithoutWarnings(@TempDir Path tmp) {
        LyProject p = Generate.generateProject(everything());
        p.write(tmp.resolve("ly"));
        Compile.Result r = CompileTest.compile(tmp.resolve("ly/main.ly"), tmp.resolve("out"), List.of("pdf"));
        assertTrue(r.ok(), r.log());
        assertEquals(List.of(), r.warnings(), r.log());
    }
}
