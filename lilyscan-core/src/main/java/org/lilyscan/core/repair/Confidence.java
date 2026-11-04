package org.lilyscan.core.repair;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Provenance;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.qa.Checks;
import org.lilyscan.core.util.Py;

/**
 * Lilyscan's own confidence (port of {@code lilyscan/repair/confidence.py}): the engine grade
 * combined with the pipeline's evidence in a logistic model fitted on the evaluation corpus,
 * then a monotone recalibration map. The model is {@code confidence.json} beside this class, fitted by {@code lilyscan eval calibrate}.
 */
public final class Confidence {
    private Confidence() {}

    public static final List<String> FEATURES = List.of("grade", "no_grade", "grade_below_0_5", "grade_below_0_8",
            "grade_below_0_9", "voice_fills", "rhythm_repaired", "clef_repaired", "out_of_range", "rest", "chord",
            "grace", "tuplet", "altered", "density", "voices", "measure_min_grade", "column_fills", "staff_fill_rate",
            "staff_mean_grade", "score_fill_rate", "score_mean_grade", "score_located");

    /** A fitted model: feature weights, bias, and the recalibration points (may be empty). */
    public record Model(double[] weights, double bias, double[][] calibration) {}

    private record Located(Part part, Measure measure, Voice voice, Event event) {}

    /** Every event with its feature vector (in {@link #FEATURES} order). */
    public static List<Map.Entry<Event, double[]>> eventFeatures(Score score) {
        Map<Measure, Frac> lengths = new IdentityHashMap<>();
        for (Score.PartStaff ps : score.staves()) {
            Frac length = null;
            for (Measure m : ps.staff().measures) {
                if (m.time != null) length = m.time.measureLength();
                lengths.put(m, length);
            }
        }
        List<Located> events = new ArrayList<>();
        for (Score.PartStaff ps : score.staves()) {
            for (Measure m : ps.staff().measures) {
                for (Voice v : m.voices) for (Event e : v.events) events.add(new Located(ps.part(), m, v, e));
            }
        }
        List<Map.Entry<Event, double[]>> out = new ArrayList<>();
        if (events.isEmpty()) return out;
        int fills = 0, voiceMeasures = 0;
        for (Score.PartStaff ps : score.staves()) {
            for (Measure m : ps.staff().measures) {
                for (Voice v : m.voices) {
                    if (v.events.isEmpty()) continue;
                    voiceMeasures++;
                    if (v.duration().equals(lengths.get(m))) fills++;
                }
            }
        }
        List<Double> grades = new ArrayList<>();
        int located = 0;
        for (Located l : events) {
            if (l.event().confidence != null) grades.add(l.event().confidence);
            if (l.event().bbox != null) located++;
        }
        double scoreFill = voiceMeasures > 0 ? (double) fills / voiceMeasures : 0.0;
        double scoreGrade = grades.isEmpty() ? 0.0 : Py.mean(grades);
        double scoreLocated = (double) located / events.size();
        Map<Part, int[]> ranges = new IdentityHashMap<>();
        for (Part p : score.parts) {
            Checks.Range r = Checks.instrumentRange(p);
            ranges.put(p, new int[] {r.lo(), r.hi()});
        }
        Map<Measure, Staff> staffOf = new IdentityHashMap<>();
        Map<Staff, Double> staffFill = new IdentityHashMap<>();
        Map<Staff, Double> staffGrade = new IdentityHashMap<>();
        Map<Integer, List<Boolean>> column = new HashMap<>();
        for (Score.PartStaff ps : score.staves()) {
            Staff staff = ps.staff();
            int ok = 0, n = 0;
            List<Double> sg = new ArrayList<>();
            for (int i = 0; i < staff.measures.size(); i++) {
                Measure m = staff.measures.get(i);
                staffOf.put(m, staff);
                for (Voice v : m.voices) {
                    if (!v.events.isEmpty()) {
                        boolean f = v.duration().equals(lengths.get(m));
                        n++;
                        if (f) ok++;
                        column.computeIfAbsent(i, x -> new ArrayList<>()).add(f);
                    }
                    for (Event e : v.events) if (e.confidence != null) sg.add(e.confidence);
                }
            }
            staffFill.put(staff, n > 0 ? (double) ok / n : 0.0);
            staffGrade.put(staff, sg.isEmpty() ? scoreGrade : Py.mean(sg));
        }

        for (Located l : events) {
            Measure m = l.measure();
            Voice v = l.voice();
            Event e = l.event();
            Double g = e.confidence;
            Frac length = lengths.get(m) != null ? lengths.get(m) : Frac.of(4);
            List<Double> measureGrades = new ArrayList<>();
            for (Voice w : m.voices) for (Event x : w.events) if (x.confidence != null) measureGrades.add(x.confidence);
            int[] range = ranges.get(l.part());
            boolean rhythm = false, clef = false;
            for (Provenance p : e.provenance) {
                if (!"repair".equals(p.stage)) continue;
                rhythm |= "rhythm".equals(p.rule);
                clef |= "octave-clef".equals(p.rule);
            }
            List<Boolean> others = new ArrayList<>(column.getOrDefault(m.index, List.of()));
            int nonEmpty = 0;
            for (Voice w : m.voices) {
                if (w.events.isEmpty()) continue;
                nonEmpty++;
                if (!others.remove((Boolean) w.duration().equals(lengths.get(m)))) {
                    throw new IllegalStateException("list.remove(x): x not in list"); // as Python fails
                }
            }
            boolean outOfRange = false, altered = false;
            for (Pitch p : e.pitches()) {
                if (!(range[0] <= p.midi() && p.midi() <= range[1])) outOfRange = true;
                if (p.alter != 0) altered = true;
            }
            int density = 0;
            for (Voice w : m.voices) density += w.events.size();
            int othersOk = 0;
            for (boolean b : others) if (b) othersOk++;
            double minGrade = Double.POSITIVE_INFINITY;
            for (double x : measureGrades) minGrade = Math.min(minGrade, x);
            Map<String, Double> values = new LinkedHashMap<>();
            values.put("grade", g != null && g != 0 ? g : 0.0);
            values.put("no_grade", g == null ? 1.0 : 0.0);
            values.put("grade_below_0_5", g != null && g < 0.5 ? 1.0 : 0.0);
            values.put("grade_below_0_8", g != null && g < 0.8 ? 1.0 : 0.0);
            values.put("grade_below_0_9", g != null && g < 0.9 ? 1.0 : 0.0);
            values.put("voice_fills", v.duration().equals(lengths.get(m)) ? 1.0 : 0.0);
            values.put("rhythm_repaired", rhythm ? 1.0 : 0.0);
            values.put("clef_repaired", clef ? 1.0 : 0.0);
            values.put("out_of_range", outOfRange ? 1.0 : 0.0);
            values.put("rest", Event.REST.equals(e.kind) ? 1.0 : 0.0);
            values.put("chord", e.notes.size() > 1 ? 1.0 : 0.0);
            values.put("grace", e.grace ? 1.0 : 0.0);
            values.put("tuplet", e.tuplet != null ? 1.0 : 0.0);
            values.put("altered", altered ? 1.0 : 0.0);
            values.put("density", density / length.doubleValue());
            values.put("voices", (double) Math.max(0, nonEmpty - 1));
            values.put("measure_min_grade", measureGrades.isEmpty() ? 0.0 : minGrade);
            values.put("column_fills", others.isEmpty() ? 1.0 : (double) othersOk / others.size());
            values.put("staff_fill_rate", staffFill.get(staffOf.get(m)));
            values.put("staff_mean_grade", staffGrade.get(staffOf.get(m)));
            values.put("score_fill_rate", scoreFill);
            values.put("score_mean_grade", scoreGrade);
            values.put("score_located", scoreLocated);
            double[] x = new double[FEATURES.size()];
            for (int i = 0; i < x.length; i++) x[i] = values.get(FEATURES.get(i));
            out.add(Map.entry(e, x));
        }
        return out;
    }

