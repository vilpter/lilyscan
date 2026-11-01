package org.lilyscan.core.prepare;

import java.util.Arrays;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.CV_8U;

/**
 * Stage 1's arrays and the NumPy semantics the Python code relies on: float32 images
 * (0 = black, 1 = white) and uint8 masks, row-major, with NumPy's pairwise summation, float32
 * statistics and percentile, and a bridge to OpenCV (JavaCPP).
 */
final class Np {
    private Np() {}

    /** A float32 grayscale image. */
    static final class F {
        final int w, h;
        final float[] a;

        F(int w, int h) {
            this(w, h, new float[w * h]);
        }

        F(int w, int h, float[] a) {
            this.w = w;
            this.h = h;
            this.a = a;
        }

        float at(int y, int x) {
            return a[y * w + x];
        }
    }

    /** A uint8 image (a mask holds 0 and 1). */
    static final class U8 {
        final int w, h;
        final byte[] a;

        U8(int w, int h) {
            this(w, h, new byte[w * h]);
        }

        U8(int w, int h, byte[] a) {
            this.w = w;
            this.h = h;
            this.a = a;
        }

        int at(int y, int x) {
            return a[y * w + x] & 255;
        }
    }

    // --- OpenCV bridge ---------------------------------------------------------------------

    static Mat mat(F img) {
        Mat m = new Mat(img.h, img.w, CV_32F);
        ((FloatIndexer) m.createIndexer()).put(0L, img.a);
        return m;
    }

    static Mat mat(U8 img) {
        Mat m = new Mat(img.h, img.w, CV_8U);
        ((UByteIndexer) m.createIndexer()).put(0L, toInts(img.a));
        return m;
    }

    private static int[] toInts(byte[] b) {
        int[] out = new int[b.length];
        for (int i = 0; i < b.length; i++) out[i] = b[i] & 255;
        return out;
    }

    static F f(Mat m) {
        Mat c = m.isContinuous() ? m : m.clone();
        F out = new F(c.cols(), c.rows());
        ((FloatIndexer) c.createIndexer()).get(0L, out.a);
        return out;
    }

    static U8 u8(Mat m) {
        Mat c = m.isContinuous() ? m : m.clone();
        U8 out = new U8(c.cols(), c.rows());
        c.data().get(out.a);
        return out;
    }

    // --- NumPy ----------------------------------------------------------------------------

    /** {@code np.clip(img * 255, 0, 255).astype(np.uint8)} ({@code bias} 0) or with {@code + 0.5}. */
    static U8 toU8(F img, float bias) {
        U8 out = new U8(img.w, img.h);
        for (int i = 0; i < img.a.length; i++) {
            float v = img.a[i] * 255f + bias;
            if (v < 0f) v = 0f;
            if (v > 255f) v = 255f;
            out.a[i] = (byte) (int) v;
        }
        return out;
    }

    /** NumPy's float32 pairwise summation over {@code n} elements from {@code off} by {@code stride}. */
    static float sum(float[] a, int off, int n, int stride) {
        if (n < 8) {
            float r = 0f;
            for (int i = 0; i < n; i++) r += a[off + i * stride];
            return r;
        }
        if (n <= 128) {
            float[] r = new float[8];
            for (int j = 0; j < 8; j++) r[j] = a[off + j * stride];
            int i = 8;
            for (; i < n - (n % 8); i += 8) for (int j = 0; j < 8; j++) r[j] += a[off + (i + j) * stride];
            float res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]));
            for (; i < n; i++) res += a[off + i * stride];
            return res;
        }
        int n2 = n / 2;
        n2 -= n2 % 8;
        return sum(a, off, n2, stride) + sum(a, off + n2 * stride, n - n2, stride);
    }

    static float sum(float[] a) {
        return sum(a, 0, a.length, 1);
    }

    /** NumPy's float64 pairwise summation. */
    static double sum(double[] a, int off, int n) {
        if (n < 8) {
            double r = 0;
            for (int i = 0; i < n; i++) r += a[off + i];
            return r;
        }
        if (n <= 128) {
            double[] r = new double[8];
            System.arraycopy(a, off, r, 0, 8);
            int i = 8;
            for (; i < n - (n % 8); i += 8) for (int j = 0; j < 8; j++) r[j] += a[off + i + j];
            double res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]));
            for (; i < n; i++) res += a[off + i];
            return res;
        }
        int n2 = n / 2;
        n2 -= n2 % 8;
        return sum(a, off, n2) + sum(a, off + n2, n - n2);
    }

    static double mean(double[] a) {
        return sum(a, 0, a.length) / a.length;
    }

    /** {@code np.mean} of a float32 array: a float32 sum divided in float32. */
    static float mean(float[] a, int off, int n) {
        return sum(a, off, n, 1) / (float) n;
    }

    /** {@code np.std} of a float32 slice (float32 throughout). */
    static float std(float[] a, int off, int n) {
        float m = mean(a, off, n);
        float[] sq = new float[n];
        for (int i = 0; i < n; i++) {
            float d = a[off + i] - m;
            sq[i] = d * d;
        }
        return (float) Math.sqrt(sum(sq) / (float) n);
    }

    /** {@code np.median} of float64 values. */
    static double median(double[] values) {
        double[] s = values.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    /** {@code np.percentile(a, q)} of a float32 array ("linear"), computed in float32 as NumPy does. */
    static float percentile(float[] values, double q) {
        float[] s = values.clone();
        Arrays.sort(s);
        double index = q / 100.0 * (s.length - 1);
        int lo = (int) Math.floor(index);
        int hi = Math.min(lo + 1, s.length - 1);
        float t = (float) (index - lo);
        float a = s[lo], b = s[hi];
        float diff = b - a;
        return t >= 0.5f ? b - diff * (1f - t) : a + diff * t;
    }

    /** {@code np.rot90(img, k)}: {@code k} quarter turns counterclockwise. */
    static F rot90(F img, int k) {
        k = ((k % 4) + 4) % 4;
        F out = img;
        for (int t = 0; t < k; t++) {
            F r = new F(out.h, out.w);
            for (int y = 0; y < r.h; y++) for (int x = 0; x < r.w; x++) r.a[y * r.w + x] = out.a[x * out.w + (out.w - 1 - y)];
            out = r;
        }
        return out;
    }

    static U8 rot90(U8 img, int k) {
        k = ((k % 4) + 4) % 4;
        U8 out = img;
        for (int t = 0; t < k; t++) {
            U8 r = new U8(out.h, out.w);
            for (int y = 0; y < r.h; y++) for (int x = 0; x < r.w; x++) r.a[y * r.w + x] = out.a[x * out.w + (out.w - 1 - y)];
            out = r;
        }
        return out;
    }

    /** Python's {@code round(x)}: nearest integer, ties to even. */
    static int round(double x) {
        return (int) Math.rint(x);
    }

    /** Python slice bounds clamped to {@code [0, n]}. */
    static int clamp(int i, int n) {
        return Math.max(0, Math.min(i, n));
    }
}
