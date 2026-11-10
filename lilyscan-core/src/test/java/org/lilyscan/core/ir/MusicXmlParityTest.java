package org.lilyscan.core.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.lilyscan.core.testing.Parity;
import org.lilyscan.core.testing.Repo;

/** The Java MusicXML reader must produce exactly the IR the Python reader produces. */
class MusicXmlParityTest {

    @TestFactory
    Stream<DynamicTest> matchesPythonReader() {
        return Parity.musicXmlCases().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            Score score = MusicXml.load(c.input(), c.source());
            Parity.assertSameText(Repo.read(c.reference()), Parity.pythonIrJson(score), c.name());
        }));
    }

    @Test
    void rejectsNonScores() {
        assertThrows(MusicXml.MusicXmlException.class, () -> MusicXml.parse(
                "<score-timewise/>".getBytes(StandardCharsets.UTF_8), "musicxml"));
        assertThrows(MusicXml.MusicXmlException.class, () -> MusicXml.parse(
                "<html/>".getBytes(StandardCharsets.UTF_8), "musicxml"));
    }

    @Test
    void addsMeasuresForAMultiMeasureRestWrittenAsOne() {
        String xml = """
                <score-partwise><part-list><score-part id="P1"><part-name>Vln</part-name></score-part></part-list>
                <part id="P1">
                 <measure number="1"><attributes><divisions>1</divisions><time><beats>3</beats><beat-type>4</beat-type></time>
                  <measure-style><multiple-rest>3</multiple-rest></measure-style></attributes>
                  <note><rest measure="yes"/><duration>3</duration></note></measure>
                 <measure number="2"><note><pitch><step>A</step><octave>4</octave></pitch><duration>3</duration></note></measure>
                </part></score-partwise>""";
        Score s = MusicXml.parse(xml.getBytes(StandardCharsets.UTF_8), "audiveris");
        var measures = s.parts.get(0).staves.get(0).measures;
        assertEquals(4, measures.size());
        assertEquals("2", measures.get(1).number);
        assertEquals("4", measures.get(3).number); // renumbered after the added measures
        assertTrue(MusicXml.addedRest(measures.get(1)));
        assertEquals(java.util.List.of(0, 0, 0, 1), MusicXml.writtenMeasures(measures));
        assertEquals(Frac.of(3), measures.get(2).voices.get(0).events.get(0).duration);
    }
}
