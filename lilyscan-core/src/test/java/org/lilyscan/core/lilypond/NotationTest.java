package org.lilyscan.core.lilypond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.KeySignature;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Tuplet;

/** Port of tests/test_notation.py. */
class NotationTest {

    @ParameterizedTest
    @CsvSource({"C,0,c", "C,1,cis", "D,-1,des", "E,-1,es", "E,-2,eses", "A,-1,as", "A,-2,ases", "B,-1,bes",
        "F,2,fisis"})
    void pitchNames(String step, int alter, String name) {
        assertEquals(name, Notation.pitchName(step, alter));
    }

    @ParameterizedTest
    @CsvSource(value = {"0|c,,,", "2|c,", "3|c", "4|c'", "6|c'''"}, delimiter = '|')
    void absoluteOctaves(int octave, String text) {
        assertEquals(text, Notation.absolutePitch(new Pitch("C", 0, octave)));
    }

    @Test
    void singleDuration() {
        assertEquals("1", Notation.singleDuration(Frac.of(4)));
        assertEquals("2.", Notation.singleDuration(Frac.of(3)));
        assertEquals("2..", Notation.singleDuration(Frac.of(7, 2)));
        assertEquals("4.", Notation.singleDuration(Frac.of(3, 2)));
        assertEquals("16", Notation.singleDuration(Frac.of(1, 4)));
        assertEquals("\\breve", Notation.singleDuration(Frac.of(8)));
        assertNull(Notation.singleDuration(Frac.of(5, 4)));
    }

    @Test
    void splitDurationSumsExactly() {
        assertEquals(List.of("1", "4"), Notation.splitDuration(Frac.of(5)));
        assertEquals(List.of("4", "16"), Notation.splitDuration(Frac.of(5, 4)));
    }

    @Test
    void skipsAreExactOffTheBinaryGrid() {
        assertEquals(List.of("s4", "s16"), Notation.skips(Frac.of(5, 4)));
        assertEquals(List.of("s8", "s32", "s128", "s1*1/384"), Notation.skips(Frac.of(2, 3)));
        assertEquals(List.of(), Notation.skips(Frac.ZERO));
    }

    @Test
    void writtenDurationPrefersNotatedType() {
        assertEquals("8.", Notation.writtenDuration("eighth", 1, Frac.of(3, 4), null));
        assertEquals("8", Notation.writtenDuration(null, 0, Frac.of(1, 3), new Tuplet(3, 2)));
        assertEquals("4.", Notation.writtenDuration(null, 0, Frac.of(3, 2), null));
    }

    @Test
    void keyAndClefCommands() {
        assertEquals("\\key c \\minor", Notation.keyCommand(new KeySignature(-3, "minor")));
        assertEquals("\\key d \\major", Notation.keyCommand(new KeySignature(2, null)));
        assertEquals("\\clef alto", Notation.clefCommand(new Clef("C", 3, 0, Frac.ZERO)));
        assertEquals("\\clef \"treble_8\"", Notation.clefCommand(new Clef("G", 2, -1, Frac.ZERO)));
        assertEquals("\\clef bass", Notation.clefCommand(new Clef("F", 4, 0, Frac.ZERO)));
    }

    @Test
    void chordRootAndTransposition() {
        assertEquals("fis", Notation.chordRoot("F#"));
        assertEquals("bes", Notation.chordRoot("Bb"));
        assertEquals("es", Notation.chordRoot("Eb"));
        assertEquals("c'", Notation.transpositionPitch(0));
        assertEquals("bes", Notation.transpositionPitch(-2));
        assertEquals("es", Notation.transpositionPitch(-9));
        assertEquals("c''", Notation.transpositionPitch(12));
    }
}
