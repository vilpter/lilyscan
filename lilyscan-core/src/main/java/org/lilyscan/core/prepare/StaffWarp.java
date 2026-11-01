package org.lilyscan.core.prepare;

import java.util.ArrayList;
import java.util.List;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import static org.bytedeco.opencv.global.opencv_core.BORDER_DEFAULT;
import static org.bytedeco.opencv.global.opencv_core.BORDER_REPLICATE;
import static org.bytedeco.opencv.global.opencv_core.ALGO_HINT_DEFAULT;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * Staff-based measurements and dewarping (port of {@code lilyscan/prepare/staff.py}): staff
 * line thickness and interline, the vertical displacement of the lines across the page, a
 * smooth field fitted to it, and the remap that removes it.
 */
final class StaffWarp {
    private StaffWarp() {}

    static final int STRIPS = 24;
    static final double WINDOW = 6.0;
    static final double STEP = 2.0;
    static final double MIN_MATCH = 0.6;
    static final int MIN_SAMPLES = 20;

    record Scale(double thickness, double interline) {}

    private static Integer mode(List<Integer> values, int low, int high) {
        int[] counts = new int[Math.max(high + 1, 1)];
        boolean any = false;
        for (int v : values) {
            if (v >= low && v <= high) {
                counts[v]++;
                any = true;
            }
        }
        if (!any) return null;
        int best = 0;
        for (int v = 1; v < counts.length; v++) if (counts[v] > counts[best]) best = v;
        return best;
    }

    /** Staff line thickness and interline from the modal vertical black and white runs. */
    static Scale measureScale(Np.U8 mask) {
        int h = mask.h, w = mask.w;
        int every = Math.max(1, w / 400);
        List<Integer> black = new ArrayList<>(), white = new ArrayList<>();
        for (int x = 0; x < w; x += every) {
            // Runs alternate black, white, black, ... from the first black run.
            List<Integer> edges = new ArrayList<>();
            int prev = 0;
            for (int y = 0; y <= h; y++) {
                int v = y < h ? (mask.a[y * w + x] != 0 ? 1 : 0) : 0;
                if (v != prev) edges.add(y);
                prev = v;
            }
            for (int k = 0; k + 1 < edges.size(); k++) {
                int run = edges.get(k + 1) - edges.get(k);
                (k % 2 == 0 ? black : white).add(run);
            }
        }
        Integer thickness = mode(black, 1, Math.max(2, h / 150));
        if (thickness == null) return null;
        Integer space = mode(white, 2 * thickness, Math.max(3 * thickness, h / 25));
        if (space == null) return null;
        return new Scale(thickness, space + thickness);
    }

    /** Ink per row in each strip (strips x rows), and the strips' centre x. */
    static Object[] profiles(Np.U8 mask) {
        int w = mask.w, h = mask.h;
        int[] bounds = new int[STRIPS + 1];
        double step = w / (double) STRIPS;
        for (int i = 0; i <= STRIPS; i++) bounds[i] = (int) (i == STRIPS ? (double) w : i * step);
        Np.F ink = new Np.F(h, STRIPS); // rows of the array: strips; columns: page rows
        for (int s = 0; s < STRIPS; s++) {
            int a = bounds[s], b = bounds[s + 1];
            for (int y = 0; y < h; y++) {
                long n = 0;
                for (int x = a; x < b; x++) n += mask.a[y * w + x] & 255;
                ink.a[s * h + y] = (float) ((double) n / (b - a));
            }
        }
        Mat rows = new Mat();
        // Smooth along each profile (not across strips) for a sub-pixel peak.
        GaussianBlur(Np.mat(ink), rows, new Size(7, 1), 1.2, 0.01, BORDER_DEFAULT, ALGO_HINT_DEFAULT);
        float[] centres = new float[STRIPS];
        for (int s = 0; s < STRIPS; s++) centres[s] = (float) ((bounds[s] + bounds[s + 1]) / 2.0);
        return new Object[] {Np.f(rows), centres};
    }

    /** The sub-pixel shift maximizing the correlation of {@code a} around {@code y} with {@code b}, and that correlation. */
    static double[] shift(float[] a, float[] b, int y, int half, int reach) {
        int n = 2 * half;
        float[] ref = new float[n];
        System.arraycopy(a, y - half, ref, 0, n);
        float refMean = Np.mean(ref, 0, n);
        float[] sq = new float[n];
        for (int i = 0; i < n; i++) {
            ref[i] = ref[i] - refMean;
            sq[i] = ref[i] * ref[i];
        }
        double normRef = (float) Math.sqrt(Np.sum(sq));
        double best = -1.0, bestS = 0.0;
        double[] scores = new double[2 * reach + 1];
        float[] seg = new float[n], prod = new float[n];
        for (int s = -reach; s <= reach; s++) {
            System.arraycopy(b, y + s - half, seg, 0, n);
            float segMean = Np.mean(seg, 0, n);
            for (int i = 0; i < n; i++) {
                seg[i] = seg[i] - segMean;
                sq[i] = seg[i] * seg[i];
                prod[i] = ref[i] * seg[i];
            }
            double denom = normRef * (double) (float) Math.sqrt(Np.sum(sq));
            double score = denom > 0 ? (double) (Np.sum(prod) / (float) denom) : -1.0;
            scores[s + reach] = score;
            if (score > best) {
                best = score;
                bestS = s;
            }
        }
        // Sub-pixel peak by a parabola through the best score and its neighbours.
        int k = (int) bestS + reach;
        if (0 < k && k < scores.length - 1) {
            double left = scores[k - 1], mid = scores[k], right = scores[k + 1];
            double curve = left - 2 * mid + right;
            if (curve < 0) bestS += 0.5 * (left - right) / curve;
        }
        return new double[] {bestS, best};
    }

