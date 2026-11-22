package org.lilyscan.core.ir;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Voice numbers are per part in MusicXML, so the writer renumbers a staff's voice when another
 * staff has the same number. A voice moved up must not land on another voice of its own staff:
 * a piano part whose upper voice 1 dips into the lower staff (OpenScore Lieder do this) has
 * voices 1 and 5 on the lower staff, and both were written as voice 5 and read back as one.
 */
class MusicXmlWriterVoicesTest {
    @TempDir Path dir;

    private static Event note(String step, int octave, Frac offset, Frac duration) {
        Event e = new Event(Event.NOTE, offset, duration);
        e.notes.add(new NoteHead(new Pitch(step, 0, octave)));
        return e;
    }

    private static Measure measure() {
        Measure m = new Measure(0, "1");
        m.time = new TimeSignature(2, 4, "normal");
        return m;
    }

    @Test
    void crossStaffVoiceKeepsItsOwnNumber() {
        Measure upper = measure();
        upper.clefs.add(new Clef("G", 2, 0, Frac.ZERO));
        Voice u1 = upper.voice(1);
        u1.events.add(note("C", 5, Frac.ZERO, Frac.ONE));
        Event rest = new Event(Event.REST, Frac.ONE, Frac.ONE);
        u1.events.add(rest);

        Measure lower = measure();
        lower.clefs.add(new Clef("F", 4, 0, Frac.ZERO));
        // Voice 1 continues on the lower staff for its second beat ...
        lower.voice(1).events.add(note("G", 3, Frac.ONE, Frac.ONE));
        // ... while the lower staff's own voice 5 holds a half note.
        lower.voice(5).events.add(note("G", 2, Frac.ZERO, Frac.of(2)));

        Part p = new Part("P1", "Piano", null);
        p.staves.add(new Staff(1, new ArrayList<>(List.of(upper))));
        p.staves.add(new Staff(2, new ArrayList<>(List.of(lower))));
        Score s = new Score();
        s.parts.add(p);

        Score back = MusicXml.load(MusicXmlWriter.write(s, dir.resolve("x.musicxml")), "x");
        Measure m = back.parts.get(0).staves.get(1).measures.get(0);
        List<Voice> voices = m.voices.stream().filter(v -> !v.events.isEmpty()).toList();
        assertEquals(2, voices.size(), "two voices on the lower staff: " + voices);
        for (Voice v : voices) {
            for (int i = 1; i < v.events.size(); i++) {
                Event a = v.events.get(i - 1), b = v.events.get(i);
                assertFalse(b.offset.lt(a.offset.add(a.duration)), "overlap in voice " + v.number + ": " + v.events);
            }
        }
    }
}
