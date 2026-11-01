package org.lilyscan.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.MusicXmlWriter;
import org.lilyscan.core.lilypond.Compile;
import org.lilyscan.core.review.Review;
import org.lilyscan.core.runtime.Device;
import org.lilyscan.core.util.Py;
import org.lilyscan.core.ir.Ops;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.lilypond.Generate;
import org.lilyscan.core.lilypond.LyProject;
import org.lilyscan.core.qa.Checks;
import org.lilyscan.core.runtime.Settings;

/**
 * One job's Stages 6-8: the IR, its MusicXML, the LilyPond project, its checks and the review
 * model. Port of {@code lilyscan/pipeline.py}.
 *
 * <p>The report has the same keys, in the same order, as the Python one.
 */
public final class Pipeline {
    private Pipeline() {}

    /** Maps music variables to (part, staff) so edited files can be re-mapped to measures. */
    public static final String SOURCE_MAP = "lilyscan-map.json";

    public static Score importMusicXmlFiles(List<Path> paths, String source) {
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("no MusicXML files to import");
        }
        List<Score> scores = new ArrayList<>();
        for (Path p : paths) {
            scores.add(MusicXml.load(p, source));
        }
        return Ops.mergeScores(scores);
    }

    /** Write {@code ir/score.json}, {@code score.musicxml} and the {@code ly/} project under
     *  {@code root}, run the checks, render the review, and return the report. {@code project}
     *  is written instead of generating one when given. */
    public static Map<String, Object> produce(Score score, Path root, Settings settings, LyProject project) {
        IrJson.writeScore(score, root.resolve("ir").resolve("score.json"));
        MusicXmlWriter.write(score, root.resolve("score.musicxml"));
        Path lyRoot = root.resolve("ly");
        if (project == null) {
            project = Generate.writeProject(score, lyRoot);
        } else {
            project.write(lyRoot);
        }
        Map<String, Object> staffVars = new LinkedHashMap<>();
        project.staffVars().forEach((var, ref) -> staffVars.put(var, List.of(ref.part(), ref.staff())));
        write(lyRoot.resolve(SOURCE_MAP), PyJson.write(MAPPER.valueToTree(Map.of("staff_vars", staffVars)),
                PyJson.Style.jsonDumps(1)));
        Checks.QaReport qa = Checks.runChecks(score, lyRoot, project, settings);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ir", "ir/score.json");
        report.put("musicxml", "score.musicxml");
        report.putAll(render(root, score, project, qa, settings));
        report.put("counts", Ops.counts(score));
        report.put("device", Device.describe());
        return report;
    }

    /** Point-and-click SVG pages and the review model; the report's LilyPond and QA sections. */
    static Map<String, Object> render(Path root, Score score, LyProject project, Checks.QaReport qa,
            Settings settings) {
        Path lyRoot = root.resolve("ly");
        Path svgDir = lyRoot.resolve("svg");
        deleteTree(svgDir);
        Compile.Result svg = Compile.compileLy(lyRoot.resolve("main.ly"), svgDir, List.of("svg"), true, List.of(),
                settings == null ? Settings.fromEnv() : settings);
        Map<String, Object> review = Review.build(score, project, qa);
        write(root.resolve("review.json"), PyJson.write(MAPPER.valueToTree(review), PyJson.Style.JSON_DUMPS_COMPACT));
        Map<String, Object> lilypond = new LinkedHashMap<>();
        lilypond.put("project", "ly");
        lilypond.put("files", new TreeSet<>(project.files().keySet()).stream().map(n -> "ly/" + n).toList());
        lilypond.put("outputs", qa.outputs().stream().map(n -> "ly/" + n).toList());
        List<Path> pages = new ArrayList<>(svg.outputs());
        pages.sort(Comparator.<Path>comparingInt(Pipeline::pageNumber).thenComparing(p -> p.getFileName().toString()));
        lilypond.put("svg", pages.stream().map(p -> "ly/svg/" + p.getFileName()).toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lilypond", lilypond);
        out.put("qa", qa.toMap());
        out.put("review", "review.json");
        return out;
    }

    /** {@code main-3.svg} sorts as page 3; a name without a page number as 0. */
    private static int pageNumber(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        int dash = stem.lastIndexOf('-');
        if (dash < 0) return 0;
        String tail = stem.substring(dash + 1);
        return Py.isDigit(tail) ? Integer.parseInt(tail) : 0;
    }

    /**
     * After the user edits {@code ly/}: recompile (Q1, Q2), re-render, refresh the review. Q3-Q5
     * describe the recognized music, which editing the source does not change, so their last
     * results are kept; Q9 keeps the parts whose lyrics the source still engraves.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> recompile(Path root, Settings settings) {
        Path lyRoot = root.resolve("ly");
        Score score = IrJson.readScore(root.resolve("ir").resolve("score.json"));
        Map<String, LyProject.StaffRef> staffVars = new LinkedHashMap<>();
        Map<String, Object> map = readJson(lyRoot.resolve(SOURCE_MAP));
        ((Map<String, List<Object>>) map.get("staff_vars")).forEach((name, ref) ->
                staffVars.put(name, new LyProject.StaffRef((String) ref.get(0), ((Number) ref.get(1)).intValue())));
        Map<String, String> files = new LinkedHashMap<>();
        for (Path p : lyFiles(lyRoot)) {
            files.put(posix(lyRoot.relativize(p).toString()), readString(p));
        }
        LyProject project = new LyProject(files, Generate.scanMeasureLines(files, staffVars), staffVars);
        Checks.Compiled compiled = Checks.compileChecks(lyRoot, project, settings);
        Path reportPath = root.resolve("report.json");
        Map<String, Object> report = readJson(reportPath);
        List<Checks.CheckResult> checks = new ArrayList<>(List.of(compiled.q1(), compiled.q2()));
        for (Map<String, Object> c : (List<Map<String, Object>>) ((Map<String, Object>) report.get("qa")).get("checks")) {
            String id = (String) c.get("id");
            if (id.equals("Q1") || id.equals("Q2")) continue;
            Checks.CheckResult r = new Checks.CheckResult(id, (String) c.get("name"), (Boolean) c.get("passed"),
                    (List<Map<String, Object>>) c.get("details"), (String) c.get("summary"));
            checks.add(id.equals("Q9") ? Checks.lyricsAfterEdit(r, files) : r);
        }
        checks.sort(Comparator.comparing(Checks.CheckResult::id));
        Path base = lyRoot.toAbsolutePath().normalize();
        List<String> outputs = new ArrayList<>();
        for (Path p : compiled.result().outputs()) {
            outputs.add(posix(base.relativize(p.toAbsolutePath().normalize()).toString()));
        }
        for (String name : List.of("main.midi", "main.mid")) {
            if (Files.isRegularFile(lyRoot.resolve(name))) outputs.add(name);
        }
        report.putAll(render(root, score, project, new Checks.QaReport(checks, outputs), settings));
        write(reportPath, PyJson.write(MAPPER.valueToTree(report), PyJson.Style.jsonDumps(2)));
        return report;
    }

    /** The project's {@code .ly} files (not the rendered SVG directory), sorted as Python sorts paths. */
    private static List<Path> lyFiles(Path lyRoot) {
        try (var walk = Files.walk(lyRoot)) {
            return walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".ly"))
                    .filter(p -> {
                        for (Path part : lyRoot.relativize(p)) if (part.toString().equals("svg")) return false;
                        return true;
                    })
                    .sorted(Comparator.comparing((Path p) -> pathParts(lyRoot.relativize(p)), Pipeline::compareParts))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> pathParts(Path p) {
        List<String> out = new ArrayList<>();
        for (Path part : p) out.add(part.toString());
        return out;
    }

    private static int compareParts(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    private static String posix(String p) {
        return p.replace('\\', '/');
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readJson(Path p) {
        try {
            return MAPPER.readValue(p.toFile(), LinkedHashMap.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readString(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Standalone conversion of a MusicXML file (CLI and tests): writes {@code report.json}. */
    public static Map<String, Object> convertFile(Path musicxml, Path outDir, Settings settings) {
        Map<String, Object> report = produce(importMusicXmlFiles(List.of(musicxml), "musicxml"), outDir, settings, null);
        write(outDir.resolve("report.json"), PyJson.write(MAPPER.valueToTree(report), PyJson.Style.jsonDumps(2)));
        return report;
    }

    private static final ObjectMapper MAPPER = IrJson.mapper();

    private static void write(Path p, String text) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
