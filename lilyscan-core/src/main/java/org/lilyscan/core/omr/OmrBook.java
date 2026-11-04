package org.lilyscan.core.omr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lilyscan.core.ir.Frac;

/**
 * What Lilyscan reads from a saved Audiveris {@code .omr} project: per sheet, its systems with
 * their stacks (measures, with slots mapping time offsets to x positions), parts and staves, and
 * the interpretations with shape, grades and page bounds. Port of the dataclasses in
 * {@code lilyscan/engine/audiveris/omr.py}; produced by the engine's Audiveris-backed reader.
 */
public record OmrBook(String softwareVersion, List<OmrSheet> sheets, Map<Integer, String> logicalParts) {

    /** A pixel rectangle on the page. */
    public record Box(double x, double y, double w, double h) {
        public double cx() {
            return x + w / 2;
        }

        public Box union(Box o) {
            double x0 = Math.min(x, o.x);
            double y0 = Math.min(y, o.y);
            double x1 = Math.max(x + w, o.x + o.w);
            double y1 = Math.max(y + h, o.y + o.h);
            return new Box(x0, y0, x1 - x0, y1 - y0);
        }
    }

    /**
     * One interpretation. {@code kind} is its element name ({@code head}, {@code rest},
     * {@code head-chord}, {@code clef}, ...); {@code staff} its sheet-level staff id; {@code step}
     * (heads only) the staff steps below the middle line; {@code value} the text read (words,
     * lyric items) and {@code role} the element's own kind attribute (e.g. {@code Syllable}).
     */
    public record Inter(int id, String kind, String shape, Double grade, Double ctxGrade, Integer staff,
            Box box, Integer step, String value, String role) {

        /** The contextual grade when known, else the intrinsic grade. */
        public Double confidence() {
            return ctxGrade != null ? ctxGrade : grade;
        }
    }

    /** A measure stack: its horizontal extent and its slots (time offset in whole notes -> x). */
    public record Stack(double left, double right, Map<Frac, Double> slots) {}

    /** A staff of a system, with its vertical extent. */
    public record OmrStaff(int id, double top, double bottom) {}

    /** One system: stacks, parts (logical part id -> staves), interpretations, chord membership. */
    public record OmrSystem(List<Stack> stacks, Map<Integer, List<OmrStaff>> parts, List<Inter> inters,
            Map<Integer, Inter> chordOf, boolean startsMovement) {

        /** The interpretations on staff {@code staffId} of the given kinds that have bounds. */
        public List<Inter> onStaff(int staffId, Set<String> kinds) {
            List<Inter> out = new ArrayList<>();
            for (Inter i : inters) {
                if (i.staff() != null && i.staff() == staffId && kinds.contains(i.kind()) && i.box() != null) {
                    out.add(i);
                }
            }
            return out;
        }
    }

    /** One sheet (page). */
    public record OmrSheet(int number, int width, int height, double interline, List<OmrSystem> systems) {}
}
