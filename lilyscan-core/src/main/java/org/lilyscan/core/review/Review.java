package org.lilyscan.core.review;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.lilyscan.core.ir.BBox;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Provenance;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.lilypond.Generate;
import org.lilyscan.core.lilypond.LyProject;
import org.lilyscan.core.qa.Checks;
import org.lilyscan.core.util.Py;

/**
 * The review model behind the web UI (port of {@code lilyscan/review.py}): every measure with
 * its page box, its line in the LilyPond source, its events and its problems, ranked so the
 * measures most likely to need a fix come first.
 */
public final class Review {
    private Review() {}

    /** How much each kind of problem pushes a measure up the review list. */
    public static final Map<String, Double> WEIGHTS = Map.of(
            "compile", 5.0, "bar-check", 3.0, "rhythm", 3.0, "range", 2.0, "repaired", 1.0,
            "low-confidence", 1.0);

    private record Key(String part, int staff, String measure) {}

    private static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return !s.isEmpty();
        if (o instanceof Number n) return n.doubleValue() != 0;
        if (o instanceof Collection<?> c) return !c.isEmpty();
        if (o instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    /** What a repair changed, in words, from the values it recorded. */
    static String describe(Provenance p) {
        Map<String, Object> before = p.before == null ? Map.of() : p.before;
        if (before.containsKey("lyrics")) {
            List<String> texts = new ArrayList<>();
            for (Object pair : (List<?>) before.get("lyrics")) texts.add(Py.str(((List<?>) pair).get(1)));
            String was = String.join(" / ", texts);
            return "lyrics were: " + (was.isEmpty() ? "none" : was);
        }
        if (before.containsKey("note_type") || before.containsKey("duration")) {
            Object nt = before.get("note_type");
            String kind = truthy(nt) ? Py.str(nt) : Py.str(before.get("duration")) + " beats";
            Object dots = before.get("dots");
            int n = truthy(dots) ? ((Number) dots).intValue() : 0;
            return "was " + kind + ".".repeat(Math.max(0, n));
        }
        if (before.containsKey("pitches")) {
            List<String> ps = new ArrayList<>();
            for (Object o : (List<?>) before.get("pitches")) ps.add(Py.str(o));
            String joined = String.join(" ", ps);
            return "was " + (joined.isEmpty() ? "no notes" : joined);
        }
        return "";
    }

    private static boolean eventRepair(Provenance p) {
        return p.rule == null || !Generate.STAFF_REPAIRS.contains(p.rule);
    }

    /** Event-level repairs in a measure (staff-wide ones are listed for the whole score). */
    public static List<Map<String, Object>> measureRepairs(Measure m) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Voice v : m.voices) {
            for (Event e : v.events) {
                for (Provenance p : e.provenance) {
                    if (("repair".equals(p.stage) || "vector-oracle".equals(p.stage)) && eventRepair(p)) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("rule", truthy(p.rule) ? p.rule : p.stage);
                        r.put("offset", e.offset.toString());
                        r.put("detail", describe(p));
                        out.add(r);
                    }
                }
            }
        }
        return out;
    }

    static Map<String, Object> bbox(BBox b) {
        if (b == null) return null;
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("page", b.page);
        o.put("x", Py.round(b.x, 1));
        o.put("y", Py.round(b.y, 1));
        o.put("w", Py.round(b.w, 1));
        o.put("h", Py.round(b.h, 1));
        return o;
    }

    /** Measures in reading order plus the ids of the ones to review, most urgent first. */
    public static Map<String, Object> build(Score score, LyProject project, Checks.QaReport qa) {
        Map<Key, LyProject.Line> where = new LinkedHashMap<>();
        project.measureLines().forEach((line, ref) ->
                where.putIfAbsent(new Key(ref.part(), ref.staff(), ref.measure()), line));
        Map<Key, List<Map<String, Object>>> issues = new HashMap<>();
        Map<LyProject.Line, Key> byLine = new HashMap<>();
        where.forEach((k, v) -> byLine.put(v, k));

        for (Checks.CheckResult check : qa.checks()) {
            for (Map<String, Object> d : check.details()) {
                switch (check.id()) {
                    case "Q1" -> {
                        if (truthy(d.get("internal"))) break;
                        Object file = d.get("file"), line = d.get("line");
                        if (file instanceof String f && line instanceof Number n) {
                            Key key = byLine.get(new LyProject.Line(f, n.intValue()));
                            if (key != null) add(issues, key.part(), key.staff(), key.measure(), "compile",
                                    d.containsKey("message") ? Py.str(d.get("message")) : "");
                        }
                    }
                    case "Q2" -> add(issues, d.get("part"), d.get("staff"), d.get("measure"), "bar-check",
                            d.containsKey("message") ? Py.str(d.get("message")) : "");
                    case "Q3" -> add(issues, d.get("part"), d.get("staff"), d.get("measure"), "rhythm",
                            "voice " + Py.str(d.get("voice")) + ": " + Py.str(d.get("actual")) + " of "
                                    + Py.str(d.get("expected")) + " quarters");
                    case "Q4" -> add(issues, d.get("part"), d.get("staff"), d.get("measure"), "range",
                            Py.str(d.get("pitch")) + " out of range");
                    default -> {}
                }
            }
        }

        List<Map<String, Object>> measures = new ArrayList<>();
        for (Score.PartStaff ps : score.staves()) {
            for (Measure m : ps.staff().measures) {
                String number = truthy(m.number) ? m.number : Integer.toString(m.index + 1);
                Key key = new Key(ps.part().id, ps.staff().number, number);
                List<Map<String, Object>> events = new ArrayList<>();
                int low = 0;
                List<Double> confidences = new ArrayList<>();
                TreeSet<String> fixed = new TreeSet<>();
                for (Voice v : m.voices) {
                    for (Event e : v.events) {
                        for (Provenance p : e.provenance) {
                            if ("repair".equals(p.stage) && eventRepair(p)) fixed.add(truthy(p.rule) ? p.rule : "repair");
                        }
                        if (e.confidence != null) {
                            confidences.add(e.confidence);
                            if (e.confidence < Generate.LOW_CONFIDENCE) low++;
                        }
                        Map<String, Object> ev = new LinkedHashMap<>();
                        ev.put("kind", e.kind);
                        ev.put("offset", e.offset.toString());
                        List<String> labels = new ArrayList<>();
                        for (Pitch p : e.pitches()) labels.add(p.label());
                        ev.put("pitches", labels);
                        ev.put("confidence", e.confidence == null ? null : Py.round(e.confidence, 3));
                        ev.put("bbox", bbox(e.bbox));
                        events.add(ev);
                    }
                }
                List<Map<String, Object>> found = new ArrayList<>(issues.getOrDefault(key, List.of()));
                if (!fixed.isEmpty()) found.add(issue("repaired", String.join(", ", fixed)));
                if (low > 0) found.add(issue("low-confidence", low + " event(s) below " + Py.str(Generate.LOW_CONFIDENCE)));
                double priority = 0;
                for (Map<String, Object> i : found) {
                    if (!"low-confidence".equals(i.get("kind"))) priority += WEIGHTS.get((String) i.get("kind"));
                }
                priority += WEIGHTS.get("low-confidence") * low;
                Double minConf = confidences.isEmpty() ? null : confidences.stream().min(Double::compare).get();
                if (minConf != null) priority += 1.0 - minConf;
                LyProject.Line location = where.get(key);
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", ps.part().id + "/" + ps.staff().number + "/" + m.index);
                o.put("part", ps.part().id);
                o.put("part_name", truthy(ps.part().name) ? ps.part().name : ps.part().id);
                o.put("staff", ps.staff().number);
                o.put("index", m.index);
                o.put("number", number);
                o.put("bbox", bbox(m.bbox));
                o.put("file", location == null ? null : location.file());
                o.put("line", location == null ? null : location.line());
                o.put("events", events);
                o.put("issues", found);
                o.put("min_confidence", minConf == null ? null : Py.round(minConf, 3));
                o.put("repairs", measureRepairs(m));
                o.put("priority", Py.round(priority, 3));
                measures.add(o);
            }
        }
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> m : measures) if (!((List<?>) m.get("issues")).isEmpty()) ranked.add(m);
        ranked.sort(Comparator.<Map<String, Object>>comparingDouble(m -> -(Double) m.get("priority"))
                .thenComparingInt(m -> (Integer) m.get("index")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("measures", measures);
        out.put("review", ranked.stream().map(m -> m.get("id")).toList());
        out.put("repairs", staffRepairs(score));
        return out;
    }

    private static Map<String, Object> issue(String kind, String detail) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("kind", kind);
        i.put("detail", detail);
        return i;
    }

    private static void add(Map<Key, List<Map<String, Object>>> issues, Object part, Object staff, Object measure,
            String kind, String detail) {
        if (part == null || staff == null || measure == null) return;
        int s = staff instanceof Number n ? n.intValue() : Integer.parseInt(staff.toString().strip());
        issues.computeIfAbsent(new Key(Py.str(part), s, Py.str(measure)), k -> new ArrayList<>())
                .add(issue(kind, detail));
    }

    /** Staff-wide repairs (merged parts, octave clefs), which are not tied to a measure. */
    private static List<Map<String, Object>> staffRepairs(Score score) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Provenance p : score.provenance) {
            Map<String, Object> before = p.before == null ? Map.of() : p.before;
            if ("repair".equals(p.stage)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("rule", p.rule);
                r.put("part", before.get("part"));
                r.put("staff", before.get("staff"));
                r.put("detail", before.containsKey("detail") ? before.get("detail") : "");
                out.add(r);
            }
        }
        return out;
    }
}
