package org.lilyscan.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.lilypond.Compile;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.testing.Repo;

/** {@code convert}: MusicXML -> IR -> LilyPond project -> checks (port of tests/test_pipeline.py's start). */
@Tag("lilypond")
class PipelineTest {

    @Test
    void convertsAMusicXmlFile(@TempDir Path tmp) {
        Settings s = Settings.fromEnv();
        Assumptions.assumeTrue(Compile.lilypondVersion(s) != null, "LilyPond not found (set LILYPOND_BIN)");
        Map<String, Object> report = Pipeline.convertFile(Repo.path("tests/fixtures/features.musicxml"), tmp, s);
        assertTrue(Files.isRegularFile(tmp.resolve("ir/score.json")));
        assertTrue(Files.isRegularFile(tmp.resolve("ly/main.ly")));
        assertTrue(Files.isRegularFile(tmp.resolve("ly/main.pdf")));
        assertTrue(Files.isRegularFile(tmp.resolve("ly/" + Pipeline.SOURCE_MAP)));
        JsonNode json = Repo.readJson(tmp.resolve("report.json"));
        assertEquals("ir/score.json", json.get("ir").asText());
        assertTrue(json.get("qa").get("passed").asBoolean(), json.get("qa").toString());
        assertEquals(List.of("ly/main.pdf", "ly/main.midi"),
                List.of(json.get("lilypond").get("outputs").get(0).asText(), json.get("lilypond").get("outputs").get(1).asText()));
        // The IR written is what the reader produced.
        assertEquals(Repo.read(Repo.parity("musicxml/features.score.json")), IrJson.toJson(IrJson.readScore(tmp.resolve("ir/score.json"))));
        // lilyscan.ir.ops.counts on the same file: {'parts': 2, 'staves': 3, 'measures': 4, 'notes': 24}
        assertEquals(Map.of("parts", 2, "staves", 3, "measures", 4, "notes", 24), report.get("counts"));
    }
}
