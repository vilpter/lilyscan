package org.lilyscan.core.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external program like Python's {@code subprocess.run(capture_output=True, text=True,
 * encoding="utf-8", errors="replace", timeout=...)}: stdout and stderr captured separately (via
 * temporary files, so a chatty program never blocks on a full pipe), decoded as UTF-8 with
 * malformed bytes replaced, and the process tree killed when it overstays.
 */
public final class ProcessRunner {
    private ProcessRunner() {}

    /** How a run ended. {@code returncode} is -1 when it timed out. */
    public record Result(int returncode, String stdout, String stderr, boolean timedOut, double wallS) {}

    /** Runs {@code cmd} in {@code cwd} (null: this process's), for at most {@code timeoutS} seconds. */
    public static Result run(List<String> cmd, Path cwd, double timeoutS) throws IOException {
        return run(cmd, cwd, timeoutS, java.util.Map.of());
    }

    /** {@link #run(List, Path, double)} with {@code env} added to this process's environment. */
    public static Result run(List<String> cmd, Path cwd, double timeoutS, java.util.Map<String, String> env)
            throws IOException {
        Path out = Files.createTempFile("lilyscan-proc-", ".out");
        Path err = Files.createTempFile("lilyscan-proc-", ".err");
        try {
            long start = System.nanoTime();
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile());
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            pb.environment().putAll(env);
            Process p = pb.start();
            boolean finished;
            try {
                finished = p.waitFor((long) (timeoutS * 1000), TimeUnit.MILLISECONDS);
                if (!finished) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    p.waitFor(30, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for " + cmd.get(0), e);
            }
            double wall = (System.nanoTime() - start) / 1e9;
            String so = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
            String se = new String(Files.readAllBytes(err), StandardCharsets.UTF_8);
            return new Result(finished ? p.exitValue() : -1, so, se, !finished, wall);
        } finally {
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    /** {@link #run}, wrapping I/O failures (e.g. the program does not exist). */
    public static Result runUnchecked(List<String> cmd, Path cwd, double timeoutS) {
        return runUnchecked(cmd, cwd, timeoutS, java.util.Map.of());
    }

    /** {@link #run(List, Path, double, java.util.Map)}, wrapping I/O failures. */
    public static Result runUnchecked(List<String> cmd, Path cwd, double timeoutS, java.util.Map<String, String> env) {
        try {
            return run(cmd, cwd, timeoutS, env);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code shutil.which}: the executable for {@code name} on the PATH, or null. */
    public static Path which(String name) {
        Path direct = Path.of(name);
        if (name.contains(java.io.File.separator) || name.contains("/")) {
            return Files.isExecutable(direct) ? direct.toAbsolutePath() : null;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // a temporary file left behind is harmless
        }
    }
}
