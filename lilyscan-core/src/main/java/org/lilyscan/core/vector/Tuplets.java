package org.lilyscan.core.vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Provenance;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Tuplet;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.repair.Repair;

/**
 * Tuplet numbers printed in a born-digital PDF, applied to the engine's rhythm (port of
 * {@code lilyscan/vector/tuplets.py}): the run of plain notes under a printed 3 becomes a
 * triplet when that makes the voice fill its measure.
 */
public final class Tuplets {
    private Tuplets() {}

    public static final String RULE = "vector-tuplet";
    static final Tuplet TRIPLET = new Tuplet(3, 2);
    static final double NEAR_STAFF = 6.0;
    static final double NEAR_GROUP = 2.0;
    private static final Map<String, Frac> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("whole", Frac.of(4));
        TYPES.put("half", Frac.of(2));
        TYPES.put("quarter", Frac.of(1));
        TYPES.put("eighth", Frac.of(1, 2));
        TYPES.put("16th", Frac.of(1, 4));
        TYPES.put("32nd", Frac.of(1, 8));
    }

    /** Counts of the printed tuplet numbers and what became of them. */
    public static final class Stats {
        public int marks, matched, applied, unplaced;
    }

    static boolean plain(Event e) {
        if (e.grace || e.measureRest || e.tuplet != null || e.noteType == null || !TYPES.containsKey(e.noteType)) return false;
        return e.duration.equals(TYPES.get(e.noteType).multiply(Frac.of(2).subtract(Frac.of(1, 1L << e.dots))));
    }

    private static double centre(Event e, double scale) {
        return (e.bbox.x + e.bbox.w / 2) / scale;
    }

    private static Staves.VectorStaff staffOf(VectorPdf.Glyph mark, List<Staves.VectorStaff> staves) {
        double x = mark.rect().cx(), y = mark.originY();
        Staves.VectorStaff best = null;
        Double bestD = null;
        for (Staves.VectorStaff s : staves) {
            if (!(s.x0() - 5 <= x && x <= s.x1() + 5)) continue;
            double d = Math.max(0.0, Math.max(s.top() - y, y - s.bottom()));
            if (d <= NEAR_STAFF * s.interline() && (bestD == null || d < bestD)) {
                best = s;
                bestD = d;
            }
        }
        return best;
    }

    private static int indexOf(List<Event> events, Event e) {
        for (int i = 0; i < events.size(); i++) if (events.get(i) == e) return i;
        throw new IllegalArgumentException("not in list");
    }

    /** Indexes of the run of plain notes the number sits over, if one fits. */
    static List<Integer> group(Voice v, double x, double scale, double space) {
        List<Event> events = new ArrayList<>();
        for (Event e : v.events) if (!e.grace) events.add(e);
        Double bestOff = null;
        List<Integer> best = null;
        for (int i = 0; i < events.size(); i++) {
            Frac total = Frac.ZERO;
            for (int j = i; j < Math.min(events.size(), i + 6); j++) {
                Event e = events.get(j);
                if (e.bbox == null || !plain(e)) break;
                total = total.add(e.duration);
                if (j == i) continue;
                Frac unit = total.divide(3);
                if (!TYPES.containsValue(unit)) continue;
                double lo = centre(events.get(i), scale), hi = centre(events.get(j), scale);
                if (!(lo - NEAR_GROUP * space <= x && x <= hi + NEAR_GROUP * space)) continue;
                double off = Math.abs((lo + hi) / 2 - x);
                if (off <= NEAR_GROUP * space && (bestOff == null || off < bestOff)) {
                    bestOff = off;
                    best = new ArrayList<>();
                    for (int k = i; k <= j; k++) best.add(indexOf(v.events, events.get(k)));
                }
            }
        }
        return best;
    }

    private static void apply(Voice v, List<Integer> indexes) {
        for (int k : indexes) {
            Event e = v.events.get(k);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("duration", e.duration.toString());
            before.put("tuplet", null);
            e.provenance.add(new Provenance("vector-oracle", RULE, before));
            Event c = e.shallowCopy();
            c.duration = e.duration.multiply(Frac.of(2, 3));
            c.tuplet = TRIPLET;
            v.events.set(k, c);
        }
        // Re-lay the voice: each note starts where the previous one ends.
        Frac pos = Frac.ZERO;
        for (Event e : v.events) {
            if (!e.grace) {
                pos = e.offset;
                break;
            }
        }
        for (int k = 0; k < v.events.size(); k++) {
            Event e = v.events.get(k);
            if (e.grace) continue;
            if (!e.offset.equals(pos)) {
                Event c = e.shallowCopy();
                c.offset = pos;
                v.events.set(k, c);
            }
            pos = pos.add(e.duration);
        }
    }

    private static boolean sequential(Voice v) {
        List<Event> events = new ArrayList<>();
        for (Event e : v.events) if (!e.grace) events.add(e);
        for (int i = 0; i + 1 < events.size(); i++) {
            if (!events.get(i).offset.add(events.get(i).duration).equals(events.get(i + 1).offset)) return false;
        }
        return true;
    }

    private record Layout(List<Staves.VectorStaff> staves, List<VectorPdf.Glyph> marks, double scale) {}

    private record Mark(VectorPdf.Glyph glyph, double space) {}

    public static List<Repair> apply(Score score, List<VectorPdf.Page> pages, OmrBook book, Stats stats) {
        List<Repair> repairs = new ArrayList<>();
        Map<Integer, Layout> layout = new LinkedHashMap<>();
        for (int index = 0; index < book.sheets().size(); index++) {
            OmrBook.OmrSheet sheet = book.sheets().get(index);
            if (0 <= sheet.number() - 1 && sheet.number() - 1 < pages.size()) {
                VectorPdf.Page page = pages.get(sheet.number() - 1);
                List<VectorPdf.Glyph> marks = new ArrayList<>();
                for (VectorPdf.Glyph g : page.glyphs) if (g.name().equals("italic.3")) marks.add(g);
                if (!marks.isEmpty()) {
                    layout.put(index, new Layout(Staves.find(page), marks, sheet.width() / page.width));
                    stats.marks += marks.size();
                }
            }
        }
        if (layout.isEmpty()) return repairs;
        for (Score.PartStaff ps : score.staves()) {
            Map<Measure, Frac> lengths = new IdentityHashMap<>();
            Frac length = null;
            for (Measure m : ps.staff().measures) {
                if (m.time != null) length = m.time.measureLength();
                lengths.put(m, length);
            }
            List<String> fixed = new ArrayList<>();
            for (Measure m : ps.staff().measures) {
                if (m.bbox == null || !layout.containsKey(m.bbox.page)) continue;
                Layout l = layout.get(m.bbox.page);
                double x0 = m.bbox.x / l.scale(), x1 = (m.bbox.x + m.bbox.w) / l.scale();
                double y = (m.bbox.y + m.bbox.h / 2) / l.scale();
                List<Mark> here = new ArrayList<>();
                for (VectorPdf.Glyph mark : l.marks()) {
                    Staves.VectorStaff home = staffOf(mark, l.staves());
                    if (home != null && x0 <= mark.rect().cx() && mark.rect().cx() <= x1
                            && home.top() - 1 <= y && y <= home.bottom() + 1) {
                        here.add(new Mark(mark, home.interline()));
                    }
                }
                if (!here.isEmpty() && place(m, here, l.scale(), lengths.get(m), stats)) {
                    fixed.add(m.number != null && !m.number.isEmpty() ? m.number : Integer.toString(m.index + 1));
                }
            }
            if (!fixed.isEmpty()) {
                var part = ps.part();
                String name = part.name != null && !part.name.isEmpty() ? part.name : part.id;
                repairs.add(new Repair(RULE, part.id, ps.staff().number, name + ": triplets printed in the PDF", fixed));
            }
        }
        return repairs;
    }

    private record Option(double distance, int voice, List<Integer> group) {}

    private static boolean place(Measure m, List<Mark> marks, double scale, Frac length, Stats stats) {
        if (length == null) {
            stats.unplaced += marks.size();
            return false;
        }
        Map<Integer, List<List<Integer>>> chosen = new LinkedHashMap<>();
        for (Mark mk : marks) {
            double x = mk.glyph().rect().cx();
            boolean matched = false;
            for (Voice v : m.voices) {
                for (Event e : v.events) {
                    if (e.tuplet != null && e.bbox != null && Math.abs(centre(e, scale) - x) <= NEAR_GROUP * mk.space()) matched = true;
                }
            }
            if (matched) {
                stats.matched++; // the engine read this one
                continue;
            }
            List<Option> options = new ArrayList<>();
            for (int k = 0; k < m.voices.size(); k++) {
                Voice v = m.voices.get(k);
                if (!sequential(v)) continue;
                List<Integer> group = group(v, x, scale, mk.space());
                if (group == null) continue;
                double sum = 0;
                for (int i : group) sum += (v.events.get(i).bbox.y + v.events.get(i).bbox.h / 2) / scale;
                options.add(new Option(Math.abs(sum / group.size() - mk.glyph().originY()), k, group));
            }
            if (options.isEmpty()) {
                stats.unplaced++;
                continue;
            }
            // min() over (distance, voice, group) tuples
            Option best = options.get(0);
            for (Option o : options) {
                int c = Double.compare(o.distance(), best.distance());
                if (c == 0) c = Integer.compare(o.voice(), best.voice());
                if (c == 0) c = compareLists(o.group(), best.group());
                if (c < 0) best = o;
            }
            chosen.computeIfAbsent(best.voice(), x2 -> new ArrayList<>()).add(best.group());
        }
        boolean changed = false;
        for (Map.Entry<Integer, List<List<Integer>>> c : chosen.entrySet()) {
            Voice v = m.voices.get(c.getKey());
            List<Integer> flat = new ArrayList<>();
            for (List<Integer> g : c.getValue()) flat.addAll(g);
            Frac saving = Frac.ZERO;
            for (int i : flat) saving = saving.add(v.events.get(i).duration);
            saving = saving.divide(3);
            if (new HashSet<>(flat).size() != flat.size() || !v.duration().subtract(saving).equals(length)) {
                stats.unplaced += c.getValue().size();
                continue;
            }
            List<Integer> sorted = new ArrayList<>(flat);
            sorted.sort(null);
            apply(v, sorted);
            stats.applied += c.getValue().size();
            changed = true;
        }
        return changed;
    }

    private static int compareLists(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Integer.compare(a.get(i), b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }
}
