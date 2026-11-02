package org.lilyscan.core.repair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Provenance;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.TimeSignature;
import org.lilyscan.core.ir.Tuplet;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.util.Py;

/**
 * Measure-duration constraint repair (port of {@code lilyscan/repair/rhythm.py}): the fewest
 * rhythm edits (a missed triplet, dot or flag) that make a voice fill its measure, or rests put
 * in where the notes' positions leave room for them.
 */
public final class Rhythm {
    private Rhythm() {}

    public static final String RULE = "rhythm";

    /** Written note types and their lengths in quarter notes, longest first. */
    static final Map<String, Frac> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("whole", Frac.of(4));
        TYPES.put("half", Frac.of(2));
        TYPES.put("quarter", Frac.of(1));
        TYPES.put("eighth", Frac.of(1, 2));
        TYPES.put("16th", Frac.of(1, 4));
        TYPES.put("32nd", Frac.of(1, 8));
        TYPES.put("64th", Frac.of(1, 16));
    }
    private static final List<String> ORDER = new ArrayList<>(TYPES.keySet());
    static final Tuplet TRIPLET = new Tuplet(3, 2);
    static final int MAX_SINGLE_EDITS = 1;
    static final int MAX_SOLUTIONS = 64;
    static final String NO_SINGLE_EDIT = "no single rhythm edit fits";
    /** Edits that restore a symbol the engine did not see, rather than change one it saw. */
    static final Set<String> MISSED = Set.of("dot", "triplet");
    static final int MAX_REST_UNITS = 4;
    static final int MAX_REST_NOTES = 12;
    static final double REST_FIT = 0.03;
    static final double REST_RATIO = 2.0;
    static final double REST_MARGIN = 0.015;
    private static final List<String> SINGLE_KINDS = List.of("dot", "undot", "double", "halve");

    record Edit(String kind, int index) {
        int size() {
            return kind.equals("triplet") ? 3 : 1;
        }
    }

    static Frac dotted(int dots) {
        return Frac.of(2).subtract(Frac.of(1, 1L << dots));
    }

    static boolean plain(Event e) {
        return !e.grace && !e.measureRest && e.tuplet == null && e.noteType != null && TYPES.containsKey(e.noteType)
                && e.duration.equals(TYPES.get(e.noteType).multiply(dotted(e.dots)));
    }

    /** The event after a one-event edit, or null if the edit does not apply. */
    static Event single(Event e, String kind) {
        if (!plain(e) || e.noteType == null) return null;
        String t = e.noteType;
        int dots = e.dots;
        if (kind.equals("dot") && dots < 2) {
            dots++;
        } else if (kind.equals("undot") && dots > 0) {
            dots--;
        } else if (kind.equals("double") || kind.equals("halve")) {
            int k = ORDER.indexOf(t) + (kind.equals("double") ? -1 : 1);
            if (k < 0 || k >= ORDER.size()) return null;
            t = ORDER.get(k);
        } else {
            return null;
        }
        Event out = e.shallowCopy();
        out.noteType = t;
        out.dots = dots;
        out.duration = TYPES.get(t).multiply(dotted(dots));
        return out;
    }

    static Event triplet(Event e) {
        Event out = e.shallowCopy();
        out.tuplet = TRIPLET;
        out.duration = e.duration.multiply(Frac.of(2, 3));
        return out;
    }

    /** Each note starts where the previous one ends (no gaps or overlaps). */
    static boolean sequential(List<Event> events) {
        Frac pos = events.get(0).offset;
        for (Event e : events) {
            if (!e.offset.equals(pos)) return false;
            pos = pos.add(e.duration);
        }
        return true;
    }

    private static boolean divides(Frac a, Frac b) {
        return a.divide(b).denominator() == 1;
    }

    /** Edit sets, fewest single-event edits first, that make {@code events} end at {@code length}. */
    static List<List<Edit>> solutions(List<Event> events, Frac start, Frac length) {
        int n = events.size();
        List<Map<String, Event>> single = new ArrayList<>();
        for (Event e : events) {
            Map<String, Event> m = new LinkedHashMap<>();
            for (String kind : SINGLE_KINDS) m.put(kind, single(e, kind));
            single.add(m);
        }
        int[] found = {0};
        List<List<Edit>> out = new ArrayList<>();
        for (int budget = 0; budget <= MAX_SINGLE_EDITS; budget++) {
            List<List<Edit>> walked = new ArrayList<>();
            walk(events, single, length, 0, start, new ArrayList<>(), budget, found, walked);
            for (List<Edit> s : walked) if (singles(s) == budget) out.add(s);
        }
        return out;
    }

    private static void walk(List<Event> events, List<Map<String, Event>> single, Frac length, int i, Frac pos,
            List<Edit> edits, int budget, int[] found, List<List<Edit>> out) {
        if (found[0] >= MAX_SOLUTIONS || pos.gt(length)) return;
        int n = events.size();
        if (i == n) {
            if (pos.equals(length) && !edits.isEmpty()) {
                found[0]++;
                out.add(new ArrayList<>(edits));
            }
            return;
        }
        Event e = events.get(i);
        walk(events, single, length, i + 1, pos.add(e.duration), edits, budget, found, out);
        if (i + 3 <= n) {
            boolean ok = true;
            for (Event x : events.subList(i, i + 3)) {
                ok &= plain(x) && java.util.Objects.equals(x.noteType, e.noteType) && x.dots == 0;
            }
            if (ok && divides(pos, e.duration.multiply(2))) {
                edits.add(new Edit("triplet", i));
                walk(events, single, length, i + 3, pos.add(e.duration.multiply(2)), edits, budget, found, out);
                edits.remove(edits.size() - 1);
            }
        }
        if (budget > 0) {
            for (Map.Entry<String, Event> c : single.get(i).entrySet()) {
                if (c.getValue() != null) {
                    edits.add(new Edit(c.getKey(), i));
                    walk(events, single, length, i + 1, pos.add(c.getValue().duration), edits, budget - 1, found, out);
                    edits.remove(edits.size() - 1);
                }
            }
        }
    }

    static int singles(List<Edit> edits) {
        int n = 0;
        for (Edit x : edits) if (!x.kind().equals("triplet")) n++;
        return n;
    }

    static List<Event> apply(List<Event> events, List<Edit> edits, Frac start) {
        List<Event> out = new ArrayList<>(events);
        for (Edit edit : edits) {
            if (edit.kind().equals("triplet")) {
                for (int k = edit.index(); k < edit.index() + 3; k++) out.set(k, triplet(out.get(k)));
            } else {
                out.set(edit.index(), single(out.get(edit.index()), edit.kind()));
            }
        }
        Frac pos = start;
        for (int k = 0; k < out.size(); k++) {
            Event e = out.get(k);
            if (!e.offset.equals(pos)) {
                Event moved = e.shallowCopy();
                moved.offset = pos;
                out.set(k, moved);
                e = moved;
            }
            pos = pos.add(e.duration);
        }
        return out;
    }

    private static double confidence(List<Event> events, List<Edit> edits) {
        Py.Sum sum = new Py.Sum(); // Python's sum() of floats is compensated
        int n = 0;
        for (Edit x : edits) {
            for (int k = x.index(); k < x.index() + x.size(); k++) {
                Double c = events.get(k).confidence;
                if (c != null) {
                    sum.add(c);
                    n++;
                }
            }
        }
        return n > 0 ? sum.value() / n : 1.0;
    }

    /** A fix to one voice. */
    static final class Fix {
        final Voice voice;
        List<Event> events;
        List<Edit> edits;
        boolean confirmed = true;
        List<Object> signature = List.of();
        boolean rests;
        Map<List<Object>, Object[]> options = new LinkedHashMap<>();

        Fix(Voice voice, List<Event> events, List<Edit> edits) {
            this.voice = voice;
            this.events = events;
            this.edits = edits;
        }
    }

    private record Ranked(int singles, int agree, double confidence, int misread, List<Edit> edits, List<Event> fixed) {}

    /** A fix, a reason it could not be fixed (a String), or null when nothing is wrong. */
    static Object fixVoice(Voice v, Frac length, Set<Frac> trusted) {
        List<Event> events = new ArrayList<>();
        for (Event e : v.events) if (!e.grace) events.add(e);
        if (events.isEmpty() || events.stream().anyMatch(e -> e.measureRest) || v.duration().equals(length)) return null;
        if (!sequential(events)) return "voice has gaps";
        Frac start = events.get(0).offset;
        List<Ranked> ranked = new ArrayList<>();
        for (List<Edit> edits : solutions(events, start, length)) {
            List<Event> fixed = apply(events, edits, start);
            int agree = 0;
            for (Event e : fixed) if (trusted.contains(e.offset)) agree++;
            int misread = 0;
            for (Edit x : edits) if (!MISSED.contains(x.kind())) misread++;
            ranked.add(new Ranked(singles(edits), -agree, confidence(events, edits), misread, edits, fixed));
        }
        if (ranked.isEmpty()) return NO_SINGLE_EDIT;
        ranked.sort(Comparator.comparingInt(Ranked::singles).thenComparingInt(Ranked::agree)
                .thenComparingDouble(Ranked::confidence).thenComparingInt(Ranked::misread));
        Ranked best = ranked.get(0);
        if (ranked.size() > 1) {
            Ranked r = ranked.get(1);
            if (r.singles() == best.singles() && r.agree() == best.agree() && r.confidence() == best.confidence()
                    && r.misread() == best.misread()) {
                return "several rhythm edits fit equally well";
            }
        }
        // A changed dot or flag is a guess unless it lines the voice up with the others.
        int asRead = 0;
        for (Event e : events) if (trusted.contains(e.offset)) asRead++;
        boolean confirmed = !(best.singles() != 0 && -best.agree() <= asRead);
        Frac off = length.subtract(v.duration());
        java.util.function.Function<List<Edit>, List<Object>> signature = edits -> {
            List<Object> sig = new ArrayList<>();
            sig.add(off);
            for (Edit x : edits) sig.add(List.of(x.kind(), events.get(x.index()).offset));
            return sig;
        };
        Fix fix = new Fix(v, best.fixed(), best.edits());
        fix.confirmed = confirmed;
        fix.signature = signature.apply(best.edits());
        for (Ranked r : ranked) {
            if (r.singles() == best.singles()) fix.options.put(signature.apply(r.edits()), new Object[] {r.edits(), r.fixed()});
        }
        return fix;
    }

    /** Python's {@code math.log2} on a positive ratio: exact for powers of two. */
    static double log2(double x) {
        if (x > 0 && Double.isFinite(x) && (Double.doubleToRawLongBits(x) & 0x000fffffffffffffL) == 0
                && Math.getExponent(x) >= Double.MIN_EXPONENT) {
            return Math.getExponent(x);
        }
        return Math.log(x) / Math.log(2.0);
    }

    /** The room an engraver gives a note: one unit for the shortest, one more per doubling. */
    static double space(Frac duration, Frac shortest) {
        return 1.0 + log2(duration.divide(shortest).doubleValue());
    }

    private record Slot(Frac duration, Event note) {}

    private static List<double[]> points(List<Slot> sequence, Frac shortest, double end) {
        List<double[]> points = new ArrayList<>();
        double room = 0.0;
        for (Slot s : sequence) {
            if (s.note() != null && s.note().bbox != null) points.add(new double[] {room, s.note().bbox.x});
            room += space(s.duration(), shortest);
        }
        points.add(new double[] {room, end});
        return points;
    }

    /** The line through the points: its root-mean-square misfit and its slope. */
    static double[] fit(List<double[]> points) {
        // Python's sum() of floats is compensated (Neumaier), so each sum here is too.
        int n = points.size();
        Py.Sum sp = new Py.Sum(), sx = new Py.Sum();
        for (double[] p : points) sp.add(p[0]);
        double mp = sp.value() / n;
        for (double[] p : points) sx.add(p[1]);
        double mx = sx.value() / n;
        Py.Sum spread = new Py.Sum(), cov = new Py.Sum();
        for (double[] p : points) spread.add((p[0] - mp) * (p[0] - mp));
        for (double[] p : points) cov.add((p[0] - mp) * (p[1] - mx));
        double slope = spread.value() != 0 ? cov.value() / spread.value() : 0.0;
        if (slope <= 0) return new double[] {Double.POSITIVE_INFINITY, 0.0};
        Py.Sum ss = new Py.Sum();
        for (double[] p : points) {
            double r = p[1] - (mx + slope * (p[0] - mp));
            ss.add(r * r);
        }
        return new double[] {Math.sqrt(ss.value() / n), slope};
    }

    /** Every way to share {@code units} among {@code slots} gaps. */
    static List<int[]> gaps(int units, int slots) {
        List<int[]> out = new ArrayList<>();
        if (slots == 1) {
            out.add(new int[] {units});
            return out;
        }
        for (int first = 0; first <= units; first++) {
            for (int[] rest : gaps(units - first, slots - 1)) {
                int[] g = new int[slots];
                g[0] = first;
                System.arraycopy(rest, 0, g, 1, rest.length);
                out.add(g);
            }
        }
        return out;
    }

    /** Rests of written values filling {@code length} from {@code offset}, longest first. */
    static List<Event> rests(Frac offset, Frac length) {
        List<Event> out = new ArrayList<>();
        for (Map.Entry<String, Frac> t : TYPES.entrySet()) {
            while (length.ge(t.getValue())) {
                Event r = new Event(Event.REST, offset, t.getValue());
                r.noteType = t.getKey();
                r.provenance.add(Repair.provenance(RULE, "rest", "put in"));
                out.add(r);
                offset = offset.add(t.getValue());
                length = length.subtract(t.getValue());
            }
        }
        return out;
    }

    private record Placement(double misfit, int[] gaps) {}

    /** The voice with rests put in where its notes' positions leave room for them, or null. */
    static Fix withRests(Voice v, Frac length, Measure m) {
        List<Event> events = new ArrayList<>(v.events);
        if (m.bbox == null || events.size() < 2 || events.size() > MAX_REST_NOTES
                || events.stream().anyMatch(e -> e.grace || e.bbox == null)
                || !events.get(0).offset.isZero() || !sequential(events)) {
            return null;
        }
        Frac unit = events.get(0).duration;
        for (Event e : events) unit = unit.min(e.duration);
        Frac units = length.subtract(v.duration()).divide(unit);
        if (units.denominator() != 1 || !(units.signum() > 0 && units.le(Frac.of(MAX_REST_UNITS)))) return null;
        double end = m.bbox.x + m.bbox.w;
        double lead = events.get(0).bbox != null ? events.get(0).bbox.x - m.bbox.x : 0.0;
        List<Placement> ranked = new ArrayList<>();
        for (int[] g : gaps((int) units.numerator(), events.size() + 1)) {
            List<Slot> sequence = new ArrayList<>();
            for (int k = 0; k < g[0]; k++) sequence.add(new Slot(unit, null));
            for (int i = 0; i < events.size(); i++) {
                sequence.add(new Slot(events.get(i).duration, events.get(i)));
                for (int k = 0; k < g[i + 1]; k++) sequence.add(new Slot(unit, null));
            }
            Frac shortest = sequence.get(0).duration();
            for (Slot s : sequence) shortest = shortest.min(s.duration());
            double[] f = fit(points(sequence, shortest, end));
            // Rests before the first note need room between the barline and that note.
            if (g[0] != 0 && lead < 0.5 * f[1] * g[0] * space(unit, shortest)) continue;
            ranked.add(new Placement(f[0] / m.bbox.w, g));
        }
        ranked.sort(Comparator.comparingDouble(Placement::misfit).thenComparing(Placement::gaps, Arrays::compare));
        if (ranked.size() < 2) return null;
        double best = ranked.get(0).misfit(), second = ranked.get(1).misfit();
        int[] g = ranked.get(0).gaps();
        if (best > REST_FIT || second < REST_RATIO * best || second - best < REST_MARGIN) return null;
        List<Event> out = new ArrayList<>();
        Frac pos = Frac.ZERO;
        out.addAll(rests(pos, unit.multiply(g[0])));
        pos = pos.add(unit.multiply(g[0]));
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            Event moved = e;
            if (!e.offset.equals(pos)) {
                moved = e.shallowCopy();
                moved.offset = pos;
                List<Provenance> prov = new ArrayList<>(e.provenance);
                prov.add(Repair.provenance(RULE, "offset", e.offset.toString()));
                moved.provenance = prov;
            }
            out.add(moved);
            pos = pos.add(e.duration);
            out.addAll(rests(pos, unit.multiply(g[i + 1])));
            pos = pos.add(unit.multiply(g[i + 1]));
        }
        Fix fix = new Fix(v, out, List.of());
        fix.rests = true;
        return fix;
    }

    /** Puts the fixed events back, keeping grace notes before the note they lead into. */
    static void write(Fix fix) {
        if (fix.rests) {
            fix.voice.events = fix.events;
            return;
        }
        var fixed = fix.events.iterator();
        List<Event> out = new ArrayList<>();
        List<Event> pending = new ArrayList<>();
        Set<Integer> changed = new HashSet<>();
        for (Edit x : fix.edits) for (int k = x.index(); k < x.index() + x.size(); k++) changed.add(k);
        int k = 0;
        for (Event e : fix.voice.events) {
            if (e.grace) {
                pending.add(e);
                continue;
            }
            Event nw = fixed.next();
            if (changed.contains(k)) {
                List<Provenance> prov = new ArrayList<>(e.provenance);
                prov.add(Repair.provenance(RULE, "duration", e.duration.toString(), "note_type", e.noteType,
                        "dots", e.dots, "tuplet", e.tuplet == null ? null : List.of(e.tuplet.actual(), e.tuplet.normal())));
                nw.provenance = prov;
            }
            for (Event g : pending) {
                Event c = g.shallowCopy();
                c.offset = nw.offset;
                out.add(c);
            }
            out.add(nw);
            pending = new ArrayList<>();
            k++;
        }
        out.addAll(pending);
        fix.voice.events = out;
    }

    private static List<TimeSignature> inForce(Staff staff) {
        List<TimeSignature> times = new ArrayList<>();
        TimeSignature time = null;
        for (Measure m : staff.measures) {
            if (m.time != null) time = m.time;
            times.add(time);
        }
        return times;
    }

    /** Whether a measure short by {@code shortBy} is short by whole beats. */
    static boolean wholeBeats(Frac shortBy, TimeSignature time) {
        if (time == null) return true;
        Frac beat = Frac.of(4, time.beatType);
        if (time.beatType == 8 && time.beats % 3 == 0 && time.beats > 3) beat = beat.multiply(3);
        return shortBy.divide(beat).denominator() == 1;
    }

    /** A voice with real rhythm (not only a measure rest or grace notes). */
    private static boolean counts(Voice v) {
        for (Event e : v.events) if (!e.grace && !e.measureRest) return true;
        return false;
    }

    private record Found(int staff, Measure measure, Fix fix) {}

    @SuppressWarnings("unchecked")
    public static List<Repair> repairRhythm(Score score) {
        List<Score.PartStaff> staves = score.staves();
        List<List<TimeSignature>> times = new ArrayList<>();
        for (Score.PartStaff ps : staves) times.add(inForce(ps.staff()));
        // Durations as read, so a voice repaired earlier is not taken as evidence.
        Map<Voice, Frac> read = new IdentityHashMap<>();
        for (Score.PartStaff ps : staves) for (Measure m : ps.staff().measures) for (Voice v : m.voices) read.put(v, v.duration());
        int columns = 0;
        for (Score.PartStaff ps : staves) columns = Math.max(columns, ps.staff().measures.size());
        List<Found> found = new ArrayList<>();
        for (int i = 0; i < columns; i++) {
            List<Measure> column = new ArrayList<>();
            for (Score.PartStaff ps : staves) if (i < ps.staff().measures.size()) column.add(ps.staff().measures.get(i));
            List<Found> fixes = new ArrayList<>();
            for (int k = 0; k < staves.size(); k++) {
                Staff staff = staves.get(k).staff();
                if (i >= staff.measures.size()) continue;
                Measure m = staff.measures.get(i);
                TimeSignature time = times.get(k).get(i);
                if (time == null || m.implicit || (i == 0 && "0".equals(m.number))) continue;
                Frac length = time.measureLength();
                boolean edge = i == 0 || i == staff.measures.size() - 1;
                for (Voice v : m.voices) {
                    if (edge && v.duration().lt(length)) continue; // a pickup or its complement
                    List<Voice> others = new ArrayList<>();
                    for (Measure x : column) for (Voice w : x.voices) if (w != v && counts(w)) others.add(w);
                    boolean shortVoice = v.duration().lt(length);
                    if (shortVoice && !others.isEmpty() && others.stream().allMatch(w -> !read.get(w).equals(length))
                            && wholeBeats(length.subtract(v.duration()), time)) {
                        continue;
                    }
                    Set<Frac> trusted = new HashSet<>();
                    for (Voice w : others) {
                        if (!read.get(w).equals(length)) continue;
                        for (Event e : w.events) if (!e.grace) trusted.add(e.offset);
                    }
                    Object result = fixVoice(v, length, trusted);
                    if (NO_SINGLE_EDIT.equals(result) && shortVoice) result = withRests(v, length, m);
                    if (result instanceof Fix f) fixes.add(new Found(k, m, f));
                }
            }
            // Unconfirmed edits that restore missed symbols confirm each other: the edit most
            // voices rank best (at least two, and no other as often) goes to every unconfirmed
            // voice it fits.
            List<Fix> unconfirmed = new ArrayList<>();
            for (Found f : fixes) if (!f.fix().confirmed) unconfirmed.add(f.fix());
            Map<List<Object>, Integer> votes = new LinkedHashMap<>();
            for (Fix f : unconfirmed) {
                if (f.edits.stream().allMatch(x -> MISSED.contains(x.kind()))) votes.merge(f.signature, 1, Integer::sum);
            }
            List<Map.Entry<List<Object>, Integer>> common = new ArrayList<>(votes.entrySet());
            common.sort((a, b) -> Integer.compare(b.getValue(), a.getValue())); // stable: Counter.most_common
            if (!common.isEmpty() && common.get(0).getValue() >= 2
                    && (common.size() == 1 || common.get(1).getValue() < common.get(0).getValue())) {
                List<Object> chosen = common.get(0).getKey();
                for (Fix f : unconfirmed) {
                    Object[] option = f.options.get(chosen);
                    if (option != null) {
                        f.edits = (List<Edit>) option[0];
                        f.events = (List<Event>) option[1];
                        f.signature = chosen;
                        f.confirmed = true;
                    }
                }
            }
            for (Found f : fixes) if (f.fix().confirmed) found.add(f);
        }
        Map<Integer, Map<String, List<String>>> fixed = new TreeMap<>();
        for (Found f : found) {
            write(f.fix());
            String kinds;
            if (f.fix().rests) {
                kinds = "rests";
            } else {
                TreeSet<String> ks = new TreeSet<>();
                for (Edit x : f.fix().edits) ks.add(x.kind());
                kinds = String.join(", ", ks);
            }
            fixed.computeIfAbsent(f.staff(), x -> new LinkedHashMap<>()).computeIfAbsent(kinds, x -> new ArrayList<>())
                    .add(Repair.label(f.measure()));
        }
        List<Repair> repairs = new ArrayList<>();
        for (int k = 0; k < staves.size(); k++) {
            var part = staves.get(k).part();
            Staff staff = staves.get(k).staff();
            String name = part.name != null && !part.name.isEmpty() ? part.name : part.id;
            for (Map.Entry<String, List<String>> e : fixed.getOrDefault(k, Map.of()).entrySet()) {
                repairs.add(new Repair(RULE, part.id, staff.number, name + ": " + e.getKey() + " to fill the measure", e.getValue()));
            }
        }
        return repairs;
    }
}
