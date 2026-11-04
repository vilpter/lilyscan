package org.lilyscan.engine.audiveris;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.omr.OmrBookJson;
import org.lilyscan.core.omr.OmrException;
import org.lilyscan.core.runtime.Config;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.util.ProcessRunner;

/**
 * Stage 2: run the pinned Audiveris release in batch mode. Port of
 * {@code lilyscan/engine/audiveris/runner.py}.
 *
 * <p>The Python runner started the Audiveris launcher. Here Audiveris is bundled (D15), and each
 * run forks an {@link EngineWorker} JVM on Lilyscan's own classpath that runs Audiveris's batch CLI
 * with the same arguments (D16) — the same options, constants, outputs and log — so a crash or an
 * out-of-memory error loses only that run. Setting {@code AUDIVERIS_BIN} runs an external
 * Audiveris launcher instead, as the Python runner did.
 */
public final class AudiverisRunner {
    private AudiverisRunner() {}

    private static final Pattern CONSTANT_KEY = Pattern.compile("^[A-Za-z_][\\w.$]*$",
            Pattern.UNICODE_CHARACTER_CLASS);

    /** The program prefix that runs Audiveris's CLI: the forked worker, or an external launcher. */
    public static List<String> launcher(Settings s) {
        if (s.audiverisBin() != null) {
            return List.of(s.audiverisBin());
        }
        List<String> cmd = worker(s);
        cmd.add("audiveris");
        return cmd;
    }

