package org.lilyscan.eval.synth;

import static org.bytedeco.opencv.global.opencv_core.BORDER_CONSTANT;
import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.CV_8U;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMWRITE_JPEG_QUALITY;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imdecode;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imencode;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;
import static org.bytedeco.opencv.global.opencv_imgproc.GaussianBlur;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR;
import static org.bytedeco.opencv.global.opencv_imgproc.getPerspectiveTransform;
import static org.bytedeco.opencv.global.opencv_imgproc.getRotationMatrix2D;
import static org.bytedeco.opencv.global.opencv_imgproc.remap;
import static org.bytedeco.opencv.global.opencv_imgproc.warpAffine;
import static org.bytedeco.opencv.global.opencv_imgproc.warpPerspective;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point2f;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Turns a clean engraving into a simulated flatbed scan or phone photo, deterministically from a
 * seed ({@code lilyscan/synth/degrade.py}): the same steps and parameter ranges as the Python
 * builder. The random draws are Java's ({@link SplittableRandom}, whose nearby seeds give
 * unrelated streams), not NumPy's, so the images differ from the Python ones in detail; corpora
 * built here are baselined on their own (D21).
 */
public final class Degrade {
    private Degrade() {}

    /** A grayscale image, 0 = black and 1 = white, row-major. */
    public record Image(int width, int height, float[] px) {}

    public static Image load(Path path) throws IOException {
        Mat raw = imread(path.toString(), IMREAD_GRAYSCALE);
        if (raw == null || raw.empty()) throw new IOException("cannot read image " + path);
        return fromU8(raw);
    }

    public static void savePng(Image img, Path path) throws IOException {
        if (!imwrite(path.toString(), toU8(img))) throw new IOException("cannot write " + path);
    }

