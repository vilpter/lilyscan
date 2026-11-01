package org.lilyscan.core.prepare;

/**
 * Interpolating warps computed at exact source coordinates, as OpenCV 5 (the Python build) does.
 * OpenCV 4.x, the release JavaCPP ships, quantizes source coordinates to 1/32 pixel, which moves
 * pixels by up to about 0.01 against Python's. Measured against OpenCV 5.0 on a corpus photo:
 * the rotation within 1.2e-4, the perspective warp within 5e-5, the remap within one float ulp.
 */
final class Warp {
    private Warp() {}

    /** {@code cv2.warpAffine(img, m, (w, h), flags=INTER_LINEAR, borderValue=fill)}, {@code m} source to destination. */
    static Np.F affineLinear(Np.F img, double[] m, int w, int h, float fill) {
        // Invert the 2x3 matrix (cv2.invertAffineTransform).
        double d = m[0] * m[4] - m[1] * m[3];
        d = d != 0 ? 1.0 / d : 0.0;
        double a11 = m[4] * d, a22 = m[0] * d, a12 = -m[1] * d, a21 = -m[3] * d;
        double b1 = -a11 * m[2] - a12 * m[5], b2 = -a21 * m[2] - a22 * m[5];
        Np.F out = new Np.F(w, h);
        int sw = img.w, sh = img.h;
        float[] src = img.a;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double sx = a11 * x + a12 * y + b1, sy = a21 * x + a22 * y + b2;
                double fx0 = Math.floor(sx), fy0 = Math.floor(sy);
                if (fx0 < -1 || fy0 < -1 || fx0 >= sw || fy0 >= sh) {
                    out.a[y * w + x] = fill;
                    continue;
                }
                int x0 = (int) fx0, y0 = (int) fy0;
                double fx = sx - fx0, fy = sy - fy0;
                double p00 = px(src, sw, sh, x0, y0, fill), p01 = px(src, sw, sh, x0 + 1, y0, fill);
                double p10 = px(src, sw, sh, x0, y0 + 1, fill), p11 = px(src, sw, sh, x0 + 1, y0 + 1, fill);
                double v = (p00 * (1 - fx) + p01 * fx) * (1 - fy) + (p10 * (1 - fx) + p11 * fx) * fy;
                out.a[y * w + x] = (float) v;
            }
        }
        return out;
    }

    private static double px(float[] src, int w, int h, int x, int y, float fill) {
        return x < 0 || y < 0 || x >= w || y >= h ? fill : src[y * w + x];
    }

    /** OpenCV's bicubic weights (A = -0.75) for fraction {@code x}. */
    static void cubic(double x, double[] w) {
        final double a = -0.75;
        w[0] = ((a * (x + 1) - 5 * a) * (x + 1) + 8 * a) * (x + 1) - 4 * a;
        w[1] = ((a + 2) * x - (a + 3)) * x * x + 1;
        w[2] = ((a + 2) * (1 - x) - (a + 3)) * (1 - x) * (1 - x) + 1;
        w[3] = 1 - w[0] - w[1] - w[2];
    }

    private static double cubicAt(float[] src, int sw, int sh, double sx, double sy, boolean replicate, float fill,
            double[] wx, double[] wy) {
        double fx0 = Math.floor(sx), fy0 = Math.floor(sy);
        int x0 = (int) fx0, y0 = (int) fy0;
        cubic(sx - fx0, wx);
        cubic(sy - fy0, wy);
        double v = 0;
        for (int j = 0; j < 4; j++) {
            int yy = y0 - 1 + j;
            double row = 0;
            for (int i = 0; i < 4; i++) {
                int xx = x0 - 1 + i;
                double p;
                if (replicate) {
                    p = src[Math.min(Math.max(yy, 0), sh - 1) * sw + Math.min(Math.max(xx, 0), sw - 1)];
                } else {
                    p = xx < 0 || yy < 0 || xx >= sw || yy >= sh ? fill : src[yy * sw + xx];
                }
                row += p * wx[i];
            }
            v += row * wy[j];
        }
        return v;
    }

    /** {@code cv2.warpPerspective(img, m, (w, h), flags=INTER_CUBIC, borderValue=fill)}, {@code m} source to destination (row-major 3x3). */
    static Np.F perspectiveCubic(Np.F img, double[] m, int w, int h, float fill) {
        double[] iv = invert3(m);
        Np.F out = new Np.F(w, h);
        double[] wx = new double[4], wy = new double[4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double ww = iv[6] * x + iv[7] * y + iv[8];
                ww = ww != 0 ? 1.0 / ww : 0.0;
                double sx = (iv[0] * x + iv[1] * y + iv[2]) * ww, sy = (iv[3] * x + iv[4] * y + iv[5]) * ww;
                if (sx < -2 || sy < -2 || sx >= img.w + 1 || sy >= img.h + 1) {
                    out.a[y * w + x] = fill;
                    continue;
                }
                out.a[y * w + x] = (float) cubicAt(img.a, img.w, img.h, sx, sy, false, fill, wx, wy);
            }
        }
        return out;
    }

    /** {@code cv2.remap(img, map_x, map_y, INTER_CUBIC, borderMode=BORDER_REPLICATE)}. */
    static Np.F remapCubicReplicate(Np.F img, Np.F mapX, Np.F mapY) {
        Np.F out = new Np.F(mapX.w, mapX.h);
        double[] wx = new double[4], wy = new double[4];
        for (int i = 0; i < out.a.length; i++) {
            out.a[i] = (float) cubicAt(img.a, img.w, img.h, mapX.a[i], mapY.a[i], true, 0f, wx, wy);
        }
        return out;
    }

    static double[] invert3(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], k = m[8];
        double det = a * (e * k - f * h) - b * (d * k - f * g) + c * (d * h - e * g);
        double r = det != 0 ? 1.0 / det : 0.0;
        return new double[] {(e * k - f * h) * r, (c * h - b * k) * r, (b * f - c * e) * r,
            (f * g - d * k) * r, (a * k - c * g) * r, (c * d - a * f) * r,
            (d * h - e * g) * r, (b * g - a * h) * r, (a * e - b * d) * r};
    }
}