    /** {@code java <options> -cp <our classpath> EngineWorker}: the forked worker JVM (D16). */
    static List<String> worker(Settings s) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xmx" + s.audiverisMaxHeap());
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-Dsun.java2d.uiScale=1");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(EngineWorker.class.getName());
        return cmd;
    }

    /** Batch transcription that saves the {@code .omr} book and exports MusicXML. */
    public static List<String> buildCommand(List<String> launcher, List<Path> inputs, Path outDir,
            Map<String, String> constants, String sheets) {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("at least one input file is required");
        }
        List<String> cmd = new ArrayList<>(launcher);
        cmd.addAll(List.of("-batch", "-transcribe", "-export", "-save", "-output", outDir.toString()));
        for (Map.Entry<String, String> e : new TreeMap<>(constants == null ? Map.of() : constants).entrySet()) {
            if (!CONSTANT_KEY.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("invalid Audiveris constant key: '" + e.getKey() + "'");
            }
            cmd.add("-constant");
            cmd.add(e.getKey() + "=" + e.getValue());
        }
        if (sheets != null && !sheets.isEmpty()) {
            cmd.add("-sheets");
            cmd.add(sheets);
        }
        cmd.add("--");
        for (Path p : inputs) {
            cmd.add(p.toString());
        }
        return cmd;
    }

    /** Seconds an engine run on {@code pages} pages may take. */
    public static double engineTimeout(Settings s, Integer pages) {
        return Math.max(s.audiverisTimeoutS(), (pages == null ? 0 : pages) * s.audiverisTimeoutPerPageS());
    }

    /**
     * The full command {@link #run} executes: the configured OCR languages ({@code ocrLanguages},
     * else the settings') and the step time limit are passed as Audiveris constants.
     */
    public static List<String> command(List<Path> inputs, Path outDir, Map<String, String> constants,
            String sheets, Settings s, String ocrLanguages) {
        Map<String, String> merged = new LinkedHashMap<>(constants == null ? Map.of() : constants);
        if (merged.containsKey(Config.AUDIVERIS_OCR_LANGUAGES_KEY)) {
            throw new IllegalArgumentException("set OCR languages with ocr_languages, not as a raw constant");
        }
        merged.put(Config.AUDIVERIS_OCR_LANGUAGES_KEY, Config.parseOcrLanguages(
                ocrLanguages != null && !ocrLanguages.isEmpty() ? ocrLanguages : s.ocrLanguages()));
        merged.putIfAbsent(Config.AUDIVERIS_STEP_TIMEOUT_KEY, Integer.toString(s.audiverisStepTimeoutS()));
        return buildCommand(launcher(s), inputs, outDir, merged, sheets);
    }

    /**
     * Transcribe {@code inputs} into {@code outDir}.
     *
     * <p>{@code pages} (when known) extends the time limit for long books: the run gets the larger
     * of the configured minimum and a per-page allowance. {@code sheets} is Audiveris's
     * {@code -sheets}, one comma-separated argument such as {@code "1-5,7-8"}; beware that with
     * it, Audiveris 5.11.0 exports every movement of the book to the same {@code .mvtnull} file.
     */
    public static AudiverisRun run(List<Path> inputs, Path outDir, Map<String, String> constants,
            String sheets, Settings s, String ocrLanguages, Integer pages) {
        List<String> cmd = command(inputs, outDir, constants, sheets, s, ocrLanguages);
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Proc p = exec(cmd, engineTimeout(s, pages));
        // On a timeout Python keeps only the output captured so far on stdout.
        String log = p.timedOut ? p.stdout : p.stdout + p.stderr;
        try {
            Files.writeString(outDir.resolve("audiveris.log"), log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new AudiverisRun(cmd, p.timedOut ? -1 : p.returncode, p.wallS, log,
                find(outDir, ".omr"), find(outDir, ".mxl"), p.timedOut);
    }

    /** The bundled (or external) Audiveris version, from its {@code -batch -version} output. */
    public static String audiverisVersion(Settings s) {
        List<String> cmd = new ArrayList<>(launcher(s));
        cmd.addAll(List.of("-batch", "-version"));
        Proc p;
        try {
            p = exec(cmd, 120);
        } catch (UncheckedIOException e) {
            return null;
        }
        if (p.timedOut) {
            return null;
        }
        Matcher m = Pattern.compile("\\b(\\d+\\.\\d+\\.\\d+)\\b").matcher(p.stdout + p.stderr);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Reads a saved {@code .omr} project in a forked worker (D16), so loading Audiveris's model
     * never touches this JVM. See {@link OmrReader#read} for the in-process equivalent.
     */
    public static OmrBook readOmr(Path omr, Settings s) {
        Path out = null;
        try {
            out = Files.createTempFile("lilyscan-book-", ".json");
            List<String> cmd = worker(s);
            cmd.addAll(List.of("read-omr", omr.toAbsolutePath().toString(), out.toString()));
            Proc p = exec(cmd, s.audiverisTimeoutS());
            if (p.timedOut) {
                throw new OmrException(omr.getFileName() + ": reading the project timed out");
            }
            if (p.returncode != 0) {
                String reason = p.stderr.lines().filter(l -> l.startsWith("OmrError: ")).findFirst()
                        .map(l -> l.substring("OmrError: ".length()))
                        .orElse(omr.getFileName() + ": the engine worker failed (exit " + p.returncode + ")");
                throw new OmrException(reason);
            }
            return OmrBookJson.load(IrJson.mapper().readTree(out.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // a temp file left behind is harmless
                }
            }
        }
    }

    /** A finished (or killed) process: exit status, wall time and its captured output. */
    private record Proc(int returncode, double wallS, String stdout, String stderr, boolean timedOut) {}

    private static Proc exec(List<String> cmd, double timeoutS) {
        ProcessRunner.Result r = ProcessRunner.runUnchecked(cmd, null, timeoutS);
        return new Proc(r.returncode(), r.wallS(), r.stdout(), r.stderr(), r.timedOut());
    }

    /** {@code sorted(out_dir.rglob("*" + suffix))}, ordered like Python paths (by components). */
    static List<Path> find(Path dir, String suffix) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted(AudiverisRunner::compareParts)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int compareParts(Path a, Path b) {
        Iterator<Path> ia = a.iterator();
        Iterator<Path> ib = b.iterator();
        while (ia.hasNext() && ib.hasNext()) {
            int c = ia.next().toString().compareTo(ib.next().toString());
            if (c != 0) {
                return c;
            }
        }
        return Boolean.compare(ia.hasNext(), ib.hasNext());
    }
}