    private static Model cached;

    /** The packaged model, or null when it is missing or fitted on another feature set. */
    public static synchronized Model loadModel() {
        if (cached != null) return cached;
        try (InputStream in = Confidence.class.getResourceAsStream("confidence.json")) {
            if (in == null) return null;
            cached = parse(new ObjectMapper().readTree(in));
            return cached;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Model parse(JsonNode model) {
        List<String> features = new ArrayList<>();
        model.path("features").forEach(f -> features.add(f.asText()));
        if (!features.equals(FEATURES)) return null; // trained on another feature set
        double[] weights = new double[model.get("weights").size()];
        for (int i = 0; i < weights.length; i++) weights[i] = model.get("weights").get(i).asDouble();
        JsonNode cal = model.get("calibration");
        double[][] points = new double[cal == null || cal.isNull() ? 0 : cal.size()][];
        for (int i = 0; i < points.length; i++) points[i] = new double[] {cal.get(i).get(0).asDouble(), cal.get(i).get(1).asDouble()};
        return new Model(weights, model.get("bias").asDouble(), points);
    }

    public static double predict(Model model, double[] x) {
        if (x.length != model.weights().length) throw new IllegalArgumentException("zip() argument lengths differ");
        Py.Sum sum = new Py.Sum(); // Python's sum() of floats is compensated
        for (int i = 0; i < x.length; i++) sum.add(model.weights()[i] * x[i]);
        double z = model.bias() + sum.value();
        double p = 1.0 / (1.0 + StrictMath.exp(-Math.max(-30.0, Math.min(30.0, z))));
        return recalibrate(model.calibration(), p);
    }

    /** Piecewise-linear monotone map fitted on held-out predictions (isotonic). */
    static double recalibrate(double[][] points, double p) {
        if (points.length == 0) return p;
        if (p <= points[0][0]) return points[0][1];
        for (int i = 0; i + 1 < points.length; i++) {
            double x0 = points[i][0], y0 = points[i][1], x1 = points[i + 1][0], y1 = points[i + 1][1];
            if (p <= x1) return x1 == x0 ? y0 : y0 + (y1 - y0) * (p - x0) / (x1 - x0);
        }
        return points[points.length - 1][1];
    }

    /** Replaces each event's confidence with the calibrated probability that it is right; false without a model. */
    public static boolean calibrate(Score score) {
        return calibrate(score, loadModel());
    }

    public static boolean calibrate(Score score, Model model) {
        if (model == null) return false;
        for (Map.Entry<Event, double[]> f : eventFeatures(score)) f.getKey().confidence = Py.round(predict(model, f.getValue()), 4);
        return true;
    }
}
