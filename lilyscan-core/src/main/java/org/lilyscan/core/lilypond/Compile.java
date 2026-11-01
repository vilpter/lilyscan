package org.lilyscan.core.lilypond;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.lilyscan.core.runtime.Config;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.util.ProcessRunner;
import org.lilyscan.core.util.Py;

/** Run the LilyPond binary and parse its diagnostics. Port of lilyscan/lilypond/compile.py. */
public final class Compile {
    private Compile() {}

    private static final Pattern LOCATED = Pattern.compile(
            "^(?<file>.+?):(?<line>\\d+):(?<column>\\d+): "
                    + "(?<severity>fatal error|programming error|error|warning): (?<message>.*)$");
    private static final Pattern UNLOCATED = Pattern.compile(
            "^(?<severity>fatal error|programming error|error|warning): (?<message>.*)$");

    public static final List<String> FORMATS = List.of("pdf", "svg", "png");

    /** The self-test score (and the engine tests' engraved page). */
    public static final String HELLO_WORLD = "\\version \"" + Config.LILYPOND_VERSION + "\"\n"
            + "\n"
            + "\\header { title = \"lilyscan self-test\" tagline = ##f }\n"
            + "\n"
            + "\\fixed c' {\n"
            + "  \\key g \\major\n"
            + "  \\time 3/4\n"
            + "  g4 a b | c'2. | b4 a g | d'2. \\bar \"|.\"\n"
            + "}\n";

    /** One LilyPond diagnostic. */
    public record Diagnostic(String severity, String message, String file, Integer line, Integer column) {
        public Diagnostic(String severity, String message) {
            this(severity, message, null, null, null);
        }

        /** Errors stop LilyPond or leave the output incomplete. A "programming error" is an
         *  internal consistency complaint after which LilyPond continues and still writes its
         *  output; it is reported as a warning. */
        public boolean isError() {
            return severity.equals("error") || severity.equals("fatal error");
        }

        public boolean isInternal() {
            return severity.equals("programming error");
        }

        /** 2.26 says "bar check failed"; 2.24 said "barcheck failed". */
        public boolean isBarcheck() {
            return message.startsWith("bar check failed") || message.startsWith("barcheck failed");
        }
    }

    /** A compile: exit status, log, diagnostics and the files it wrote. */
    public record Result(int returncode, String log, List<Diagnostic> diagnostics, List<Path> outputs) {
        public boolean ok() {
            return returncode == 0 && errors().isEmpty();
        }

        public List<Diagnostic> errors() {
            return diagnostics.stream().filter(Diagnostic::isError).toList();
        }

        public List<Diagnostic> warnings() {
            return diagnostics.stream().filter(d -> !d.isError()).toList();
        }
    }

    public static List<Diagnostic> parseDiagnostics(String log) {
        List<Diagnostic> out = new ArrayList<>();
        for (String raw : Generate.splitLines(log)) {
            String line = Py.rstrip(raw);
            Matcher m = LOCATED.matcher(line);
            if (m.matches()) {
                out.add(new Diagnostic(m.group("severity"), m.group("message"), m.group("file"),
                        Integer.parseInt(m.group("line")), Integer.parseInt(m.group("column"))));
                continue;
            }
            m = UNLOCATED.matcher(line);
            if (m.matches()) {
                out.add(new Diagnostic(m.group("severity"), m.group("message")));
            }
        }
        return out;
    }

    public static Result compileLy(Path source, Path outDir, List<String> formats) {
        return compileLy(source, outDir, formats, false, List.of(), Settings.fromEnv());
    }

