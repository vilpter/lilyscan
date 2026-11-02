package org.lilyscan.core.combine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lilyscan.core.Pipeline;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Ir;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Transpose;
import org.lilyscan.core.ir.Transpose.Interval;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.lilypond.Generate;
import org.lilyscan.core.lilypond.LyProject;
import org.lilyscan.core.lilypond.Notation;
import org.lilyscan.core.repair.Keys;
import org.lilyscan.core.runtime.Settings;

/**
 * Score combiner (port of {@code lilyscan/combine.py}): parts from finished jobs as one new
 * score. The project is built from each part's own LilyPond source, so edits made in the review
 * carry over: the generator plans the new score, then each part's file is copied from its job
 * with its variables renamed. A transposed part is wrapped in LilyPond's {@code \transpose}.
 */
public final class Combine {
    private Combine() {}

    /** A part that has no measure for more than this share of the score is not lined up. */
    static final double MAX_FILLED = 0.25;

    /** A request that cannot be combined (Python {@code CombineError}). */
    public static final class CombineException extends IllegalArgumentException {
        public CombineException(String message) {
            super(message);
        }
    }

    /**
     * One chosen part: a finished job's folder, the part id in that job's IR, an interval
     * ({@code "M2"}, {@code "-m3"}) or {@code "concert"} (or null), and a new name (or null).
     */
    public record Selection(Path job, String part, String transpose, String name) {}

