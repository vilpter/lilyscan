package org.lilyscan.core.combine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.testing.Parity;
import org.lilyscan.core.testing.Repo;

/**
 * The combined job (report, review, IR, MusicXML, every LilyPond file) must be Python's, byte for
 * byte, on combiner requests over engine runs of the corpus ({@code -PcombineSweep},
 * scripts/java_combine_sweep.py); a refused request must fail with Python's message.
 */
class CombineParityTest {

    private static List<Combine.Selection> selections(JsonNode items) {
        List<Combine.Selection> out = new ArrayList<>();
        for (JsonNode s : items) {
            out.add(new Combine.Selection(Path.of(s.get("job").asText()), s.get("part").asText(),
                    s.get("transpose").isNull() ? null : s.get("transpose").asText(),
                    s.get("name").isNull() ? null : s.get("name").asText()));
        }
        return out;
    }

    /** The text outputs of a job, relative paths (binary outputs and SVG pages left out). */
    private static TreeSet<String> textFiles(Path root) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("ly/svg/") || rel.endsWith(".pdf") || rel.endsWith(".midi") || rel.endsWith(".mid")) continue;
                out.add(rel);
            }
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> matchesPython() {
        String sweep = System.getProperty("lilyscan.combineSweep");
        if (sweep == null || sweep.isBlank()) return Stream.empty();
        Path root = Path.of(sweep);
        Settings settings = Settings.fromEnv();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode n : Repo.readJson(root.resolve("manifest.json"))) {
            Path dir = root.resolve("cases").resolve(n.asText());
            tests.add(DynamicTest.dynamicTest(n.asText(), () -> {
                JsonNode c = Repo.readJson(dir.resolve("case.json"));
                Path out = Files.createTempDirectory("lilyscan-combine-");
                String error = null;
                try {
                    Combine.combine(selections(c.get("parts")), out, c.get("title").isNull() ? null : c.get("title").asText(),
                            settings, selections(c.get("piano")));
                } catch (Combine.CombineException e) {
                    error = "CombineError: " + e.getMessage();
                } catch (IllegalArgumentException e) {
                    error = "ValueError: " + e.getMessage();
                }
                Path errFile = dir.resolve("error.txt");
                if (Files.isRegularFile(errFile)) {
                    assertEquals(Repo.read(errFile), error, "error");
                    return;
                }
                assertEquals(null, error, "error");
                Path expected = dir.resolve("job");
                TreeSet<String> want = textFiles(expected), got = textFiles(out);
                assertEquals(want, got, "files");
                for (String rel : want) {
                    Parity.assertSameText(Repo.read(expected.resolve(rel)), Repo.read(out.resolve(rel)), n.asText() + " " + rel);
                }
                assertTrue(!want.isEmpty());
            }));
        }
        return tests.stream();
    }
}
