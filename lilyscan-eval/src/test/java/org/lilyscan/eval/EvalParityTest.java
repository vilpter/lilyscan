package org.lilyscan.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.repair.Confidence;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.testing.Parity;
import org.lilyscan.core.testing.Repo;

/**
 * The evaluation must score exactly as Python does: the committed comparisons
 * (tests/parity/eval), and with {@code -PevalSweep} a whole {@code eval run} over Python's cached
 * engine output, whose {@code results.json} (but its environment) and {@code summary.md} (but its
 * header) must be Python's.
 */
class EvalParityTest {

    @TestFactory
    Stream<DynamicTest> committedComparisons() {
        List<DynamicTest> tests = new ArrayList<>();
        Path jobs = Repo.parity("combine/jobs");
        for (JsonNode c : Repo.readJson(Repo.parity("eval/compare.json"))) {
            String name = c.get("gt").asText() + " vs " + c.get("pred").asText();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                Score gt = IrJson.readScore(jobs.resolve(c.get("gt").asText()).resolve("ir/score.json"));
                Score pred = c.get("pred").isNull() ? null : IrJson.readScore(jobs.resolve(c.get("pred").asText()).resolve("ir/score.json"));
                if (pred != null) Confidence.calibrate(pred);
                String got = PyJson.write(IrJson.mapper().valueToTree(Compare.compare(gt, pred).toMap()), PyJson.Style.jsonDumps(1));
                assertEquals(PyJson.write(c.get("comparison"), PyJson.Style.jsonDumps(1)), got, "comparison");
                if (pred != null) {
                    String cal = PyJson.write(IrJson.mapper().valueToTree(Compare.calibration(Compare.eventCorrectness(gt, pred))),
                            PyJson.Style.jsonDumps(1));
                    assertEquals(PyJson.write(c.get("calibration"), PyJson.Style.jsonDumps(1)), cal, "calibration");
                }
            }));
        }
        return tests.stream();
    }

    /** {@code -PevalSweep=<dir>}: {@code <dir>/runs.json} lists {corpus, work, flags, python results dir}. */
    @TestFactory
    Stream<DynamicTest> evalRuns() {
        String sweep = System.getProperty("lilyscan.evalSweep");
        if (sweep == null || sweep.isBlank()) return Stream.empty();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode r : Repo.readJson(Path.of(sweep).resolve("runs.json"))) {
            tests.add(DynamicTest.dynamicTest(r.get("label").asText(), () -> {
                Settings s = Settings.fromEnv();
                List<Corpus.Item> items = Corpus.load(Path.of(r.get("corpus").asText()));
                List<Harness.ItemResult> results = Harness.run(items, Path.of(r.get("work").asText()), Corpus.VARIANTS, 3, s,
                        new Harness.Options(true, r.get("lilypond").asBoolean(), r.get("repair").asBoolean(), r.get("prepare").asBoolean()));
                Path out = Files.createTempDirectory("lilyscan-eval-");
                Harness.writeResults(results, out, r.get("label").asText(), "", s);
                Path py = Path.of(r.get("python").asText());
                ObjectNode want = (ObjectNode) Repo.readJson(py.resolve("results.json"));
                ObjectNode got = (ObjectNode) Repo.readJson(out.resolve("results.json"));
                want.remove("environment");
                got.remove("environment");
                Parity.assertSameText(PyJson.write(want, PyJson.Style.jsonDumps(2)), PyJson.write(got, PyJson.Style.jsonDumps(2)), "results.json");
                String[] a = Repo.read(py.resolve("summary.md")).split("\n", 5), b = Repo.read(out.resolve("summary.md")).split("\n", 5);
                Parity.assertSameText(a[4], b[4], "summary.md (after the header)");
            }));
        }
        return tests.stream();
    }
}
