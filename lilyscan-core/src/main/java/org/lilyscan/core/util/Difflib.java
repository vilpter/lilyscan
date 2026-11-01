package org.lilyscan.core.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Python {@code difflib.SequenceMatcher.ratio()} and {@code get_close_matches} for strings, as
 * CPython computes them (no junk; the "popular element" autojunk only applies to sequences of 200
 * or more, which the callers' words never are, but it is implemented too).
 */
public final class Difflib {
    private Difflib() {}

    /** {@code SequenceMatcher(None, a, b).ratio()}. */
    public static double ratio(String a, String b) {
        int[] x = a.codePoints().toArray();
        int[] y = b.codePoints().toArray();
        int total = x.length + y.length;
        if (total == 0) {
            return 1.0;
        }
        return 2.0 * matches(x, y) / total;
    }

    /** {@code get_close_matches(word, possibilities, n, cutoff)}. */
    public static List<String> closeMatches(String word, List<String> possibilities, int n, double cutoff) {
        List<double[]> scored = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String x : possibilities) {
            // set_seq2(word), set_seq1(x): a is the candidate, b the word.
            double r = ratio(x, word);
            if (r >= cutoff) {
                scored.add(new double[] {r, names.size()});
                names.add(x);
            }
        }
        // heapq.nlargest(n, result): by score, then by the string (tuples (score, x)).
        scored.sort((p, q) -> {
            int c = Double.compare(q[0], p[0]);
            return c != 0 ? c : names.get((int) q[1]).compareTo(names.get((int) p[1]));
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, scored.size()); i++) {
            out.add(names.get((int) scored.get(i)[1]));
        }
        return out;
    }

    private static int matches(int[] a, int[] b) {
        Map<Integer, List<Integer>> b2j = new HashMap<>();
        for (int j = 0; j < b.length; j++) {
            b2j.computeIfAbsent(b[j], k -> new ArrayList<>()).add(j);
        }
        java.util.Set<Integer> popular = new java.util.HashSet<>();
        if (b.length >= 200) {
            int ntest = b.length / 100 + 1;
            for (Map.Entry<Integer, List<Integer>> e : b2j.entrySet()) {
                if (e.getValue().size() > ntest) {
                    popular.add(e.getKey());
                }
            }
            for (int p : popular) {
                b2j.remove(p);
            }
        }
        int total = 0;
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[] {0, a.length, 0, b.length});
        while (!queue.isEmpty()) {
            int[] q = queue.pop();
            int alo = q[0];
            int ahi = q[1];
            int blo = q[2];
            int bhi = q[3];
            int[] m = longest(a, b, b2j, alo, ahi, blo, bhi);
            int i = m[0];
            int j = m[1];
            int k = m[2];
            if (k > 0) {
                total += k;
                if (alo < i && blo < j) {
                    queue.push(new int[] {alo, i, blo, j});
                }
                if (i + k < ahi && j + k < bhi) {
                    queue.push(new int[] {i + k, ahi, j + k, bhi});
                }
            }
        }
        return total;
    }

    /** {@code find_longest_match} (no junk; popular elements are extended into as CPython does). */
    private static int[] longest(int[] a, int[] b, Map<Integer, List<Integer>> b2j, int alo, int ahi, int blo, int bhi) {
        int besti = alo;
        int bestj = blo;
        int bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<>();
            for (int j : b2j.getOrDefault(a[i], List.of())) {
                if (j < blo) {
                    continue;
                }
                if (j >= bhi) {
                    break;
                }
                int k = j2len.getOrDefault(j - 1, 0) + 1;
                newj2len.put(j, k);
                if (k > bestsize) {
                    besti = i - k + 1;
                    bestj = j - k + 1;
                    bestsize = k;
                }
            }
            j2len = newj2len;
        }
        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] == b[bestj + bestsize]) {
            bestsize++;
        }
        return new int[] {besti, bestj, bestsize};
    }
}
