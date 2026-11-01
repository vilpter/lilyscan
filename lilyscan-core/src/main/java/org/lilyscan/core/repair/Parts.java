package org.lilyscan.core.repair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lilyscan.core.ir.BBox;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Voice;

/**
 * Merge parts the engine split between systems (port of {@code lilyscan/repair/parts.py}).
 * Two parts are merged when they are never present in the same measure, have the same staves
 * and clefs, and either their names are compatible or one name is unreadable and its
 * neighbours were merged in the same order.
 */
public final class Parts {
    private Parts() {}

    public static final String RULE = "part-merge";

    private static final Map<Character, Character> OCR_DIGITS = Map.of('1', 'l', '0', 'o', '5', 's', '8', 'b');
    private static final Map<String, Integer> ROMAN = Map.of("i", 1, "ii", 2, "iii", 3, "iv", 4, "v", 5, "vi", 6);
    private static final Map<String, List<String>> ALIASES = Map.of(
            "cello", List.of("violoncello"),
            "bassoon", List.of("fagotto", "fagott"),
            "horn", List.of("corno"),
            "doublebass", List.of("contrabass", "contrabasso", "kontrabass"),
            "timpani", List.of("pauken"));
    private static final Set<String> PLACEHOLDERS = Set.of("voice");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern LETTERS = Pattern.compile("[a-z]+");
    private static final Pattern HAS_LOWER = Pattern.compile("[a-z]");

    /** Letters and instrument number of a part name; null when there are no letters. */
    record Label(String letters, Integer number) {}

