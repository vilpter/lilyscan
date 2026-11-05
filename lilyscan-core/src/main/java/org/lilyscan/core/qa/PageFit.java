package org.lilyscan.core.qa;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

/**
 * How far the drawing on each page of an engraved PDF reaches past the page's edges. Staff
 * lines, bar lines and beams are paths, so a system LilyPond could not break shows up as paths
 * running off the right edge.
 */
final class PageFit {
    private PageFit() {}

    /** One page: its width and the horizontal extent of everything stroked or filled on it (pt). */
    record Extent(int page, double width, double minX, double maxX) {
        double overflow() {
            return Math.max(0, Math.max(maxX - width, -minX));
        }
    }

    private static final class Engine extends PDFGraphicsStreamEngine {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        private double pathMin = Double.POSITIVE_INFINITY;
        private double pathMax = Double.NEGATIVE_INFINITY;
        private final Point2D.Float current = new Point2D.Float();

        Engine(PDPage page) {
            super(page);
        }

        private void add(double x) {
            pathMin = Math.min(pathMin, x);
            pathMax = Math.max(pathMax, x);
        }

        private void commit() {
            minX = Math.min(minX, pathMin);
            maxX = Math.max(maxX, pathMax);
            endPath();
        }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            for (Point2D p : new Point2D[] {p0, p1, p2, p3}) add(p.getX());
        }
        @Override public void drawImage(PDImage pdImage) {}
        @Override public void clip(int windingRule) {}
        @Override public void moveTo(float x, float y) { add(x); current.setLocation(x, y); }
        @Override public void lineTo(float x, float y) { add(x); current.setLocation(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            add(x1);
            add(x2);
            add(x3);
            current.setLocation(x3, y3);
        }
        @Override public Point2D getCurrentPoint() { return current; }
        @Override public void closePath() {}
        @Override public void endPath() {
            pathMin = Double.POSITIVE_INFINITY;
            pathMax = Double.NEGATIVE_INFINITY;
        }
        @Override public void strokePath() { commit(); }
        @Override public void fillPath(int windingRule) { commit(); }
        @Override public void fillAndStrokePath(int windingRule) { commit(); }
        @Override public void shadingFill(COSName shadingName) {}
    }

    static List<Extent> extents(Path pdf) throws IOException {
        List<Extent> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                PDRectangle box = page.getMediaBox();
                Engine e = new Engine(page);
                e.processPage(page);
                if (e.maxX < e.minX) continue; // nothing drawn
                out.add(new Extent(i + 1, box.getWidth(), e.minX - box.getLowerLeftX(), e.maxX - box.getLowerLeftX()));
            }
        }
        return out;
    }
}
