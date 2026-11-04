package org.lilyscan.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.lilyscan.core.lilypond.Compile;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.omr.OmrBookJson;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.testing.Repo;
import org.lilyscan.engine.audiveris.AudiverisRun;
import org.lilyscan.server.LilyscanServer;
import org.lilyscan.server.jobs.Tasks;

/**
 * The embedded server as the desktop app starts it, with a stand-in engine that "transcribes"
 * any upload into a committed Audiveris project (piano-two-voices), as the server's own tests do.
 */
final class FakeServer {
    private FakeServer() {}

    static final Path FIXTURE = Repo.root().resolve("eval/fixtures/omr/5.11.0/piano-two-voices");
    static final Path PDF = Repo.root().resolve("docs/upstream/audiveris/repro/satb-01.pdf");

    static final class Engine implements Tasks.Engine {
        @Override
        public AudiverisRun transcribe(List<Path> inputs, Path outDir, Map<String, String> constants,
                String ocrLanguages, Integer pages) {
            try {
                Files.createDirectories(outDir);
                Path omr = Files.copy(FIXTURE.resolve("book.omr"), outDir.resolve("book.omr"));
                Path mxl = Files.copy(FIXTURE.resolve("output.mxl"), outDir.resolve("book.mxl"));
                return new AudiverisRun(List.of("audiveris", "-batch"), 0, 1.0, "", List.of(omr), List.of(mxl), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public OmrBook readOmr(Path omr) {
            return OmrBookJson.load(Repo.readJson(Repo.parity("omr/piano-two-voices.book.json")));
        }

        @Override
        public double timeout(Integer pages) {
            return 60;
        }
    }

    /** A local connection to a server on {@code data} with the stand-in engine. */
    static Connection.Local start(Path data) {
        Settings env = Settings.fromEnv();
        Assumptions.assumeTrue(Compile.lilypondVersion(env) != null, "LilyPond not found (set LILYPOND_BIN)");
        Settings s = new Settings(data, env.lilypondBin(), null, env.lilypondTimeoutS(), 60, 1, 60, "eng",
                "inline", null, "1G");
        LilyscanServer server = new LilyscanServer(s, new Engine());
        int port = server.start("127.0.0.1", 0);
        return new Connection.Local(server, new Api("http://127.0.0.1:" + port), data);
    }

    static JsonNode waitFor(Api api, String id) throws Exception {
        long end = System.currentTimeMillis() + 180_000;
        JsonNode job = api.job(id);
        while (Labels.busy(job) && System.currentTimeMillis() < end) {
            Thread.sleep(200);
            job = api.job(id);
        }
        return job;
    }
}
