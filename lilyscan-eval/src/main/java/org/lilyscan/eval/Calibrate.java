package org.lilyscan.eval;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.omr.OmrException;
import org.lilyscan.core.repair.Confidence;
import org.lilyscan.core.repair.Repairs;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.util.Py;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fit Lilyscan's confidence model on a corpus's cached engine output (port of
 * {@code lilyscan/evaluation/calibrate.py}): every engine event, after geometry and repairs,
 * labelled right or wrong and described by {@link Confidence#FEATURES}; an L2-regularized
 * logistic regression fitted by Newton's method, then an isotonic map fitted on out-of-fold
 * scores.
 *
 * <p>NumPy does the matrix products and the solve through BLAS/LAPACK, whose summation order is
 * not reproduced here; the weights are rounded to 6 decimals, as Python rounds them.
 */
public final class Calibrate {
    private Calibrate() {}

    private static final Logger log = LoggerFactory.getLogger(Calibrate.class);
    static final double L2 = 1e-2;
    static final int ECE_BINS = 10;
    static final int ISOTONIC_BINS = 40;

    /** One labelled event: its piece (folds never split a piece), features, label and engine grade. */
    public record Sample(String group, double[] x, boolean y, Double grade) {}

    /** Features and labels from cached engine output (engine runs are not repeated when cached). */
    public static List<Sample> collect(List<Corpus.Item> items, Path work, Settings settings) throws IOException {
        List<Sample> samples = new ArrayList<>();
        for (Corpus.Item item : items) {
            Score gt = MusicXml.load(item.groundTruth(), "ground-truth");
            for (String variant : Corpus.VARIANTS) {
                Score pred = Harness.engineScore(item, variant, work, settings);
                if (pred == null) continue;
                Path omr = Harness.firstOmrIn(work.resolve(item.id()).resolve(variant));
                OmrBook book = null;
                if (omr != null) {
                    try {
                        book = Harness.readOmr(omr); // boxes are already attached, for the repairs
                    } catch (OmrException e) {
                        log.warn("{}/{}: {}", item.id(), variant, e.getMessage());
                    }
                }
                Repairs.apply(pred, book);
                Map<Event, Boolean> labels = Compare.correctEvents(gt, pred);
                for (Map.Entry<Event, double[]> f : Confidence.eventFeatures(pred)) {
                    samples.add(new Sample(item.id(), f.getValue(), labels.getOrDefault(f.getKey(), false), f.getKey().confidence));
                }
            }
        }
        return samples;
    }

    /** Logistic regression by Newton's method, weights in raw feature units. */
    static Confidence.Model fitLogistic(List<Sample> samples, double l2) {
        int n = samples.size(), f = samples.get(0).x().length, d = f + 1;
        double[] mu = new double[f], sd = new double[f];
        for (Sample s : samples) for (int j = 0; j < f; j++) mu[j] += s.x()[j];
        for (int j = 0; j < f; j++) mu[j] /= n;
        for (Sample s : samples) for (int j = 0; j < f; j++) {
            double c = s.x()[j] - mu[j];
            sd[j] += c * c;
        }
        for (int j = 0; j < f; j++) {
            sd[j] = Math.sqrt(sd[j] / n);
            if (sd[j] == 0) sd[j] = 1.0;
        }
        double[][] z = new double[n][d];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            z[i][0] = 1.0;
            for (int j = 0; j < f; j++) z[i][j + 1] = (samples.get(i).x()[j] - mu[j]) / sd[j];
            y[i] = samples.get(i).y() ? 1.0 : 0.0;
        }
        double penalty = l2 * n; // no penalty on the intercept
        double[] w = new double[d];
        for (int iter = 0; iter < 50; iter++) {
            double[] gradient = new double[d];
            double[][] hessian = new double[d][d];
            for (int i = 0; i < n; i++) {
                double s = 0;
                for (int j = 0; j < d; j++) s += z[i][j] * w[j];
                double p = 1.0 / (1.0 + Math.exp(-Math.max(-30, Math.min(30, s))));
                double r = p - y[i], q = p * (1 - p);
                for (int j = 0; j < d; j++) {
                    gradient[j] += z[i][j] * r;
                    double zq = z[i][j] * q;
                    for (int k = 0; k < d; k++) hessian[j][k] += zq * z[i][k];
                }
            }
            for (int j = 1; j < d; j++) {
                gradient[j] += penalty * w[j];
                hessian[j][j] += penalty;
            }
            double[] step = solve(hessian, gradient);
            double max = 0;
            for (int j = 0; j < d; j++) {
                w[j] -= step[j];
                max = Math.max(max, Math.abs(step[j]));
            }
            if (max < 1e-8) break;
        }
        // Fold the standardization into the weights so applying needs no statistics.
        double[] weights = new double[f];
        double fold = 0;
        for (int j = 0; j < f; j++) {
            weights[j] = Py.round(w[j + 1] / sd[j], 6);
            fold += w[j + 1] * mu[j] / sd[j];
        }
        return new Confidence.Model(weights, Py.round(w[0] - fold, 6), new double[0][]);
    }

    /** Gaussian elimination with partial pivoting (as LAPACK's gesv). */
    static double[] solve(double[][] a0, double[] b0) {
        int n = b0.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) a[i] = a0[i].clone();
        double[] b = b0.clone();
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r;
            double[] t = a[c];
            a[c] = a[piv];
            a[piv] = t;
            double tb = b[c];
            b[c] = b[piv];
            b[piv] = tb;
            for (int r = c + 1; r < n; r++) {
                double m = a[r][c] / a[c][c];
                if (m == 0) continue;
                for (int k = c; k < n; k++) a[r][k] -= m * a[c][k];
                b[r] -= m * b[c];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < n; k++) s -= a[r][k] * x[k];
            x[r] = s / a[r][r];
        }
        return x;
    }

    private record Pair(double p, boolean ok) {}

    /** Expected calibration error of predictions against outcomes. */
    static double ece(List<Double> preds, List<Boolean> ys) {
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < preds.size(); i++) pairs.add(new Pair(preds.get(i), ys.get(i)));
        return ece(pairs);
    }

    /** Expected calibration error over equal-width confidence bins. */
    static double ece(List<Pair> pairs) {
        double total = 0.0;
        for (int b = 0; b < ECE_BINS; b++) {
            double lo = (double) b / ECE_BINS, hi = (double) (b + 1) / ECE_BINS;
            int n = 0, ok = 0;
            double conf = 0;
            for (Pair p : pairs) {
                if ((lo <= p.p() && p.p() < hi) || (b == ECE_BINS - 1 && p.p() == 1.0)) {
                    n++;
                    if (p.ok()) ok++;
                    conf += p.p();
                }
            }
            if (n > 0) total += (double) n / pairs.size() * Math.abs((double) ok / n - conf / n);
        }
        return Py.round(total, 4);
    }

    /** Monotone map from prediction to accuracy: pool adjacent violators over bins of equal size. */
    static double[][] fitIsotonic(List<Double> preds, List<Boolean> ys) {
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < preds.size(); i++) pairs.add(new Pair(preds.get(i), ys.get(i)));
        pairs.sort(Comparator.comparingDouble(Pair::p).thenComparing(Pair::ok));
        int size = Math.max(1, pairs.size() / ISOTONIC_BINS);
        List<double[]> merged = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i += size) {
            double sp = 0;
            int sy = 0;
            List<Pair> chunk = pairs.subList(i, Math.min(i + size, pairs.size()));
            for (Pair p : chunk) {
                sp += p.p();
                if (p.ok()) sy++;
            }
            merged.add(new double[] {sp, sy, chunk.size()});
            while (merged.size() > 1) {
                double[] a = merged.get(merged.size() - 2), last = merged.get(merged.size() - 1);
                if (!(a[1] / a[2] > last[1] / last[2])) break;
                merged.remove(merged.size() - 1);
                merged.set(merged.size() - 1, new double[] {a[0] + last[0], a[1] + last[1], a[2] + last[2]});
            }
        }
        double[][] out = new double[merged.size()][];
        for (int i = 0; i < out.length; i++) {
            double[] b = merged.get(i);
            out[i] = new double[] {Py.round(b[0] / b[2], 6), Py.round(b[1] / b[2], 6)};
        }
        return out;
    }

    private static List<Set<String>> folds(List<Sample> samples, int folds) {
        TreeSet<String> groups = new TreeSet<>();
        for (Sample s : samples) groups.add(s.group());
        List<String> sorted = new ArrayList<>(groups);
        List<Set<String>> out = new ArrayList<>();
        for (int k = 0; k < folds; k++) {
            Set<String> fold = new java.util.HashSet<>();
            for (int i = k; i < sorted.size(); i += folds) fold.add(sorted.get(i));
            out.add(fold);
        }
        return out;
    }

    /** Logistic model on all samples, recalibrated on its out-of-fold predictions. */
    static Confidence.Model fit(List<Sample> samples, int folds) {
        Confidence.Model model = fitLogistic(samples, L2);
        double[] oof = new double[samples.size()];
        for (Set<String> held : folds(samples, folds)) {
            List<Sample> train = new ArrayList<>();
            for (Sample s : samples) if (!held.contains(s.group())) train.add(s);
            Confidence.Model inner = fitLogistic(train, L2);
            for (int i = 0; i < samples.size(); i++) if (held.contains(samples.get(i).group())) oof[i] = Confidence.predict(inner, samples.get(i).x());
        }
        List<Double> preds = new ArrayList<>();
        List<Boolean> ys = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            preds.add(oof[i]);
            ys.add(samples.get(i).y());
        }
        return new Confidence.Model(model.weights(), model.bias(), fitIsotonic(preds, ys));
    }

    /** Out-of-fold predictions of the whole procedure, in sample order. */
    static double[] crossValidate(List<Sample> samples, int folds) {
        double[] out = new double[samples.size()];
        for (Set<String> held : folds(samples, folds)) {
            List<Sample> train = new ArrayList<>();
            for (Sample s : samples) if (!held.contains(s.group())) train.add(s);
            Confidence.Model model = fit(train, 4);
            for (int i = 0; i < samples.size(); i++) if (held.contains(samples.get(i).group())) out[i] = Confidence.predict(model, samples.get(i).x());
        }
        return out;
    }

    private static Map<String, Object> summary(String name, List<Sample> samples, double[] fitted, double[] oof) {
        List<Pair> graded = new ArrayList<>(), in = new ArrayList<>(), out = new ArrayList<>();
        int right = 0;
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            if (s.grade() != null) graded.add(new Pair(s.grade(), s.y()));
            in.add(new Pair(fitted[i], s.y()));
            out.add(new Pair(oof[i], s.y()));
            if (s.y()) right++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("corpus", name);
        m.put("events", samples.size());
        m.put("accuracy", Py.round((double) right / samples.size(), 4));
        m.put("ece_in_sample", ece(in));
        m.put("ece_out_of_fold", ece(out));
        m.put("ece_engine_grade", graded.isEmpty() ? null : ece(graded));
        return m;
    }

    /** Fits on every corpus together; reports each corpus in-sample and out of fold; writes the model. */
    public static Map<String, Object> calibrate(Map<String, List<Sample>> corpora, Path out) {
        List<Sample> samples = new ArrayList<>();
        corpora.values().forEach(samples::addAll);
        Confidence.Model model = fit(samples, 4);
        double[] oof = crossValidate(samples, 5);
        List<Object> results = new ArrayList<>();
        int start = 0;
        for (Map.Entry<String, List<Sample>> e : corpora.entrySet()) {
            List<Sample> d = e.getValue();
            double[] fitted = new double[d.size()], part = new double[d.size()];
            for (int i = 0; i < d.size(); i++) {
                fitted[i] = Confidence.predict(model, d.get(i).x());
                part[i] = oof[start + i];
            }
            results.add(summary(e.getKey(), d, fitted, part));
            start += d.size();
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("features", Confidence.FEATURES);
        List<Double> weights = new ArrayList<>();
        for (double w : model.weights()) weights.add(w);
        doc.put("weights", weights);
        doc.put("bias", model.bias());
        List<Object> cal = new ArrayList<>();
        for (double[] p : model.calibration()) cal.add(List.of(p[0], p[1]));
        doc.put("calibration", cal);
        Map<String, Object> trained = new LinkedHashMap<>();
        trained.put("corpora", new ArrayList<>(corpora.keySet()));
        trained.put("events", samples.size());
        trained.put("created", ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+00:00'")));
        trained.put("evaluation", results);
        doc.put("trained", trained);
        Harness.write(out, PyJson.write(IrJson.mapper().valueToTree(doc), PyJson.Style.jsonDumps(1)) + "\n");
        return doc;
    }
}
