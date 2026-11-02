package org.lilyscan.core.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lilyscan.core.util.Py;

/**
 * Transposition of IR scores by a spelled interval (port of {@code lilyscan/ir/transpose.py}).
 * An interval has a diatonic size (steps) and a chromatic size (semitones), so spelling follows
 * it: a major second up takes F# to G#, not A-flat. Keys and chord symbols move with the notes.
 */
public final class Transpose {
    private Transpose() {}

    private static final String STEPS = "CDEFGAB";
    private static final int[] NATURAL = {0, 2, 4, 5, 7, 9, 11};
    private static final Map<String, Integer> FIFTHS = Map.of("F", -1, "C", 0, "G", 1, "D", 2, "A", 3, "E", 4, "B", 5);
    private static final Pattern NAME = Pattern.compile("^([+-]?)([PMmAd])(\\d+)$", Pattern.UNIX_LINES);
    private static final String[] BY_SEMITONES = {"P1", "m2", "M2", "m3", "M3", "P4", "A4", "P5", "m6", "M6", "m7", "M7"};

    /** An interval: diatonic steps and semitones, negative downwards. */
    public record Interval(int steps, int semitones) {
        /** {@code "M2"}, {@code "-m3"}, {@code "P5"}, {@code "A4"}, {@code "P8"}, {@code "M9"} (a sign means down). */
        public static Interval parse(String text) {
            Matcher m = NAME.matcher(Py.strip(text));
            if (!m.find()) throw new IllegalArgumentException("not an interval: " + pyRepr(text) + " (expected e.g. M2, -m3, P5)");
            String sign = m.group(1), quality = m.group(2);
            int n = Integer.parseInt(m.group(3));
            if (n < 1) throw new IllegalArgumentException("not an interval: " + pyRepr(text));
            int steps = n - 1;
            int simple = steps % 7, octaves = steps / 7;
            int base = NATURAL[simple] + 12 * octaves;
            boolean perfect = simple == 0 || simple == 3 || simple == 4;
            Integer offset = perfect
                    ? switch (quality) { case "P" -> 0; case "A" -> 1; case "d" -> -1; default -> null; }
                    : switch (quality) { case "M" -> 0; case "m" -> -1; case "A" -> 1; case "d" -> -2; default -> null; };
            if (offset == null) throw new IllegalArgumentException(pyRepr(text) + ": a " + n + " is not " + quality);
            int down = sign.equals("-") ? -1 : 1;
            return new Interval(down * steps, down * (base + offset));
        }

        /** The usual spelling of a chromatic size (e.g. -2 is a major second down). */
        public static Interval fromSemitones(int semitones) {
            int size = Math.abs(semitones);
            Interval simple = parse(BY_SEMITONES[size % 12]);
            int steps = simple.steps() + 7 * (size / 12);
            int sign = semitones < 0 ? -1 : 1;
            return new Interval(sign * steps, sign * size);
        }

        public Interval negate() {
            return new Interval(-steps, -semitones);
        }

        /** Python's dataclass repr, as it appears in error messages. */
        @Override
        public String toString() {
            return "Interval(steps=" + steps + ", semitones=" + semitones + ")";
        }
    }

    /** Python {@code repr()} of a string (single quotes unless the text holds one). */
    static String pyRepr(String s) {
        boolean dq = s.contains("'") && !s.contains("\"");
        char q = dq ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c == q) sb.append('\\').append(c);
                    else if (c < 0x20 || c == 0x7f) sb.append(String.format("\\x%02x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append(q).toString();
    }

    private static int naturalMidi(String step, int octave) {
        return 12 * (octave + 1) + NATURAL[STEPS.indexOf(step)];
    }

    public static Pitch transposePitch(Pitch p, Interval interval) {
        int diatonic = p.diatonic() + interval.steps();
        String step = String.valueOf(STEPS.charAt(Math.floorMod(diatonic, 7)));
        int octave = Math.floorDiv(diatonic, 7);
        int alter = p.midi() + interval.semitones() - naturalMidi(step, octave);
        if (alter < -2 || alter > 2) {
            throw new IllegalArgumentException(p.label() + " transposed by " + interval + " needs a triple accidental");
        }
        return new Pitch(step, alter, octave);
    }

    /** How far the interval moves a key along the line of fifths. */
    private static int fifthsShift(Interval interval) {
        Pitch c = transposePitch(new Pitch("C", 0, 4), interval);
        return FIFTHS.get(c.step) + 7 * c.alter;
    }

    public static KeySignature transposeKey(KeySignature key, Interval interval) {
        int fifths = key.fifths + fifthsShift(interval);
        if (fifths < -7 || fifths > 7) {
            throw new IllegalArgumentException("the key moves to " + fifths + " fifths; choose the enharmonic interval");
        }
        KeySignature out = new KeySignature();
        out.fifths = fifths;
        out.mode = key.mode;
        return out;
    }

    /** A chord root or bass such as {@code "F#"} or {@code "Bb"}. */
    private static String transposeName(String name, Interval interval) {
        int alter = (int) (name.chars().filter(c -> c == '#').count() - name.substring(1).chars().filter(c -> c == 'b').count());
        Pitch p = transposePitch(new Pitch(name.substring(0, 1).toUpperCase(java.util.Locale.ROOT), alter, 4), interval);
        return p.step + (p.alter > 0 ? "#".repeat(p.alter) : "b".repeat(-p.alter));
    }

    private static ChordSymbol transposeChord(ChordSymbol c, Interval interval) {
        String bass = c.bass != null && !c.bass.isEmpty() ? transposeName(c.bass, interval) : null;
        return new ChordSymbol(c.offset, transposeName(c.root, interval), c.kind, bass, null);
    }

    /** A copy of {@code part} transposed by {@code interval} (notes, keys and chord symbols). */
    public static Part transposePart(Part part, Interval interval) {
        Part out = Ir.deepCopy(part);
        for (Staff staff : out.staves) {
            for (Measure m : staff.measures) {
                if (m.key != null) m.key = transposeKey(m.key, interval);
                List<ChordSymbol> chords = new ArrayList<>();
                for (ChordSymbol c : m.chordSymbols) chords.add(transposeChord(c, interval));
                m.chordSymbols = chords;
                for (Voice v : m.voices) {
                    for (Event e : v.events) for (NoteHead head : e.notes) head.pitch = transposePitch(head.pitch, interval);
                }
            }
        }
        return out;
    }

    /** Written pitch to sounding pitch for every transposing part. */
    public static Score toConcertPitch(Score score) {
        Score out = Ir.deepCopy(score);
        for (int k = 0; k < out.parts.size(); k++) {
            Part part = out.parts.get(k);
            if (part.transposeSemitones != null && part.transposeSemitones != 0) {
                Part moved = transposePart(part, Interval.fromSemitones(part.transposeSemitones));
                moved.transposeSemitones = null;
                out.parts.set(k, moved);
            }
        }
        return out;
    }
}
