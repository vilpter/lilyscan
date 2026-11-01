package org.lilyscan.core.vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Staves from a PDF's vector lines (port of {@code lilyscan/vector/staves.py}). */
public final class Staves {
    private Staves() {}

    /** Staff lines are at least this share of the page width. */
    static final double MIN_LENGTH = 0.15;
    /** Lines of one staff: spacing equal to within this share of the interline. */
    static final double SPACING_TOLERANCE = 0.15;

    /** A staff: the y of its five lines, top to bottom, and its horizontal extent. */
    public record VectorStaff(double[] lines, double x0, double x1) {
        public double interline() {
            return (lines[4] - lines[0]) / 4;
        }

        public double top() {
            return lines[0];
        }

        public double bottom() {
            return lines[4];
        }
    }

    private static double cy(VectorPdf.Line l) {
        return (l.y0() + l.y1()) / 2;
    }

    /** Staves on a page, top to bottom (then left to right). */
    public static List<VectorStaff> find(VectorPdf.Page page) {
        List<VectorPdf.Line> lines = new ArrayList<>();
        for (VectorPdf.Line ln : page.lines) {
            if (ln.horizontal() && Math.abs(ln.x1() - ln.x0()) >= MIN_LENGTH * page.width) lines.add(ln);
        }
        lines.sort(Comparator.comparingDouble(Staves::cy).thenComparingDouble(l -> Math.min(l.x0(), l.x1())));
        List<VectorStaff> staves = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (used.contains(i)) continue;
            List<Integer> group = new ArrayList<>(List.of(i));
            for (int j = i + 1; j < lines.size(); j++) {
                if (used.contains(j) || group.size() == 5) continue;
                if (!overlap(lines.get(group.get(0)), lines.get(j))) continue;
                double last = cy(lines.get(group.get(group.size() - 1)));
                double y = cy(lines.get(j));
                double gap = y - last;
                if (gap <= 0.1) continue; // the same line drawn twice
                if (group.size() >= 2) {
                    double spacing = cy(lines.get(group.get(1))) - cy(lines.get(group.get(0)));
                    if (Math.abs(gap - spacing) > SPACING_TOLERANCE * spacing) {
                        if (gap > spacing) break;
                        continue;
                    }
                } else if (gap > 0.05 * page.height) {
                    break;
                }
                group.add(j);
            }
            if (group.size() == 5) {
                used.addAll(group);
                double[] ys = new double[5];
                double x0 = Double.NEGATIVE_INFINITY, x1 = Double.POSITIVE_INFINITY;
                for (int k = 0; k < 5; k++) {
                    VectorPdf.Line l = lines.get(group.get(k));
                    ys[k] = cy(l);
                    x0 = Math.max(x0, Math.min(l.x0(), l.x1()));
                    x1 = Math.min(x1, Math.max(l.x0(), l.x1()));
                }
                java.util.Arrays.sort(ys);
                staves.add(new VectorStaff(ys, x0, x1));
            }
        }
        staves.sort(Comparator.comparingDouble(VectorStaff::top).thenComparingDouble(VectorStaff::x0));
        return staves;
    }

    private static boolean overlap(VectorPdf.Line a, VectorPdf.Line b) {
        double a0 = Math.min(a.x0(), a.x1()), a1 = Math.max(a.x0(), a.x1());
        double b0 = Math.min(b.x0(), b.x1()), b1 = Math.max(b.x0(), b.x1());
        return Math.min(a1, b1) - Math.max(a0, b0) > 0.5 * Math.min(a1 - a0, b1 - b0);
    }
}
