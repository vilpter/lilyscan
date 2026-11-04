package org.lilyscan.core.omr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.Pitch;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.testing.Parity;
import org.lilyscan.core.testing.Repo;

/** Geometry on the Python reader's book must give exactly the Python attach result. */
class GeometryParityTest {

    static Stream<JsonNode> cases() {
        List<JsonNode> out = new ArrayList<>();
        Repo.readJson(Repo.parity("omr/manifest.json")).forEach(out::add);
        return out.stream();
    }

    @TestFactory
    Stream<DynamicTest> bookJsonRoundTrips() {
        return cases().map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            Path ref = Repo.parity("omr/" + c.get("name").asText() + ".book.json");
            String python = Repo.read(ref);
            Parity.assertSameText(python, OmrBookJson.write(OmrBookJson.load(Repo.readJson(ref))), "book.json");
        }));
    }

    @TestFactory
    Stream<DynamicTest> attachMatchesPython() {
        return cases().map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            String name = c.get("name").asText();
            OmrBook book = OmrBookJson.load(Repo.readJson(Repo.parity("omr/" + name + ".book.json")));
            List<Score> movements = new ArrayList<>();
            for (JsonNode mv : c.get("movements")) {
                movements.add(MusicXml.load(Repo.path(c.get("dir").asText()).resolve(mv.asText()), "audiveris"));
            }
            Geometry.Attached attached = Geometry.attachMovements(movements, book);
            Parity.assertSameText(Repo.read(Repo.parity("omr/" + name + ".score.json")),
                    IrJson.toJson(attached.score()), name + " score.json");
            Parity.assertSameText(Repo.read(Repo.parity("omr/" + name + ".stats.json")),
                    PyJson.write(IrJson.mapper().valueToTree(stats(attached.stats())), PyJson.Style.jsonDumps(1))
                            + "\n", name + " stats");
        }));
    }

    static java.util.Map<String, Object> stats(AttachStats s) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("events", s.events);
        m.put("mappable", s.mappable);
        m.put("located", s.located);
        m.put("pitch_mismatch", s.pitchMismatch);
        m.put("measures", s.measures);
        m.put("measures_located", s.measuresLocated);
        m.put("unmapped_staves", s.unmappedStaves);
        return m;
    }

    @Test
    void middleLines() { // tests/test_omr.py::test_middle_line
        assertEquals(34, Geometry.middleLineDiatonic(new Clef("G", 2, 0, Frac.ZERO)));
        assertEquals(22, Geometry.middleLineDiatonic(new Clef("F", 4, 0, Frac.ZERO)));
        assertEquals(28, Geometry.middleLineDiatonic(new Clef("C", 3, 0, Frac.ZERO)));
        assertEquals(26, Geometry.middleLineDiatonic(new Clef("C", 4, 0, Frac.ZERO)));
        assertEquals(27, Geometry.middleLineDiatonic(new Clef("G", 2, -1, Frac.ZERO)));
    }

    @Test
    void staffStepDirection() { // tests/test_omr.py::test_staff_step_direction
        Clef treble = new Clef("G", 2, 0, Frac.ZERO);
        assertEquals(0, Geometry.staffStep(new Pitch("B", 0, 4), treble));
        assertEquals(-2, Geometry.staffStep(new Pitch("D", 0, 5), treble));
        assertEquals(2, Geometry.staffStep(new Pitch("G", 0, 4), treble));
    }
}
