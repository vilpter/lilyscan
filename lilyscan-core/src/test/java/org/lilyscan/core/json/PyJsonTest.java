package org.lilyscan.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.lilyscan.core.testing.Repo;

class PyJsonTest {

    @Test
    void floatsMatchPythonRepr() {
        JsonNode cases = Repo.readJson(Repo.parity("floats.json"));
        for (JsonNode c : cases) {
            double d = Double.parseDouble(c.get(0).asText());
            assertEquals(c.get(1).asText(), PyJson.floatRepr(d), "repr of " + c.get(0).asText());
        }
    }

    @Test
    void stringsEscapeLikePydantic() {
        ObjectNode n = new ObjectMapper().createObjectNode();
        n.put("t", "Sø \"q\" \\ \t/ \u0001\u001f\u007f");
        assertEquals("{\"t\":\"Sø \\\"q\\\" \\\\ \\t/ \\u0001\\u001f\u007f\"}",
                PyJson.write(n, PyJson.Style.PYDANTIC_COMPACT));
        assertEquals("{\"t\": \"S\\u00f8 \\\"q\\\" \\\\ \\t/ \\u0001\\u001f\\u007f\"}",
                PyJson.write(n, PyJson.Style.JSON_DUMPS_COMPACT));
    }

    @Test
    void indentsLikePydantic() {
        ObjectMapper m = new ObjectMapper();
        ObjectNode n = m.createObjectNode();
        n.putArray("a").add(3).add(2);
        n.putArray("e");
        n.putObject("o");
        n.putNull("z");
        assertEquals("{\n \"a\": [\n  3,\n  2\n ],\n \"e\": [],\n \"o\": {},\n \"z\": null\n}",
                PyJson.write(n, PyJson.Style.PYDANTIC_INDENT_1));
    }
}
