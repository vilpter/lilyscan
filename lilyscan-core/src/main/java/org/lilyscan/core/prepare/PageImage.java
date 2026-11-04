package org.lilyscan.core.prepare;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bytedeco.javacpp.indexer.DoubleIndexer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.opencv.global.opencv_photo;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Point2f;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.lilyscan.core.runtime.Config;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * Page geometry, orientation and light (port of {@code lilyscan/prepare/image.py}): find the
 * page in a photo, turn it upright, flatten the light. Images are float32, 0 = black, 1 = white.
 */
final class PageImage {
    private PageImage() {}

    /** Pages are detected in a downscaled copy of this size (longest side, pixels). */
    static final int DETECT_SIZE = 1000;
    /** A page filling more than this share of the frame is already cropped (a scan). */
    static final double FULL_FRAME = 0.95;

    static Np.F resize(Np.F img, int w, int h, int interpolation) {
        Mat out = new Mat();
        org.bytedeco.opencv.global.opencv_imgproc.resize(Np.mat(img), out, new Size(w, h), 0, 0, interpolation);
        return Np.f(out);
    }

    /** {@code cv2.resize(img, None, fx=s, fy=s)}: OpenCV rounds the size. */
    static Np.F resizeBy(Np.F img, double s, int interpolation) {
        Mat out = new Mat();
        org.bytedeco.opencv.global.opencv_imgproc.resize(Np.mat(img), out, new Size(0, 0), s, s, interpolation);
        return Np.f(out);
    }

    static Np.F gaussian(Np.F img, double sigma) {
        Mat out = new Mat();
        GaussianBlur(Np.mat(img), out, new Size(0, 0), sigma);
        return Np.f(out);
    }

    /** Corners as top-left, top-right, bottom-right, bottom-left. */
    static float[][] order(float[][] quad) {
        int amin = 0, amax = 0, dmin = 0, dmax = 0;
        for (int i = 1; i < 4; i++) {
            float s = quad[i][0] + quad[i][1], d = quad[i][1] - quad[i][0];
            if (s < quad[amin][0] + quad[amin][1]) amin = i;
            if (s > quad[amax][0] + quad[amax][1]) amax = i;
            if (d < quad[dmin][1] - quad[dmin][0]) dmin = i;
            if (d > quad[dmax][1] - quad[dmax][0]) dmax = i;
        }
        return new float[][] {quad[amin].clone(), quad[dmin].clone(), quad[amax].clone(), quad[dmax].clone()};
    }

    private static float[][] points(Mat m) {
        Mat c = new Mat();
        m.convertTo(c, CV_32F);
        int n = (int) (c.total());
        float[] flat = new float[n * 2];
        ((FloatIndexer) c.reshape(1, n).createIndexer()).get(0L, flat);
        float[][] out = new float[n][2];
        for (int i = 0; i < n; i++) out[i] = new float[] {flat[2 * i], flat[2 * i + 1]};
        return out;
    }

    /** The page's corners in a photo, or null when the page fills the frame or none is found. */
    static float[][] findPage(Np.F gray) {
        double scale = (double) DETECT_SIZE / Math.max(gray.h, gray.w);
        Np.F small = resize(gray, Np.round(gray.w * scale), Np.round(gray.h * scale), INTER_AREA);
        Mat u8 = new Mat();
        GaussianBlur(Np.mat(Np.toU8(small, 0f)), u8, new Size(5, 5), 0);
        Mat mask = new Mat();
        threshold(u8, mask, 0, 255, THRESH_BINARY + THRESH_OTSU);
        Mat closed = new Mat();
        morphologyEx(mask, closed, MORPH_CLOSE, new Mat(15, 15, CV_8U, new Scalar(1)));
        MatVector contours = new MatVector();
        findContours(closed, contours, new Mat(), RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);
        if (contours.size() == 0) return null;
        Mat outline = null;
        double best = 0;
        for (long i = 0; i < contours.size(); i++) {
            double area = contourArea(contours.get(i));
            if (outline == null || area > best) {
                best = area;
                outline = contours.get(i);
            }
        }
        double share = best / (double) (closed.rows() * closed.cols());
        if (!(Config.PREPARE_MIN_PAGE_AREA <= share && share <= FULL_FRAME)) return null;
        Mat hull = new Mat();
        convexHull(outline, hull);
        double perimeter = arcLength(hull, true);
        float[][] quad = null;
        for (double eps : new double[] {0.01, 0.02, 0.03, 0.05}) {
            Mat approx = new Mat();
            approxPolyDP(hull, approx, eps * perimeter, true);
            if (approx.total() == 4) {
                quad = points(approx);
                break;
            }
        }
        if (quad == null) {
            Mat box = new Mat();
            boxPoints(minAreaRect(hull), box);
            quad = points(box);
        }
        if (cutLines(inkMask(small), quad) > Config.PREPARE_MAX_CUT_LINES) return null;
        float s = (float) scale;
        float[][] scaled = new float[4][];
        for (int i = 0; i < 4; i++) scaled[i] = new float[] {quad[i][0] / s, quad[i][1] / s};
        return order(scaled);
    }

