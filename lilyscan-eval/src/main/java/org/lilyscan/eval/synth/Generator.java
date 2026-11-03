package org.lilyscan.eval.synth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lilyscan.core.ir.Barline;
import org.lilyscan.core.ir.ChordSymbol;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.KeySignature;
import org.lilyscan.core.ir.Lyric;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.TimeSignature;
import org.lilyscan.core.ir.Tuplet;
import org.lilyscan.core.ir.Voice;

/**
 * The seed corpus's generator ({@code lilyscan/synth/generate.py}): random-but-valid pieces in six
 * categories, fully determined by category and seed. Every random draw is made in the same order
 * as the Python generator, with {@link PyRandom}, so the same spec gives the same music; the piece
 * is built as a Lilyscan score and written as MusicXML by Lilyscan's own writer.
 */
public final class Generator {
    private Generator() {}

    /** Lyrics: short public-domain liturgical and folk lines. */
    static final Map<String, List<String>> LYRICS = Map.of(
            "lat", List.of(
                    "Ky-ri-e e-le-i-son Chri-ste e-le-i-son",
                    "Glo-ri-a in ex-cel-sis De-o et in ter-ra pax ho-mi-ni-bus",
                    "Ag-nus De-i qui tol-lis pec-ca-ta mun-di mi-se-re-re no-bis",
                    "San-ctus san-ctus san-ctus Do-mi-nus De-us Sa-ba-oth"),
            "deu", List.of(
                    "Lo-be den Her-ren den mäch-ti-gen Kö-nig der Eh-ren",
                    "Ein fes-te Burg ist un-ser Gott ein gu-te Wehr und Waf-fen",
                    "Nun dan-ket al-le Gott mit Her-zen Mund und Hän-den"),
            "fra", List.of(
                    "Au clair de la lu-ne mon a-mi Pier-rot prê-te-moi ta plu-me",
                    "Il est né le di-vin En-fant jou-ez haut-bois ré-son-nez mu-set-tes",
                    "À la claire fon-tai-ne m'en al-lant pro-me-ner"),
            "eng", List.of(
                    "A-maz-ing grace how sweet the sound that saved a wretch like me",
                    "O come all ye faith-ful joy-ful and tri-um-phant"));

    private static final String LETTERS = "CDEFGAB";
    private static final String SHARP_ORDER = "FCGDAEB";
    private static final String FLAT_ORDER = "BEADGCF";

    /** Diatonic ranges (C4 = 28) per voice or instrument. */
    private static final Map<String, int[]> RANGES = Map.ofEntries(
            Map.entry("soprano", new int[] {28, 39}),
            Map.entry("alto", new int[] {25, 35}),
            Map.entry("tenor", new int[] {21, 32}),
            Map.entry("bass", new int[] {16, 28}),
            Map.entry("solo", new int[] {29, 41}),
            Map.entry("melody", new int[] {28, 39}),
            Map.entry("violin", new int[] {26, 42}),
            Map.entry("viola", new int[] {21, 36}),
            Map.entry("cello", new int[] {14, 30}),
            Map.entry("rh", new int[] {28, 42}),
            Map.entry("lh", new int[] {14, 28}));

    static final Clef TREBLE = new Clef("G", 2, 0, Frac.ZERO);
    static final Clef TREBLE_8VB = new Clef("G", 2, -1, Frac.ZERO);
    static final Clef BASS = new Clef("F", 4, 0, Frac.ZERO);
    static final Clef ALTO = new Clef("C", 3, 0, Frac.ZERO);

    /** The key's alterations; spells diatonic steps as pitches. */
    record KeyCtx(int fifths, Map<Character, Integer> alters) {
        static KeyCtx of(int fifths) {
            String order = fifths > 0 ? SHARP_ORDER : FLAT_ORDER;
            int sign = fifths > 0 ? 1 : -1;
            Map<Character, Integer> alters = new HashMap<>();
            for (int i = 0; i < Math.abs(fifths); i++) alters.put(order.charAt(i), sign);
            return new KeyCtx(fifths, alters);
        }

        Pitch pitch(int diatonic, int extra) {
            char letter = LETTERS.charAt(Math.floorMod(diatonic, 7));
            int alter = Math.max(-2, Math.min(2, alters.getOrDefault(letter, 0) + extra));
            return new Pitch(String.valueOf(letter), alter, Math.floorDiv(diatonic, 7));
        }

        Pitch pitch(int diatonic) {
            return pitch(diatonic, 0);
        }
    }

    private record Meter(int beats, int beatType) {
        Frac length() {
            return Frac.of(4L * beats, beatType);
        }

        Frac beat() {
            return beats == 6 && beatType == 8 ? Frac.of(3, 2) : Frac.ONE;
        }
    }

