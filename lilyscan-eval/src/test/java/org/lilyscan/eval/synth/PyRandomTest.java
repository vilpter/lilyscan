package org.lilyscan.eval.synth;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.testing.Repo;

/** {@link PyRandom} against CPython's {@code random.Random} (tests/parity/synth/random.json). */
class PyRandomTest {

    @Test
    void sameDrawsAsPython() {
        JsonNode cases = Repo.readJson(Repo.parity("synth/random.json"));
        List<Integer> eleven = IntStream.range(0, 11).boxed().toList();
        List<String> pop = List.of("a", "b", "c", "d");
        double[] weights = {40, 25, 15, 20};
        int n = 0;
        for (JsonNode c : cases) {
            JsonNode seed = c.get("seed");
            PyRandom rng = seed.isTextual() ? new PyRandom(seed.asText()) : new PyRandom(seed.asLong());
            for (JsonNode d : c.get("draws")) {
                String what = seed + " draw " + n++;
                switch (d.get(0).asText()) {
                    case "random" -> assertEquals(d.get(1).asText(), PyJson.floatRepr(rng.random()), what);
                    case "randint" -> assertEquals(d.get(3).asInt(), rng.randint(d.get(1).asInt(), d.get(2).asInt()), what);
                    case "choice" -> assertEquals(d.get(2).asInt(), rng.choice(eleven), what);
                    case "choices" -> assertEquals(d.get(1).asText(), rng.choice(pop, weights), what);
                    default -> fail("unknown draw " + d);
                }
            }
        }
        assertEquals(7 * 400, n);
    }
}
