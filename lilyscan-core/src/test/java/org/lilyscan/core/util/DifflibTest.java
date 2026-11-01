package org.lilyscan.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lilyscan.core.testing.Repo;

class DifflibTest {

    @Test
    void ratiosMatchPython() { // tests/parity/difflib.json: Python's SequenceMatcher(None, a, b).ratio()
        for (JsonNode c : Repo.readJson(Repo.parity("difflib.json"))) {
            assertEquals(c.get(2).asDouble(), Difflib.ratio(c.get(0).asText(), c.get(1).asText()), 0.0, c.toString());
        }
    }

    @Test
    void closeMatchesPickTheBest() {
        assertEquals(List.of("cello"), Difflib.closeMatches("celio", List.of("cello", "viola", "violin"), 1, 0.8));
        assertEquals(List.of(), Difflib.closeMatches("tuba", List.of("cello", "viola"), 1, 0.8));
    }
}
