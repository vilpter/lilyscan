package org.lilyscan.core.ir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Whole-score operations on the IR. Port of {@code lilyscan/ir/ops.py}. */
public final class Ops {
    private Ops() {}

    private static int keyInForce(Staff staff) {
        for (int i = staff.measures.size() - 1; i >= 0; i--) {
            Measure m = staff.measures.get(i);
            if (m.key != null) {
                return m.key.fifths;
            }
        }
        return 0;
    }

    private static String partName(Part p) {
        return (p.name == null ? "" : p.name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** A part carried on from one movement into the next: same staves, same name. */
    private static boolean samePart(Part a, Part b) {
        return a.staves.size() == b.staves.size() && partName(a).equals(partName(b));
    }

    /** Measures for a part absent from a movement: a measure rest in each measure of
     *  {@code reference} (a staff of that movement), with its numbering, key and time. */
    private static List<Measure> rests(List<Measure> reference, int offset) {
        List<Measure> out = new ArrayList<>();
        TimeSignature time = null;
        for (Measure m : reference) {
            time = m.time != null ? m.time : time;
            Frac length = Frac.ZERO;
            boolean first = true;
            for (Voice v : m.voices) {
                Frac d = v.duration();
                if (first || d.gt(length)) {
                    length = d;
                    first = false;
                }
            }
            if (length.isZero()) {
                length = time != null ? time.measureLength() : Frac.of(4);
            }
            Event rest = new Event(Event.REST, Frac.ZERO, length);
            rest.measureRest = true;
            Measure nm = new Measure(m.index + offset, m.number);
            nm.key = m.key;
            nm.time = m.time;
            nm.implicit = m.implicit;
            Voice v = new Voice(1);
            v.events.add(rest);
            nm.voices.add(v);
            out.add(nm);
        }
        return out;
    }

    /** The measures of the score's longest staff (the first, on a tie). */
    private static List<Measure> longest(Score score) {
        List<Measure> best = List.of();
        boolean found = false;
        for (Score.PartStaff ps : score.staves()) {
            if (!found || ps.staff().measures.size() > best.size()) {
                best = ps.staff().measures;
                found = true;
            }
        }
        return best;
    }

    private static <T> List<T> tail(List<T> list, int from) {
        return from >= list.size() ? List.of() : list.subList(from, list.size());
    }

    /** Measure rests to the end for a part that ends early: Audiveris does not pad a part
     *  missing from a movement's last systems. */
    private static void pad(Score score) {
        List<Measure> reference = new ArrayList<>(longest(score));
        for (Score.PartStaff ps : score.staves()) {
            Staff staff = ps.staff();
            staff.measures.addAll(rests(tail(reference, staff.measures.size()), 0));
        }
    }

    /**
     * Add movement {@code extra} after {@code base}, matching its parts to base's in order. A part
     * of either that the other lacks is kept, with measure rests where it is absent (the form
     * Audiveris gives a part missing from a system, so the part-merge repair can join parts the
     * engine split between movements).
     */
    private static void append(Score base, Score extra) {
        pad(base); // so the next movement starts at the same measure in every part
        List<Measure> baseReference = new ArrayList<>(longest(base));
        int offset = baseReference.size();
        List<Measure> extraReference = longest(extra);
        Set<String> ids = new java.util.HashSet<>();
        for (Part p : base.parts) {
            ids.add(p.id);
        }
        List<Part> parts = base.parts;
        Map<Staff, Integer> carried = new IdentityHashMap<>();
        for (Score.PartStaff ps : base.staves()) {
            carried.put(ps.staff(), keyInForce(ps.staff()));
        }
        Set<Part> continued = Collections.newSetFromMap(new IdentityHashMap<>());
        int last = -1;
        for (Part more : extra.parts) {
            Integer j = null;
            for (int k = last + 1; k < parts.size(); k++) {
                if (samePart(parts.get(k), more)) {
                    j = k;
                    break;
                }
            }
            if (j == null) {
                // A part new in this movement: absent from everything before it.
                int n = ids.size() + 1;
                while (ids.contains("P" + n)) {
                    n++;
                }
                ids.add("P" + n);
                Part nw = Ir.copy(more);
                nw.id = "P" + n;
                nw.staves = new ArrayList<>();
                for (Staff st : more.staves) {
                    List<Measure> filler = rests(baseReference, 0);
                    List<Clef> firstClefs = List.of();
                    for (Measure m : st.measures) {
                        if (!m.clefs.isEmpty()) {
                            firstClefs = m.clefs.subList(0, 1);
                            break;
                        }
                    }
                    if (!filler.isEmpty()) {
                        List<Clef> clefs = new ArrayList<>();
                        for (Clef c : firstClefs) {
                            Clef cc = Ir.copy(c);
                            cc.offset = Frac.ZERO;
                            clefs.add(cc);
                        }
                        filler.get(0).clefs = clefs;
                    }
                    Staff ns = new Staff(st.number, filler);
                    nw.staves.add(ns);
                    carried.put(ns, keyInForce(ns));
                }
                j = last + 1;
                parts.add(j, nw);
            }
            Part target = parts.get(j);
            int pairs = Math.min(target.staves.size(), more.staves.size());
            for (int s = 0; s < pairs; s++) {
                Staff staff = target.staves.get(s);
                for (Measure m : more.staves.get(s).measures) {
                    Measure mc = Ir.copy(m);
                    mc.index = m.index + offset;
                    staff.measures.add(mc);
                }
            }
            continued.add(target);
            last = j;
        }
        for (Part part : parts) {
            if (!continued.contains(part)) {
                for (Staff staff : part.staves) {
                    staff.measures.addAll(rests(extraReference, offset));
                }
            }
        }
        // A movement that states no key signature has none, rather than the previous one's.
        for (Score.PartStaff ps : base.staves()) {
            Measure first = null;
            for (Measure m : ps.staff().measures) {
                if (m.index == offset) {
                    first = m;
                    break;
                }
            }
            if (first != null && first.key == null && carried.getOrDefault(ps.staff(), 0) != 0) {
                first.key = new KeySignature(0, null);
            }
        }
    }

    /** Concatenate scores (e.g. one engine output file per movement) into one. */
    public static Score mergeScores(List<Score> scores) {
        if (scores.isEmpty()) {
            throw new IllegalArgumentException("nothing to merge");
        }
        Score base = Ir.deepCopy(scores.get(0));
        for (Score extra : scores.subList(1, scores.size())) {
            append(base, Ir.deepCopy(extra));
        }
        pad(base);
        return base;
    }

    /** {@code {"parts", "staves", "measures", "notes"}} counts, in that order. */
    public static Map<String, Integer> counts(Score score) {
        List<Score.PartStaff> staves = score.staves();
        int measures = 0;
        int notes = 0;
        for (Score.PartStaff ps : staves) {
            measures = Math.max(measures, ps.staff().measures.size());
            for (Measure m : ps.staff().measures) {
                for (Voice v : m.voices) {
                    for (Event e : v.events) {
                        notes += e.notes.size();
                    }
                }
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("parts", score.parts.size());
        out.put("staves", staves.size());
        out.put("measures", measures);
        out.put("notes", notes);
        return out;
    }
}