    private static List<List<Frac>> rhythmCells(Meter ts) {
        Frac q = Frac.ONE, e = Frac.of(1, 2), s = Frac.of(1, 4), t = Frac.of(1, 3);
        if (ts.beats() == 6 && ts.beatType() == 8) {
            return List.of(List.of(Frac.of(3, 2)), List.of(q, e), List.of(e, e, e), List.of(e, q),
                    List.of(Frac.of(3, 4), s, e), List.of(Frac.of(3)));
        }
        return List.of(List.of(q), List.of(q), List.of(e, e), List.of(e, e), List.of(e, s, s), List.of(s, s, e),
                List.of(s, s, s, s), List.of(Frac.of(3, 2), e), List.of(Frac.of(2)), List.of(Frac.of(3, 4), s),
                List.of(t, t, t));
    }

    private static Frac sum(List<Frac> cell) {
        Frac total = Frac.ZERO;
        for (Frac f : cell) total = total.add(f);
        return total;
    }

    static List<Frac> measureRhythm(PyRandom rng, Meter ts) {
        List<List<Frac>> cells = rhythmCells(ts);
        List<Frac> out = new ArrayList<>();
        Frac remaining = ts.length();
        while (remaining.signum() > 0) {
            List<List<Frac>> fitting = new ArrayList<>();
            for (List<Frac> c : cells) if (sum(c).le(remaining)) fitting.add(c);
            if (fitting.isEmpty()) {
                out.add(remaining);
                break;
            }
            List<Frac> cell = rng.choice(fitting);
            out.addAll(cell);
            remaining = remaining.subtract(sum(cell));
        }
        return out;
    }

    /** A random-walk melody inside a diatonic range. */
    static final class Walker {
        private final PyRandom rng;
        private final int lo, hi;
        int pos;

        Walker(PyRandom rng, int lo, int hi) {
            this.rng = rng;
            this.lo = lo;
            this.hi = hi;
            this.pos = rng.randint(lo + 2, hi - 2);
        }

        int next() {
            double r = rng.random();
            int step = r < 0.55 ? rng.choice(List.of(-1, 1)) : rng.choice(List.of(-2, 2));
            if (r > 0.85) step = rng.choice(List.of(-5, -4, -3, 3, 4, 5));
            pos += step;
            if (pos < lo || pos > hi) pos -= 2 * step;
            pos = Math.max(lo, Math.min(hi, pos));
            return pos;
        }
    }

    /** A word list's syllables and their kind: {@code single}, {@code begin}, {@code middle}, {@code end}. */
    static List<String[]> syllables(String text) {
        List<String[]> out = new ArrayList<>();
        for (String word : text.trim().split("\\s+")) {
            String[] parts = word.split("-", -1);
            if (parts.length == 1) {
                out.add(new String[] {parts[0], "single"});
                continue;
            }
            for (int i = 0; i < parts.length; i++) {
                out.add(new String[] {parts[i], i == 0 ? "begin" : i == parts.length - 1 ? "end" : "middle"});
            }
        }
        return out;
    }

    private static Event note(Frac offset, Frac ql, List<Pitch> pitches) {
        Event e = new Event(pitches.size() > 1 ? Event.CHORD : Event.NOTE, offset, ql);
        for (Pitch p : pitches) e.notes.add(new NoteHead(p));
        if (ql.denominator() % 3 == 0) e.tuplet = new Tuplet(3, 2);
        return e;
    }

    private static Event rest(Frac offset, Frac ql) {
        Event e = new Event(Event.REST, offset, ql);
        if (ql.denominator() % 3 == 0) e.tuplet = new Tuplet(3, 2);
        return e;
    }

    private static Measure measure(int i, int count, Meter ts, KeyCtx k, String mode, Clef clef) {
        Measure m = new Measure(i, Integer.toString(i + 1));
        if (i == 0) {
            m.clefs.add(new Clef(clef.sign, clef.line, clef.octaveChange, Frac.ZERO));
            m.key = new KeySignature(k.fifths(), mode);
            m.time = new TimeSignature(ts.beats(), ts.beatType(), "normal");
        }
        if (i == count - 1) {
            m.rightBarline = new Barline();
            m.rightBarline.style = "light-heavy";
        }
        return m;
    }

