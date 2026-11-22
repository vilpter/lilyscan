package org.lilyscan.eval;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import org.lilyscan.core.ir.BBox;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.util.Py;

/**
 * The error taxonomy of raw engine output: every difference between the engine's score (before
 * Lilyscan's repairs) and the ground truth, sorted into categories per staff-measure, and the
 * categories ranked by how many pieces they affect. It reads cached engine output only.
 *
 * <p>Staves are matched by order and measures aligned as the scorer aligns them ({@link
 * Compare#align}). In an aligned measure the notes (one per note head) are matched in turn:
 * exactly; by onset and pitch ({@code duration}, {@code tuplet}); by onset ({@code accidental},
 * {@code pitch}, or {@code consistent-shift} when at least three paired notes of the measure all
 * moved by the same number of steps, as under a wrong clef); by pitch and duration at another
 * onset ({@code onset}). Of what is left, notes at an onset where the two have a different number
 * of notes are {@code chord-size} (voices are not told apart, so a note of another voice starting
 * with a chord counts here too); the others are {@code note-missing} or {@code note-extra}. Rests
 * and voice counts are compared separately. Grace notes are left out.
 */
public final class Taxonomy {
    private Taxonomy() {}

    /** One difference. {@code count} is the number of notes or rests involved. */
    public record Instance(String category, String item, String variant, int staff, String staffLabel, String measure,
            Integer predMeasure, int count, String detail, BBox bbox) {}

    record Note(Frac offset, Pitch pitch, Frac duration) {
        Pitch.Key key() {
            return pitch.key();
        }
    }

    private static String label(Pitch p) {
        return p.label();
    }

    private static Pitch pitch(Pitch.Key k) {
        return new Pitch(k.step(), k.alter(), k.octave());
    }

    private static List<Note> notes(Compare.MeasureSig sig) {
        List<Note> out = new ArrayList<>();
        for (Compare.Token t : sig.events()) {
            if (t.grace() || t.pitches() == null) continue;
            for (Pitch.Key k : t.pitches()) out.add(new Note(t.offset(), pitch(k), t.duration()));
        }
        return out;
    }

    record Rest(Frac offset, Frac duration) {}

    private static List<Rest> rests(Compare.MeasureSig sig) {
        List<Rest> out = new ArrayList<>();
        for (Compare.Token t : sig.events()) if (!t.grace() && t.pitches() == null) out.add(new Rest(t.offset(), t.duration()));
        return out;
    }

    /** Whether a duration is a tuplet value (its denominator has a factor other than 2). */
    static boolean tupletValue(Frac d) {
        long q = d.denominator();
        while (q % 2 == 0) q /= 2;
        return q != 1;
    }

    /** Removes the first element of {@code b} matching {@code a} by {@code same}, returning it. */
    private static <T> T take(List<T> b, java.util.function.Predicate<T> same) {
        for (Iterator<T> it = b.iterator(); it.hasNext();) {
            T x = it.next();
            if (same.test(x)) {
                it.remove();
                return x;
            }
        }
        return null;
    }