    /** Share of the staff lines mostly inside {@code quad} that the outline cuts off. */
    static double cutLines(Np.U8 mask, float[][] quad) {
        Mat inside = new Mat(mask.h, mask.w, CV_8U, new Scalar(0));
        Mat poly = new Mat(4, 1, CV_32SC2);
        IntIndexer pi = poly.createIndexer();
        for (int i = 0; i < 4; i++) {
            pi.put(i, 0, 0, (int) Math.rint(quad[i][0]));
            pi.put(i, 0, 1, (int) Math.rint(quad[i][1]));
        }
        fillPoly(inside, new MatVector(poly), new Scalar(1));
        Np.U8 in = Np.u8(inside);
        Np.U8 lines = lines(mask, true);
        Np.U8 bin = new Np.U8(lines.w, lines.h);
        for (int i = 0; i < bin.a.length; i++) bin.a[i] = (byte) (lines.a[i] != 0 ? 1 : 0);
        Mat labels = new Mat();
        int n = connectedComponents(Np.mat(bin), labels, 8, CV_32S);
        int[] lab = new int[mask.w * mask.h];
        ((IntIndexer) labels.createIndexer()).get(0L, lab);
        long[] total = new long[n];
        double[] kept = new double[n];
        for (int i = 0; i < lab.length; i++) {
            total[lab[i]]++;
            kept[lab[i]] += in.a[i];
        }
        double whole = 0, cut = 0;
        for (int k = 1; k < n; k++) {
            if (kept[k] > total[k] / 2.0) { // lines of this page, not of a facing page
                whole += total[k];
                cut += total[k] - kept[k];
            }
        }
        return whole != 0 ? cut / whole : 0.0;
    }

