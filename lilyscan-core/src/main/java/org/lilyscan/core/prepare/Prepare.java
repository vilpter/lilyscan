package org.lilyscan.core.prepare;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lilyscan.core.runtime.Config;
import org.lilyscan.core.util.Py;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_AREA;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_CUBIC;

/**
 * Stage 1 (port of {@code lilyscan/prepare/__init__.py}): turn a photo or scan into the flat,
 * scan-like page Audiveris expects. Find the page and correct the perspective, flatten the
 * light, turn the staff lines level, rescale to a readable interline, straighten curled lines,
 * turn the page right way up, and clean photos; then a quality gate.
 */
public final class Prepare {
    private Prepare() {}

    /** Staff lines already straighter than this (RMS displacement, interlines) are left alone. */
    static final double FLAT_ENOUGH = 0.1;

    /** What was done to one page (Python {@code PrepareReport}). */
    public static final class Report {
        public String source;
        public boolean pageFound;
        public int[] inputSize;
        public int[] outputSize = {0, 0};
        public Double interline;
        public double rescaled = 1.0;
        public Double curvatureBefore;
        public Double curvatureAfter;
        public boolean dewarped;
        public double rotated = 0.0;
        public String cleaned;
        public final List<String> warnings = new ArrayList<>();

        /** The quality gate: nothing found that makes the page hard to read. */
        public boolean passed() {
            return warnings.isEmpty();
        }

        /** {@code PrepareReport.to_dict()}: the fields in order, then {@code passed}. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", source);
            m.put("page_found", pageFound);
            m.put("input_size", List.of(inputSize[0], inputSize[1]));
            m.put("output_size", List.of(outputSize[0], outputSize[1]));
            m.put("interline", interline);
            m.put("rescaled", rescaled);
            m.put("curvature_before", curvatureBefore);
            m.put("curvature_after", curvatureAfter);
            m.put("dewarped", dewarped);
            m.put("rotated", rotated);
            m.put("cleaned", cleaned);
            m.put("warnings", new ArrayList<>(warnings));
            m.put("passed", passed());
            return m;
        }
    }

    /** A prepared page: 8-bit grayscale pixels and the report. */
    public record Result(BufferedImage image, Report report) {}

    /** Prepares one 8-bit grayscale page with the default options. */
    public static Result preparePage(BufferedImage gray8, String source) {
        return preparePage(gray8, source, true, true, "auto");
    }

    /**
     * Prepares one page. {@code light} and {@code straighten} switch the light flattening and
     * the dewarping; {@code clean} is {@code sharpen}, {@code binarize}, null, or {@code auto}
     * (sharpen photos, leave scans).
     */
    public static Result preparePage(BufferedImage gray8, String source, boolean light, boolean straighten, String clean) {
        Np.F gray = fromImage(gray8);
        Object[] out = preparePage(gray, source, light, straighten, clean);
        return new Result(toImage((Np.F) out[0]), (Report) out[1]);
    }

