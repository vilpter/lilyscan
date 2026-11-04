package org.lilyscan.core.repair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.KeySignature;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.omr.OmrBook;

/**
 * Key-signature repair (port of {@code lilyscan/repair/keys.py}): within each stretch of a
 * piece between real key changes, the key read on most systems wins, and the notes the misread
 * key altered follow. Where no staff changes key, the staves vote together.
 */
public final class Keys {
    private Keys() {}

    public static final String RULE = "key-signature";
    private static final String SHARPS = "FCGDAEB";
    private static final String FLATS = "BEADGCF";
    private static final Set<String> DOUBLE_BARS = Set.of("light-light", "light-heavy", "heavy-light", "heavy-heavy");
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern TRANSPOSING = Pattern.compile(
            "clar|\\bcl\\b|trump|\\btpt|\\btr\\b|horn|\\bhn\\b|\\bcor\\b|sax|cornet|flug|\\bin [a-g](?:b|#|s)?\\b", FLAGS);
    private static final Pattern CONCERT = Pattern.compile(
            "viol|vln|vla|cell|\\bvc\\b|bass|flute|\\bfl\\b|oboe|\\bob\\b|bassoon|\\bbsn\\b|"
                    + "piano|pno|organ|harp|guitar|recorder|trombone|\\btbn\\b|tuba", FLAGS);

    /** Whether a staff reading another key than the rest of the piece could be a transposing instrument. */
    public static boolean mayTranspose(Part part, Staff staff) {
        if (part.transposeSemitones != null && part.transposeSemitones != 0) return true;
        List<String> ns = new ArrayList<>();
        for (String n : new String[] {part.name, part.abbreviation}) if (n != null && !n.isEmpty() && !n.equals("Voice")) ns.add(n);
        String names = String.join(" ", ns);
        if (TRANSPOSING.matcher(names).find()) return true;
        if (CONCERT.matcher(names).find()) return false;
        for (Measure m : staff.measures) for (Clef c : m.clefs) if ("C".equals(c.sign) || "F".equals(c.sign)) return false;
        return true;
    }

    /** How a key signature alters a step: +1 sharp, -1 flat, 0 neither. */
    public static int keyAlter(int fifths, String step) {
        if (fifths > 0 && SHARPS.substring(0, Math.min(fifths, 7)).contains(step)) return 1;
        if (fifths < 0 && FLATS.substring(0, Math.min(-fifths, 7)).contains(step)) return -1;
        return 0;
    }

    /** Measures read in one key: a whole system, or the part of one after a key change. */
    static final class Segment {
        final List<Measure> measures = new ArrayList<>();
        final int fifths;
        final boolean systemStart;

        Segment(Measure m, int fifths, boolean systemStart) {
            measures.add(m);
            this.fifths = fifths;
            this.systemStart = systemStart;
        }
    }

    record Layout(List<Integer> starts, Set<Integer> movements) {}

    /** Engine measure indices (stacks) where systems start, and those where pieces start. */
    static Layout layout(OmrBook book) {
        List<Integer> starts = new ArrayList<>();
        Set<Integer> movements = new LinkedHashSet<>();
        int index = 0;
        for (OmrBook.OmrSheet sheet : book.sheets()) {
            for (OmrBook.OmrSystem system : sheet.systems()) {
                if (system.stacks().isEmpty()) continue;
                starts.add(index);
                if (system.startsMovement() || index == 0) movements.add(index);
                index += system.stacks().size();
            }
        }
        return new Layout(starts, movements);
    }