    private static float norm(float[] a, float[] b) {
        float dx = a[0] - b[0], dy = a[1] - b[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** The page seen head-on, trimmed slightly so no background remains at the edges. */
    static Np.F warpPage(Np.F gray, float[][] corners) {
        double trim = 0.004;
        float[] tl = corners[0], tr = corners[1], br = corners[2], bl = corners[3];
        int width = Np.round(Math.max(norm(tr, tl), norm(br, bl)));
        int height = Np.round(Math.max(norm(bl, tl), norm(br, tr)));
        Mat src = new Mat(4, 1, CV_32FC2), dst = new Mat(4, 1, CV_32FC2);
        FloatIndexer si = src.createIndexer(), di = dst.createIndexer();
        float[][] target = {{0, 0}, {width - 1, 0}, {width - 1, height - 1}, {0, height - 1}};
        for (int i = 0; i < 4; i++) {
            si.put(i, 0, 0, corners[i][0]);
            si.put(i, 0, 1, corners[i][1]);
            di.put(i, 0, 0, target[i][0]);
            di.put(i, 0, 1, target[i][1]);
        }
        DoubleIndexer mi = getPerspectiveTransform(src, dst).createIndexer();
        double[] m = new double[9];
        for (int i = 0; i < 9; i++) m[i] = mi.get(i / 3, i % 3);
        Np.F full = Warp.perspectiveCubic(gray, m, width, height, 1f);
        int dy = (int) (height * trim), dx = (int) (width * trim);
        return crop(full, dx, dy, width - dx, height - dy);
    }

    static Np.F crop(Np.F img, int x0, int y0, int x1, int y1) {
        x0 = Np.clamp(x0, img.w);
        x1 = Np.clamp(x1, img.w);
        y0 = Np.clamp(y0, img.h);
        y1 = Np.clamp(y1, img.h);
        Np.F out = new Np.F(Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        for (int y = 0; y < out.h; y++) System.arraycopy(img.a, (y0 + y) * img.w + x0, out.a, y * out.w, out.w);
        return out;
    }

    /** Divide out uneven lighting: the paper's brightness, estimated with a max filter and smoothing, becomes white. */
    static Np.F flattenLight(Np.F gray) {
        int factor = 4;
        Np.F small = resize(gray, Math.max(1, gray.w / factor), Math.max(1, gray.h / factor), INTER_AREA);
        int k = Math.max(3, (small.w / 40) | 1);
        Mat paper = new Mat();
        dilate(Np.mat(small), paper, getStructuringElement(MORPH_RECT, new Size(k, k)));
        Mat blurred = new Mat();
        GaussianBlur(paper, blurred, new Size(0, 0), (double) k);
        Np.F p = resize(Np.f(blurred), gray.w, gray.h, INTER_LINEAR);
        float[] out = new float[gray.a.length];
        for (int i = 0; i < out.length; i++) out[i] = gray.a[i] / Math.max(p.a[i], 0.05f);
        float ink = Np.percentile(out, 0.5);
        float denom = (float) Math.max(1e-3, 1.0 - (double) ink);
        for (int i = 0; i < out.length; i++) {
            float v = (out[i] - ink) / denom;
            out[i] = v < 0f ? 0f : v > 1f ? 1f : v;
        }
        return new Np.F(gray.w, gray.h, out);
    }

    /** Ink as 1, paper as 0 (Otsu's threshold on a light-flattened page). */
    static Np.U8 inkMask(Np.F gray) {
        Mat out = new Mat();
        threshold(Np.mat(Np.toU8(gray, 0f)), out, 0, 1, THRESH_BINARY_INV + THRESH_OTSU);
        return Np.u8(out);
    }

    /** Remove sensor noise and JPEG speckle, then restore edges the camera blurred. */
    static Np.F sharpen(Np.F gray, double interline) {
        Mat clean = new Mat();
        opencv_photo.fastNlMeansDenoising(Np.mat(Np.toU8(gray, 0.5f)), clean, 12, 7, 21);
        Np.U8 c8 = Np.u8(clean);
        Np.F c = new Np.F(gray.w, gray.h);
        for (int i = 0; i < c.a.length; i++) c.a[i] = (float) (c8.a[i] & 255) / 255f;
        Np.F soft = gaussian(c, Math.max(0.8, interline / 5));
        for (int i = 0; i < c.a.length; i++) {
            float v = c.a[i] + 1.0f * (c.a[i] - soft.a[i]);
            c.a[i] = v < 0f ? 0f : v > 1f ? 1f : v;
        }
        return c;
    }

    /** Black and white by a local threshold over a window of about three staff spaces. */
    static Np.F binarize(Np.F gray, double interline) {
        int block = Math.max(3, (int) (3 * interline) | 1);
        Mat out = new Mat();
        adaptiveThreshold(Np.mat(Np.toU8(gray, 0.5f)), out, 255, ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY, block, 15);
        Np.U8 o = Np.u8(out);
        Np.F f = new Np.F(gray.w, gray.h);
        for (int i = 0; i < f.a.length; i++) f.a[i] = (float) (o.a[i] & 255) / 255f;
        return f;
    }

    /** Long straight strokes in one direction (staff lines, or stems and barlines). */
    static Np.U8 lines(Np.U8 mask, boolean horizontal) {
        int length = Math.max(mask.h, mask.w) / 12;
        Size size = horizontal ? new Size(length, 1) : new Size(1, length);
        Mat out = new Mat();
        morphologyEx(Np.mat(mask), out, MORPH_OPEN, getStructuringElement(MORPH_RECT, size));
        return Np.u8(out);
    }

    /** A staff: (top, bottom, left, right). */
    record StaffBox(int top, int bottom, int x0, int x1) {}

    /** Staves from the rows of long horizontal strokes: five lines, evenly spaced. */
    static List<StaffBox> staves(Np.U8 lines) {
        int width = lines.w;
        List<Double> centres = new ArrayList<>();
        List<Integer> run = new ArrayList<>();
        for (int r = 0; r < lines.h; r++) {
            long s = 0;
            for (int x = 0; x < width; x++) s += lines.a[r * width + x] & 255;
            if (!(s > 0.25 * width)) continue;
            if (!run.isEmpty() && r != run.get(run.size() - 1) + 1) {
                centres.add(mean(run));
                run.clear();
            }
            run.add(r);
        }
        if (!run.isEmpty()) centres.add(mean(run));
        List<StaffBox> staves = new ArrayList<>();
        int i = 0;
        while (i + 4 < centres.size()) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < 4; k++) {
                double g = centres.get(i + k + 1) - centres.get(i + k);
                min = Math.min(min, g);
                max = Math.max(max, g);
            }
            if (min > 1 && max <= 1.35 * min) {
                int top = (int) (double) centres.get(i), bottom = Np.round(centres.get(i + 4));
                int lo = -1, hi = -1;
                for (int x = 0; x < width; x++) {
                    boolean any = false;
                    for (int y = Np.clamp(top, lines.h); y < Np.clamp(bottom + 1, lines.h) && !any; y++) any = lines.a[y * width + x] != 0;
                    if (any) {
                        if (lo < 0) lo = x;
                        hi = x;
                    }
                }
                if (lo >= 0) staves.add(new StaffBox(top, bottom, lo, hi));
                i += 5;
            } else {
                i += 1;
            }
        }
        return staves;
    }

    private static double mean(List<Integer> run) {
        long s = 0;
        for (int r : run) s += r;
        return (double) s / run.size();
    }

    private static long count(boolean[] symbols, int w, int y0, int y1, int x0, int x1) {
        long n = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if (symbols[y * w + x]) n++;
        return n;
    }

    /** Positive when more ink sticks out beyond the staves at their left ends than at their right ends. */
    static double startWeight(Np.U8 mask) {
        Np.U8 lines = lines(mask, true);
        boolean[] symbols = new boolean[mask.a.length];
        for (int i = 0; i < symbols.length; i++) symbols[i] = mask.a[i] != 0 && lines.a[i] == 0;
        double left = 0, right = 0;
        int h = mask.h, w = mask.w;
        for (StaffBox s : staves(lines)) {
            double space = (s.bottom() - s.top()) / 4.0;
            int reach = (int) (2.5 * space);
            int zone = Math.max(1, (int) (3 * space));
            int[][] bands = {{Math.max(0, s.top() - reach), Math.max(0, s.top() - 1)}, {s.bottom() + 2, s.bottom() + reach}};
            for (int[] rows : bands) {
                int y0 = Np.clamp(rows[0], h), y1 = Np.clamp(rows[1], h);
                left += count(symbols, w, y0, y1, Np.clamp(s.x0(), w), Np.clamp(s.x0() + zone, w));
                right += count(symbols, w, y0, y1, Np.clamp(Math.max(0, s.x1() - zone), w), Np.clamp(s.x1() + 1, w));
            }
        }
        return (left - right) / Math.max(1.0, left + right);
    }

    static Np.U8 smallMask(Np.F gray, int size) {
        double scale = (double) size / Math.max(gray.h, gray.w);
        Np.F small = scale < 1 ? resizeBy(gray, scale, INTER_AREA) : gray;
        return inkMask(small);
    }

    /** Rotate counterclockwise by {@code degrees} on a canvas that keeps every corner. */
    static Np.F rotate(Np.F img, double degrees, double fill) {
        int h = img.h, w = img.w;
        Mat matrix = getRotationMatrix2D(new Point2f(w / 2f, h / 2f), degrees, 1.0);
        DoubleIndexer m = matrix.createIndexer();
        double cos = Math.abs(m.get(0, 0)), sin = Math.abs(m.get(0, 1));
        int sw = (int) (h * sin + w * cos + 0.5), sh = (int) (h * cos + w * sin + 0.5);
        m.put(0, 2, m.get(0, 2) + (sw / 2.0 - w / 2.0));
        m.put(1, 2, m.get(1, 2) + (sh / 2.0 - h / 2.0));
        return Warp.affineLinear(img, new double[] {m.get(0, 0), m.get(0, 1), m.get(0, 2), m.get(1, 0), m.get(1, 1), m.get(1, 2)},
                sw, sh, (float) fill);
    }

    private static Np.F asFloat(Np.U8 mask) {
        Np.F f = new Np.F(mask.w, mask.h);
        for (int i = 0; i < f.a.length; i++) f.a[i] = mask.a[i] & 255;
        return f;
    }

    /** How spiky the ink per row is after rotating. */
    static float sharpness(Np.F maskF, double degrees) {
        Np.F r = rotate(maskF, degrees, 0.0);
        float[] rows = new float[r.h];
        for (int y = 0; y < r.h; y++) rows[y] = Np.sum(r.a, y * r.w, r.w, 1);
        float[] sq = new float[r.h];
        for (int y = 0; y < r.h; y++) sq[y] = rows[y] * rows[y];
        return Np.sum(sq);
    }

    static int fiveLineStaves(Np.F maskF, double degrees) {
        Np.F r = rotate(maskF, degrees, 0.0);
        Np.U8 turned = new Np.U8(r.w, r.h);
        for (int i = 0; i < r.a.length; i++) turned.a[i] = (byte) (r.a[i] > 0.5f ? 1 : 0);
        return staves(lines(turned, true)).size();
    }

    /** Degrees (counterclockwise) that make the staff lines horizontal, in [-90, 90). */
    static double staffAngle(Np.F gray) {
        Np.F mask = asFloat(smallMask(gray, 1000));
        double coarse = 0;
        float best = Float.NaN;
        for (int a = -90; a < 90; a += 2) {
            float s = sharpness(mask, a);
            if (Float.isNaN(best) || s > best) {
                best = s;
                coarse = a;
            }
        }
        double bestAngle = refine(mask, coarse);
        double other = refine(mask, coarse >= 0 ? coarse - 90 : coarse + 90);
        Np.F staves = asFloat(smallMask(gray, 2000));
        if (fiveLineStaves(staves, other) > fiveLineStaves(staves, bestAngle)) bestAngle = other;
        return ((bestAngle + 90) % 180 + 180) % 180 - 90;
    }

    private static double refine(Np.F mask, double coarse) {
        double bestA = 0;
        float best = Float.NaN;
        for (int d = -8; d <= 8; d++) {
            double a = coarse + d / 4.0;
            float s = sharpness(mask, a);
            if (Float.isNaN(best) || s > best) {
                best = s;
                bestA = a;
            }
        }
        return bestA;
    }

    /** The page turned so its staff lines run across it, and the angle turned. */
    static Object[] levelStaves(Np.F gray) {
        double angle = staffAngle(gray);
        if (Math.abs(angle) < 2.0) return new Object[] {gray, 0.0};
        if (Math.abs(Math.abs(angle) - 90) < 0.5) {
            return new Object[] {Np.rot90(gray, angle > 0 ? 1 : 3), angle > 0 ? 90.0 : -90.0};
        }
        return new Object[] {rotate(gray, angle, 1.0), angle};
    }

    static final double TEXT_UNIT = 0.01, CLEF_UNIT = 0.2, UPRIGHT_VETO = 1.5, BOTH_LEAN = 0.5, ONE_STRONG = 5.0;
    static final int MIN_LETTERS = 60;

    /** How upright the page's text reads, and how many letters that rests on. */
    static double[] textLean(Np.U8 mask) {
        Np.U8 h = lines(mask, true), v = lines(mask, false);
        Np.U8 symbols = new Np.U8(mask.w, mask.h);
        for (int i = 0; i < symbols.a.length; i++) {
            symbols.a[i] = (byte) (mask.a[i] != 0 && (h.a[i] | v.a[i]) == 0 ? 1 : 0);
        }
        Mat labels = new Mat(), stats = new Mat(), cents = new Mat();
        int n = connectedComponentsWithStats(Np.mat(symbols), labels, stats, cents, 8, CV_32S);
        IntIndexer st = stats.createIndexer();
        List<int[]> boxes = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            int bh = st.get(i, CC_STAT_HEIGHT), bw = st.get(i, CC_STAT_WIDTH);
            if (bh >= 6 && bh <= 45 && bw >= 2 && bw <= 45) boxes.add(new int[] {st.get(i, CC_STAT_LEFT), st.get(i, CC_STAT_TOP), bw, bh});
        }
        if (boxes.isEmpty()) return new double[] {0.0, 0};
        int m = boxes.size();
        double[] top = new double[m], left = new double[m], height = new double[m], width = new double[m], centre = new double[m];
        for (int i = 0; i < m; i++) {
            left[i] = boxes.get(i)[0];
            top[i] = boxes.get(i)[1];
            width[i] = boxes.get(i)[2];
            height[i] = boxes.get(i)[3];
            centre[i] = top[i] + height[i] / 2;
        }
        boolean[] used = new boolean[m];
        double lean = 0;
        int letters = 0;
        for (int i : argsort(centre)) {
            if (used[i]) continue;
            List<Integer> rowList = new ArrayList<>();
            for (int j = 0; j < m; j++) if (!used[j] && Math.abs(centre[j] - centre[i]) < 0.6 * height[i]) rowList.add(j);
            double[] rowLeft = new double[rowList.size()];
            for (int k = 0; k < rowLeft.length; k++) rowLeft[k] = left[rowList.get(k)];
            int[] order = argsort(rowLeft);
            int[] row = new int[order.length];
            for (int k = 0; k < order.length; k++) row[k] = rowList.get(order[k]);
            // Split the row into words and phrases: letters close side by side.
            List<List<Integer>> runs = new ArrayList<>();
            runs.add(new ArrayList<>(List.of(row[0])));
            for (int k = 0; k + 1 < row.length; k++) {
                int a = row[k], b = row[k + 1];
                if (left[b] - (left[a] + width[a]) <= 1.5 * height[i]) runs.get(runs.size() - 1).add(b);
                else runs.add(new ArrayList<>(List.of(b)));
            }
            for (List<Integer> r : runs) {
                if (r.size() < 5) continue;
                for (int k : r) used[k] = true;
                double[] hs = new double[r.size()], tops = new double[r.size()], bottoms = new double[r.size()];
                for (int k = 0; k < r.size(); k++) {
                    hs[k] = height[r.get(k)];
                    tops[k] = top[r.get(k)];
                    bottoms[k] = top[r.get(k)] + height[r.get(k)];
                }
                double size = Np.median(hs);
                double spreadTop = meanAbsDev(tops) / size, spreadBottom = meanAbsDev(bottoms) / size;
                lean += (spreadTop - spreadBottom) * r.size();
                letters += r.size();
            }
        }
        return new double[] {letters > 0 ? lean / letters : 0.0, letters};
    }

