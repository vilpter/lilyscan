package org.lilyscan.core.lilypond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.lilyscan.core.ir.BBox;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Ir;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.Provenance;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.testing.Parity;
import org.lilyscan.core.testing.Repo;

/** The generator against Python's output (tests/parity/lilypond) and tests/test_generate.py. */
class GenerateTest {

    /** {@code project_dump} of scripts/java_parity.py. */
    static String dump(LyProject p) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode root = f.objectNode();
        ObjectNode files = root.putObject("files");
        p.files().forEach(files::put);
        ArrayNode lines = root.putArray("measure_lines");
        p.measureLines().forEach((k, v) -> lines.addArray().add(k.file()).add(k.line()).add(v.part())
                .add(v.staff()).add(v.measure()));
        ArrayNode vars = root.putArray("staff_vars");
        p.staffVars().forEach((k, v) -> vars.addArray().add(k).add(v.part()).add(v.staff()));
        return PyJson.write(root, PyJson.Style.jsonDumps(1)) + "\n";
    }

    @TestFactory
    Stream<DynamicTest> matchesPythonGenerator() {
        return Parity.projectCases().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            LyProject p = Generate.generateProject(IrJson.readScore(c.score()));
            Parity.assertSameText(Repo.read(c.reference()), dump(p), c.name());
        }));
    }

    static Score features() {
        return MusicXml.load(Repo.path("tests/fixtures/features.musicxml"));
    }

    @Test
    void matchesGoldenFiles() throws IOException {
        Path golden = Repo.path("tests/golden/features");
        Map<String, String> expected = new TreeMap<>();
        try (Stream<Path> s = Files.walk(golden)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".ly")).toList()) {
                expected.put(golden.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        assertEquals(expected, new TreeMap<>(Generate.generateProject(features()).files()));
    }

    @Test
    void outputIsDeterministic() {
        Score score = features();
        assertEquals(Generate.generateProject(score).files(), Generate.generateProject(Ir.deepCopy(score)).files());
    }

    @Test
    void everyMeasureLineIsMapped() {
        LyProject project = Generate.generateProject(features());
        Set<LyProject.Line> mapped = new HashSet<>(project.measureLines().keySet());
        project.files().forEach((rel, text) -> {
            if (!rel.startsWith("parts/")) {
                return;
            }
            var lines = Generate.splitLines(text);
            for (int n = 1; n <= lines.size(); n++) {
                String line = lines.get(n - 1);
                if (line.stripTrailing().endsWith("|") || line.contains(" | \\bar")) {
                    assertTrue(mapped.contains(new LyProject.Line(rel, n)), rel + ":" + n + " " + line);
                }
            }
        });
        // The map rebuilt from the files is the same map.
        assertEquals(project.measureLines(), Generate.scanMeasureLines(project.files(), project.staffVars()));
    }

    @Test
    void lowConfidenceAndRepairMarkers() {
        Score score = features();
        Event event = score.parts.get(0).staves.get(0).measures.get(1).voices.get(0).events.get(0);
        event.confidence = 0.42;
        event.bbox = new BBox(1, 812.4, 340.2, 10, 10);
        event.provenance.add(new Provenance("repair", "missing-dot"));
        String text = Generate.generateProject(score).files().get("parts/voice.ly");
        assertTrue(text.contains("g'2 %{ ?? conf=0.42 bbox=p2:(812,340) %} %{ fix: missing-dot %}"), text);
    }

    @Test
    void lyricWordsAreQuotedOnlyWhenNeeded() {
        assertTrue(Generate.plainSyllable("Lord's,"));
        assertTrue(Generate.plainSyllable("Kyrie"));
        assertTrue(Generate.plainSyllable("Grüß"));
        assertTrue(!Generate.plainSyllable("12"));
        assertTrue(!Generate.plainSyllable("--"));
        assertTrue(!Generate.plainSyllable("a_b"));
    }
}
