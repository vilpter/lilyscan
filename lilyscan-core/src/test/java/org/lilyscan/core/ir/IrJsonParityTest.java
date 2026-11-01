package org.lilyscan.core.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.lilyscan.core.testing.Repo;

/** Python-written {@code score.json} files read and written back must be byte-identical. */
class IrJsonParityTest {

    @TestFactory
    Stream<DynamicTest> roundTripsPythonScoreJsonByteForByte() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode entry : Repo.readJson(Repo.parity("musicxml/manifest.json"))) {
            String name = entry.get("name").asText();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                String python = Repo.read(Repo.parity("musicxml/" + name + ".score.json"));
                Score score = IrJson.readScore(python);
                assertFalse(score.parts.isEmpty());
                assertEquals(python, IrJson.toJson(score));
                // A deep copy is equal and serializes identically.
                assertEquals(score, IrJson.deepCopy(score, Score.class));
            }));
        }
        return tests.stream();
    }

    @Test
    void rejectsWhatPydanticRejects() {
        assertThrows(IllegalArgumentException.class,
                () -> IrJson.readScore("{\"parts\": [], \"bogus\": 1}"));
        assertThrows(IllegalArgumentException.class, () -> IrJson.read(
                "{\"kind\": \"blob\", \"offset\": \"0\", \"duration\": \"1\"}", Event.class));
        assertThrows(IllegalArgumentException.class, () -> IrJson.read(
                "{\"step\": \"C\", \"alter\": 3, \"octave\": 4}", Pitch.class));
    }

    @Test
    void acceptsNumbersForFractionsLikePydantic() {
        Event e = IrJson.read("{\"kind\": \"rest\", \"offset\": 0, \"duration\": 1.5}", Event.class);
        assertEquals(Frac.ZERO, e.offset);
        assertEquals(Frac.of(3, 2), e.duration);
    }
}
