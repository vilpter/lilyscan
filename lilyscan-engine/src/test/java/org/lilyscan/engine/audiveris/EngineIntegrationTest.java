package org.lilyscan.engine.audiveris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.Ops;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.omr.Geometry;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.runtime.Config;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.testing.Repo;

/**
 * End to end against the bundled Audiveris, run in-process in forked workers (port of
 * tests/test_engine_integration.py). Needs the OCR models: set TESSDATA_PREFIX.
 */
@Tag("engine")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngineIntegrationTest {

    Settings settings;
    Path page;
    AudiverisRun run;

    @BeforeAll
    void transcribeOnce(@TempDir Path tmp) throws Exception {
        String tessdata = System.getenv("TESSDATA_PREFIX");
        Assumptions.assumeTrue(tessdata != null && !tessdata.isBlank(), "TESSDATA_PREFIX not set");
        settings = Settings.fromEnv(System.getenv());
        // A clean engraved page: the image saved inside a fixture project.
        page = tmp.resolve("page.png");
        try (ZipFile z = new ZipFile(Repo.path("eval/fixtures/omr/5.11.0/split-flute/book.omr").toFile());
                InputStream in = z.getInputStream(z.getEntry("sheet#1/BINARY.png"))) {
            Files.copy(in, page);
        }
        run = AudiverisRunner.run(List.of(page), tmp.resolve("engine"), null, null, settings, null, 1);
    }

    @Test
    void bundledVersionMatchesPin() {
        assertEquals(Config.AUDIVERIS_VERSION, AudiverisRunner.audiverisVersion(settings));
    }

    @Test
    void transcribesAPageWithWorkingOcr() {
        assertTrue(run.ok(), () -> tail(run.log()));
        assertEquals(List.of(".omr"), run.omrFiles().stream().map(p -> suffix(p)).toList());
        assertTrue(!run.mxlFiles().isEmpty() && run.mxlFiles().stream().allMatch(p -> suffix(p).equals(".mxl")));
        assertEquals(List.of(), run.ocrProblems());
    }

    @Test
    void transcriptionToIrLocatesTheEvents() { // the JM1 exit criterion
        Assumptions.assumeTrue(run.ok());
        OmrBook book = AudiverisRunner.readOmr(run.omrFiles().get(0), settings); // in a forked worker
        List<Score> movements = new ArrayList<>();
        for (Path mxl : run.mxlFiles()) {
            movements.add(MusicXml.load(mxl, "audiveris"));
        }
        Geometry.Attached attached = Geometry.attachMovements(movements, book);
        System.out.println("transcribed: " + Ops.counts(attached.score()) + ", located "
                + attached.stats().located + "/" + attached.stats().mappable + " ("
                + Math.round(1000 * attached.stats().locatedRate()) / 10.0 + "%)");
        assertTrue(attached.stats().locatedRate() >= 0.98, "located rate " + attached.stats().locatedRate());
        assertTrue(IrJson.toJson(attached.score()).contains("\"bbox\": {"));
    }

    @Test
    void ocrLanguageConstantIsHonoured(@TempDir Path tmp) {
        // Audiveris ignores unknown -constant keys silently, so prove this one is read: asking
        // for a language that is not installed must produce a complaint.
        AudiverisRun r = AudiverisRunner.run(List.of(page), tmp.resolve("engine"), null, null, settings, "eng+zzz", 1);
        assertTrue(r.ocrProblems().stream().anyMatch(p -> p.contains("zzz")), () -> tail(r.log()));
    }

    @Test
    void stepTimeoutConstantIsHonoured(@TempDir Path tmp) {
        // A one-second limit per step must stop Audiveris on an ordinary page, with its own
        // timeout message: proof that the key is read.
        AudiverisRun r = AudiverisRunner.run(List.of(page), tmp.resolve("engine"),
                Map.of(Config.AUDIVERIS_STEP_TIMEOUT_KEY, "1"), null, settings, null, 1);
        assertTrue(r.log().contains("Timeout 1 seconds for step"), () -> tail(r.log()));
    }

    static String suffix(Path p) {
        String n = p.getFileName().toString();
        return n.substring(n.lastIndexOf('.'));
    }

    static String tail(String log) {
        return log.length() > 3000 ? log.substring(log.length() - 3000) : log;
    }
}