    /** The staff's segments grouped into stretches that should share one key. */
    static List<List<Segment>> stretches(Staff staff, List<Integer> starts, Set<Integer> movements) {
        Set<Integer> systemStart = new HashSet<>(starts);
        List<List<Segment>> stretches = new ArrayList<>();
        int fifths = 0;
        Measure previous = null;
        List<Integer> written = MusicXml.writtenMeasures(staff.measures);
        for (int i = 0; i < staff.measures.size(); i++) {
            Measure m = staff.measures.get(i);
            int w = written.get(i);
            boolean changed = m.key != null && m.key.fifths != fifths;
            if (m.key != null) fifths = m.key.fifths;
            boolean first = !MusicXml.addedRest(m); // a rest added after a multi-measure rest starts nothing
            boolean newPiece = (first && movements.contains(w)) || previous == null;
            boolean atSystem = first && systemStart.contains(w);
            if (newPiece) {
                List<Segment> st = new ArrayList<>();
                st.add(new Segment(m, fifths, true));
                stretches.add(st);
            } else if (atSystem || changed) {
                boolean doubleBar = (previous.rightBarline != null && DOUBLE_BARS.contains(previous.rightBarline.style))
                        || (m.leftBarline != null && DOUBLE_BARS.contains(m.leftBarline.style));
                Segment segment = new Segment(m, fifths, atSystem);
                // A change in mid-system, or after a double bar, is printed as a change.
                if (changed && (!atSystem || doubleBar)) {
                    List<Segment> st = new ArrayList<>();
                    st.add(segment);
                    stretches.add(st);
                } else {
                    stretches.get(stretches.size() - 1).add(segment);
                }
            } else {
                List<Segment> last = stretches.get(stretches.size() - 1);
                last.get(last.size() - 1).measures.add(m);
            }
            previous = m;
        }
        return stretches;
    }

    /** The key read at the start of each system of a stretch. */
    static List<Integer> readings(List<Segment> stretch) {
        List<Integer> out = new ArrayList<>();
        for (Segment s : stretch) if (s.systemStart) out.add(s.fifths);
        return out;
    }

