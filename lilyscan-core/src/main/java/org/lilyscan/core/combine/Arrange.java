package org.lilyscan.core.combine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Ir;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Transpose;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.util.Py;

/**
 * Piano accompaniment (port of {@code lilyscan/arrange.py}): an ensemble's other parts reduced
 * onto a piano grand staff, by register; same rhythms in a measure merge into chords.
 */
public final class Arrange {
    private Arrange() {}

    static final int MIDDLE_C = 60;
    /** Per staff, as the LilyPond generator engraves. */
    static final int MAX_VOICES = 4;
    private static final Pattern OCTAVE_LOWER = Pattern.compile(
            "contrabass|double\\s*bass|^bass\\b|^cb\\.?$|^db\\.?$|kontrabass",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS);

    /** Semitones from written to sounding pitch. */
    public static int soundingShift(Part part) {
        if (part.transposeSemitones != null && part.transposeSemitones != 0) return part.transposeSemitones;
        for (String n : new String[] {part.name, part.abbreviation}) {
            if (OCTAVE_LOWER.matcher(Py.strip(n == null ? "" : n)).find()) return -12;
        }
        return 0;
    }

    private static Double medianPitch(Part part) {
        List<Integer> pitches = new ArrayList<>();
        for (Staff st : part.staves) for (Measure m : st.measures) for (Voice v : m.voices) for (Event e : v.events) {
            for (Pitch p : e.pitches()) pitches.add(p.midi());
        }
        return pitches.isEmpty() ? null : Py.median(pitches);
    }

    private record Beat(Frac offset, Frac duration, boolean grace, boolean rest) {}

    private static List<Beat> rhythm(Voice voice) {
        List<Beat> out = new ArrayList<>();
        for (Event e : voice.events) out.add(new Beat(e.offset, e.duration, e.grace, Event.REST.equals(e.kind)));
        return out;
    }

    private static boolean plays(Voice voice) {
        return voice.events.stream().anyMatch(e -> !e.notes.isEmpty());
    }

    /** Events at the same onset, of the same length, as one (a chord when they differ). */
    static Event chord(List<Event> events) {
        Event first = events.get(0);
        if (Event.REST.equals(first.kind)) {
            Event r = Ir.deepCopy(first);
            r.lyrics = new ArrayList<>();
            return r;
        }
        Map<Pitch.Key, NoteHead> heads = new LinkedHashMap<>();
        for (Event e : events) {
            for (NoteHead h : e.notes) {
                Pitch.Key key = h.pitch.key();
                NoteHead old = heads.get(key);
                if (old != null) { // a unison: keep one head, tied if either is
                    NoteHead merged = Ir.copy(old);
                    merged.tieStart = old.tieStart || h.tieStart;
                    merged.tieStop = old.tieStop || h.tieStop;
                    heads.put(key, merged);
                } else {
                    heads.put(key, Ir.deepCopy(h));
                }
            }
        }
        List<NoteHead> notes = new ArrayList<>(heads.values());
        notes.sort(Comparator.comparingInt(h -> h.pitch.midi()));
        Double conf = null;
        for (Event e : events) if (e.confidence != null) conf = conf == null ? e.confidence : Math.min(conf, e.confidence);
        // Bowings tell a string player which way to draw the bow: not for the piano.
        TreeSet<String> arts = new TreeSet<>();
        for (Event e : events) for (String a : e.articulations) if (!Event.BOWINGS.contains(a)) arts.add(a);
        Event out = Ir.deepCopy(first);
        out.kind = notes.size() > 1 ? Event.CHORD : Event.NOTE;
        out.notes = notes;
        out.lyrics = new ArrayList<>();
        out.articulations = new ArrayList<>(arts);
        out.confidence = conf;
        out.bbox = null;
        return out;
    }