    /**
     * Writes the prepared page for the image {@code src} to {@code dst} (PNG) and returns the
     * report (Python {@code prepare_image}); images are read and written with OpenCV, as
     * {@code cv2} does.
     */
    public static Report prepareImage(java.nio.file.Path src, java.nio.file.Path dst) {
        org.bytedeco.opencv.opencv_core.Mat raw = org.bytedeco.opencv.global.opencv_imgcodecs.imread(src.toString(),
                org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE);
        if (raw == null || raw.empty()) throw new IllegalArgumentException("cannot read image " + src);
        Np.U8 u = Np.u8(raw);
        Np.F gray = new Np.F(u.w, u.h);
        for (int i = 0; i < gray.a.length; i++) gray.a[i] = (float) (u.a[i] & 255) / 255f;
        Object[] out = preparePage(gray, src.getFileName().toString(), true, true, "auto");
        try {
            java.nio.file.Files.createDirectories(dst.getParent());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (!org.bytedeco.opencv.global.opencv_imgcodecs.imwrite(dst.toString(), Np.mat(Np.toU8((Np.F) out[0], 0.5f)))) {
            throw new java.io.UncheckedIOException(new java.io.IOException("cannot write " + dst));
        }
        return (Report) out[1];
    }

    static Np.F fromImage(BufferedImage img) {
        if (img.getType() != BufferedImage.TYPE_BYTE_GRAY) throw new IllegalArgumentException("expected an 8-bit gray image");
        byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        Np.F f = new Np.F(img.getWidth(), img.getHeight());
        int stride = img.getRaster().getSampleModel() instanceof java.awt.image.ComponentSampleModel cs ? cs.getScanlineStride() : img.getWidth();
        for (int y = 0; y < f.h; y++) {
            for (int x = 0; x < f.w; x++) f.a[y * f.w + x] = (float) (px[y * stride + x] & 255) / 255f;
        }
        return f;
    }

    /** {@code np.clip(gray * 255 + 0.5, 0, 255).astype(np.uint8)} as an image. */
    static BufferedImage toImage(Np.F gray) {
        Np.U8 u = Np.toU8(gray, 0.5f);
        BufferedImage img = new BufferedImage(gray.w, gray.h, BufferedImage.TYPE_BYTE_GRAY);
        img.getRaster().setDataElements(0, 0, gray.w, gray.h, u.a);
        return img;
    }

    static Object[] preparePage(Np.F gray, String source, boolean light, boolean straighten, String clean) {
        Report report = new Report();
        report.source = source;
        report.inputSize = new int[] {gray.w, gray.h};
        float[][] corners = PageImage.findPage(gray);
        report.pageFound = corners != null;
        if (corners != null) gray = PageImage.warpPage(gray, corners);
        if (light) gray = PageImage.flattenLight(gray);
        // Staff lines across the page; whether it is upside down is settled once they are straight.
        Object[] levelled = PageImage.levelStaves(gray);
        gray = (Np.F) levelled[0];
        report.rotated = Py.round((double) levelled[1], 1);
        Np.U8 mask = PageImage.inkMask(gray);

        StaffWarp.Scale scale = StaffWarp.measureScale(mask);
        if (scale == null) {
            report.warnings.add("no staff lines found");
            return finish(report, gray);
        }
        double interline = scale.interline();
        double low = Config.PREPARE_INTERLINE_MIN, high = Config.PREPARE_INTERLINE_MAX;
        if (!(low <= interline && interline <= high)) {
            double factor = (low + high) / 2 / interline;
            int w = Np.round(gray.w * factor), h = Np.round(gray.h * factor);
            gray = PageImage.resize(gray, w, h, factor > 1 ? INTER_CUBIC : INTER_AREA);
            mask = PageImage.inkMask(gray);
            interline *= factor;
            report.rescaled = Py.round(factor, 4);
        }
        report.interline = Py.round(interline, 2);

        List<double[]> samples = StaffWarp.displacementSamples(mask, interline);
        double before = StaffWarp.curvature(samples) / interline;
        report.curvatureBefore = Py.round(before, 4);
        double after = before;
        if (straighten && before > FLAT_ENOUGH) {
            StaffWarp.Field field = StaffWarp.fitField(samples, gray.w, gray.h);
            if (field != null) {
                gray = StaffWarp.dewarp(gray, field);
                report.dewarped = true;
                after = StaffWarp.curvature(StaffWarp.displacementSamples(PageImage.inkMask(gray), interline)) / interline;
            }
        }
        report.curvatureAfter = Py.round(after, 4);
        double r = Math.abs(report.rotated);
        if (PageImage.upsideDown(gray, 45 < r && r < 135)) {
            gray = Np.rot90(gray, 2);
            report.rotated = Py.round(((report.rotated + 360) % 360 + 360) % 360 - 180, 1);
        }
        if ("auto".equals(clean)) clean = report.pageFound ? "sharpen" : null;
        report.cleaned = clean;
        if ("sharpen".equals(clean) || "binarize".equals(clean)) {
            gray = PageImage.sharpen(gray, interline);
            if ("binarize".equals(clean)) gray = PageImage.binarize(gray, interline);
        }
        if (scale.interline() < Config.PREPARE_MIN_INTERLINE) {
            report.warnings.add("staff lines only " + Py.fixed(scale.interline(), 0)
                    + " px apart; photograph closer or scan at a higher resolution");
        }
        if (after > Config.PREPARE_MAX_CURVATURE) {
            report.warnings.add("staff lines still curved by " + Py.fixed(after, 2) + " staff spaces; flatten the page and reshoot");
        }
        return finish(report, gray);
    }

    private static Object[] finish(Report report, Np.F gray) {
        report.outputSize = new int[] {gray.w, gray.h};
        return new Object[] {gray, report};
    }
}
