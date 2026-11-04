package org.lilyscan.core.omr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lilyscan.core.ir.BBox;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Ops;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.omr.OmrBook.Box;
import org.lilyscan.core.omr.OmrBook.Inter;
import org.lilyscan.core.omr.OmrBook.OmrSheet;
import org.lilyscan.core.omr.OmrBook.OmrStaff;
import org.lilyscan.core.omr.OmrBook.OmrSystem;
import org.lilyscan.core.omr.OmrBook.Stack;

/**
 * Stage 3: attach page geometry and confidence from the {@code .omr} book to the IR.
 *
 * <p>Port of the attaching half of {@code lilyscan/engine/audiveris/omr.py}. MusicXML measure
 * <i>i</i> is the <i>i</i>-th stack in sheet/system order (the measures the reader adds for a
 * multi-measure rest share its stack). An event's onset selects a slot, hence an x position;
 * the notehead on the event's staff near that x whose staff step matches the event's pitch
 * supplies the bbox and confidence.
 */
public final class Geometry {
    private Geometry() {}

    /** Search windows, in interlines. An engine's MusicXML onsets can disagree with its own slot
     *  positions after a rhythm misread, so a head at exactly the expected staff step is
     *  accepted further from the slot than a head at some other step. */
    public static final double EXACT_WINDOW = 2.5;
    public static final double FALLBACK_WINDOW = 1.0;
    public static final double GRACE_WINDOW = 2.0;

    private static final Set<String> HEADS = Set.of("head");
    private static final Set<String> RESTS = Set.of("rest");

    /** Diatonic number of the staff's middle line under {@code clef}. */
    public static int middleLineDiatonic(Clef clef) {
        int reference;
        int dflt;
        switch (clef.sign) {
            case "G" -> { reference = 32; dflt = 2; } // G4
            case "F" -> { reference = 24; dflt = 4; } // F3
            case "C" -> { reference = 28; dflt = 3; } // C4
            default -> {
                return 34; // treat percussion/none as treble
            }
        }
        int line = clef.line != null && clef.line != 0 ? clef.line : dflt;
        return reference + 2 * (3 - line) + 7 * clef.octaveChange;
    }

    /** Audiveris head 'pitch': staff steps below the middle line. */
    public static int staffStep(Pitch pitch, Clef clef) {
        return middleLineDiatonic(clef) - pitch.diatonic();
    }

    private static double slotX(Stack stack, Frac offset) {
        Frac whole = offset.divide(4);
        Double exact = stack.slots().get(whole);
        if (exact != null) {
            return exact;
        }
        if (stack.slots().isEmpty()) {
            return (stack.left() + stack.right()) / 2;
        }
        Map.Entry<Frac, Double> best = null;
        Frac bestDist = null;
        for (Map.Entry<Frac, Double> e : stack.slots().entrySet()) {
            Frac d = e.getKey().subtract(whole).abs();
            if (best == null || d.lt(bestDist)) {
                best = e;
                bestDist = d;
            }
        }
        return best.getValue();
    }

    private static double distance(Inter inter, double x) {
        return inter.box() != null ? Math.abs(inter.box().cx() - x) : Double.POSITIVE_INFINITY;
    }

