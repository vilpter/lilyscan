package org.lilyscan.core.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lilyscan.core.testing.Repo;

/**
 * What the oracle reads from a PDF (staves, italic digits) must match PyMuPDF's reading, to
 * within float noise: on the corpus PDFs of the engine sweep ({@code -PengineSweep}).
 */
class VectorPdfParityTest {
    private static final double EPS = 1e-3;

    @TestFactory
    Stream<DynamicTest> matchesPyMuPdf() throws Exception {
        String sweep = System.getProperty("lilyscan.engineSweep");
        if (sweep == null || sweep.isBlank()) return Stream.empty();
        List<DynamicTest> tests = new ArrayList<>();
        try (var dirs = Files.list(Path.of(sweep, "corpus"))) {
            for (Path dir : dirs.sorted().toList()) {
                Path ref = dir.resolve("vector.json");
                if (!Files.isRegularFile(ref)) continue;
                tests.add(DynamicTest.dynamicTest(dir.getFileName().toString(), () -> {
                    JsonNode expected = Repo.readJson(ref);
                    List<VectorPdf.Page> pages = VectorPdf.read(dir.resolve("score.pdf"));
                    assertEquals(expected.size(), pages.size(), "pages");
                    for (int p = 0; p < pages.size(); p++) {
                        VectorPdf.Page page = pages.get(p);
                        JsonNode e = expected.get(p);
                        assertEquals(e.get("width").asDouble(), page.width, EPS);
                        List<Staves.VectorStaff> staves = Staves.find(page);
                        assertEquals(e.get("staves").size(), staves.size(), "staves on page " + p);
                        for (int s = 0; s < staves.size(); s++) {
                            JsonNode es = e.get("staves").get(s);
                            for (int k = 0; k < 5; k++) assertEquals(es.get(0).get(k).asDouble(), staves.get(s).lines()[k], EPS, "staff " + s);
                            assertEquals(es.get(1).asDouble(), staves.get(s).x0(), EPS);
                            assertEquals(es.get(2).asDouble(), staves.get(s).x1(), EPS);
                        }
                        List<VectorPdf.Glyph> italic = page.glyphs.stream().filter(g -> g.name().startsWith("italic.")).toList();
                        assertEquals(e.get("italic").size(), italic.size(), "italic digits on page " + p);
                        for (int g = 0; g < italic.size(); g++) {
                            JsonNode eg = e.get("italic").get(g);
                            assertEquals(eg.get(0).asText(), italic.get(g).name());
                            assertEquals(eg.get(1).asDouble(), italic.get(g).rect().cx(), EPS, "cx of digit " + g);
                            assertEquals(eg.get(2).asDouble(), italic.get(g).originY(), EPS, "origin of digit " + g);
                        }
                    }
                }));
            }
        }
        return tests.stream();
    }
}
