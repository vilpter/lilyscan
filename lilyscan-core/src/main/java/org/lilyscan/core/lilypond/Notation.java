package org.lilyscan.core.lilypond;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.KeySignature;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.TimeSignature;
import org.lilyscan.core.ir.Tuplet;

/** IR values -> LilyPond tokens (Dutch note names, absolute octaves). Port of notation.py. */
public final class Notation {
    private Notation() {}

    private static String alterSuffix(int alter) {
        return switch (alter) {
            case -2 -> "eses";
            case -1 -> "es";
            case 0 -> "";
            case 1 -> "is";
            case 2 -> "isis";
            default -> throw new IllegalArgumentException("alter " + alter);
        };
    }

    /** Dutch name without octave: c, cis, des, es (E-flat), as (A-flat), bes, ... */
    public static String pitchName(String step, int alter) {
        String base = step.toLowerCase(java.util.Locale.ROOT);
        if ((base.equals("e") || base.equals("a")) && alter < 0) {
            return base + alterSuffix(alter).substring(1); // es, eses -> es, ses; as, ases
        }
        return base + alterSuffix(alter);
    }

    /** LilyPond's unmarked octave is the one starting at C3. */
    public static String octaveMarks(int octave) {
        return octave >= 3 ? "'".repeat(octave - 3) : ",".repeat(3 - octave);
    }

    public static String absolutePitch(Pitch p) {
        return pitchName(p.step, p.alter) + octaveMarks(p.octave);
    }

    private static final Map<String, String> TYPE_DENOMINATOR = Map.ofEntries(
            Map.entry("maxima", "\\maxima"), Map.entry("long", "\\longa"), Map.entry("breve", "\\breve"),
            Map.entry("whole", "1"), Map.entry("half", "2"), Map.entry("quarter", "4"),
            Map.entry("eighth", "8"), Map.entry("16th", "16"), Map.entry("32nd", "32"),
            Map.entry("64th", "64"), Map.entry("128th", "128"), Map.entry("256th", "256"));

    /** An undotted value in quarter notes and its token. */
    private record Base(Frac value, String token) {}

    /** Undotted values in quarter notes, longest first. */
    private static final List<Base> BASES = List.of(
            new Base(Frac.of(8), "\\breve"), new Base(Frac.of(4), "1"), new Base(Frac.of(2), "2"),
            new Base(Frac.of(1), "4"), new Base(Frac.of(1, 2), "8"), new Base(Frac.of(1, 4), "16"),
            new Base(Frac.of(1, 8), "32"), new Base(Frac.of(1, 16), "64"), new Base(Frac.of(1, 32), "128"));

    public static Frac dottedValue(Frac base, int dots) {
        return base.multiply(Frac.of(2).subtract(Frac.of(1, 1L << dots)));
    }

    /** One LilyPond duration for {@code length} quarters, if one exists (e.g. 3/2 -> "4."). */
    public static String singleDuration(Frac length, int maxDots) {
        for (Base b : BASES) {
            for (int dots = 0; dots <= maxDots; dots++) {
                if (dottedValue(b.value(), dots).equals(length)) {
                    return b.token() + ".".repeat(dots);
                }
            }
        }
        return null;
    }

    public static String singleDuration(Frac length) {
        return singleDuration(length, 2);
    }

    private record Greedy(List<String> tokens, Frac rest) {}

    /** Greedy decomposition into dotted durations, and what is left over. */
    private static Greedy greedy(Frac length) {
        List<String> out = new ArrayList<>();
        Frac remaining = length;
        outer:
        while (remaining.signum() > 0) {
            for (Base b : BASES) {
                if (b.value().le(remaining)) {
                    int dots = 0;
                    while (dots < 2 && dottedValue(b.value(), dots + 1).le(remaining)) {
                        dots++;
                    }
                    out.add(b.token() + ".".repeat(dots));
                    remaining = remaining.subtract(dottedValue(b.value(), dots));
                    continue outer;
                }
            }
            break; // shorter than a 128th
        }
        return new Greedy(out, remaining);
    }

    /** Greedy decomposition into dotted durations (a remainder under a 128th is dropped). */
    public static List<String> splitDuration(Frac length) {
        return greedy(length).tokens();
    }

    /** Spacer rests lasting exactly {@code length} quarters; a remainder dotted durations cannot
     *  reach ends with a scaled skip, {@code s1*1/12}. */
    public static List<String> skips(Frac length) {
        Greedy g = greedy(length);
        List<String> tokens = new ArrayList<>(g.tokens());
        if (length.signum() > 0 && g.rest().signum() > 0) {
            tokens.add("1*" + g.rest().divide(4));
        }
        List<String> out = new ArrayList<>();
        for (String t : tokens) {
            out.add("s" + t);
        }
        return out;
    }

