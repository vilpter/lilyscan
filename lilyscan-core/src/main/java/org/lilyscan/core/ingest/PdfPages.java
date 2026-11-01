package org.lilyscan.core.ingest;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.lilyscan.core.runtime.Config;

/**
 * Stage 0 for PDFs (port of {@code lilyscan/ingest/pdf.py} and the PDF half of
 * {@code book.py}, with PDFBox in place of PyMuPDF): tell born-digital pages from scanned ones
 * and render them, born-digital pages at {@link Config#PDF_RENDER_DPI}, scans at their own
 * resolution.
 */
public final class PdfPages {
    private PdfPages() {}

    /** One page: {@code vector}, {@code raster} (a scanned image) or {@code empty}. */
    public record Page(String kind, BufferedImage pixels) {}

    /** The images a page draws and whether it paints any path. */
    private static final class Survey extends PDFGraphicsStreamEngine {
        final List<double[]> images = new ArrayList<>(); // x0, y0, x1, y1, pixel width
        boolean drawings;

        Survey(PDPage page) {
            super(page);
        }

        @Override
        public void drawImage(PDImage image) {
            Matrix m = getGraphicsState().getCurrentTransformationMatrix();
            double[] xs = new double[4], ys = new double[4];
            double[][] corners = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
            for (int k = 0; k < 4; k++) {
                Point2D.Float p = m.transformPoint((float) corners[k][0], (float) corners[k][1]);
                xs[k] = p.x;
                ys[k] = p.y;
            }
            images.add(new double[] {min(xs), min(ys), max(xs), max(ys), image.getWidth()});
        }

        private static double min(double[] a) {
            double r = a[0];
            for (double v : a) r = Math.min(r, v);
            return r;
        }

        private static double max(double[] a) {
            double r = a[0];
            for (double v : a) r = Math.max(r, v);
            return r;
        }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {}
        @Override public void clip(int windingRule) {}
        @Override public void moveTo(float x, float y) {}
        @Override public void lineTo(float x, float y) {}
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
        @Override public Point2D getCurrentPoint() { return new Point2D.Float(); }
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() { drawings = true; }
        @Override public void fillPath(int windingRule) { drawings = true; }
        @Override public void fillAndStrokePath(int windingRule) { drawings = true; }
        @Override public void shadingFill(COSName shadingName) { drawings = true; }
    }

    private static Survey survey(PDPage page) throws IOException {
        Survey s = new Survey(page);
        s.processPage(page);
        return s;
    }

    static String pageKind(PDDocument doc, int index) throws IOException {
        PDPage page = doc.getPage(index);
        PDRectangle r = page.getCropBox();
        double area = Math.abs(r.getWidth() * r.getHeight());
        Survey s = survey(page);
        for (double[] b : s.images) {
            if (area > 0 && (b[2] - b[0]) * (b[3] - b[1]) / area >= Config.PDF_RASTER_COVER) return "raster";
        }
        if (s.drawings) return "vector";
        PDFTextStripper text = new PDFTextStripper();
        text.setStartPage(index + 1);
        text.setEndPage(index + 1);
        return text.getText(doc).strip().isEmpty() ? "empty" : "vector";
    }

    /** The resolution of the page's largest image (a scanned page), 150 to 600 DPI. */
    static int nativeDpi(PDPage page) throws IOException {
        double best = 0;
        for (double[] b : survey(page).images) {
            if (b[2] > b[0] && b[4] > 0) best = Math.max(best, b[4] / ((b[2] - b[0]) / 72.0));
        }
        return (int) Math.rint(Math.max(150.0, Math.min(best == 0 ? 300.0 : best, 600.0)));
    }

    /**
     * The page in grayscale at {@code dpi}, sized as MuPDF sizes it (a partial pixel counts, as in
     * its {@code fz_round_rect}; PDFBox's own {@code renderImageWithDPI} drops it).
     */
    static BufferedImage render(PDFRenderer renderer, PDPage page, int index, int dpi) throws IOException {
        PDRectangle box = page.getCropBox();
        boolean sideways = page.getRotation() % 180 != 0;
        double w = sideways ? box.getHeight() : box.getWidth();
        double h = sideways ? box.getWidth() : box.getHeight();
        BufferedImage img = new BufferedImage(pixels(w, dpi), pixels(h, dpi),
                BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D g = img.createGraphics();
        g.setBackground(java.awt.Color.WHITE);
        g.clearRect(0, 0, img.getWidth(), img.getHeight());
        renderer.renderPageToGraphics(index, g, dpi / 72f);
        g.dispose();
        return img;
    }

    private static int pixels(double points, int dpi) {
        return (int) Math.ceil(points * dpi / 72.0 - 0.001);
    }

    /** Every non-empty page, grayscale: born-digital pages at 400 DPI, scans at their own. */
    public static List<Page> pages(java.nio.file.Path path) throws IOException {
        List<Page> out = new ArrayList<>();
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(path.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                String kind = pageKind(doc, i);
                if (kind.equals("empty")) continue;
                int dpi = kind.equals("raster") ? nativeDpi(doc.getPage(i)) : Config.PDF_RENDER_DPI;
                out.add(new Page(kind, render(renderer, doc.getPage(i), i, dpi)));
            }
        }
        return out;
    }

    /** A copy of the PDF holding only the pages {@code keep} (numbered from 1), in that order. */
    public static void select(java.nio.file.Path src, List<Integer> keep, java.nio.file.Path dst) throws IOException {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(src.toFile()); PDDocument out = new PDDocument()) {
            for (int n : keep) {
                if (n > 0 && n <= doc.getNumberOfPages()) out.importPage(doc.getPage(n - 1));
            }
            out.save(dst.toFile());
        }
    }
}
