package org.lilyscan.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.Ops;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.lilypond.Generate;
import org.lilyscan.core.lilypond.LyProject;
import org.lilyscan.core.qa.Checks;
import org.lilyscan.core.runtime.Settings;

/**
 * One job's Stages 6-8: the IR, the LilyPond project and its checks. Port of the parts of
 * {@code lilyscan/pipeline.py} that JM2 covers.
 *
 * <p>Not yet written (they come with the web review in JM3): {@code score.musicxml} (the
 * MusicXML export), the point-and-click SVG pages, {@code review.json}, and the compute device
 * in the report. The report has the same keys as the Python one for everything it does write.
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

    /** Write {@code ir/score.json} and the {@code ly/} project under {@code root}, run the checks,
     *  and return the report. {@code project} is written instead of generating one when given. */
    public static Map<String, Object> produce(Score score, Path root, Settings settings, LyProject project) {
        IrJson.writeScore(score, root.resolve("ir").resolve("score.json"));
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
        Map<String, Object> lilypond = new LinkedHashMap<>();
        lilypond.put("project", "ly");
        List<String> files = new ArrayList<>();
        for (String name : project.files().keySet()) {
            files.add("ly/" + name);
        }
        lilypond.put("files", files);
        List<String> outputs = new ArrayList<>();
        for (String name : qa.outputs()) {
            outputs.add("ly/" + name);
        }
        lilypond.put("outputs", outputs);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ir", "ir/score.json");
        report.put("lilypond", lilypond);
        report.put("qa", qa.toMap());
        report.put("counts", Ops.counts(score));
        return report;
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
