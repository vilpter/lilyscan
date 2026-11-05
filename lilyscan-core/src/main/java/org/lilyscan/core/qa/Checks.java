package org.lilyscan.core.qa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.lilypond.Compile;
import org.lilyscan.core.lilypond.Generate;
import org.lilyscan.core.lilypond.LyProject;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.util.Difflib;
import org.lilyscan.core.util.Py;

/**
 * Quality checks Q1-Q5, Q9 and Q10 (design doc, Stage 8). Port of {@code lilyscan/qa/checks.py}.
 *
 * <pre>
 * Q1 compiles       the generated project compiles without errors
 * Q2 bar checks     no bar-check warnings, each mapped back to part/staff/measure
 * Q3 rhythm         every voice fills its measure (pickup and its complement excepted)
 * Q4 pitch range    notes inside the practical range of the part's instrument
 * Q5 alignment      all staves have the same measures and barline structure
 * Q9 lyrics         no lyrics in a part named for an instrument that does not sing
 * Q10 fits the page nothing engraved runs off the page (a system LilyPond could not break)
 * </pre>
 *
 * <p>Details are insertion-ordered maps of plain JSON values, so the report serializes exactly as
 * the Python {@code asdict} report does.
 */
public final class Checks {
    private Checks() {}

    /** One check's outcome. */
    public record CheckResult(String id, String name, boolean passed, List<Map<String, Object>> details,
            String summary) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("passed", passed);
            m.put("details", details);
            m.put("summary", summary);
            return m;
        }
    }

    /** All checks of a project, and the files the compile wrote. */
    public record QaReport(List<CheckResult> checks, List<String> outputs) {
        public boolean passed() {
            return checks.stream().allMatch(CheckResult::passed);
        }

        public CheckResult check(String id) {
            return checks.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed());
            List<Map<String, Object>> cs = new ArrayList<>();
            for (CheckResult c : checks) {
                cs.add(c.toMap());
            }
            m.put("checks", cs);
            m.put("outputs", outputs);
            return m;
        }
    }

    @SafeVarargs
    private static Map<String, Object> map(Map.Entry<String, Object>... entries) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entries) {
            m.put(e.getKey(), e.getValue());
        }
        return m;
    }

    private static Map.Entry<String, Object> kv(String k, Object v) {
        return new java.util.AbstractMap.SimpleEntry<>(k, v);
    }

    private static String label(Measure m) {
        return m.number != null && !m.number.isEmpty() ? m.number : Integer.toString(m.index + 1);
    }

    // ---- Q1 / Q2 ----

    /** pathlib's {@code as_posix()} of a relative path: empty and "." parts dropped. */
    private static String posix(String file) {
        boolean absolute = file.startsWith("/");
        List<String> parts = new ArrayList<>();
        for (String p : file.split("/", -1)) {
            if (!p.isEmpty() && !p.equals(".")) {
                parts.add(p);
            }
        }
        String s = String.join("/", parts);
        return absolute ? "/" + s : s.isEmpty() ? "." : s;
    }

    static String relative(String file, Path root) {
        if (file == null) {
            return null;
        }
        Path path = Path.of(file);
        if (path.isAbsolute()) {
            try {
                Path real = Files.exists(path) ? path.toRealPath() : path.normalize();
                Path base = Files.exists(root) ? root.toRealPath() : root.toAbsolutePath().normalize();
                if (real.startsWith(base)) {
                    return posix(base.relativize(real).toString());
                }
            } catch (IOException e) {
                // fall through
            }
            return posix(file);
        }
        String s = posix(file);
        return s.startsWith("./") ? s.substring(2) : s;
    }

    /** Q1, Q2 and the compile they come from. */
    public record Compiled(CheckResult q1, CheckResult q2, Compile.Result result) {}

    public static Compiled compileChecks(Path root, LyProject project, Settings settings) {
        Compile.Result result = Compile.compileLy(root.resolve("main.ly"), root, List.of("pdf"), false, List.of(),
                settings);
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Compile.Diagnostic d : result.errors()) {
            errors.add(map(kv("file", relative(d.file(), root)), kv("line", d.line()), kv("message", d.message())));
        }
        List<Compile.Diagnostic> internal = result.warnings().stream().filter(Compile.Diagnostic::isInternal).toList();
        List<Map<String, Object>> details = new ArrayList<>(errors);
        for (Compile.Diagnostic d : internal) {
            details.add(map(kv("file", relative(d.file(), root)), kv("line", d.line()), kv("message", d.message()),
                    kv("internal", true)));
        }
        String summary = result.ok() ? "compiled" : errors.size() + " error(s)";
        if (!internal.isEmpty()) {
            summary += " (" + internal.size() + " LilyPond internal warning(s))";
        }
        CheckResult q1 = new CheckResult("Q1", "compiles", result.ok(), details, summary);
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Compile.Diagnostic d : result.warnings()) {
            if (!d.isBarcheck()) {
                continue;
            }
            String rel = relative(d.file(), root);
            LyProject.MeasureRef where = rel != null && d.line() != null && d.line() != 0
                    ? project.measureLines().get(new LyProject.Line(rel, d.line())) : null;
            failures.add(map(kv("file", rel), kv("line", d.line()), kv("message", d.message()),
                    kv("part", where != null ? where.part() : null), kv("staff", where != null ? where.staff() : null),
                    kv("measure", where != null ? where.measure() : null)));
        }
        CheckResult q2 = new CheckResult("Q2", "bar checks", result.ok() && failures.isEmpty(), failures,
                failures.size() + " failing bar check(s)");
        return new Compiled(q1, q2, result);
    }

    // ---- Q3 ----

    public static CheckResult rhythmCheck(Score score) {
        List<Map<String, Object>> problems = new ArrayList<>();
        for (Score.PartStaff ps : score.staves()) {
            Part part = ps.part();
            Staff staff = ps.staff();
            Frac length = null;
            Frac pickup = Frac.ZERO;
            for (Measure m : staff.measures) {
                if (m.time != null) {
                    length = m.time.measureLength();
                }
                if (length == null) {
                    continue;
                }
                boolean isLast = m == staff.measures.get(staff.measures.size() - 1);
                for (Voice v : m.voices) {
                    Frac actual = v.duration();
                    if (actual.equals(length)) {
                        continue;
                    }
                    if (m.index == 0 && actual.lt(length) && (m.implicit || "0".equals(m.number))) {
                        pickup = actual; // anacrusis, marked as such in the source
                        continue;
                    }
                    if (isLast && !pickup.isZero() && actual.add(pickup).equals(length)) {
                        continue; // final measure completing the pickup
                    }
                    problems.add(map(kv("part", part.id), kv("staff", staff.number), kv("measure", label(m)),
                            kv("voice", v.number), kv("expected", length.toString()), kv("actual", actual.toString())));
                }
            }
        }
        return new CheckResult("Q3", "rhythmic integrity", problems.isEmpty(), problems,
                problems.size() + " voice-measure(s) not matching the time signature");
    }

    // ---- Q4 ----

    /** A practical written range as MIDI numbers, matched on part names. */
    public record Range(String pattern, int lo, int hi) {}

    /** The Python patterns, verbatim: the report names the one that matched. */
    public static final List<Range> RANGES = List.of(
            new Range("piccolo", 74, 108), new Range("flute|fl\\.", 59, 98), new Range("oboe|ob\\.", 58, 93),
            new Range("clarinet|cl\\.", 52, 96), new Range("bassoon|fag|bsn", 34, 76),
            new Range("horn|cor\\b|hn\\.", 42, 84), new Range("trumpet|tpt|trp", 54, 86),
            new Range("trombone|tbn", 40, 77), new Range("tuba", 26, 65), new Range("violin|vln|vn\\.", 55, 103),
            new Range("viola|vla", 48, 88), new Range("cello|vc\\.|vlc", 36, 81),
            new Range("contrabass|double bass|kontrabass|cb\\.|db\\.", 28, 67), new Range("guitar|gtr", 52, 88),
            new Range("soprano|^s\\.?$", 59, 84), new Range("alto|^a\\.?$", 53, 77),
            new Range("tenor|^t\\.?$", 47, 72), new Range("bass|^b\\.?$", 40, 65),
            new Range("piano|pno|keyboard|organ|harp", 21, 108));
    public static final Range DEFAULT_RANGE = new Range("default", 21, 108);

    private static final Map<String, Pattern> COMPILED = new java.util.concurrent.ConcurrentHashMap<>();

    private static Pattern ci(String pattern) {
        return COMPILED.computeIfAbsent(pattern, p -> Pattern.compile(p,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS));
    }

    public static Range instrumentRange(Part part) {
        for (String label : new String[] {part.name == null ? "" : part.name,
            part.abbreviation == null ? "" : part.abbreviation}) {
            for (Range r : RANGES) {
                if (ci(r.pattern()).matcher(Py.strip(label)).find()) {
                    return r;
                }
            }
        }
        return DEFAULT_RANGE;
    }

    public static CheckResult rangeCheck(Score score) {
        List<Map<String, Object>> suspects = new ArrayList<>();
        for (Score.PartStaff ps : score.staves()) {
            Range r = instrumentRange(ps.part());
            for (Measure m : ps.staff().measures) {
                for (Voice v : m.voices) {
                    for (Event e : v.events) {
                        for (Pitch p : e.pitches()) {
                            if (p.midi() < r.lo() || p.midi() > r.hi()) {
                                suspects.add(map(kv("part", ps.part().id), kv("staff", ps.staff().number),
                                        kv("measure", label(m)), kv("offset", e.offset.toString()), kv("pitch", p.label()),
                                        kv("range", List.of(r.lo(), r.hi())), kv("rule", r.pattern())));
                            }
                        }
                    }
                }
            }
        }
        return new CheckResult("Q4", "pitch range", suspects.isEmpty(), suspects,
                suspects.size() + " note(s) outside the instrument's practical range");
    }

    // ---- Q5 ----

    public static CheckResult alignmentCheck(Score score) {
        List<Score.PartStaff> staves = score.staves();
        List<Map<String, Object>> problems = new ArrayList<>();
        if (!staves.isEmpty()) {
            Map<String, Object> counts = new LinkedHashMap<>();
            Set<Integer> distinct = new HashSet<>();
            for (Score.PartStaff ps : staves) {
                counts.put(ps.part().id + "/" + ps.staff().number, ps.staff().measures.size());
                distinct.add(ps.staff().measures.size());
            }
            if (distinct.size() > 1) {
                problems.add(map(kv("kind", "measure-count"), kv("counts", counts)));
            }
            Score.PartStaff reference = staves.get(0);
            for (Score.PartStaff ps : staves.subList(1, staves.size())) {
                int n = Math.min(reference.staff().measures.size(), ps.staff().measures.size());
                for (int i = 0; i < n; i++) {
                    Measure a = reference.staff().measures.get(i);
                    Measure b = ps.staff().measures.get(i);
                    if (!Objects.equals(a.rightBarline, b.rightBarline)) { // model_dump() equality
                        problems.add(map(kv("kind", "barline"), kv("measure", label(a)),
                                kv("staves", List.of(reference.part().id + "/" + reference.staff().number,
                                        ps.part().id + "/" + ps.staff().number))));
                    }
                }
            }
        }
        return new CheckResult("Q5", "cross-part alignment", problems.isEmpty(), problems,
                problems.size() + " alignment problem(s)");
    }

    // ---- Q9 ----

    /** Instruments that do not sing (keyboards, guitars and names a voice could have left out). */
    static final Pattern INSTRUMENTS = ci("piccolo|flute|\\bfl\\.|oboe|\\bob\\.|clarinet|\\bcl\\.|bassoon|fagott|\\bbsn|horn|\\bhn\\."
            + "|trumpet|\\btpt|\\btrp|trombone|\\btbn|tuba|violin|\\bvln|\\bvn\\.|viola|\\bvla|cello|\\bvc\\."
            + "|\\bvlc|contrabass|double bass|\\bcb\\.|\\bdb\\.");
    static final Pattern VOICES = ci("soprano|alto|tenor|baritone|voice|vocal|choir|chorus|(?<!double )\\bbass\\b");

    /** Instruments' names, for names OCR misread by a letter ("Celio", "Vrolin"). */
    private static final List<String> INSTRUMENT_WORDS = List.of("piccolo", "flute", "oboe", "clarinet", "bassoon",
            "trumpet", "trombone", "violin", "viola", "cello", "violoncello", "contrabass", "fiddle");
    /** The name Audiveris gives a one-staff part whose name it could not read. */
    private static final String PLACEHOLDER = "voice";

    /** Runs of 4+ characters matching {@code [^\W\d_]} (letters, non-decimal numerics). */
    private static List<String> longWords(String s) {
        List<String> out = new ArrayList<>();
        int[] cps = s.codePoints().toArray();
        int i = 0;
        while (i < cps.length) {
            if (!Generate.wordLetter(cps[i])) {
                i++;
                continue;
            }
            int start = i;
            while (i < cps.length && Generate.wordLetter(cps[i])) {
                i++;
            }
            if (i - start >= 4) {
                out.add(new String(cps, start, i - start));
            }
        }
        return out;
    }

    /** True for an instrument's name, false for a voice's, null when it says neither. */
    static Boolean instrumentName(String name) {
        if (VOICES.matcher(name).find()) {
            return false;
        }
        if (INSTRUMENTS.matcher(name).find()) {
            return true;
        }
        for (String w : longWords(name.toLowerCase(Locale.ROOT))) {
            if (!Difflib.closeMatches(w, INSTRUMENT_WORDS, 1, 0.8).isEmpty()) {
                return true;
            }
        }
        return null;
    }

    /** Named for an instrument, not for a voice: the name decides; the abbreviation only when
     *  the name says neither or is the engine's placeholder. */
    public static boolean isInstrumental(Part part) {
        for (String label : new String[] {part.name, part.abbreviation}) {
            if (label == null || label.isEmpty() || Py.strip(label).toLowerCase(Locale.ROOT).equals(PLACEHOLDER)) {
                continue;
            }
            Boolean verdict = instrumentName(label);
            if (verdict != null) {
                return verdict;
            }
        }
        return false;
    }

    /** Lyrics in an instrumental part are usually text the engine read as lyrics. Each part found
     *  lists the voices whose lyrics would be dropped ({@link Generate#dropLyrics}). */
    public static CheckResult lyricsCheck(Score score, LyProject project) {
        Map<LyProject.StaffRef, String> voices = new LinkedHashMap<>();
        project.staffVars().forEach((music, key) -> voices.put(key, Generate.voiceName(music)));
        List<Map<String, Object>> found = new ArrayList<>();
        for (Part part : score.parts) {
            if (!isInstrumental(part)) {
                continue;
            }
            List<String> measures = new ArrayList<>();
            List<Integer> staves = new ArrayList<>();
            int events = 0;
            for (Staff staff : part.staves) {
                for (Measure m : staff.measures) {
                    int n = 0;
                    for (Voice v : m.voices) {
                        for (Event e : v.events) {
                            if (!e.lyrics.isEmpty()) {
                                n++;
                            }
                        }
                    }
                    if (n > 0) {
                        events += n;
                        measures.add(label(m));
                        if (!staves.contains(staff.number)) {
                            staves.add(staff.number);
                        }
                    }
                }
            }
            if (events > 0) {
                List<String> vs = new ArrayList<>();
                for (int n : staves) {
                    String v = voices.get(new LyProject.StaffRef(part.id, n));
                    if (v != null) {
                        vs.add(v);
                    }
                }
                String name = part.name != null && !part.name.isEmpty() ? part.name
                        : part.abbreviation != null && !part.abbreviation.isEmpty() ? part.abbreviation : part.id;
                found.add(map(kv("part", part.id), kv("name", name), kv("events", events), kv("measures", measures),
                        kv("voices", vs)));
            }
        }
        return lyricsResult(found);
    }

    private static CheckResult lyricsResult(List<Map<String, Object>> found) {
        return new CheckResult("Q9", "lyrics in instrumental parts", found.isEmpty(), found,
                found.size() + " instrumental part(s) with lyrics");
    }

    /** Q9 after the source was edited: parts whose lyrics are no longer engraved are dropped. */
    @SuppressWarnings("unchecked")
    public static CheckResult lyricsAfterEdit(CheckResult check, Map<String, String> files) {
        Set<String> engraved = Generate.engravedLyrics(files);
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> d : check.details()) {
            Object vs = d.getOrDefault("voices", List.of());
            for (Object v : (List<Object>) vs) {
                if (engraved.contains(String.valueOf(v))) {
                    kept.add(d);
                    break;
                }
            }
        }
        return lyricsResult(kept);
    }

    public static QaReport runChecks(Score score, Path root, LyProject project, Settings settings) {
        Compiled c = compileChecks(root, project, settings);
        List<String> outputs = new ArrayList<>();
        Path base = root.toAbsolutePath().normalize();
        for (Path p : c.result().outputs()) {
            outputs.add(posix(base.relativize(p.toAbsolutePath().normalize()).toString()));
        }
        // LilyPond writes main.midi on Linux and main.mid on Windows.
        for (String name : List.of("main.midi", "main.mid")) {
            if (Files.isRegularFile(root.resolve(name))) {
                outputs.add(name);
            }
        }
        List<CheckResult> checks = new ArrayList<>(List.of(c.q1(), c.q2(), rhythmCheck(score), rangeCheck(score),
                alignmentCheck(score), lyricsCheck(score, project)));
        Path pdf = root.resolve("main.pdf");
        if (Files.isRegularFile(pdf)) checks.add(pageFitCheck(pdf));
        return new QaReport(List.copyOf(checks), outputs);
    }

    // ---- Q10 ----

    /** Drawing more than this past a page edge (in points, about 1 mm) counts as running off it. */
    static final double PAGE_FIT_TOLERANCE_PT = 3.0;

    /**
     * Q10: nothing engraved runs off the page. LilyPond keeps a system on one line when it finds
     * no place to break it (for example, when the parts' bar lines do not coincide), and the
     * system then runs past the right edge; Q1 still passes.
     */
    public static CheckResult pageFitCheck(Path pdf) {
        List<Map<String, Object>> pages = new ArrayList<>();
        try {
            for (PageFit.Extent e : PageFit.extents(pdf)) {
                if (e.overflow() > PAGE_FIT_TOLERANCE_PT) {
                    double mm = e.overflow() * 25.4 / 72.0;
                    pages.add(map(kv("page", e.page()), kv("overflow_mm", Math.round(mm * 10) / 10.0),
                            kv("message", String.format(java.util.Locale.ROOT,
                                    "page %d: the music runs %.0f mm past the page edge (a system that could not be broken)",
                                    e.page(), mm))));
                }
            }
        } catch (IOException ex) {
            return new CheckResult("Q10", "fits the page", false,
                    List.of(map(kv("message", "cannot read " + pdf.getFileName() + ": " + ex.getMessage()))),
                    "could not be checked");
        }
        return new CheckResult("Q10", "fits the page", pages.isEmpty(), pages,
                pages.isEmpty() ? "fits the page" : pages.size() + " page(s) with music past the edge");
    }
}