    /** (x, y, d) samples: at (x, y), the staff lines sit d pixels below where they sit in the centre strip. */
    static List<double[]> displacementSamples(Np.U8 mask, double interline) {
        int h = mask.h;
        Object[] p = profiles(mask);
        Np.F rows = (Np.F) p[0];
        float[] centres = (float[]) p[1];
        float[][] strip = new float[STRIPS][];
        for (int s = 0; s < STRIPS; s++) {
            strip[s] = new float[h];
            System.arraycopy(rows.a, s * h, strip[s], 0, h);
        }
        int half = (int) (WINDOW * interline / 2);
        int reach = Math.max(2, (int) (interline * 0.45));
        int step = Math.max(1, (int) (STEP * interline));
        int mid = STRIPS / 2;
        List<double[]> samples = new ArrayList<>();
        for (int y = half + reach + 1; y < h - half - reach - 1; y += step) {
            // Skip windows without staff-line structure in the centre strip.
            if (Np.std(strip[mid], y - half, 2 * half) < 0.02f) continue;
            for (int direction : new int[] {-1, 1}) {
                double offset = 0.0;
                int prev = mid;
                for (int s = mid + direction; direction > 0 ? s < STRIPS : s > -1; s += direction) {
                    int at = Np.round(y + offset);
                    if (at - half - reach < 0 || at + half + reach >= h) break;
                    double[] r = shift(strip[prev], strip[s], at, half, reach);
                    if (r[1] < MIN_MATCH) break;
                    offset += r[0];
                    samples.add(new double[] {centres[s], y, offset});
                    prev = s;
                }
            }
            samples.add(new double[] {centres[mid], y, 0.0});
        }
        return samples;
    }

    /** Polynomial terms x^i y^j, i = 1..3, j = 0..2. */
    static double[] terms(double x, double y) {
        double[] t = new double[9];
        int k = 0;
        for (int i = 1; i <= 3; i++) for (int j = 0; j < 3; j++) t[k++] = power(x, i) * power(y, j);
        return t;
    }

    /** NumPy's {@code x ** n}: squares multiply, other powers call pow. */
    private static double power(double x, int n) {
        return n == 0 ? 1.0 : n == 1 ? x : n == 2 ? x * x : Math.pow(x, n);
    }

    /** A fitted displacement field. */
    record Field(double[] coefficients, int width, int height, double residual) {
        double at(double x, double y) {
            double[] t = terms(x / width - 0.5, y / height - 0.5);
            double d = 0;
            for (int i = 0; i < 9; i++) d += t[i] * coefficients[i];
            return d;
        }
    }

    /** Least-squares displacement field, refitted once without the worst outliers. */
    static Field fitField(List<double[]> samples, int width, int height) {
        if (samples.size() < MIN_SAMPLES) return null;
        int n = samples.size();
        boolean[] keep = new boolean[n];
        java.util.Arrays.fill(keep, true);
        double[] coefficients = new double[9];
        double residual = 0.0;
        double[][] all = new double[n][];
        for (int i = 0; i < n; i++) all[i] = terms(samples.get(i)[0] / width - 0.5, samples.get(i)[1] / height - 0.5);
        for (int round = 0; round < 2; round++) {
            List<double[]> rows = new ArrayList<>();
            List<Double> rhs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (keep[i]) {
                    rows.add(all[i]);
                    rhs.add(samples.get(i)[2]);
                }
            }
            coefficients = Lsq.solve(rows, rhs, 9);
            double[] misfit = new double[n];
            for (int i = 0; i < n; i++) {
                double d = 0;
                for (int c = 0; c < 9; c++) d += all[i][c] * coefficients[c];
                misfit[i] = samples.get(i)[2] - d;
            }
            List<Double> kept = new ArrayList<>();
            for (int i = 0; i < n; i++) if (keep[i]) kept.add(misfit[i] * misfit[i]);
            double[] k2 = new double[kept.size()];
            for (int i = 0; i < k2.length; i++) k2[i] = kept.get(i);
            residual = Math.sqrt(Np.mean(k2));
            double limit = Math.max(3.0 * residual, 1.0);
            for (int i = 0; i < n; i++) keep[i] = Math.abs(misfit[i]) <= limit;
        }
        return new Field(coefficients, width, height, residual);
    }

    /** Removes the displacement: each output row is read from where its line actually lies. */
    static Np.F dewarp(Np.F gray, Field field) {
        int h = gray.h, w = gray.w;
        // Evaluate on a coarse grid and interpolate: the field is smooth.
        Np.F grid = new Np.F(64, 64);
        for (int gy = 0; gy < 64; gy++) {
            double y = gy == 63 ? h - 1 : gy * ((h - 1) / 63.0); // np.linspace
            for (int gx = 0; gx < 64; gx++) {
                double x = gx == 63 ? w - 1 : gx * ((w - 1) / 63.0);
                grid.a[gy * 64 + gx] = (float) field.at(x, y);
            }
        }
        Np.F full = PageImage.resize(grid, w, h, INTER_CUBIC);
        Np.F mapX = new Np.F(w, h), mapY = new Np.F(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                mapX.a[y * w + x] = x;
                mapY.a[y * w + x] = y + full.a[y * w + x];
            }
        }
        return Warp.remapCubicReplicate(gray, mapX, mapY);
    }

    /** RMS vertical displacement of the staff lines across the page, pixels (0 when flat). */
    static double curvature(List<double[]> samples) {
        if (samples.isEmpty()) return 0.0;
        double[] sq = new double[samples.size()];
        for (int i = 0; i < sq.length; i++) sq[i] = samples.get(i)[2] * samples.get(i)[2];
        return Math.sqrt(Np.mean(sq));
    }
}
