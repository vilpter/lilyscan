package org.lilyscan.core.repair;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.lilyscan.core.ir.BBox;
import org.lilyscan.core.ir.ChordSymbol;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Lyric;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Provenance;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.util.Py;

/**
 * Lyrics cleanup (port of {@code lilyscan/repair/lyrics.py}): page text, chord names and
 * stray marks read as lyrics are dropped (chord names become chord symbols), and each system's
 * verses are renumbered from 1.
 */
public final class Lyrics {
    private Lyrics() {}

    public static final String TEXT_RULE = "lyric-text";
    public static final String VERSE_RULE = "lyric-verse";

    private static final Pattern PAGE_TEXT = Pattern.compile(
            "^(v?\\d+(\\.\\d+)+|www\\..+|.+\\.(org|com|net)|©.*|\\(c\\).*|copyright.*"
                    + "|scanned|camscanner|turboscan|tinyscanner|scanbot|genius ?scan|adobe ?scan)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS);
    private static final String VOWELS = "aeiouyAEIOUYàáâèéêìíòóôùú";
    /** Share of a line's syllables that must read as chord names for it to be a chord line. */
    static final double CHORD_LINE = 0.75;

    private static boolean hasLetter(String text) {
        return text.codePoints().anyMatch(Character::isLetter);
    }

    static boolean stray(String text) {
        int[] letters = text.codePoints().filter(Character::isLetter).toArray();
        return letters.length == 0 || (letters.length == 1 && VOWELS.indexOf(letters[0]) < 0);
    }

    /** Measures grouped by system, from their page boxes (one group without boxes). */
    static List<List<Measure>> systems(Staff staff) {
        List<List<Measure>> groups = new ArrayList<>();
        groups.add(new ArrayList<>());
        BBox last = null;
        for (Measure m : staff.measures) {
            BBox b = m.bbox;
            if (b != null && last != null && (b.page != last.page || b.x < last.x)) groups.add(new ArrayList<>());
            if (b != null) last = b;
            groups.get(groups.size() - 1).add(m);
        }
        groups.removeIf(List::isEmpty);
        return groups;
    }

    private record Entry(Measure measure, Event event, Lyric lyric) {}

    private static final class Line {
        final List<Entry> entries = new ArrayList<>();

        List<String> texts() {
            List<String> out = new ArrayList<>();
            for (Entry e : entries) out.add(Py.strip(e.lyric().text));
            return out;
        }
    }

    private static List<Chords.ChordName> chordLine(Line line) {
        List<Chords.ChordName> names = new ArrayList<>();
        for (String t : line.texts()) names.add(Chords.parse(t));
        int found = 0;
        boolean decorated = false;
        for (Chords.ChordName n : names) {
            if (n != null) {
                found++;
                decorated |= n.decorated();
            }
        }
        if (found < CHORD_LINE * names.size() || !decorated) return null;
        return names;
    }

    /** Measures changed, per kind of change, in first-seen order. */
    private static final class Log {
        final Map<String, List<String>> measures = new LinkedHashMap<>();

        void add(String what, Measure m) {
            List<String> seen = measures.computeIfAbsent(what, k -> new ArrayList<>());
            String number = Repair.label(m);
            if (!seen.contains(number)) seen.add(number);
        }
    }

    private static void noteChange(Event e, List<Lyric> before) {
        for (Provenance p : e.provenance) if (TEXT_RULE.equals(p.rule)) return;
        List<Object> lyrics = new ArrayList<>();
        for (Lyric ly : before) lyrics.add(List.of(ly.verse, ly.text));
        e.provenance.add(Repair.provenance(TEXT_RULE, "lyrics", lyrics));
    }

    /** Cleans one staff's lyrics within one system; true if verses were renumbered. */
    private static boolean cleanSystem(List<Measure> measures, Log log) {
        Map<Integer, Line> lines = new LinkedHashMap<>();
        for (Measure m : measures) {
            for (Voice v : m.voices) {
                for (Event e : v.events) {
                    for (Lyric ly : e.lyrics) lines.computeIfAbsent(ly.verse, k -> new Line()).entries.add(new Entry(m, e, ly));
                }
            }
        }
        Set<Lyric> drop = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Line line : lines.values()) {
            List<String> texts = line.texts();
            List<Chords.ChordName> chords = chordLine(line);
            String what;
            if (texts.stream().anyMatch(t -> PAGE_TEXT.matcher(t).find())) {
                what = "page text";
            } else if (chords != null) {
                what = "chord names";
                for (int i = 0; i < line.entries.size(); i++) {
                    Entry en = line.entries.get(i);
                    Chords.ChordName name = chords.get(i);
                    Measure m = en.measure();
                    if (name != null && m.chordSymbols.stream().noneMatch(c -> c.offset.equals(en.event().offset))) {
                        ChordSymbol cs = new ChordSymbol();
                        cs.offset = en.event().offset;
                        cs.root = name.root();
                        cs.kind = name.kind();
                        cs.bass = name.bass();
                        m.chordSymbols.add(cs);
                        m.chordSymbols.sort((a, b) -> a.offset.compareTo(b.offset));
                    }
                }
            } else if (texts.stream().allMatch(Lyrics::stray)) {
                what = "stray marks";
            } else {
                for (Entry en : line.entries) {
                    if (!hasLetter(en.lyric().text)) {
                        drop.add(en.lyric());
                        log.add("stray marks", en.measure());
                    }
                }
                continue;
            }
            for (Entry en : line.entries) {
                drop.add(en.lyric());
                log.add(what, en.measure());
            }
        }

        Map<Event, List<Lyric>> changed = new IdentityHashMap<>();
        List<Event> order = new ArrayList<>();
        for (Line line : lines.values()) {
            for (Entry en : line.entries) {
                if (!changed.containsKey(en.event())) {
                    changed.put(en.event(), new ArrayList<>(en.event().lyrics));
                    order.add(en.event());
                }
            }
        }
        for (Event e : order) {
            List<Lyric> kept = new ArrayList<>();
            for (Lyric ly : e.lyrics) if (!drop.contains(ly)) kept.add(ly);
            if (kept.size() != e.lyrics.size()) {
                noteChange(e, changed.get(e));
                e.lyrics = kept;
            }
        }
        TreeSet<Integer> remaining = new TreeSet<>();
        for (Event e : order) for (Lyric ly : e.lyrics) remaining.add(ly.verse);
        Map<Integer, Integer> renumber = new LinkedHashMap<>();
        int n = 1;
        for (int old : remaining) {
            if (old != n) renumber.put(old, n);
            n++;
        }
        if (renumber.isEmpty()) return false;
        for (Event e : order) for (Lyric ly : e.lyrics) ly.verse = renumber.getOrDefault(ly.verse, ly.verse);
        return true;
    }