    /** Duration token for an event, preferring its notated type. */
    public static String writtenDuration(String noteType, int dots, Frac duration, Tuplet tuplet) {
        if (noteType != null && TYPE_DENOMINATOR.containsKey(noteType)) {
            return TYPE_DENOMINATOR.get(noteType) + ".".repeat(dots);
        }
        Frac written = tuplet != null ? duration.multiply(Frac.of(tuplet.actual(), tuplet.normal())) : duration;
        String token = singleDuration(written, 3);
        return token != null ? token : "4";
    }

    private static final String[] MAJOR_TONICS = {"ces", "ges", "des", "as", "es", "bes", "f", "c", "g",
        "d", "a", "e", "b", "fis", "cis"};
    private static final String[] MINOR_TONICS = {"as", "es", "bes", "f", "c", "g", "d", "a", "e", "b",
        "fis", "cis", "gis", "dis", "ais"};

    public static String keyCommand(KeySignature key) {
        if ("minor".equals(key.mode)) {
            return "\\key " + MINOR_TONICS[key.fifths + 7] + " \\minor";
        }
        return "\\key " + MAJOR_TONICS[key.fifths + 7] + " \\major";
    }

    public static String timeCommand(TimeSignature time) {
        return "\\time " + time.beats + "/" + time.beatType;
    }

    private static final Map<String, String> CLEFS = Map.of(
            "G2", "treble", "G1", "french", "F4", "bass", "F3", "varbaritone", "F5", "subbass",
            "C1", "soprano", "C2", "mezzosoprano", "C3", "alto", "C4", "tenor", "C5", "baritone");

    public static String clefCommand(Clef clef) {
        if ("percussion".equals(clef.sign)) {
            return "\\clef percussion";
        }
        if ("TAB".equals(clef.sign) || "none".equals(clef.sign)) {
            return "\\clef treble";
        }
        int defaultLine = switch (clef.sign) {
            case "G" -> 2;
            case "F" -> 4;
            case "C" -> 3;
            default -> throw new IllegalArgumentException("clef sign " + clef.sign);
        };
        int line = clef.line != null && clef.line != 0 ? clef.line : defaultLine;
        String name = CLEFS.getOrDefault(clef.sign + line, "treble");
        if (clef.octaveChange != 0) {
            String mark = clef.octaveChange < 0 ? "_" : "^";
            return "\\clef \"" + name + mark + (Math.abs(clef.octaveChange) == 1 ? 8 : 15) + "\"";
        }
        return "\\clef " + name;
    }

    /** MusicXML {@code <kind>} -> LilyPond chordmode modifier. */
    public static final Map<String, String> CHORD_MODIFIERS = Map.ofEntries(
            Map.entry("major", ""), Map.entry("minor", ":m"), Map.entry("augmented", ":aug"),
            Map.entry("diminished", ":dim"), Map.entry("dominant", ":7"), Map.entry("major-seventh", ":maj7"),
            Map.entry("minor-seventh", ":m7"), Map.entry("diminished-seventh", ":dim7"),
            Map.entry("augmented-seventh", ":aug7"), Map.entry("half-diminished", ":m7.5-"),
            Map.entry("major-minor", ":m7+"), Map.entry("major-sixth", ":6"), Map.entry("minor-sixth", ":m6"),
            Map.entry("dominant-ninth", ":9"), Map.entry("major-ninth", ":maj9"), Map.entry("minor-ninth", ":m9"),
            Map.entry("dominant-11th", ":11"), Map.entry("major-11th", ":maj11"), Map.entry("minor-11th", ":m11"),
            Map.entry("dominant-13th", ":13"), Map.entry("major-13th", ":maj13"), Map.entry("minor-13th", ":m13"),
            Map.entry("suspended-second", ":sus2"), Map.entry("suspended-fourth", ":sus4"),
            Map.entry("power", ":5"));

    /** {@code F#} / {@code Bb} / {@code Ebb} -> {@code fis} / {@code bes} / {@code eses}. */
    public static String chordRoot(String name) {
        String step = name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        int sharps = (int) name.chars().filter(c -> c == '#').count();
        int flats = (int) name.substring(1).chars().filter(c -> c == 'b').count();
        return pitchName(step, Math.max(-2, Math.min(2, sharps - flats)));
    }

    private static final String[] TRANSPOSITION_NAMES = {"c", "des", "d", "es", "e", "f", "ges", "g", "as",
        "a", "bes", "b"};

    /** Written-to-sounding interval as a \transposition pitch (0 -> c', -2 -> bes). */
    public static String transpositionPitch(int semitones) {
        int midi = 60 + semitones;
        int octave = Math.floorDiv(midi, 12) - 1;
        return TRANSPOSITION_NAMES[Math.floorMod(midi, 12)] + octaveMarks(octave);
    }

    public static String lilyString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