    /** First element with the smallest distance to {@code x} (Python {@code min}). */
    private static Inter nearest(List<Inter> pool, double x) {
        Inter best = null;
        double bestD = 0;
        for (Inter i : pool) {
            double d = distance(i, x);
            if (best == null || d < bestD) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    /** Box, confidence, and whether every pitch matched a head at the expected step. */
    private record Located(Box box, Double confidence, boolean matched) {}

    private static Located locate(Event e, double x, List<Inter> heads, List<Inter> rests, Clef clef,
            double interline, Map<Integer, Inter> chordOf, Set<Integer> used) {
        if (Event.REST.equals(e.kind) || e.notes.isEmpty()) {
            List<Inter> found = new ArrayList<>();
            for (Inter r : rests) {
                if (!used.contains(r.id()) && distance(r, x) <= FALLBACK_WINDOW * interline) {
                    found.add(r);
                }
            }
            if (found.isEmpty()) {
                return new Located(null, null, true);
            }
            Inter best = nearest(found, x);
            used.add(best.id());
            return new Located(best.box(), best.confidence(), true);
        }

        List<Inter> free = new ArrayList<>();
        for (Inter h : heads) {
            if (h.box() != null && !used.contains(h.id())) {
                free.add(h);
            }
        }
        List<Inter> chosen = new ArrayList<>();
        boolean allMatched = true;
        for (NoteHead head : e.notes) {
            int step = staffStep(head.pitch, clef);
            List<Inter> exact = new ArrayList<>();
            List<Inter> fallback = new ArrayList<>();
            if (e.grace) {
                for (Inter h : free) {
                    if (h.box() != null && x - GRACE_WINDOW * interline <= h.box().cx() && h.box().cx() < x) {
                        if (!chosen.contains(h)) {
                            if (h.step() != null && h.step() == step) {
                                exact.add(h);
                            }
                            fallback.add(h);
                        }
                    }
                }
            } else {
                for (Inter h : free) {
                    if (chosen.contains(h)) {
                        continue;
                    }
                    double d = distance(h, x);
                    if (h.step() != null && h.step() == step && d <= EXACT_WINDOW * interline) {
                        exact.add(h);
                    }
                    if (d <= FALLBACK_WINDOW * interline) {
                        fallback.add(h);
                    }
                }
            }
            List<Inter> pool = !exact.isEmpty() ? exact : fallback;
            if (pool.isEmpty()) {
                continue;
            }
            if (exact.isEmpty()) {
                allMatched = false;
            }
            chosen.add(nearest(pool, x));
        }
        if (chosen.isEmpty()) {
            return new Located(null, null, false);
        }
        if (chosen.size() < e.notes.size()) {
            allMatched = false;
        }
        for (Inter h : chosen) {
            used.add(h.id());
        }
        Box box = chosen.get(0).box();
        for (Inter h : chosen.subList(1, chosen.size())) {
            if (h.box() != null) {
                box = box.union(h.box());
            }
        }
        Double conf = null;
        for (Inter h : chosen) {
            if (h.confidence() != null && (conf == null || h.confidence() < conf)) {
                conf = h.confidence();
            }
        }
        Inter chord = chordOf.get(chosen.get(0).id());
        if (chord != null && chord.confidence() != null && (conf == null || chord.confidence() < conf)) {
            conf = chord.confidence();
        }
        return new Located(box, conf, allMatched);
    }

    private static Located measureRest(Stack stack, List<Inter> rests, Set<Integer> used) {
        double centre = (stack.left() + stack.right()) / 2;
        List<Inter> inside = new ArrayList<>();
        for (Inter r : rests) {
            if (r.box() != null && !used.contains(r.id()) && stack.left() <= r.box().cx()
                    && r.box().cx() <= stack.right()) {
                inside.add(r);
            }
        }
        if (inside.isEmpty()) {
            return new Located(null, null, true);
        }
        Inter best = nearest(inside, centre);
        used.add(best.id());
        return new Located(best.box(), best.confidence(), true);
    }

    private record Placement(int page, OmrSheet sheet, OmrSystem system, Stack stack) {}

    /**
     * Fill {@code bbox} and {@code confidence} on events and measures from the {@code .omr} book.
     *
     * <p>{@code score} is one movement (one exported MusicXML file) whose measure 0 is the book's
     * measure {@code firstMeasure}: its parts are numbered as that movement's parts are in the
     * book. Pages are numbered 0-based in sheet order. Events whose pitch disagrees with the
     * notehead found at that position get their confidence halved.
     */
    public static AttachStats attach(Score score, OmrBook book, int firstMeasure) {
        AttachStats stats = new AttachStats();
        List<Placement> placements = new ArrayList<>();
        for (int page = 0; page < book.sheets().size(); page++) {
            OmrSheet sheet = book.sheets().get(page);
            for (OmrSystem system : sheet.systems()) {
                for (Stack stack : system.stacks()) {
                    placements.add(new Placement(page, sheet, system, stack));
                }
            }
        }
        for (int pi = 0; pi < score.parts.size(); pi++) {
            Part part = score.parts.get(pi);
            int partIndex = pi + 1;
            for (Staff staff : part.staves) {
                Clef clef = new Clef("G", 2, 0, Frac.ZERO);
                List<Integer> written = MusicXml.writtenMeasures(staff.measures);
                for (int k = 0; k < staff.measures.size(); k++) {
                    Measure m = staff.measures.get(k);
                    boolean added = MusicXml.addedRest(m);
                    stats.measures += 1;
                    for (Voice v : m.voices) {
                        stats.events += v.events.size();
                    }
                    for (Clef c : m.clefs) {
                        if (c.offset.isZero()) {
                            clef = c; // the last clef at the start of the measure
                        }
                    }
                    int w = written.get(k) + firstMeasure;
                    if (w >= placements.size()) {
                        continue;
                    }
                    Placement pl = placements.get(w);
                    List<OmrStaff> omrStaves = pl.system().parts().getOrDefault(partIndex, List.of());
                    if (staff.number > omrStaves.size()) {
                        String label = part.id + "/" + staff.number;
                        if (!stats.unmappedStaves.contains(label)) {
                            stats.unmappedStaves.add(label);
                        }
                        continue;
                    }
                    OmrStaff omrStaff = omrStaves.get(staff.number - 1);
                    m.bbox = new BBox(pl.page(), pl.stack().left(), omrStaff.top(),
                            pl.stack().right() - pl.stack().left(), omrStaff.bottom() - omrStaff.top());
                    stats.measuresLocated += 1;
                    if (added) {
                        continue; // drawn once, as the multi-measure rest
                    }
                    List<Inter> heads = pl.system().onStaff(omrStaff.id(), HEADS);
                    List<Inter> rests = pl.system().onStaff(omrStaff.id(), RESTS);
                    Set<Integer> used = new HashSet<>();
                    for (Voice v : m.voices) {
                        for (Event e : v.events) {
                            stats.mappable += 1;
                            Clef active = clef;
                            for (Clef c : m.clefs) {
                                if (c.offset.signum() > 0 && c.offset.le(e.offset)) {
                                    active = c;
                                }
                            }
                            Located loc = e.measureRest
                                    ? measureRest(pl.stack(), rests, used)
                                    : locate(e, slotX(pl.stack(), e.offset), heads, rests, active,
                                            pl.sheet().interline(), pl.system().chordOf(), used);
                            if (loc.box() == null) {
                                continue;
                            }
                            stats.located += 1;
                            e.bbox = new BBox(pl.page(), loc.box().x(), loc.box().y(), loc.box().w(),
                                    loc.box().h());
                            if (loc.confidence() != null) {
                                e.confidence = loc.matched() ? loc.confidence() : loc.confidence() / 2;
                            }
                            if (!loc.matched()) {
                                stats.pitchMismatch += 1;
                            }
                        }
                    }
                    for (Clef c : m.clefs) {
                        if (c.offset.signum() > 0) {
                            clef = c; // the last mid-measure clef carries on
                        }
                    }
                }
            }
        }
        return stats;
    }

    /** The merged score and the combined stats (null without a book). */
    public record Attached(Score score, AttachStats stats) {}

    /**
     * The engine's movements merged into one score, geometry attached to each first. Each
     * movement numbers its parts afresh, and the merge may place them differently, so boxes are
     * attached while every part still has its movement's numbering.
     */
    public static Attached attachMovements(List<Score> movements, OmrBook book) {
        if (book == null) {
            return new Attached(Ops.mergeScores(movements), null);
        }
        AttachStats total = new AttachStats();
        int first = 0;
        for (Score movement : movements) {
            AttachStats s = attach(movement, book, first);
            total.events += s.events;
            total.mappable += s.mappable;
            total.located += s.located;
            total.pitchMismatch += s.pitchMismatch;
            total.measures += s.measures;
            total.measuresLocated += s.measuresLocated;
            for (String x : s.unmappedStaves) {
                if (!total.unmappedStaves.contains(x)) {
                    total.unmappedStaves.add(x);
                }
            }
            int longest = 0;
            for (Score.PartStaff ps : movement.staves()) {
                longest = Math.max(longest, new HashSet<>(MusicXml.writtenMeasures(ps.staff().measures)).size());
            }
            first += longest;
        }
        return new Attached(Ops.mergeScores(movements), total);
    }
}
