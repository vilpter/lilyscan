package org.lilyscan.core.vector;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Stage 4: symbols and lines from a born-digital PDF (port of {@code lilyscan/vector/extract.py},
 * with PDFBox in place of PyMuPDF). Coordinates are PDF points from the page's top-left corner.
 *
 * <p>What the oracle uses is reproduced as PyMuPDF reports it: the italic digits of text fonts
 * (pen origin, and a box one advance wide) and the line segments of painted paths ({@code re}
 * rectangles, and {@code l} segments including the one {@code h} adds to close a subpath).
 * Music-font glyphs are named from the font's encoding.
 */
public final class VectorPdf {
    private VectorPdf() {}

    /** Embedded music fonts recognized by name (a subset prefix like "EDSDRQ+" is ignored). */
    public static final List<String> MUSIC_FONTS = List.of("Emmentaler", "Bravura", "Petaluma", "Leland", "Finale Maestro", "Opus");

    public record Rect(double x0, double y0, double x1, double y1) {
        public double cx() {
            return (x0 + x1) / 2;
        }

        public double cy() {
            return (y0 + y1) / 2;
        }
    }

    /** A glyph: its name in its font (e.g. {@code noteheads.s2}, or {@code italic.3}), box and pen origin. */
    public record Glyph(String name, String font, Rect rect, double originX, double originY) {}

    public record Line(double x0, double y0, double x1, double y1, double width) {
        public boolean horizontal() {
            return Math.abs(y1 - y0) <= 0.1 * Math.max(1e-6, Math.abs(x1 - x0));
        }

        public boolean vertical() {
            return Math.abs(x1 - x0) <= 0.1 * Math.max(1e-6, Math.abs(y1 - y0));
        }
    }

    public static final class Page {
        public final int number;
        public final double width, height;
        public final List<Glyph> glyphs = new ArrayList<>();
        public final List<Line> lines = new ArrayList<>();
        public String musicFont;

        Page(int number, double width, double height) {
            this.number = number;
            this.width = width;
            this.height = height;
        }
    }

    static String musicFont(String basefont) {
        String name = stripSubset(basefont);
        for (String f : MUSIC_FONTS) if (name.startsWith(f)) return f;
        return null;
    }

    static String stripSubset(String basefont) {
        int plus = basefont.indexOf('+');
        return plus >= 0 ? basefont.substring(plus + 1) : basefont;
    }

    private static final class Engine extends PDFGraphicsStreamEngine {
        final Page page;
        final double left, top;
        // The path being built: segments as page-space lines, and rectangles.
        final List<Line> pending = new ArrayList<>();
        Point2D.Float current, start;

        Engine(PDPage p, Page page) {
            super(p);
            this.page = page;
            PDRectangle box = p.getCropBox();
            left = box.getLowerLeftX();
            top = box.getUpperRightY();
        }

        private double fx(double x) {
            return x - left;
        }

        private double fy(double y) {
            return top - y;
        }

        private double lineWidth() {
            return getGraphicsState().getLineWidth() * getGraphicsState().getCurrentTransformationMatrix().getScalingFactorX();
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            double x0 = Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
            double x1 = Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
            double y0 = Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
            double y1 = Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));
            double rx0 = fx(x0), rx1 = fx(x1), ry0 = fy(y1), ry1 = fy(y0);
            double w = rx1 - rx0, h = ry1 - ry0;
            // A thin filled rectangle is a line drawn as a box (LilyPond's staff lines, stems and barlines).
            if (h <= w * 0.2) {
                pending.add(new Line(rx0, ry0 + h / 2, rx1, ry0 + h / 2, h));
            } else if (w <= h * 0.2) {
                pending.add(new Line(rx0 + w / 2, ry0, rx0 + w / 2, ry1, w));
            }
            start = current = new Point2D.Float((float) p0.getX(), (float) p0.getY());
        }

        @Override
        public void moveTo(float x, float y) {
            start = current = new Point2D.Float(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            if (current != null) pending.add(new Line(fx(current.x), fy(current.y), fx(x), fy(y), Double.NaN));
            current = new Point2D.Float(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            current = new Point2D.Float(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return current == null ? new Point2D.Float() : current;
        }

        @Override
        public void closePath() {
            if (current != null && start != null && !current.equals(start)) {
                pending.add(new Line(fx(current.x), fy(current.y), fx(start.x), fy(start.y), Double.NaN));
            }
            current = start;
        }

        private void paint(boolean stroked) {
            double w = stroked ? lineWidth() : 0.0;
            for (Line l : pending) page.lines.add(Double.isNaN(l.width()) ? new Line(l.x0(), l.y0(), l.x1(), l.y1(), w) : l);
            discard();
        }

        private void discard() {
            pending.clear();
            current = start = null;
        }

        @Override public void endPath() { discard(); }
        @Override public void clip(int windingRule) {}
        @Override public void strokePath() { paint(true); }
        @Override public void fillPath(int windingRule) { paint(false); }
        @Override public void fillAndStrokePath(int windingRule) { paint(true); }
        @Override public void shadingFill(COSName shadingName) {}
        @Override public void drawImage(PDImage pdImage) {}

        // Music-font glyph names, by font, from the font's encoding (null: unmapped).
        final Map<PDFont, Boolean> mapped = new HashMap<>();

        @Override
        protected void showGlyph(Matrix trm, PDFont font, int code, Vector displacement) throws IOException {
            String name = font.getName() == null ? "" : stripSubset(font.getName());
            double ox = fx(trm.getTranslateX()), oy = fy(trm.getTranslateY());
            double adv = displacement.getX() * trm.getScalingFactorX();
            String known = musicFont(font.getName() == null ? "" : font.getName());
            if (known != null) {
                if (page.musicFont == null) page.musicFont = known;
                String glyph = font instanceof PDSimpleFont simple && simple.getEncoding() != null
                        ? simple.getEncoding().getName(code) : null;
                if (glyph != null && !glyph.equals(".notdef")) {
                    page.glyphs.add(new Glyph(glyph, name, new Rect(ox, oy - trm.getScalingFactorY(), ox + adv, oy), ox, oy));
                    return;
                }
            }
            // Italic digits in a text font: tuplet numbers, or the 8 of an octave clef.
            if (name.contains("Italic") || name.contains("Oblique")) {
                String text = font.toUnicode(code);
                if (text != null && text.codePointCount(0, text.length()) == 1 && Character.isDigit(text.codePointAt(0))) {
                    page.glyphs.add(new Glyph("italic." + text, name, new Rect(ox, oy - trm.getScalingFactorY(), ox + adv, oy), ox, oy));
                }
            }
        }
    }

    /** Every page's music glyphs and lines. */
    public static List<Page> read(Path path) throws IOException {
        List<Page> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage p = doc.getPage(i);
                PDRectangle box = p.getCropBox();
                Page page = new Page(i, box.getWidth(), box.getHeight());
                new Engine(p, page).processPage(p);
                pages.add(page);
            }
        }
        return pages;
    }
}