    /** The differences of one aligned measure pair. */
    static List<Instance> measure(Compare.MeasureSig gt, Compare.MeasureSig pred, Context c) {
        List<Instance> out = new ArrayList<>();
        if (gt.voices().equals(pred.voices())) return out;
        List<Note> g = notes(gt), p = notes(pred);
        // Chords whose size differs, from all the notes at each onset both have.
        Map<Frac, Integer> sizeDiff = new TreeMap<>();
        Map<Frac, Integer> gCount = new TreeMap<>(), pCount = new TreeMap<>();
        for (Note n : g) gCount.merge(n.offset(), 1, Integer::sum);
        for (Note n : p) pCount.merge(n.offset(), 1, Integer::sum);
        for (var e : gCount.entrySet()) {
            Integer q = pCount.get(e.getKey());
            if (q != null && !q.equals(e.getValue())) sizeDiff.put(e.getKey(), e.getValue() - q);
        }
        // 1. exact
        for (Iterator<Note> it = g.iterator(); it.hasNext();) {
            Note n = it.next();
            if (take(p, x -> x.offset().equals(n.offset()) && x.key().equals(n.key()) && x.duration().equals(n.duration())) != null) it.remove();
        }
        // 2. same onset and pitch, another duration
        for (Iterator<Note> it = g.iterator(); it.hasNext();) {
            Note n = it.next();
            Note m = take(p, x -> x.offset().equals(n.offset()) && x.key().equals(n.key()));
            if (m == null) continue;
            it.remove();
            boolean tuplet = tupletValue(n.duration()) != tupletValue(m.duration());
            out.add(c.instance(tuplet ? "tuplet" : "duration", 1,
                    label(n.pitch()) + " at " + n.offset() + ": " + n.duration() + " read as " + m.duration()));
        }
        // 3. same onset, other pitches
        Map<Frac, List<Note>> gAt = new TreeMap<>(), pAt = new TreeMap<>();
        for (Note n : g) gAt.computeIfAbsent(n.offset(), k -> new ArrayList<>()).add(n);
        for (Note n : p) pAt.computeIfAbsent(n.offset(), k -> new ArrayList<>()).add(n);
        List<Note[]> pairs = new ArrayList<>();
        for (Frac at : new ArrayList<>(gAt.keySet())) {
            List<Note> a = gAt.get(at), b = pAt.get(at);
            if (b == null) continue;
            a.sort(Comparator.comparingInt(n -> n.pitch().midi()));
            b.sort(Comparator.comparingInt(n -> n.pitch().midi()));
            int n = Math.min(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                pairs.add(new Note[] {a.get(i), b.get(i)});
                g.remove(a.get(i));
                p.remove(b.get(i));
            }
        }
        Set<Integer> shifts = new HashSet<>();
        List<Note[]> stepPairs = new ArrayList<>();
        for (Note[] pr : pairs) {
            int shift = pr[1].pitch().diatonic() - pr[0].pitch().diatonic();
            if (shift == 0) {
                out.add(c.instance("accidental", 1, label(pr[0].pitch()) + " at " + pr[0].offset() + " read as " + label(pr[1].pitch())));
            } else {
                shifts.add(shift);
                stepPairs.add(pr);
            }
        }
        boolean consistent = stepPairs.size() >= 3 && shifts.size() == 1;
        if (consistent) {
            out.add(c.instance("consistent-shift", stepPairs.size(), stepPairs.size() + " notes all " + signed(shifts.iterator().next())
                    + " steps (e.g. " + label(stepPairs.get(0)[0].pitch()) + " read as " + label(stepPairs.get(0)[1].pitch()) + ")"));
        } else {
            for (Note[] pr : stepPairs) {
                out.add(c.instance("pitch", 1, label(pr[0].pitch()) + " at " + pr[0].offset() + " read as " + label(pr[1].pitch())));
            }
        }
        // 4. same pitch and duration, another onset
        for (Iterator<Note> it = g.iterator(); it.hasNext();) {
            Note n = it.next();
            Note m = take(p, x -> x.key().equals(n.key()) && x.duration().equals(n.duration()));
            if (m == null) continue;
            it.remove();
            out.add(c.instance("onset", 1, label(n.pitch()) + " at " + n.offset() + " read at " + m.offset()));
        }
        // 5. notes left at an onset whose chord size differs are that difference
        for (var e : sizeDiff.entrySet()) {
            Frac at = e.getKey();
            int d = e.getValue();
            List<Note> side = d > 0 ? g : p, absorbed = new ArrayList<>();
            for (Note n : new ArrayList<>(side)) {
                if (absorbed.size() == Math.abs(d)) break;
                if (n.offset().equals(at)) {
                    absorbed.add(n);
                    side.remove(n);
                }
            }
            if (!absorbed.isEmpty()) {
                out.add(c.instance("chord-size", absorbed.size(), "at " + at + ": " + gCount.get(at) + " notes read as "
                        + pCount.get(at) + " (" + (d > 0 ? "missing " : "extra ") + names(absorbed) + ")"));
            }
        }
        // 6. the rest
        if (!g.isEmpty()) out.add(c.instance("note-missing", g.size(), names(g)));
        if (!p.isEmpty()) out.add(c.instance("note-extra", p.size(), names(p)));
        // rests
        List<Rest> gr = rests(gt), prs = rests(pred);
        for (Iterator<Rest> it = gr.iterator(); it.hasNext();) {
            Rest r = it.next();
            if (take(prs, x -> x.equals(r)) != null) it.remove();
        }
        for (Iterator<Rest> it = gr.iterator(); it.hasNext();) {
            Rest r = it.next();
            Rest m = take(prs, x -> x.offset().equals(r.offset()));
            if (m == null) continue;
            it.remove();
            out.add(c.instance("rest-duration", 1, "rest at " + r.offset() + ": " + r.duration() + " read as " + m.duration()));
        }
        if (!gr.isEmpty()) out.add(c.instance("rest-missing", gr.size(), restNames(gr)));
        if (!prs.isEmpty()) out.add(c.instance("rest-extra", prs.size(), restNames(prs)));
        // voices
        int gv = gt.voices().size(), pv = pred.voices().size();
        if (pv > gv) out.add(c.instance("voice-extra", pv - gv, gv + " voices read as " + pv));
        if (pv < gv) out.add(c.instance("voice-merged", gv - pv, gv + " voices read as " + pv));
        if (out.isEmpty()) out.add(c.instance("voice-split", 1, "same notes and rests, assigned to voices differently"));
        return out;
    }

