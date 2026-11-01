package org.lilyscan.engine.audiveris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.omr.Geometry;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.omr.OmrBookJson;
import org.lilyscan.core.omr.OmrException;
import org.lilyscan.core.testing.Parity;
import org.lilyscan.core.testing.Repo;

/**
 * The in-process reader, through Audiveris's own classes, must extract exactly what the Python
 * reader parsed from the saved XML — and attaching it must give the same IR.
 */
class OmrReaderParityTest {

    static Stream<JsonNode> cases() {
        List<JsonNode> out = new ArrayList<>();
        Repo.readJson(Repo.parity("omr/manifest.json")).forEach(out::add);
        return out.stream();
    }

    @TestFactory
    Stream<DynamicTest> readsTheSameBookAsPython() {
        return cases().map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            String name = c.get("name").asText();
            OmrReader.Detailed read = OmrReader.readDetailed(Repo.path(c.get("dir").asText()).resolve("book.omr"));
            OmrBook book = read.book();
            // No stage uses the boxes Audiveris re-derives on reload; they may move by a pixel.
            for (String used : USED_KINDS) {
                assertFalse(read.derivedBoundsKinds().contains(used), used + " boxes must be exact");
            }
            assertSameBook(Repo.readJson(Repo.parity("omr/" + name + ".book.json")),
                    OmrBookJson.dump(book), read.derivedBoundsKinds(), name);

            List<Score> movements = new ArrayList<>();
            for (JsonNode mv : c.get("movements")) {
                movements.add(MusicXml.load(Repo.path(c.get("dir").asText()).resolve(mv.asText()), "audiveris"));
            }
            Parity.assertSameText(Repo.read(Repo.parity("omr/" + name + ".score.json")),
                    IrJson.toJson(Geometry.attachMovements(movements, book).score()), name + " score.json");
        }));
    }

    /** Kinds whose boxes or grades Lilyscan stages use: they must match exactly. */
    static final Set<String> USED_KINDS = Set.of("head", "rest", "head-chord", "rest-chord", "lyric-item", "word");

    /** Exact equality, except each edge of a derived-bounds kind's box may move by up to 1 px. */
    static void assertSameBook(JsonNode python, JsonNode java, Set<String> derived, String name) {
        assertEquals(python.get("software_version"), java.get("software_version"), name);
        assertEquals(python.get("logical_parts"), java.get("logical_parts"), name);
        assertEquals(python.get("sheets").size(), java.get("sheets").size(), name);
        int tolerated = 0;
        for (int s = 0; s < python.get("sheets").size(); s++) {
            JsonNode ps = python.get("sheets").get(s);
            JsonNode js = java.get("sheets").get(s);
            for (String k : List.of("number", "width", "height", "interline")) {
                assertEquals(ps.get(k), js.get(k), name + " sheet " + k);
            }
            assertEquals(ps.get("systems").size(), js.get("systems").size(), name + " systems");
            for (int y = 0; y < ps.get("systems").size(); y++) {
                JsonNode py = ps.get("systems").get(y);
                JsonNode jy = js.get("systems").get(y);
                for (String k : List.of("starts_movement", "stacks", "parts", "chord_of")) {
                    assertEquals(py.get(k), jy.get(k), name + " system " + y + " " + k);
                }
                assertEquals(py.get("inters").size(), jy.get("inters").size(), name + " inters");
                for (int i = 0; i < py.get("inters").size(); i++) {
                    JsonNode pi = py.get("inters").get(i);
                    JsonNode ji = jy.get("inters").get(i);
                    for (int f = 0; f < pi.size(); f++) {
                        if (f == 6 && derived.contains(pi.get(1).asText()) && !pi.get(6).equals(ji.get(6))) {
                            double[] p = edges(pi.get(6));
                            double[] j = edges(ji.get(6));
                            for (int b = 0; b < 4; b++) {
                                assertTrue(Math.abs(p[b] - j[b]) <= 1.0, name + " " + pi + " vs " + ji);
                            }
                            tolerated++;
                            continue;
                        }
                        assertEquals(pi.get(f), ji.get(f), name + " inter " + pi.get(0) + " field " + f);
                    }
                }
            }
        }
        System.out.println(name + ": " + tolerated + " re-derived line-symbol boxes within 1 px");
    }

    /** {x0, y0, x1, y1} of an {x, y, w, h} box. */
    static double[] edges(JsonNode b) {
        double x = b.get(0).asDouble();
        double y = b.get(1).asDouble();
        return new double[] {x, y, x + b.get(2).asDouble(), y + b.get(3).asDouble()};
    }

    @Test
    void readsBookStructure() { // tests/test_omr.py::test_reads_book_structure
        OmrBook book = OmrReader.read(Repo.path("eval/fixtures/omr/5.11.0/split-flute/book.omr"));
        assertEquals("5.11.0", book.softwareVersion());
        assertEquals(1, book.sheets().size());
        assertEquals(2480, book.sheets().get(0).width());
        assertEquals(3508, book.sheets().get(0).height());
        assertEquals(16, book.sheets().get(0).systems().stream().mapToInt(s -> s.stacks().size()).sum());
        assertEquals(List.of("F1.", "Flute"), book.logicalParts().values().stream().sorted().toList());
    }

    @Test
    void rejectsWhatIsNotAProject(@TempDir Path tmp) throws Exception {
        Path notZip = Files.writeString(tmp.resolve("x.omr"), "hello");
        assertThrows(OmrException.class, () -> OmrReader.read(notZip));
    }
}