    /** One staff's voices in one measure, highest first: same rhythms become chords. */
    static List<Voice> merge(List<Voice> voices) {
        List<Voice> playing = new ArrayList<>();
        for (Voice v : voices) if (plays(v)) playing.add(v);
        if (playing.isEmpty()) {
            if (voices.isEmpty()) return new ArrayList<>();
            Voice v = Ir.deepCopy(voices.get(0));
            v.number = 1;
            return new ArrayList<>(List.of(v));
        }
        Map<List<Beat>, List<Voice>> groups = new LinkedHashMap<>();
        for (Voice v : playing) groups.computeIfAbsent(rhythm(v), k -> new ArrayList<>()).add(v);
        List<Voice> merged = new ArrayList<>();
        for (List<Voice> group : groups.values()) {
            List<Event> events = new ArrayList<>();
            for (int i = 0; i < group.get(0).events.size(); i++) {
                List<Event> at = new ArrayList<>();
                for (Voice v : group) at.add(v.events.get(i));
                events.add(chord(at));
            }
            Voice v = new Voice();
            v.number = 0;
            v.events = events;
            merged.add(v);
        }
        merged.sort(Comparator.comparingDouble(Arrange::height));
        if (merged.size() > MAX_VOICES) {
            // Rare with string parts: keep the top voices and the bass line.
            List<Voice> kept = new ArrayList<>(merged.subList(0, MAX_VOICES - 1));
            kept.add(merged.get(merged.size() - 1));
            merged = kept;
        }
        List<Voice> out = new ArrayList<>();
        for (int k = 0; k < merged.size(); k++) {
            Voice v = Ir.copy(merged.get(k));
            v.number = k + 1;
            out.add(v);
        }
        return out;
    }

    private static double height(Voice v) {
        List<Integer> pitches = new ArrayList<>();
        for (Event e : v.events) for (Pitch p : e.pitches()) pitches.add(p.midi());
        return pitches.isEmpty() ? 0.0 : -Py.median(pitches);
    }

    /** The piano part made from {@code parts}, measure by measure along {@code reference}. */
    public static Part reduceToPiano(List<Part> parts, Part reference, String name) {
        List<Part> upper = new ArrayList<>(), lower = new ArrayList<>();
        for (Part p : parts) {
            int shift = soundingShift(p);
            Part sounding = shift != 0 ? Transpose.transposePart(p, Transpose.Interval.fromSemitones(shift)) : p;
            Double centre = medianPitch(sounding);
            (centre != null && centre < MIDDLE_C ? lower : upper).add(sounding);
        }
        List<Measure> ref = reference.staves.get(0).measures;
        List<Staff> staves = new ArrayList<>();
        Object[][] layout = {{1, upper, new Clef("G", 2, 0, Frac.ZERO)}, {2, lower, new Clef("F", 4, 0, Frac.ZERO)}};
        for (Object[] row : layout) {
            int number = (Integer) row[0];
            @SuppressWarnings("unchecked")
            List<Part> sources = (List<Part>) row[1];
            Clef clef = (Clef) row[2];
            List<Measure> measures = new ArrayList<>();
            for (int i = 0; i < ref.size(); i++) {
                Measure r = ref.get(i);
                List<Voice> voices = new ArrayList<>();
                for (Part s : sources) for (Staff st : s.staves) {
                    if (i < st.measures.size()) for (Voice v : st.measures.get(i).voices) if (!v.events.isEmpty()) voices.add(v);
                }
                Measure m = new Measure();
                m.index = r.index;
                m.number = r.number;
                m.clefs = i == 0 ? new ArrayList<>(List.of(clef)) : new ArrayList<>();
                m.key = r.key;
                m.time = r.time;
                m.implicit = r.implicit;
                m.leftBarline = r.leftBarline;
                m.rightBarline = r.rightBarline;
                m.voices = merge(voices);
                measures.add(m);
            }
            staves.add(new Staff(number, measures));
        }
        Part piano = new Part();
        piano.id = name;
        piano.name = name;
        piano.abbreviation = "Pno.";
        piano.staves = staves;
        return piano;
    }
}