    /** The key read most often, then the one with more accidentals; null on a draw between keys with as many. */
    static Integer vote(List<Integer> readings) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int r : readings) counts.merge(r, 1, Integer::sum);
        List<Map.Entry<Integer, Integer>> ranked = new ArrayList<>(counts.entrySet());
        // sorted(key=(count, abs(key)), reverse=True): descending, ties in first-seen order (stable)
        ranked.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : Integer.compare(Math.abs(b.getKey()), Math.abs(a.getKey()));
        });
        if (ranked.isEmpty()) return null;
        var best = ranked.get(0);
        if (ranked.size() > 1 && ranked.get(1).getValue().equals(best.getValue())
                && Math.abs(ranked.get(1).getKey()) == Math.abs(best.getKey())) {
            return null;
        }
        return best.getKey();
    }

    /** The key each stretch of each staff should be in. */
    static Map<List<Segment>, Integer> targets(List<List<List<Segment>>> stretches, List<Integer> pieces, List<Boolean> keeps) {
        java.util.function.Function<List<Segment>, Integer> piece = stretch -> {
            int start = stretch.get(0).measures.get(0).index;
            int best = 0;
            boolean any = false;
            for (int p : pieces) {
                if (p <= start && (!any || p > best)) {
                    best = p;
                    any = true;
                }
            }
            return any ? best : 0;
        };
        Map<List<Segment>, Integer> targets = new IdentityHashMap<>();
        Set<Integer> ps = new LinkedHashSet<>();
        for (List<List<Segment>> staff : stretches) for (List<Segment> st : staff) ps.add(piece.apply(st));
        for (int p : ps) {
            List<Boolean> inKeep = new ArrayList<>();
            List<List<List<Segment>>> inPiece = new ArrayList<>();
            for (int k = 0; k < stretches.size(); k++) {
                List<List<Segment>> staff = new ArrayList<>();
                for (List<Segment> st : stretches.get(k)) if (piece.apply(st) == p) staff.add(st);
                if (!staff.isEmpty()) {
                    inKeep.add(keeps.get(k));
                    inPiece.add(staff);
                }
            }
            // The staves vote together only when none of them changes key in the piece.
            boolean together = inPiece.stream().allMatch(s -> s.size() == 1);
            List<Integer> all = new ArrayList<>();
            for (List<List<Segment>> staff : inPiece) all.addAll(readings(staff.get(0)));
            Integer key = together ? vote(all) : null;
            int agree = 0;
            for (List<List<Segment>> staff : inPiece) if (java.util.Objects.equals(vote(readings(staff.get(0))), key)) agree++;
            boolean most = key != null && agree >= 2 && agree > inPiece.size() - agree;
            for (int s = 0; s < inPiece.size(); s++) {
                boolean keep = inKeep.get(s);
                for (List<Segment> stretch : inPiece.get(s)) {
                    List<Integer> rd = readings(stretch);
                    Integer own = vote(rd);
                    boolean follows = !rd.isEmpty()
                            && (java.util.Objects.equals(key, rd.get(0)) || java.util.Objects.equals(key, own) || (most && !keep));
                    if (key != null && follows) {
                        targets.put(stretch, key);
                    } else if (own != null) {
                        targets.put(stretch, own);
                    }
                }
            }
        }
        return targets;
    }

    private record StepOctave(String step, int octave) {}

    /** Moves the notes in {@code m} that key {@code old} altered to key {@code nw}; returns how many events changed. */
    static int respell(Measure m, int old, int nw) {
        int changed = 0;
        // An accidental printed in the measure holds for that step and octave until the bar.
        Set<StepOctave> printed = new HashSet<>();
        List<Event> events = new ArrayList<>();
        for (Voice v : m.voices) for (Event e : v.events) if (!e.notes.isEmpty()) events.add(e);
        events.sort(java.util.Comparator.<Event, org.lilyscan.core.ir.Frac>comparing(e -> e.offset).thenComparing(e -> !e.grace));
        for (Event e : events) {
            List<String> before = new ArrayList<>();
            for (NoteHead h : e.notes) before.add(h.pitch.label());
            boolean moved = false;
            for (NoteHead h : e.notes) {
                Pitch p = h.pitch;
                if (h.accidentalShown) {
                    printed.add(new StepOctave(p.step, p.octave));
                    continue;
                }
                if (printed.contains(new StepOctave(p.step, p.octave)) || p.alter != keyAlter(old, p.step)) continue;
                int alter = keyAlter(nw, p.step);
                if (alter != p.alter) {
                    h.pitch = new Pitch(p.step, alter, p.octave);
                    moved = true;
                }
            }
            if (moved) {
                e.provenance.add(Repair.provenance(RULE, "pitches", before));
                changed++;
            }
        }
        return changed;
    }

    public static List<Repair> consistentKeys(Score score, OmrBook book) {
        Layout layout = layout(book);
        List<Score.PartStaff> staves = new ArrayList<>();
        for (Part part : score.parts) for (Staff staff : part.staves) staves.add(new Score.PartStaff(part, staff));
        List<List<List<Segment>>> stretches = new ArrayList<>();
        List<Boolean> keeps = new ArrayList<>();
        for (Score.PartStaff ps : staves) {
            stretches.add(stretches(ps.staff(), layout.starts(), layout.movements()));
            keeps.add(mayTranspose(ps.part(), ps.staff()));
        }
        Map<List<Segment>, Integer> targets = targets(stretches, new ArrayList<>(new TreeSet<>(layout.movements())), keeps);
        List<Repair> repairs = new ArrayList<>();
        for (int k = 0; k < staves.size(); k++) {
            Part part = staves.get(k).part();
            Staff staff = staves.get(k).staff();
            List<String> first = new ArrayList<>();
            List<String> fixed = new ArrayList<>();
            int notes = 0;
            for (List<Segment> stretch : stretches.get(k)) {
                Integer key = targets.get(stretch);
                if (key == null) continue;
                for (Segment segment : stretch) {
                    if (segment.fifths == key) continue;
                    for (Measure m : segment.measures) {
                        notes += respell(m, segment.fifths, key);
                        if (m.key != null || layout.movements().contains(m.index)) {
                            String mode = m.key != null ? m.key.mode : null;
                            m.key = newKey(key, mode);
                        }
                        fixed.add(Repair.label(m));
                    }
                    first.add(fixed.get(fixed.size() - segment.measures.size()));
                }
            }
            if (fixed.isEmpty()) continue;
            dropRestatedKeys(staff, layout.movements());
            String name = part.name != null && !part.name.isEmpty() ? part.name : part.id;
            String detail = name + ": key signature misread on the system(s) from measure " + String.join(", ", first)
                    + "; set to the key the piece is read in (" + notes + " note(s) respelled)";
            repairs.add(new Repair(RULE, part.id, staff.number, detail, fixed));
        }
        return repairs;
    }

    private static KeySignature newKey(int fifths, String mode) {
        KeySignature k = new KeySignature();
        k.fifths = fifths;
        k.mode = mode;
        return k;
    }

    /** After a repair, a system may restate the key already in force; keep only changes. */
    static void dropRestatedKeys(Staff staff, Set<Integer> movements) {
        Integer fifths = null;
        for (Measure m : staff.measures) {
            if (m.key == null) continue;
            if (fifths != null && m.key.fifths == fifths && !movements.contains(m.index)) {
                m.key = null;
            } else {
                fifths = m.key.fifths;
            }
        }
    }

    /**
     * Parts of a combined score, lined up measure by measure, where one or two read another key
     * than the others at the same measures: set to the others' key, with the notes it alters
     * respelled. {@code keeps.get(k)}: part {@code k} transposes (or may), so its key is its
     * own. Returns the measures changed, by part index (in part order).
     */
    public static Map<Integer, List<String>> ensembleKeys(List<Part> parts, List<Boolean> keeps) {
        List<Integer> voters = new ArrayList<>();
        for (int k = 0; k < keeps.size(); k++) if (!keeps.get(k)) voters.add(k);
        if (voters.size() < 3) return new LinkedHashMap<>();
        Map<Integer, List<Integer>> inForce = new LinkedHashMap<>();
        for (int k : voters) {
            int fifths = 0;
            List<Integer> keys = new ArrayList<>();
            for (Measure m : parts.get(k).staves.get(0).measures) {
                if (m.key != null) fifths = m.key.fifths;
                keys.add(fifths);
            }
            inForce.put(k, keys);
        }
        int columns = Integer.MAX_VALUE;
        for (List<Integer> v : inForce.values()) columns = Math.min(columns, v.size());
        List<Integer> majority = new ArrayList<>();
        for (int c = 0; c < columns; c++) {
            // Counter(...).most_common()[0]: the highest count, first seen on a tie.
            Map<Integer, Integer> counts = new LinkedHashMap<>();
            for (int k : voters) counts.merge(inForce.get(k).get(c), 1, Integer::sum);
            Map.Entry<Integer, Integer> top = null;
            for (Map.Entry<Integer, Integer> e : counts.entrySet()) if (top == null || e.getValue() > top.getValue()) top = e;
            int n = top.getValue();
            majority.add(n >= 2 && n > voters.size() - n ? top.getKey() : null);
        }
        Set<Integer> turns = new HashSet<>();
        for (int c = 1; c < columns; c++) if (!java.util.Objects.equals(majority.get(c), majority.get(c - 1))) turns.add(c);
        Map<Integer, List<String>> changed = new LinkedHashMap<>();
        Map<Integer, List<Integer>> targets = new LinkedHashMap<>();
        inForce.forEach((k, v) -> targets.put(k, new ArrayList<>(v)));
        for (int k : voters) {
            int c = 0;
            while (c < columns) {
                Integer maj = majority.get(c);
                if (maj == null || inForce.get(k).get(c).equals(maj)) {
                    c++;
                    continue;
                }
                int end = c;
                while (end + 1 < columns && java.util.Objects.equals(majority.get(end + 1), maj)
                        && !inForce.get(k).get(end + 1).equals(maj)) {
                    end++;
                }
                boolean nearTurn = false;
                for (int t = c; t < end + 2; t++) if (turns.contains(t)) nearTurn = true;
                if (!nearTurn) {
                    for (int col = c; col <= end; col++) {
                        int old = inForce.get(k).get(col), target = majority.get(col);
                        targets.get(k).set(col, target);
                        for (Staff staff : parts.get(k).staves) {
                            if (col < staff.measures.size()) respell(staff.measures.get(col), old, target);
                        }
                        Measure m = parts.get(k).staves.get(0).measures.get(col);
                        changed.computeIfAbsent(k, x -> new ArrayList<>()).add(Repair.label(m));
                    }
                }
                c = end + 1;
            }
        }
        for (int k : changed.keySet()) {
            for (Staff staff : parts.get(k).staves) {
                Integer previous = null;
                for (int c = 0; c < Math.min(columns, staff.measures.size()); c++) {
                    Measure m = staff.measures.get(c);
                    int key = targets.get(k).get(c);
                    if (previous == null || key != previous) {
                        String mode = m.key != null ? m.key.mode : null;
                        m.key = newKey(key, mode);
                    } else if (m.key != null) {
                        m.key = null;
                    }
                    previous = key;
                }
            }
        }
        return changed;
    }
}