    /** One melodic line; returns the events that take a syllable (not tied continuations). */
    private static List<Event> fillLine(PyRandom rng, Staff staff, int measures, Meter ts, KeyCtx k, String mode,
            Clef clef, String range, List<List<Frac>> rhythms, double restP) {
        int[] r = RANGES.get(range);
        Walker walker = new Walker(rng, r[0], r[1]);
        List<Event> sung = new ArrayList<>();
        Event prev = null;
        for (int i = 0; i < measures; i++) {
            Measure m = measure(i, measures, ts, k, mode, clef);
            Voice v = m.voice(1);
            Frac offset = Frac.ZERO;
            List<Frac> rhythm = rhythms != null ? rhythms.get(i) : measureRhythm(rng, ts);
            for (Frac ql : rhythm) {
                if (rng.random() < restP) {
                    v.events.add(rest(offset, ql));
                    offset = offset.add(ql);
                    prev = null;
                    continue;
                }
                Event n;
                if (prev != null && rng.random() < 0.07) {
                    Pitch p = prev.notes.get(0).pitch;
                    n = note(offset, ql, List.of(new Pitch(p.step, p.alter, p.octave)));
                    prev.notes.get(0).tieStart = true;
                    n.notes.get(0).tieStop = true;
                } else {
                    int extra = rng.random() < 0.06 ? rng.choice(List.of(-1, 1)) : 0;
                    n = note(offset, ql, List.of(k.pitch(walker.next(), extra)));
                    sung.add(n);
                }
                v.events.add(n);
                offset = offset.add(ql);
                prev = n;
            }
            staff.measures.add(m);
        }
        return sung;
    }

    private static void addLyrics(List<Event> notes, String text) {
        List<String[]> syl = syllables(text);
        int cycles = notes.size() / Math.max(syl.size(), 1) + 1;
        List<String[]> pool = new ArrayList<>();
        for (int c = 0; c < cycles; c++) pool.addAll(syl);
        int n = Math.min(notes.size(), pool.size());
        // Stop at a word boundary so the text never ends on a dangling hyphen.
        while (n > 0 && (pool.get(n - 1)[1].equals("begin") || pool.get(n - 1)[1].equals("middle"))) n--;
        for (int i = 0; i < n; i++) notes.get(i).lyrics.add(new Lyric(pool.get(i)[0], 1, pool.get(i)[1], false));
    }

    private static Meter randomMeter(PyRandom rng) {
        return rng.choice(List.of(new Meter(4, 4), new Meter(3, 4), new Meter(2, 4), new Meter(6, 8)),
                new double[] {40, 25, 15, 20});
    }

    private static Part part(String id, String name, String abbreviation) {
        return new Part(id, name, abbreviation);
    }

    private static Staff staff(Part p, int number) {
        Staff s = new Staff(number, new ArrayList<>());
        p.staves.add(s);
        return s;
    }

    /** The piece for {@code spec} (a generated category; not a corpus work). */
    public static Score build(CorpusSpec.Piece spec) {
        PyRandom rng = new PyRandom(spec.category() + ":" + spec.seed());
        Meter ts = randomMeter(rng);
        KeyCtx k = KeyCtx.of(rng.randint(-4, 4));
        String mode = rng.random() < 0.3 ? "minor" : "major";
        Score score = new Score();
        score.title = "Lilyscan " + spec.id();
        score.composer = "Generated";
        score.source = "synthetic";
        int measures = spec.measures();
        switch (spec.category()) {
            case "solo" -> {
                Part p = part("P1", "Flute", "Fl.");
                fillLine(rng, staff(p, 1), measures, ts, k, mode, TREBLE, "solo", null, 0.08);
                score.parts.add(p);
            }
            case "quartet" -> {
                Object[][] strings = {
                    {"Violin I", "Vln. I", TREBLE, "violin"}, {"Violin II", "Vln. II", TREBLE, "violin"},
                    {"Viola", "Vla.", ALTO, "viola"}, {"Violoncello", "Vc.", BASS, "cello"}};
                for (int i = 0; i < strings.length; i++) {
                    Part p = part("P" + (i + 1), (String) strings[i][0], (String) strings[i][1]);
                    fillLine(rng, staff(p, 1), measures, ts, k, mode, (Clef) strings[i][2], (String) strings[i][3], null, 0.08);
                    score.parts.add(p);
                }
            }
            case "satb" -> {
                String text = rng.choice(LYRICS.get(spec.language() == null ? "lat" : spec.language()));
                // Homophonic: one shared rhythm and no rests, so the parts sing together.
                List<List<Frac>> rhythms = new ArrayList<>();
                for (int i = 0; i < measures; i++) rhythms.add(measureRhythm(rng, ts));
                Object[][] choir = {
                    {"Soprano", "S.", TREBLE, "soprano"}, {"Alto", "A.", TREBLE, "alto"},
                    {"Tenor", "T.", TREBLE_8VB, "tenor"}, {"Bass", "B.", BASS, "bass"}};
                for (int i = 0; i < choir.length; i++) {
                    Part p = part("P" + (i + 1), (String) choir[i][0], (String) choir[i][1]);
                    List<Event> sung = fillLine(rng, staff(p, 1), measures, ts, k, mode, (Clef) choir[i][2],
                            (String) choir[i][3], rhythms, 0.0);
                    addLyrics(sung, text);
                    score.parts.add(p);
                }
            }
            case "leadsheet" -> {
                String text = rng.choice(LYRICS.get(spec.language() == null ? "eng" : spec.language()));
                Part p = part("P1", "Voice", "Vo.");
                Staff s = staff(p, 1);
                List<Event> sung = fillLine(rng, s, measures, ts, k, mode, TREBLE, "melody", null, 0.08);
                addLyrics(sung, text);
                addChordSymbols(rng, s, k, ts);
                score.parts.add(p);
            }
            case "piano" -> {
                Part p = part("P1", "Piano", "Pno.");
                Staff rh = staff(p, 1), lh = staff(p, 2);
                fillLine(rng, rh, measures, ts, k, mode, TREBLE, "rh", null, 0.08);
                addInnerVoice(rng, rh, k, ts, 0.3);
                fillBassChords(rng, lh, measures, ts, k, mode);
                score.parts.add(p);
            }
            default -> throw new IllegalArgumentException("unknown category '" + spec.category() + "'");
        }
        return score;
    }

