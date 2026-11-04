package org.lilyscan.core.repair;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.util.Py;

/**
 * Octave-clef repair (port of {@code lilyscan/repair/clefs.py}): a tenor line read in a plain
 * treble clef (the small "8" missed) is lowered an octave.
 */
public final class Clefs {
    private Clefs() {}

    public static final String RULE = "octave-clef";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern TENOR = Pattern.compile("tenor|^t\\.?$|^ten\\.?$", FLAGS | Pattern.UNIX_LINES);
    private static final Pattern VOCAL = Pattern.compile("soprano|alto|tenor|bass|^[satb]\\.?$", FLAGS | Pattern.UNIX_LINES);
    /** A tenor line read an octave too high centres around A4; repair at or above D4. */
    static final int OCTAVE_HIGH_MEDIAN = 62;

    private static boolean isTenor(Part part, Score score) {
        for (String label : new String[] {part.name == null ? "" : part.name, part.abbreviation == null ? "" : part.abbreviation}) {
            if (TENOR.matcher(Py.strip(label)).find()) return true;
        }
        // Unnamed choir: four single-staff vocal parts, this one third.
        int vocal = 0;
        for (Part p : score.parts) {
            boolean v = false;
            for (String x : new String[] {p.name, p.abbreviation}) if (VOCAL.matcher(x == null ? "" : x).find()) v = true;
            if (v) vocal++;
        }
        return score.parts.size() == 4 && vocal >= 3 && score.parts.indexOf(part) == 2;
    }

    private static boolean plainTrebleThroughout(Staff staff) {
        List<Clef> clefs = new ArrayList<>();
        for (Measure m : staff.measures) clefs.addAll(m.clefs);
        if (clefs.isEmpty()) return false;
        for (Clef c : clefs) {
            if (!"G".equals(c.sign) || !(c.line == null || c.line == 2) || c.octaveChange != 0) return false;
        }
        return true;
    }

    public static List<Repair> octaveClefs(Score score) {
        List<Repair> repairs = new ArrayList<>();
        for (Part part : score.parts) {
            if (part.staves.size() != 1 || !isTenor(part, score)) continue;
            Staff staff = part.staves.get(0);
            List<Integer> notes = new ArrayList<>();
            for (Measure m : staff.measures) {
                for (Voice v : m.voices) for (Event e : v.events) for (Pitch p : e.pitches()) notes.add(p.midi());
            }
            double centre = notes.isEmpty() ? 0 : Py.median(notes);
            if (notes.isEmpty() || !plainTrebleThroughout(staff) || centre < OCTAVE_HIGH_MEDIAN) continue;
            for (Measure m : staff.measures) {
                List<Clef> lowered = new ArrayList<>();
                for (Clef c : m.clefs) lowered.add(new Clef("G", 2, -1, c.offset));
                m.clefs = lowered;
                for (Voice v : m.voices) {
                    for (Event e : v.events) {
                        if (e.notes.isEmpty()) continue;
                        List<String> before = new ArrayList<>();
                        for (NoteHead h : e.notes) before.add(h.pitch.label());
                        for (NoteHead h : e.notes) h.pitch = new Pitch(h.pitch.step, h.pitch.alter, h.pitch.octave - 1);
                        e.provenance.add(Repair.provenance(RULE, "pitches", before));
                    }
                }
            }
            String detail = "treble clef read without its 8; lowered the staff an octave";
            score.provenance.add(Repair.provenance(RULE, "part", part.id, "staff", staff.number, "detail", detail));
            List<String> measures = new ArrayList<>();
            for (Measure m : staff.measures) measures.add(Repair.label(m));
            String name = part.name != null && !part.name.isEmpty() ? part.name : part.id;
            repairs.add(new Repair(RULE, part.id, staff.number,
                    name + ": " + detail + " (median note was MIDI " + Py.fixed(centre, 0) + ")", measures));
        }
        return repairs;
    }
}