    private static boolean isDigits(String w) {
        return !w.isEmpty() && w.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    static Label label(String name) {
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(name == null ? "" : name);
        while (m.find()) {
            String w = m.group();
            // An upper-case I inside a word is usually a misread l ("FI." for "Fl.").
            if (!ROMAN.containsKey(w.toLowerCase(java.util.Locale.ROOT))) w = w.charAt(0) + w.substring(1).replace("I", "l");
            words.add(w.toLowerCase(java.util.Locale.ROOT));
        }
        Integer number = null;
        if (words.size() > 1) {
            String last = words.get(words.size() - 1);
            if (isDigits(last) || ROMAN.containsKey(last)) {
                words.remove(words.size() - 1);
                number = isDigits(last) ? Integer.valueOf(Integer.parseInt(last)) : ROMAN.get(last);
            }
        }
        StringBuilder letters = new StringBuilder();
        for (String w : words) {
            if (HAS_LOWER.matcher(w).find()) {
                for (char c : w.toCharArray()) letters.append(OCR_DIGITS.getOrDefault(c, c));
            } else {
                letters.append(w);
            }
        }
        String l = letters.toString();
        return LETTERS.matcher(l.isEmpty() ? "0" : l).matches() ? new Label(l, number) : null;
    }

    /** {@code short} is {@code long} or an abbreviation of it: same first letter, then a subsequence. */
    static boolean abbreviates(String shortName, String longName) {
        if (shortName.isEmpty() || shortName.charAt(0) != longName.charAt(0)) return false;
        int j = 1;
        for (int i = 1; i < shortName.length(); i++) {
            char ch = shortName.charAt(i);
            while (j < longName.length() && longName.charAt(j) != ch) j++;
            if (j >= longName.length()) return false;
            j++;
        }
        return true;
    }

    /** One name abbreviates the other; a name read without its number matches any number. */
    public static boolean namesCompatible(String a, String b) {
        Label la = label(a), lb = label(b);
        if (la == null || lb == null) return false;
        if (la.number() != null && lb.number() != null && !la.number().equals(lb.number())) return false;
        List<String> formsA = new ArrayList<>(List.of(la.letters()));
        formsA.addAll(ALIASES.getOrDefault(la.letters(), List.of()));
        List<String> formsB = new ArrayList<>(List.of(lb.letters()));
        formsB.addAll(ALIASES.getOrDefault(lb.letters(), List.of()));
        for (String x : formsA) {
            for (String y : formsB) {
                // sorted((x, y), key=len): stable, so x first on equal lengths
                boolean ok = y.length() < x.length() ? abbreviates(y, x) : abbreviates(x, y);
                if (ok) return true;
            }
        }
        return false;
    }

    static boolean absent(Measure m) {
        if (m.bbox != null) return false;
        for (Voice v : m.voices) {
            for (Event e : v.events) if (!(Event.REST.equals(e.kind) && e.measureRest)) return false;
        }
        return true;
    }

    static List<Boolean> presence(Part part) {
        List<Boolean> out = new ArrayList<>();
        if (part.staves.isEmpty()) return out;
        int n = part.staves.get(0).measures.size();
        for (Staff s : part.staves) {
            if (s.measures.size() != n) throw new IllegalArgumentException("zip() argument lengths differ");
        }
        for (int i = 0; i < n; i++) {
            boolean any = false;
            for (Staff s : part.staves) any |= !absent(s.measures.get(i));
            out.add(any);
        }
        return out;
    }

    record ClefKey(String sign, Integer line) {}

    private static int firstPresent(List<Boolean> present, int otherwise) {
        for (int i = 0; i < present.size(); i++) if (present.get(i)) return i;
        return otherwise;
    }

    /** Clef (sign, line) of each staff where the part first appears; octave marks ignored. */
    static List<ClefKey> clefs(Part part, List<Boolean> present) {
        int first = firstPresent(present, 0);
        List<ClefKey> out = new ArrayList<>();
        for (Staff s : part.staves) {
            Clef current = null;
            for (Measure m : s.measures.subList(0, Math.min(first + 1, s.measures.size()))) {
                for (Clef c : m.clefs) if (m.index < first || c.offset.isZero()) current = c;
            }
            if (current == null) {
                outer:
                for (Measure m : s.measures) {
                    for (Clef c : m.clefs) {
                        current = c;
                        break outer;
                    }
                }
            }
            out.add(current == null ? null : new ClefKey(current.sign, current.line));
        }
        return out;
    }

    static final class Cluster {
        final List<Part> parts = new ArrayList<>();
        List<Boolean> present;
        final List<ClefKey> clefs;
        final List<Integer> members = new ArrayList<>();

        Cluster(Part part, List<Boolean> present, List<ClefKey> clefs, int member) {
            parts.add(part);
            this.present = present;
            this.clefs = clefs;
            members.add(member);
        }

        boolean accepts(Part part, List<Boolean> present, List<ClefKey> clefs) {
            Part rep = parts.get(0);
            if (part.staves.size() != rep.staves.size() || present.size() != this.present.size()) return false;
            for (int i = 0; i < present.size(); i++) if (present.get(i) && this.present.get(i)) return false;
            if (clefs.size() != this.clefs.size()) throw new IllegalArgumentException("zip() argument lengths differ");
            for (int i = 0; i < clefs.size(); i++) {
                ClefKey a = clefs.get(i), b = this.clefs.get(i);
                if (a != null && b != null && !a.equals(b)) return false;
            }
            return true;
        }

        boolean namedLike(Part part) {
            for (Part p : parts) {
                for (String x : new String[] {p.name, p.abbreviation}) {
                    for (String y : new String[] {part.name, part.abbreviation}) {
                        if (namesCompatible(x, y)) return true;
                    }
                }
            }
            return false;
        }

        void add(int index, Part part, List<Boolean> present) {
            parts.add(part);
            members.add(index);
            List<Boolean> merged = new ArrayList<>();
            for (int i = 0; i < present.size(); i++) merged.add(this.present.get(i) || present.get(i));
            this.present = merged;
        }
    }

    static boolean readable(Part part) {
        for (String n : new String[] {part.name, part.abbreviation}) {
            Label l = label(n);
            if (l != null && !PLACEHOLDERS.contains(l.letters())) return true;
        }
        return false;
    }

    private static int indexOf(List<Cluster> clusters, Cluster c) {
        for (int i = 0; i < clusters.size(); i++) if (clusters.get(i) == c) return i;
        return -1;
    }

    private static boolean containsSame(List<Cluster> list, Cluster c) {
        return indexOf(list, c) >= 0;
    }

    static List<Cluster> cluster(Score score) {
        List<Part> parts = score.parts;
        int n = parts.size();
        List<List<Boolean>> presence = new ArrayList<>();
        for (Part p : parts) presence.add(presence(p));
        List<List<ClefKey>> clefs = new ArrayList<>();
        for (int k = 0; k < n; k++) clefs.add(clefs(parts.get(k), presence.get(k)));
        int[] first = new int[n];
        for (int k = 0; k < n; k++) first[k] = firstPresent(presence.get(k), presence.get(k).size());
        List<Cluster> clusters = new ArrayList<>();
        Map<Integer, Cluster> home = new HashMap<>();

        java.util.function.IntConsumer start = k -> {
            Cluster c = new Cluster(parts.get(k), presence.get(k), clefs.get(k), k);
            home.put(k, c);
            clusters.add(c);
        };
        java.util.function.BiConsumer<Integer, Cluster> join = (k, c) -> {
            c.add(k, parts.get(k), presence.get(k));
            home.put(k, c);
        };
        java.util.function.IntFunction<Cluster> byNeighbour = k -> {
            List<Integer> system = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                List<Boolean> pr = presence.get(j);
                if (first[k] < pr.size() && pr.get(first[k])) system.add(j);
            }
            int pos = system.indexOf(k);
            for (int step : new int[] {-1, 1}) {
                if (0 <= pos + step && pos + step < system.size() && home.containsKey(system.get(pos + step))) {
                    int j = indexOf(clusters, home.get(system.get(pos + step))) - step;
                    if (0 <= j && j < clusters.size() && clusters.get(j).accepts(parts.get(k), presence.get(k), clefs.get(k))) {
                        return clusters.get(j);
                    }
                }
            }
            return null;
        };
        java.util.function.IntPredicate alone = k -> {
            for (int j = 0; j < n; j++) {
                if (j == k) continue;
                List<Boolean> pk = presence.get(k), pj = presence.get(j);
                for (int i = 0; i < pk.size(); i++) if (pk.get(i) && i < pj.size() && pj.get(i)) return false;
            }
            return true;
        };

        boolean oneLine = true;
        for (int k = 0; k < n; k++) if (presence.get(k).contains(true) && !alone.test(k)) oneLine = false;
        Map<Integer, List<Cluster>> deferred = new LinkedHashMap<>();
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < n; k++) order.add(k);
        order.sort((a, b) -> first[a] != first[b] ? Integer.compare(first[a], first[b]) : Integer.compare(a, b));
        for (int k : order) {
            if (!presence.get(k).contains(true)) continue;
            List<Cluster> fits = new ArrayList<>();
            for (Cluster c : clusters) {
                if (c.accepts(parts.get(k), presence.get(k), clefs.get(k)) && c.namedLike(parts.get(k))) fits.add(c);
            }
            // One staff group per system: complementary parts with the same staves and clefs
            // are the same part when one of the names is unreadable.
            List<Cluster> single = new ArrayList<>();
            for (Cluster c : clusters) {
                if (!c.accepts(parts.get(k), presence.get(k), clefs.get(k))) continue;
                boolean allReadable = c.parts.stream().allMatch(Parts::readable);
                if (!(readable(parts.get(k)) && allReadable)) single.add(c);
            }
            if (fits.isEmpty() && oneLine && single.size() == 1) fits = single;
            if (fits.size() == 1) {
                join.accept(k, fits.get(0));
            } else if (!fits.isEmpty() || (!clusters.isEmpty() && !readable(parts.get(k)))) {
                deferred.put(k, fits);
            } else {
                start.accept(k);
            }
        }
        // Ambiguous or unreadable names: place by the staves above and below, repeating
        // while that places more parts.
        boolean placed = true;
        while (!deferred.isEmpty() && placed) {
            placed = false;
            for (Map.Entry<Integer, List<Cluster>> e : new ArrayList<>(deferred.entrySet())) {
                int k = e.getKey();
                Cluster target = byNeighbour.apply(k);
                if (target != null && (e.getValue().isEmpty() || containsSame(e.getValue(), target))) {
                    join.accept(k, target);
                    deferred.remove(k);
                    placed = true;
                }
            }
        }
        // A whole system of parts with one name: match them to the candidates in order, top to bottom.
        java.util.function.IntFunction<double[]> top = k -> {
            Staff staff = parts.get(k).staves.get(0);
            int i = first[k];
            BBox box = i < staff.measures.size() ? staff.measures.get(i).bbox : null;
            return new double[] {box != null ? box.y : Double.POSITIVE_INFINITY, k};
        };
        java.util.Comparator<double[]> byTop = (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]);
        Map<Integer, List<Integer>> systems = new LinkedHashMap<>();
        for (int k : deferred.keySet()) systems.computeIfAbsent(first[k], x -> new ArrayList<>()).add(k);
        for (List<Integer> ks : systems.values()) {
            List<Cluster> common = new ArrayList<>();
            for (Cluster c : clusters) {
                boolean all = true;
                for (int k : ks) all &= containsSame(deferred.get(k), c);
                if (all) common.add(c);
            }
            if (ks.size() > 1 && common.size() == ks.size()) {
                List<Cluster> ordered = new ArrayList<>(common);
                ordered.sort((a, b) -> byTop.compare(top.apply(a.members.get(0)), top.apply(b.members.get(0))));
                List<Integer> sortedKs = new ArrayList<>(ks);
                sortedKs.sort((a, b) -> byTop.compare(top.apply(a), top.apply(b)));
                for (int i = 0; i < sortedKs.size(); i++) {
                    join.accept(sortedKs.get(i), ordered.get(i));
                    deferred.remove(sortedKs.get(i));
                }
            }
        }
        for (int k : deferred.keySet()) start.accept(k);
        return clusters;
    }

    private static String display(Part part) {
        String n = part.name != null && !part.name.isEmpty() ? part.name
                : part.abbreviation != null && !part.abbreviation.isEmpty() ? part.abbreviation : "unnamed";
        return part.id + " (" + n + ")";
    }

    public static List<Repair> mergeSplitParts(Score score) {
        List<Repair> repairs = new ArrayList<>();
        List<Cluster> clusters = new ArrayList<>();
        for (Cluster c : cluster(score)) if (c.parts.size() > 1) clusters.add(c);
        if (clusters.isEmpty()) return repairs;
        for (Cluster c : clusters) {
            Part rep = c.parts.get(0);
            List<Part> others = c.parts.subList(1, c.parts.size());
            List<String> moved = new ArrayList<>();
            for (Part other : others) {
                List<Boolean> present = presence(other);
                if (rep.staves.size() != other.staves.size()) throw new IllegalArgumentException("zip() argument lengths differ");
                for (int s = 0; s < rep.staves.size(); s++) {
                    Staff staff = rep.staves.get(s), src = other.staves.get(s);
                    for (int i = 0; i < src.measures.size(); i++) {
                        if (!present.get(i)) continue;
                        Measure m = src.measures.get(i);
                        for (Voice v : m.voices) {
                            for (Event e : v.events) e.provenance.add(Repair.provenance(RULE, "part", other.id));
                        }
                        staff.measures.set(i, m);
                        moved.add(Repair.label(m));
                    }
                }
            }
            List<String> names = new ArrayList<>();
            for (Part p : c.parts) {
                for (String x : new String[] {p.name, p.abbreviation}) {
                    if (x != null && !x.isEmpty() && label(x) != null) names.add(x);
                }
            }
            if (!names.isEmpty()) {
                String longest = names.get(0), shortest = names.get(0);
                for (String x : names) {
                    if (x.codePointCount(0, x.length()) > longest.codePointCount(0, longest.length())) longest = x;
                    if (x.codePointCount(0, x.length()) < shortest.codePointCount(0, shortest.length())) shortest = x;
                }
                rep.name = longest;
                rep.abbreviation = shortest;
            }
            List<String> otherNames = new ArrayList<>();
            for (Part p : others) otherNames.add(display(p));
            String detail = "merged " + String.join(", ", otherNames) + " into " + display(rep);
            score.provenance.add(Repair.provenance(RULE, "part", rep.id, "staff", rep.staves.get(0).number, "detail", detail));
            repairs.add(new Repair(RULE, rep.id, null, detail, new ArrayList<>(new LinkedHashSet<>(moved))));
        }
        score.parts = pageOrder(score, clusters);
        return repairs;
    }

    /** The merged parts in the order their staves appear down each system. */
    static List<Part> pageOrder(Score score, List<Cluster> clusters) {
        Map<Integer, Part> home = new HashMap<>();
        for (Cluster c : clusters) for (int k : c.members) home.put(k, c.parts.get(0));
        List<List<Boolean>> presence = new ArrayList<>();
        for (Part p : score.parts) presence.add(presence(p));
        Map<Part, int[]> key = new IdentityHashMap<>();
        List<Part> nodes = new ArrayList<>();
        for (int k = 0; k < score.parts.size(); k++) {
            Part node = home.getOrDefault(k, score.parts.get(k));
            if (!key.containsKey(node)) nodes.add(node);
            int first = firstPresent(presence.get(k), presence.get(k).size());
            int[] mine = {first, k};
            int[] old = key.get(node);
            if (old == null || compare(mine, old) < 0) key.put(node, mine);
        }
        Map<Part, Set<Part>> after = new IdentityHashMap<>();
        for (Part p : nodes) after.put(p, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        int columns = 0;
        for (List<Boolean> pr : presence) columns = Math.max(columns, pr.size());
        for (int i = 0; i < columns; i++) {
            List<Part> column = new ArrayList<>();
            for (int k = 0; k < score.parts.size(); k++) {
                if (i < presence.get(k).size() && presence.get(k).get(i)) column.add(home.getOrDefault(k, score.parts.get(k)));
            }
            for (int j = 0; j + 1 < column.size(); j++) {
                if (column.get(j) != column.get(j + 1)) after.get(column.get(j + 1)).add(column.get(j));
            }
        }
        List<Part> ordered = new ArrayList<>();
        Set<Part> left = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        left.addAll(nodes);
        while (!left.isEmpty()) {
            List<Part> ready = new ArrayList<>();
            for (Part p : left) {
                boolean free = true;
                for (Part q : after.get(p)) if (left.contains(q)) free = false;
                if (free) ready.add(p);
            }
            if (ready.isEmpty()) ready.addAll(left); // a cycle: break it
            Part best = null;
            for (Part p : ready) if (best == null || compare(key.get(p), key.get(best)) < 0) best = p;
            ordered.add(best);
            left.remove(best);
        }
        return ordered;
    }

    private static int compare(int[] a, int[] b) {
        return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
    }
}
