package org.lilyscan.core.repair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Lyric;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.omr.OmrBook.Box;
import org.lilyscan.core.util.Py;

/**
 * Split lyric syllables the engine glued together (port of {@code lilyscan/repair/syllables.py}).
 * Text is indexed by code point, as Python indexes strings.
 */
public final class Syllables {
    private Syllables() {}

    public static final String RULE = "lyric-split";
    static final int MAX_PIECES = 4;
    /** Worst allowed distance between a piece's centre and its note, in average glyph widths. */
    static final double MAX_OFFSET = 1.0;

    private static final String VOWELS = "aeiouyäöüàáâãåèéêëìíîïòóôõùúûýæœ";
    private static final Set<String> DIPHTHONGS = Set.of("ie", "ei", "ai", "au", "eu", "äu", "oi", "ou", "oo", "ee",
            "ae", "oe", "ay", "ey", "oy");
    private static final Map<Integer, Double> WIDTHS = new HashMap<>();
    static {
        put("ijlI.,;:'!|", 0.28);
        put("frt", 0.35);
        put("cesz", 0.45);
        put("abdghknopquvxy", 0.55);
        put("mw", 0.8);
        put("MW", 0.9);
    }

    private static void put(String chars, double w) {
        chars.codePoints().forEach(c -> WIDTHS.put(c, w));
    }

    static double width(int ch) {
        Double w = WIDTHS.get(ch);
        if (w != null) return w;
        return Character.isUpperCase(ch) ? 0.7 : 0.5;
    }

    /** Where a syllable for this note can sit: centred on the head, or starting at its left edge before a melisma. */
    record Anchor(double centre, double left, boolean melisma) {}

    static double fit(double start, double stop, Anchor anchor) {
        double centred = Math.abs((start + stop) / 2 - anchor.centre());
        return anchor.melisma() ? Math.min(centred, Math.abs(start - anchor.left())) : centred;
    }

    record Split(double worst, List<String> pieces) {}

    private static String sub(int[] cps, int a, int b) {
        return new String(cps, a, b - a);
    }

    private static boolean hasVowel(String piece) {
        return piece.codePoints().anyMatch(c -> VOWELS.contains(new String(Character.toChars(c)).toLowerCase(Locale.ROOT)));
    }

    /** All {@code r}-combinations of 1..n-1 in lexicographic order ({@code itertools.combinations}). */
    static List<int[]> combinations(int n, int r) {
        List<int[]> out = new ArrayList<>();
        int[] c = new int[r];
        rec(1, n, 0, c, out);
        return out;
    }

    private static void rec(int from, int n, int depth, int[] c, List<int[]> out) {
        if (depth == c.length) {
            out.add(c.clone());
            return;
        }
        for (int v = from; v < n; v++) {
            c[depth] = v;
            rec(v + 1, n, depth + 1, c, out);
        }
    }