    /**
     * Compile {@code source} into {@code outDir}; output files share the source's stem.
     * {@code extraArgs} are passed to every run, e.g. {@code -dresolution=300} for PNGs.
     */
    public static Result compileLy(Path source, Path outDir, List<String> formats, boolean pointAndClick,
            List<String> extraArgs, Settings s) {
        Set<String> unknown = new TreeSet<>(formats);
        unknown.removeAll(FORMATS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unsupported formats: " + unknown);
        }
        // LilyPond runs from the source's directory, so relative paths must be resolved first.
        source = source.toAbsolutePath().normalize();
        outDir = outDir.toAbsolutePath().normalize();
        String stem = stem(source.getFileName().toString());
        Path base = outDir.resolve(stem);
        List<Diagnostic> locked = new ArrayList<>();
        try {
            Files.createDirectories(outDir);
            // Outputs of an earlier run must not pass for this one's.
            List<String> stale = new ArrayList<>(formats);
            stale.addAll(List.of("mid", "midi"));
            for (Path old : outputs(outDir, stem, stale)) {
                try {
                    Files.delete(old);
                } catch (IOException e) {
                    locked.add(new Diagnostic("error", "cannot replace " + old.getFileName() + ": is it open?"));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long started = System.currentTimeMillis();

        // The PostScript backend (PDF/PNG) and the SVG backend need separate runs.
        List<List<String>> runs = new ArrayList<>();
        List<String> ps = new ArrayList<>();
        for (String f : formats) {
            if (!f.equals("svg")) {
                ps.add("--" + f);
            }
        }
        if (!ps.isEmpty()) {
            runs.add(ps);
        }
        if (formats.contains("svg")) {
            runs.add(List.of("--svg"));
        }

        int returncode = 0;
        StringBuilder log = new StringBuilder();
        for (List<String> flags : runs) {
            List<String> cmd = new ArrayList<>(List.of(s.lilypondBin(), "--loglevel=WARNING", "--output=" + base));
            cmd.addAll(flags);
            cmd.addAll(extraArgs);
            if (!pointAndClick) {
                cmd.add("-dno-point-and-click");
            }
            cmd.add(source.toString());
            ProcessRunner.Result r;
            try {
                r = ProcessRunner.run(cmd, source.getParent(), s.lilypondTimeoutS());
            } catch (IOException e) {
                String msg = "cannot run " + s.lilypondBin() + ": " + e.getMessage();
                return new Result(-1, msg, List.of(new Diagnostic("fatal error", msg)), List.of());
            }
            if (r.timedOut()) {
                String msg = "lilypond timed out after " + Py.fixed(s.lilypondTimeoutS(), 0) + "s";
                return new Result(-1, msg, List.of(new Diagnostic("fatal error", msg)), List.of());
            }
            if (returncode == 0) {
                returncode = r.returncode();
            }
            log.append(r.stderr()).append(r.stdout());
            if (r.returncode() != 0) {
                break;
            }
        }

        // Each run reports the same source diagnostics; keep each one once.
        List<Diagnostic> diagnostics = new ArrayList<>(new LinkedHashSet<>(parseDiagnostics(log.toString())));
        diagnostics.addAll(locked);
        List<Path> written = new ArrayList<>();
        List<Path> candidates;
        try {
            candidates = outputs(outDir, stem, formats);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path p : candidates) {
            try {
                if (Files.getLastModifiedTime(p).toMillis() >= started - 1000) {
                    written.add(p);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (String fmt : formats) {
            if (written.stream().noneMatch(p -> p.getFileName().toString().endsWith("." + fmt))) {
                diagnostics.add(new Diagnostic("error", "LilyPond wrote no " + fmt.toUpperCase(java.util.Locale.ROOT) + " file"));
            }
        }
        return new Result(returncode, log.toString(), diagnostics, written);
    }

    /** Python {@code Path.stem}: the name without its last suffix. */
    static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String suffix(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    /** Files LilyPond writes for a source: {@code stem.ext} and {@code stem-*.ext} (pages, books). */
    static List<Path> outputs(Path outDir, String stem, List<String> suffixes) throws IOException {
        if (!Files.isDirectory(outDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(outDir)) {
            return s.filter(p -> {
                String name = p.getFileName().toString();
                String st = stem(name);
                return name.startsWith(stem) && suffixes.contains(suffix(name))
                        && (st.equals(stem) || st.startsWith(stem + "-"));
            }).sorted().toList();
        }
    }

    private static final Pattern VERSION = Pattern.compile("GNU LilyPond (\\S+)");

    /** {@code lilypond --version}'s version, or null. */
    public static String lilypondVersion(Settings s) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(List.of(s.lilypondBin(), "--version"), null, 30);
            if (r.timedOut()) {
                return null;
            }
            Matcher m = VERSION.matcher(r.stdout());
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Command prefix for a script shipped with LilyPond, such as {@code musicxml2ly}. Linux builds
     * ship executable scripts next to {@code lilypond}; Windows builds ship {@code <tool>.py} plus a
     * bundled {@code python.exe}.
     */
    public static List<String> lilypondTool(String tool, Settings s) {
        Path exe = ProcessRunner.which(s.lilypondBin());
        if (exe == null) {
            throw new IllegalStateException("LilyPond not found (" + s.lilypondBin() + ")");
        }
        Path binDir;
        try {
            binDir = exe.toRealPath().getParent();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path script = binDir.resolve(tool);
        if (Files.isRegularFile(script) && !windows) {
            return List.of(script.toString());
        }
        Path pyScript = binDir.resolve(tool + ".py");
        if (Files.isRegularFile(pyScript)) {
            for (String name : List.of("python.exe", "python3", "python")) {
                if (Files.isRegularFile(binDir.resolve(name))) {
                    return List.of(binDir.resolve(name).toString(), pyScript.toString());
                }
            }
            return List.of("python3", pyScript.toString());
        }
        if (Files.isRegularFile(script)) {
            return List.of("python3", script.toString());
        }
        throw new IllegalStateException(tool + " not found next to " + exe);
    }
}
