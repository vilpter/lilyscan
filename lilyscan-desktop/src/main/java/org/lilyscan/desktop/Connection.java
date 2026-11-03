package org.lilyscan.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.server.LilyscanServer;

/**
 * Where the desktop app's jobs live: on this computer (the Lilyscan server started embedded on
 * a private {@code 127.0.0.1} port, with its own data folder) or on a Lilyscan server elsewhere.
 * Either way the app uses only the HTTP API.
 */
public sealed interface Connection extends AutoCloseable {

    Api api();

    /** For the window title: "this computer" or the server's address. */
    String label();

    @Override
    void close();

    /** The embedded server. */
    record Local(LilyscanServer server, Api api, Path dataDir) implements Connection {
        @Override
        public String label() {
            return "this computer";
        }

        @Override
        public void close() {
            server.close();
        }
    }

    /** A server somewhere else. */
    record Remote(Api api) implements Connection {
        @Override
        public String label() {
            return api.base().replaceFirst("^https?://", "");
        }

        @Override
        public void close() {}
    }

    /** Starts the embedded server on {@code dataDir} ({@code null}: {@link #defaultDataDir()}). */
    static Local local(Path dataDir) {
        Settings s = localSettings(System.getenv(), dataDir == null ? defaultDataDir() : dataDir);
        LilyscanServer server = new LilyscanServer(s);
        int port = server.start("127.0.0.1", 0);
        return new Local(server, new Api("http://127.0.0.1:" + port), s.dataDir());
    }

    /** Connects to a server and checks that it answers as Lilyscan. */
    static Remote remote(String url) throws java.io.IOException {
        String u = url.strip();
        if (!u.matches("(?i)https?://.*")) u = "http://" + u;
        Api api = new Api(u);
        if (!"ok".equals(api.health().path("status").asText())) {
            throw new java.io.IOException(u + " did not answer as a Lilyscan server");
        }
        return new Remote(api);
    }

    /**
     * The server's settings from the environment, with the jobs in {@code dataDir}. A LilyPond
     * bundled with an installed app ({@code <app>/lilypond/bin/lilypond}, JM10) is used when
     * {@code LILYPOND_BIN} is not set.
     */
    static Settings localSettings(Map<String, String> env, Path dataDir) {
        Settings s = Settings.fromEnv(env);
        String lilypond = s.lilypondBin();
        if (env.get("LILYPOND_BIN") == null || env.get("LILYPOND_BIN").isBlank()) {
            Path bundled = Bundle.lilypond();
            if (bundled != null) lilypond = bundled.toString();
        }
        return new Settings(dataDir.toAbsolutePath().normalize(), lilypond, s.audiverisBin(), s.lilypondTimeoutS(),
                s.audiverisTimeoutS(), s.audiverisTimeoutPerPageS(), s.audiverisStepTimeoutS(), s.ocrLanguages(),
                s.dispatch(), s.webDir(), s.audiverisMaxHeap());
    }

    /**
     * The per-user data folder: {@code %LOCALAPPDATA%\Lilyscan} on Windows,
     * {@code ~/Library/Application Support/Lilyscan} on macOS, {@code $XDG_DATA_HOME/lilyscan}
     * (default {@code ~/.local/share/lilyscan}) elsewhere.
     */
    static Path defaultDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            return (local != null ? Path.of(local) : home.resolve("AppData/Local")).resolve("Lilyscan");
        }
        if (os.contains("mac")) return home.resolve("Library/Application Support/Lilyscan");
        String xdg = System.getenv("XDG_DATA_HOME");
        return (xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".local/share")).resolve("lilyscan");
    }

    /** Tools shipped inside an installed app (JM10): found next to the app's jars. */
    final class Bundle {
        private Bundle() {}

        /** The app folder of a jpackage install, or {@code null} when run from a jar. */
        static Path appDir() {
            String app = System.getProperty("jpackage.app-path");
            if (app == null) return null;
            // <root>/bin/Lilyscan (Linux) -> <root>/lib/app
            Path root = Path.of(app).toAbsolutePath().getParent().getParent();
            for (Path candidate : new Path[] {root.resolve("lib/app"), root.resolve("app"), root.resolve("Contents/app")}) {
                if (Files.isDirectory(candidate)) return candidate;
            }
            return null;
        }

        static Path lilypond() {
            Path app = appDir();
            if (app == null) return null;
            for (String exe : new String[] {"lilypond/bin/lilypond", "lilypond/bin/lilypond.exe"}) {
                Path p = app.resolve(exe);
                if (Files.isExecutable(p)) return p;
            }
            return null;
        }

        static Path tessdata() {
            Path app = appDir();
            Path p = app == null ? null : app.resolve("tessdata");
            return p != null && Files.isDirectory(p) ? p : null;
        }
    }
}
