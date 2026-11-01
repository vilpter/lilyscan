package org.lilyscan.cli;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lilyscan.core.Versions;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.runtime.Config;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.engine.audiveris.AudiverisRunner;
import org.lilyscan.server.LilyscanServer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The {@code lilyscan} command. One main class dispatches every mode by argument, so one binary
 * does everything (as {@code audiveris} does); {@code serve} is the Docker image's entry point.
 */
@Command(
        name = "lilyscan",
        mixinStandardHelpOptions = true,
        version = "lilyscan " + Versions.APP,
        description = "Sheet music in, editable LilyPond out.",
        subcommands = {
            Main.Serve.class,
            Main.VersionsCmd.class,
            Main.Selftest.class,
        })
public final class Main implements Runnable {

    @Override
    public void run() {
        // No subcommand: show help.
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        System.exit(exit);
    }

    @Command(name = "serve", description = "Run the web server (the primary front end).")
    static final class Serve implements Callable<Integer> {
        @Option(names = "--port", description = "Port to listen on (default 8000).")
        int port = 8000;

        @Option(names = "--data", description = "Folder where jobs are kept (default ./data).")
        Path data = Path.of("data");

        @Override
        public Integer call() throws InterruptedException {
            new LilyscanServer(port, data).start();
            // Javalin/Jetty run on their own threads; block so Main.main's System.exit
            // does not tear the server down.
            new java.util.concurrent.CountDownLatch(1).await();
            return 0;
        }
    }

    /** {@code lilypond --version}'s version, or null (lilyscan/lilypond/compile.py). */
    static String lilypondVersion(Settings s) {
        try {
            Process p = new ProcessBuilder(s.lilypondBin(), "--version").redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            Matcher m = Pattern.compile("GNU LilyPond (\\S+)").matcher(out);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Command(name = "versions", description = "Pinned vs installed tool versions.")
    static final class VersionsCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            Settings s = Settings.fromEnv();
            JsonNodeFactory f = JsonNodeFactory.instance;
            ObjectNode root = f.objectNode();
            ObjectNode ly = root.putObject("lilypond");
            ly.put("pinned", Config.LILYPOND_VERSION);
            ly.put("found", lilypondVersion(s));
            ObjectNode au = root.putObject("audiveris");
            au.put("pinned", Config.AUDIVERIS_VERSION);
            au.put("found", AudiverisRunner.audiverisVersion(s));
            System.out.println(PyJson.write(root, PyJson.Style.JSON_DUMPS_COMPACT));
            return 0;
        }
    }

    /** The hello-world score of lilyscan/lilypond/compile.py. */
    static final String HELLO_WORLD = "\\version \"" + Config.LILYPOND_VERSION + "\"\n"
            + "\n"
            + "\\header { title = \"lilyscan self-test\" tagline = ##f }\n"
            + "\n"
            + "\\fixed c' {\n"
            + "  \\key g \\major\n"
            + "  \\time 3/4\n"
            + "  g4 a b | c'2. | b4 a g | d'2. \\bar \"|.\"\n"
            + "}\n";

    @Command(name = "selftest", description = "Compile a hello-world score with LilyPond.")
    static final class Selftest implements Callable<Integer> {
        @Option(names = "--out", description = "Keep the output in this folder.")
        Path out;

        @Override
        public Integer call() throws Exception {
            Settings s = Settings.fromEnv();
            Path tmp = Files.createTempDirectory("lilyscan-selftest");
            Path src = tmp.resolve("hello.ly");
            Files.writeString(src, HELLO_WORLD, StandardCharsets.UTF_8);
            Path dir = out != null ? out : tmp.resolve("out");
            Files.createDirectories(dir);
            // The full compile driver with diagnostics (Q1/Q2) is ported in JM2; this runs the
            // same LilyPond command and reports whether it produced the PDF.
            List<String> cmd = List.of(s.lilypondBin(), "--loglevel=WARNING",
                    "--output=" + dir.resolve("hello"), "--pdf", src.toString());
            boolean ok;
            try {
                Process p = new ProcessBuilder(cmd).inheritIO().start();
                ok = p.waitFor((long) (s.lilypondTimeoutS() * 1000), TimeUnit.MILLISECONDS)
                        && p.exitValue() == 0 && Files.isRegularFile(dir.resolve("hello.pdf"));
            } catch (IOException e) {
                System.err.println("error: cannot run " + s.lilypondBin() + ": " + e.getMessage());
                ok = false;
            }
            System.out.println("lilypond self-test: " + (ok ? "ok" : "FAILED"));
            return ok ? 0 : 1;
        }
    }
}
