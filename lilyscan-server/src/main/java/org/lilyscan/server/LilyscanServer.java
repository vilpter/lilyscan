package org.lilyscan.server;

import io.javalin.Javalin;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lilyscan.core.Versions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The headless web server (D17): the primary front end. Serves the existing {@code services/web/}
 * single-page app and the JSON API on one process.
 *
 * <p>JM0 skeleton: a health endpoint and static-file serving. JM3 adds the full {@code /api/jobs}
 * and {@code /api/scores} surface and wires the pipeline through the SQLite job store (D18).
 */
public final class LilyscanServer {

    private static final Logger log = LoggerFactory.getLogger(LilyscanServer.class);

    private final int port;
    private final Path dataDir;

    public LilyscanServer(int port, Path dataDir) {
        this.port = port;
        this.dataDir = dataDir;
    }

    /** Builds and starts the Javalin app. Keeping the server alive is the caller's concern. */
    public void start() {
        try {
            Files.createDirectories(dataDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot create data dir " + dataDir, e);
        }

        Javalin app = Javalin.create(config -> {
            // services/web/ is served from the classpath in the packaged image (JM3).
            config.staticFiles.enableWebjars();
        });

        app.get("/api/health", ctx -> ctx.json(new Health("ok", Versions.APP, Versions.AUDIVERIS, Versions.LILYPOND)));

        app.start(port);
        log.info("Lilyscan server listening on port {} (data dir {})", port, dataDir);
    }

    /** Health payload. */
    public record Health(String status, String version, String audiveris, String lilypond) {}
}
