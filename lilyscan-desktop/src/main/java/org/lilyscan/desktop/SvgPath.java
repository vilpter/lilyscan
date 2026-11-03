package org.lilyscan.desktop;

import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;

/** SVG path data ({@code d}) as a Java2D path: every command, absolute and relative. */
final class SvgPath {
    private final String d;
    private int i;

    private SvgPath(String d) {
        this.d = d;
    }

    static Path2D.Double parse(String d) {
        return new SvgPath(d == null ? "" : d).run();
    }

    private Path2D.Double run() {
        Path2D.Double p = new Path2D.Double();
        double x = 0, y = 0, startX = 0, startY = 0, ctrlX = 0, ctrlY = 0;
        char cmd = 0, prev = 0;
        boolean open = false;
        while (true) {
            skip();
            if (i >= d.length()) break;
            char c = d.charAt(i);
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            } else if (cmd == 0) {
                break; // numbers before any command: malformed
            } else if (cmd == 'M') {
                cmd = 'L'; // implicit lineto after a moveto
            } else if (cmd == 'm') {
                cmd = 'l';
            }
            boolean rel = Character.isLowerCase(cmd);
            double ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    x = ox + num();
                    y = oy + num();
                    p.moveTo(x, y);
                    startX = x;
                    startY = y;
                    open = true;
                }
                case 'L' -> {
                    x = ox + num();
                    y = oy + num();
                    lineTo(p, x, y, open);
                    open = true;
                }
                case 'H' -> {
                    x = ox + num();
                    lineTo(p, x, y, open);
                    open = true;
                }
                case 'V' -> {
                    y = oy + num();
                    lineTo(p, x, y, open);
                    open = true;
                }
                case 'C' -> {
                    double x1 = ox + num(), y1 = oy + num(), x2 = ox + num(), y2 = oy + num();
                    x = ox + num();
                    y = oy + num();
                    ensure(p, open);
                    p.curveTo(x1, y1, x2, y2, x, y);
                    ctrlX = x2;
                    ctrlY = y2;
                    open = true;
                }
                case 'S' -> {
                    boolean smooth = "CcSs".indexOf(prev) >= 0;
                    double x1 = smooth ? 2 * x - ctrlX : x, y1 = smooth ? 2 * y - ctrlY : y;
                    double x2 = ox + num(), y2 = oy + num();
                    x = ox + num();
                    y = oy + num();
                    ensure(p, open);
                    p.curveTo(x1, y1, x2, y2, x, y);
                    ctrlX = x2;
                    ctrlY = y2;
                    open = true;
                }
                case 'Q' -> {
                    double x1 = ox + num(), y1 = oy + num();
                    x = ox + num();
                    y = oy + num();
                    ensure(p, open);
                    p.quadTo(x1, y1, x, y);
                    ctrlX = x1;
                    ctrlY = y1;
                    open = true;
                }
                case 'T' -> {
                    boolean smooth = "QqTt".indexOf(prev) >= 0;
                    double x1 = smooth ? 2 * x - ctrlX : x, y1 = smooth ? 2 * y - ctrlY : y;
                    x = ox + num();
                    y = oy + num();
                    ensure(p, open);
                    p.quadTo(x1, y1, x, y);
                    ctrlX = x1;
                    ctrlY = y1;
                    open = true;
                }
                case 'A' -> {
                    double rx = Math.abs(num()), ry = Math.abs(num()), angle = num();
                    boolean large = flag(), sweep = flag();
                    double x2 = ox + num(), y2 = oy + num();
                    ensure(p, open);
                    arc(p, x, y, rx, ry, angle, large, sweep, x2, y2);
                    x = x2;
                    y = y2;
                    open = true;
                }
                case 'Z' -> {
                    if (open) p.closePath();
                    x = startX;
                    y = startY;
                    open = false;
                }
                default -> {
                    return p; // unknown command: stop, as browsers do
                }
            }
            prev = cmd;
        }
        return p;
    }

    private static void ensure(Path2D.Double p, boolean open) {
        if (!open) {
            var cp = p.getCurrentPoint();
            p.moveTo(cp == null ? 0 : cp.getX(), cp == null ? 0 : cp.getY());
        }
    }

    private static void lineTo(Path2D.Double p, double x, double y, boolean open) {
        ensure(p, open);
        p.lineTo(x, y);
    }

    /** The SVG endpoint arc as a Java2D arc (SVG 1.1 appendix F.6). */
    static void arc(Path2D.Double p, double x1, double y1, double rx, double ry, double angleDeg, boolean large,
            boolean sweep, double x2, double y2) {
        if (x1 == x2 && y1 == y2) return;
        if (rx == 0 || ry == 0) {
            p.lineTo(x2, y2);
            return;
        }
        double phi = Math.toRadians(angleDeg % 360), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x1 - x2) / 2, dy = (y1 - y2) / 2;
        double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            rx *= Math.sqrt(lambda);
            ry *= Math.sqrt(lambda);
        }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * rx * y1p / ry, cyp = -coef * ry * x1p / rx;
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2, cy = sin * cxp + cos * cyp + (y1 + y2) / 2;
        double start = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double extent = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && extent > 0) extent -= 2 * Math.PI;
        if (sweep && extent < 0) extent += 2 * Math.PI;
        // Java2D angles run counter-clockwise with y up: negate.
        Arc2D.Double a = new Arc2D.Double(-rx, -ry, 2 * rx, 2 * ry, -Math.toDegrees(start), -Math.toDegrees(extent),
                Arc2D.OPEN);
        AffineTransform t = new AffineTransform();
        t.translate(cx, cy);
        t.rotate(phi);
        p.append(t.createTransformedShape(a), true);
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double a = Math.atan2(uy * vx - ux * vy, ux * vx + uy * vy);
        return -a;
    }

    private void skip() {
        while (i < d.length() && (Character.isWhitespace(d.charAt(i)) || d.charAt(i) == ',')) i++;
    }

    private boolean flag() {
        skip();
        char c = d.charAt(i++);
        return c == '1';
    }

    private double num() {
        skip();
        int s = i;
        if (i < d.length() && (d.charAt(i) == '+' || d.charAt(i) == '-')) i++;
        boolean dot = false;
        while (i < d.length()) {
            char c = d.charAt(i);
            if (Character.isDigit(c)) {
                i++;
            } else if (c == '.' && !dot) {
                dot = true;
                i++;
            } else {
                break;
            }
        }
        if (i < d.length() && (d.charAt(i) == 'e' || d.charAt(i) == 'E')) {
            int e = i++;
            if (i < d.length() && (d.charAt(i) == '+' || d.charAt(i) == '-')) i++;
            if (i < d.length() && Character.isDigit(d.charAt(i))) {
                while (i < d.length() && Character.isDigit(d.charAt(i))) i++;
            } else {
                i = e;
            }
        }
        if (s == i) throw new IllegalArgumentException("bad path data at " + s + ": " + d);
        return Double.parseDouble(d.substring(s, i));
    }
}