    private static Image fromU8(Mat m) {
        int w = m.cols(), h = m.rows();
        float[] px = new float[w * h];
        try (UByteIndexer idx = m.createIndexer()) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = idx.get(y, x) / 255f;
            }
        }
        return new Image(w, h, px);
    }

    private static Mat toU8(Image img) {
        Mat m = new Mat(img.height(), img.width(), CV_8U);
        try (UByteIndexer idx = m.createIndexer()) {
            for (int y = 0; y < img.height(); y++) {
                for (int x = 0; x < img.width(); x++) {
                    double v = img.px()[y * img.width() + x] * 255.0 + 0.5;
                    idx.put(y, x, (int) Math.max(0, Math.min(255, Math.floor(v))));
                }
            }
        }
        return m;
    }

    private static Mat mat(Image img) {
        Mat m = new Mat(img.height(), img.width(), CV_32F);
        try (FloatIndexer idx = m.createIndexer()) {
            idx.put(0L, img.px());
        }
        return m;
    }

    private static Image image(Mat m) {
        float[] px = new float[m.rows() * m.cols()];
        try (FloatIndexer idx = m.createIndexer()) {
            idx.get(0L, px);
        }
        return new Image(m.cols(), m.rows(), px);
    }

    private static Image jpeg(Image img, int quality) {
        BytePointer buf = new BytePointer();
        if (!imencode(".jpg", toU8(img), buf, new IntPointer(IMWRITE_JPEG_QUALITY, quality))) {
            throw new IllegalStateException("JPEG encoding failed");
        }
        byte[] bytes = new byte[(int) buf.limit()];
        buf.get(bytes);
        Mat encoded = new Mat(1, bytes.length, CV_8U);
        encoded.data().put(bytes);
        Mat decoded = imdecode(encoded, IMREAD_GRAYSCALE);
        if (decoded == null || decoded.empty()) throw new IllegalStateException("JPEG round trip failed");
        return fromU8(decoded);
    }

    private static double uniform(SplittableRandom rng, double lo, double hi) {
        return lo + (hi - lo) * rng.nextDouble();
    }

    private static Image blur(Image img, double sigma) {
        Mat out = new Mat();
        GaussianBlur(mat(img), out, new Size(0, 0), sigma);
        return image(out);
    }

    private static void noiseAndClip(float[] px, SplittableRandom rng, double sigma) {
        for (int i = 0; i < px.length; i++) {
            px[i] = (float) Math.max(0, Math.min(1, px[i] + rng.nextGaussian() * sigma));
        }
    }

    /** Flatbed scan: small skew, soft focus, grey paper, noise, specks, JPEG. */
    public static Image scan(Image img, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        int w = img.width(), h = img.height();
        double angle = uniform(rng, -1.2, 1.2);
        Mat rot = getRotationMatrix2D(new Point2f(w / 2f, h / 2f), angle, 1.0);
        Mat rotated = new Mat();
        warpAffine(mat(img), rotated, rot, new Size(w, h), INTER_LINEAR, BORDER_CONSTANT, new Scalar(1.0));
        Image out = blur(image(rotated), uniform(rng, 0.4, 0.9));
        double gain = uniform(rng, 0.85, 1.0), lift = uniform(rng, 0.0, 0.08);
        float[] px = out.px();
        for (int i = 0; i < px.length; i++) px[i] = (float) (px[i] * gain + lift);
        double sigma = uniform(rng, 0.02, 0.05);
        for (int i = 0; i < px.length; i++) {
            px[i] = (float) (px[i] + rng.nextGaussian() * sigma);
            if (rng.nextDouble() < 2e-4) px[i] = 0f; // specks
            px[i] = Math.max(0f, Math.min(1f, px[i]));
        }
        return jpeg(out, 70 + rng.nextInt(21));
    }

    /** Phone photo: page curl, perspective on a darker background, uneven light, blur, JPEG. */
    public static Image photo(Image img, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        int w = img.width(), h = img.height();

        // Page curl: a vertical displacement that bows the page, strongest mid-page.
        double amp = uniform(rng, 0.004, 0.012) * h;
        Mat mapX = new Mat(h, w, CV_32F), mapY = new Mat(h, w, CV_32F);
        try (FloatIndexer ix = mapX.createIndexer(); FloatIndexer iy = mapY.createIndexer()) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double bow = amp * Math.sin(Math.PI * x / w) * (0.5 + 0.5 * y / h);
                    ix.put(y, x, x);
                    iy.put(y, x, (float) (y - bow));
                }
            }
        }
        Mat curled = new Mat();
        remap(mat(img), curled, mapX, mapY, INTER_LINEAR, BORDER_CONSTANT, new Scalar(1.0));

        // On a background with margins, then a random perspective warp.
        int margin = (int) (0.06 * Math.max(h, w));
        float bg = (float) uniform(rng, 0.2, 0.4);
        int cw = w + 2 * margin, ch = h + 2 * margin;
        float[] canvas = new float[cw * ch];
        java.util.Arrays.fill(canvas, bg);
        Image c = image(curled);
        for (int y = 0; y < h; y++) System.arraycopy(c.px(), y * w, canvas, (y + margin) * cw + margin, w);
        float[] src = {0, 0, cw, 0, cw, ch, 0, ch};
        float[] dst = new float[8];
        for (int i = 0; i < 4; i++) {
            dst[2 * i] = (float) (src[2 * i] + uniform(rng, -0.04, 0.04) * cw);
            dst[2 * i + 1] = (float) (src[2 * i + 1] + uniform(rng, -0.04, 0.04) * ch);
        }
        Mat matrix = getPerspectiveTransform(points(src), points(dst));
        Mat warped = new Mat();
        warpPerspective(mat(new Image(cw, ch, canvas)), warped, matrix, new Size(cw, ch), INTER_LINEAR,
                BORDER_CONSTANT, new Scalar(bg));
        Image out = image(warped);

        // Uneven light: a tilted plane times a soft vignette.
        double tx = uniform(rng, -0.15, 0.15), ty = uniform(rng, -0.15, 0.15), vig = uniform(rng, 0.1, 0.25);
        float[] px = out.px();
        for (int y = 0; y < ch; y++) {
            double gy = ch == 1 ? -1 : -1 + 2.0 * y / (ch - 1);
            for (int x = 0; x < cw; x++) {
                double gx = cw == 1 ? -1 : -1 + 2.0 * x / (cw - 1);
                double light = (1 + tx * gx + ty * gy) * (1 - vig * (gx * gx + gy * gy) / 2);
                px[y * cw + x] = (float) (px[y * cw + x] * light);
            }
        }
        out = blur(out, uniform(rng, 0.8, 1.6));
        noiseAndClip(out.px(), rng, uniform(rng, 0.02, 0.04));
        return jpeg(out, 60 + rng.nextInt(21));
    }

    private static Mat points(float[] xy) {
        Mat m = new Mat(4, 1, org.bytedeco.opencv.global.opencv_core.CV_32FC2);
        try (FloatIndexer idx = m.createIndexer()) {
            idx.put(0L, xy);
        }
        return m;
    }
}