    /** Splits some measures into two voices: the line on top, longer notes below. */
    private static void addInnerVoice(PyRandom rng, Staff staff, KeyCtx k, Meter ts, double probability) {
        Frac length = ts.length(), beat = ts.beat();
        for (Measure m : staff.measures) {
            if (rng.random() >= probability) continue;
            Voice upper = m.voices.get(0);
            int floor = Integer.MAX_VALUE;
            for (Event e : upper.events) if (!e.isRest()) floor = Math.min(floor, e.notes.get(0).pitch.diatonic());
            if (floor == Integer.MAX_VALUE) continue;
            Voice lower = m.voice(2);
            Frac position = Frac.ZERO;
            while (position.lt(length)) {
                Frac ql = length.subtract(position).min(rng.choice(List.of(beat, beat.multiply(2))));
                int d = Math.max(24, floor - rng.randint(2, 4));
                lower.events.add(note(position, ql, List.of(k.pitch(d))));
                position = position.add(ql);
            }
        }
    }

    private static void fillBassChords(PyRandom rng, Staff staff, int measures, Meter ts, KeyCtx k, String mode) {
        Frac beat = ts.beat(), length = ts.length();
        Walker root = new Walker(rng, 14, 21);
        for (int i = 0; i < measures; i++) {
            Measure m = measure(i, measures, ts, k, mode, BASS);
            Voice v = m.voice(1);
            Frac offset = Frac.ZERO, remaining = length;
            while (remaining.signum() > 0) {
                Frac ql = remaining.min(rng.choice(List.of(beat, beat.multiply(2))));
                int r = root.next();
                int size = rng.choice(List.of(1, 2, 3));
                List<Pitch> pitches = new ArrayList<>();
                for (int j = 0; j < size; j++) pitches.add(k.pitch(r + 2 * j));
                v.events.add(note(offset, ql, pitches));
                offset = offset.add(ql);
                remaining = remaining.subtract(ql);
            }
            staff.measures.add(m);
        }
    }

    /**
     * Chord-symbol qualities: the music21 figure suffix and the MusicXML kind music21 writes for
     * it. music21 10.5 writes {@code m7b5} as a plain {@code minor-seventh} (it drops the flat
     * fifth), so the Python corpus never has a half-diminished chord; this follows it, so both
     * generators make the same corpus.
     */
    static final List<String[]> QUALITIES = List.of(
            new String[] {"", "major"}, new String[] {"m", "minor"}, new String[] {"7", "dominant"},
            new String[] {"m7", "minor-seventh"}, new String[] {"maj7", "major-seventh"},
            new String[] {"m7", "minor-seventh"}, new String[] {"dim", "diminished"},
            new String[] {"sus4", "suspended-fourth"});

    private static void addChordSymbols(PyRandom rng, Staff staff, KeyCtx k, Meter ts) {
        Frac length = ts.length();
        for (Measure m : staff.measures) {
            boolean one = rng.random() < 0.6 || length.lt(Frac.of(3));
            List<Frac> offsets = one ? List.of(Frac.ZERO) : List.of(Frac.ZERO, length.divide(2));
            for (Frac off : offsets) {
                Pitch root = k.pitch(rng.randint(0, 6) + 28);
                String[] quality = rng.choice(QUALITIES);
                String name = root.step + (root.alter > 0 ? "#".repeat(root.alter) : "b".repeat(-root.alter));
                m.chordSymbols.add(new ChordSymbol(off, name, quality[1], null, name + quality[0]));
            }
        }
    }
}
