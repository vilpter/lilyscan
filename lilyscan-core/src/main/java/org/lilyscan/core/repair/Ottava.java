package org.lilyscan.core.repair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lilyscan.core.ir.BBox;
import org.lilyscan.core.ir.Direction;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Voice;

/**
 * Octave shifts with a missing end.
 *
 * <p>MusicXML writes the notes under an 8va at the pitch they sound. Audiveris does so only when
 * it links the shift's line to both its first and its last chord; when one end is missing it
 * exports only the end it found, and the notes at the pitch they are printed (an octave off).
 * This repair gives such a shift its other end (where its system starts or ends, the line
 * having been read in that system only), moves the notes under it to the pitch they sound,
 * and records each change so the region comes up in the review list.
 *
 * <p>A start whose stop is in the same system or the next one is complete (a shift continued
 * across a line break is exported as a start in one system and a stop in the next) and left as
 * it is.
 */
public final class Ottava {
    private Ottava() {}

    public static final String RULE = "ottava";

    private record Mark(int measure, Direction direction) {}

    public static List<Repair> missingEnds(Score score) {
        List<Repair> repairs = new ArrayList<>();
        for (Score.PartStaff ps : score.staves()) {
            List<Measure> measures = ps.staff().measures;
            List<Mark> marks = new ArrayList<>();
            for (int i = 0; i < measures.size(); i++) {
                List<Direction> ds = new ArrayList<>(measures.get(i).directions);
                ds.sort((a, b) -> a.offset.compareTo(b.offset));
                for (Direction d : ds) {
                    if (d.isOctaveStart() || d.isOctaveStop()) marks.add(new Mark(i, d));
                }
            }
            if (marks.isEmpty()) continue;
            int[] system = systems(measures);
            Map<Integer, Mark> open = new HashMap<>();
            List<Mark> unended = new ArrayList<>();
            List<Mark> unstarted = new ArrayList<>();
            for (Mark mk : marks) {
                int n = mk.direction().number == null ? 1 : mk.direction().number;
                Mark start = open.get(n);
                if (mk.direction().isOctaveStart()) {
                    if (start != null) unended.add(start);
                    open.put(n, mk);
                } else if (start != null && system[mk.measure()] - system[start.measure()] <= 1) {
                    open.remove(n);
                } else {
                    if (start != null) {
                        unended.add(start);
                        open.remove(n);
                    }
                    unstarted.add(mk);
                }
            }
            unended.addAll(open.values());
            unended.sort((a, b) -> a.measure() != b.measure() ? Integer.compare(a.measure(), b.measure())
                    : a.direction().offset.compareTo(b.direction().offset));
            for (Mark start : unended) {
                int last = start.measure();
                while (last + 1 < measures.size() && system[last + 1] == system[start.measure()]) last++;
                Frac end = musicEnd(measures.get(last));
                Mark next = nextStart(marks, start);
                if (next != null && system[next.measure()] == system[start.measure()]) {
                    last = next.measure();
                    end = next.direction().offset;
                }
                Direction stop = new Direction(Direction.OCTAVE_SHIFT, end);
                stop.type = "stop";
                stop.number = start.direction().number;
                stop.placement = start.direction().placement;
                measures.get(last).directions.add(stop);
                int octaves = start.direction().octaves();
                List<String> labels = shift(measures, start.measure(), start.direction().offset, last, end, octaves);
                repairs.add(new Repair(RULE, ps.part().id, ps.staff().number,
                        name(start.direction()) + " without an end: taken to end with its system (m. "
                                + Repair.label(measures.get(last)) + "); notes under it moved "
                                + (octaves > 0 ? "up " : "down ") + Math.abs(octaves) + " octave"
                                + (Math.abs(octaves) == 1 ? "" : "s") + " to the pitch they sound",
                        labels));
            }
            for (Mark stopMark : unstarted) {
                int first = stopMark.measure();
                while (first > 0 && system[first - 1] == system[stopMark.measure()]) first--;
                Direction stop = stopMark.direction();
                Direction start = new Direction(Direction.OCTAVE_SHIFT, Frac.ZERO);
                // Audiveris puts an 8va's line above the staff and an 8vb's below.
                start.type = "below".equals(stop.placement) ? "up" : "down";
                start.number = stop.number;
                start.size = 8;
                start.placement = stop.placement;
                measures.get(first).directions.add(0, start);
                int octaves = start.octaves();
                List<String> labels = shift(measures, first, Frac.ZERO, stopMark.measure(), stop.offset, octaves);
                repairs.add(new Repair(RULE, ps.part().id, ps.staff().number,
                        "end of an " + name(start) + " without its start: taken to start with its system (m. "
                                + Repair.label(measures.get(first)) + "); notes under it moved "
                                + (octaves > 0 ? "up" : "down") + " an octave to the pitch they sound",
                        labels));
            }
        }
        return repairs;
    }

    private static String name(Direction d) {
        int size = d.size == null ? 8 : d.size;
        return size + ("up".equals(d.type) ? "vb" : "va");
    }

    private static Mark nextStart(List<Mark> marks, Mark after) {
        boolean seen = false;
        for (Mark m : marks) {
            if (m == after) {
                seen = true;
            } else if (seen && m.direction().isOctaveStart()) {
                return m;
            }
        }
        return null;
    }

    /**
     * Moves every note from ({@code first}, {@code from}) up to ({@code last}, {@code to}) by
     * {@code octaves}; returns the measures changed.
     */
    private static List<String> shift(List<Measure> measures, int first, Frac from, int last, Frac to, int octaves) {
        List<String> labels = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            Measure m = measures.get(i);
            boolean changed = false;
            for (Voice v : m.voices) {
                for (Event e : v.events) {
                    if (e.notes.isEmpty()) continue;
                    if (i == first && e.offset.lt(from)) continue;
                    if (i == last && !e.offset.lt(to)) continue;
                    List<String> before = new ArrayList<>();
                    for (NoteHead h : e.notes) before.add(h.pitch.label());
                    for (NoteHead h : e.notes) {
                        h.pitch = new Pitch(h.pitch.step, h.pitch.alter, h.pitch.octave + octaves);
                    }
                    e.provenance.add(Repair.provenance(RULE, "pitches", before));
                    changed = true;
                }
            }
            if (changed) labels.add(Repair.label(m));
        }
        return labels;
    }

    /** Where the measure's music ends: the latest end of any event in it. */
    private static Frac musicEnd(Measure m) {
        Frac end = Frac.ZERO;
        for (Voice v : m.voices) {
            for (Event e : v.events) {
                if (!e.grace) end = end.max(e.offset.add(e.duration));
            }
        }
        return end;
    }

    /**
     * A system number for each measure, from the measures' page boxes: a new system starts on a
     * new page or lower on the same page. Without boxes, each measure counts as its own system,
     * so a shift with a missing end is kept to the measure it was read in.
     */
    static int[] systems(List<Measure> measures) {
        int[] out = new int[measures.size()];
        int system = 0;
        BBox prev = null;
        for (int i = 0; i < measures.size(); i++) {
            BBox b = measures.get(i).bbox;
            if (i > 0 && (b == null || prev == null || b.page != prev.page
                    || Math.abs(b.y - prev.y) > Math.max(prev.h, 1.0) / 2)) {
                system++;
            }
            out[i] = system;
            prev = b;
        }
        return out;
    }
}
