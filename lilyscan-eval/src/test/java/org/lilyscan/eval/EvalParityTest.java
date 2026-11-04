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

    /** The confidence model's fit on a fixed sample set: weights, calibration map and ECE as Python's. */
    @org.junit.jupiter.api.Test
    void calibrationFit() {
        JsonNode ref = Repo.readJson(Repo.parity("eval/calibrate.json"));
        List<Calibrate.Sample> samples = new ArrayList<>();
        for (JsonNode s : ref.get("samples")) {
            double[] x = new double[s.get(1).size()];
            for (int i = 0; i < x.length; i++) x[i] = s.get(1).get(i).asDouble();
            samples.add(new Calibrate.Sample(s.get(0).asText(), x, s.get(2).asBoolean(), s.get(3).isNull() ? null : s.get(3).asDouble()));
        }
        Confidence.Model model = Calibrate.fit(samples, 4);
        JsonNode want = ref.get("model");
        StringBuilder got = new StringBuilder(), exp = new StringBuilder();
        for (int i = 0; i < model.weights().length; i++) {
            got.append(model.weights()[i]).append('\n');
            exp.append(want.get("weights").get(i).asDouble()).append('\n');
        }
        got.append("bias ").append(model.bias()).append('\n');
        exp.append("bias ").append(want.get("bias").asDouble()).append('\n');
        for (int i = 0; i < model.calibration().length; i++) {
            got.append(model.calibration()[i][0]).append(' ').append(model.calibration()[i][1]).append('\n');
        }
        for (JsonNode p : want.get("calibration")) exp.append(p.get(0).asDouble()).append(' ').append(p.get(1).asDouble()).append('\n');
        assertEquals(exp.toString(), got.toString(), "model");
        double[] oof = Calibrate.crossValidate(samples, 5);
        List<Double> in = new ArrayList<>(), out = new ArrayList<>();
        List<Boolean> ys = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            in.add(Confidence.predict(model, samples.get(i).x()));
            out.add(oof[i]);
            ys.add(samples.get(i).y());
        }
        assertEquals(ref.get("ece_in_sample").asDouble(), Calibrate.ece(in, ys), "ece in sample");
        assertEquals(ref.get("ece_out_of_fold").asDouble(), Calibrate.ece(out, ys), "ece out of fold");
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