    /** The best split of {@code text} into one piece per anchor, or null when no split fits. */
    static Split splitWord(String text, Box box, List<Anchor> anchors) {
        int[] cps = text.codePoints().toArray();
        int n = cps.length, k = anchors.size();
        if (k < 2 || n < k) return null;
        double[] widths = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            widths[i] = width(cps[i]);
            total += widths[i];
        }
        double scale = box.w() / total;
        double[] edges = new double[n + 1];
        edges[0] = box.x();
        for (int i = 0; i < n; i++) edges[i + 1] = edges[i] + widths[i] * scale;
        double glyph = box.w() / n;
        Split best = null;
        for (int[] cuts : combinations(n, k - 1)) {
            boolean diphthong = false;
            for (int c : cuts) {
                if (DIPHTHONGS.contains(sub(cps, c - 1, Math.min(c + 1, n)).toLowerCase(Locale.ROOT))) diphthong = true;
            }
            if (diphthong) continue;
            int[] bounds = new int[k + 1];
            bounds[0] = 0;
            System.arraycopy(cuts, 0, bounds, 1, cuts.length);
            bounds[k] = n;
            List<String> pieces = new ArrayList<>();
            for (int i = 0; i < k; i++) pieces.add(sub(cps, bounds[i], bounds[i + 1]));
            if (!pieces.stream().allMatch(Syllables::hasVowel)) continue;
            double worst = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < k; i++) worst = Math.max(worst, fit(edges[bounds[i]], edges[bounds[i + 1]], anchors.get(i)));
            worst = worst / glyph;
            if (best == null || worst < best.worst()) best = new Split(worst, pieces);
        }
        if (best == null || best.worst() > MAX_OFFSET) return null;
        return best;
    }

    static List<String> syllabic(String original, int k) {
        List<String> out = new ArrayList<>();
        out.add(original.equals("single") || original.equals("begin") ? "begin" : "middle");
        for (int i = 0; i < k - 2; i++) out.add("middle");
        out.add(original.equals("single") || original.equals("end") ? "end" : "middle");
        return out;
    }

    private record Item(String text, Box box) {}

    /** Lyric syllables per page index: (text, box). */
    private static Map<Integer, List<Item>> items(OmrBook book) {
        Map<Integer, List<Item>> out = new HashMap<>();
        for (int page = 0; page < book.sheets().size(); page++) {
            List<Item> list = new ArrayList<>();
            for (OmrBook.OmrSystem system : book.sheets().get(page).systems()) {
                for (OmrBook.Inter i : system.inters()) {
                    if ("lyric-item".equals(i.kind()) && "Syllable".equals(i.role()) && i.value() != null
                            && !i.value().isEmpty() && i.box() != null) {
                        list.add(new Item(i.value(), i.box()));
                    }
                }
            }
            out.put(page, list);
        }
        return out;
    }

    private static Double centre(Event e) {
        return e.bbox == null ? null : e.bbox.x + e.bbox.w / 2;
    }

    /** A note that could take a syllable of this verse. */
    private static boolean free(Event e, int verse, int page) {
        if (Event.REST.equals(e.kind) || e.grace || e.bbox == null || e.bbox.page != page) return false;
        for (Lyric ly : e.lyrics) if (ly.verse == verse) return false;
        return !(!e.notes.isEmpty() && e.notes.stream().allMatch(h -> h.tieStop));
    }

    private static int indexOf(List<Event> events, Event e) {
        for (int i = 0; i < events.size(); i++) if (events.get(i) == e) return i;
        throw new IllegalArgumentException("not in list");
    }

    public static List<Repair> splitGluedSyllables(Score score, OmrBook book) {
        Map<Integer, List<Item>> items = items(book);
        List<Repair> repairs = new ArrayList<>();
        for (Score.PartStaff ps : score.staves()) {
            List<String> measures = new ArrayList<>();
            for (Measure m : ps.staff().measures) {
                for (Voice v : m.voices) {
                    List<Event> events = new ArrayList<>();
                    for (Event e : v.events) if (!e.grace) events.add(e);
                    for (int i = 0; i < events.size(); i++) {
                        Event e = events.get(i);
                        Double x = centre(e);
                        if (x == null || e.bbox == null) continue;
                        for (Lyric ly : new ArrayList<>(e.lyrics)) {
                            Box box = null;
                            for (Item it : items.getOrDefault(e.bbox.page, List.of())) {
                                Box b = it.box();
                                if (Py.strip(it.text()).equals(Py.strip(ly.text)) && b.x() <= x && x <= b.x() + b.w()
                                        && b.y() > e.bbox.y) {
                                    box = b;
                                    break;
                                }
                            }
                            if (box == null || Py.len(Py.strip(ly.text)) < 2) continue;
                            List<Event> near = new ArrayList<>();
                            near.add(e);
                            for (int step : new int[] {-1, 1}) {
                                int j = i + step;
                                while (0 <= j && j < events.size() && near.size() < MAX_PIECES + 2) {
                                    Event other = events.get(j);
                                    Double cx = centre(other);
                                    if (cx == null || !free(other, ly.verse, e.bbox.page)) break;
                                    if (!(box.x() <= cx && cx <= box.x() + box.w())) break;
                                    near.add(other);
                                    j += step;
                                }
                            }
                            near.sort(java.util.Comparator.comparingInt(g -> indexOf(events, g)));
                            Object[] found = bestSplit(ly, box, e, near, events);
                            if (found == null) continue;
                            @SuppressWarnings("unchecked")
                            List<Event> group = (List<Event>) found[0];
                            @SuppressWarnings("unchecked")
                            List<String> pieces = (List<String>) found[1];
                            apply(group, ly, pieces);
                            measures.add(Repair.label(m));
                        }
                    }
                }
            }
            if (!measures.isEmpty()) {
                var part = ps.part();
                String name = part.name != null && !part.name.isEmpty() ? part.name : part.id;
                repairs.add(new Repair(RULE, part.id, ps.staff().number,
                        name + ": split syllables the engine read as one word", new ArrayList<>(new LinkedHashSet<>(measures))));
            }
        }
        return repairs;
    }

    private static Anchor anchor(Event g, List<Event> group, List<Event> events, int verse) {
        int k = indexOf(events, g);
        Event after = k + 1 < events.size() ? events.get(k + 1) : null;
        boolean melisma = after != null && group.stream().noneMatch(x -> x == after) && !Event.REST.equals(after.kind)
                && after.lyrics.stream().noneMatch(ly -> ly.verse == verse);
        return new Anchor(g.bbox.x + g.bbox.w / 2, g.bbox.x, melisma);
    }

    /** The notes (the owner and some free neighbours) and pieces that fit best, as {group, pieces}. */
    private static Object[] bestSplit(Lyric ly, Box box, Event owner, List<Event> near, List<Event> events) {
        Double bestWorst = null;
        Object[] best = null;
        List<Event> others = new ArrayList<>();
        for (Event g : near) if (g != owner) others.add(g);
        for (int size = 1; size < Math.min(MAX_PIECES, near.size()); size++) {
            for (int[] chosen : combinationsOf(others.size(), size)) {
                List<Event> group = new ArrayList<>();
                group.add(owner);
                for (int c : chosen) group.add(others.get(c));
                group.sort(java.util.Comparator.comparingInt(g -> indexOf(events, g)));
                List<Anchor> anchors = new ArrayList<>();
                for (Event g : group) anchors.add(anchor(g, group, events, ly.verse));
                Split found = splitWord(Py.strip(ly.text), box, anchors);
                if (found != null && (bestWorst == null || found.worst() < bestWorst)) {
                    bestWorst = found.worst();
                    best = new Object[] {group, found.pieces()};
                }
            }
        }
        return best;
    }

    /** {@code itertools.combinations(range(n), r)}. */
    private static List<int[]> combinationsOf(int n, int r) {
        List<int[]> out = new ArrayList<>();
        int[] c = new int[r];
        recOf(0, n, 0, c, out);
        return out;
    }

    private static void recOf(int from, int n, int depth, int[] c, List<int[]> out) {
        if (depth == c.length) {
            out.add(c.clone());
            return;
        }
        for (int v = from; v < n; v++) {
            c[depth] = v;
            recOf(v + 1, n, depth + 1, c, out);
        }
    }

    private static void apply(List<Event> group, Lyric original, List<String> pieces) {
        Event owner = null;
        for (Event g : group) {
            if (g.lyrics.stream().anyMatch(ly -> ly == original)) {
                owner = g;
                break;
            }
        }
        List<String> kinds = syllabic(original.syllabic, pieces.size());
        for (int i = 0; i < group.size(); i++) {
            Event g = group.get(i);
            List<Object> before = new ArrayList<>();
            for (Lyric ly : g.lyrics) before.add(List.of(ly.verse, ly.text));
            Lyric nw = new Lyric(pieces.get(i), original.verse, kinds.get(i), original.extend && g == group.get(group.size() - 1));
            if (g == owner) {
                List<Lyric> lyrics = new ArrayList<>();
                for (Lyric ly : g.lyrics) lyrics.add(ly == original ? nw : ly);
                g.lyrics = lyrics;
            } else {
                List<Lyric> lyrics = new ArrayList<>(g.lyrics);
                lyrics.add(nw);
                lyrics.sort(java.util.Comparator.comparingInt(ly -> ly.verse));
                g.lyrics = lyrics;
            }
            g.provenance.add(Repair.provenance(RULE, "lyrics", before));
        }
    }
}
