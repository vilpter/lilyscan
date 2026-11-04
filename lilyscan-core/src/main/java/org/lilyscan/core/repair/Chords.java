package org.lilyscan.core.repair;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lilyscan.core.util.Py;

/** Chord-name grammar (port of {@code lilyscan/repair/chords.py}): printed chord symbols to MusicXML kinds. */
public final class Chords {
    private Chords() {}

    private static final Pattern NAME =
            Pattern.compile("^([A-G])([#b♯♭]?)([^/]*?)(?:/([A-G])([#b♯♭]?))?$", Pattern.UNIX_LINES);

    /** Printed suffix to MusicXML {@code <kind>}. Case matters only for m (minor) vs M (major). */
    static final Map<String, String> KINDS = new LinkedHashMap<>();
    /** Suffixes that can be matched case-insensitively (OCR often upper-cases "SUS4"). */
    static final Map<String, String> FOLDED = new LinkedHashMap<>();
    static {
        KINDS.put("", "major");
        KINDS.put("M", "major");
        KINDS.put("maj", "major");
        KINDS.put("m", "minor");
        KINDS.put("mi", "minor");
        KINDS.put("min", "minor");
        KINDS.put("-", "minor");
        KINDS.put("7", "dominant");
        KINDS.put("maj7", "major-seventh");
        KINDS.put("M7", "major-seventh");
        KINDS.put("ma7", "major-seventh");
        KINDS.put("Δ7", "major-seventh");
        KINDS.put("Δ", "major-seventh");
        KINDS.put("m7", "minor-seventh");
        KINDS.put("mi7", "minor-seventh");
        KINDS.put("min7", "minor-seventh");
        KINDS.put("-7", "minor-seventh");
        KINDS.put("dim", "diminished");
        KINDS.put("°", "diminished");
        KINDS.put("dim7", "diminished-seventh");
        KINDS.put("°7", "diminished-seventh");
        KINDS.put("m7b5", "half-diminished");
        KINDS.put("m7-5", "half-diminished");
        KINDS.put("-7b5", "half-diminished");
        KINDS.put("ø", "half-diminished");
        KINDS.put("ø7", "half-diminished");
        KINDS.put("aug", "augmented");
        KINDS.put("+", "augmented");
        KINDS.put("aug7", "augmented-seventh");
        KINDS.put("+7", "augmented-seventh");
        KINDS.put("mmaj7", "major-minor");
        KINDS.put("mM7", "major-minor");
        KINDS.put("m(maj7)", "major-minor");
        KINDS.put("6", "major-sixth");
        KINDS.put("m6", "minor-sixth");
        KINDS.put("9", "dominant-ninth");
        KINDS.put("maj9", "major-ninth");
        KINDS.put("m9", "minor-ninth");
        KINDS.put("11", "dominant-11th");
        KINDS.put("m11", "minor-11th");
        KINDS.put("13", "dominant-13th");
        KINDS.put("maj13", "major-13th");
        KINDS.put("m13", "minor-13th");
        KINDS.put("sus2", "suspended-second");
        KINDS.put("sus4", "suspended-fourth");
        KINDS.put("sus", "suspended-fourth");
        KINDS.put("5", "power");
        KINDS.forEach((k, v) -> {
            if (!k.toLowerCase(Locale.ROOT).contains("m")) FOLDED.put(k.toLowerCase(Locale.ROOT), v);
        });
    }

    /** A parsed chord name: root ("F#"), MusicXML kind, bass (or null), the text as read. */
    public record ChordName(String root, String kind, String bass, String text) {
        /** More than a bare letter, so unlikely to be a word or syllable. */
        public boolean decorated() {
            return !kind.equals("major") || bass != null || root.length() > 1;
        }
    }

    private static String accidental(String mark) {
        return switch (mark) {
            case "♯" -> "#";
            case "♭" -> "b";
            default -> mark;
        };
    }

    /** {@code "F#m7b5"} to ChordName("F#", "half-diminished", null); null if not a chord name. */
    public static ChordName parse(String text) {
        String t = Py.strip(text);
        Matcher m = NAME.matcher(t);
        if (!m.find()) return null;
        String suffix = m.group(3).replace(" ", "");
        String kind = KINDS.get(suffix);
        if (kind == null || kind.isEmpty()) kind = FOLDED.get(suffix.toLowerCase(Locale.ROOT));
        if (kind == null) return null;
        String bass = m.group(4) != null ? m.group(4) + accidental(m.group(5)) : null;
        return new ChordName(m.group(1) + accidental(m.group(2)), kind, bass, t);
    }
}
