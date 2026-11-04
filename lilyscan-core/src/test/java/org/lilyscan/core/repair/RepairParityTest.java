package org.lilyscan.core.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.omr.OmrBookJson;
import org.lilyscan.core.testing.Parity;
import org.lilyscan.core.testing.Repo;
import org.lilyscan.core.vector.VectorOracle;

/**
 * The repair rules and the calibrated confidence must give the Python score and repair log, byte
 * for byte: on the committed Audiveris fixtures, and with {@code -PengineSweep} on every engine
 * run of the corpus (scripts/java_engine_sweep.py).
 */
class RepairParityTest {

    private static String repairsJson(List<Repair> repairs) {
        List<Object> out = new ArrayList<>();
        for (Repair r : repairs) out.add(r.toMap());
        return PyJson.write(IrJson.mapper().valueToTree(out), PyJson.Style.jsonDumps(1)) + "\n";
    }

    private static OmrBook book(Path p) {
        return OmrBookJson.load(Repo.readJson(p));
    }

    @TestFactory
    Stream<DynamicTest> committedFixtures() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : Repo.readJson(Repo.parity("omr/manifest.json"))) {
            String name = c.get("name").asText();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                Score score = IrJson.readScore(Repo.parity("omr/" + name + ".score.json"));
                List<Repair> repairs = Repairs.apply(score, book(Repo.parity("omr/" + name + ".book.json")));
                Confidence.calibrate(score);
                Parity.assertSameText(Repo.read(Repo.parity("omr/" + name + ".repairs.json")), repairsJson(repairs), name + " repairs");
                Parity.assertSameText(Repo.read(Repo.parity("omr/" + name + ".repaired.score.json")), IrJson.toJson(score),
                        name + " repaired score");
            }));
        }
        return tests.stream();
    }

    /** Engine runs of the corpus: the rules alone, and (for scans) the whole chain with confidence. */
    @TestFactory
    Stream<DynamicTest> engineSweep() {
        String sweep = System.getProperty("lilyscan.engineSweep");
        if (sweep == null || sweep.isBlank()) return Stream.empty();
        Path root = Path.of(sweep);
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode n : Repo.readJson(root.resolve("manifest.json"))) {
            Path run = root.resolve("runs").resolve(n.asText());
            tests.add(DynamicTest.dynamicTest(n.asText() + " rules", () -> {
                Score score = IrJson.readScore(run.resolve("input.score.json"));
                List<Repair> repairs = Repairs.apply(score, book(run.resolve("book.json")));
                Parity.assertSameText(Repo.read(run.resolve("rules.repairs.json")), repairsJson(repairs), "repairs");
                Parity.assertSameText(Repo.read(run.resolve("rules.score.json")), IrJson.toJson(score), "score");
            }));
            if (n.asText().endsWith("-pdf")) {
                tests.add(DynamicTest.dynamicTest(n.asText() + " vector oracle + rules + calibrated", () -> {
                    Score score = IrJson.readScore(run.resolve("input.score.json"));
                    OmrBook book = book(run.resolve("book.json"));
                    Path pdf = Path.of(Repo.readJson(run.resolve("run.json")).get("pdf").asText());
                    List<Repair> log = new ArrayList<>(VectorOracle.apply(score, pdf, book));
                    log.addAll(Repairs.apply(score, book));
                    boolean calibrated = Confidence.calibrate(score);
                    var expected = Repo.readJson(run.resolve("final.repairs.json"));
                    assertEquals(expected.get("calibrated").asBoolean(), calibrated);
                    Parity.assertSameText(PyJson.write(expected.get("repairs"), PyJson.Style.jsonDumps(1)) + "\n",
                            repairsJson(log), "repairs");
                    Parity.assertSameText(Repo.read(run.resolve("final.score.json")), IrJson.toJson(score), "score");
                }));
            }
            if (n.asText().endsWith("-scan")) {
                tests.add(DynamicTest.dynamicTest(n.asText() + " calibrated", () -> {
                    Score score = IrJson.readScore(run.resolve("rules.score.json"));
                    Confidence.calibrate(score);
                    Parity.assertSameText(Repo.read(run.resolve("final.score.json")), IrJson.toJson(score), "score");
                }));
            }
        }
        return tests.stream();
    }
}
