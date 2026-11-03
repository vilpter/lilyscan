package org.lilyscan.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The engraved pane's SVG handling on a LilyPond 2.26 page (a three-measure violin part,
 * {@code violin.svg}): links found with sensible boxes, path data parsed, and the page drawn by
 * jsvg with ink inside every link box.
 */
class SvgTest {

    static byte[] fixture() throws Exception {
        try (InputStream in = SvgTest.class.getResourceAsStream("violin.svg")) {
            return in.readAllBytes();
        }
    }

    @Test
    void linksOfALilyPondPage() throws Exception {
        LilySvg.Page page = LilySvg.parse(fixture());
        assertEquals(new Rectangle2D.Double(0, 0, 59.7508, 84.2202), page.viewBox());
        // 13 <a> elements, one of them empty (the tuplet bracket's second link): nothing to click.
        assertEquals(12, page.links().size());
        for (LilySvg.Link l : page.links()) {
            assertTrue(l.href().startsWith("textedit:///"), l.href());
            Rectangle2D b = l.box();
            assertTrue(page.viewBox().contains(b), l + " outside the page");
            assertTrue(b.getWidth() > 0.2 && b.getWidth() < 10 && b.getHeight() > 0.2 && b.getHeight() < 15, l.toString());
        }
        // Lines of the part file, as the review's measure map uses them.
        List<String> targets = page.links().stream().map(l -> Labels.linkTarget(l.href())).distinct().toList();
        assertTrue(targets.containsAll(List.of("parts/violin.ly:3", "parts/violin.ly:4", "parts/violin.ly:5")), targets.toString());
        // Measure 1's notes come before measure 2's, left to right.
        double m1 = page.links().stream().filter(l -> l.href().contains("violin.ly:3:")).mapToDouble(l -> l.box().getCenterX()).max().orElseThrow();
        double m2 = page.links().stream().filter(l -> l.href().contains("violin.ly:4:")).mapToDouble(l -> l.box().getCenterX()).min().orElseThrow();
        assertTrue(m1 < m2);
    }

    @Test
    void jsvgDrawsInkInsideTheLinks() throws Exception {
        byte[] svg = fixture();
        SVGDocument doc = new SVGLoader().load(new ByteArrayInputStream(svg), URI.create("file:/violin.svg"),
                LoaderContext.createDefault());
        assertNotNull(doc);
        LilySvg.Page page = LilySvg.parse(svg);
        double scale = 10; // pixels per user unit
        int w = (int) Math.ceil(page.viewBox().getWidth() * scale), h = (int) Math.ceil(page.viewBox().getHeight() * scale);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(java.awt.Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        doc.render(null, g, new ViewBox(0, 0, w, h));
        g.dispose();
        for (LilySvg.Link l : page.links()) {
            Rectangle2D b = l.box();
            int dark = 0;
            for (int y = (int) (b.getMinY() * scale); y < b.getMaxY() * scale; y++) {
                for (int x = (int) (b.getMinX() * scale); x < b.getMaxX() * scale; x++) {
                    if ((img.getRGB(x, y) & 0xff) < 128) dark++;
                }
            }
            assertTrue(dark > 0, "no ink inside " + l);
        }
    }

    @Test
    void pathData() {
        Path2D.Double p = SvgPath.parse("M10 10 h5 v5 H10 z m2 2 l1 1");
        assertEquals(new Rectangle2D.Double(10, 10, 5, 5), p.getBounds2D());
        // A half circle from (0,0) to (10,0) through (5,5) (y down: sweep 0 bulges downwards? no:
        // sweep 1 goes clockwise on screen, so from left to right it passes below the chord).
        Rectangle2D b = SvgPath.parse("M0 0 A5 5 0 0 1 10 0").getBounds2D();
        assertEquals(0, b.getMinX(), 1e-6);
        assertEquals(10, b.getMaxX(), 1e-6);
        assertEquals(-5, b.getMinY(), 1e-6);
        assertEquals(0, b.getMaxY(), 1e-6);
        Rectangle2D b2 = SvgPath.parse("M0 0 A5 5 0 0 0 10 0").getBounds2D();
        assertEquals(5, b2.getMaxY(), 1e-6);
        // Smooth curves, numbers run together, exponents.
        Path2D.Double s = SvgPath.parse("M0,0c1-1 2-1 3,0s2,1 3,0 1e1-0.5.5.5");
        assertTrue(s.getBounds2D().getMaxX() >= 6);
    }

    @Test
    void transforms() {
        var t = LilySvg.transform("translate(10, 5) scale(0.5, -0.5)");
        var p = t.transform(new java.awt.geom.Point2D.Double(2, 2), null);
        assertEquals(11, p.getX(), 1e-9);
        assertEquals(4, p.getY(), 1e-9);
    }
}
