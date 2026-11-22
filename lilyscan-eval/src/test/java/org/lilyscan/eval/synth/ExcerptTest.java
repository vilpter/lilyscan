package org.lilyscan.eval.synth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.ir.ChordSymbol;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.Score;

class ExcerptTest {
    @TempDir Path dir;

    /** One measure with a fingering entered as a chord symbol (as in OpenScore's Viardot) and a real chord. */
    private static final String SOURCE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="3.1">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>1</divisions><time><beats>2</beats><beat-type>4</beat-type></time>
                    <clef><sign>G</sign><line>2</line></clef></attributes>
                  <harmony print-frame="no"><root><root-step text="">C</root-step></root><kind text="4">none</kind></harmony>
                  <note><pitch><step>G</step><octave>5</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type></note>
                  <harmony><root><root-step>F</root-step></root><kind>major</kind></harmony>
                  <note><pitch><step>A</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type></note>
                </measure>
              </part>
            </score-partwise>
            """;

    @Test
    void fingeringsEnteredAsChordSymbolsAreDropped() throws Exception {
        Path f = dir.resolve("src.musicxml");
        Files.writeString(f, SOURCE);
        Score s = Excerpt.of(MusicXml.load(f, "src"), 1, 1);
        Measure m = s.parts.get(0).staves.get(0).measures.get(0);
        assertEquals(2, m.chordSymbols.size());
        assertEquals(1, Excerpt.dropFingeringChords(s));
        List<String> left = new ArrayList<>();
        for (ChordSymbol c : m.chordSymbols) left.add(c.root + " " + c.kind);
        assertEquals(List.of("F major"), left);
    }
}