    private static String signed(int n) {
        return n > 0 ? "+" + n : String.valueOf(n);
    }

    private static String names(List<Note> ns) {
        List<String> s = new ArrayList<>();
        for (Note n : ns) s.add(label(n.pitch()) + "@" + n.offset() + "(" + n.duration() + ")");
        return String.join(" ", s);
    }

    private static String restNames(List<Rest> rs) {
        List<String> s = new ArrayList<>();
        for (Rest r : rs) s.add("r@" + r.offset() + "(" + r.duration() + ")");
        return String.join(" ", s);
    }

    /** Where the instances of one measure pair are. */
    static final class Context {
        final String item, variant, staffLabel, measure;
        final int staff;
        final Integer predMeasure;
        final BBox bbox;

        Context(String item, String variant, int staff, String staffLabel, String measure, Integer predMeasure, BBox bbox) {
            this.item = item;
            this.variant = variant;
            this.staff = staff;
            this.staffLabel = staffLabel;
            this.measure = measure;
            this.predMeasure = predMeasure;
            this.bbox = bbox;
        }

        Instance instance(String category, int count, String detail) {
            return new Instance(category, item, variant, staff, staffLabel, measure, predMeasure, count, detail, bbox);
        }
    }

    /** Every difference between a ground truth and the engine's score (null: the engine failed). */
    public static List<Instance> classify(String item, String variant, Score gt, Score pred) {
        List<Instance> out = new ArrayList<>();
        List<Score.PartStaff> gtStaves = gt.staves();
        if (pred == null) {
            out.add(new Instance("engine-failure", item, variant, -1, null, null, null, 1, "no score", null));
            return out;
        }
        List<List<Compare.MeasureSig>> gs = Compare.staffSigs(gt), ps = Compare.staffSigs(pred);
        List<Score.PartStaff> predStaves = pred.staves();
        for (int k = 0; k < Math.max(gs.size(), ps.size()); k++) {
            if (k >= ps.size()) {
                String l = label(gtStaves.get(k));
                out.add(new Instance("staff-missing", item, variant, k, l, null, null, gs.get(k).size(), "staff not read", null));
                continue;
            }
            if (k >= gs.size()) {
                out.add(new Instance("staff-extra", item, variant, k, null, null, null, ps.get(k).size(), "staff not in the ground truth", null));
                continue;
            }
            List<Measure> gm = gtStaves.get(k).staff().measures, pm = predStaves.get(k).staff().measures;
            String l = label(gtStaves.get(k));
            for (Integer[] pair : Compare.align(gs.get(k), ps.get(k))) {
                Integer gi = pair[0], pi = pair[1];
                if (pi == null) {
                    out.add(new Instance("measure-missing", item, variant, k, l, gm.get(gi).number, null,
                            gs.get(k).get(gi).events().size(), "measure not read", null));
                } else if (gi == null) {
                    out.add(new Instance("measure-extra", item, variant, k, l, null, pi,
                            ps.get(k).get(pi).events().size(), "measure not in the ground truth", pm.get(pi).bbox));
                } else {
                    Context c = new Context(item, variant, k, l, gm.get(gi).number, pi, pm.get(pi).bbox);
                    out.addAll(measure(gs.get(k).get(gi), ps.get(k).get(pi), c));
                }
            }
        }
        return out;
    }

    private static String label(Score.PartStaff ps) {
        return ps.part().id + "/" + ps.staff().number;
    }

    // ---- the run ----

