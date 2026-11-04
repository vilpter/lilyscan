package org.lilyscan.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.lilyscan.core.Versions;
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

    @Command(name = "versions", description = "Show the pinned and detected tool versions.")
    static final class VersionsCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            System.out.println("lilyscan     " + Versions.APP);
            System.out.println("audiveris    pinned " + Versions.AUDIVERIS);
            System.out.println("lilypond     pinned " + Versions.LILYPOND);
            return 0;
        }
    }

    @Command(name = "selftest", description = "Run a quick self-test.")
    static final class Selftest implements Callable<Integer> {
        @Override
        public Integer call() {
            System.out.println("lilyscan selftest: OK (skeleton)");
            return 0;
        }
    }
}