    private static final Map<String, String> SHORT_NAMES = new LinkedHashMap<>();
    static {
        SHORT_NAMES.put("violin", "Vln.");
        SHORT_NAMES.put("viola", "Vla.");
        SHORT_NAMES.put("violoncello", "Vc.");
        SHORT_NAMES.put("cello", "Vc.");
        SHORT_NAMES.put("double bass", "Cb.");
        SHORT_NAMES.put("contrabass", "Cb.");
        SHORT_NAMES.put("bass", "Cb.");
        SHORT_NAMES.put("flute", "Fl.");
        SHORT_NAMES.put("piano", "Pno.");
    }
    private static final Pattern INSTRUMENT;
    static {
        List<String> names = new ArrayList<>(SHORT_NAMES.keySet());
        names.sort((a, b) -> Integer.compare(b.length(), a.length())); // sorted(key=len, reverse=True): stable
        INSTRUMENT = Pattern.compile("\\s*(" + String.join("|", names) + ")\\b\\s*(.*)$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    }
    private static final Pattern TRANSPOSITION = Pattern.compile("^[ \\t]*\\\\transposition\\s+\\S+[ \\t]*\\n",
            Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);

    /** The part under a new name, with a matching short name ("Violin 1" gets "Vln. 1"). */
    static Part renamed(Part part, String name) {
        if (name == null || name.isEmpty()) return part;
        Matcher m = INSTRUMENT.matcher(name);
        String shortName = null;
        if (m.lookingAt()) {
            String base = SHORT_NAMES.get(m.group(1).toLowerCase(java.util.Locale.ROOT));
            String rest = org.lilyscan.core.util.Py.strip(m.group(2));
            shortName = rest.isEmpty() ? base : base + " " + rest;
        }
        Part out = Ir.copy(part);
        out.name = name;
        out.abbreviation = shortName;
        return out;
    }

    private static Score load(Path job) {
        Path path = job.resolve("ir").resolve("score.json");
        if (!Files.isRegularFile(path)) throw new CombineException(job.getFileName() + " has no finished score");
        return IrJson.readScore(path);
    }

    /** Renames LilyPond identifiers (whole words only); alternatives tried in the map's order. */
    static String rename(String text, Map<String, String> names) {
        if (names.isEmpty()) return text;
        List<String> alts = new ArrayList<>();
        for (String k : names.keySet()) alts.add(Pattern.quote(k));
        Pattern p = Pattern.compile("(?<![A-Za-z])(" + String.join("|", alts) + ")(?![A-Za-z])");
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(names.get(m.group(1))));
        m.appendTail(sb);
        return sb.toString();
    }

    /** Wraps each use of these music variables in {@code \transpose c' <to>}. */
    static String transposed(String layout, List<String> variables, String to) {
        for (String var : variables) {
            String wrapped = "\\transpose c' " + to + " \\" + var;
            layout = Pattern.compile("\\\\" + var + "(?![A-Za-z])").matcher(layout).replaceAll(Matcher.quoteReplacement(wrapped));
        }
        return layout;
    }

    static Interval interval(Selection sel, Part part) {
        if (sel.transpose() == null || sel.transpose().isEmpty()) return null;
        if (sel.transpose().equals("concert")) {
            // Written to sounding: a part in B-flat (-2) sounds a major second lower.
            int semitones = part.transposeSemitones == null ? 0 : part.transposeSemitones;
            return semitones != 0 ? Interval.fromSemitones(semitones) : null;
        }
        return Interval.parse(sel.transpose());
    }

    private static Map<String, String> names(Generate.PartVars old, Generate.PartVars nw) {
        if (old.staves.size() != nw.staves.size()) throw new IllegalArgumentException("zip() argument lengths differ");
        Map<String, String> out = new LinkedHashMap<>();
        for (int s = 0; s < old.staves.size(); s++) {
            Generate.StaffVars a = old.staves.get(s), b = nw.staves.get(s);
            out.put(a.music, b.music);
            for (int i = 0; i < Math.min(a.lyrics.size(), b.lyrics.size()); i++) out.put(a.lyrics.get(i), b.lyrics.get(i));
        }
        out.entrySet().removeIf(e -> e.getKey().equals(e.getValue()));
        return out;
    }

    private static Part findPart(Score score, Selection sel) {
        for (Part p : score.parts) if (p.id.equals(sel.part())) return p;
        throw new CombineException(sel.job().getFileName() + " has no part " + sel.part());
    }

    private static Map<String, Object> source(Selection sel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job", sel.job().getFileName().toString());
        m.put("part", sel.part());
        m.put("transpose", sel.transpose());
        return m;
    }

    /**
     * Writes the combined job (IR, LilyPond project, QA report) under {@code out}. {@code piano}:
     * parts reduced onto a piano grand staff below the chosen parts.
     */
    public static Map<String, Object> combine(List<Selection> selections, Path out, String title, Settings settings,
            List<Selection> piano) {
        if (selections.isEmpty()) throw new CombineException("choose at least one part");
        piano = piano == null ? List.of() : piano;
        List<Score> sources = new ArrayList<>();
        for (Selection sel : selections) sources.add(load(sel.job()));
        List<Score> reduced = new ArrayList<>();
        for (Selection sel : piano) reduced.add(load(sel.job()));
        Score combined = new Score();
        combined.title = title != null && !title.isEmpty() ? title : "Combined score";
        combined.source = "combined";
        List<Interval> intervals = new ArrayList<>();
        for (int k = 0; k < selections.size(); k++) {
            Selection sel = selections.get(k);
            Part part = findPart(sources.get(k), sel);
            Interval interval = interval(sel, part);
            if (interval != null) part = Transpose.transposePart(part, interval);
            if ("concert".equals(sel.transpose())) {
                part = Ir.copy(part);
                part.transposeSemitones = null;
            }
            intervals.add(interval);
            Part copy = Ir.deepCopy(part);
            copy.id = "P" + (k + 1);
            part = renamed(copy, sel.name());
            for (Staff staff : part.staves) {
                for (Measure m : staff.measures) {
                    m.bbox = null; // boxes belong to the source job's pages
                    for (Voice v : m.voices) for (Event e : v.events) e.bbox = null;
                }
            }
            combined.parts.add(part);
        }
        List<Part> accompanying = new ArrayList<>();
        for (int k = 0; k < piano.size(); k++) {
            Selection sel = piano.get(k);
            Part part = findPart(reduced.get(k), sel);
            Interval interval = interval(sel, part);
            Part moved = interval != null ? Transpose.transposePart(part, interval) : part;
            accompanying.add(renamed(moved, sel.name()));
        }
        // Parts read separately seldom have exactly the same measures: line them up.
        List<Part> everything = new ArrayList<>(combined.parts);
        everything.addAll(accompanying);
        Align.Result aligned = Align.alignParts(everything);
        Align.Alignment alignment = aligned.alignment();
        List<String> names = new ArrayList<>();
        for (Part p : everything) names.add(p.name != null && !p.name.isEmpty() ? p.name : p.id);
        for (int k = 0; k < alignment.filled.size(); k++) {
            List<Integer> gaps = alignment.filled.get(k);
            if (gaps.size() > MAX_FILLED * alignment.measures) {
                throw new CombineException(names.get(k) + " has " + gaps.size() + " of " + alignment.measures
                        + " measures that do not line up with " + names.get(alignment.reference) + ": is it the same piece?");
            }
        }
        List<Part> linedUp = aligned.parts();
        int n = combined.parts.size();
        combined.parts = new ArrayList<>(linedUp.subList(0, n));
        accompanying = new ArrayList<>(linedUp.subList(n, linedUp.size()));
        // One or two parts reading another key than the rest take the others' key; a part the
        // user transposes, or one that may be a transposing instrument, keeps its own.
        List<Selection> all = new ArrayList<>(selections);
        all.addAll(piano);
        List<Boolean> keeps = new ArrayList<>();
        for (int k = 0; k < linedUp.size(); k++) {
            Part p = linedUp.get(k);
            keeps.add(all.get(k).transpose() != null || Keys.mayTranspose(p, p.staves.get(0)));
        }
        Map<Integer, List<String>> rekeyed = Keys.ensembleKeys(linedUp, keeps);
        // A part with rests put in, or respelled, no longer matches its own LilyPond source.
        Set<Integer> regenerated = new HashSet<>();
        for (int k = 0; k < sources.size(); k++) if (!alignment.filled.get(k).isEmpty()) regenerated.add(k);
        for (int k : rekeyed.keySet()) if (k < sources.size()) regenerated.add(k);

        if (!accompanying.isEmpty()) combined.parts.add(Arrange.reduceToPiano(accompanying, combined.parts.get(0), "Piano"));
        LyProject generated = Generate.generateProject(combined);
        List<Generate.PartVars> plans = Generate.plan(combined);
        Map<String, String> files = new LinkedHashMap<>(generated.files());
        // The piano part, if any, is generated from the IR: it has no source of its own.
        for (int k = 0; k < sources.size(); k++) {
            if (regenerated.contains(k)) continue;
            Selection sel = selections.get(k);
            Generate.PartVars plan = plans.get(k);
            Generate.PartVars old = null;
            for (Generate.PartVars pv : Generate.plan(sources.get(k))) {
                if (pv.part.id.equals(sel.part())) {
                    old = pv;
                    break;
                }
            }
            Path src = sel.job().resolve("ly").resolve("parts").resolve(old.slug + ".ly");
            if (!Files.isRegularFile(src)) continue; // keep the part generated from the (already transposed) IR
            // The part as the user left it, in written pitch; the layout transposes it.
            String text = rename(read(src), names(old, plan));
            if ("concert".equals(sel.transpose())) text = TRANSPOSITION.matcher(text).replaceAll(""); // it now sounds as written
            files.put("parts/" + plan.slug + ".ly", text);
            Interval interval = intervals.get(k);
            if (interval != null) {
                String to = Notation.absolutePitch(Transpose.transposePitch(new Pitch("C", 0, 4), interval));
                List<String> vars = new ArrayList<>();
                for (Generate.StaffVars sv : plan.staves) vars.add(sv.music);
                for (String rel : List.of("layout/score.ly", "layout/part-" + plan.slug + ".ly")) {
                    if (!files.containsKey(rel)) throw new IllegalStateException("KeyError: '" + rel + "'");
                    files.put(rel, transposed(files.get(rel), vars, to));
                }
            }
        }
        LyProject project = new LyProject(files, Generate.scanMeasureLines(files, generated.staffVars()), generated.staffVars());
        Map<String, Object> report = Pipeline.produce(combined, out, settings, project);
        List<Object> from = new ArrayList<>();
        for (Selection sel : selections) from.add(source(sel));
        report.put("combined_from", from);
        if (alignment.changed()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("measures", alignment.measures);
            a.put("reference", names.get(alignment.reference));
            Map<String, Object> added = new LinkedHashMap<>();
            for (int k = 0; k < alignment.filled.size(); k++) {
                if (alignment.filled.get(k).isEmpty()) continue;
                List<Integer> cols = new ArrayList<>();
                for (int c : alignment.filled.get(k)) cols.add(c + 1);
                added.put(names.get(k), cols);
            }
            a.put("rests_added", added);
            Map<String, Object> unsure = new LinkedHashMap<>();
            for (int k = 0; k < alignment.unsure.size(); k++) {
                if (alignment.unsure.get(k).isEmpty()) continue;
                List<Object> ranges = new ArrayList<>();
                for (int[] r : alignment.unsure.get(k)) ranges.add(List.of(r[0] + 1, r[1] + 1));
                unsure.put(names.get(k), ranges);
            }
            // Measures where the rest (or the extra measure) could as well have gone.
            if (!unsure.isEmpty()) a.put("placement_unsure", unsure);
            report.put("alignment", a);
        }
        if (!rekeyed.isEmpty()) {
            Map<String, Object> keys = new LinkedHashMap<>();
            rekeyed.forEach((k, measures) -> keys.put(names.get(k), measures));
            report.put("keys", keys);
        }
        if (!piano.isEmpty()) {
            List<Object> pf = new ArrayList<>();
            for (Selection sel : piano) pf.add(source(sel));
            report.put("piano_from", pf);
        }
        write(out.resolve("report.json"), PyJson.write(IrJson.mapper().valueToTree(report), PyJson.Style.jsonDumps(2)));
        return report;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path p, String text) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
