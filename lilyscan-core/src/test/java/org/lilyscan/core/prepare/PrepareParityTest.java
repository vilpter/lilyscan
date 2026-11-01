package org.lilyscan.core.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lilyscan.core.testing.Repo;

/**
 * Stage 1 against Python on the corpus images ({@code -PprepareSweep}, scripts/java_prepare_sweep.py).
 *
 * <p>Steps without float interpolation are compared exactly on Python's own intermediate
 * images. The whole page is compared by its report and its pixels: OpenCV 4.13 (JavaCPP) and
 * 5.0 (Python) interpolate warps and resizes differently (design-java.md, JM5), so pixels differ
 * slightly and the report's measurements are compared to a tolerance; its decisions must match.
 */
class PrepareParityTest {

    /** As {@code cv2.imread(path, cv2.IMREAD_GRAYSCALE)} reads it. */
    static Np.F loadGray(Path png) throws IOException {
        var m = org.bytedeco.opencv.global.opencv_imgcodecs.imread(png.toString(),
                org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE);
        Np.U8 u = Np.u8(m);
        Np.F f = new Np.F(u.w, u.h);
        for (int i = 0; i < f.a.length; i++) f.a[i] = (float) (u.a[i] & 255) / 255f;
        return f;
    }

    static Np.F loadF32(Path dir, String name) throws IOException {
        JsonNode shape = Repo.readJson(dir.resolve(name + ".shape.json"));
        int h = shape.get(0).asInt(), w = shape.get(1).asInt();
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(dir.resolve(name + ".f32"))).order(ByteOrder.LITTLE_ENDIAN);
        Np.F f = new Np.F(w, h);
        b.asFloatBuffer().get(f.a);
        return f;
    }

    static double[] diff(Np.F a, Np.F b) {
        if (a.w != b.w || a.h != b.h) return new double[] {Double.NaN, Double.NaN};
        double max = 0, sum = 0;
        for (int i = 0; i < a.a.length; i++) {
            double d = Math.abs(a.a[i] - b.a[i]);
            max = Math.max(max, d);
            sum += d;
        }
        return new double[] {max, sum / a.a.length};
    }

    static Path sweep() {
        String s = System.getProperty("lilyscan.prepareSweep");
        return s == null || s.isBlank() ? null : Path.of(s);
    }

    @TestFactory
    Stream<DynamicTest> checkpoints() {
        Path root = sweep();
        if (root == null) return Stream.empty();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode e : Repo.readJson(root.resolve("manifest.json"))) {
            if (!e.has("checkpoints")) continue;
            Path dir = root.resolve(e.get("name").asText());
            Path input = Path.of(e.get("input").asText());
            tests.add(DynamicTest.dynamicTest(e.get("name").asText(), () -> {
                Np.F gray = loadGray(input);
                // find_page: resize (area), blur (uint8), Otsu, morphology and contours match exactly.
                JsonNode corners = Repo.readJson(dir.resolve("corners.json"));
                float[][] got = PageImage.findPage(gray);
                if (corners.isNull()) {
                    assertEquals(null, got, "corners");
                } else {
                    for (int i = 0; i < 4; i++) for (int k = 0; k < 2; k++) {
                        assertEquals(corners.get(i).get(k).floatValue(), got[i][k], "corner " + i);
                    }
                }
                Np.F flat = loadF32(dir, "flat");
                double[] fd = diff(PageImage.flattenLight(gray), flat);
                System.out.printf("%s flatten: max %.2e mean %.2e%n", e.get("name").asText(), fd[0], fd[1]);
                double angle = PageImage.staffAngle(flat);
                System.out.printf("%s angle: %s vs %s%n", e.get("name").asText(), angle, Repo.readJson(dir.resolve("angle.json")));
                JsonNode info = Repo.readJson(dir.resolve("level.json"));
                Np.F level = loadF32(dir, "level");
                Np.U8 mask = PageImage.inkMask(level);
                StaffWarp.Scale scale = StaffWarp.measureScale(mask);
                if (info.get("scale").isNull()) {
                    assertEquals(null, scale);
                } else {
                    assertEquals(info.get("scale").get(0).asDouble(), scale.thickness(), "thickness");
                    assertEquals(info.get("scale").get(1).asDouble(), scale.interline(), "interline");
                    List<double[]> samples = StaffWarp.displacementSamples(mask, scale.interline());
                    assertEquals(info.get("samples").asInt(), samples.size(), "samples");
                    assertEquals(info.get("curvature").asDouble(), StaffWarp.curvature(samples), 1e-6, "curvature");
                }
                Np.U8 small = PageImage.smallMask(level, 2000);
                double[] lean = PageImage.textLean(small);
                // NumPy's argsort breaks ties by CPU (AVX-512 sort or introsort), so Python's own
                // letter grouping varies by machine; Java's stable order is close, not identical.
                System.out.printf("%s text lean %.4f/%s letters %s/%d%n", e.get("name").asText(),
                        info.get("text_lean").get(0).asDouble(), lean[0], info.get("text_lean").get(1), (int) lean[1]);
                assertEquals(info.get("text_lean").get(1).asInt(), (int) lean[1], 0.05 * lean[1] + 3, "letters");
                assertEquals(info.get("text_lean").get(0).asDouble(), lean[0], 0.005, "lean");
                assertEquals(info.get("start_weight").asDouble(), PageImage.startWeight(small), 1e-12, "start weight");
                assertEquals(info.get("upside_down").asBoolean(), PageImage.upsideDown(level, false), "upside down");
                assertEquals(info.get("angle").asDouble(), (double) PageImage.levelStaves(flat)[1], "level angle");
            }));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> wholePages() {
        Path root = sweep();
        if (root == null) return Stream.empty();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode e : Repo.readJson(root.resolve("manifest.json"))) {
            Path dir = root.resolve(e.get("name").asText());
            Path input = Path.of(e.get("input").asText());
            tests.add(DynamicTest.dynamicTest(e.get("name").asText(), () -> {
                JsonNode py = Repo.readJson(dir.resolve("report.json"));
                Object[] out = Prepare.preparePage(loadGray(input), input.getFileName().toString(), true, true, "auto");
                var report = ((Prepare.Report) out[1]).toMap();
                Np.F expected = loadGray(dir.resolve("prepared.png"));
                Np.F got = Np.F.class.cast(out[0]);
                Np.U8 got8 = Np.toU8(got, 0.5f);
                Np.F gotQ = new Np.F(got.w, got.h);
                for (int i = 0; i < gotQ.a.length; i++) gotQ.a[i] = (float) (got8.a[i] & 255) / 255f;
                double[] d = diff(gotQ, expected);
                System.out.printf("PAGE %-22s found %s/%s rot %s/%s il %s/%s resc %s/%s curv %s/%s→%s/%s dew %s/%s warn %s/%s px max %.3f mean %.5f%n",
                        e.get("name").asText(), py.get("page_found"), report.get("page_found"), py.get("rotated"), report.get("rotated"),
                        py.get("interline"), report.get("interline"), py.get("rescaled"), report.get("rescaled"),
                        py.get("curvature_before"), report.get("curvature_before"), py.get("curvature_after"), report.get("curvature_after"),
                        py.get("dewarped"), report.get("dewarped"), py.get("warnings").size(), ((List<?>) report.get("warnings")).size(), d[0], d[1]);
                assertEquals(py.get("page_found").asBoolean(), report.get("page_found"), "page_found");
                assertEquals(py.get("rotated").asDouble(), (double) report.get("rotated"), "rotated");
                assertEquals(py.get("dewarped").asBoolean(), report.get("dewarped"), "dewarped");
                assertEquals(py.get("cleaned").isNull() ? null : py.get("cleaned").asText(), report.get("cleaned"), "cleaned");
                assertEquals(py.get("rescaled").asDouble(), (double) report.get("rescaled"), 0.002, "rescaled");
                assertEquals(py.get("output_size").toString(), report.get("output_size").toString().replace(" ", ""), "output size");
                assertEquals(py.get("warnings").size(), ((List<?>) report.get("warnings")).size(), "warnings");
            }));
        }
        return tests.stream();
    }
}
