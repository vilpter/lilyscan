package org.lilyscan.core.combine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lilyscan.core.ir.Barline;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Ir;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Voice;

/**
 * Measure alignment (port of {@code lilyscan/align.py}): parts transcribed separately, lined up
 * measure by measure against a reference part, with a flagged whole-measure rest where a part
 * has no measure for a column.
 */
public final class Align {
    private Align() {}

    private static final Set<String> LANDMARK = Set.of("light-light", "light-heavy", "heavy-light", "heavy-heavy");
    /** Two placements of a gap whose costs differ by less than this are as good as each other. */
    static final double UNSURE = 0.1;

    /** What alignment changed, per part (by position in the list given). */
    public static final class Alignment {
        public final int measures;
        public final List<List<Integer>> filled;
        public final int reference;
        public final List<List<int[]>> unsure;

        Alignment(int measures, List<List<Integer>> filled, int reference, List<List<int[]>> unsure) {
            this.measures = measures;
            this.filled = filled;
            this.reference = reference;
            this.unsure = unsure;
        }

        public boolean changed() {
            return filled.stream().anyMatch(f -> !f.isEmpty());
        }
    }

    public record Result(List<Part> parts, Alignment alignment) {}

    static Set<Frac> onsets(Measure m) {
        Set<Frac> out = new HashSet<>();
        for (Voice v : m.voices) for (Event e : v.events) if (!e.notes.isEmpty()) out.add(e.offset);
        return out;
    }

    static boolean landmark(Barline b) {
        return b != null && (LANDMARK.contains(b.style) || b.repeat != null);
    }

    /** How unlike two measures of different parts are (0 to about 2). */
    static double cost(Measure a, Measure b) {
        Set<Frac> x = onsets(a), y = onsets(b);
        double rhythm;
        if (x.isEmpty() && y.isEmpty()) {
            rhythm = 0.0;
        } else if (x.isEmpty() || y.isEmpty()) {
            rhythm = 0.6; // one part rests while the other plays: common, not damning
        } else {
            Set<Frac> both = new HashSet<>(x);
            both.retainAll(y);
            Set<Frac> any = new HashSet<>(x);
            any.addAll(y);
            rhythm = 1.0 - (double) both.size() / any.size();
        }
        double marks = 0.0;
        if (landmark(a.rightBarline) != landmark(b.rightBarline)) marks += 0.5;
        if ((a.key != null) != (b.key != null) && a.index != 0 && b.index != 0) marks += 0.25;
        if ((a.time != null) != (b.time != null) && a.index != 0 && b.index != 0) marks += 0.25;
        return rhythm + marks;
    }

    private record Fit(List<Integer[]> pairs, List<int[]> spans) {}

