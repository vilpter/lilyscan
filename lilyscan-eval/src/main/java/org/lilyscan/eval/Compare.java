package org.lilyscan.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lilyscan.core.ir.ChordSymbol;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Lyric;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.util.Py;

/**
 * Compare a predicted score with ground truth, measure by measure (port of
 * {@code lilyscan/evaluation/compare.py}): staves matched by order, measures aligned by
 * Needleman-Wunsch, a measure exact when its voices (as a multiset of event sequences) match.
 */
public final class Compare {
    private Compare() {}

    /** An event as compared: onset, duration, sorted pitch keys (null for a rest), grace. */
    public record Token(Frac offset, Frac duration, List<Pitch.Key> pitches, boolean grace) {}

    static <T> int levenshtein(List<T> a, List<T> b) {
        if (a.size() < b.size()) {
            List<T> t = a;
            a = b;
            b = t;
        }
        int[] previous = new int[b.size() + 1];
        for (int j = 0; j <= b.size(); j++) previous[j] = j;
        for (int i = 1; i <= a.size(); i++) {
            int[] current = new int[b.size() + 1];
            current[0] = i;
            for (int j = 1; j <= b.size(); j++) {
                int sub = previous[j - 1] + (a.get(i - 1).equals(b.get(j - 1)) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), sub);
            }
            previous = current;
        }
        return previous[b.size()];
    }

    private static final Comparator<Pitch.Key> KEY_ORDER = Comparator.comparing(Pitch.Key::step)
            .thenComparingInt(Pitch.Key::alter).thenComparingInt(Pitch.Key::octave);

    static Token token(Event e, Frac measureLength) {
        List<Pitch.Key> pitches = null;
        if (!Event.REST.equals(e.kind)) {
            pitches = new ArrayList<>();
            for (Pitch p : e.pitches()) pitches.add(p.key());
            pitches.sort(KEY_ORDER);
        }
        Frac duration = e.measureRest && measureLength != null ? measureLength : e.duration;
        return new Token(e.offset, duration, pitches, e.grace);
    }

    /** Python {@code str()} of a token's pitches: {@code None}, or a tuple of (step, alter, octave). */
    static String pyStr(List<Pitch.Key> pitches) {
        if (pitches == null) return "None";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < pitches.size(); i++) {
            Pitch.Key k = pitches.get(i);
            if (i > 0) sb.append(", ");
            sb.append("('").append(k.step()).append("', ").append(k.alter()).append(", ").append(k.octave()).append(')');
        }
        if (pitches.size() == 1) sb.append(',');
        return sb.append(')').toString();
    }

    record NoteKey(Frac offset, Pitch.Key pitch, Frac duration) {}

    record OnsetKey(Frac offset, Pitch.Key pitch) {}

    /** A measure as compared. */
    public record MeasureSig(List<List<Token>> voices, List<Token> events, Map<NoteKey, Integer> notes,
            Map<OnsetKey, Integer> onsets, List<String> lyrics) {
        int noteTotal() {
            int n = 0;
            for (int c : notes.values()) n += c;
            return n;
        }
    }

    private static boolean hasPitches(List<Token> v) {
        for (Token t : v) if (t.pitches() != null) return true;
        return false;
    }

    /** Lexicographic comparison of pitch-key tuples (empty for a rest). */
    private static int comparePitches(List<Pitch.Key> a, List<Pitch.Key> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = KEY_ORDER.compare(a.get(i), b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int compareVoices(List<Token> a, List<Token> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            Token x = a.get(i), y = b.get(i);
            int c = x.offset().compareTo(y.offset());
            if (c == 0) c = x.duration().compareTo(y.duration());
            if (c == 0) c = comparePitches(x.pitches() == null ? List.of() : x.pitches(), y.pitches() == null ? List.of() : y.pitches());
            if (c == 0) c = Boolean.compare(x.grace(), y.grace());
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    private static String stripDash(String s) {
        String t = Py.strip(s);
        int end = t.length();
        while (end > 0 && t.charAt(end - 1) == '-') end--;
        return Py.strip(t.substring(0, end));
    }

    public static MeasureSig measureSig(Measure m, Frac measureLength) {
        List<List<Token>> voices = new ArrayList<>();
        for (Voice v : m.voices) {
            List<Token> ts = new ArrayList<>();
            for (Event e : v.events) ts.add(token(e, measureLength));
            if (!ts.isEmpty()) voices.add(ts);
        }
        if (voices.stream().anyMatch(Compare::hasPitches)) voices.removeIf(v -> !hasPitches(v));
        List<Token> events = new ArrayList<>();
        for (List<Token> v : voices) events.addAll(v);
        events.sort(Comparator.<Token, Frac>comparing(Token::offset).thenComparing(t -> pyStr(t.pitches())));
        Map<NoteKey, Integer> notes = new LinkedHashMap<>();
        Map<OnsetKey, Integer> onsets = new LinkedHashMap<>();
        for (Token t : events) {
            if (t.pitches() == null) continue;
            for (Pitch.Key p : t.pitches()) {
                notes.merge(new NoteKey(t.offset(), p, t.duration()), 1, Integer::sum);
                onsets.merge(new OnsetKey(t.offset(), p), 1, Integer::sum);
            }
        }
        List<String> lyrics = new ArrayList<>();
        for (Voice v : m.voices) for (Event e : v.events) for (Lyric ly : e.lyrics) if (ly.verse == 1) lyrics.add(stripDash(ly.text));
        // Order voices canonically (voice numbers are ignored).
        List<List<Token>> ordered = new ArrayList<>(voices);
        ordered.sort(Compare::compareVoices);
        return new MeasureSig(ordered, events, notes, onsets, lyrics);
    }

    /** The time-signature length in force at each measure. */
    static List<Frac> measureLengths(Staff staff) {
        List<Frac> lengths = new ArrayList<>();
        Frac length = null;
        for (Measure m : staff.measures) {
            if (m.time != null) length = m.time.measureLength();
            lengths.add(length);
        }
        return lengths;
    }

    public static List<List<MeasureSig>> staffSigs(Score score) {
        List<List<MeasureSig>> out = new ArrayList<>();
        for (Score.PartStaff ps : score.staves()) {
            List<Frac> lengths = measureLengths(ps.staff());
            List<MeasureSig> sigs = new ArrayList<>();
            for (int i = 0; i < ps.staff().measures.size(); i++) sigs.add(measureSig(ps.staff().measures.get(i), lengths.get(i)));
            out.add(sigs);
        }
        return out;
    }

    private static double subCost(MeasureSig a, MeasureSig b) {
        if (a.voices().equals(b.voices())) return 0.0;
        return Math.min(1.0, (double) levenshtein(a.events(), b.events()) / Math.max(Math.max(a.events().size(), b.events().size()), 1));
    }

    /** (ground-truth index, predicted index) pairs, null for a gap. */
    public static List<Integer[]> align(List<MeasureSig> gt, List<MeasureSig> pred) {
        int n = gt.size(), m = pred.size();
        double[][] cost = new double[n + 1][m + 1];
        for (int i = 1; i <= n; i++) cost[i][0] = i;
        for (int j = 1; j <= m; j++) cost[0][j] = j;
        double[][] sub = new double[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                sub[i][j] = subCost(gt.get(i - 1), pred.get(j - 1));
                cost[i][j] = Math.min(Math.min(cost[i - 1][j - 1] + sub[i][j], cost[i - 1][j] + 1.0), cost[i][j - 1] + 1.0);
            }
        }
        List<Integer[]> pairs = new ArrayList<>();
        int i = n, j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + sub[i][j]) {
                pairs.add(new Integer[] {i - 1, j - 1});
                i--;
                j--;
            } else if (i > 0 && cost[i][j] == cost[i - 1][j] + 1.0) {
                pairs.add(new Integer[] {i - 1, null});
                i--;
            } else {
                pairs.add(new Integer[] {null, j - 1});
                j--;
            }
        }
        java.util.Collections.reverse(pairs);
        return pairs;
    }

    /** The comparison's counts (Python {@code Comparison}). */
    public static final class Comparison {
        public boolean engineOk;
        public int gtStaves, predStaves, gtMeasures, predMeasures, exactMeasures, gtEvents, eventEdits, gtNotes, predNotes,
                matchedNotes, matchedOnsets, gtLyrics, lyricEdits, gtChords, chordEdits;
        public final List<Double> perStaffExact = new ArrayList<>();

        public double measureAccuracy() {
            return gtMeasures != 0 ? (double) exactMeasures / gtMeasures : 0.0;
        }

        /** Event edits needed per ground-truth event (lower is better). */
        public double editRate() {
            return gtEvents != 0 ? (double) eventEdits / gtEvents : 0.0;
        }

        static double[] f1(int matched, int gt, int pred) {
            double p = pred != 0 ? (double) matched / pred : 0.0;
            double r = gt != 0 ? (double) matched / gt : 0.0;
            return new double[] {p, r, p + r != 0 ? 2 * p * r / (p + r) : 0.0};
        }

        public double[] notePrf() {
            return f1(matchedNotes, gtNotes, predNotes);
        }

        /** Pitch + onset only (durations ignored). */
        public double[] onsetPrf() {
            return f1(matchedOnsets, gtNotes, predNotes);
        }

        public Double lyricAccuracy() {
            return gtLyrics == 0 ? null : Math.max(0.0, 1 - (double) lyricEdits / gtLyrics);
        }

        public Double chordAccuracy() {
            return gtChords == 0 ? null : Math.max(0.0, 1 - (double) chordEdits / gtChords);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("engine_ok", engineOk);
            m.put("gt_staves", gtStaves);
            m.put("pred_staves", predStaves);
            m.put("gt_measures", gtMeasures);
            m.put("pred_measures", predMeasures);
            m.put("exact_measures", exactMeasures);
            m.put("gt_events", gtEvents);
            m.put("event_edits", eventEdits);
            m.put("gt_notes", gtNotes);
            m.put("pred_notes", predNotes);
            m.put("matched_notes", matchedNotes);
            m.put("matched_onsets", matchedOnsets);
            m.put("gt_lyrics", gtLyrics);
            m.put("lyric_edits", lyricEdits);
            m.put("gt_chords", gtChords);
            m.put("chord_edits", chordEdits);
            m.put("per_staff_exact", new ArrayList<>(perStaffExact));
            double[] prf = notePrf();
            m.put("measure_accuracy", Py.round(measureAccuracy(), 4));
            m.put("edit_rate", Py.round(editRate(), 4));
            m.put("note_precision", Py.round(prf[0], 4));
            m.put("note_recall", Py.round(prf[1], 4));
            m.put("note_f1", Py.round(prf[2], 4));
            m.put("onset_f1", Py.round(onsetPrf()[2], 4));
            m.put("lyric_accuracy", lyricAccuracy() == null ? null : Py.round(lyricAccuracy(), 4));
            m.put("chord_accuracy", chordAccuracy() == null ? null : Py.round(chordAccuracy(), 4));
            return m;
        }
    }

    record Chord(String root, String kind) {}

    /** All chord symbols in reading order (measure index, then offset, then root and kind). */
    static List<Chord> chords(Score score) {
        record Found(int index, Frac offset, String root, String kind) {}
        List<Found> found = new ArrayList<>();
        for (Score.PartStaff ps : score.staves()) {
            for (Measure m : ps.staff().measures) for (ChordSymbol c : m.chordSymbols) found.add(new Found(m.index, c.offset, c.root, c.kind));
        }
        found.sort(Comparator.comparingInt(Found::index).thenComparing(Found::offset).thenComparing(Found::root).thenComparing(Found::kind));
        List<Chord> out = new ArrayList<>();
        for (Found f : found) out.add(new Chord(f.root(), f.kind()));
        return out;
    }

    private static <K> int intersection(Map<K, Integer> a, Map<K, Integer> b) {
        int n = 0;
        for (Map.Entry<K, Integer> e : a.entrySet()) n += Math.min(e.getValue(), b.getOrDefault(e.getKey(), 0));
        return n;
    }

    public static Comparison compare(Score gt, Score pred) {
        List<List<MeasureSig>> gtStaves = staffSigs(gt);
        Comparison result = new Comparison();
        result.engineOk = pred != null;
        result.gtStaves = gtStaves.size();
        for (List<MeasureSig> sigs : gtStaves) {
            result.gtMeasures += sigs.size();
            for (MeasureSig s : sigs) {
                result.gtEvents += s.events().size();
                result.gtNotes += s.noteTotal();
                result.gtLyrics += s.lyrics().size();
            }
        }
        result.gtChords = chords(gt).size();
        if (pred == null) {
            result.eventEdits = result.gtEvents;
            result.lyricEdits = result.gtLyrics;
            result.chordEdits = result.gtChords;
            for (int k = 0; k < gtStaves.size(); k++) result.perStaffExact.add(0.0);
            return result;
        }
        List<List<MeasureSig>> predStaves = staffSigs(pred);
        result.predStaves = predStaves.size();
        for (List<MeasureSig> sigs : predStaves) {
            result.predMeasures += sigs.size();
            for (MeasureSig s : sigs) result.predNotes += s.noteTotal();
        }
        for (int k = 0; k < Math.max(gtStaves.size(), predStaves.size()); k++) {
            List<MeasureSig> g = k < gtStaves.size() ? gtStaves.get(k) : List.of();
            List<MeasureSig> p = k < predStaves.size() ? predStaves.get(k) : List.of();
            int exact = 0;
            for (Integer[] pair : align(g, p)) {
                Integer gi = pair[0], pi = pair[1];
                if (gi == null && pi != null) {
                    result.eventEdits += p.get(pi).events().size();
                } else if (pi == null && gi != null) {
                    result.eventEdits += g.get(gi).events().size();
                } else if (gi != null) {
                    MeasureSig a = g.get(gi), b = p.get(pi);
                    if (a.voices().equals(b.voices())) exact++;
                    result.eventEdits += levenshtein(a.events(), b.events());
                    result.matchedNotes += intersection(a.notes(), b.notes());
                    result.matchedOnsets += intersection(a.onsets(), b.onsets());
                }
            }
            result.exactMeasures += exact;
            if (!g.isEmpty()) result.perStaffExact.add(Py.round((double) exact / g.size(), 4));
            List<String> gLyrics = new ArrayList<>(), pLyrics = new ArrayList<>();
            for (MeasureSig s : g) gLyrics.addAll(s.lyrics());
            for (MeasureSig s : p) pLyrics.addAll(s.lyrics());
            if (!gLyrics.isEmpty()) result.lyricEdits += levenshtein(gLyrics, pLyrics);
        }
        result.chordEdits = result.gtChords != 0 ? levenshtein(chords(gt), chords(pred)) : 0;
        return result;
    }

    /** (confidence, correct) per predicted event. */
    public record Scored(Double confidence, boolean correct) {}

    private interface Visit {
        void event(Event e, boolean correct);
    }

    /** Each predicted event, in order, with whether it matches an unclaimed identical event in its aligned ground-truth measure. */
    private static void walk(Score gt, Score pred, Visit visit) {
        List<List<MeasureSig>> gtSigs = staffSigs(gt);
        List<Score.PartStaff> staves = pred.staves();
        for (int k = 0; k < staves.size(); k++) {
            Staff staff = staves.get(k).staff();
            List<Frac> lengths = measureLengths(staff);
            List<MeasureSig> sigs = new ArrayList<>();
            for (int i = 0; i < staff.measures.size(); i++) sigs.add(measureSig(staff.measures.get(i), lengths.get(i)));
            List<MeasureSig> reference = k < gtSigs.size() ? gtSigs.get(k) : List.of();
            for (Integer[] pair : align(reference, sigs)) {
                Integer gi = pair[0], pi = pair[1];
                if (pi == null) continue;
                Map<Token, Integer> available = new HashMap<>();
                if (gi != null) for (Token t : reference.get(gi).events()) available.merge(t, 1, Integer::sum);
                for (Voice v : staff.measures.get(pi).voices) {
                    for (Event e : v.events) {
                        Token token = token(e, lengths.get(pi));
                        boolean correct = available.getOrDefault(token, 0) > 0;
                        if (correct) available.merge(token, -1, Integer::sum);
                        visit.event(e, correct);
                    }
                }
            }
        }
    }

    /** {@code id(event) -> right?} (Python {@code calibrate.correct_events}). */
    public static Map<Event, Boolean> correctEvents(Score gt, Score pred) {
        Map<Event, Boolean> out = new java.util.IdentityHashMap<>();
        walk(gt, pred, out::put);
        return out;
    }

    /** (confidence, correct) for every predicted event (Python {@code event_correctness}). */
    public static List<Scored> eventCorrectness(Score gt, Score pred) {
        List<Scored> out = new ArrayList<>();
        walk(gt, pred, (e, ok) -> out.add(new Scored(e.confidence, ok)));
        return out;
    }

    /** Confidence bands for calibration reports: [low, high). */
    static final double[][] BANDS = {{0.0, 0.5}, {0.5, 0.7}, {0.7, 0.8}, {0.8, 0.9}, {0.9, 1.01}};

    /** Accuracy per confidence band and the expected calibration error. */
    public static Map<String, Object> calibration(List<Scored> pairs) {
        List<Scored> scored = new ArrayList<>();
        for (Scored s : pairs) if (s.confidence() != null) scored.add(s);
        List<Object> bands = new ArrayList<>();
        double ece = 0.0;
        for (double[] band : BANDS) {
            double lo = band[0], hi = band[1];
            List<Scored> members = new ArrayList<>();
            for (Scored s : scored) if (lo <= s.confidence() && s.confidence() < hi) members.add(s);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("band", Py.fixed(lo, 1) + "-" + Py.fixed(Math.min(hi, 1.0), 1));
            if (members.isEmpty()) {
                b.put("events", 0);
                bands.add(b);
                continue;
            }
            int ok = 0;
            double conf = 0;
            for (Scored s : members) {
                if (s.correct()) ok++;
                conf += s.confidence();
            }
            double accuracy = (double) ok / members.size();
            double confidence = conf / members.size();
            ece += (double) members.size() / scored.size() * Math.abs(accuracy - confidence);
            b.put("events", members.size());
            b.put("accuracy", Py.round(accuracy, 4));
            b.put("mean_confidence", Py.round(confidence, 4));
            bands.add(b);
        }
        int unscored = 0, unscoredOk = 0;
        for (Scored s : pairs) {
            if (s.confidence() == null) {
                unscored++;
                if (s.correct()) unscoredOk++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", pairs.size());
        out.put("scored_events", scored.size());
        out.put("ece", scored.isEmpty() ? null : Py.round(ece, 4));
        out.put("bands", bands);
        out.put("unscored_accuracy", unscored > 0 ? Py.round((double) unscoredOk / unscored, 4) : null);
        return out;
    }
}