    private static double meanAbsDev(double[] xs) {
        double med = Np.median(xs);
        double[] d = new double[xs.length];
        for (int i = 0; i < xs.length; i++) d[i] = Math.abs(xs[i] - med);
        return Np.mean(d);
    }

    /** Indexes sorting {@code values} ascending (stable). */
    static int[] argsort(double[] values) {
        Integer[] idx = new Integer[values.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(values[a], values[b]));
        int[] out = new int[idx.length];
        for (int i = 0; i < out.length; i++) out[i] = idx[i];
        return out;
    }

    /** Text reads upside down and clefs sit at the right ends of the staves, taken together. */
    static boolean upsideDown(Np.F gray, boolean turned) {
        Np.U8 mask = smallMask(gray, 2000);
        double[] tl = textLean(mask);
        double text = tl[1] >= MIN_LETTERS ? tl[0] / TEXT_UNIT : 0.0;
        return turnOver(text, startWeight(mask) / CLEF_UNIT, turned);
    }

    /** The decision on the two cues, in units (positive: the page reads upright). */
    static boolean turnOver(double text, double clef, boolean turned) {
        if (turned) return text + clef < 0;
        if (text >= UPRIGHT_VETO || clef >= UPRIGHT_VETO) return false;
        if (text <= -BOTH_LEAN && clef <= -BOTH_LEAN) return true;
        return Math.min(text, clef) <= -ONE_STRONG;
    }
}
