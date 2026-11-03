package org.lilyscan.desktop;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * The point-and-click links of a LilyPond SVG page: each {@code <a xlink:href="textedit://...">}
 * and the box, in the page's user units, of what it wraps. The web UI makes those elements
 * clickable in the browser; the desktop app draws the page with jsvg and hit-tests these boxes.
 */
final class LilySvg {
    private LilySvg() {}

    record Link(String href, Rectangle2D box) {}

    /** The page's viewBox (user units) and its links in document order. */
    record Page(Rectangle2D viewBox, List<Link> links) {}

    private static final String XLINK = "http://www.w3.org/1999/xlink";
    private static final Pattern TRANSFORM = Pattern.compile("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)");
    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    static Page parse(byte[] svg) throws IOException {
        Element root;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            root = f.newDocumentBuilder().parse(new ByteArrayInputStream(svg)).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("not an SVG page: " + e.getMessage(), e);
        }
        double[] vb = numbers(root.getAttribute("viewBox"));
        Rectangle2D viewBox = vb.length == 4 ? new Rectangle2D.Double(vb[0], vb[1], vb[2], vb[3])
                : new Rectangle2D.Double(0, 0, length(root.getAttribute("width")), length(root.getAttribute("height")));
        List<Link> links = new ArrayList<>();
        walk(root, new AffineTransform(), links, null);
        return new Page(viewBox, links);
    }

    /** Collects links; {@code box} is the open link's bounds, or {@code null} outside a link. */
    private static void walk(Element e, AffineTransform parent, List<Link> links, Rectangle2D[] box) {
        AffineTransform t = new AffineTransform(parent);
        t.concatenate(transform(e.getAttribute("transform")));
        String name = e.getLocalName() == null ? e.getTagName() : e.getLocalName();
        Rectangle2D[] inner = box;
        String href = null;
        if (name.equals("a")) {
            href = e.getAttributeNS(XLINK, "href");
            if (href.isEmpty()) href = e.getAttribute("href");
            inner = new Rectangle2D[1];
        }
        if (inner != null) {
            Shape s = shape(e, name);
            if (s != null) {
                Rectangle2D b = t.createTransformedShape(s).getBounds2D();
                double stroke = numberOr(e.getAttribute("stroke-width"), 0) * scale(t) / 2;
                if (stroke > 0) b.setRect(b.getX() - stroke, b.getY() - stroke, b.getWidth() + 2 * stroke, b.getHeight() + 2 * stroke);
                if (inner[0] == null) inner[0] = b; else inner[0].add(b);
            }
        }
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element child) walk(child, t, links, inner);
        }
        if (href != null) {
            if (inner[0] != null && !href.isEmpty()) links.add(new Link(href, inner[0]));
            if (box != null && inner[0] != null) {
                if (box[0] == null) box[0] = inner[0].getBounds2D(); else box[0].add(inner[0]);
            }
        }
    }

    private static double scale(AffineTransform t) {
        return Math.sqrt(Math.abs(t.getDeterminant()));
    }

    private static Shape shape(Element e, String name) {
        switch (name) {
            case "path":
                return SvgPath.parse(e.getAttribute("d"));
            case "rect":
                return new Rectangle2D.Double(num(e, "x"), num(e, "y"), num(e, "width"), num(e, "height"));
            case "line":
                return new Line2D.Double(num(e, "x1"), num(e, "y1"), num(e, "x2"), num(e, "y2"));
            case "polygon", "polyline": {
                double[] p = numbers(e.getAttribute("points"));
                if (p.length < 2) return null;
                Path2D.Double path = new Path2D.Double();
                path.moveTo(p[0], p[1]);
                for (int k = 2; k + 1 < p.length; k += 2) path.lineTo(p[k], p[k + 1]);
                return path;
            }
            case "circle": {
                double r = num(e, "r");
                return new Ellipse2D.Double(num(e, "cx") - r, num(e, "cy") - r, 2 * r, 2 * r);
            }
            case "ellipse": {
                double rx = num(e, "rx"), ry = num(e, "ry");
                return new Ellipse2D.Double(num(e, "cx") - rx, num(e, "cy") - ry, 2 * rx, 2 * ry);
            }
            case "text": {
                // An estimate: the text's em box from its baseline, half an em per character.
                double size = numberOr(e.getAttribute("font-size"), 1);
                double width = Math.max(1, e.getTextContent().strip().length()) * size * 0.5;
                return new Rectangle2D.Double(num(e, "x"), num(e, "y") - size * 0.8, width, size);
            }
            default:
                return null;
        }
    }

    static AffineTransform transform(String spec) {
        AffineTransform t = new AffineTransform();
        if (spec == null || spec.isBlank()) return t;
        Matcher m = TRANSFORM.matcher(spec);
        while (m.find()) {
            double[] a = numbers(m.group(2));
            switch (m.group(1)) {
                case "matrix" -> {
                    if (a.length == 6) t.concatenate(new AffineTransform(a[0], a[1], a[2], a[3], a[4], a[5]));
                }
                case "translate" -> t.translate(a.length > 0 ? a[0] : 0, a.length > 1 ? a[1] : 0);
                case "scale" -> t.scale(a.length > 0 ? a[0] : 1, a.length > 1 ? a[1] : (a.length > 0 ? a[0] : 1));
                case "rotate" -> {
                    if (a.length >= 3) t.rotate(Math.toRadians(a[0]), a[1], a[2]);
                    else if (a.length == 1) t.rotate(Math.toRadians(a[0]));
                }
                case "skewX" -> t.shear(Math.tan(Math.toRadians(a.length > 0 ? a[0] : 0)), 0);
                case "skewY" -> t.shear(0, Math.tan(Math.toRadians(a.length > 0 ? a[0] : 0)));
                default -> { }
            }
        }
        return t;
    }

    private static double num(Element e, String attr) {
        return numberOr(e.getAttribute(attr), 0);
    }

    private static double numberOr(String s, double fallback) {
        double[] n = numbers(s);
        return n.length > 0 ? n[0] : fallback;
    }

    static double[] numbers(String s) {
        if (s == null) return new double[0];
        Matcher m = NUMBER.matcher(s);
        List<Double> out = new ArrayList<>();
        while (m.find()) out.add(Double.parseDouble(m.group()));
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** A length in mm, as LilyPond writes the page size; other units as plain numbers. */
    private static double length(String s) {
        return numberOr(s, 0);
    }
}