    /** {@code shortList} matched in order into {@code longList} at the least cost; gaps as late as possible. */
    static Fit fit(List<Measure> longList, List<Measure> shortList) {
        int n = longList.size(), m = shortList.size();
        double inf = Double.POSITIVE_INFINITY;
        Map<Long, Double> cost = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (int j = Math.max(0, i - (n - m)); j <= Math.min(i, m - 1); j++) cost.put(key(i, j), cost(longList.get(i), shortList.get(j)));
        }
        double[][] best = new double[n + 1][m + 1];
        for (double[] row : best) Arrays.fill(row, inf);
        best[0][0] = 0.0;
        for (int i = 1; i <= n; i++) {
            for (int j = Math.max(0, m - (n - i)); j <= Math.min(i, m); j++) {
                double take = j > 0 ? best[i - 1][j - 1] + cost.get(key(i - 1, j - 1)) : inf;
                best[i][j] = Math.min(best[i - 1][j], take); // skip: long[i - 1] has no partner
            }
        }
        double[][] rest = new double[n + 1][m + 1];
        for (double[] row : rest) Arrays.fill(row, inf);
        rest[n][m] = 0.0;
        for (int i = n - 1; i >= 0; i--) {
            for (int j = Math.max(0, i - (n - m)); j <= Math.min(i, m); j++) {
                double take = j < m ? cost.get(key(i, j)) + rest[i + 1][j + 1] : inf;
                rest[i][j] = Math.min(rest[i + 1][j], take);
            }
        }
        List<Integer[]> out = new ArrayList<>();
        int j = 0;
        for (int i = 0; i < n; i++) { // matching as early as the cost allows puts the gaps late
            if (j < m && rest[i][j] == cost.get(key(i, j)) + rest[i + 1][j + 1]) {
                out.add(new Integer[] {i, j});
                j++;
            } else {
                out.add(new Integer[] {i, null});
            }
        }
        List<Integer> gaps = new ArrayList<>();
        for (Integer[] p : out) if (p[1] == null) gaps.add(p[0]);
        List<int[]> spans = new ArrayList<>();
        for (int k = 0; k < gaps.size(); k++) { // gap k at long[p] leaves p - k measures before it
            int lo = -1, hi = -1;
            for (int p = k; p <= n - (gaps.size() - k); p++) {
                if (best[p][p - k] + rest[p + 1][p - k] <= rest[0][0] + UNSURE) {
                    if (lo < 0) lo = p;
                    hi = p;
                }
            }
            spans.add(lo < 0 ? new int[] {gaps.get(k), gaps.get(k)} : new int[] {lo, hi});
        }
        return new Fit(out, spans);
    }

    private static long key(int i, int j) {
        return ((long) i << 32) | (j & 0xffffffffL);
    }

    /** (reference measure, other measure) pairs, null for a gap; only the shorter part has gaps. */
    static Fit pairs(List<Measure> ref, List<Measure> other) {
        if (ref.size() == other.size()) {
            List<Integer[]> p = new ArrayList<>();
            for (int i = 0; i < ref.size(); i++) p.add(new Integer[] {i, i});
            return new Fit(p, List.of());
        }
        if (ref.size() > other.size()) return fit(ref, other);
        Fit f = fit(other, ref);
        List<Integer[]> swapped = new ArrayList<>();
        for (Integer[] p : f.pairs()) swapped.add(new Integer[] {p[1], p[0]});
        return new Fit(swapped, f.spans());
    }

    private static double meanConfidence(Part part) {
        org.lilyscan.core.util.Py.Sum sum = new org.lilyscan.core.util.Py.Sum(); // Python's sum() of floats
        int n = 0;
        for (Staff st : part.staves) {
            for (Measure m : st.measures) {
                for (Voice v : m.voices) for (Event e : v.events) if (e.confidence != null) { sum.add(e.confidence); n++; }
            }
        }
        return n > 0 ? sum.value() / n : 0.0;
    }

    /** The longest measure count at least two parts share (else the median one), then the most confident part. */
    public static int chooseReference(List<Part> parts) {
        List<Integer> counts = new ArrayList<>();
        for (Part p : parts) counts.add(p.staves.get(0).measures.size());
        Map<Integer, Integer> tally = new LinkedHashMap<>();
        for (int c : counts) tally.merge(c, 1, Integer::sum);
        Integer length = null;
        for (Map.Entry<Integer, Integer> e : tally.entrySet()) {
            if (e.getValue() > 1 && (length == null || e.getKey() > length)) length = e.getKey();
        }
        if (length == null) {
            List<Integer> sorted = new ArrayList<>(counts);
            sorted.sort(null);
            length = sorted.get(sorted.size() / 2);
        }
        int best = -1;
        double bestConf = 0;
        for (int k = 0; k < counts.size(); k++) {
            if (counts.get(k).equals(length)) {
                double c = meanConfidence(parts.get(k));
                if (best < 0 || c > bestConf) {
                    best = k;
                    bestConf = c;
                }
            }
        }
        return best;
    }

    /** A whole-measure rest as long as {@code template}, flagged for review (no confidence). */
    static Measure rest(Measure template, int index) {
        Frac length = template.time != null ? template.time.measureLength() : null;
        if (length == null) {
            Frac max = null;
            for (Voice v : template.voices) {
                Frac s = Frac.ZERO;
                for (Event e : v.events) if (!e.grace) s = s.add(e.duration);
                if (max == null || s.gt(max)) max = s;
            }
            length = max == null || max.isZero() ? Frac.of(4) : max;
        }
        Event r = new Event(Event.REST, Frac.ZERO, length);
        r.measureRest = true;
        r.confidence = 0.0;
        Measure m = new Measure();
        m.index = index;
        m.number = Integer.toString(index + 1);
        Voice v = new Voice();
        v.number = 1;
        v.events.add(r);
        m.voices.add(v);
        return m;
    }

    /** The parts on one grid of measures, and where rests were put in. */
    public static Result alignParts(List<Part> parts) {
        if (parts.size() < 2) {
            int n = parts.isEmpty() ? 0 : parts.get(0).staves.get(0).measures.size();
            List<List<Integer>> filled = new ArrayList<>();
            List<List<int[]>> unsure = new ArrayList<>();
            for (int k = 0; k < parts.size(); k++) {
                filled.add(new ArrayList<>());
                unsure.add(new ArrayList<>());
            }
            return new Result(parts, new Alignment(n, filled, 0, unsure));
        }
        int refK = chooseReference(parts);
        List<Measure> ref = parts.get(refK).staves.get(0).measures;
        List<Integer[]> columns = new ArrayList<>();
        for (int c = 0; c < ref.size(); c++) {
            Integer[] col = new Integer[parts.size()];
            col[refK] = c;
            columns.add(col);
        }
        Map<Integer, Object[]> spans = new LinkedHashMap<>(); // k -> {short?, spans}
        for (int k = 0; k < parts.size(); k++) {
            if (k == refK) continue;
            List<Integer[]> grown = new ArrayList<>();
            int c = 0;
            List<Measure> own = parts.get(k).staves.get(0).measures;
            Fit f = pairs(ref, own);
            spans.put(k, new Object[] {ref.size() > own.size(), f.spans()});
            for (Integer[] p : f.pairs()) {
                Integer r = p[0], o = p[1];
                if (r == null) { // a measure the reference lacks
                    Integer[] column = new Integer[parts.size()];
                    column[k] = o;
                    grown.add(column);
                    continue;
                }
                while (!r.equals(columns.get(c)[refK])) {
                    grown.add(columns.get(c));
                    c++;
                }
                columns.get(c)[k] = o;
                grown.add(columns.get(c));
                c++;
            }
            grown.addAll(columns.subList(c, columns.size()));
            columns = grown;
        }
        List<Part> aligned = new ArrayList<>();
        List<List<Integer>> filled = new ArrayList<>();
        for (int k = 0; k < parts.size(); k++) filled.add(new ArrayList<>());
        for (int k = 0; k < parts.size(); k++) {
            Part part = parts.get(k);
            List<Staff> staves = new ArrayList<>();
            boolean hasTime = false;
            for (Staff st : part.staves) for (Measure m : st.measures) if (m.time != null) hasTime = true;
            for (Staff st : part.staves) {
                List<Measure> measures = new ArrayList<>();
                for (int c = 0; c < columns.size(); c++) {
                    Integer[] column = columns.get(c);
                    Integer own = column[k], at = column[refK];
                    String number = at != null ? ref.get(at).number : "";
                    Measure m;
                    if (own != null && own < st.measures.size()) {
                        m = Ir.copy(st.measures.get(own));
                        m.index = c;
                        m.number = number;
                    } else {
                        Measure template = null;
                        for (int jj = 0; jj < column.length && template == null; jj++) {
                            if (column[jj] != null) template = parts.get(jj).staves.get(0).measures.get(column[jj]);
                        }
                        m = rest(template, c);
                        m.number = number;
                        if (st.number == part.staves.get(0).number) filled.get(k).add(c);
                    }
                    if (!hasTime && at != null) m.time = ref.get(at).time; // a part that read no time signature
                    measures.add(m);
                }
                if (!st.measures.isEmpty() && !measures.isEmpty()) {
                    // The part's opening clef, key and time belong on its first column.
                    Measure first = st.measures.get(0), head = measures.get(0);
                    if (head.clefs.isEmpty()) {
                        List<Clef> clefs = new ArrayList<>();
                        for (Clef c : first.clefs) if (c.offset.isZero()) clefs.add(Ir.copy(c));
                        head.clefs = clefs;
                    }
                    if (head.key == null) head.key = first.key;
                    if (head.time == null) head.time = first.time;
                }
                staves.add(new Staff(st.number, measures));
            }
            Part copy = Ir.copy(part);
            copy.staves = staves;
            aligned.add(copy);
        }
        List<List<int[]>> unsure = unsureColumns(columns, refK, spans, parts.size());
        for (int k = 0; k < unsure.size(); k++) {
            for (int[] r : unsure.get(k)) doubt(aligned.get(k), r[0], r[1], filled.get(k));
        }
        return new Result(aligned, new Alignment(columns.size(), filled, refK, unsure));
    }

    @SuppressWarnings("unchecked")
    private static List<List<int[]>> unsureColumns(List<Integer[]> columns, int refK, Map<Integer, Object[]> spans, int count) {
        List<List<int[]>> out = new ArrayList<>();
        for (int k = 0; k < count; k++) out.add(new ArrayList<>());
        for (Map.Entry<Integer, Object[]> e : spans.entrySet()) {
            int k = e.getKey();
            boolean shortPart = (Boolean) e.getValue()[0];
            List<int[]> near = (List<int[]>) e.getValue()[1];
            int owner = shortPart ? refK : k;
            Map<Integer, Integer> columnOf = new HashMap<>();
            for (int c = 0; c < columns.size(); c++) if (columns.get(c)[owner] != null) columnOf.put(columns.get(c)[owner], c);
            List<int[]> ranges = new ArrayList<>();
            for (int[] fl : near) {
                if (fl[1] > fl[0] && columnOf.containsKey(fl[0]) && columnOf.containsKey(fl[1])) {
                    ranges.add(new int[] {columnOf.get(fl[0]), columnOf.get(fl[1])});
                }
            }
            ranges.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
            List<int[]> mine = out.get(k);
            for (int[] r : ranges) {
                if (!mine.isEmpty() && r[0] <= mine.get(mine.size() - 1)[1]) {
                    int[] last = mine.get(mine.size() - 1);
                    mine.set(mine.size() - 1, new int[] {last[0], Math.max(last[1], r[1])});
                } else {
                    mine.add(r);
                }
            }
        }
        return out;
    }

    /** The part's notes in columns {@code first}..{@code last}: confidence at most the chance they are placed right. */
    private static void doubt(Part part, int first, int last, List<Integer> filled) {
        double chance = 1.0 / (last - first + 1);
        for (Staff st : part.staves) {
            for (Measure m : st.measures.subList(Math.min(first, st.measures.size()), Math.min(last + 1, st.measures.size()))) {
                if (filled.contains(m.index)) continue;
                // The measure is the part's own copy; its voices and events are still shared with the part as read.
                List<Voice> voices = new ArrayList<>();
                for (Voice v : m.voices) {
                    Voice nv = Ir.copy(v);
                    List<Event> events = new ArrayList<>();
                    for (Event e : v.events) {
                        Event ne = Ir.copy(e);
                        ne.confidence = e.confidence == null ? chance : Math.min(e.confidence, chance);
                        events.add(ne);
                    }
                    nv.events = events;
                    voices.add(nv);
                }
                m.voices = voices;
            }
        }
    }
}
