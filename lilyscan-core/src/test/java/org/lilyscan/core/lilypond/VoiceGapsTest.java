package org.lilyscan.core.lilypond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Voice;

/**
 * A voice with a gap inside the measure (a {@code <forward>} between two rests) is as long as
 * where it ends, not as the sum of its durations: the measure must not be padded past its length.
 */
class VoiceGapsTest {

    private static final String REST_QUARTER =
            "<note><rest/><duration>2</duration><voice>1</voice><type>quarter</type></note>";
    private static final String REST_EIGHTH =
            "<note><rest/><duration>1</duration><voice>1</voice><type>eighth</type></note>";
    private static final String FORWARD_EIGHTH = "<forward><duration>1</duration><voice>1</voice></forward>";
    private static final String NOTE_QUARTER = "<note><pitch><step>C</step><octave>5</octave></pitch>"
            + "<duration>2</duration><voice>1</voice><type>quarter</type></note>";

    private static Score score() {
        // The gapped voice is in P1; P2 fills the measure, so the measure is 2/4 long throughout.
        String attrs = "<attributes><divisions>2</divisions><time><beats>2</beats>"
                + "<beat-type>4</beat-type></time><clef><sign>F</sign><line>4</line></clef></attributes>";
        String xml = "<?xml version=\"1.0\"?><score-partwise version=\"4.0\"><part-list>"
                + "<score-part id=\"P1\"><part-name>Bass</part-name></score-part>"
                + "<score-part id=\"P2\"><part-name>Cello</part-name></score-part></part-list>"
                + "<part id=\"P1\"><measure number=\"1\">" + attrs
                + REST_QUARTER + FORWARD_EIGHTH + REST_EIGHTH + "</measure>"
                + "<measure number=\"2\">" + NOTE_QUARTER + NOTE_QUARTER + "</measure></part>"
                + "<part id=\"P2\"><measure number=\"1\">" + attrs + NOTE_QUARTER + NOTE_QUARTER
                + "</measure><measure number=\"2\">" + NOTE_QUARTER + NOTE_QUARTER + "</measure></part>"
                + "</score-partwise>";
        return MusicXml.parse(xml.getBytes(StandardCharsets.UTF_8), "test.musicxml");
    }

    @Test
    void voiceEndCountsTheGap() {
        Voice v = score().parts.get(0).staves.get(0).measures.get(0).voices.get(0);
        assertEquals(Frac.of(3, 2), v.duration());
        assertEquals(Frac.of(2), v.end());
    }

    @Test
    void gappedMeasureIsNotPaddedPastItsLength() {
        String music = Generate.generateProject(score()).files().entrySet().stream()
                .filter(e -> e.getKey().startsWith("parts/bass")).map(java.util.Map.Entry::getValue)
                .findFirst().orElseThrow();
        assertTrue(music.contains("r4 s8 r8 |"), music);
        assertTrue(!music.contains("r8 s8 |"), music);
    }
}