    private static List<Repair> staffRepairs(Part part, Staff staff, Score score) {
        Log log = new Log();
        List<List<String>> renumbered = new ArrayList<>();
        for (List<Measure> system : systems(staff)) {
            if (cleanSystem(system, log)) {
                List<String> ms = new ArrayList<>();
                for (Measure m : system) ms.add(Repair.label(m));
                renumbered.add(ms);
            }
        }
        String name = part.name != null && !part.name.isEmpty() ? part.name : part.id;
        List<Repair> repairs = new ArrayList<>();
        log.measures.forEach((what, ms) -> {
            if (!what.equals("chord names")) {
                repairs.add(new Repair(TEXT_RULE, part.id, staff.number, name + ": dropped " + what + " read as lyrics", ms));
            }
        });
        if (log.measures.containsKey("chord names")) {
            repairs.add(new Repair(TEXT_RULE, part.id, staff.number,
                    name + ": chord names read as lyrics, moved to chord symbols", log.measures.get("chord names")));
        }
        if (!renumbered.isEmpty()) {
            List<String> spans = new ArrayList<>();
            for (List<String> ms : renumbered) spans.add(ms.size() == 1 ? ms.get(0) : ms.get(0) + "-" + ms.get(ms.size() - 1));
            String detail = "verse numbers restarted at 1 in measures " + String.join(", ", spans);
            score.provenance.add(Repair.provenance(VERSE_RULE, "part", part.id, "staff", staff.number, "detail", detail));
            List<String> measures = new ArrayList<>();
            for (List<String> ms : renumbered) measures.addAll(ms);
            repairs.add(new Repair(VERSE_RULE, part.id, staff.number, name + ": " + detail, measures));
        }
        return repairs;
    }

    public static List<Repair> cleanLyrics(Score score) {
        List<Repair> repairs = new ArrayList<>();
        for (Part part : score.parts) for (Staff staff : part.staves) repairs.addAll(staffRepairs(part, staff, score));
        return repairs;
    }
}