    /** What to classify: the corpus items, the variants, and the engine cache an {@code eval run} filled. */
    public record Options(List<String> variants, boolean prepare, int crops) {}

    /** The ranking of one category. */
    public record Rank(String category, int instances, int count, int pieces, int itemVariants, double topThreeShare,
            List<String> topPieces) {}

    public record Report(List<Rank> ranking, List<Instance> instances, List<String> skipped) {}

    public static Report run(List<Corpus.Item> items, Path work, Settings settings, Options o) throws IOException {
        List<Instance> all = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Corpus.Item item : items) {
            Score gt = MusicXml.load(item.groundTruth(), "ground-truth");
            for (String v : o.variants()) {
                Harness.Cached cached = Harness.cachedEngineOutput(item, v, work, settings, o.prepare());
                if (cached == null) {
                    skipped.add(item.id() + "/" + v);
                    continue;
                }
                all.addAll(classify(item.id(), v, gt, cached.score()));
            }
        }
        return new Report(rank(all), all, skipped);
    }

    /** Categories ordered by pieces affected, then by notes involved. */
    static List<Rank> rank(List<Instance> all) {
        Map<String, List<Instance>> by = new LinkedHashMap<>();
        for (Instance i : all) by.computeIfAbsent(i.category(), k -> new ArrayList<>()).add(i);
        List<Rank> out = new ArrayList<>();
        for (var e : by.entrySet()) {
            Map<String, Integer> perPiece = new HashMap<>();
            Set<String> iv = new LinkedHashSet<>();
            int count = 0;
            for (Instance i : e.getValue()) {
                perPiece.merge(i.item(), i.count(), Integer::sum);
                iv.add(i.item() + "/" + i.variant());
                count += i.count();
            }
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(perPiece.entrySet());
            sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            int top = 0;
            List<String> topPieces = new ArrayList<>();
            for (int k = 0; k < Math.min(3, sorted.size()); k++) {
                top += sorted.get(k).getValue();
                topPieces.add(sorted.get(k).getKey() + " " + sorted.get(k).getValue());
            }
            out.add(new Rank(e.getKey(), e.getValue().size(), count, perPiece.size(), iv.size(),
                    count == 0 ? 0 : (double) top / count, topPieces));
        }
        out.sort(Comparator.comparingInt(Rank::pieces).reversed().thenComparing(Comparator.comparingInt(Rank::count).reversed()));
        return out;
    }

    /**
     * Writes {@code taxonomy.json}, {@code taxonomy.md} and, for up to {@code crops} instances per
     * category (one per piece first), the engine's source image cropped to the measure.
     */
    public static Path write(Report r, List<Corpus.Item> items, Path work, Options o, Path outDir, String notes) throws IOException {
        Files.createDirectories(outDir);
        Map<String, Corpus.Item> byId = new HashMap<>();
        for (Corpus.Item i : items) byId.put(i.id(), i);
        Map<Instance, String> crops = new HashMap<>();
        if (o.crops() > 0) crops = crops(r, byId, work, o, outDir.resolve("crops"));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("notes", notes);
        json.put("variants", o.variants());
        json.put("items", items.size());
        json.put("skipped", r.skipped());
        List<Object> ranking = new ArrayList<>();
        for (Rank k : r.ranking()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", k.category());
            m.put("pieces", k.pieces());
            m.put("item_variants", k.itemVariants());
            m.put("instances", k.instances());
            m.put("count", k.count());
            m.put("top3_share", Py.round(k.topThreeShare(), 3));
            m.put("top_pieces", k.topPieces());
            ranking.add(m);
        }
        json.put("ranking", ranking);
        List<Object> instances = new ArrayList<>();
        for (Instance i : r.instances()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", i.category());
            m.put("item", i.item());
            m.put("variant", i.variant());
            m.put("staff", i.staffLabel());
            m.put("measure", i.measure());
            m.put("pred_measure", i.predMeasure());
            m.put("count", i.count());
            m.put("detail", i.detail());
            if (crops.containsKey(i)) m.put("crop", crops.get(i));
            instances.add(m);
        }
        json.put("instances", instances);
        IrJson.mapper().writerWithDefaultPrettyPrinter().writeValue(outDir.resolve("taxonomy.json").toFile(), json);

        StringBuilder md = new StringBuilder("# Engine error taxonomy\n\n");
        if (!notes.isEmpty()) md.append(notes).append("\n\n");
        md.append(items.size()).append(" pieces, variants ").append(String.join(", ", o.variants()))
                .append(". Raw engine output (no repairs) against the ground truth, per staff-measure; ranked by pieces affected.\n\n");
        if (!r.skipped().isEmpty()) md.append("Not in the engine cache (skipped): ").append(String.join(", ", r.skipped())).append("\n\n");
        md.append("| Category | Pieces | Item-variants | Instances | Notes/rests | Top-3 share | Top pieces |\n|---|---|---|---|---|---|---|\n");
        for (Rank k : r.ranking()) {
            md.append("| ").append(k.category()).append(" | ").append(k.pieces()).append(" | ").append(k.itemVariants()).append(" | ")
                    .append(k.instances()).append(" | ").append(k.count()).append(" | ")
                    .append(Math.round(k.topThreeShare() * 100)).append("% | ").append(String.join(", ", k.topPieces())).append(" |\n");
        }
        if (!crops.isEmpty()) {
            md.append("\n## Examples\n");
            Map<String, List<Instance>> byCat = new LinkedHashMap<>();
            for (Rank k : r.ranking()) byCat.put(k.category(), new ArrayList<>());
            for (Instance i : r.instances()) if (crops.containsKey(i)) byCat.get(i.category()).add(i);
            for (var e : byCat.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                md.append("\n### ").append(e.getKey()).append("\n\n");
                for (Instance i : e.getValue()) {
                    md.append("- ").append(i.item()).append(" ").append(i.variant()).append(", ").append(i.staffLabel())
                            .append(" m. ").append(i.measure()).append(": ").append(i.detail()).append(" ![](").append(crops.get(i)).append(")\n");
                }
            }
        }
        Path p = outDir.resolve("taxonomy.md");
        Files.writeString(p, md.toString());
        return p;
    }

    private static Map<Instance, String> crops(Report r, Map<String, Corpus.Item> byId, Path work, Options o, Path dir) throws IOException {
        Map<Instance, String> out = new HashMap<>();
        Map<String, List<Instance>> byCat = new LinkedHashMap<>();
        for (Instance i : r.instances()) if (i.bbox() != null) byCat.computeIfAbsent(i.category(), k -> new ArrayList<>()).add(i);
        Map<Path, BufferedImage> images = new HashMap<>();
        for (var e : byCat.entrySet()) {
            // One per piece first, then the rest, so the examples cover the pieces.
            List<Instance> order = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Instance i : e.getValue()) if (seen.add(i.item())) order.add(i);
            for (Instance i : e.getValue()) if (!order.contains(i)) order.add(i);
            int n = 0;
            for (Instance i : order) {
                if (n >= o.crops()) break;
                Corpus.Item item = byId.get(i.item());
                Path engineDir = work.resolve(item.id()).resolve(o.prepare() && Harness.PREPARED_VARIANTS.contains(i.variant())
                        ? i.variant() + "-prepared" : i.variant());
                Path src = Harness.engineImage(item, i.variant(), engineDir, o.prepare());
                if (src == null || !Files.isRegularFile(src) || i.bbox().page != 0) continue;
                BufferedImage img = images.computeIfAbsent(src, Taxonomy::read);
                if (img == null) continue;
                BBox b = i.bbox();
                int padY = (int) Math.round(b.h * 1.0), padX = 40;
                int x0 = clamp((int) b.x - padX, img.getWidth()), y0 = clamp((int) b.y - padY, img.getHeight());
                int x1 = clamp((int) (b.x + b.w) + padX, img.getWidth()), y1 = clamp((int) (b.y + b.h) + padY, img.getHeight());
                if (x1 - x0 < 4 || y1 - y0 < 4) continue;
                String name = e.getKey() + "/" + i.item() + "-" + i.variant() + "-" + i.staffLabel().replace('/', '_') + "-m" + i.measure() + ".png";
                Path f = dir.resolve(name);
                Files.createDirectories(f.getParent());
                ImageIO.write(img.getSubimage(x0, y0, x1 - x0, y1 - y0), "png", f.toFile());
                out.put(i, "crops/" + name);
                n++;
            }
        }
        return out;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max));
    }

    private static BufferedImage read(Path p) {
        try {
            return ImageIO.read(p.toFile());
        } catch (IOException e) {
            return null;
        }
    }
}
